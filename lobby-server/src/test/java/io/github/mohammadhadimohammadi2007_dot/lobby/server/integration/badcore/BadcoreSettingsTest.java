package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.ConfigManager;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.IntegrationsConfig;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore.BadcoreSettingsRepository.DuelsSetting;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore.BadcoreSettingsRepository.DuelsState;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore.BadcoreSettingsRepository.QoaState;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore.BadcoreSettingsRepository.TimePreset;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.player.PermissionService;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.player.Permissions;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.player.PlayerMeta;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventFilter;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.trait.PlayerEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.common.PluginMessagePacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
class BadcoreSettingsTest {

    @TempDir
    Path dir;

    private record FakePermissions(Map<String, Set<String>> granted) implements PermissionService {
        @Override
        public String name() {
            return "test";
        }

        @Override
        public boolean hasPermission(Player player, String permission) {
            Set<String> set = granted.getOrDefault(player.getUsername(), Set.of());
            return set.contains("*") || set.contains(permission);
        }

        @Override
        public PlayerMeta meta(Player player) {
            return PlayerMeta.EMPTY;
        }
    }

    @Test
    void smallCapsPreservesMiniMessageTags() {
        assertEquals("<dark_gray>ꜱᴇᴛᴛɪɴɢꜱ</dark_gray>",
                BadcoreSettingsGuiStyle.toSmallCaps("<dark_gray>Settings</dark_gray>"));
        assertEquals("ᴅᴜᴇʟꜱ ꜱᴇᴛᴛɪɴɢꜱ", BadcoreSettingsGuiStyle.toSmallCaps("Duels Settings"));
    }

    @Test
    void configReadsDefaultsAndCustomValues() throws Exception {
        ConfigManager defaults = new ConfigManager(dir.resolve("defaults"));
        defaults.load();
        IntegrationsConfig.BadcoreSettings def = defaults.current().integrations().badcoreSettings();
        assertFalse(def.enabled());
        assertEquals(500L, def.cooldownMs());
        assertEquals("sb", def.scoreboardCommand());
        assertEquals("VIP", def.vipLabel());
        assertEquals(" &7%vip%", def.vipBadge());
        assertEquals(4, def.tabViewItems().size());
        assertEquals("pv", def.tabViewItems().get(0).id());
        assertTrue(def.tabViewItems().get(0).defaultEnabled());
        assertEquals(5, def.chatColors().size());
        assertEquals("fire", def.chatColors().get(0).id());
        assertEquals("badcore_practice", def.database().database());
        assertEquals("badcore_qoa_settings", def.database().qoaTable());
        assertTrue(def.message("reloaded").contains("reloaded"));

        Path customDir = dir.resolve("custom");
        Files.createDirectories(customDir);
        ConfigManager custom = new ConfigManager(customDir);
        custom.load();
        String existing = Files.readString(customDir.resolve(ConfigManager.INTEGRATIONS_FILE));
        String edited = existing
                .replace("badcore-settings:\n  enabled: false", "badcore-settings:\n  enabled: true")
                .replace("cooldown-ms: 500", "cooldown-ms: 1200")
                .replace("scoreboard-command: \"sb\"", "scoreboard-command: \"scoreboard\"")
                .replace("qoa-table: \"badcore_qoa_settings\"", "qoa-table: \"custom_qoa_tbl\"");
        Files.writeString(customDir.resolve(ConfigManager.INTEGRATIONS_FILE), edited);
        custom.reload();

        IntegrationsConfig.BadcoreSettings loaded = custom.current().integrations().badcoreSettings();
        assertTrue(loaded.enabled());
        assertEquals(1200L, loaded.cooldownMs());
        assertEquals("scoreboard", loaded.scoreboardCommand());
        assertEquals("custom_qoa_tbl", loaded.database().qoaTable());
    }

