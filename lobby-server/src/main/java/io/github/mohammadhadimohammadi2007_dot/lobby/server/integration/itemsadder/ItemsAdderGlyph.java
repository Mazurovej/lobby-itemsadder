package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.itemsadder;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * One ItemsAdder font image / glyph (emoji, icon, rank badge, custom GUI background or space offset).
 *
 * @param id     full namespaced ID in lowercase, e.g. {@code myitems:smile}
 * @param symbol Unicode character(s) mapped to this glyph in the resource pack font
 * @param font   Adventure font key (e.g. {@code myitems:default}), or {@code null} for {@code minecraft:default}
 */
public record ItemsAdderGlyph(String id, String symbol, @Nullable String font) {

    public static final String DEFAULT_FONT = "minecraft:default";

    public ItemsAdderGlyph {
        id = Objects.requireNonNull(id, "id").strip().toLowerCase(Locale.ROOT);
        symbol = Objects.requireNonNull(symbol, "symbol");
        if (font != null) {
            font = font.strip().toLowerCase(Locale.ROOT);
            if (font.isEmpty() || font.equals(DEFAULT_FONT)) {
                font = null;
            }
        }
    }

    /** Short name without namespace (e.g. {@code smile} for {@code myitems:smile}). */
    public String shortName() {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    /** MiniMessage representation preserving custom font if this glyph is not in {@code minecraft:default}. */
    public String toMiniMessage() {
        return font == null ? symbol : "<font:" + font + ">" + symbol + "</font>";
    }

    /** Adventure component with this glyph's character and font applied (safe for untrusted chat). */
    public Component toComponent() {
        Component text = Component.text(symbol);
        if (font == null) {
            return text;
        }
        return text.font(Key.key(font));
    }
}
