package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.IntegrationsConfig.BadcoreSettings;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore.BadcoreSettingsRepository.DuelsSetting;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore.BadcoreSettingsRepository.DuelsState;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore.BadcoreSettingsRepository.QoaState;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder.FormattedText;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.player.PermissionService;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.player.Permissions;
import net.kyori.adventure.sound.Sound;
import net.minestom.server.MinecraftServer;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Minestom chest GUIs for badcoreSettings:
 * <ul>
 *     <li>Hub ({@code Settings}, 3 rows)</li>
 *     <li>Global / QOA ({@code Global Settings}, 3 rows)</li>
 *     <li>Gens ({@code Gens Settings}, 3 rows)</li>
 *     <li>Practice ({@code Practice Settings}, 4 rows)</li>
 *     <li>Tab View ({@code Tab View}, 3–4 rows paginated)</li>
 *     <li>Chat Colors ({@code Chat Colors}, 3–4 rows paginated)</li>
 * </ul>
 */
public final class BadcoreSettingsGuis {

    public enum Kind {
        HUB,
        QOA,
        DUELS,
        GENS,
        TABVIEW,
        CHATCOLOR
    }

    private record OpenGui(
            Kind kind,
            Inventory inventory,
            boolean openedFromSettings,
            int page,
            int totalPages,
            int navRowStart,
            Map<Integer, Object> slotMap
    ) {
    }

    /** Sends a configured badcoreSettings message with optional key/value placeholder pairs. */
    @FunctionalInterface
    public interface MessageSender {
        void send(Player player, String key, String... placeholders);
    }

    // Hub slots
    static final int HUB_SLOT_GLOBAL = 11;
    static final int HUB_SLOT_GENS = 13;
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

    // Practice (Duels) slots
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

    // Gens slots
    static final int GENS_SLOT_NAMETAG_PERF = 10;
    static final int GENS_SLOT_CHATCOLOR = 12;
    static final int GENS_SLOT_TABVIEW = 14;
    static final int GENS_SLOT_SCOREBOARD = 16;
    static final int GENS_SLOT_BACK = 18;
    static final int GENS_SLOT_CLOSE = 26;

