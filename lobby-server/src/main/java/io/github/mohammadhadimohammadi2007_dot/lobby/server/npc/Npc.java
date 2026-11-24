package io.github.mohammadhadimohammadi2007_dot.lobby.server.npc;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.entity.ClientObject;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.entity.EntityPart;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.entity.WorldScope;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.hologram.Hologram;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.hologram.HologramText;
import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.Metadata;
import net.minestom.server.entity.MetadataDef;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerSkin;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.play.EntityAttributesPacket;
import net.minestom.server.network.packet.server.play.EntityHeadLookPacket;
import net.minestom.server.network.packet.server.play.EntityRotationPacket;
import net.minestom.server.network.packet.server.play.EntityTeleportPacket;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One NPC, shown to the players near it with packets only: the body (a player with a skin, or any other
 * entity) and its name tag hologram above it.
 *
 * <p>Body and name are one object, so a click on either is a click on the NPC, and both share one render
 * per group of viewers. Turning towards players and scaling up while looked at are done per viewer with
 * small packets and never re-render or split viewer groups.
 *
 * <p>Viewers are grouped by what their client can draw, on top of the name tag's own groups:
 * <ul>
 *   <li>clients older than 1.19.4 (1.8 through ViaRewind) get the player-list entry listed, and the
 *       renderer removes it from their tab list a few seconds later, because those clients often keep
 *       the default skin when the entry is not listed while the NPC appears. Newer clients get an entry
 *       that is never listed;</li>
 *   <li>clients that draw the {@code scale} attribute (1.20.5+) get the NPC's size and a name tag at
 *       the matching height; older ones see a normal-size NPC with the name tag right above it.</li>
 * </ul>
 */
public final class Npc implements ClientObject {

    /** NPC objects are named {@code npc:<name>} in the renderer, so they never clash with holograms. */
    public static final String OBJECT_PREFIX = "npc:";
    /** Entity flag 0x40: glowing outline. */
    private static final byte GLOWING = 0x40;
    /** Every outer skin layer: hat, jacket, sleeves, trouser legs and cape. */
    private static final byte ALL_SKIN_LAYERS = 0x7F;
    /** Rotations closer than this, in degrees, are not sent again. */
    private static final float TURN_STEP_DEGREES = 2f;
    private static final int PACK_SHIFT = 32;
    private static final long PACK_MASK = 0xFFFFFFFFL;
    /** The seat is the last part, so adding or removing it never shifts the body or the name lines. */
    private static final int BODY = 0;
    /** On 1.19.4+ clients with a non-empty name tag, part 1 is the single text display above the body. */
    private static final int NAME_TAG = 1;
    /** Comfort padding around the entity hitbox so aiming at arms/hat triggers look-scaling smoothly. */
    private static final double LOOK_PADDING_XZ = 0.15;
    private static final double LOOK_PADDING_Y = 0.10;
    /** Entity flags: 0x20 is "invisible". */
    private static final byte INVISIBLE = 0x20;
    /** Armor stand flags: 0x01 small, 0x08 no base plate, 0x10 marker (no hitbox). */
    private static final byte HIDDEN_STAND = 0x01 | 0x08 | 0x10;

    /**
     * What decides how the NPC looks for one viewer.
     *
     * @param text   the name tag's group (tier, and the viewer for per-player names)
     * @param scaled true if the viewer's client draws the NPC's size; always false at normal size, so
     *               normal-size NPCs never split viewers by it
     */
    record Key(HologramText.Variant text, boolean scaled) {
    }

    /** Last overridden look-scale sent to a viewer and the NPC data revision it was computed against. */
    private record ScaleState(double scale, int revision) {
    }

    private final NpcData data;
    private final Hologram.Services services;
    /** The rotation each viewer last got, packed into a long, so unchanged turns are not sent again. */
    private final Map<UUID, Long> lastLook = new ConcurrentHashMap<>();
    /** Viewers whose scale is currently overridden by {@code scale_up_when_looking}. */
    private final Map<UUID, ScaleState> lastLookScale = new ConcurrentHashMap<>();
    /** Bumped on admin edits so active look-scale overrides re-apply cleanly after content updates. */
    private final AtomicInteger configRevision = new AtomicInteger();

    public Npc(NpcData data, Hologram.Services services) {
        this.data = data;
        this.services = services;
    }

    /** The stored NPC behind this one. */
    public NpcData data() {
        return data;
    }

    /** Marks the NPC's settings as changed so per-viewer scale overrides re-sync on the next refresh. */
    void onDataChanged() {
        configRevision.incrementAndGet();
    }

