package io.github.mohammadhadimohammadi2007_dot.lobby.server.display;

import io.github.mohammadhadimohammadi2007_dot.lobby.common.ProtocolVersions;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder.ComponentColors;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder.LobbyText;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.player.PermissionService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Metadata;
import net.minestom.server.entity.MetadataDef;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.play.DestroyEntitiesPacket;
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket;
import net.minestom.server.network.packet.server.play.SetPassengersPacket;
import net.minestom.server.network.packet.server.play.SpawnEntityPacket;
import net.minestom.server.utils.PacketSendingUtils;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * UnlimitedNameTags implementation using packet-only {@link EntityType#TEXT_DISPLAY} passengers on
 * Minecraft 1.19.4+ clients.
 *
 * <p>Because the display entity is mounted directly on the player with {@link SetPassengersPacket},
 * it tracks movement smoothly on the client with zero teleport jitter, supports full RGB hex colors
 * ({@code #RRGGBB}), gradients, ItemsAdder glyphs, and multiple lines, and respects player visibility
 * and vanish through {@link Player#getViewers()}. Clients older than 1.19.4 automatically keep the
 * classic scoreboard-team nametag from {@link PlayerTeams}.
 *
 * <p>Optimized for 200+ players: text is rendered only on the tab refresh interval (not every 5 ticks),
 * steady-state viewer checks allocate zero packets or temporary sets, and multi-viewer updates are
 * sent with {@link PacketSendingUtils#sendGroupedPacket} so each packet is serialized once.
 */
final class UnlimitedNameTags {

    /** Center billboard constraint: always faces the viewer. */
    private static final byte BILLBOARD_CENTER = 3;
    private static final int DEFAULT_BACKGROUND = 0x40000000;
    private static final byte FLAG_SHADOW = 0x01;
    private static final byte FLAG_SEE_THROUGH = 0x02;
    private static final byte FLAG_DEFAULT_BACKGROUND = 0x04;
    private static final byte OPACITY_FULL = -1;
    private static final byte OPACITY_SNEAKING = 100;
    private static final double CLIENT_RANGE_PER_UNIT = 64.0;

    private final LobbyText lobbyText;
    private final PermissionService permissions;
    private final ToIntFunction<Player> protocolOf;
    private final Map<UUID, TagState> tags = new HashMap<>();

    UnlimitedNameTags(LobbyText lobbyText, PermissionService permissions, ToIntFunction<Player> protocolOf) {
        this.lobbyText = lobbyText;
        this.permissions = permissions;
        this.protocolOf = protocolOf;
    }

    /** Refreshes both text and viewers (convenience overload for tests). */
    void refresh(TabConfig.Nametags config, Collection<Player> online, TextCache textCache) {
        refresh(config, online, textCache, true);
    }

    /**
     * Syncs nametag viewers/sneak state, and re-renders nametag text when {@code refreshText} is true
     * (or for players seen for the first time). Board thread only.
     */
    void refresh(TabConfig.Nametags config, Collection<Player> online, TextCache textCache, boolean refreshText) {
        if (!config.enabled() || !config.unlimited()) {
            hideAll();
            return;
        }

        // Remove tags for players who are no longer online.
        if (!tags.isEmpty() && tags.size() > online.size()) {
            Set<UUID> onlineIds = new HashSet<>(online.size() * 2);
            for (Player p : online) {
                onlineIds.add(p.getUuid());
            }
            Iterator<Map.Entry<UUID, TagState>> it = tags.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, TagState> entry = it.next();
                if (!onlineIds.contains(entry.getKey())) {
                    destroyForAll(entry.getValue());
                    it.remove();
                }
            }
        }

        TagLook look = TagLook.of(config);
        for (Player target : online) {
            TagState state = tags.computeIfAbsent(target.getUuid(), ignored -> new TagState());
            boolean needText = refreshText || state.lastText == null;
            Component rendered = needText ? renderNameTag(config, target, textCache) : state.lastText;
            boolean sneaking = target.isSneaking();
            boolean changed = !rendered.equals(state.lastText)
                    || sneaking != state.lastSneaking
                    || !look.equals(state.lastLook);
            state.lastText = rendered;
            state.lastSneaking = sneaking;
            state.lastLook = look;

            Set<? extends Player> currentViewers = target.getViewers();
            var targetPos = target.getPosition();
            double maxDistSq = config.viewDistance() * config.viewDistance();

            // 1. Despawn for viewers who can no longer see this player or moved out of nametag view-distance.
            List<Player> toDestroy = null;
            Iterator<Player> existingIt = state.viewers.iterator();
            while (existingIt.hasNext()) {
                Player viewer = existingIt.next();
                if (!viewer.isOnline() || !currentViewers.contains(viewer)
                        || viewer.getPosition().distanceSquared(targetPos) > maxDistSq) {
                    existingIt.remove();
                    if (viewer.isOnline()) {
                        if (toDestroy == null) {
                            toDestroy = new ArrayList<>();
                        }
                        toDestroy.add(viewer);
                    }
                }
            }
            if (toDestroy != null) {
                PacketSendingUtils.sendGroupedPacket(toDestroy, new DestroyEntitiesPacket(state.entityId));
            }

            // 2. Find newly visible modern viewers within view-distance.
            List<Player> newlyAdded = null;
            for (Player viewer : currentViewers) {
                if (viewer == target || !viewer.isOnline() || state.viewers.contains(viewer)) {
                    continue;
                }
                if (protocolOf.applyAsInt(viewer) >= ProtocolVersions.V1_19_4
                        && viewer.getPosition().distanceSquared(targetPos) <= maxDistSq) {
                    if (newlyAdded == null) {
                        newlyAdded = new ArrayList<>();
                    }
                    newlyAdded.add(viewer);
                }
            }

            // 3. Send grouped metadata update to existing viewers if text/sneak/look changed.
            EntityMetaDataPacket metaPacket = null;
            if (changed && !state.viewers.isEmpty()) {
                metaPacket = new EntityMetaDataPacket(state.entityId, metadata(rendered, sneaking, config));
                PacketSendingUtils.sendGroupedPacket(state.viewers, metaPacket);
            }

            // 4. Spawn + mount for newly added viewers (serialized once via sendGroupedPacket).
            if (newlyAdded != null) {
                if (metaPacket == null) {
                    metaPacket = new EntityMetaDataPacket(state.entityId, metadata(rendered, sneaking, config));
                }
                SpawnEntityPacket spawnPacket = new SpawnEntityPacket(
                        state.entityId, state.entityUuid, EntityType.TEXT_DISPLAY,
                        target.getPosition(), 0f, 0, Vec.ZERO);
                SetPassengersPacket passengersPacket = new SetPassengersPacket(
                        target.getEntityId(), passengerIds(target, state.entityId));
                PacketSendingUtils.sendGroupedPacket(newlyAdded, spawnPacket);
                PacketSendingUtils.sendGroupedPacket(newlyAdded, metaPacket);
                PacketSendingUtils.sendGroupedPacket(newlyAdded, passengersPacket);
                state.viewers.addAll(newlyAdded);
            }
        }
    }

    /** Destroys every nametag entity (e.g. when disabled or switched to vanilla mode on reload). */
    void hideAll() {
        if (tags.isEmpty()) {
            return;
        }
        for (TagState state : tags.values()) {
            destroyForAll(state);
        }
        tags.clear();
    }

    /** Forgets a disconnected player (both as target and as viewer). Board thread only. */
    void forget(UUID playerId) {
        TagState removed = tags.remove(playerId);
        if (removed != null) {
            destroyForAll(removed);
        }
        for (TagState state : tags.values()) {
            state.viewers.removeIf(p -> p.getUuid().equals(playerId));
        }
    }

    /** Builds the nametag component with full RGB hex color support. Package-private for tests. */
    Component renderNameTag(TabConfig.Nametags config, Player target, TextCache textCache) {
        Component prefix = textCache.about(config.prefix().modern(), target, false);
        Component suffix = textCache.about(config.suffix().modern(), target, false);
        String rawLpPrefix = permissions.meta(target).prefix();
        TextColor nameColor = config.hexNameColor() != null
                ? config.hexNameColor()
                : ComponentColors.lastColor(rawLpPrefix, prefix);
        Component coloredName = nameColor != null
                ? Component.text(target.getUsername(), nameColor)
                : Component.text(target.getUsername());

        if (config.lines().isEmpty()) {
            return Component.empty().append(prefix).append(coloredName).append(suffix);
        }
        List<Component> renderedLines = new ArrayList<>(config.lines().size());
        var nameTag = Placeholder.component("name", coloredName);
        for (String line : config.lines()) {
            renderedLines.add(lobbyText.render(line, target, nameTag));
        }
        return Component.join(JoinConfiguration.newlines(), renderedLines);
    }

    private static Map<Integer, Metadata.Entry<?>> metadata(Component text, boolean sneaking, TabConfig.Nametags config) {
        Map<Integer, Metadata.Entry<?>> meta = new LinkedHashMap<>(8);
        meta.put(MetadataDef.Display.BILLBOARD_CONSTRAINTS.index(), Metadata.Byte(BILLBOARD_CENTER));
        meta.put(MetadataDef.Display.TRANSLATION.index(), Metadata.Vector3(new Vec(0, config.yOffset(), 0)));
        if (config.scale() != 1.0) {
            meta.put(MetadataDef.Display.SCALE.index(),
                    Metadata.Vector3(new Vec(config.scale(), config.scale(), config.scale())));
        }
        float range = (float) Math.max(1.0, config.viewDistance() / CLIENT_RANGE_PER_UNIT);
        meta.put(MetadataDef.Display.VIEW_RANGE.index(), Metadata.Float(range));
        meta.put(MetadataDef.TextDisplay.TEXT.index(), Metadata.Component(text));
        meta.put(MetadataDef.TextDisplay.BACKGROUND_COLOR.index(),
                Metadata.VarInt(config.background() == null ? DEFAULT_BACKGROUND : config.background()));
        meta.put(MetadataDef.TextDisplay.TEXT_OPACITY.index(),
                Metadata.Byte(sneaking ? OPACITY_SNEAKING : OPACITY_FULL));
        int flags = 0;
        if (config.textShadow()) {
            flags |= FLAG_SHADOW;
        }
        if (config.seeThrough() && !sneaking) {
            flags |= FLAG_SEE_THROUGH;
        }
        if (config.background() == null) {
            flags |= FLAG_DEFAULT_BACKGROUND;
        }
        meta.put(MetadataDef.TextDisplay.TEXT_DISPLAY_FLAGS.index(), Metadata.Byte((byte) flags));
        return meta;
    }

    private static List<Integer> passengerIds(Player target, int tagEntityId) {
        List<Entity> realPassengers = target.getPassengers();
        if (realPassengers.isEmpty()) {
            return List.of(tagEntityId);
        }
        List<Integer> ids = new ArrayList<>(realPassengers.size() + 1);
        for (Entity passenger : realPassengers) {
            ids.add(passenger.getEntityId());
        }
        ids.add(tagEntityId);
        return ids;
    }

    private static void destroyForAll(TagState state) {
        if (state.viewers.isEmpty()) {
            return;
        }
        List<Player> onlineViewers = new ArrayList<>(state.viewers.size());
        for (Player viewer : state.viewers) {
            if (viewer.isOnline()) {
                onlineViewers.add(viewer);
            }
        }
        if (!onlineViewers.isEmpty()) {
            PacketSendingUtils.sendGroupedPacket(onlineViewers, new DestroyEntitiesPacket(state.entityId));
        }
        state.viewers.clear();
    }

    private record TagLook(double yOffset, double scale, @Nullable Integer background, boolean textShadow,
                           boolean seeThrough, double viewDistance) {
        static TagLook of(TabConfig.Nametags c) {
            return new TagLook(c.yOffset(), c.scale(), c.background(), c.textShadow(), c.seeThrough(), c.viewDistance());
        }
    }

    private static final class TagState {
        final int entityId = Entity.generateId();
        final UUID entityUuid = UUID.randomUUID();
        final Set<Player> viewers = new HashSet<>();
        @Nullable Component lastText;
        boolean lastSneaking;
        @Nullable TagLook lastLook;
    }
}
