package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore.BadcoreSettingsRepository.DuelsSetting;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore.BadcoreSettingsRepository.DuelsState;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore.BadcoreSettingsRepository.QoaState;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.player.PermissionService;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.player.Permissions;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.trait.PlayerEvent;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.sound.SoundEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Minestom chest GUIs for badcoreSettings:
 * <ul>
 *     <li>Hub ({@code Settings}, 3 rows)</li>
 *     <li>Global / QOA ({@code Global Settings}, 3 rows)</li>
 *     <li>Practice / Duels ({@code Duels Settings}, 4 rows)</li>
 * </ul>
 */
public final class BadcoreSettingsGuis {

    public enum Kind {
        HUB,
        QOA,
        DUELS
    }

    private record OpenGui(Kind kind, Inventory inventory) {
    }

    /** Sends a configured badcoreSettings message with optional key/value placeholder pairs. */
    @FunctionalInterface
    public interface MessageSender {
        void send(Player player, String key, String... placeholders);
    }

    // Hub slots
    static final int HUB_SLOT_GLOBAL = 11;
    static final int HUB_SLOT_DUELS = 15;
    static final int HUB_SLOT_CLOSE = 22;

    // QOA slots
    static final int QOA_SLOT_MSG_REGULAR = 11;
    static final int QOA_SLOT_FIND_REGULAR = 15;
    static final int QOA_SLOT_MSG_STAFF = 11;
    static final int QOA_SLOT_FIND_STAFF = 13;
    static final int QOA_SLOT_SPY_STAFF = 15;
    static final int QOA_SLOT_BACK = 18;
    static final int QOA_SLOT_CLOSE = 26;

    // Duels slots
    static final int DUELS_SLOT_DUEL_REQUESTS = 10;
    static final int DUELS_SLOT_PARTY_INVITES = 12;
    static final int DUELS_SLOT_VISIBILITY = 14;
    static final int DUELS_SLOT_TIME = 16;
    static final int DUELS_SLOT_LOBBY_FLY = 19;
    static final int DUELS_SLOT_VIP_JOIN_MSG = 21;
    static final int DUELS_SLOT_ALLOW_SPECTATORS = 23;
    static final int DUELS_SLOT_SILENT_SPECTATE = 25;
    static final int DUELS_SLOT_BACK = 27;
    static final int DUELS_SLOT_CLOSE = 35;

    private final BadcoreSettingsRepository repository;
    private final BadcoreSettingsBridge bridge;
    private final PermissionService permissions;
    private final LongSupplier cooldownMs;
    private final MessageSender messages;
    private final Map<UUID, OpenGui> open = new ConcurrentHashMap<>();

    public BadcoreSettingsGuis(BadcoreSettingsRepository repository,
                               BadcoreSettingsBridge bridge,
                               PermissionService permissions,
                               LongSupplier cooldownMs,
                               MessageSender messages) {
        this.repository = repository;
        this.bridge = bridge;
        this.permissions = permissions;
        this.cooldownMs = cooldownMs;
        this.messages = messages;
    }

    public void register(EventNode<PlayerEvent> node) {
        node.addListener(InventoryPreClickEvent.class, this::onClick);
        node.addListener(InventoryCloseEvent.class, event -> {
            OpenGui current = open.get(event.getPlayer().getUuid());
            if (current != null && current.inventory() == event.getInventory()) {
                open.remove(event.getPlayer().getUuid());
            }
        });
        node.addListener(PlayerDisconnectEvent.class, event -> open.remove(event.getPlayer().getUuid()));
    }

    public void openHub(Player player) {
        Inventory inventory = new Inventory(InventoryType.CHEST_3_ROW, BadcoreSettingsGuiStyle.title("Settings"));
        renderHub(inventory);
        open.put(player.getUuid(), new OpenGui(Kind.HUB, inventory));
        player.openInventory(inventory);
    }

    public void openQoa(Player player) {
        Inventory inventory = new Inventory(InventoryType.CHEST_3_ROW, BadcoreSettingsGuiStyle.title("Global Settings"));
        renderQoa(player, inventory);
        open.put(player.getUuid(), new OpenGui(Kind.QOA, inventory));
        player.openInventory(inventory);
    }