    @Override
    public String name() {
        return OBJECT_PREFIX + data.name();
    }

    @Override
    public Pos position() {
        return data.position();
    }

    @Override
    public WorldScope scope() {
        return services.scope();
    }

    @Override
    public double viewDistance() {
        return data.viewDistance();
    }

    @Override
    public boolean visibleTo(Player viewer) {
        String permission = data.permission();
        return permission.isEmpty() || services.permissions().hasPermission(viewer, permission);
    }

    @Override
    public Object variantKey(Player viewer) {
        boolean mirror = data.skin().kind() == NpcSkin.Kind.MIRROR;
        HologramText.Variant text = HologramText.variant(data.nameTag(), services.text(), viewer,
                services.legacyClient().test(viewer), mirror);
        return new Key(text, data.scale() != 1 && services.scaleSupported().test(viewer));
    }

    @Override
    public int updateIntervalTicks() {
        return data.nameTag().effectiveUpdateIntervalTicks();
    }

    /**
     * The body first (its entity id is always the first one), then the name tag, then the seat of a
     * sitting NPC.
     */
    @Override
    public List<EntityPart> render(Object key, Player viewer) {
        Key npcKey = key instanceof Key known ? known : new Key(new HologramText.Variant(false, null), false);
        boolean legacy = npcKey.text().legacy();
        List<EntityPart> parts = new ArrayList<>();
        parts.add(body(viewer, npcKey));
        if (!data.nameTag().lines().isEmpty()) {
            // The name tag sits on top of the NPC as the viewer's client draws it.
            Vec drop = new Vec(0, data.height(npcKey.scaled()) - data.height(true), 0);
            for (EntityPart line : HologramText.parts(data.nameTag(), services.text(), viewer, legacy)) {
                parts.add(drop.isZero() ? line : line.moved(drop));
            }
        }
        if (data.pose() == NpcPose.SITTING) {
            parts.add(seat(legacy));
        }
        return parts;
    }

    private EntityPart body(Player viewer, Key key) {
        Map<Integer, Metadata.Entry<?>> metadata = new HashMap<>();
        if (data.glowing()) {
            metadata.put(MetadataDef.ENTITY_FLAGS.index(), Metadata.Byte(GLOWING));
        }
        metadata.put(MetadataDef.HAS_NO_GRAVITY.index(), Metadata.Boolean(true));
        metadata.put(MetadataDef.IS_SILENT.index(), Metadata.Boolean(true));
        if (data.pose().entityPose() != net.minestom.server.entity.EntityPose.STANDING) {
            metadata.put(MetadataDef.POSE.index(), Metadata.Pose(data.pose().entityPose()));
        }
        Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
        var resolver = io.github.mohammadhadimohammadi2007_dot.lobby.server.item.ItemResolver.Active.get();
        for (var item : data.equipmentSpecs().entrySet()) {
            equipment.put(item.getKey(), resolver.build(item.getValue()));
        }
        double scale = key.scaled() ? data.scale() : 1;
        if (!data.isPlayer()) {
            return new EntityPart(data.type(), data.position(), metadata, equipment)
                    .withUuid(data.profileId()).withScale(scale);
        }
        metadata.put(MetadataDef.Avatar.DISPLAYED_MODEL_PARTS_FLAGS.index(), Metadata.Byte(ALL_SKIN_LAYERS));
        PlayerSkin skin = data.skin().kind() == NpcSkin.Kind.MIRROR ? viewer.getSkin() : data.resolvedSkin();
        return new EntityPart(EntityType.PLAYER, data.position(), metadata, equipment,
                profile(viewer, key, skin)).withUuid(data.profileId()).withScale(scale);
    }

    /**
     * Listed for good with {@code show_in_tab}; otherwise listed for a moment on old clients, so their
     * skin loads, and never on new ones.
     */
    private EntityPart.Profile profile(Player viewer, Key key, PlayerSkin skin) {
        if (data.showInTab()) {
            return new EntityPart.Profile(data.profileName(), skin, EntityPart.Listing.LISTED, tabName(viewer, key));
        }
        EntityPart.Listing listing = key.text().legacy() ? EntityPart.Listing.TEMPORARY : EntityPart.Listing.HIDDEN;
        return new EntityPart.Profile(data.profileName(), skin, listing, null);
    }

