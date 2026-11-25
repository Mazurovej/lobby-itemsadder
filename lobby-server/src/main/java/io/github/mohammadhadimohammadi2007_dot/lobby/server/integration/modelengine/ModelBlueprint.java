package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.modelengine;

import net.minestom.server.coordinate.Vec;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A parsed ModelEngine R4 {@code .bbmodel} blueprint ready for packet-only {@code ITEM_DISPLAY}
 * rendering, with hierarchical forward kinematics, looping keyframe animations, and head tracking.
 */
public final class ModelBlueprint {

    /** {@code ItemDisplay} transform context {@code HEAD} (byte 5) — ModelEngine R4 default. */
    public static final byte DISPLAY_HEAD = 5;
    /** {@code ItemDisplay} transform context {@code FIXED} (byte 7). */
    public static final byte DISPLAY_FIXED = 7;

    /**
     * One bone in topological parent-first order ({@code parentIndex < index}, or {@code -1} for root).
     *
     * @param uuid         Blockbench bone UUID (matches animation animator keys)
     * @param name         lowercased bone name (e.g. {@code body}, {@code h_head})
     * @param parentIndex  index of parent bone in {@link #bones()}, or {@code -1}
     * @param localOffset  rest offset from parent pivot in blocks ({@code (origin - parentOrigin) / 16})
     * @param restRotation rest Euler rotation in degrees (Blockbench X, Y, Z)
     * @param head         true if this bone is a head bone ({@code h_*} or {@code head})
     * @param displayItem  resolved bone {@link ItemStack} (with {@code CUSTOM_MODEL_DATA} or {@code ITEM_MODEL}),
     *                     or {@code null} if this bone is an invisible helper/pivot group
     * @param displayType  {@link #DISPLAY_HEAD} or {@link #DISPLAY_FIXED}
     */
    public record Bone(
            String uuid,
            String name,
            int parentIndex,
            Vec localOffset,
            Vec restRotation,
            boolean head,
            @Nullable ItemStack displayItem,
            byte displayType
    ) {
        public boolean visible() {
            return displayItem != null && !displayItem.isAir();
        }
    }

    /** Keyframe interpolation mode from Blockbench. */
    public enum Interpolation {
        LINEAR,
        STEP,
        SMOOTH
    }

    /** A single 3D keyframe at {@code timeSeconds}. */
    public record Keyframe(double timeSeconds, Vec value, Interpolation interpolation) {
    }

    /** Keyframe tracks for one bone inside an animation. */
    public record BoneTrack(List<Keyframe> position, List<Keyframe> rotation, List<Keyframe> scale) {
        public static final BoneTrack EMPTY = new BoneTrack(List.of(), List.of(), List.of());

        public BoneTrack {
            position = List.copyOf(position);
            rotation = List.copyOf(rotation);
            scale = List.copyOf(scale);
        }
    }

    /** One Blockbench animation (e.g. {@code idle}). */
    public record Animation(String name, double lengthSeconds, boolean loop, Map<Integer, BoneTrack> tracks) {
        public Animation {
            tracks = Map.copyOf(tracks);
        }
    }

    /** Evaluated world-space transform of one visible bone relative to the NPC's feet position. */
    public record BonePose(int boneIndex, Vec translation, float[] rotation, Vec scale) {
    }

    private final String id;
    private final double width;
    private final double height;
    private final double eyeHeight;
    private final List<Bone> bones;
    private final int[] visibleBoneIndices;
    private final boolean hasHeadBone;
    private final Map<String, Animation> animations;

    public ModelBlueprint(String id, double width, double height, double eyeHeight,
                          List<Bone> bones, Map<String, Animation> animations) {
        this.id = id.toLowerCase(Locale.ROOT);
        this.width = Math.max(0.3, width);
        this.height = Math.max(0.3, height);
        this.eyeHeight = Math.clamp(eyeHeight, 0.2, this.height);
        this.bones = List.copyOf(bones);
        this.animations = Map.copyOf(animations);

        List<Integer> vis = new ArrayList<>();
        boolean headFound = false;
        for (int i = 0; i < this.bones.size(); i++) {
            Bone b = this.bones.get(i);
            if (b.visible()) {
                vis.add(i);
            }
            if (b.head()) {
                headFound = true;
            }
        }
        this.visibleBoneIndices = vis.stream().mapToInt(Integer::intValue).toArray();
        this.hasHeadBone = headFound;
    }

    public String id() {
        return id;
    }

    public double width() {
        return width;
    }

    public double height() {
        return height;
    }

