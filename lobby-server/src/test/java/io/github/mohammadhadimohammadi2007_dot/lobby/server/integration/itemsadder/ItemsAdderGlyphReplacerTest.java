package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.itemsadder;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder.PlaceholderRegistry;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder.PlaceholderService;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemsAdderGlyphReplacerTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static ItemsAdderRegistry sampleRegistry() {
        return new ItemsAdderRegistry(
                Map.of(),
                Map.of(
                        "myitems:smile", new ItemsAdderGlyph("myitems:smile", "\uE001", null),
                        "myitems:vip", new ItemsAdderGlyph("myitems:vip", "\uE002", "myitems:ranks"),
                        "myitems:menu_bg", new ItemsAdderGlyph("myitems:menu_bg", "\uE003", "myitems:gui")
                ),
                Map.of(
                        -8, new ItemsAdderGlyph("_iainternal:offset_-8", "\uF808", "minecraft:default"),
                        -1, new ItemsAdderGlyph("_iainternal:offset_-1", "\uF801", "minecraft:default"),
                        8, new ItemsAdderGlyph("_iainternal:offset_8", "\uF828", "minecraft:default")
                )
        );
    }

    @Test
    void replacesShortcodesAndOffsetsInMiniMessageTemplateWithoutBreakingTags() {
        ItemsAdderRegistry registry = sampleRegistry();
        String input = "<click:run_command:/spawn><white>:offset_-8::myitems:menu_bg: :smile: :vip: :unknown:</click>";
        String replaced = ItemsAdderGlyphReplacer.replaceInTemplate(input, registry);

        assertEquals(
                "<click:run_command:/spawn><white>\uF808<font:myitems:gui>\uE003</font> \uE001 <font:myitems:ranks>\uE002</font> :unknown:</click>",
                replaced);
    }

    @Test
    void replacesGlyphsInPlayerChatComponentSafelyWithoutParsingMiniMessage() {
        ItemsAdderRegistry registry = sampleRegistry();
        Component chat = Component.text("Hello :smile: :myitems:vip: <red>not-parsed</red>");
        Component replaced = ItemsAdderGlyphReplacer.replaceInComponent(chat, registry);

        String plain = PLAIN.serialize(replaced);
        assertTrue(plain.contains("\uE001"));
        assertTrue(plain.contains("\uE002"));
        assertTrue(plain.contains("<red>not-parsed</red>"), "player MiniMessage tags must never be parsed");

        // Verify the custom font was applied to the vip glyph component.
        boolean foundRanksFont = hasFont(replaced, Key.key("myitems", "ranks"));
        assertTrue(foundRanksFont, "vip glyph should carry font myitems:ranks");
    }

    @Test
    void imgPlaceholdersAndShortcodesWorkTogetherInPlaceholderService() {
        ItemsAdderRegistry registry = sampleRegistry();
        PlaceholderRegistry placeholderRegistry = new PlaceholderRegistry();
        placeholderRegistry.register("img", new ItemsAdderPlaceholders(() -> registry));
        PlaceholderService placeholders = new PlaceholderService(placeholderRegistry);
        placeholders.textPreprocessor(s -> ItemsAdderGlyphReplacer.replaceInTemplate(s, registry));

        Component rendered = placeholders.render("<white>%img_offset_-8%%img_myitems:menu_bg% :vip:", null);
        assertEquals("\uF808\uE003 \uE002", PLAIN.serialize(rendered));
        assertTrue(hasFont(rendered, Key.key("myitems", "gui")));
        assertTrue(hasFont(rendered, Key.key("myitems", "ranks")));
    }

    private static boolean hasFont(Component component, Key expected) {
        if (expected.equals(component.font())) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasFont(child, expected)) {
                return true;
            }
        }
        return false;
    }
}
