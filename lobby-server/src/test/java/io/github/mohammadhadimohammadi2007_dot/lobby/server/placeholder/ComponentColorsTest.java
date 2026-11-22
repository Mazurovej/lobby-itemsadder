package io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ComponentColorsTest {

    @Test
    void extractsTrailingHexFromRawMiniMessageAndLegacyPrefixes() {
        TextColor expected = TextColor.fromHexString("#74b9ff");

        assertEquals(expected, ComponentColors.lastColor(":developer: <#74b9ff>", FormattedText.parse(":developer: <#74b9ff>")));
        assertEquals(expected, ComponentColors.lastColor(":developer: <#74b9ff> ", FormattedText.parse(":developer: <#74b9ff> ")));
        assertEquals(expected, ComponentColors.lastColor("&b[DEV] &#74b9ff", FormattedText.parse("&b[DEV] &#74b9ff")));
        assertEquals(expected, ComponentColors.lastColor("&b[DEV] &x&7&4&b&9&f&f ", FormattedText.parse("&b[DEV] &x&7&4&b&9&f&f ")));
        assertEquals(NamedTextColor.AQUA, ComponentColors.lastColor("[DEV] &b", FormattedText.parse("[DEV] &b")));
    }

    @Test
    void extractsLastColorFromVisibleColoredTextAndGradient() {
        Component colored = FormattedText.parse("<#ff5500>[ADMIN] ");
        assertEquals(TextColor.fromHexString("#ff5500"), ComponentColors.lastColor("<#ff5500>[ADMIN] ", colored));

        Component gradient = FormattedText.parse("<gradient:#ff0000:#0000ff>DEV</gradient>");
        assertEquals(TextColor.fromHexString("#0000ff"), ComponentColors.lastColor("<gradient:#ff0000:#0000ff>DEV</gradient>", gradient));
    }

    @Test
    void trailingResetReturnsNull() {
        assertNull(ComponentColors.lastColor("<red>[ADMIN]<reset>", FormattedText.parse("<red>[ADMIN]<reset>")));
        assertNull(ComponentColors.lastColor("&c[ADMIN]&r ", FormattedText.parse("&c[ADMIN]&r ")));
    }
}