    public void openDuels(Player player) {
        Inventory inventory = new Inventory(InventoryType.CHEST_4_ROW, BadcoreSettingsGuiStyle.title("Duels Settings"));
        renderDuels(player, inventory);
        open.put(player.getUuid(), new OpenGui(Kind.DUELS, inventory));
        player.openInventory(inventory);
    }

    /** Re-renders the player's currently open settings GUI in place if it matches {@code kind}. */
    public void rerenderIfOpen(Player player, Kind kind) {
        OpenGui current = open.get(player.getUuid());
        if (current == null || current.kind() != kind || player.getOpenInventory() != current.inventory()) {
            return;
        }
        switch (kind) {
            case HUB -> renderHub(current.inventory());
            case QOA -> renderQoa(player, current.inventory());
            case DUELS -> renderDuels(player, current.inventory());
        }
    }

    /** Which GUI kind the player currently has open, or {@code null}. Package-private for tests. */
    @Nullable Kind openKind(Player player) {
        OpenGui current = open.get(player.getUuid());
        if (current == null || player.getOpenInventory() != current.inventory()) {
            return null;
        }
        return current.kind();
    }

    private void renderHub(Inventory inventory) {
        clear(inventory);

        inventory.setItemStack(HUB_SLOT_GLOBAL, BadcoreSettingsGuiStyle.item(
                Material.COMPASS,
                BadcoreSettingsGuiStyle.name("Global Settings"),
                BadcoreSettingsGuiStyle.subtitle("network qoa"),
                "",
                BadcoreSettingsGuiStyle.bullet("Private Messages (/togglemsg)"),
                BadcoreSettingsGuiStyle.bullet("Server Finding (/togglefind)"),
                BadcoreSettingsGuiStyle.bullet("Synced across all servers"),
                "",
                BadcoreSettingsGuiStyle.action("Click to open")
        ));

        inventory.setItemStack(HUB_SLOT_DUELS, BadcoreSettingsGuiStyle.item(
                Material.IRON_SWORD,
                BadcoreSettingsGuiStyle.name("Duels Settings"),
                BadcoreSettingsGuiStyle.subtitle("practice"),
                "",
                BadcoreSettingsGuiStyle.bullet("Duel Requests, Party Invites"),
                BadcoreSettingsGuiStyle.bullet("Visibility, Time of Day & VIP perks"),
                BadcoreSettingsGuiStyle.bullet("Synced across all servers"),
                "",
                BadcoreSettingsGuiStyle.action("Click to open")
        ));

        inventory.setItemStack(HUB_SLOT_CLOSE, BadcoreSettingsGuiStyle.closeButton());
        BadcoreSettingsGuiStyle.fillEmpty(inventory);
    }

    private void renderQoa(Player viewer, Inventory inventory) {
        clear(inventory);

        QoaState state = repository.getQoaState(viewer.getUuid());
        boolean hasSpy = permissions.hasPermission(viewer, Permissions.BADCORE_QOA_SOCIALSPY);

        int msgSlot = hasSpy ? QOA_SLOT_MSG_STAFF : QOA_SLOT_MSG_REGULAR;
        int findSlot = hasSpy ? QOA_SLOT_FIND_STAFF : QOA_SLOT_FIND_REGULAR;

        inventory.setItemStack(msgSlot, BadcoreSettingsGuiStyle.item(
                Material.WRITABLE_BOOK,
                BadcoreSettingsGuiStyle.name("Private Messages"),
                BadcoreSettingsGuiStyle.subtitle("global setting"),
                "",
                BadcoreSettingsGuiStyle.bullet("Allow players to send you /msg"),
                BadcoreSettingsGuiStyle.statusLine("Status", state.msgEnabled()),
                "",
                BadcoreSettingsGuiStyle.action("Click to toggle")
        ));

        inventory.setItemStack(findSlot, BadcoreSettingsGuiStyle.item(
                Material.COMPASS,
                BadcoreSettingsGuiStyle.name("Server Finding"),
                BadcoreSettingsGuiStyle.subtitle("global setting"),
                "",
                BadcoreSettingsGuiStyle.bullet("Allow players to locate you with /find"),
                BadcoreSettingsGuiStyle.statusLine("Status", state.findEnabled()),
                "",
                BadcoreSettingsGuiStyle.action("Click to toggle")
        ));

        if (hasSpy) {
            inventory.setItemStack(QOA_SLOT_SPY_STAFF, BadcoreSettingsGuiStyle.item(
                    Material.SPYGLASS,
                    BadcoreSettingsGuiStyle.name("SocialSpy"),
                    BadcoreSettingsGuiStyle.subtitle("staff setting"),
                    "",
                    BadcoreSettingsGuiStyle.bullet("View private messages across the network"),
                    BadcoreSettingsGuiStyle.statusLine("Status", state.socialSpy()),
                    "",
                    BadcoreSettingsGuiStyle.action("Click to toggle")
            ));
        }

        inventory.setItemStack(QOA_SLOT_BACK, BadcoreSettingsGuiStyle.backButton());
        inventory.setItemStack(QOA_SLOT_CLOSE, BadcoreSettingsGuiStyle.closeButton());
        BadcoreSettingsGuiStyle.fillEmpty(inventory);
    }

