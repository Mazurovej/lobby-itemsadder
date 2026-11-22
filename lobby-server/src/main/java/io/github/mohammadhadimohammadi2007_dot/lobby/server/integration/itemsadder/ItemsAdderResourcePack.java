package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.itemsadder;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.IntegrationsConfig;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder.LobbyText;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerResourcePackStatusEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.event.trait.PlayerEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Sends the configured ItemsAdder resource pack (external URL) to joining players and kicks players who
 * decline or fail to load it when {@code required: true}.
 */
public final class ItemsAdderResourcePack {

    private static final Logger LOGGER = LoggerFactory.getLogger(ItemsAdderResourcePack.class);

    private final LobbyText text;
    private volatile @Nullable ActivePack active;

    public ItemsAdderResourcePack(LobbyText text) {
        this.text = text;
    }

    /** Updates the active resource pack settings (called on startup and {@code /setup-lobby reload}). */
    public void configure(boolean enabled, IntegrationsConfig.ItemsAdder.ResourcePack settings, String resolvedHash) {
        if (!enabled || !settings.hasUrl()) {
            active = null;
            return;
        }
        URI uri;
        try {
            uri = URI.create(settings.url());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("ItemsAdder: ignoring invalid resource-pack URL '{}': {}", settings.url(), e.getMessage());
            active = null;
            return;
        }
        String hash = resolvedHash == null ? "" : resolvedHash;
        UUID packId = UUID.nameUUIDFromBytes(("lobby-itemsadder:" + settings.url() + ":" + hash).getBytes(StandardCharsets.UTF_8));
        ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(packId, uri, hash);
        active = new ActivePack(info, settings.required(), settings.prompt(), settings.kickMessage(), settings.replace());
    }

    /** True if a resource pack URL is currently active and will be sent to players. */
    public boolean active() {
        return active != null;
    }

    /** Registers join and status listeners. */
    public void register(EventNode<PlayerEvent> events) {
        events.addListener(PlayerSpawnEvent.class, event -> {
            if (event.isFirstSpawn()) {
                sendTo(event.getPlayer());
            }
        });
        events.addListener(PlayerResourcePackStatusEvent.class, this::onStatus);
    }

    /** Sends the active resource pack to {@code player}, if configured. */
    public void sendTo(Player player) {
        ActivePack pack = active;
        if (pack == null) {
            return;
        }
        ResourcePackRequest.Builder request = ResourcePackRequest.resourcePackRequest()
                .packs(pack.info())
                .required(pack.required())
                .replace(pack.replace());
        if (!pack.prompt().isBlank()) {
            request.prompt(text.render(pack.prompt(), player));
        }
        player.sendResourcePacks(request.build());
    }

    private void onStatus(PlayerResourcePackStatusEvent event) {
        ActivePack pack = active;
        if (pack == null || !pack.info().id().equals(event.getPackUuid())) {
            return;
        }
        ResourcePackStatus status = event.getStatus();
        Player player = event.getPlayer();
        if (status == ResourcePackStatus.SUCCESSFULLY_LOADED) {
            LOGGER.debug("ItemsAdder resource pack loaded for {}", player.getUsername());
            return;
        }
        if (isFailure(status)) {
            LOGGER.debug("ItemsAdder resource pack status {} for {}", status, player.getUsername());
            if (pack.required()) {
                Component reason = pack.kickMessage().isBlank()
                        ? Component.text("You must accept the server resource pack.")
                        : text.render(pack.kickMessage(), player);
                player.kick(reason);
            }
        }
    }

    private static boolean isFailure(ResourcePackStatus status) {
        return switch (status) {
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> true;
            case ACCEPTED, DOWNLOADED, SUCCESSFULLY_LOADED -> false;
        };
    }

    private record ActivePack(
            ResourcePackInfo info,
            boolean required,
            String prompt,
            String kickMessage,
            boolean replace
    ) {
    }
}
