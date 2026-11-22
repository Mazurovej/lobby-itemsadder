package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.itemsadder;

import net.kyori.adventure.text.format.TextColor;
import net.minestom.server.item.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemsAdderLoaderTest {

    @Test
    void loadsFromItemsAdderFolder(@TempDir Path dir) throws IOException {
        Path storage = dir.resolve("storage");
        Files.createDirectories(storage);
        Files.writeString(storage.resolve("items_ids_cache.yml"), """
                DIAMOND_SWORD:
                  myitems:ruby_sword: 10001
                LEATHER_HELMET:
                  myitems:wizard_hat: 10002
                """);
        Files.writeString(storage.resolve("font_images_unicode_cache.yml"), """
                myitems:smile: "\\uE001"
                _iainternal:offset_-8: "\\uF808"
                """);

        Path contents = dir.resolve("contents/myitems/configs");
        Files.createDirectories(contents);
        Files.writeString(contents.resolve("items.yml"), """
                info:
                  namespace: myitems
                items:
                  ruby_sword:
                    display_name: "<red>Ruby Sword"
                    lore:
                      - "<gray>Forged in fire"
                    resource:
                      material: DIAMOND_SWORD
                  wizard_hat:
                    display_name: "<light_purple>Wizard Hat"
                    resource:
                      material: LEATHER_HELMET
                      item_model: "myitems:wizard_hat"
                    specific_properties:
                      armor:
                        color: "#7B2CBF"
                font_images:
                  star:
                    symbol: "\\uE002"
                """);

        ItemsAdderRegistry registry = ItemsAdderLoader.load(dir, null);

        assertEquals(2, registry.itemCount());
        ItemsAdderItem sword = registry.item("myitems:ruby_sword");
        assertNotNull(sword);
        assertEquals(Material.DIAMOND_SWORD, sword.material());
        assertEquals(10001, sword.customModelData());
        assertEquals("<red>Ruby Sword", sword.displayName());
        assertEquals(List.of("<gray>Forged in fire"), sword.lore());
        assertEquals(sword, registry.item("itemsadder:myitems:ruby_sword"));
        assertEquals(sword, registry.item("ia:myitems:ruby_sword"));
        assertEquals(sword, registry.item("ruby_sword"));

        ItemsAdderItem hat = registry.item("myitems:wizard_hat");
        assertNotNull(hat);
        assertEquals(Material.LEATHER_HELMET, hat.material());
        assertEquals(10002, hat.customModelData());
        assertEquals("myitems:wizard_hat", hat.itemModel());
        assertEquals(TextColor.fromHexString("#7B2CBF"), hat.color());

        ItemsAdderGlyph smile = registry.glyph("myitems:smile");
        assertNotNull(smile);
        assertEquals("\uE001", smile.symbol());
        assertNull(smile.font());
        assertEquals(smile, registry.glyph("smile"));

        ItemsAdderGlyph star = registry.glyph("myitems:star");
        assertNotNull(star);
        assertEquals("\uE002", star.symbol());

        List<ItemsAdderGlyph> offset = registry.offset(-8);
        assertEquals(1, offset.size());
        assertEquals("\uF808", offset.getFirst().symbol());
    }

    @Test
    void loadsFromGeneratedPackZip(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("generated.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            addZipEntry(out, "assets/myitems/font/icons.json", """
                    {
                      "providers": [
                        { "type": "bitmap", "file": "myitems:font/crown.png", "chars": ["\\uE100"] },
                        { "type": "space", "advances": { "\\uF801": -1, "\\uF808": -8, "\\uF821": 1, "\\uF828": 8 } }
                      ]
                    }
                    """);
            addZipEntry(out, "assets/minecraft/models/item/paper.json", """
                    {
                      "overrides": [
                        { "predicate": { "custom_model_data": 10050 }, "model": "myitems:item/custom_coin" }
                      ]
                    }
                    """);
            addZipEntry(out, "assets/myitems/items/modern_wand.json", """
                    { "model": { "type": "minecraft:model", "model": "myitems:item/modern_wand" } }
                    """);
        }

        ItemsAdderRegistry registry = ItemsAdderLoader.load(null, zip);

        ItemsAdderGlyph crown = registry.glyph("myitems:crown");
        assertNotNull(crown);
        assertEquals("\uE100", crown.symbol());
        assertEquals("myitems:icons", crown.font());

        ItemsAdderItem coin = registry.item("myitems:custom_coin");
        assertNotNull(coin);
        assertEquals(Material.PAPER, coin.material());
        assertEquals(10050, coin.customModelData());

        ItemsAdderItem wand = registry.item("myitems:modern_wand");
        assertNotNull(wand);
        assertEquals(Material.PAPER, wand.material());
        assertEquals("myitems:modern_wand", wand.itemModel());

        // -10 pixels = -8 + -1 + -1
        List<ItemsAdderGlyph> offset = registry.offset(-10);
        assertEquals(3, offset.size());
        assertEquals("\uF808", offset.get(0).symbol());
        assertEquals("\uF801", offset.get(1).symbol());
        assertEquals("\uF801", offset.get(2).symbol());

        String sha1 = ItemsAdderLoader.sha1Hex(zip);
        assertEquals(40, sha1.length());
        assertTrue(sha1.matches("[0-9a-f]{40}"));
    }

    @Test
    void combinesFolderAndAutoDetectedGeneratedZip(@TempDir Path dir) throws IOException {
        Path storage = dir.resolve("storage");
        Files.createDirectories(storage);
        Files.writeString(storage.resolve("font_images_unicode_cache.yml"), """
                myitems:rank_vip: "\\uE200"
                """);
        Path output = dir.resolve("output");
        Files.createDirectories(output);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(output.resolve("generated.zip")))) {
            addZipEntry(out, "assets/myitems/font/ranks.json", """
                    {
                      "providers": [
                        { "type": "bitmap", "file": "myitems:font/vip_badge.png", "chars": ["\\uE200"] }
                      ]
                    }
                    """);
        }

        ItemsAdderRegistry registry = ItemsAdderLoader.load(dir, null);
        assertFalse(registry.isEmpty());
        ItemsAdderGlyph rankVip = registry.glyph("myitems:rank_vip");
        assertNotNull(rankVip);
        assertEquals("\uE200", rankVip.symbol());
        assertEquals("myitems:ranks", rankVip.font(), "zip font key should enrich cache glyph sharing the same symbol");
    }

    private static void addZipEntry(ZipOutputStream out, String name, String content) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