    private void renderDuels(Player viewer, Inventory inventory) {
        clear(inventory);

        DuelsState s = repository.getDuelsState(viewer.getUuid());

        inventory.setItemStack(DUELS_SLOT_DUEL_REQUESTS, createToggle(
                Material.IRON_SWORD,
                "Duel Requests",
                s.duelRequests(),
                "Allow players to send you /duel challenges"
        ));

        inventory.setItemStack(DUELS_SLOT_PARTY_INVITES, createToggle(
                Material.NAME_TAG,
                "Party Invites",
                s.partyInvites(),
                "Allow players to invite you to parties"
        ));

        inventory.setItemStack(DUELS_SLOT_VISIBILITY, createToggle(
                s.lobbyVisibility() ? Material.ENDER_EYE : Material.ENDER_PEARL,
                "Lobby Visibility",
                s.lobbyVisibility(),
                "Show or hide other players in Practice lobby"
        ));

        inventory.setItemStack(DUELS_SLOT_TIME, BadcoreSettingsGuiStyle.item(
                Material.CLOCK,
                BadcoreSettingsGuiStyle.name("Time of Day"),
                BadcoreSettingsGuiStyle.subtitle("duels setting"),
                "",
                BadcoreSettingsGuiStyle.bullet("Client-side sky time in Practice lobby"),
                BadcoreSettingsGuiStyle.info("Selected", s.timePreset().label()),
                "",
                BadcoreSettingsGuiStyle.action("Click to cycle time")
        ));

        inventory.setItemStack(DUELS_SLOT_LOBBY_FLY, createVipToggle(
                Material.FEATHER,
                "Lobby Fly",
                s.lobbyFly(),
                hasVipPerm(viewer, Permissions.BADCORE_LOBBY_FLY),
                "Fly freely while waiting in the Practice lobby"
        ));

        inventory.setItemStack(DUELS_SLOT_VIP_JOIN_MSG, createVipToggle(
                Material.GOLD_INGOT,
                "VIP Join Message",
                s.vipJoinMessage(),
                hasVipPerm(viewer, Permissions.BADCORE_JOIN_MESSAGE),
                "Broadcast a special VIP message on Practice join"
        ));

        inventory.setItemStack(DUELS_SLOT_ALLOW_SPECTATORS, createVipToggle(
                Material.SPYGLASS,
                "Allow Spectators",
                s.allowSpectators(),
                hasVipPerm(viewer, Permissions.BADCORE_SPECTATE_PRIVACY),
                "Allow other players to /spec your duels"
        ));

        inventory.setItemStack(DUELS_SLOT_SILENT_SPECTATE, createVipToggle(
                Material.PHANTOM_MEMBRANE,
                "Silent Spectate",
                s.silentSpectate(),
                hasVipPerm(viewer, Permissions.BADCORE_SPECTATE_SILENT),
                "Spectate matches without notifying fighters"
        ));

        inventory.setItemStack(DUELS_SLOT_BACK, BadcoreSettingsGuiStyle.backButton());
        inventory.setItemStack(DUELS_SLOT_CLOSE, BadcoreSettingsGuiStyle.closeButton());
        BadcoreSettingsGuiStyle.fillEmpty(inventory);
    }

    private void onClick(InventoryPreClickEvent event) {
        Player player = event.getPlayer();
        OpenGui current = open.get(player.getUuid());
        if (current == null || player.getOpenInventory() != current.inventory()) {
            return;
        }
        event.setCancelled(true);
        if (event.getInventory() != current.inventory()) {
            return;
        }
        handleSlotClick(player, current, event.getSlot());
    }

