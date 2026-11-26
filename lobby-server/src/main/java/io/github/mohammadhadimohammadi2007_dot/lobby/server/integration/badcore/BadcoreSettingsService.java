package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.IntegrationsConfig.BadcoreSettings;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.IntegrationStatus;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.player.PermissionService;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.util.Async;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerPluginMessageEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.event.trait.PlayerEvent;
import net.minestom.server.timer.TaskSchedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Coordinates badcoreSettings inside the lobby server: MariaDB persistence,
 * {@code badcore:settings} Velocity plugin-message sync, and the chest GUIs.
 */
public final class BadcoreSettingsService {

    public static final String INTEGRATION_NAME = "badcoreSettings";
    /** Built-in menu names usable via {@code open_menu: ...} in menus.yml / hotbar.yml. */
    public static final String MENU_HUB = "badcore_settings";
    public static final String MENU_GLOBAL = "badcore_settings_global";
    public static final String MENU_GENS = "badcore_settings_gens";
    public static final String MENU_DUELS = "badcore_settings_duels";
    public static final String MENU_TABVIEW = "badcore_settings_tabview";
    public static final String MENU_CHATCOLOR = "badcore_settings_chatcolor";

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String DISABLED_MESSAGE =
            "<white><bold>ꜱᴇʀᴠᴇʀ →</bold></white> <#CF8484>Settings are currently disabled.</#CF8484>";

    private final BadcoreSettingsDatabase database;
    private final BadcoreSettingsBridge bridge;
    private final BadcoreSettingsRepository repository;
    private final BadcoreSettingsGuis guis;
    private volatile BadcoreSettings config = BadcoreSettings.DISABLED;

    public BadcoreSettingsService(PermissionService permissions) {
        this.database = new BadcoreSettingsDatabase();
        this.bridge = new BadcoreSettingsBridge(this::onProxyQoaState, this::onProxyDuelsState);
        this.repository = new BadcoreSettingsRepository(database, bridge);
        this.guis = new BadcoreSettingsGuis(repository, bridge, permissions,
                () -> this.config, this::sendMessage);
    }

    /** Applies (or reloads) the {@code badcore-settings:} config from {@code integrations.yml}. */
    public IntegrationStatus reload(BadcoreSettings settings) {
        this.config = settings != null ? settings : BadcoreSettings.DISABLED;
        if (!this.config.enabled()) {
            database.closePoolOnly();
            return IntegrationStatus.disabled(INTEGRATION_NAME);
        }

        String dbError = database.init(this.config.database());
        syncOnlinePlayers();

        if (!this.config.database().enabled()) {
            return IntegrationStatus.active(INTEGRATION_NAME, "badcore:settings bridge only");
        }
        if (dbError != null) {
            return IntegrationStatus.failed(INTEGRATION_NAME, "MariaDB failed (" + dbError + "), running bridge-only");
        }
        return IntegrationStatus.active(INTEGRATION_NAME, "MariaDB + badcore:settings bridge");
    }

    public void register(EventNode<PlayerEvent> node) {
        node.addListener(PlayerPluginMessageEvent.class, event -> {
            if (config.enabled() && BadcoreSettingsBridge.CHANNEL.equals(event.getIdentifier())) {
                bridge.handleIncoming(event.getIdentifier(), event.getMessage());
            }
        });
        node.addListener(PlayerSpawnEvent.class, event -> {
            if (!event.isFirstSpawn() || !config.enabled()) {
                return;
            }
            Player player = event.getPlayer();
            repository.loadAllAsync(player.getUuid());
            MinecraftServer.getSchedulerManager()
                    .buildTask(() -> {
                        if (config.enabled() && player.isOnline()) {
                            bridge.requestState(player);
                            bridge.requestDuelsState(player);
                        }
                    })
                    .delay(TaskSchedule.tick(10))
                    .schedule();
        });
        node.addListener(PlayerDisconnectEvent.class,
                event -> repository.cleanupDisconnect(event.getPlayer().getUuid()));
        guis.register(node);
    }

    public boolean isEnabled() {
        return config.enabled();
    }

    public BadcoreSettings config() {
        return config;
    }

    public BadcoreSettingsDatabase database() {
        return database;
    }

    public BadcoreSettingsBridge bridge() {
        return bridge;
    }

    public BadcoreSettingsRepository repository() {
        return repository;
    }

    public BadcoreSettingsGuis guis() {
        return guis;
    }

    /** Opens the hub, global/qoa, gens, duels/practice, tabview, or chatcolor settings GUI for {@code player}. */
    public void openMenu(Player player, String section) {
        if (!config.enabled()) {
            player.sendMessage(MM.deserialize(DISABLED_MESSAGE));
            return;
        }
        String normalized = section == null ? "hub" : section.toLowerCase(Locale.ROOT);
        bridge.requestState(player);
        bridge.requestDuelsState(player);
        repository.loadAllAsync(player.getUuid()).whenComplete((unused, err) -> Async.onTickThread(() -> {
            if (!player.isOnline() || !config.enabled()) {
                return;
            }
            switch (normalized) {
                case "global", "qoa" -> guis.openQoa(player);
                case "gens" -> guis.openGens(player);
                case "duels", "practice" -> guis.openDuels(player);
                case "tabview" -> guis.openTabView(player, false, 0);
                case "chatcolor" -> guis.openChatColor(player, false, 0);
                default -> guis.openHub(player);
            }
        }));
    }

    public void sendMessage(CommandSender target, String key, String... placeholders) {
        String template = config.message(key);
        List<TagResolver> resolvers = new ArrayList<>();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            resolvers.add(Placeholder.unparsed(placeholders[i],
                    placeholders[i + 1] != null ? placeholders[i + 1] : ""));
        }
        target.sendMessage(MM.deserialize(template, TagResolver.resolver(resolvers)));
    }

    public void shutdown() {
        repository.shutdown();
        database.close();
    }

    private void syncOnlinePlayers() {
        var connectionManager = MinecraftServer.getConnectionManager();
        if (connectionManager == null) {
            return;
        }
        for (Player online : connectionManager.getOnlinePlayers()) {
            repository.loadAllAsync(online.getUuid());
            bridge.requestState(online);
            bridge.requestDuelsState(online);
        }
    }

    private void onProxyQoaState(UUID uuid, BadcoreSettingsRepository.QoaState state) {
        repository.updateQoaCacheFromProxy(uuid, state);
        var connectionManager = MinecraftServer.getConnectionManager();
        if (connectionManager == null) {
            return;
        }
        Player target = connectionManager.getOnlinePlayerByUuid(uuid);
        if (target != null && target.isOnline()) {
            Async.onTickThread(() -> {
                if (target.isOnline()) {
                    guis.rerenderIfOpen(target, BadcoreSettingsGuis.Kind.QOA);
                }
            });
        }
    }

    private void onProxyDuelsState(UUID uuid, BadcoreSettingsRepository.DuelsState state) {
        repository.updateDuelsCacheFromProxy(uuid, state);
        var connectionManager = MinecraftServer.getConnectionManager();
        if (connectionManager == null) {
            return;
        }
        Player target = connectionManager.getOnlinePlayerByUuid(uuid);
        if (target != null && target.isOnline()) {
            Async.onTickThread(() -> {
                if (target.isOnline()) {
                    guis.rerenderIfOpen(target, BadcoreSettingsGuis.Kind.DUELS);
                }
            });
        }
    }
}