    @Test
    void wireProtocolMatchesVelocityBadcoreSettingsFormat() throws Exception {
        UUID uuid = UUID.randomUUID();
        AtomicReference<QoaState> receivedQoa = new AtomicReference<>();
        AtomicReference<DuelsState> receivedDuels = new AtomicReference<>();

        BadcoreSettingsBridge bridge = new BadcoreSettingsBridge(
                (id, state) -> {
                    assertEquals(uuid, id);
                    receivedQoa.set(state);
                },
                (id, state) -> {
                    assertEquals(uuid, id);
                    receivedDuels.set(state);
                });

        // Verify GET encoding
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(BadcoreSettingsBridge.encodeGet(uuid)))) {
            assertEquals("GET", in.readUTF());
            assertEquals(uuid.toString(), in.readUTF());
        }

        // Verify GET_DUELS encoding
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(BadcoreSettingsBridge.encodeGetDuels(uuid)))) {
            assertEquals("GET_DUELS", in.readUTF());
            assertEquals(uuid.toString(), in.readUTF());
        }

        // Verify SET encoding
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(BadcoreSettingsBridge.encodeSet(uuid, "msg", false)))) {
            assertEquals("SET", in.readUTF());
            assertEquals(uuid.toString(), in.readUTF());
            assertEquals("msg", in.readUTF());
            assertFalse(in.readBoolean());
        }

        // Verify SET_DUELS encoding
        DuelsState sample = new DuelsState(false, true, false, TimePreset.NIGHT, false, true, false, true);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(
                BadcoreSettingsBridge.encodeSetDuels(uuid, "Mazik", sample)))) {
            assertEquals("SET_DUELS", in.readUTF());
            assertEquals(uuid.toString(), in.readUTF());
            assertEquals("Mazik", in.readUTF());
            assertFalse(in.readBoolean());
            assertTrue(in.readBoolean());
            assertFalse(in.readBoolean());
            assertEquals("NIGHT", in.readUTF());
            assertFalse(in.readBoolean());
            assertTrue(in.readBoolean());
            assertFalse(in.readBoolean());
            assertTrue(in.readBoolean());
        }

        // Verify incoming STATE decoding
        ByteArrayOutputStream qoaOut = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(qoaOut)) {
            out.writeUTF("STATE");
            out.writeUTF(uuid.toString());
            out.writeBoolean(false);
            out.writeBoolean(true);
            out.writeBoolean(true);
        }
        bridge.handleIncoming(BadcoreSettingsBridge.CHANNEL, qoaOut.toByteArray());
        assertEquals(new QoaState(false, true, true), receivedQoa.get());

        // Verify incoming DUELS_STATE decoding
        ByteArrayOutputStream duelsOut = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(duelsOut)) {
            out.writeUTF("DUELS_STATE");
            out.writeUTF(uuid.toString());
            out.writeBoolean(true);
            out.writeBoolean(false);
            out.writeBoolean(true);
            out.writeUTF("SUNSET");
            out.writeBoolean(false);
            out.writeBoolean(false);
            out.writeBoolean(true);
            out.writeBoolean(false);
        }
        bridge.handleIncoming(BadcoreSettingsBridge.CHANNEL, duelsOut.toByteArray());
        assertEquals(new DuelsState(true, false, true, TimePreset.SUNSET, false, false, true, false), receivedDuels.get());
    }

    @Test
    void repositoryCooldownAndTimeCycleWork() {
        BadcoreSettingsDatabase db = new BadcoreSettingsDatabase();
        BadcoreSettingsBridge bridge = new BadcoreSettingsBridge((u, s) -> {}, (u, s) -> {});
        AtomicLong now = new AtomicLong(10_000L);
        BadcoreSettingsRepository repo = new BadcoreSettingsRepository(db, bridge, now::get);
        try {
            UUID uuid = UUID.randomUUID();
            assertEquals(0L, repo.checkCooldown(uuid, 500L));
            now.addAndGet(200L);
            assertEquals(300L, repo.checkCooldown(uuid, 500L));
            now.addAndGet(300L);
            assertEquals(0L, repo.checkCooldown(uuid, 500L));

            assertEquals(TimePreset.SUNSET, TimePreset.DAY.next());
            assertEquals(TimePreset.NIGHT, TimePreset.SUNSET.next());
            assertEquals(TimePreset.DAY, TimePreset.NIGHT.next());
            assertEquals(TimePreset.DAY, TimePreset.parse("invalid"));
        } finally {
            repo.shutdown();
            db.close();
        }
    }

    @Test
    void guisRenderClickToggleAndSendPluginMessages(Env env) {
        Instance instance = env.createFlatInstance();
        FakePermissions perms = new FakePermissions(Map.of(
                "Regular", Set.of(Permissions.BADCORE_SETTINGS_USE),
                "VipStaff", Set.of(
                        Permissions.BADCORE_SETTINGS_USE,
                        Permissions.BADCORE_QOA_SOCIALSPY,
                        Permissions.BADCORE_LOBBY_FLY,
                        "axvaults.vault.1",
                        "badcore.tabview.og",
                        "badcore.chatcolor.fire")
        ));

        BadcoreSettingsService service = new BadcoreSettingsService(perms);
        // Enable with database disabled so tests don't require a live MariaDB instance.
        IntegrationsConfig.BadcoreSettings enabledBridgeOnly = new IntegrationsConfig.BadcoreSettings(
                true,
                0L,
                "sb",
                "VIP",
                " &7%vip%",
                new IntegrationsConfig.BadcoreSettings.Database(
                        false, "localhost", 3306, "badcore_practice", "root", "", 6, 5000L, false, "badcore_qoa_settings"),
                IntegrationsConfig.BadcoreSettings.DEFAULT_MESSAGES,
                IntegrationsConfig.BadcoreSettings.DEFAULT_TAB_VIEW_ITEMS,
                IntegrationsConfig.BadcoreSettings.DEFAULT_CHAT_COLORS);
        service.reload(enabledBridgeOnly);

        EventNode<PlayerEvent> node = EventNode.type("badcore-test", EventFilter.PLAYER);
        env.process().eventHandler().addChild(node);
        service.register(node);

        try {
            var regularConn = env.createConnection(new GameProfile(UUID.randomUUID(), "Regular"));
            Player regular = regularConn.connect(instance, new Pos(0, 42, 0));
            var regularPm = regularConn.trackIncoming(PluginMessagePacket.class);

            var staffConn = env.createConnection(new GameProfile(UUID.randomUUID(), "VipStaff"));
            Player staff = staffConn.connect(instance, new Pos(0, 42, 0));

            // Open Hub -> verify items -> click Global
            service.openMenu(regular, "hub");
            env.tickWhile(() -> service.guis().openKind(regular) != BadcoreSettingsGuis.Kind.HUB, Duration.ofSeconds(2));
            assertEquals(Material.COMPASS, regular.getOpenInventory().getItemStack(BadcoreSettingsGuis.HUB_SLOT_GLOBAL).material());
            assertEquals(Material.EMERALD, regular.getOpenInventory().getItemStack(BadcoreSettingsGuis.HUB_SLOT_GENS).material());
            assertEquals(Material.IRON_SWORD, regular.getOpenInventory().getItemStack(BadcoreSettingsGuis.HUB_SLOT_DUELS).material());

            service.guis().handleSlotClick(regular, BadcoreSettingsGuis.HUB_SLOT_GLOBAL);
            assertEquals(BadcoreSettingsGuis.Kind.QOA, service.guis().openKind(regular));
            // Regular player: slot 11 msg, slot 15 find, slot 13 is filler (no spyglass)
            assertEquals(Material.WRITABLE_BOOK, regular.getOpenInventory().getItemStack(BadcoreSettingsGuis.QOA_SLOT_MSG_REGULAR).material());
            assertEquals(Material.COMPASS, regular.getOpenInventory().getItemStack(BadcoreSettingsGuis.QOA_SLOT_FIND_REGULAR).material());
            assertEquals(Material.GRAY_STAINED_GLASS_PANE, regular.getOpenInventory().getItemStack(13).material());

            // Toggle Private Messages (default true -> false)
            service.guis().handleSlotClick(regular, BadcoreSettingsGuis.QOA_SLOT_MSG_REGULAR);
            assertFalse(service.repository().getQoaState(regular.getUuid()).msgEnabled());

            // Verify SET plugin message was emitted
            List<PluginMessagePacket> sent = regularPm.collect().stream()
                    .filter(p -> BadcoreSettingsBridge.CHANNEL.equals(p.channel()))
                    .toList();
            assertTrue(sent.size() >= 2, "expected GET and SET messages on badcore:settings");

            // Open Practice for Regular player: VIP Lobby Fly click must be blocked
            service.guis().openDuels(regular);
            assertEquals(BadcoreSettingsGuis.Kind.DUELS, service.guis().openKind(regular));
            assertTrue(service.repository().getDuelsState(regular.getUuid()).lobbyFly());
            service.guis().handleSlotClick(regular, BadcoreSettingsGuis.DUELS_SLOT_LOBBY_FLY);
            assertTrue(service.repository().getDuelsState(regular.getUuid()).lobbyFly(), "non-VIP must not toggle VIP setting");

            // Time of day click cycles DAY -> SUNSET
            service.guis().handleSlotClick(regular, BadcoreSettingsGuis.DUELS_SLOT_TIME);
            assertEquals(TimePreset.SUNSET, service.repository().getDuelsState(regular.getUuid()).timePreset());
            var clockItem = regular.getOpenInventory().getItemStack(BadcoreSettingsGuis.DUELS_SLOT_TIME);
            assertNotNull(clockItem);
            String clockLore = clockItem.get(net.minestom.server.component.DataComponents.LORE).stream()
                    .map(PlainTextComponentSerializer.plainText()::serialize)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertTrue(clockLore.contains("Sunset"), "clock lore should show updated Sunset preset: " + clockLore);

            // Staff + VIP player: sees SocialSpy at slot 15 and can toggle Lobby Fly
            service.guis().openQoa(staff);
            assertEquals(Material.SPYGLASS, staff.getOpenInventory().getItemStack(BadcoreSettingsGuis.QOA_SLOT_SPY_STAFF).material());
            service.guis().handleSlotClick(staff, BadcoreSettingsGuis.QOA_SLOT_SPY_STAFF);
            assertTrue(service.repository().getQoaState(staff.getUuid()).socialSpy());

            service.guis().openDuels(staff);
            service.guis().handleSlotClick(staff, BadcoreSettingsGuis.DUELS_SLOT_LOBBY_FLY);
            assertFalse(service.repository().getDuelsState(staff.getUuid()).lobbyFly());

            // Hub -> Gens -> TabView & ChatColor flows
            service.guis().openHub(staff);
            service.guis().handleSlotClick(staff, BadcoreSettingsGuis.HUB_SLOT_GENS);
            assertEquals(BadcoreSettingsGuis.Kind.GENS, service.guis().openKind(staff));
            assertEquals(Material.GLOWSTONE_DUST, staff.getOpenInventory().getItemStack(BadcoreSettingsGuis.GENS_SLOT_NAMETAG_PERF).material());
            assertEquals(Material.MAGENTA_DYE, staff.getOpenInventory().getItemStack(BadcoreSettingsGuis.GENS_SLOT_CHATCOLOR).material());
            assertEquals(Material.NAME_TAG, staff.getOpenInventory().getItemStack(BadcoreSettingsGuis.GENS_SLOT_TABVIEW).material());
            assertEquals(Material.OAK_SIGN, staff.getOpenInventory().getItemStack(BadcoreSettingsGuis.GENS_SLOT_SCOREBOARD).material());

            // Gens -> TabView
            service.guis().handleSlotClick(staff, BadcoreSettingsGuis.GENS_SLOT_TABVIEW);
            assertEquals(BadcoreSettingsGuis.Kind.TABVIEW, service.guis().openKind(staff));
            // Slot 10 = pv (default true), slot 11 = og (default false)
            assertTrue(service.repository().isTabViewEnabled(staff.getUuid(), "pv", true));
            service.guis().handleSlotClick(staff, 10);
            assertFalse(service.repository().isTabViewEnabled(staff.getUuid(), "pv", true));
            service.guis().handleSlotClick(staff, 11);
            assertTrue(service.repository().isTabViewEnabled(staff.getUuid(), "og", false));
            // Hide All (slot 22) disables all tabview entries
            service.guis().handleSlotClick(staff, 22);
            assertFalse(service.repository().isTabViewEnabled(staff.getUuid(), "og", false));
            // Back (slot 18) returns to Gens
            service.guis().handleSlotClick(staff, 18);
            assertEquals(BadcoreSettingsGuis.Kind.GENS, service.guis().openKind(staff));

            // Gens -> ChatColor
            service.guis().handleSlotClick(staff, BadcoreSettingsGuis.GENS_SLOT_CHATCOLOR);
            assertEquals(BadcoreSettingsGuis.Kind.CHATCOLOR, service.guis().openKind(staff));
            assertEquals("default", service.repository().getChatColorId(staff.getUuid()));
            // Slot 10 = fire (unlocked for VipStaff), slot 11 = ice (locked)
            service.guis().handleSlotClick(staff, 10);
            assertEquals("fire", service.repository().getChatColorId(staff.getUuid()));
            service.guis().handleSlotClick(staff, 11);
            assertEquals("fire", service.repository().getChatColorId(staff.getUuid()), "locked chatcolor must not be selected");
            // Reset (slot 22) resets to default
            service.guis().handleSlotClick(staff, 22);
            assertEquals("default", service.repository().getChatColorId(staff.getUuid()));

            // Close button closes inventory
            service.guis().handleSlotClick(staff, 26);
            assertNull(service.guis().openKind(staff));
        } finally {
            service.shutdown();
        }
    }
}