    /** Handles a click on {@code slot} in the player's currently open settings GUI. */
    void handleSlotClick(Player clicker, int slot) {
        OpenGui current = open.get(clicker.getUuid());
        if (current == null || clicker.getOpenInventory() != current.inventory()) {
            return;
        }
        handleSlotClick(clicker, current, slot);
    }

    private void handleSlotClick(Player clicker, OpenGui current, int slot) {
        switch (current.kind()) {
            case HUB -> handleHubClick(clicker, slot);
            case QOA -> handleQoaClick(clicker, current.inventory(), slot);
            case DUELS -> handleDuelsClick(clicker, current.inventory(), slot);
        }
    }

    private void handleHubClick(Player clicker, int slot) {
        switch (slot) {
            case HUB_SLOT_GLOBAL -> {
                playClick(clicker, 0.8f, 1.1f);
                bridge.requestState(clicker);
                openQoa(clicker);
            }
            case HUB_SLOT_DUELS -> {
                playClick(clicker, 0.8f, 1.1f);
                bridge.requestDuelsState(clicker);
                openDuels(clicker);
            }
            case HUB_SLOT_CLOSE -> clicker.closeInventory();
            default -> {
            }
        }
    }

    private void handleQoaClick(Player clicker, Inventory inventory, int slot) {
        if (slot == QOA_SLOT_BACK) {
            playClick(clicker, 0.8f, 0.9f);
            openHub(clicker);
            return;
        }
        if (slot == QOA_SLOT_CLOSE) {
            clicker.closeInventory();
            return;
        }

        boolean hasSpy = permissions.hasPermission(clicker, Permissions.BADCORE_QOA_SOCIALSPY);
        int msgSlot = hasSpy ? QOA_SLOT_MSG_STAFF : QOA_SLOT_MSG_REGULAR;
        int findSlot = hasSpy ? QOA_SLOT_FIND_STAFF : QOA_SLOT_FIND_REGULAR;

        String key = null;
        if (slot == msgSlot) {
            key = "msg";
        } else if (slot == findSlot) {
            key = "find";
        } else if (hasSpy && slot == QOA_SLOT_SPY_STAFF) {
            key = "socialspy";
        }
        if (key == null) {
            return;
        }

        long remaining = repository.checkCooldown(clicker.getUuid(), cooldownMs.getAsLong());
        if (remaining > 0L) {
            String seconds = String.format(Locale.ROOT, "%.1f", Math.max(0.1, remaining / 1000.0));
            playDeny(clicker, 0.6f);
            messages.send(clicker, "cooldown", "seconds", seconds);
            return;
        }

        boolean next = repository.toggleQoa(clicker, key);
        playClick(clicker, 0.8f, next ? 1.3f : 0.8f);
        renderQoa(clicker, inventory);
    }

    private void handleDuelsClick(Player clicker, Inventory inventory, int slot) {
        if (slot == DUELS_SLOT_BACK) {
            playClick(clicker, 0.8f, 0.9f);
            openHub(clicker);
            return;
        }
        if (slot == DUELS_SLOT_CLOSE) {
            clicker.closeInventory();
            return;
        }

        DuelsSetting setting = switch (slot) {
            case DUELS_SLOT_DUEL_REQUESTS -> DuelsSetting.DUEL_REQUESTS;
            case DUELS_SLOT_PARTY_INVITES -> DuelsSetting.PARTY_INVITES;
            case DUELS_SLOT_VISIBILITY -> DuelsSetting.LOBBY_VISIBILITY;
            case DUELS_SLOT_TIME -> DuelsSetting.TIME_OF_DAY;
            case DUELS_SLOT_LOBBY_FLY -> DuelsSetting.LOBBY_FLY;
            case DUELS_SLOT_VIP_JOIN_MSG -> DuelsSetting.VIP_JOIN_MESSAGE;
            case DUELS_SLOT_ALLOW_SPECTATORS -> DuelsSetting.ALLOW_SPECTATORS;
            case DUELS_SLOT_SILENT_SPECTATE -> DuelsSetting.SILENT_SPECTATE;
            default -> null;
        };
        if (setting == null) {
            return;
        }

        if (!hasPermissionFor(clicker, setting)) {
            playDeny(clicker, 0.7f);
            messages.send(clicker, "vip-required");
            return;
        }

        long remaining = repository.checkCooldown(clicker.getUuid(), cooldownMs.getAsLong());
        if (remaining > 0L) {
            String seconds = String.format(Locale.ROOT, "%.1f", Math.max(0.1, remaining / 1000.0));
            playDeny(clicker, 0.6f);
            messages.send(clicker, "cooldown", "seconds", seconds);
            return;
        }

        DuelsState next = repository.toggleDuels(clicker, setting);
        float pitch = switch (setting) {
            case DUEL_REQUESTS -> next.duelRequests() ? 1.3f : 0.8f;
            case PARTY_INVITES -> next.partyInvites() ? 1.3f : 0.8f;
            case LOBBY_VISIBILITY -> next.lobbyVisibility() ? 1.3f : 0.8f;
            case TIME_OF_DAY -> 1.2f;
            case LOBBY_FLY -> next.lobbyFly() ? 1.3f : 0.8f;
            case VIP_JOIN_MESSAGE -> next.vipJoinMessage() ? 1.3f : 0.8f;
            case ALLOW_SPECTATORS -> next.allowSpectators() ? 1.3f : 0.8f;
            case SILENT_SPECTATE -> next.silentSpectate() ? 1.3f : 0.8f;
        };
        playClick(clicker, 0.8f, pitch);
        renderDuels(clicker, inventory);
    }

