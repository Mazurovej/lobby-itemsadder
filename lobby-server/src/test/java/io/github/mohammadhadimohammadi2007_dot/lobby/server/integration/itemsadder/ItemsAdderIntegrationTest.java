package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.itemsadder;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.ConfigException;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.ConfigManager;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.ConfigSnapshot;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.IntegrationsConfig;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.RestartRequired;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.cosmetic.Cosmetic;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.cosmetic.CosmeticType;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.hologram.HologramCodec;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.hologram.HologramData;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.hologram.HologramType;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.hotbar.HotbarConfig;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.IntegrationStatus;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.item.ItemSpec;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.menu.MenuDefinition;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.npc.NpcCodec;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.npc.NpcData;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder.LobbyText;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder.PlaceholderRegistry;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder.PlaceholderService;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.player.OperatorPermissionService;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.player.OpList;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.CustomModelData;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
class ItemsAdderIntegrationTest {

    @Test
    void itemSpecParsesVanillaAndItemsAdder() {
        ItemSpec vanilla = ItemSpec.parse("diamond_sword");
        assertNotNull(vanilla);
        assertFalse(vanilla.isItemsAdder());
        assertEquals(Material.DIAMOND_SWORD, vanilla.material());
        assertEquals("diamond_sword", vanilla.serialize());

        ItemSpec iaFull = ItemSpec.parse("itemsadder:myitems:ruby_sword");
        assertNotNull(iaFull);
        assertTrue(iaFull.isItemsAdder());
        assertEquals("myitems:ruby_sword", iaFull.itemsAdderId());
        assertEquals("itemsadder:myitems:ruby_sword", iaFull.serialize());

        ItemSpec iaShort = ItemSpec.parse("ia:myitems:crown");
        assertNotNull(iaShort);
        assertTrue(iaShort.isItemsAdder());
        assertEquals("myitems:crown", iaShort.itemsAdderId());

        assertNull(ItemSpec.parse("not_a_real_material"));
        assertNull(ItemSpec.parse("itemsadder:"));
        assertNull(ItemSpec.parse(""));
    }

    @Test
    void serviceResolvesItemsAdderItemStackComponents(Env env, @TempDir Path dir) throws Exception {
        Path storage = dir.resolve("itemsadder/storage");
        Files.createDirectories(storage);
        Files.writeString(storage.resolve("items_ids_cache.yml"), """
                DIAMOND_SWORD:
                  myitems:ruby_sword: 10042
                """);
        Path contents = dir.resolve("itemsadder/contents/myitems");
        Files.createDirectories(contents);
        Files.writeString(contents.resolve("items.yml"), """
                info:
                  namespace: myitems
                items:
                  ruby_sword:
                    display_name: "<red>Ruby Sword"
                    resource:
                      material: DIAMOND_SWORD
                      item_model: "myitems:ruby_sword"
                """);

        ConfigManager configManager = new ConfigManager(dir);
        configManager.load();
        LobbyText text = new LobbyText(configManager, new PlaceholderService(new PlaceholderRegistry()));
        ItemsAdderService service = new ItemsAdderService(dir, text,
                new OperatorPermissionService(io.github.mohammadhadimohammadi2007_dot.lobby.server.TestOps.named()));

        IntegrationsConfig.ItemsAdder settings = new IntegrationsConfig.ItemsAdder(
                true, "itemsadder", "",
                new IntegrationsConfig.ItemsAdder.ResourcePack(
                        "https://example.com/pack.zip",
                        "0123456789abcdef0123456789abcdef01234567",
                        true, "<gold>Prompt", "<red>Kicked", false),
                new IntegrationsConfig.ItemsAdder.Chat(true, ""));

        IntegrationStatus status = service.reload(settings);
        assertEquals(IntegrationStatus.State.ACTIVE, status.state());
        assertTrue(service.resourcePack().active());

        ItemStack stack = service.build(ItemSpec.parse("itemsadder:myitems:ruby_sword"), 3);
        assertEquals(Material.DIAMOND_SWORD, stack.material());
        assertEquals(3, stack.amount());
        CustomModelData cmd = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        assertNotNull(cmd);
        assertEquals(List.of(10042.0f), cmd.floats());
        assertEquals("myitems:ruby_sword", stack.get(DataComponents.ITEM_MODEL));
    }

