package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.itemsadder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.util.RGBLike;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads ItemsAdder custom items, font images (glyphs) and pixel-offset spaces from:
 * <ul>
 *   <li>An ItemsAdder folder ({@code storage/items_ids_cache.yml}, {@code storage/font_images_unicode_cache.yml},
 *       {@code contents/**&#47;*.yml})</li>
 *   <li>A generated ItemsAdder resource pack {@code .zip} ({@code assets/*&#47;font/*.json},
 *       {@code assets/minecraft/models/item/*.json}, {@code assets/*&#47;items/*.json})</li>
 * </ul>
 * Both sources can be combined: the folder is loaded first, then the zip enriches or fills in any missing entries
 * (including non-default font keys and 1.21.4+ {@code item_model} entries).
 */
public final class ItemsAdderLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(ItemsAdderLoader.class);
    private static final Pattern FONT_JSON = Pattern.compile("^assets/([^/]+)/font/(.+)\\.json$");
    private static final Pattern VANILLA_ITEM_MODEL_JSON = Pattern.compile("^assets/minecraft/models/item/([^/]+)\\.json$");
    private static final Pattern MODERN_ITEM_DEF_JSON = Pattern.compile("^assets/([^/]+)/items/(.+)\\.json$");
    private static final Pattern OFFSET_ID_PATTERN = Pattern.compile("^(?:[^:]+:)?offset_(-?\\d+)$");
    /** Guard against unexpectedly huge files inside a resource pack zip. */
    private static final long MAX_JSON_ENTRY_BYTES = 16L * 1024L * 1024L;

    private ItemsAdderLoader() {
    }

    /**
     * Loads an {@link ItemsAdderRegistry} from the given folder and/or zip file.
     *
     * @param dataDir ItemsAdder folder (may be {@code null} or absent)
     * @param packZip generated resource pack {@code .zip} (may be {@code null} or absent)
     */
    public static ItemsAdderRegistry load(@Nullable Path dataDir, @Nullable Path packZip) {
        MutableRegistry builder = new MutableRegistry();

        if (dataDir != null && Files.isDirectory(dataDir)) {
            loadFolder(dataDir, builder);
        }

        Path effectiveZip = packZip;
        if ((effectiveZip == null || !Files.isRegularFile(effectiveZip)) && dataDir != null) {
            Path autoZip = dataDir.resolve("output").resolve("generated.zip");
            if (Files.isRegularFile(autoZip)) {
                effectiveZip = autoZip;
            }
        }
        if (effectiveZip != null && Files.isRegularFile(effectiveZip)) {
            loadPackZip(effectiveZip, builder);
        }

        return builder.build();
    }

    /** Computes the lowercase 40-character SHA-1 hex digest of {@code file}, or {@code ""} on error. */
    public static String sha1Hex(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] bytes = digest.digest();
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            LOGGER.warn("ItemsAdder: could not compute SHA-1 of {}: {}", file, e.getMessage());
            return "";
        }
    }

    private static void loadFolder(Path dataDir, MutableRegistry out) {
        loadItemsIdsCache(firstExisting(dataDir, "storage/items_ids_cache.yml", "data/items_ids_cache.yml"), out);
        loadFontImagesCache(firstExisting(dataDir, "storage/font_images_unicode_cache.yml", "data/font_images_unicode_cache.yml"), out);

        Path contents = dataDir.resolve("contents");
        if (Files.isDirectory(contents)) {
            try (Stream<Path> walk = Files.walk(contents)) {
                walk.filter(Files::isRegularFile)
                        .filter(ItemsAdderLoader::isYamlFile)
                        .sorted()
                        .forEach(file -> loadContentYaml(file, out));
            } catch (IOException e) {
                LOGGER.warn("ItemsAdder: failed to scan {}: {}", contents, e.getMessage());
            }
        }
    }

    private static @Nullable Path firstExisting(Path base, String... relativePaths) {
        for (String rel : relativePaths) {
            Path candidate = base.resolve(rel);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    /**
     * Reads {@code storage/items_ids_cache.yml}:
     * <pre>
     * PAPER:
     *   myitems:ruby_sword: 10000
     * </pre>
     */
    private static void loadItemsIdsCache(@Nullable Path file, MutableRegistry out) {
        if (file == null) {
            return;
        }
        ConfigurationNode root = loadYaml(file);
        if (root == null) {
            return;
        }
        for (Map.Entry<Object, ? extends ConfigurationNode> materialEntry : root.childrenMap().entrySet()) {
            Material material = parseMaterial(String.valueOf(materialEntry.getKey()));
            if (material == null) {
                continue;
            }
            for (Map.Entry<Object, ? extends ConfigurationNode> itemEntry : materialEntry.getValue().childrenMap().entrySet()) {
                String id = String.valueOf(itemEntry.getKey()).strip().toLowerCase(Locale.ROOT);
                int cmd = itemEntry.getValue().getInt(-1);
                if (!id.isEmpty() && cmd >= 0) {
                    MutableItem item = out.item(id);
                    item.material = material;
                    item.customModelData = cmd;
                }
            }
        }
    }

    /**
     * Reads {@code storage/font_images_unicode_cache.yml}, supporting both flat ({@code myitems:smile: "\uE000"})
     * and nested ({@code myitems: { smile: "\uE000" }}) forms.
     */
    private static void loadFontImagesCache(@Nullable Path file, MutableRegistry out) {
        if (file == null) {
            return;
        }
        ConfigurationNode root = loadYaml(file);
        if (root == null) {
            return;
        }
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : root.childrenMap().entrySet()) {
            String key = String.valueOf(entry.getKey()).strip().toLowerCase(Locale.ROOT);
            ConfigurationNode value = entry.getValue();
            if (value.isMap()) {
                for (Map.Entry<Object, ? extends ConfigurationNode> nested : value.childrenMap().entrySet()) {
                    String symbol = nested.getValue().getString();
                    if (symbol != null && !symbol.isEmpty()) {
                        String id = key + ":" + String.valueOf(nested.getKey()).strip().toLowerCase(Locale.ROOT);
                        out.putGlyph(id, symbol, null);
                    }
                }
            } else {
                String symbol = value.getString();
                if (symbol != null && !symbol.isEmpty()) {
                    out.putGlyph(key, symbol, null);
                }
            }
        }
    }

    /** Reads one YAML file under {@code contents/}. */
    private static void loadContentYaml(Path file, MutableRegistry out) {
        ConfigurationNode root = loadYaml(file);
        if (root == null) {
            return;
        }
        String namespace = root.node("info", "namespace").getString("");
        namespace = namespace.strip().toLowerCase(Locale.ROOT);
        if (namespace.isEmpty()) {
            return;
        }

        ConfigurationNode itemsNode = root.node("items");
        if (itemsNode.isMap()) {
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : itemsNode.childrenMap().entrySet()) {
                String name = String.valueOf(entry.getKey()).strip().toLowerCase(Locale.ROOT);
                if (name.isEmpty()) {
                    continue;
                }
                String fullId = name.contains(":") ? name : namespace + ":" + name;
                ConfigurationNode itemNode = entry.getValue();
                MutableItem item = out.item(fullId);

                String displayName = itemNode.node("display_name").getString();
                if (displayName != null && !displayName.isBlank()) {
                    item.displayName = displayName;
                }
                List<String> lore = readStringList(itemNode.node("lore"));
                if (!lore.isEmpty()) {
                    item.lore = lore;
                }
                ConfigurationNode resource = itemNode.node("resource");
                String materialName = resource.node("material").getString();
                if (materialName != null && !materialName.isBlank()) {
                    Material material = parseMaterial(materialName);
                    if (material != null) {
                        item.material = material;
                    }
                }
                if (!resource.node("model_id").virtual()) {
                    int modelId = resource.node("model_id").getInt(-1);
                    if (modelId >= 0) {
                        item.customModelData = modelId;
                    }
                }
                String itemModel = resource.node("item_model").getString();
                if (itemModel != null && !itemModel.isBlank()) {
                    item.itemModel = itemModel.strip().toLowerCase(Locale.ROOT);
                }
                String colorStr = firstNonBlank(
                        itemNode.node("specific_properties", "armor", "color").getString(),
                        itemNode.node("specific_properties", "color").getString(),
                        itemNode.node("color").getString());
                if (colorStr != null) {
                    RGBLike parsed = parseColor(colorStr);
                    if (parsed != null) {
                        item.color = parsed;
                    }
                }
            }
        }

        ConfigurationNode fontImagesNode = root.node("font_images");
        if (fontImagesNode.isMap()) {
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : fontImagesNode.childrenMap().entrySet()) {
                String name = String.valueOf(entry.getKey()).strip().toLowerCase(Locale.ROOT);
                if (name.isEmpty()) {
                    continue;
                }
                String fullId = name.contains(":") ? name : namespace + ":" + name;
                ConfigurationNode imgNode = entry.getValue();
                String symbol = imgNode.node("symbol").getString();
                if (symbol != null && !symbol.isEmpty()) {
                    out.putGlyph(fullId, symbol, null);
                }
            }
        }
    }

    /** Reads fonts, custom_model_data overrides, and 1.21.4+ item_model definitions from a resource pack zip. */
    private static void loadPackZip(Path zipPath, MutableRegistry out) {
        try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getSize() > MAX_JSON_ENTRY_BYTES) {
                    continue;
                }
                String name = entry.getName();

                Matcher fontMatcher = FONT_JSON.matcher(name);
                if (fontMatcher.matches()) {
                    String fontNamespace = fontMatcher.group(1).toLowerCase(Locale.ROOT);
                    String fontName = fontMatcher.group(2).toLowerCase(Locale.ROOT);
                    String fontKey = fontNamespace + ":" + fontName;
                    JsonObject json = readJsonObject(zip, entry);
                    if (json != null) {
                        parseFontJson(fontNamespace, fontKey, json, out);
                    }
                    continue;
                }

                Matcher vanillaModelMatcher = VANILLA_ITEM_MODEL_JSON.matcher(name);
                if (vanillaModelMatcher.matches()) {
                    Material material = parseMaterial(vanillaModelMatcher.group(1));
                    if (material != null) {
                        JsonObject json = readJsonObject(zip, entry);
                        if (json != null) {
                            parseVanillaItemOverrides(material, json, out);
                        }
                    }
                    continue;
                }

                Matcher modernItemMatcher = MODERN_ITEM_DEF_JSON.matcher(name);
                if (modernItemMatcher.matches()) {
                    String ns = modernItemMatcher.group(1).toLowerCase(Locale.ROOT);
                    String itemPath = modernItemMatcher.group(2).toLowerCase(Locale.ROOT);
                    if (!ns.equals("minecraft")) {
                        String id = ns + ":" + itemPath;
                        MutableItem item = out.item(id);
                        if (item.itemModel == null) {
                            item.itemModel = id;
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("ItemsAdder: could not read resource pack zip {}: {}", zipPath, e.getMessage());
        }
    }

    private static void parseFontJson(String fontNamespace, String fontKey, JsonObject json, MutableRegistry out) {
        JsonArray providers = asArray(json.get("providers"));
        if (providers == null) {
            return;
        }
        String effectiveFont = ItemsAdderGlyph.DEFAULT_FONT.equals(fontKey) ? null : fontKey;
        for (JsonElement element : providers) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject provider = element.getAsJsonObject();
            String type = asString(provider.get("type"));
            if ("bitmap".equals(type)) {
                String file = asString(provider.get("file"));
                JsonArray chars = asArray(provider.get("chars"));
                if (file == null || chars == null || chars.isEmpty()) {
                    continue;
                }
                String symbol = firstCharSymbol(chars);
                if (symbol == null) {
                    continue;
                }
                // Update font key on any cache glyph that shares this symbol.
                out.updateFontBySymbol(symbol, effectiveFont);
                String derivedId = deriveGlyphId(fontNamespace, file);
                if (derivedId != null) {
                    out.putGlyphIfAbsent(derivedId, symbol, effectiveFont);
                }
            } else if ("space".equals(type)) {
                JsonObject advances = asObject(provider.get("advances"));
                if (advances == null) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> adv : advances.entrySet()) {
                    if (adv.getKey().isEmpty() || !adv.getValue().isJsonPrimitive()) {
                        continue;
                    }
                    try {
                        int pixels = adv.getValue().getAsInt();
                        if (pixels != 0) {
                            out.putSpaceOffset(pixels, adv.getKey(), effectiveFont);
                        }
                    } catch (NumberFormatException ignored) {
                        // Non-integer advance; skip.
                    }
                }
            }
        }
    }

    private static void parseVanillaItemOverrides(Material material, JsonObject json, MutableRegistry out) {
        JsonArray overrides = asArray(json.get("overrides"));
        if (overrides == null) {
            return;
        }
        for (JsonElement element : overrides) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject override = element.getAsJsonObject();
            JsonObject predicate = asObject(override.get("predicate"));
            String model = asString(override.get("model"));
            if (predicate == null || model == null || !predicate.has("custom_model_data")) {
                continue;
            }
            int cmd;
            try {
                cmd = predicate.get("custom_model_data").getAsInt();
            } catch (RuntimeException e) {
                continue;
            }
            String id = deriveItemIdFromModel(model);
            if (id == null) {
                continue;
            }
            MutableItem item = out.item(id);
            if (item.material == null) {
                item.material = material;
            }
            if (item.customModelData == null) {
                item.customModelData = cmd;
            }
        }
    }

    /** Derives {@code namespace:basename} from a bitmap file path like {@code myitems:font/icons/smile.png}. */
    private static @Nullable String deriveGlyphId(String fallbackNamespace, String file) {
        String clean = file.strip().toLowerCase(Locale.ROOT);
        String ns = fallbackNamespace;
        int colon = clean.indexOf(':');
        if (colon > 0) {
            ns = clean.substring(0, colon);
            clean = clean.substring(colon + 1);
        }
        if (clean.endsWith(".png")) {
            clean = clean.substring(0, clean.length() - 4);
        }
        int slash = clean.lastIndexOf('/');
        String base = slash >= 0 ? clean.substring(slash + 1) : clean;
        return base.isEmpty() ? null : ns + ":" + base;
    }

    /** Derives {@code namespace:id} from a model reference like {@code myitems:item/ruby_sword}. */
    private static @Nullable String deriveItemIdFromModel(String model) {
        String clean = model.strip().toLowerCase(Locale.ROOT);
        int colon = clean.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String ns = clean.substring(0, colon);
        if (ns.equals("minecraft")) {
            return null;
        }
        String path = clean.substring(colon + 1);
        if (path.startsWith("item/")) {
            path = path.substring("item/".length());
        } else if (path.startsWith("items/")) {
            path = path.substring("items/".length());
        }
        if (path.startsWith("auto_generated/")) {
            path = path.substring("auto_generated/".length());
        }
        return path.isEmpty() ? null : ns + ":" + path;
    }

    private static @Nullable String firstCharSymbol(JsonArray chars) {
        for (JsonElement el : chars) {
            String row = asString(el);
            if (row != null && !row.isEmpty()) {
                return row;
            }
        }
        return null;
    }

    private static @Nullable ConfigurationNode loadYaml(Path file) {
        try {
            return YamlConfigurationLoader.builder().path(file).build().load();
        } catch (IOException e) {
            LOGGER.warn("ItemsAdder: could not read {}: {}", file, e.getMessage());
            return null;
        }
    }

    private static @Nullable JsonObject readJsonObject(ZipFile zip, ZipEntry entry) {
        try (InputStream in = zip.getInputStream(entry);
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (IOException | JsonParseException e) {
            LOGGER.debug("ItemsAdder: skipping unreadable json {} in zip: {}", entry.getName(), e.getMessage());
            return null;
        }
    }

    private static @Nullable Material parseMaterial(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.strip().toLowerCase(Locale.ROOT);
        return Material.fromKey(key.contains(":") ? key : "minecraft:" + key);
    }

    private static @Nullable RGBLike parseColor(String raw) {
        String trimmed = raw.strip();
        if (trimmed.startsWith("#")) {
            return TextColor.fromHexString(trimmed);
        }
        if (trimmed.matches("[0-9A-Fa-f]{6}")) {
            return TextColor.fromHexString("#" + trimmed);
        }
        if (trimmed.contains(",")) {
            String[] parts = trimmed.split(",");
            if (parts.length == 3) {
                try {
                    int r = Math.clamp(Integer.parseInt(parts[0].strip()), 0, 255);
                    int g = Math.clamp(Integer.parseInt(parts[1].strip()), 0, 255);
                    int b = Math.clamp(Integer.parseInt(parts[2].strip()), 0, 255);
                    return TextColor.color(r, g, b);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return NamedTextColor.NAMES.value(trimmed.toLowerCase(Locale.ROOT));
    }

    private static List<String> readStringList(ConfigurationNode node) {
        if (node.virtual() || !node.isList()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (ConfigurationNode child : node.childrenList()) {
            String val = child.getString();
            if (val != null) {
                out.add(val);
            }
        }
        return out;
    }

    private static @Nullable String firstNonBlank(@Nullable String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static @Nullable JsonArray asArray(@Nullable JsonElement el) {
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : null;
    }

    private static @Nullable JsonObject asObject(@Nullable JsonElement el) {
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static @Nullable String asString(@Nullable JsonElement el) {
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static final class MutableItem {
        final String id;
        @Nullable Material material;
        @Nullable Integer customModelData;
        @Nullable String itemModel;
        @Nullable String displayName;
        List<String> lore = List.of();
        @Nullable RGBLike color;

        MutableItem(String id) {
            this.id = id;
        }

        ItemsAdderItem freeze() {
            return new ItemsAdderItem(id, material == null ? Material.PAPER : material,
                    customModelData, itemModel, displayName, lore, color);
        }
    }

    private static final class MutableRegistry {
        final Map<String, MutableItem> items = new LinkedHashMap<>();
        final Map<String, ItemsAdderGlyph> glyphs = new LinkedHashMap<>();
        final Map<String, List<String>> glyphIdsBySymbol = new HashMap<>();
        final Map<Integer, ItemsAdderGlyph> spaceOffsets = new HashMap<>();

        MutableItem item(String id) {
            return items.computeIfAbsent(id.toLowerCase(Locale.ROOT), MutableItem::new);
        }

        void putGlyph(String id, String symbol, @Nullable String font) {
            String normalized = id.toLowerCase(Locale.ROOT);
            ItemsAdderGlyph glyph = new ItemsAdderGlyph(normalized, symbol, font);
            glyphs.put(normalized, glyph);
            glyphIdsBySymbol.computeIfAbsent(symbol, ignored -> new ArrayList<>()).add(normalized);
            checkOffsetGlyph(normalized, glyph);
        }

        void putGlyphIfAbsent(String id, String symbol, @Nullable String font) {
            String normalized = id.toLowerCase(Locale.ROOT);
            if (!glyphs.containsKey(normalized)) {
                putGlyph(normalized, symbol, font);
            }
        }

        void updateFontBySymbol(String symbol, @Nullable String font) {
            if (font == null) {
                return;
            }
            List<String> ids = glyphIdsBySymbol.get(symbol);
            if (ids == null) {
                return;
            }
            for (String id : ids) {
                ItemsAdderGlyph existing = glyphs.get(id);
                if (existing != null && existing.font() == null) {
                    ItemsAdderGlyph updated = new ItemsAdderGlyph(existing.id(), existing.symbol(), font);
                    glyphs.put(id, updated);
                    checkOffsetGlyph(id, updated);
                }
            }
        }

        void putSpaceOffset(int pixels, String symbol, @Nullable String font) {
            spaceOffsets.putIfAbsent(pixels, new ItemsAdderGlyph("_iainternal:offset_" + pixels, symbol, font));
        }

        private void checkOffsetGlyph(String id, ItemsAdderGlyph glyph) {
            Matcher matcher = OFFSET_ID_PATTERN.matcher(id);
            if (matcher.matches()) {
                try {
                    int pixels = Integer.parseInt(matcher.group(1));
                    if (pixels != 0) {
                        spaceOffsets.put(pixels, glyph);
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore out-of-range offset numbers.
                }
            }
        }

        ItemsAdderRegistry build() {
            Map<String, ItemsAdderItem> frozenItems = new LinkedHashMap<>(items.size());
            for (Map.Entry<String, MutableItem> entry : items.entrySet()) {
                frozenItems.put(entry.getKey(), entry.getValue().freeze());
            }
            return new ItemsAdderRegistry(frozenItems, glyphs, spaceOffsets);
        }
    }
}