    private boolean hasVipPerm(Player player, String perm) {
        return permissions.hasPermission(player, perm)
                || permissions.hasPermission(player, Permissions.DUELS_ADMIN);
    }

    private boolean hasPermissionFor(Player player, DuelsSetting setting) {
        return switch (setting) {
            case LOBBY_FLY -> hasVipPerm(player, Permissions.BADCORE_LOBBY_FLY);
            case VIP_JOIN_MESSAGE -> hasVipPerm(player, Permissions.BADCORE_JOIN_MESSAGE);
            case ALLOW_SPECTATORS -> hasVipPerm(player, Permissions.BADCORE_SPECTATE_PRIVACY);
            case SILENT_SPECTATE -> hasVipPerm(player, Permissions.BADCORE_SPECTATE_SILENT);
            default -> true;
        };
    }

    private static ItemStack createToggle(Material mat, String title, boolean enabled, String desc) {
        return BadcoreSettingsGuiStyle.item(
                mat,
                BadcoreSettingsGuiStyle.name(title),
                BadcoreSettingsGuiStyle.subtitle("duels setting"),
                "",
                BadcoreSettingsGuiStyle.bullet(desc),
                BadcoreSettingsGuiStyle.statusLine("Status", enabled),
                "",
                BadcoreSettingsGuiStyle.action("Click to toggle")
        );
    }

    private static ItemStack createVipToggle(Material mat, String title, boolean enabled, boolean unlocked, String desc) {
        if (!unlocked) {
            return BadcoreSettingsGuiStyle.item(
                    mat,
                    BadcoreSettingsGuiStyle.name(title) + " <gold>[VIP]</gold>",
                    BadcoreSettingsGuiStyle.subtitle("vip setting"),
                    "",
                    BadcoreSettingsGuiStyle.bullet(desc),
                    "",
                    "<#CF8484>✖ VIP Rank Required</#CF8484>"
            );
        }
        return BadcoreSettingsGuiStyle.item(
                mat,
                BadcoreSettingsGuiStyle.name(title) + " <gold>[VIP]</gold>",
                BadcoreSettingsGuiStyle.subtitle("vip setting"),
                "",
                BadcoreSettingsGuiStyle.bullet(desc),
                BadcoreSettingsGuiStyle.statusLine("Status", enabled),
                "",
                BadcoreSettingsGuiStyle.action("Click to toggle")
        );
    }

    private static void clear(Inventory inventory) {
        int size = inventory.getSize();
        for (int i = 0; i < size; i++) {
            inventory.setItemStack(i, ItemStack.AIR);
        }
    }

    private static void playClick(Player player, float volume, float pitch) {
        player.playSound(Sound.sound(SoundEvent.UI_BUTTON_CLICK, Sound.Source.MASTER, volume, pitch));
    }

    private static void playDeny(Player player, float volume) {
        player.playSound(Sound.sound(SoundEvent.ENTITY_VILLAGER_NO, Sound.Source.MASTER, volume, 1.0f));
    }
}