    @Test
    void configsAndCodecsSupportItemsAdderItemsAndLiveReload(@TempDir Path dir) throws IOException, ConfigException {
        ConfigManager manager = new ConfigManager(dir);
        ConfigSnapshot before = manager.load();
        assertFalse(before.integrations().itemsAdder().enabled());

        // Enable itemsadder in integrations.yml: RestartRequired must report NO restart needed.
        String integrationsYml = Files.readString(dir.resolve(ConfigManager.INTEGRATIONS_FILE))
                .replace("itemsadder:\n  enabled: false", "itemsadder:\n  enabled: true")
                .replace("url: \"\"", "url: \"https://cdn.example.com/ia-pack.zip\"");
        Files.writeString(dir.resolve(ConfigManager.INTEGRATIONS_FILE), integrationsYml);

        // Configure ItemsAdder items in menus.yml, hotbar.yml, and cosmetics.yml.
        Files.writeString(dir.resolve(ConfigManager.MENUS_FILE), """
                menus:
                  custom:
                    title: "<white>:menu_bg:"
                    rows: 3
                    fill: "itemsadder:_iainternal:icon_empty"
                    items:
                      sword:
                        slot: 13
                        material: "itemsadder:myitems:ruby_sword"
                        name: "<red>Play"
                      override:
                        slot: 14
                        material: "paper"
                        custom-model-data: 555
                        item-model: "myitems:custom_paper"
                """);
        Files.writeString(dir.resolve(ConfigManager.HOTBAR_FILE), """
                enabled: true
                clear-inventory: true
                items:
                  menu:
                    slot: 0
                    material: "itemsadder:myitems:compass_icon"
                    name: "<gold>Menu"
                visibility:
                  default: all
                  staff-permission: "lobby.visibility.staff"
                  cooldown: 3000
                """);
        Files.writeString(dir.resolve(ConfigManager.COSMETICS_FILE), """
                enabled: true
                view-distance: 32
                preview-seconds: 5
                show-locked: true
                limits:
                  per-viewer: 40
                  per-tick: 400
                hats:
                  wizard:
                    name: "<light_purple>Wizard Hat"
                    material: "itemsadder:myitems:wizard_hat"
                outfits:
                  knight:
                    name: "<gold>Knight"
                    helmet: "itemsadder:myitems:knight_helm"
                    chest: "diamond_chestplate"
                """);

        ConfigSnapshot after = manager.load();
        assertTrue(after.integrations().itemsAdder().enabled());
        assertEquals("https://cdn.example.com/ia-pack.zip", after.integrations().itemsAdder().resourcePack().url());
        assertEquals(List.of(), RestartRequired.changedOptions(before, after),
                "ItemsAdder changes should apply live on reload without requiring a restart");

        MenuDefinition menu = after.menus().menu("custom");
        assertNotNull(menu);
        assertNotNull(menu.fill());
        assertTrue(menu.fill().isItemsAdder());
        assertEquals("_iainternal:icon_empty", menu.fill().itemsAdderId());
        assertEquals("myitems:ruby_sword", menu.items().get(0).item().itemsAdderId());
        assertEquals(555, menu.items().get(1).item().customModelData());
        assertEquals("myitems:custom_paper", menu.items().get(1).item().itemModel());

        HotbarConfig.Item hotbarItem = after.hotbar().items().getFirst();
        assertTrue(hotbarItem.look().item().isItemsAdder());
        assertEquals("myitems:compass_icon", hotbarItem.look().item().itemsAdderId());

        Cosmetic hat = after.cosmetics().find(CosmeticType.HAT, "wizard");
        assertNotNull(hat);
        assertNotNull(hat.hat());
        assertEquals("myitems:wizard_hat", hat.hat().itemSpec().itemsAdderId());

        Cosmetic outfit = after.cosmetics().find(CosmeticType.OUTFIT, "knight");
        assertNotNull(outfit);
        assertNotNull(outfit.outfit());
        assertEquals("myitems:knight_helm", outfit.outfit().pieces().get(EquipmentSlot.HELMET).itemsAdderId());

        // HologramCodec round-trip with ItemsAdder item
        HologramData holo = new HologramData("sword_holo", HologramType.ITEM, new Pos(0, 64, 0), List.of());
        holo.itemSpec(ItemSpec.parse("itemsadder:myitems:ruby_sword"));
        var holoStore = new io.github.mohammadhadimohammadi2007_dot.lobby.server.data.YamlDataStore<>(
                dir.resolve("holograms.yml"), "Holograms", new HologramCodec());
        holoStore.save(java.util.Map.of("sword_holo", holo));
        var loadedHolos = holoStore.load();
        assertEquals(1, loadedHolos.size());
        assertNotNull(loadedHolos.get("sword_holo").itemSpec());
        assertEquals("myitems:ruby_sword", loadedHolos.get("sword_holo").itemSpec().itemsAdderId());

        // NpcCodec round-trip with ItemsAdder equipment
        NpcData npc = new NpcData("guard", new Pos(1, 64, 1), List.of("<gold>Guard"));
        npc.equipmentSpec(EquipmentSlot.MAIN_HAND, ItemSpec.parse("itemsadder:myitems:ruby_sword"));
        var npcStore = new io.github.mohammadhadimohammadi2007_dot.lobby.server.data.YamlDataStore<>(
                dir.resolve("npcs.yml"), "NPCs", new NpcCodec());
        npcStore.save(java.util.Map.of("guard", npc));
        var loadedNpcs = npcStore.load();
        assertEquals(1, loadedNpcs.size());
        assertEquals("myitems:ruby_sword",
                loadedNpcs.get("guard").equipmentSpecs().get(EquipmentSlot.MAIN_HAND).itemsAdderId());
    }
}
