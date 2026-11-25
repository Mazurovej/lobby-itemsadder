package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.modelengine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.modelengine.ModelBlueprint.Animation;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.modelengine.ModelBlueprint.Bone;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.modelengine.ModelBlueprint.BoneTrack;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.modelengine.ModelBlueprint.Interpolation;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.modelengine.ModelBlueprint.Keyframe;
import net.minestom.server.color.Color;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.CustomModelData;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
 * Loads ModelEngine R4 {@code .bbmodel} blueprints and resolves each bone to its Minecraft
 * {@link ItemStack} ({@code item_model} for 1.21.4+ or {@code custom_model_data} override) from:
 * <ul>
 *     <li>A ModelEngine folder ({@code blueprints/*.bbmodel}, {@code model_data.json}, {@code resource pack/})</li>
 *     <li>A standalone ModelEngine resource pack {@code .zip}</li>
 *     <li>A merged ItemsAdder resource pack {@code .zip} (automatically scanned when configured)</li>
 * </ul>
 */
public final class ModelEngineLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelEngineLoader.class);
    private static final Color WHITE_DYE = new Color(0xFFFFFF);
    private static final Pattern VANILLA_ITEM_MODEL_JSON = Pattern.compile("^assets/minecraft/models/item/([^/]+)\\.json$");
    private static final Pattern VANILLA_ITEM_DEF_JSON = Pattern.compile("^assets/minecraft/items/([^/]+)\\.json$");
    private static final Pattern MODERN_ITEM_DEF_JSON = Pattern.compile("^assets/([^/]+)/items/(.+)\\.json$");
    private static final Pattern ENTITY_MODEL_JSON = Pattern.compile("^assets/([^/]+)/models/(?:entity/)?([^/]+)/(.+)\\.json$");
    private static final long MAX_JSON_BYTES = 16L * 1024L * 1024L;

    /** Resolved item & display transform for one bone. */
    record BoneAsset(ItemStack item, byte displayType) {
    }

    private ModelEngineLoader() {
    }

    /**
     * Loads all blueprints from {@code dataDir} and resolves bone items from any available resource packs.
     *
     * @param dataDir         ModelEngine folder (contains {@code blueprints/})
     * @param packZips        resource pack {@code .zip} files to scan (e.g. ModelEngine zip + merged ItemsAdder zip)
     * @param defaultItemName fallback base material name (e.g. {@code "leather_horse_armor"})
     */
    public static Map<String, ModelBlueprint> load(@Nullable Path dataDir, List<Path> packZips, String defaultItemName) {
        Material defaultMaterial = resolveMaterial(defaultItemName);
        Map<String, BoneAsset> boneAssets = new HashMap<>();
        Map<String, Byte> displayTypes = new HashMap<>();

        // 1. Scan resource packs (ItemsAdder merged pack + ModelEngine pack) for bone item mappings.
        for (Path zip : packZips) {
            if (zip != null && Files.isRegularFile(zip)) {
                scanPackZip(zip, boneAssets, displayTypes);
            }
        }
        if (dataDir != null && Files.isDirectory(dataDir)) {
            Path unpackedPack = dataDir.resolve("resource pack");
            if (Files.isDirectory(unpackedPack)) {
                scanPackFolder(unpackedPack, boneAssets, displayTypes);
            }
            Path autoZip = dataDir.resolve("resource pack.zip");
            if (Files.isRegularFile(autoZip)) {
                scanPackZip(autoZip, boneAssets, displayTypes);
            }
            // Also read ModelEngine R4's model_data.json if present (fills any missing CMD entries).
            for (String candidate : List.of("model_data.json", "cache/model_data.json", "resource pack/model_data.json")) {
                Path dataFile = dataDir.resolve(candidate);
                if (Files.isRegularFile(dataFile)) {
                    scanModelDataJson(dataFile, defaultMaterial, boneAssets, displayTypes);
                }
            }
        }

        // Apply detected display types (HEAD vs FIXED) if model JSONs were scanned after items.
        if (!displayTypes.isEmpty()) {
            boneAssets.replaceAll((key, asset) -> {
                Byte detected = displayTypes.get(key);
                return detected != null && detected != asset.displayType()
                        ? new BoneAsset(asset.item(), detected)
                        : asset;
            });
        }

        // 2. Load .bbmodel blueprints from dataDir/blueprints/ (or dataDir/ directly).
        Map<String, ModelBlueprint> blueprints = new LinkedHashMap<>();
        if (dataDir == null || !Files.isDirectory(dataDir)) {
            return Map.of();
        }
        Path blueprintsDir = dataDir.resolve("blueprints");
        Path scanRoot = Files.isDirectory(blueprintsDir) ? blueprintsDir : dataDir;
        try (Stream<Path> walk = Files.walk(scanRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bbmodel"))
                    .sorted()
                    .forEach(file -> {
                        ModelBlueprint bp = parseBbmodel(file, boneAssets);
                        if (bp != null) {
                            blueprints.put(bp.id(), bp);
                        }
                    });
        } catch (IOException e) {
            LOGGER.warn("ModelEngine: failed to scan {}: {}", scanRoot, e.getMessage());
        }
        return Map.copyOf(blueprints);
    }

    /** Parses one Blockbench {@code .bbmodel} file into a {@link ModelBlueprint}. */
    static @Nullable ModelBlueprint parseBbmodel(Path file, Map<String, BoneAsset> boneAssets) {
        String fileName = file.getFileName().toString();
        String modelId = fileName.substring(0, fileName.length() - ".bbmodel".length()).toLowerCase(Locale.ROOT);
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                return null;
            }
            return parseBbmodelJson(modelId, parsed.getAsJsonObject(), boneAssets);
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("ModelEngine: could not parse {}: {}", file.getFileName(), e.getMessage());
            return null;
        }
    }

    static ModelBlueprint parseBbmodelJson(String modelId, JsonObject root, Map<String, BoneAsset> boneAssets) {
        String id = modelId.toLowerCase(Locale.ROOT);

        // Compute bounding box from cube elements (in pixels -> blocks).
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        if (root.has("elements") && root.get("elements").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("elements")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject obj = el.getAsJsonObject();
                Vec from = readVec3(obj.get("from"), null);
                Vec to = readVec3(obj.get("to"), null);
                if (from != null && to != null) {
                    minX = Math.min(minX, Math.min(from.x(), to.x()));
                    maxX = Math.max(maxX, Math.max(from.x(), to.x()));
                    minY = Math.min(minY, Math.min(from.y(), to.y()));
                    maxY = Math.max(maxY, Math.max(from.y(), to.y()));
                    minZ = Math.min(minZ, Math.min(from.z(), to.z()));
                    maxZ = Math.max(maxZ, Math.max(from.z(), to.z()));
                }
            }
        }
        double width = Double.isFinite(minX) ? Math.max(maxX - minX, maxZ - minZ) / 16.0 : 0.8;
        double height = Double.isFinite(minY) ? Math.max(0.5, (maxY - Math.min(0.0, minY)) / 16.0) : 1.8;
        double eyeHeight = height * 0.85;

        // Parse bone hierarchy from outliner in parent-first order.
        List<Bone> bones = new ArrayList<>();
        Map<String, Integer> uuidToIndex = new HashMap<>();
        Map<String, Integer> nameToIndex = new HashMap<>();
        if (root.has("outliner") && root.get("outliner").isJsonArray()) {
            parseOutlinerChildren(id, root.getAsJsonArray("outliner"), -1, Vec.ZERO,
                    boneAssets, bones, uuidToIndex, nameToIndex);
        }

        // Refine eyeHeight if there's a head bone, or height if there's a tag_name bone.
        for (Bone b : bones) {
            if (b.head()) {
                double headY = absolutePivotY(bones, uuidToIndex.get(b.uuid()));
                if (headY > 0.2) {
                    eyeHeight = headY;
                }
                break;
            }
        }

        // Parse animations.
        Map<String, Animation> animations = new LinkedHashMap<>();
        if (root.has("animations") && root.get("animations").isJsonArray()) {
            for (JsonElement animEl : root.getAsJsonArray("animations")) {
                if (!animEl.isJsonObject()) {
                    continue;
                }
                Animation anim = parseAnimation(animEl.getAsJsonObject(), uuidToIndex, nameToIndex);
                if (anim != null) {
                    animations.put(anim.name(), anim);
                }
            }
        }

        return new ModelBlueprint(id, width, height, eyeHeight, bones, animations);
    }

    private static double absolutePivotY(List<Bone> bones, int idx) {
        double y = 0;
        int cur = idx;
        while (cur >= 0) {
            Bone b = bones.get(cur);
            y += b.localOffset().y();
            cur = b.parentIndex();
        }
        return y;
    }

    private static void parseOutlinerChildren(String modelId, JsonArray array, int parentIndex, Vec parentOriginPx,
                                              Map<String, BoneAsset> boneAssets, List<Bone> out,
                                              Map<String, Integer> uuidToIndex, Map<String, Integer> nameToIndex) {
        for (JsonElement el : array) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject obj = el.getAsJsonObject();
            String rawName = stringOr(obj, "name", "");
            String boneName = rawName.toLowerCase(Locale.ROOT);
            String uuid = stringOr(obj, "uuid", boneName + "#" + out.size());
            Vec originPx = readVec3(obj.get("origin"), Vec.ZERO);
            Vec rotation = readVec3(obj.get("rotation"), Vec.ZERO);
            boolean visibleFlag = !obj.has("visibility") || obj.get("visibility").getAsBoolean();

            // Check if this bone has direct cube elements (string children).
            boolean hasCubes = false;
            JsonArray children = obj.has("children") && obj.get("children").isJsonArray()
                    ? obj.getAsJsonArray("children") : null;
            if (children != null) {
                for (JsonElement child : children) {
                    if (child.isJsonPrimitive()) {
                        hasCubes = true;
                        break;
                    }
                }
            }

            boolean isHelper = isHelperBone(boneName);
            boolean isHead = boneName.equals("head") || boneName.startsWith("h_");
            BoneAsset asset = (visibleFlag && !isHelper) ? lookupBoneAsset(boneAssets, modelId, boneName) : null;
            // Even if a bone has no direct cubes, if the pack explicitly has an item for it, render it;
            // conversely, if it has no asset in the pack, keep it as a pivot-only bone for child transforms.
            if (!hasCubes && asset == null) {
                asset = null;
            }

            // Blockbench X axis is mirrored relative to Minecraft entity space.
            Vec localOffset = new Vec(
                    -(originPx.x() - parentOriginPx.x()) / 16.0,
                    (originPx.y() - parentOriginPx.y()) / 16.0,
                    (originPx.z() - parentOriginPx.z()) / 16.0
            );

            int myIndex = out.size();
            out.add(new Bone(
                    uuid,
                    boneName,
                    parentIndex,
                    localOffset,
                    rotation,
                    isHead,
                    asset != null ? asset.item() : null,
                    asset != null ? asset.displayType() : ModelBlueprint.DISPLAY_HEAD
            ));
            uuidToIndex.put(uuid, myIndex);
            nameToIndex.putIfAbsent(boneName, myIndex);

            if (children != null) {
                parseOutlinerChildren(modelId, children, myIndex, originPx, boneAssets, out, uuidToIndex, nameToIndex);
            }
        }
    }

    private static boolean isHelperBone(String boneName) {
        return boneName.equals("hitbox")
                || boneName.startsWith("b_")
                || boneName.startsWith("tag_")
                || boneName.startsWith("p_")
                || boneName.startsWith("ob_")
                || boneName.startsWith("sub_");
    }

    private static @Nullable BoneAsset lookupBoneAsset(Map<String, BoneAsset> assets, String modelId, String boneName) {
        BoneAsset direct = assets.get(modelId + "/" + boneName);
        if (direct != null) {
            return direct;
        }
        // ModelEngine R4 strips behavior prefixes like "h_" when exporting bone JSON filenames.
        String stripped = stripBehaviorPrefix(boneName);
        if (!stripped.equals(boneName)) {
            return assets.get(modelId + "/" + stripped);
        }
        return null;
    }

    private static String stripBehaviorPrefix(String boneName) {
        int idx = boneName.indexOf('_');
        if (idx > 0 && idx <= 3 && idx + 1 < boneName.length()) {
            String prefix = boneName.substring(0, idx);
            if (prefix.equals("h") || prefix.equals("hi") || prefix.equals("g") || prefix.equals("seg")) {
                return boneName.substring(idx + 1);
            }
        }
        return boneName;
    }

    private static @Nullable Animation parseAnimation(JsonObject obj, Map<String, Integer> uuidToIndex,
                                                      Map<String, Integer> nameToIndex) {
        String name = stringOr(obj, "name", "").toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return null;
        }
        double length = obj.has("length") ? parseDouble(obj.get("length"), 0.0) : 0.0;
        String loopStr = stringOr(obj, "loop", "loop").toLowerCase(Locale.ROOT);
        boolean loop = !loopStr.equals("once") && !loopStr.equals("hold");

        Map<Integer, BoneTrack> tracks = new HashMap<>();
        if (obj.has("animators") && obj.get("animators").isJsonObject()) {
            JsonObject animators = obj.getAsJsonObject("animators");
            for (Map.Entry<String, JsonElement> entry : animators.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject animObj = entry.getValue().getAsJsonObject();
                Integer boneIndex = uuidToIndex.get(entry.getKey());
                if (boneIndex == null) {
                    String bName = stringOr(animObj, "name", "").toLowerCase(Locale.ROOT);
                    boneIndex = nameToIndex.get(bName);
                }
                if (boneIndex == null || !animObj.has("keyframes") || !animObj.get("keyframes").isJsonArray()) {
                    continue;
                }
                List<Keyframe> pos = new ArrayList<>();
                List<Keyframe> rot = new ArrayList<>();
                List<Keyframe> scl = new ArrayList<>();
                for (JsonElement kfEl : animObj.getAsJsonArray("keyframes")) {
                    if (!kfEl.isJsonObject()) {
                        continue;
                    }
                    JsonObject kf = kfEl.getAsJsonObject();
                    String channel = stringOr(kf, "channel", "").toLowerCase(Locale.ROOT);
                    double time = parseDouble(kf.get("time"), 0.0);
                    Interpolation interp = parseInterpolation(stringOr(kf, "interpolation", "linear"));
                    Vec value = readKeyframeValue(kf, channel.equals("scale") ? Vec.ONE : Vec.ZERO);
                    Keyframe frame = new Keyframe(time, value, interp);
                    switch (channel) {
                        case "position" -> pos.add(frame);
                        case "rotation" -> rot.add(frame);
                        case "scale" -> scl.add(frame);
                        default -> { }
                    }
                }
                Comparator<Keyframe> byTime = Comparator.comparingDouble(Keyframe::timeSeconds);
                pos.sort(byTime);
                rot.sort(byTime);
                scl.sort(byTime);
                if (!pos.isEmpty() || !rot.isEmpty() || !scl.isEmpty()) {
                    tracks.put(boneIndex, new BoneTrack(pos, rot, scl));
                }
            }
        }
        return new Animation(name, length, loop, tracks);
    }

    private static Interpolation parseInterpolation(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "step" -> Interpolation.STEP;
            case "catmullrom", "bezier" -> Interpolation.SMOOTH;
            default -> Interpolation.LINEAR;
        };
    }

    private static Vec readKeyframeValue(JsonObject kf, Vec fallback) {
        if (!kf.has("data_points") || !kf.get("data_points").isJsonArray()) {
            return fallback;
        }
        JsonArray pts = kf.getAsJsonArray("data_points");
        if (pts.isEmpty() || !pts.get(0).isJsonObject()) {
            return fallback;
        }
        JsonObject pt = pts.get(0).getAsJsonObject();
        return new Vec(
                parseDouble(pt.get("x"), fallback.x()),
                parseDouble(pt.get("y"), fallback.y()),
                parseDouble(pt.get("z"), fallback.z())
        );
    }

    // ---------------- Resource pack scanning ----------------

    private static void scanPackZip(Path zipPath, Map<String, BoneAsset> boneAssets, Map<String, Byte> displayTypes) {
        try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getSize() > MAX_JSON_BYTES) {
                    continue;
                }
                String path = entry.getName().replace('\\', '/');
                if (!path.endsWith(".json")) {
                    continue;
                }
                try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                    processPackJson(path, reader, boneAssets, displayTypes);
                } catch (Exception ignored) {
                }
            }
        } catch (IOException e) {
            LOGGER.warn("ModelEngine: could not read resource pack {}: {}", zipPath, e.getMessage());
        }
    }

    private static void scanPackFolder(Path root, Map<String, BoneAsset> boneAssets, Map<String, Byte> displayTypes) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .forEach(file -> {
                        String rel = root.relativize(file).toString().replace('\\', '/');
                        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                            processPackJson(rel, reader, boneAssets, displayTypes);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (IOException e) {
            LOGGER.warn("ModelEngine: could not scan {}: {}", root, e.getMessage());
        }
    }

    private static void processPackJson(String path, java.io.Reader reader,
                                        Map<String, BoneAsset> boneAssets, Map<String, Byte> displayTypes) {
        // 1. 1.21.4+ modern item definitions: assets/modelengine/items/<model>/<bone>.json
        Matcher modernItem = MODERN_ITEM_DEF_JSON.matcher(path);
        if (modernItem.matches()) {
            String ns = modernItem.group(1).toLowerCase(Locale.ROOT);
            String itemPath = modernItem.group(2).toLowerCase(Locale.ROOT);
            if (ns.equals("minecraft")) {
                // Check range_dispatch custom_model_data overrides (1.21.4+ format)
                Material base = resolveMaterial(itemPath);
                JsonElement el = JsonParser.parseReader(reader);
                if (el.isJsonObject()) {
                    extractRangeDispatchOverrides(el.getAsJsonObject(), base, boneAssets);
                }
                return;
            }
            if (ns.equals("modelengine") || itemPath.startsWith("entity/")) {
                String key = extractModelBoneKey(itemPath);
                if (key != null) {
                    ItemStack stack = buildItemModelStack(Material.LEATHER_HORSE_ARMOR, ns + ":" + itemPath);
                    boneAssets.putIfAbsent(key, new BoneAsset(stack, ModelBlueprint.DISPLAY_HEAD));
                }
            }
            return;
        }

        // 2. Classic custom_model_data overrides: assets/minecraft/models/item/<material>.json
        Matcher vanillaModel = VANILLA_ITEM_MODEL_JSON.matcher(path);
        if (vanillaModel.matches()) {
            Material base = resolveMaterial(vanillaModel.group(1));
            JsonElement el = JsonParser.parseReader(reader);
            if (!el.isJsonObject()) {
                return;
            }
            JsonObject obj = el.getAsJsonObject();
            if (!obj.has("overrides") || !obj.get("overrides").isJsonArray()) {
                return;
            }
            for (JsonElement ovEl : obj.getAsJsonArray("overrides")) {
                if (!ovEl.isJsonObject()) {
                    continue;
                }
                JsonObject ov = ovEl.getAsJsonObject();
                String modelRef = stringOr(ov, "model", "");
                if (!ov.has("predicate") || !ov.get("predicate").isJsonObject()) {
                    continue;
                }
                JsonObject pred = ov.getAsJsonObject("predicate");
                if (!pred.has("custom_model_data")) {
                    continue;
                }
                int cmd = (int) parseDouble(pred.get("custom_model_data"), -1);
                if (cmd < 0) {
                    continue;
                }
                String key = extractModelBoneKeyFromRef(modelRef);
                if (key != null) {
                    boneAssets.putIfAbsent(key, new BoneAsset(buildCmdStack(base, cmd), ModelBlueprint.DISPLAY_HEAD));
                }
            }
            return;
        }

        // 3. Entity bone model JSONs: detect display transform (HEAD vs FIXED).
        Matcher entityModel = ENTITY_MODEL_JSON.matcher(path);
        if (entityModel.matches()) {
            String ns = entityModel.group(1).toLowerCase(Locale.ROOT);
            if (!ns.equals("modelengine") && !path.contains("/entity/")) {
                return;
            }
            String modelName = entityModel.group(2).toLowerCase(Locale.ROOT);
            String boneName = entityModel.group(3).toLowerCase(Locale.ROOT);
            JsonElement el = JsonParser.parseReader(reader);
            if (el.isJsonObject() && el.getAsJsonObject().has("display")
                    && el.getAsJsonObject().get("display").isJsonObject()) {
                JsonObject display = el.getAsJsonObject().getAsJsonObject("display");
                if (!display.has("head") && display.has("fixed")) {
                    displayTypes.put(modelName + "/" + boneName, ModelBlueprint.DISPLAY_FIXED);
                } else if (display.has("head")) {
                    displayTypes.put(modelName + "/" + boneName, ModelBlueprint.DISPLAY_HEAD);
                }
            }
        }
    }

    private static void extractRangeDispatchOverrides(JsonObject root, Material base, Map<String, BoneAsset> out) {
        if (!root.has("model") || !root.get("model").isJsonObject()) {
            return;
        }
        walkItemDefNode(root.getAsJsonObject("model"), base, out);
    }

    private static void walkItemDefNode(JsonObject node, Material base, Map<String, BoneAsset> out) {
        String type = stringOr(node, "type", "");
        if (type.endsWith("range_dispatch") && node.has("entries") && node.get("entries").isJsonArray()) {
            for (JsonElement e : node.getAsJsonArray("entries")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject entry = e.getAsJsonObject();
                int threshold = (int) parseDouble(entry.get("threshold"), -1);
                if (threshold < 0 || !entry.has("model") || !entry.get("model").isJsonObject()) {
                    continue;
                }
                String modelRef = stringOr(entry.getAsJsonObject("model"), "model", "");
                String key = extractModelBoneKeyFromRef(modelRef);
                if (key != null) {
                    out.putIfAbsent(key, new BoneAsset(buildCmdStack(base, threshold), ModelBlueprint.DISPLAY_HEAD));
                }
            }
        }
    }

    /** Reads ModelEngine R4 {@code model_data.json} if present. */
    private static void scanModelDataJson(Path file, Material defaultMaterial,
                                          Map<String, BoneAsset> out, Map<String, Byte> displayTypes) {
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                return;
            }
            JsonObject root = parsed.getAsJsonObject();
            // R4 model_data.json maps "<model>:<bone>" or "<model>/<bone>" -> int CMD (or nested object).
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                String k = entry.getKey().toLowerCase(Locale.ROOT);
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
                    int cmd = entry.getValue().getAsInt();
                    String normalized = k.replace(':', '/');
                    String key = extractModelBoneKey(normalized);
                    if (key != null) {
                        out.putIfAbsent(key, new BoneAsset(buildCmdStack(defaultMaterial, cmd),
                                displayTypes.getOrDefault(key, ModelBlueprint.DISPLAY_HEAD)));
                    }
                } else if (entry.getValue().isJsonObject()) {
                    JsonObject sub = entry.getValue().getAsJsonObject();
                    for (Map.Entry<String, JsonElement> boneEntry : sub.entrySet()) {
                        if (boneEntry.getValue().isJsonPrimitive() && boneEntry.getValue().getAsJsonPrimitive().isNumber()) {
                            String key = k + "/" + boneEntry.getKey().toLowerCase(Locale.ROOT);
                            out.putIfAbsent(key, new BoneAsset(buildCmdStack(defaultMaterial, boneEntry.getValue().getAsInt()),
                                    displayTypes.getOrDefault(key, ModelBlueprint.DISPLAY_HEAD)));
                        }
                    }
                }
            }
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("ModelEngine: could not parse {}: {}", file.getFileName(), e.getMessage());
        }
    }

    /** Extracts {@code "<model>/<bone>"} from a model reference like {@code "modelengine:entity/knight/head"}. */
    static @Nullable String extractModelBoneKeyFromRef(String modelRef) {
        if (modelRef == null || modelRef.isBlank()) {
            return null;
        }
        String lower = modelRef.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        String ns = colon >= 0 ? lower.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? lower.substring(colon + 1) : lower;
        if (!ns.equals("modelengine") && !path.startsWith("entity/")) {
            return null;
        }
        return extractModelBoneKey(path);
    }

    private static @Nullable String extractModelBoneKey(String path) {
        String p = path.startsWith("entity/") ? path.substring("entity/".length()) : path;
        int slash = p.indexOf('/');
        if (slash <= 0 || slash + 1 >= p.length()) {
            return null;
        }
        return p;
    }

    private static ItemStack buildCmdStack(Material base, int customModelData) {
        ItemStack stack = ItemStack.of(base)
                .with(DataComponents.CUSTOM_MODEL_DATA,
                        new CustomModelData(List.of((float) customModelData), List.of(), List.of(), List.of()));
        if (isDyeable(base)) {
            stack = stack.with(DataComponents.DYED_COLOR, WHITE_DYE);
        }
        return stack;
    }

    private static ItemStack buildItemModelStack(Material base, String itemModel) {
        ItemStack stack = ItemStack.of(base).with(DataComponents.ITEM_MODEL, itemModel);
        if (isDyeable(base)) {
            stack = stack.with(DataComponents.DYED_COLOR, WHITE_DYE);
        }
        return stack;
    }

    private static boolean isDyeable(Material material) {
        return material == Material.LEATHER_HORSE_ARMOR
                || material == Material.LEATHER_HELMET
                || material == Material.LEATHER_CHESTPLATE
                || material == Material.LEATHER_LEGGINGS
                || material == Material.LEATHER_BOOTS
                || material == Material.WOLF_ARMOR;
    }

    private static Material resolveMaterial(String name) {
        if (name == null || name.isBlank()) {
            return Material.LEATHER_HORSE_ARMOR;
        }
        String key = name.strip().toLowerCase(Locale.ROOT);
        Material mat = Material.fromKey(key.contains(":") ? key : "minecraft:" + key);
        return mat != null ? mat : Material.LEATHER_HORSE_ARMOR;
    }

    private static @Nullable Vec readVec3(@Nullable JsonElement el, @Nullable Vec fallback) {
        if (el == null || !el.isJsonArray()) {
            return fallback;
        }
        JsonArray arr = el.getAsJsonArray();
        if (arr.size() < 3) {
            return fallback;
        }
        return new Vec(
                parseDouble(arr.get(0), 0.0),
                parseDouble(arr.get(1), 0.0),
                parseDouble(arr.get(2), 0.0)
        );
    }

    private static double parseDouble(@Nullable JsonElement el, double fallback) {
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return fallback;
        }
        try {
            if (el.getAsJsonPrimitive().isNumber()) {
                return el.getAsDouble();
            }
            String s = el.getAsString().trim();
            return s.isEmpty() ? fallback : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String stringOr(JsonObject obj, String key, String fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return fallback;
        }
        return obj.get(key).getAsString();
    }
}
