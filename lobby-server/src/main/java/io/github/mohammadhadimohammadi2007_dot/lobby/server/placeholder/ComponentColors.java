package io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the trailing {@link TextColor} (including full RGB hex colors such as {@code #74b9ff})
 * from a formatted prefix (such as a LuckPerms prefix), so chat names and UnlimitedNameTags can
 * inherit the exact color the prefix ends in.
 */
public final class ComponentColors {

    /**
     * Matches any color or reset token in a MiniMessage-normalized string (after
     * {@link FormattedText#legacyToMiniMessage(String)}).
     */
    private static final Pattern COLOR_OR_RESET_TAG = Pattern.compile(
            "<(reset|(?:/(?:color|c|#[0-9a-fA-F]{6}|[a-z_]+))|(?:(?:color:|c:)?(#[0-9a-fA-F]{6}))|(?:(?:color:|c:)?([a-z_]+)))>",
            Pattern.CASE_INSENSITIVE);

    private ComponentColors() {
    }

    /**
     * Returns the last {@link TextColor} in a prefix.
     *
     * <p>Checks the raw text first for a trailing color tag or legacy code (which MiniMessage drops
     * when no text follows it, e.g. {@code "&b[DEV] &#74b9ff"} or {@code ":dev: <#74b9ff> "}). If the
     * prefix ends with visible text (such as a {@code <gradient:...>}), falls back to the last colored
     * piece of the parsed {@link Component}.
     */
    public static @Nullable TextColor lastColor(@Nullable String rawPrefix, @Nullable Component parsedPrefix) {
        if (rawPrefix != null && !rawPrefix.isBlank()) {
            TrailingResult trailing = trailingColorInRaw(rawPrefix);
            if (trailing.found()) {
                return trailing.color();
            }
        }
        if (parsedPrefix == null) {
            return null;
        }
        TextColor nonBlank = lastInComponent(parsedPrefix, null, true);
        return nonBlank != null ? nonBlank : lastInComponent(parsedPrefix, null, false);
    }

    /** Returns the last {@link TextColor} in an already-parsed {@link Component}, or {@code null}. */
    public static @Nullable TextColor lastColor(@Nullable Component component) {
        return lastColor(null, component);
    }

    /**
     * Checks if {@code raw} ends with one or more color/reset tags (ignoring trailing whitespace and
     * formatting tags like {@code <bold>}). If visible text appears after the last color tag, returns
     * {@link TrailingResult#NONE} so the parsed component tree decides (which handles {@code <gradient>}).
     */
    private static TrailingResult trailingColorInRaw(String raw) {
        String normalized = FormattedText.legacyToMiniMessage(raw);
        int end = normalized.length();
        while (end > 0 && Character.isWhitespace(normalized.charAt(end - 1))) {
            end--;
        }
        if (end == 0) {
            return TrailingResult.NONE;
        }
        // Only inspect trailing tags when the last non-whitespace character closes a tag.
        if (normalized.charAt(end - 1) != '>') {
            return TrailingResult.NONE;
        }
        // Walk backwards over contiguous trailing tags (and spaces between them).
        int cursor = end;
        TextColor foundColor = null;
        boolean sawReset = false;
        while (cursor > 0) {
            while (cursor > 0 && Character.isWhitespace(normalized.charAt(cursor - 1))) {
                cursor--;
            }
            if (cursor == 0 || normalized.charAt(cursor - 1) != '>') {
                break;
            }
            int open = normalized.lastIndexOf('<', cursor - 1);
            if (open < 0) {
                break;
            }
            String tag = normalized.substring(open, cursor);
            Matcher matcher = COLOR_OR_RESET_TAG.matcher(tag);
            if (matcher.matches()) {
                String full = matcher.group(1).toLowerCase(Locale.ROOT);
                String hex = matcher.group(2);
                String named = matcher.group(3);
                if (hex != null) {
                    TextColor c = TextColor.fromHexString(hex);
                    if (c != null) {
                        foundColor = c;
                        break;
                    }
                } else if (named != null) {
                    NamedTextColor c = NamedTextColor.NAMES.value(named.toLowerCase(Locale.ROOT));
                    if (c != null) {
                        foundColor = c;
                        break;
                    }
                } else if (full.equals("reset")) {
                    sawReset = true;
                    break;
                }
            }
            cursor = open;
        }
        if (foundColor != null) {
            return new TrailingResult(true, foundColor);
        }
        if (sawReset) {
            return new TrailingResult(true, null);
        }
        return TrailingResult.NONE;
    }

    private static @Nullable TextColor lastInComponent(Component component, @Nullable TextColor inherited,
                                                       boolean ignoreBlank) {
        TextColor own = component.color() != null ? component.color() : inherited;
        TextColor result = null;
        if (component instanceof TextComponent text) {
            boolean matches = ignoreBlank ? !text.content().isBlank() : !text.content().isEmpty();
            if (matches && own != null) {
                result = own;
            }
        }
        for (Component child : component.children()) {
            TextColor found = lastInComponent(child, own, ignoreBlank);
            if (found != null) {
                result = found;
            }
        }
        return result;
    }

    private record TrailingResult(boolean found, @Nullable TextColor color) {
        static final TrailingResult NONE = new TrailingResult(false, null);
    }
}