    /** The first line of the name tag, or the NPC's name if it has none. */
    private Component tabName(Player viewer, Key key) {
        List<Component> lines = data.nameTag().lines().isEmpty() ? List.of()
                : HologramText.lines(data.nameTag(), services.text(), viewer, key.text().legacy());
        return lines.isEmpty() ? Component.text(data.name()) : lines.getFirst();
    }

    /**
     * The invisible entity a sitting NPC rides, as FancyNpcs does it: an empty text display for clients
     * that have display entities, an invisible marker armor stand for older ones.
     */
    private EntityPart seat(boolean legacy) {
        Pos at = data.position().withView(0, 0);
        EntityPart seat;
        if (legacy) {
            seat = new EntityPart(EntityType.ARMOR_STAND, at, Map.of(
                    MetadataDef.ENTITY_FLAGS.index(), Metadata.Byte(INVISIBLE),
                    MetadataDef.HAS_NO_GRAVITY.index(), Metadata.Boolean(true),
                    MetadataDef.ArmorStand.ARMOR_STAND_FLAGS.index(), Metadata.Byte(HIDDEN_STAND)));
        } else {
            seat = new EntityPart(EntityType.TEXT_DISPLAY, at, Map.of(
                    MetadataDef.TextDisplay.BACKGROUND_COLOR.index(), Metadata.VarInt(0)));
        }
        return seat.withPassengers(List.of(BODY));
    }

    @Override
    public boolean hasViewerPackets() {
        return data.turnToPlayer() || data.scaleUpWhenLooking() || !lastLookScale.isEmpty();
    }

    /**
     * Sends per-viewer rotation packets (when {@code turn_to_player} is on) and per-viewer scale
     * attribute + name-tag height updates (when {@code scale_up_when_looking} is on). Unchanged state
     * sends nothing and allocates no packet list.
     */
    @Override
    public List<ServerPacket> viewerPackets(Player viewer, List<Integer> entityIds, boolean justSpawned) {
        if (entityIds.isEmpty()) {
            return List.of();
        }
        int body = entityIds.get(BODY);
        List<ServerPacket> packets = null;

        if (data.turnToPlayer()) {
            Pos npc = data.position();
            Pos target = viewer.getPosition();
            float yaw = npc.yaw();
            float pitch = npc.pitch();
            if (npc.distanceSquared(target) <= data.turnDistance() * data.turnDistance()) {
                double dx = target.x() - npc.x();
                double dz = target.z() - npc.z();
                double dy = (target.y() + viewer.getEyeHeight()) - (npc.y() + data.type().eyeHeight());
                yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            }
            long packed = pack(round(yaw), round(pitch));
            Long previous = lastLook.put(viewer.getUuid(), packed);
            if (justSpawned || previous == null || previous != packed) {
                packets = new ArrayList<>(4);
                packets.add(new EntityRotationPacket(body, yaw, pitch, true));
                packets.add(new EntityHeadLookPacket(body, yaw));
            }
        }

        UUID viewerId = viewer.getUuid();
        ScaleState previousScale = lastLookScale.get(viewerId);
        if (data.scaleUpWhenLooking() || previousScale != null) {
            boolean canScale = data.type().shouldSendAttributes() && services.scaleSupported().test(viewer);
            if (data.scaleUpWhenLooking() && canScale) {
                double probeScale = previousScale != null ? Math.max(data.scale(), previousScale.scale()) : data.scale();
                double targetScale = isLookingAt(viewer, probeScale) ? data.lookedScale() : data.scale();
                if (Double.compare(targetScale, data.scale()) != 0) {
                    int rev = configRevision.get();
                    if (justSpawned || previousScale == null
                            || Double.compare(previousScale.scale(), targetScale) != 0
                            || previousScale.revision() != rev) {
                        lastLookScale.put(viewerId, new ScaleState(targetScale, rev));
                        if (packets == null) {
                            packets = new ArrayList<>(2);
                        }
                        appendScalePackets(packets, viewer, entityIds, body, targetScale);
                    }
                } else if (lastLookScale.remove(viewerId) != null) {
                    if (packets == null) {
                        packets = new ArrayList<>(2);
                    }
                    appendScalePackets(packets, viewer, entityIds, body, data.scale());
                }
            } else if (lastLookScale.remove(viewerId) != null && canScale) {
                if (packets == null) {
                    packets = new ArrayList<>(2);
                }
                appendScalePackets(packets, viewer, entityIds, body, data.scale());
            }
        }

        return packets == null ? List.of() : packets;
    }