    // Paginated item slots (TabView & ChatColor)
    static final int[] SLOTS_SMALL = {10, 11, 12, 13, 14, 15, 16};
    static final int[] SLOTS_LARGE = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25
    };

    private final BadcoreSettingsRepository repository;
    private final BadcoreSettingsBridge bridge;
    private final PermissionService permissions;
    private final Supplier<BadcoreSettings> settingsSupplier;
    private final MessageSender messages;
    private final Map<UUID, OpenGui> open = new ConcurrentHashMap<>();

    public BadcoreSettingsGuis(BadcoreSettingsRepository repository,
                               BadcoreSettingsBridge bridge,
                               PermissionService permissions,
                               Supplier<BadcoreSettings> settingsSupplier,
                               MessageSender messages) {
        this.repository = repository;
        this.bridge = bridge;
        this.permissions = permissions;
        this.settingsSupplier = settingsSupplier;
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
        open.put(player.getUuid(), new OpenGui(Kind.HUB, inventory, false, 0, 1, 18, Map.of()));
        player.openInventory(inventory);
    }

    public void openQoa(Player player) {
        Inventory inventory = new Inventory(InventoryType.CHEST_3_ROW, BadcoreSettingsGuiStyle.title("Global Settings"));
        renderQoa(player, inventory);
        open.put(player.getUuid(), new OpenGui(Kind.QOA, inventory, true, 0, 1, 18, Map.of()));
        player.openInventory(inventory);
    }

    public void openDuels(Player player) {
        Inventory inventory = new Inventory(InventoryType.CHEST_4_ROW, BadcoreSettingsGuiStyle.title("Practice Settings"));
        renderDuels(player, inventory);
        open.put(player.getUuid(), new OpenGui(Kind.DUELS, inventory, true, 0, 1, 27, Map.of()));
        player.openInventory(inventory);
    }

    public void openGens(Player player) {
        Inventory inventory = new Inventory(InventoryType.CHEST_3_ROW, BadcoreSettingsGuiStyle.title("Gens Settings"));
        renderGens(inventory);
        open.put(player.getUuid(), new OpenGui(Kind.GENS, inventory, true, 0, 1, 18, Map.of()));
        player.openInventory(inventory);
    }

    public void openTabView(Player player, boolean openedFromSettings, int requestedPage) {
        List<BadcoreSettings.TabViewItem> entries = settingsSupplier.get().tabViewItems();
        boolean large = entries.size() > SLOTS_SMALL.length;
        InventoryType type = large ? InventoryType.CHEST_4_ROW : InventoryType.CHEST_3_ROW;
        int navRowStart = (large ? 36 : 27) - 9;
        int pageSize = large ? SLOTS_LARGE.length : SLOTS_SMALL.length;
        int totalPages = Math.max(1, (int) Math.ceil((double) entries.size() / (double) pageSize));
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));
        String title = totalPages > 1 ? "Tab View (" + (page + 1) + "/" + totalPages + ")" : "Tab View";

        Inventory inventory = new Inventory(type, BadcoreSettingsGuiStyle.title(title));
        Map<Integer, Object> slotMap = new HashMap<>();
        OpenGui gui = new OpenGui(Kind.TABVIEW, inventory, openedFromSettings, page, totalPages, navRowStart, slotMap);
        renderTabView(player, gui, entries);
        open.put(player.getUuid(), gui);
        player.openInventory(inventory);
    }

    public void openChatColor(Player player, boolean openedFromSettings, int requestedPage) {
        List<BadcoreSettings.ChatColorPreset> presets = settingsSupplier.get().chatColors();
        boolean large = presets.size() > SLOTS_SMALL.length;
        InventoryType type = large ? InventoryType.CHEST_4_ROW : InventoryType.CHEST_3_ROW;
        int navRowStart = (large ? 36 : 27) - 9;
        int pageSize = large ? SLOTS_LARGE.length : SLOTS_SMALL.length;
        int totalPages = Math.max(1, (int) Math.ceil((double) presets.size() / (double) pageSize));
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));
        String title = totalPages > 1 ? "Chat Colors (" + (page + 1) + "/" + totalPages + ")" : "Chat Colors";

        Inventory inventory = new Inventory(type, BadcoreSettingsGuiStyle.title(title));
        Map<Integer, Object> slotMap = new HashMap<>();
        OpenGui gui = new OpenGui(Kind.CHATCOLOR, inventory, openedFromSettings, page, totalPages, navRowStart, slotMap);
        renderChatColor(player, gui, presets);
        open.put(player.getUuid(), gui);
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
            case GENS -> renderGens(current.inventory());
            case TABVIEW -> renderTabView(player, current, settingsSupplier.get().tabViewItems());
            case CHATCOLOR -> renderChatColor(player, current, settingsSupplier.get().chatColors());
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

        inventory.setItemStack(HUB_SLOT_GENS, BadcoreSettingsGuiStyle.item(
                Material.EMERALD,
                BadcoreSettingsGuiStyle.name("Gens Settings"),
                BadcoreSettingsGuiStyle.subtitle("gens"),
                "",
                BadcoreSettingsGuiStyle.bullet("Nametag Performance, Scoreboard"),
                BadcoreSettingsGuiStyle.bullet("Chat Color & Tab View"),
                BadcoreSettingsGuiStyle.bullet("Synced across all servers"),
                "",
                BadcoreSettingsGuiStyle.action("Click to open")
        ));

        inventory.setItemStack(HUB_SLOT_DUELS, BadcoreSettingsGuiStyle.item(
                Material.IRON_SWORD,
                BadcoreSettingsGuiStyle.name("Practice Settings"),
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
        BadcoreSettings cfg = settingsSupplier.get();

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
                BadcoreSettingsGuiStyle.subtitle("practice setting"),
                "",
                BadcoreSettingsGuiStyle.bullet("Client-side sky time in Practice lobby"),
                BadcoreSettingsGuiStyle.info("Selected", s.timePreset().label()),
                "",
                BadcoreSettingsGuiStyle.action("Click to cycle time")
        ));

        inventory.setItemStack(DUELS_SLOT_LOBBY_FLY, createVipToggle(
                cfg,
                Material.FEATHER,
                "Lobby Fly",
                s.lobbyFly(),
                hasVipPerm(viewer, Permissions.BADCORE_LOBBY_FLY),
                "Fly freely while waiting in the Practice lobby"
        ));

        inventory.setItemStack(DUELS_SLOT_VIP_JOIN_MSG, createVipToggle(
                cfg,
                Material.GOLD_INGOT,
                "VIP Join Message",
                s.vipJoinMessage(),
                hasVipPerm(viewer, Permissions.BADCORE_JOIN_MESSAGE),
                "Broadcast a special VIP message on Practice join"
        ));

        inventory.setItemStack(DUELS_SLOT_ALLOW_SPECTATORS, createVipToggle(
                cfg,
                Material.SPYGLASS,
                "Allow Spectators",
                s.allowSpectators(),
                hasVipPerm(viewer, Permissions.BADCORE_SPECTATE_PRIVACY),
                "Allow other players to /spec your duels"
        ));

        inventory.setItemStack(DUELS_SLOT_SILENT_SPECTATE, createVipToggle(
                cfg,
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

    private void renderGens(Inventory inventory) {
        clear(inventory);

        inventory.setItemStack(GENS_SLOT_NAMETAG_PERF, BadcoreSettingsGuiStyle.item(
                Material.GLOWSTONE_DUST,
                BadcoreSettingsGuiStyle.name("Nametag Performance"),
                BadcoreSettingsGuiStyle.subtitle("gens setting"),
                "",
                BadcoreSettingsGuiStyle.bullet("Show sparkles & particles on nametags"),
                BadcoreSettingsGuiStyle.statusLine("Particles", true),
                "",
                BadcoreSettingsGuiStyle.action("Click to toggle")
        ));

        inventory.setItemStack(GENS_SLOT_CHATCOLOR, BadcoreSettingsGuiStyle.item(
                Material.MAGENTA_DYE,
                BadcoreSettingsGuiStyle.name("Chat Color"),
                BadcoreSettingsGuiStyle.subtitle("gens cosmetic"),
                "",
                BadcoreSettingsGuiStyle.bullet("Customize your chat message color"),
                "",
                BadcoreSettingsGuiStyle.action("Click to open /chatcolor")
        ));

        inventory.setItemStack(GENS_SLOT_TABVIEW, BadcoreSettingsGuiStyle.item(
                Material.NAME_TAG,
                BadcoreSettingsGuiStyle.name("Tab View"),
                BadcoreSettingsGuiStyle.subtitle("gens cosmetic"),
                "",
                BadcoreSettingsGuiStyle.bullet("Toggle PV badge & custom tags in TAB"),
                "",
                BadcoreSettingsGuiStyle.action("Click to open /tabview")
        ));

        inventory.setItemStack(GENS_SLOT_SCOREBOARD, BadcoreSettingsGuiStyle.item(
                Material.OAK_SIGN,
                BadcoreSettingsGuiStyle.name("Scoreboard"),
                BadcoreSettingsGuiStyle.subtitle("gens setting"),
                "",
                BadcoreSettingsGuiStyle.bullet("Toggle the sidebar scoreboard on or off"),
                "",
                BadcoreSettingsGuiStyle.action("Click to toggle")
        ));

        inventory.setItemStack(GENS_SLOT_BACK, BadcoreSettingsGuiStyle.backButton());
        inventory.setItemStack(GENS_SLOT_CLOSE, BadcoreSettingsGuiStyle.closeButton());
        BadcoreSettingsGuiStyle.fillEmpty(inventory);
    }

    private void renderTabView(Player viewer, OpenGui gui, List<BadcoreSettings.TabViewItem> entries) {
        Inventory inventory = gui.inventory();
        clear(inventory);
        gui.slotMap().clear();

        int[] itemSlots = entries.size() > SLOTS_SMALL.length ? SLOTS_LARGE : SLOTS_SMALL;
        int pageSize = itemSlots.length;
        int start = gui.page() * pageSize;
        int end = Math.min(start + pageSize, entries.size());

        for (int i = start; i < end; i++) {
            BadcoreSettings.TabViewItem entry = entries.get(i);
            int slot = itemSlots[i - start];
            gui.slotMap().put(slot, entry);

            Material mat = BadcoreSettingsGuiStyle.resolveMaterial(entry.material(), Material.NAME_TAG);
            boolean unlocked = entry.permission().isBlank() || permissions.hasPermission(viewer, entry.permission());
            boolean enabled = unlocked && repository.isTabViewEnabled(viewer.getUuid(), entry.id(), entry.defaultEnabled());

            ItemStack stack;
            if (!unlocked) {
                stack = BadcoreSettingsGuiStyle.item(
                        mat,
                        BadcoreSettingsGuiStyle.name(entry.name()),
                        BadcoreSettingsGuiStyle.subtitle("tab view"),
                        "",
                        "<#CF8484>✖ Locked</#CF8484>"
                );
            } else {
                stack = BadcoreSettingsGuiStyle.item(
                        mat,
                        enabled,
                        BadcoreSettingsGuiStyle.name(entry.name()),
                        BadcoreSettingsGuiStyle.subtitle("tab view"),
                        "",
                        BadcoreSettingsGuiStyle.statusLine("Status", enabled),
                        "",
                        BadcoreSettingsGuiStyle.action("Click to toggle")
                );
            }
            inventory.setItemStack(slot, stack);
        }

        int navRowStart = gui.navRowStart();
        if (gui.page() > 0) {
            inventory.setItemStack(navRowStart, BadcoreSettingsGuiStyle.item(
                    Material.ARROW,
                    BadcoreSettingsGuiStyle.name("Previous Page"),
                    BadcoreSettingsGuiStyle.subtitle("navigation")
            ));
        } else if (gui.openedFromSettings()) {
            inventory.setItemStack(navRowStart, BadcoreSettingsGuiStyle.backButton());
        }

        inventory.setItemStack(navRowStart + 4, BadcoreSettingsGuiStyle.item(
                Material.RED_DYE,
                BadcoreSettingsGuiStyle.dangerName("Hide All"),
                BadcoreSettingsGuiStyle.subtitle("tab view"),
                "",
                BadcoreSettingsGuiStyle.bullet("Hide all tags next to your name in TAB"),
                "",
                BadcoreSettingsGuiStyle.action("Click to hide all")
        ));

        if (gui.page() < gui.totalPages() - 1) {
            inventory.setItemStack(navRowStart + 8, BadcoreSettingsGuiStyle.item(
                    Material.ARROW,
                    BadcoreSettingsGuiStyle.name("Next Page"),
                    BadcoreSettingsGuiStyle.subtitle("navigation")
            ));
        } else {
            inventory.setItemStack(navRowStart + 8, BadcoreSettingsGuiStyle.closeButton());
        }

        BadcoreSettingsGuiStyle.fillEmpty(inventory);
    }

    private void renderChatColor(Player viewer, OpenGui gui, List<BadcoreSettings.ChatColorPreset> presets) {
        Inventory inventory = gui.inventory();
        clear(inventory);
        gui.slotMap().clear();

        String activeId = repository.getChatColorId(viewer.getUuid());
        int[] itemSlots = presets.size() > SLOTS_SMALL.length ? SLOTS_LARGE : SLOTS_SMALL;
        int pageSize = itemSlots.length;
        int start = gui.page() * pageSize;
        int end = Math.min(start + pageSize, presets.size());

        for (int i = start; i < end; i++) {
            BadcoreSettings.ChatColorPreset preset = presets.get(i);
            int slot = itemSlots[i - start];
            gui.slotMap().put(slot, preset);

            Material mat = BadcoreSettingsGuiStyle.resolveMaterial(preset.material(), Material.NAME_TAG);
            boolean unlocked = preset.permission().isBlank() || permissions.hasPermission(viewer, preset.permission());
            boolean selected = preset.id().equalsIgnoreCase(activeId);

            ItemStack stack;
            if (!unlocked) {
                stack = BadcoreSettingsGuiStyle.item(
                        mat,
                        BadcoreSettingsGuiStyle.name(preset.name()),
                        BadcoreSettingsGuiStyle.subtitle("chatcolor"),
                        "",
                        "<#CF8484>✖ Locked</#CF8484>"
                );
            } else {
                stack = BadcoreSettingsGuiStyle.item(
                        mat,
                        selected,
                        BadcoreSettingsGuiStyle.name(preset.name()),
                        BadcoreSettingsGuiStyle.subtitle("chatcolor"),
                        "",
                        BadcoreSettingsGuiStyle.statusLine("Selected", selected),
                        "",
                        BadcoreSettingsGuiStyle.action("Click to select")
                );
            }
            inventory.setItemStack(slot, stack);
        }

        int navRowStart = gui.navRowStart();
        if (gui.page() > 0) {
            inventory.setItemStack(navRowStart, BadcoreSettingsGuiStyle.item(
                    Material.ARROW,
                    BadcoreSettingsGuiStyle.name("Previous Page"),
                    BadcoreSettingsGuiStyle.subtitle("navigation")
            ));
        } else if (gui.openedFromSettings()) {
            inventory.setItemStack(navRowStart, BadcoreSettingsGuiStyle.backButton());
        }

        boolean isDefault = activeId == null || activeId.equalsIgnoreCase("default");
        inventory.setItemStack(navRowStart + 4, BadcoreSettingsGuiStyle.item(
                Material.RED_DYE,
                BadcoreSettingsGuiStyle.dangerName("Reset"),
                BadcoreSettingsGuiStyle.subtitle("chatcolor"),
                "",
                BadcoreSettingsGuiStyle.bullet("Reset your chat color to default"),
                BadcoreSettingsGuiStyle.statusLine("Selected", isDefault),
                "",
                BadcoreSettingsGuiStyle.action("Click to reset")
        ));

        if (gui.page() < gui.totalPages() - 1) {
            inventory.setItemStack(navRowStart + 8, BadcoreSettingsGuiStyle.item(
                    Material.ARROW,
                    BadcoreSettingsGuiStyle.name("Next Page"),
                    BadcoreSettingsGuiStyle.subtitle("navigation")
            ));
        } else {
            inventory.setItemStack(navRowStart + 8, BadcoreSettingsGuiStyle.closeButton());
        }

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
            case GENS -> handleGensClick(clicker, slot);
            case TABVIEW -> handleTabViewClick(clicker, current, slot);
            case CHATCOLOR -> handleChatColorClick(clicker, current, slot);
        }
    }

    private void handleHubClick(Player clicker, int slot) {
        switch (slot) {
            case HUB_SLOT_GLOBAL -> {
                playClick(clicker, 0.8f, 1.1f);
                bridge.requestState(clicker);
                openQoa(clicker);
            }
            case HUB_SLOT_GENS -> {
                playClick(clicker, 0.8f, 1.1f);
                openGens(clicker);
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

        if (!checkCooldownOrNotify(clicker)) {
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

        if (!checkCooldownOrNotify(clicker)) {
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

    private void handleGensClick(Player clicker, int slot) {
        if (slot == GENS_SLOT_BACK) {
            playClick(clicker, 0.8f, 0.9f);
            openHub(clicker);
            return;
        }
        if (slot == GENS_SLOT_CLOSE) {
            clicker.closeInventory();
            return;
        }
        if (slot == GENS_SLOT_NAMETAG_PERF) {
            if (!checkCooldownOrNotify(clicker)) {
                return;
            }
            clicker.closeInventory();
            executeIfRegistered(clicker, "hideparticles");
            return;
        }
        if (slot == GENS_SLOT_CHATCOLOR) {
            playClick(clicker, 0.8f, 1.1f);
            openChatColor(clicker, true, 0);
            return;
        }
        if (slot == GENS_SLOT_TABVIEW) {
            playClick(clicker, 0.8f, 1.1f);
            openTabView(clicker, true, 0);
            return;
        }
        if (slot == GENS_SLOT_SCOREBOARD) {
            playClick(clicker, 0.8f, 1.1f);
            String cmd = settingsSupplier.get().scoreboardCommand();
            if (cmd != null && !cmd.isBlank()) {
                executeIfRegistered(clicker, cmd.startsWith("/") ? cmd.substring(1) : cmd);
            }
        }
    }

    private void handleTabViewClick(Player clicker, OpenGui current, int slot) {
        int navRowStart = current.navRowStart();
        if (slot == navRowStart) {
            if (current.page() > 0) {
                playClick(clicker, 0.8f, 0.9f);
                openTabView(clicker, current.openedFromSettings(), current.page() - 1);
            } else if (current.openedFromSettings()) {
                playClick(clicker, 0.8f, 0.9f);
                openGens(clicker);
            }
            return;
        }
        if (slot == navRowStart + 8) {
            if (current.page() < current.totalPages() - 1) {
                playClick(clicker, 0.8f, 1.1f);
                openTabView(clicker, current.openedFromSettings(), current.page() + 1);
            } else {
                clicker.closeInventory();
            }
            return;
        }
        if (slot == navRowStart + 4) {
            List<BadcoreSettings.TabViewItem> entries = settingsSupplier.get().tabViewItems();
            List<String> ids = new ArrayList<>(entries.size());
            for (BadcoreSettings.TabViewItem item : entries) {
                ids.add(item.id());
            }
            repository.disableAllTabView(clicker.getUuid(), ids);
            playClick(clicker, 0.8f, 0.8f);
            renderTabView(clicker, current, entries);
            return;
        }

        Object mapped = current.slotMap().get(slot);
        if (!(mapped instanceof BadcoreSettings.TabViewItem entry)) {
            return;
        }
        boolean unlocked = entry.permission().isBlank() || permissions.hasPermission(clicker, entry.permission());
        if (!unlocked) {
            playDeny(clicker, 0.7f);
            return;
        }
        if (!checkCooldownOrNotify(clicker)) {
            return;
        }

        boolean next = repository.toggleTabView(clicker.getUuid(), entry.id(), entry.defaultEnabled());
        playOrb(clicker, 0.8f, next ? 1.3f : 0.8f);
        renderTabView(clicker, current, settingsSupplier.get().tabViewItems());
    }

    private void handleChatColorClick(Player clicker, OpenGui current, int slot) {
        int navRowStart = current.navRowStart();
        if (slot == navRowStart) {
            if (current.page() > 0) {
                playClick(clicker, 0.8f, 0.9f);
                openChatColor(clicker, current.openedFromSettings(), current.page() - 1);
            } else if (current.openedFromSettings()) {
                playClick(clicker, 0.8f, 0.9f);
                openGens(clicker);
            }
            return;
        }
        if (slot == navRowStart + 8) {
            if (current.page() < current.totalPages() - 1) {
                playClick(clicker, 0.8f, 1.1f);
                openChatColor(clicker, current.openedFromSettings(), current.page() + 1);
            } else {
                clicker.closeInventory();
            }
            return;
        }
        if (slot == navRowStart + 4) {
            repository.setChatColorId(clicker.getUuid(), "default");
            playClick(clicker, 0.8f, 0.9f);
            renderChatColor(clicker, current, settingsSupplier.get().chatColors());
            return;
        }

        Object mapped = current.slotMap().get(slot);
        if (!(mapped instanceof BadcoreSettings.ChatColorPreset preset)) {
            return;
        }
        boolean unlocked = preset.permission().isBlank() || permissions.hasPermission(clicker, preset.permission());
        if (!unlocked) {
            playDeny(clicker, 0.7f);
            return;
        }
        if (!checkCooldownOrNotify(clicker)) {
            return;
        }

        repository.setChatColorId(clicker.getUuid(), preset.id());
        playOrb(clicker, 0.8f, 1.2f);
        renderChatColor(clicker, current, settingsSupplier.get().chatColors());
    }

    private boolean checkCooldownOrNotify(Player clicker) {
        long remaining = repository.checkCooldown(clicker.getUuid(), settingsSupplier.get().cooldownMs());
        if (remaining > 0L) {
            String seconds = String.format(Locale.ROOT, "%.1f", Math.max(0.1, remaining / 1000.0));
            playDeny(clicker, 0.6f);
            messages.send(clicker, "cooldown", "seconds", seconds);
            return false;
        }
        return true;
    }

    private static void executeIfRegistered(Player player, String commandLine) {
        var cm = MinecraftServer.getCommandManager();
        if (cm == null || commandLine.isBlank()) {
            return;
        }
        String root = commandLine.split("\\s+", 2)[0];
        if (cm.commandExists(root)) {
            cm.execute(player, commandLine);
        }
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
                BadcoreSettingsGuiStyle.subtitle("practice setting"),
                "",
                BadcoreSettingsGuiStyle.bullet(desc),
                BadcoreSettingsGuiStyle.statusLine("Status", enabled),
                "",
                BadcoreSettingsGuiStyle.action("Click to toggle")
        );
    }

    private static ItemStack createVipToggle(BadcoreSettings cfg, Material mat, String title, boolean enabled, boolean unlocked, String desc) {
        String vipLabel = cfg.vipLabel() != null && !cfg.vipLabel().isBlank() ? cfg.vipLabel() : "VIP";
        String rawBadge = cfg.vipBadge() != null ? cfg.vipBadge() : " &7%vip%";
        String badge = FormattedText.legacyToMiniMessage(rawBadge.replace("%vip%", vipLabel));
        if (!unlocked) {
            return BadcoreSettingsGuiStyle.item(
                    mat,
                    BadcoreSettingsGuiStyle.name(title) + badge,
                    BadcoreSettingsGuiStyle.subtitle("vip setting"),
                    "",
                    BadcoreSettingsGuiStyle.bullet(desc),
                    "",
                    "<#CF8484>✖ " + vipLabel + " Rank Required</#CF8484>"
            );
        }
        return BadcoreSettingsGuiStyle.item(
                mat,
                BadcoreSettingsGuiStyle.name(title) + badge,
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

    private static void playOrb(Player player, float volume, float pitch) {
        player.playSound(Sound.sound(SoundEvent.ENTITY_EXPERIENCE_ORB_PICKUP, Sound.Source.MASTER, volume, pitch));
    }

    private static void playDeny(Player player, float volume) {
        player.playSound(Sound.sound(SoundEvent.ENTITY_VILLAGER_NO, Sound.Source.MASTER, volume, 1.0f));
    }
}
