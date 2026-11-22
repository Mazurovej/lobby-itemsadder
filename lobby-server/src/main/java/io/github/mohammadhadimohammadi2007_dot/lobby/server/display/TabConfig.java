package io.github.mohammadhadimohammadi2007_dot.lobby.server.display;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.ConfigReader;
import net.minestom.server.color.TeamColor;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The {@code tab:} and {@code nametags:} sections of {@code display.yml}. They share one config record
 * because they share one team per player: the team decides both the tab order and the nametag.
 *
 * @param header           the lines above the tab list, joined with line breaks
 * @param footer           the lines below it
 * @param nameFormat       how each player is written in the tab list
 * @param sameInstanceOnly true to list only the players of the viewer's own lobby instance
 * @param groupOrder       lower-case group names, first sorted first, for groups without a weight
 * @param nametags         the nametag settings
 */
public record TabConfig(boolean enabled, int intervalTicks, TieredText header, TieredText footer, TieredText nameFormat,
                        boolean sameInstanceOnly, List<String> groupOrder, Nametags nametags) {

    private static final int MAX_INTERVAL_TICKS = 20 * 60;
    private static final int DEFAULT_INTERVAL_TICKS = 20;

    public TabConfig {
        groupOrder = List.copyOf(groupOrder);
    }

    /**
     * Nametags above players' heads.
     *
     * @param unlimited    true to render a TextDisplay passenger on 1.19.4+ clients (full RGB hex, gradients, glyphs, multi-line)
     * @param nameColor    the 16-colour team colour for legacy/vanilla nametags, or {@code null} for {@code auto}
     * @param hexNameColor the exact RGB/named colour for unlimited nametags, or {@code null} for {@code auto}
     * @param lines        optional multi-line format for unlimited nametags; empty uses {@code prefix + name + suffix}
     * @param yOffset      vertical translation above the player's head, in blocks
     * @param scale        text scale (1.0 = standard size)
     * @param background   ARGB background colour, or {@code null} for Minecraft's default translucent black
     * @param textShadow   true to draw a text shadow
     * @param seeThrough   true to show through blocks when not sneaking
     * @param viewDistance max viewing distance in blocks
     */
    public record Nametags(
            boolean enabled,
            boolean unlimited,
            TieredText prefix,
            TieredText suffix,
            @Nullable TeamColor nameColor,
            @Nullable net.kyori.adventure.text.format.TextColor hexNameColor,
            List<String> lines,
            double yOffset,
            double scale,
            @Nullable Integer background,
            boolean textShadow,
            boolean seeThrough,
            double viewDistance
    ) {
        public Nametags {
            lines = List.copyOf(lines);
        }
    }

    static TabConfig read(ConfigReader reader) {
        String show = reader.choice("tab.show", Set.of("server", "instance"));
        String mode = reader.string("nametags.mode", "unlimited").strip().toLowerCase(Locale.ROOT);
        boolean unlimited = true;
        if (!mode.equals("unlimited") && !mode.equals("vanilla")) {
            reader.reportInvalid("nametags.mode", mode, "unlimited or vanilla");
        } else {
            unlimited = mode.equals("unlimited");
        }

        String colorName = reader.string("nametags.name-color", "auto").strip();
        TeamColor teamCol = null;
        net.kyori.adventure.text.format.TextColor hexCol = null;
        if (!colorName.equalsIgnoreCase("auto")) {
            if (colorName.startsWith("#")) {
                hexCol = net.kyori.adventure.text.format.TextColor.fromHexString(colorName);
                if (hexCol == null) {
                    reader.reportInvalid("nametags.name-color", colorName,
                            "auto, a hex colour like \"#74b9ff\", or one of Minecraft's 16 colours");
                } else {
                    var nearest = net.kyori.adventure.text.format.NamedTextColor.nearestTo(hexCol);
                    teamCol = teamColor(net.kyori.adventure.text.format.NamedTextColor.NAMES.key(nearest));
                }
            } else {
                teamCol = teamColor(colorName);
                if (teamCol == null) {
                    reader.reportInvalid("nametags.name-color", colorName,
                            "auto, a hex colour like \"#74b9ff\", or one of Minecraft's 16 colours");
                } else {
                    hexCol = net.kyori.adventure.text.format.NamedTextColor.NAMES.value(
                            colorName.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
                }
            }
        }

        Integer background = parseBackground(reader, reader.string("nametags.background", "default"));
        Nametags nametags = new Nametags(
                reader.bool("nametags.enabled", true),
                unlimited,
                TieredText.of(reader.string("nametags.prefix", ""), optional(reader, "nametags.legacy-prefix")),
                TieredText.of(reader.string("nametags.suffix", ""), optional(reader, "nametags.legacy-suffix")),
                teamCol,
                hexCol,
                reader.stringList("nametags.lines", List.of()),
                reader.decimal("nametags.y-offset", -2.0, 5.0, 0.25),
                reader.decimal("nametags.scale", 0.1, 5.0, 1.0),
                background,
                reader.bool("nametags.text-shadow", false),
                reader.bool("nametags.see-through", true),
                reader.decimal("nametags.view-distance", 4.0, 128.0, 48.0));
        return new TabConfig(reader.bool("tab.enabled", true),
                reader.integer("tab.update-interval", SidebarConfig.MIN_INTERVAL_TICKS, MAX_INTERVAL_TICKS,
                        DEFAULT_INTERVAL_TICKS),
                block(reader, "tab.header", "tab.legacy-header"),
                block(reader, "tab.footer", "tab.legacy-footer"),
                TieredText.of(reader.string("tab.name-format", "%player_name%"),
                        optional(reader, "tab.legacy-name-format")),
                "instance".equals(show),
                reader.stringList("tab.group-order", List.of()).stream()
                        .map(group -> group.strip().toLowerCase(Locale.ROOT)).toList(),
                nametags);
    }

    private static @Nullable Integer parseBackground(ConfigReader reader, String raw) {
        String trimmed = raw.strip().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty() || trimmed.equals("default")) {
            return null;
        }
        if (trimmed.equals("transparent") || trimmed.equals("none")) {
            return 0;
        }
        String hex = trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
        if (hex.length() == 6 || hex.length() == 8) {
            try {
                long parsed = Long.parseLong(hex, 16);
                return hex.length() == 6 ? (int) (0xFF000000L | parsed) : (int) parsed;
            } catch (NumberFormatException ignored) {
                // Fall through to reportInvalid.
            }
        }
        reader.reportInvalid("nametags.background", raw, "default, transparent, #RRGGBB or #AARRGGBB");
        return null;
    }

    /** Several lines in the file, one text with line breaks. */
    private static TieredText block(ConfigReader reader, String path, String legacyPath) {
        String modern = String.join("\n", reader.stringList(path, List.of()));
        List<String> legacy = reader.stringList(legacyPath, List.of());
        return TieredText.of(modern, legacy.isEmpty() ? null : String.join("\n", legacy));
    }

    private static @Nullable String optional(ConfigReader reader, String path) {
        String value = reader.string(path, "");
        return value.isEmpty() ? null : value;
    }

    /** One of Minecraft's 16 colours by name ({@code dark_purple}), or {@code null}. */
    static @Nullable TeamColor teamColor(String name) {
        String wanted = name.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (TeamColor color : TeamColor.values()) {
            if (color.name().equals(wanted)) {
                return color;
            }
        }
        return null;
    }
}
