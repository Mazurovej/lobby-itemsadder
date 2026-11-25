package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.mythicmobs;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.item.ItemSpec;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.EquipmentSlot;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads MythicMobs mob definitions from {@code <mythicmobs>/Mobs/**&#47;*.yml}.
 * Extracts {@code Type}, {@code Display}, {@code Options.Scale}, {@code Model},
 * ModelEngine R4 {@code model{mid=...;scale=...}} skills, and {@code Equipment}.
 */
public final class MythicMobsLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(MythicMobsLoader.class);
    /** Matches {@code model{mid=knight;scale=1.5}}, {@code meg:model{id="knight"}}, or {@code state{mid=...}}. */
    private static final Pattern MODEL_SKILL = Pattern.compile(
            "(?:(?:meg|modelengine):)?(?:model|state)\\s*\\{([^}]*)}", Pattern.CASE_INSENSITIVE);
    private static final Pattern SKILL_PARAM = Pattern.compile(
            "([a-zA-Z0-9_]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^;\\s}]+))");

    private MythicMobsLoader() {
    }

    /** Loads every mob definition from {@code dataDir/Mobs/} (or {@code dataDir/mobs/}, or {@code dataDir}). */
    public static Map<String, MythicMobDefinition> load(@Nullable Path dataDir) {
        if (dataDir == null || !Files.isDirectory(dataDir)) {
            return Map.of();
        }
        Path mobsDir = dataDir.resolve("Mobs");
        if (!Files.isDirectory(mobsDir)) {
            mobsDir = dataDir.resolve("mobs");
        }
        Path scanRoot = Files.isDirectory(mobsDir) ? mobsDir : dataDir;

        Map<String, MythicMobDefinition> result = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(scanRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".yml") || n.endsWith(".yaml");
                    })
                    .sorted()
                    .forEach(file -> loadFile(file, result));
        } catch (IOException e) {
            LOGGER.warn("MythicMobs: failed to scan {}: {}", scanRoot, e.getMessage());
        }
        return Map.copyOf(result);
    }

    static void loadFile(Path file, Map<String, MythicMobDefinition> out) {
        try {
            ConfigurationNode root = YamlConfigurationLoader.builder().path(file).build().load();
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : root.childrenMap().entrySet()) {
                String id = String.valueOf(entry.getKey()).strip();
                if (id.isEmpty()) {
                    continue;
                }
                MythicMobDefinition def = parseMob(id, entry.getValue());
                if (def != null) {
                    out.put(id.toLowerCase(Locale.ROOT), def);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("MythicMobs: could not read {}: {}", file.getFileName(), e.getMessage());
        }
    }

    static @Nullable MythicMobDefinition parseMob(String id, ConfigurationNode node) {
        if (!node.isMap()) {
            return null;
        }
        EntityType entityType = parseEntityType(node.node("Type").getString("PLAYER"));
        String display = node.node("Display").getString("").strip();
        double scale = node.node("Options", "Scale").getDouble(
                node.node("Options", "Size").getDouble(1.0));
        if (scale <= 0) {
            scale = 1.0;
        }

        // Direct Model: field (either "Model: knight" or "Model: { Id: knight, Scale: 1.2 }")
        String modelId = "";
        ConfigurationNode modelNode = node.node("Model");
        if (!modelNode.virtual()) {
            if (modelNode.isMap()) {
                modelId = firstNonBlank(
                        modelNode.node("Id").getString(""),
                        modelNode.node("Mid").getString(""),
                        modelNode.node("Model").getString(""));
                double mScale = modelNode.node("Scale").getDouble(-1.0);
                if (mScale > 0) {
                    scale = mScale;
                }
            } else {
                modelId = modelNode.getString("").strip();
            }
        }

        // Parse Skills: for ModelEngine R4 "- model{mid=knight;scale=1.2} @self ~onSpawn"
        ConfigurationNode skills = node.node("Skills");
        if (skills.isList()) {
            for (ConfigurationNode line : skills.childrenList()) {
                String raw = line.getString("");
                Matcher m = MODEL_SKILL.matcher(raw);
                if (m.find()) {
                    Map<String, String> params = parseParams(m.group(1));
                    String mid = firstNonBlank(
                            params.getOrDefault("mid", ""),
                            params.getOrDefault("m", ""),
                            params.getOrDefault("id", ""),
                            params.getOrDefault("model", ""));
                    if (!mid.isBlank() && modelId.isBlank()) {
                        modelId = mid.strip();
                    }
                    String sVal = params.get("scale");
                    if (sVal != null) {
                        try {
                            double parsedScale = Double.parseDouble(sVal);
                            if (parsedScale > 0) {
                                scale = parsedScale;
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        }

        Map<EquipmentSlot, ItemSpec> equipment = parseEquipment(node.node("Equipment"));
        return new MythicMobDefinition(
                id,
                entityType,
                display,
                scale,
                modelId.toLowerCase(Locale.ROOT).replace(' ', '_'),
                "idle",
                equipment
        );
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return "";
    }

    private static Map<String, String> parseParams(String inside) {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher matcher = SKILL_PARAM.matcher(inside);
        while (matcher.find()) {
            String val = matcher.group(2) != null ? matcher.group(2)
                    : (matcher.group(3) != null ? matcher.group(3) : matcher.group(4));
            if (val != null) {
                map.put(matcher.group(1).toLowerCase(Locale.ROOT), val.strip());
            }
        }
        return map;
    }

    private static Map<EquipmentSlot, ItemSpec> parseEquipment(ConfigurationNode node) {
        Map<EquipmentSlot, ItemSpec> out = new EnumMap<>(EquipmentSlot.class);
        if (node.isList()) {
            // Standard MythicMobs format: "- IRON_SWORD HAND" or "- DIAMOND_HELMET:0 HEAD"
            for (ConfigurationNode item : node.childrenList()) {
                String raw = item.getString("").strip();
                if (raw.isEmpty()) {
                    continue;
                }
                String[] parts = raw.split("\\s+");
                if (parts.length < 2) {
                    continue;
                }
                EquipmentSlot slot = parseSlot(parts[parts.length - 1]);
                ItemSpec spec = ItemSpec.parse(parts[0]);
                if (slot != null && spec != null) {
                    out.put(slot, spec);
                }
            }
        } else if (node.isMap()) {
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
                EquipmentSlot slot = parseSlot(String.valueOf(entry.getKey()));
                ItemSpec spec = ItemSpec.parse(entry.getValue().getString(""));
                if (slot != null && spec != null) {
                    out.put(slot, spec);
                }
            }
        }
        return out;
    }

    private static @Nullable EquipmentSlot parseSlot(String raw) {
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "hand", "mainhand", "main_hand" -> EquipmentSlot.MAIN_HAND;
            case "offhand", "off_hand" -> EquipmentSlot.OFF_HAND;
            case "head", "helmet" -> EquipmentSlot.HELMET;
            case "chest", "chestplate" -> EquipmentSlot.CHESTPLATE;
            case "legs", "leggings" -> EquipmentSlot.LEGGINGS;
            case "feet", "boots" -> EquipmentSlot.BOOTS;
            default -> null;
        };
    }

    private static EntityType parseEntityType(String raw) {
        if (raw == null || raw.isBlank()) {
            return EntityType.PLAYER;
        }
        String key = raw.strip().toLowerCase(Locale.ROOT);
        // Common MythicMobs aliases
        key = switch (key) {
            case "baby_zombie" -> "zombie";
            case "wither_skeleton" -> "wither_skeleton";
            case "pig_zombie", "zombie_pigman" -> "zombified_piglin";
            default -> key;
        };
        EntityType type = EntityType.fromKey(key.contains(":") ? key : "minecraft:" + key);
        return type != null ? type : EntityType.PLAYER;
    }
}