    public double eyeHeight() {
        return eyeHeight;
    }

    public List<Bone> bones() {
        return bones;
    }

    /** Indices into {@link #bones()} for every bone that spawns an {@code ITEM_DISPLAY}. */
    public int[] visibleBoneIndices() {
        return visibleBoneIndices;
    }

    public int visibleBoneCount() {
        return visibleBoneIndices.length;
    }

    public boolean hasHeadBone() {
        return hasHeadBone;
    }

    public Map<String, Animation> animations() {
        return animations;
    }

    public @Nullable Animation animation(@Nullable String name) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase("none")) {
            return null;
        }
        return animations.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Evaluates forward kinematics for every visible bone at the given animation time, entity yaw,
     * optional relative head look angles, and overall scale.
     *
     * @param animationName animation name (e.g. {@code "idle"}), or {@code null}/{@code "none"} for rest pose
     * @param timeSeconds   elapsed time in seconds
     * @param bodyYawDeg    Minecraft entity yaw in degrees (0 = South +Z)
     * @param headYawRelDeg head yaw relative to body yaw in degrees (clamped to [-75, 75])
     * @param headPitchDeg  head pitch in degrees (clamped to [-60, 60])
     * @param entityScale   overall NPC scale multiplier
     */
    public List<BonePose> samplePose(@Nullable String animationName, double timeSeconds,
                                     float bodyYawDeg, float headYawRelDeg, float headPitchDeg,
                                     double entityScale) {
        int count = bones.size();
        if (visibleBoneIndices.length == 0) {
            return List.of();
        }

        Animation anim = animation(animationName);
        double animTime = 0.0;
        if (anim != null && anim.lengthSeconds() > 0.0) {
            animTime = anim.loop()
                    ? ((timeSeconds % anim.lengthSeconds()) + anim.lengthSeconds()) % anim.lengthSeconds()
                    : Math.min(timeSeconds, anim.lengthSeconds());
        }

        // Root rotation around Y so Minecraft yaw=0 faces South (+Z) matching Blockbench's front (-Z -> +Z).
        float[] rootRot = quatAxisY(Math.toRadians(180.0 - bodyYawDeg));

        Vec[] worldPos = new Vec[count];
        float[][] worldRot = new float[count][];
        Vec[] worldScale = new Vec[count];

        float clampedHeadYaw = Math.clamp(headYawRelDeg, -75f, 75f);
        float clampedHeadPitch = Math.clamp(headPitchDeg, -60f, 60f);

        for (int i = 0; i < count; i++) {
            Bone bone = bones.get(i);
            BoneTrack track = anim != null ? anim.tracks().get(i) : null;

            Vec animPos = track != null && !track.position().isEmpty()
                    ? sampleTrack(track.position(), animTime, Vec.ZERO)
                    : Vec.ZERO;
            Vec animRot = track != null && !track.rotation().isEmpty()
                    ? sampleTrack(track.rotation(), animTime, Vec.ZERO)
                    : Vec.ZERO;
            Vec animScale = track != null && !track.scale().isEmpty()
                    ? sampleTrack(track.scale(), animTime, Vec.ONE)
                    : Vec.ONE;

            // Blockbench position keyframes are in pixels (16 px = 1 block); X is mirrored in Blockbench UI.
            Vec localPos = new Vec(
                    bone.localOffset().x() - animPos.x() / 16.0,
                    bone.localOffset().y() + animPos.y() / 16.0,
                    bone.localOffset().z() + animPos.z() / 16.0
            );

            double rx = bone.restRotation().x() + animRot.x();
            double ry = bone.restRotation().y() + animRot.y();
            double rz = bone.restRotation().z() + animRot.z();
            if (bone.head()) {
                rx += clampedHeadPitch;
                ry -= clampedHeadYaw;
            }
            float[] localRot = quatFromBlockbenchEuler(rx, ry, rz);

            int p = bone.parentIndex();
            if (p < 0) {
                Vec scaledLocal = localPos.mul(entityScale);
                worldPos[i] = rotateVec(rootRot, scaledLocal);
                worldRot[i] = quatMul(rootRot, localRot);
                worldScale[i] = animScale.mul(entityScale);
            } else {
                Vec parentPos = worldPos[p];
                float[] parentRot = worldRot[p];
                Vec parentScale = worldScale[p];

                Vec scaledLocal = new Vec(
                        localPos.x() * parentScale.x(),
                        localPos.y() * parentScale.y(),
                        localPos.z() * parentScale.z()
                );
                worldPos[i] = parentPos.add(rotateVec(parentRot, scaledLocal));
                worldRot[i] = quatMul(parentRot, localRot);
                worldScale[i] = new Vec(
                        parentScale.x() * animScale.x(),
                        parentScale.y() * animScale.y(),
                        parentScale.z() * animScale.z()
                );
            }
        }

        List<BonePose> result = new ArrayList<>(visibleBoneIndices.length);
        for (int idx : visibleBoneIndices) {
            result.add(new BonePose(idx, worldPos[idx], worldRot[idx], worldScale[idx]));
        }
        return result;
    }

    static Vec sampleTrack(List<Keyframe> keyframes, double time, Vec defaultValue) {
        int n = keyframes.size();
        if (n == 0) {
            return defaultValue;
        }
        if (n == 1 || time <= keyframes.getFirst().timeSeconds()) {
            return keyframes.getFirst().value();
        }
        if (time >= keyframes.getLast().timeSeconds()) {
            return keyframes.getLast().value();
        }
        // Binary search for the surrounding pair [lo, lo + 1].
        int lo = 0;
        int hi = n - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (keyframes.get(mid).timeSeconds() <= time) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        Keyframe a = keyframes.get(lo);
        Keyframe b = keyframes.get(lo + 1);
        if (a.interpolation() == Interpolation.STEP || b.timeSeconds() <= a.timeSeconds()) {
            return a.value();
        }
        double t = (time - a.timeSeconds()) / (b.timeSeconds() - a.timeSeconds());
        if (a.interpolation() == Interpolation.SMOOTH) {
            // Smoothstep easing approximates Blockbench catmullrom without overshoot.
            t = t * t * (3.0 - 2.0 * t);
        }
        return new Vec(
                a.value().x() + (b.value().x() - a.value().x()) * t,
                a.value().y() + (b.value().y() - a.value().y()) * t,
                a.value().z() + (b.value().z() - a.value().z()) * t
        );
    }

    /** Quaternion for a rotation around the Y axis, stored as {@code [x, y, z, w]}. */
    static float[] quatAxisY(double radians) {
        double half = radians * 0.5;
        return new float[]{0f, (float) Math.sin(half), 0f, (float) Math.cos(half)};
    }

    /**
     * Converts Blockbench Euler degrees (applied in Z-Y-X order, with Blockbench's X/Y sign convention)
     * into a unit quaternion {@code [x, y, z, w]}.
     */
    static float[] quatFromBlockbenchEuler(double degX, double degY, double degZ) {
        double hx = Math.toRadians(-degX) * 0.5;
        double hy = Math.toRadians(-degY) * 0.5;
        double hz = Math.toRadians(degZ) * 0.5;

        double cx = Math.cos(hx), sx = Math.sin(hx);
        double cy = Math.cos(hy), sy = Math.sin(hy);
        double cz = Math.cos(hz), sz = Math.sin(hz);

        // q = qZ * qY * qX
        double x = sx * cy * cz - cx * sy * sz;
        double y = cx * sy * cz + sx * cy * sz;
        double z = cx * cy * sz - sx * sy * cz;
        double w = cx * cy * cz + sx * sy * sz;
        return new float[]{(float) x, (float) y, (float) z, (float) w};
    }

    /** Hamilton product {@code a * b} for quaternions stored as {@code [x, y, z, w]}. */
    static float[] quatMul(float[] a, float[] b) {
        float ax = a[0], ay = a[1], az = a[2], aw = a[3];
        float bx = b[0], by = b[1], bz = b[2], bw = b[3];
        return new float[]{
                aw * bx + ax * bw + ay * bz - az * by,
                aw * by - ax * bz + ay * bw + az * bx,
                aw * bz + ax * by - ay * bx + az * bw,
                aw * bw - ax * bx - ay * by - az * bz
        };
    }

    /** Rotates vector {@code v} by unit quaternion {@code q = [x, y, z, w]}. */
    static Vec rotateVec(float[] q, Vec v) {
        double qx = q[0], qy = q[1], qz = q[2], qw = q[3];
        double vx = v.x(), vy = v.y(), vz = v.z();

        double tx = 2.0 * (qy * vz - qz * vy);
        double ty = 2.0 * (qz * vx - qx * vz);
        double tz = 2.0 * (qx * vy - qy * vx);

        return new Vec(
                vx + qw * tx + (qy * tz - qz * ty),
                vy + qw * ty + (qz * tx - qx * tz),
                vz + qw * tz + (qx * ty - qy * tx)
        );
    }
}
