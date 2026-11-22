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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** Refreshes nametag entities for all online players. Board thread only. */
    void refresh(TabConfig.Nametags config, Collection<Player> online, TextCache textCache) {
        if (!config.enabled() || !config.unlimited()) {
            hideAll(online);
            return;
        }
        Map<UUID, Player> byId = new HashMap<>(online.size() * 2);
        for (Player p : online) {
            byId.put(p.getUuid(), p);
        }

        // Remove tags for players who are no longer online.
        List<UUID> departed = new ArrayList<>();
        for (UUID id : tags.keySet()) {
            if (!byId.containsKey(id)) {
                departed.add(id);
            }
        }
        for (UUID id : departed) {
            destroyForAll(tags.remove(id), byId);
        }

        TagLook look = TagLook.of(config);
        for (Player target : online) {
            TagState state = tags.computeIfAbsent(target.getUuid(), ignored -> new TagState());
            Component rendered = renderNameTag(config, target, textCache);
            boolean sneaking = target.isSneaking();
            boolean changed = !rendered.equals(state.lastText)
                    || sneaking != state.lastSneaking
                    || !look.equals(state.lastLook);
            state.lastText = rendered;
            state.lastSneaking = sneaking;
            state.lastLook = look;

            Set<UUID> desired = new HashSet<>();
            for (Player viewer : target.getViewers()) {
                if (viewer != target && viewer.isOnline()
                        && protocolOf.applyAsInt(viewer) >= ProtocolVersions.V1_19_4) {
                    desired.add(viewer.getUuid());
                }
            }

            // Despawn for viewers who can no longer see this player.
            state.viewers.removeIf(viewerId -> {
                if (desired.contains(viewerId)) {
                    return false;
                }
                Player viewer = byId.get(viewerId);
                if (viewer != null && viewer.isOnline()) {
                    viewer.sendPacket(new DestroyEntitiesPacket(state.entityId));
                }
                return true;
            });

            Map<Integer, Metadata.Entry<?>> metadata = null;
            for (UUID viewerId : desired) {
                Player viewer = byId.get(viewerId);
                if (viewer == null || !viewer.isOnline()) {
                    continue;
                }
                if (state.viewers.add(viewerId)) {
                    if (metadata == null) {
                        metadata = metadata(rendered, sneaking, config);
                    }
                    viewer.sendPacket(new SpawnEntityPacket(
                            state.entityId, state.entityUuid, EntityType.TEXT_DISPLAY,
                            target.getPosition(), 0f, 0, Vec.ZERO));
                    viewer.sendPacket(new EntityMetaDataPacket(state.entityId, metadata));
                    viewer.sendPacket(new SetPassengersPacket(target.getEntityId(), passengerIds(target, state.entityId)));
                } else if (changed) {
                    if (metadata == null) {
                        metadata = metadata(rendered, sneaking, config);
                    }
                    viewer.sendPacket(new EntityMetaDataPacket(state.entityId, metadata));
                }
            }
        }
    }

    /** Immediately updates a player's nametag metadata (e.g. when they start/stop sneaking). Board thread only. */
    void updateSneak(Player target, TabConfig.Nametags config, Collection<Player> online) {
        if (!config.enabled() || !config.unlimited()) {
            return;
        }
        TagState state = tags.get(target.getUuid());
        if (state == null || state.lastText == null || state.viewers.isEmpty()) {
            return;
        }
        boolean sneaking = target.isSneaking();
        if (sneaking == state.lastSneaking) {
            return;
        }
        state.lastSneaking = sneaking;
        EntityMetaDataPacket packet = new EntityMetaDataPacket(state.entityId, metadata(state.lastText, sneaking, config));
        for (Player viewer : online) {
            if (state.viewers.contains(viewer.getUuid()) && viewer.isOnline()) {
                viewer.sendPacket(packet);
            }
        }
    }

    /** Destroys every nametag entity (e.g. when disabled or switched to vanilla mode on reload). */
    void hideAll(Collection<Player> online) {
        if (tags.isEmpty()) {
            return;
        }
        Map<UUID, Player> byId = new HashMap<>(online.size() * 2);
        for (Player p : online) {
            byId.put(p.getUuid(), p);
        }
        for (TagState state : tags.values()) {
            destroyForAll(state, byId);
        }
        tags.clear();
    }

    /** Forgets a disconnected player (both as target and as viewer). Board thread only. */
    void forget(UUID playerId, Collection<Player> online) {
        TagState removed = tags.remove(playerId);
        if (removed != null) {
            Map<UUID, Player> byId = new HashMap<>(online.size() * 2);
            for (Player p : online) {
                byId.put(p.getUuid(), p);
            }
            destroyForAll(removed, byId);
        }
        for (TagState state : tags.values()) {
            state.viewers.remove(playerId);
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
        Map<Integer, Metadata.Entry<?>> meta = new LinkedHashMap<>();
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

    private static void destroyForAll(@Nullable TagState state, Map<UUID, Player> byId) {
        if (state == null || state.viewers.isEmpty()) {
            return;
        }
        DestroyEntitiesPacket packet = new DestroyEntitiesPacket(state.entityId);
        for (UUID viewerId : state.viewers) {
            Player viewer = byId.get(viewerId);
            if (viewer != null && viewer.isOnline()) {
                viewer.sendPacket(packet);
            }
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
        final Set<UUID> viewers = new HashSet<>();
        @Nullable Component lastText;
        boolean lastSneaking;
        @Nullable TagLook lastLook;
    }
}