    private void appendScalePackets(List<ServerPacket> packets, Player viewer, List<Integer> entityIds,
                                    int bodyEntityId, double scale) {
        packets.add(new EntityAttributesPacket(bodyEntityId,
                List.of(new EntityAttributesPacket.Property(Attribute.SCALE, scale, List.of()))));
        if (!data.nameTag().lines().isEmpty()
                && !services.legacyClient().test(viewer)
                && entityIds.size() > NAME_TAG) {
            Pos npc = data.position();
            Pos namePos = new Pos(npc.x(), npc.y() + data.heightForScale(scale) + NpcData.NAME_GAP, npc.z());
            packets.add(new EntityTeleportPacket(entityIds.get(NAME_TAG), namePos, Vec.ZERO, 0, false));
        }
    }

    /**
     * Exact ray-AABB intersection from the viewer's eyes along their look vector against this NPC's
     * hitbox at {@code effectiveScale}. Zero heap allocations.
     */
    boolean isLookingAt(Player viewer, double effectiveScale) {
        Pos npc = data.position();
        Pos viewerPos = viewer.getPosition();
        double maxDist = Math.min(data.lookScaleDistance(), data.viewDistance());
        double height = data.heightForScale(effectiveScale);
        double halfW = Math.max(0.4, data.type().width()) * effectiveScale * 0.5 + LOOK_PADDING_XZ;

        double eyeX = viewerPos.x();
        double eyeY = viewerPos.y() + viewer.getEyeHeight();
        double eyeZ = viewerPos.z();

        double toX = npc.x() - eyeX;
        double toY = (npc.y() + height * 0.5) - eyeY;
        double toZ = npc.z() - eyeZ;
        double boundRadius = halfW * 1.5 + height * 0.5 + LOOK_PADDING_Y;
        double maxCheck = maxDist + boundRadius;
        if (toX * toX + toY * toY + toZ * toZ > maxCheck * maxCheck) {
            return false;
        }

        double yawRad = Math.toRadians(viewerPos.yaw());
        double pitchRad = Math.toRadians(viewerPos.pitch());
        double cosPitch = Math.cos(pitchRad);
        double dirX = -cosPitch * Math.sin(yawRad);
        double dirY = -Math.sin(pitchRad);
        double dirZ = cosPitch * Math.cos(yawRad);

        // Fast reject when the NPC is behind the player's camera.
        if (toX * dirX + toY * dirY + toZ * dirZ < -boundRadius) {
            return false;
        }

        double tMin = 0.0;
        double tMax = maxDist;

        // X slab
        if (Math.abs(dirX) < 1e-9) {
            if (eyeX < npc.x() - halfW || eyeX > npc.x() + halfW) {
                return false;
            }
        } else {
            double inv = 1.0 / dirX;
            double t1 = (npc.x() - halfW - eyeX) * inv;
            double t2 = (npc.x() + halfW - eyeX) * inv;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax) return false;
        }

        // Y slab
        double minY = npc.y();
        double maxY = npc.y() + height + LOOK_PADDING_Y;
        if (Math.abs(dirY) < 1e-9) {
            if (eyeY < minY || eyeY > maxY) {
                return false;
            }
        } else {
            double inv = 1.0 / dirY;
            double t1 = (minY - eyeY) * inv;
            double t2 = (maxY - eyeY) * inv;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tMin) tMin = t1;
            if (t2 < tMax) tMax = t2;
            if (tMin > tMax) return false;
        }

        // Z slab
        if (Math.abs(dirZ) < 1e-9) {
            return eyeZ >= npc.z() - halfW && eyeZ <= npc.z() + halfW;
        }
        double inv = 1.0 / dirZ;
        double t1 = (npc.z() - halfW - eyeZ) * inv;
        double t2 = (npc.z() + halfW - eyeZ) * inv;
        if (t1 > t2) {
            double tmp = t1;
            t1 = t2;
            t2 = tmp;
        }
        if (t1 > tMin) tMin = t1;
        if (t2 < tMax) tMax = t2;
        return tMin <= tMax;
    }

    @Override
    public void viewerGone(Player viewer) {
        UUID id = viewer.getUuid();
        lastLook.remove(id);
        lastLookScale.remove(id);
    }

    private static float round(float degrees) {
        return Math.round(degrees / TURN_STEP_DEGREES) * TURN_STEP_DEGREES;
    }

    private static long pack(float yaw, float pitch) {
        return ((long) Float.floatToIntBits(yaw) << PACK_SHIFT) | (Float.floatToIntBits(pitch) & PACK_MASK);
    }
}
