package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.itemsadder;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Replaces ItemsAdder font-image shortcodes ({@code :name:}, {@code :namespace:name:}, {@code :offset_-8:})
 * in both trusted MiniMessage templates and untrusted player chat components.
 */
public final class ItemsAdderGlyphReplacer {

    private static final int MAX_TOKEN_LENGTH = 64;
    private static final String OFFSET_PREFIX = "offset_";
    /** Matches {@code :name:} or {@code :namespace:name:} in player chat. */
    private static final Pattern CHAT_SHORTCODE = Pattern.compile(":([A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)?):");

    private ItemsAdderGlyphReplacer() {
    }

    /**
     * Replaces {@code :glyph:}, {@code :namespace:glyph:} and {@code :offset_<n>:} in a trusted MiniMessage
     * template. Skips anything inside {@code <...>} MiniMessage tags and {@code %...%} placeholders so
     * colons in tags like {@code <click:run_command:/spawn>} are never touched.
     */
    public static String replaceInTemplate(String template, ItemsAdderRegistry registry) {
        if (registry.isEmpty() || template.indexOf(':') < 0) {
            return template;
        }
        StringBuilder out = new StringBuilder(template.length() + 16);
        int len = template.length();
        int i = 0;
        boolean inTag = false;
        char quote = 0;
        boolean inPlaceholder = false;

        while (i < len) {
            char c = template.charAt(i);
            if (inTag) {
                out.append(c);
                if (quote != 0) {
                    if (c == quote && (i == 0 || template.charAt(i - 1) != '\\')) {
                        quote = 0;
                    }
                } else if (c == '\'' || c == '"') {
                    quote = c;
                } else if (c == '>') {
                    inTag = false;
                }
                i++;
                continue;
            }
            if (c == '<') {
                inTag = true;
                out.append(c);
                i++;
                continue;
            }
            if (c == '%') {
                inPlaceholder = !inPlaceholder;
                out.append(c);
                i++;
                continue;
            }
            if (!inPlaceholder && c == ':') {
                TokenMatch match = matchToken(template, i, registry);
                if (match != null) {
                    out.append(match.replacement());
                    i = match.nextIndex();
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Replaces {@code :glyph:} and {@code :namespace:glyph:} inside an already-parsed player chat
     * {@link Component}. Never parses MiniMessage, so players cannot inject formatting or click events.
     */
    public static Component replaceInComponent(Component component, ItemsAdderRegistry registry) {
        if (registry.isEmpty()) {
            return component;
        }
        TextReplacementConfig config = TextReplacementConfig.builder()
                .match(CHAT_SHORTCODE)
                .replacement((match, builder) -> {
                    String token = match.group(1);
                    Component resolved = resolveComponent(token, registry);
                    return resolved != null ? resolved : Component.text(match.group(0));
                })
                .build();
        return component.replaceText(config);
    }

    /** Resolves {@code id} (glyph name, {@code namespace:name}, or {@code offset_<n>}) to MiniMessage, or {@code null}. */
    public static @Nullable String resolveMiniMessage(String rawToken, ItemsAdderRegistry registry) {
        String token = rawToken.strip().toLowerCase(Locale.ROOT);
        if (token.isEmpty()) {
            return null;
        }
        if (token.startsWith(OFFSET_PREFIX)) {
            Integer pixels = parseIntOrNull(token.substring(OFFSET_PREFIX.length()));
            if (pixels != null) {
                List<ItemsAdderGlyph> glyphs = registry.offset(pixels);
                if (!glyphs.isEmpty()) {
                    return joinMiniMessage(glyphs);
                }
            }
        }
        ItemsAdderGlyph glyph = registry.glyph(token);
        return glyph == null ? null : glyph.toMiniMessage();
    }

    /** Resolves {@code id} (glyph name, {@code namespace:name}, or {@code offset_<n>}) to a {@link Component}, or {@code null}. */
    public static @Nullable Component resolveComponent(String rawToken, ItemsAdderRegistry registry) {
        String token = rawToken.strip().toLowerCase(Locale.ROOT);
        if (token.isEmpty()) {
            return null;
        }
        if (token.startsWith(OFFSET_PREFIX)) {
            Integer pixels = parseIntOrNull(token.substring(OFFSET_PREFIX.length()));
            if (pixels != null) {
                List<ItemsAdderGlyph> glyphs = registry.offset(pixels);
                if (!glyphs.isEmpty()) {
                    Component combined = Component.empty();
                    for (ItemsAdderGlyph g : glyphs) {
                        combined = combined.append(g.toComponent());
                    }
                    return combined;
                }
            }
        }
        ItemsAdderGlyph glyph = registry.glyph(token);
        return glyph == null ? null : glyph.toComponent();
    }

    /**
     * Tries to match {@code :namespace:name:} first, then {@code :name:} starting at {@code start}.
     */
    private static @Nullable TokenMatch matchToken(String text, int start, ItemsAdderRegistry registry) {
        int len = text.length();
        int limit = Math.min(len, start + MAX_TOKEN_LENGTH + 2);
        int firstEnd = -1;
        int secondEnd = -1;

        for (int j = start + 1; j < limit; j++) {
            char ch = text.charAt(j);
            if (ch == ':') {
                if (firstEnd < 0) {
                    firstEnd = j;
                } else {
                    secondEnd = j;
                    break;
                }
            } else if (!isTokenChar(ch)) {
                break;
            }
        }

        if (secondEnd > firstEnd + 1) {
            String namespaced = text.substring(start + 1, secondEnd);
            String resolved = resolveMiniMessage(namespaced, registry);
            if (resolved != null) {
                return new TokenMatch(resolved, secondEnd + 1);
            }
        }
        if (firstEnd > start + 1) {
            String simple = text.substring(start + 1, firstEnd);
            String resolved = resolveMiniMessage(simple, registry);
            if (resolved != null) {
                return new TokenMatch(resolved, firstEnd + 1);
            }
        }
        return null;
    }

    private static boolean isTokenChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.';
    }

    private static @Nullable Integer parseIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String joinMiniMessage(List<ItemsAdderGlyph> glyphs) {
        StringBuilder sb = new StringBuilder();
        String currentFont = null;
        for (ItemsAdderGlyph g : glyphs) {
            String f = g.font();
            if (!java.util.Objects.equals(currentFont, f)) {
                if (currentFont != null) {
                    sb.append("</font>");
                }
                if (f != null) {
                    sb.append("<font:").append(f).append('>');
                }
                currentFont = f;
            }
            sb.append(g.symbol());
        }
        if (currentFont != null) {
            sb.append("</font>");
        }
        return sb.toString();
    }

    private record TokenMatch(String replacement, int nextIndex) {
    }
}
