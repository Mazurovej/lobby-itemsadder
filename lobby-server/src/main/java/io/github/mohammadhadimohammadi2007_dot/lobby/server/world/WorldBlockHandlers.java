package io.github.mohammadhadimohammadi2007_dot.lobby.server.world;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockEntityType;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.instance.block.BlockManager;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Registers client-visible {@link BlockHandler}s for vanilla block entities (such as {@code minecraft:skull},
 * signs, banners, and decorated pots) and upgrades legacy pre-1.20.5 {@code SkullOwner} NBT to the modern
 * {@code profile} format.
 *
 * <p>Why this is needed: both Minestom's {@code AnvilLoader} and Polar's {@code PolarLoader} attach
 * {@code BlockManager.getHandlerOrDummy(id)} to every block entity. Minestom's {@code BlockUtils.extractClientNbt}
 * filters client-bound NBT through {@link BlockHandler#getBlockEntityTags()}, which returns an empty list on
 * {@code BlockHandler.Dummy}, silently stripping player head skins, sign text, and banner patterns unless a
 * real handler is registered before chunks are loaded.
 */
public final class WorldBlockHandlers {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldBlockHandlers.class);

    private static final BlockHandler SKULL = new StaticTagHandler("minecraft:skull", List.of(
            Tag.NBT("profile"),
            Tag.String("note_block_sound"),
            Tag.NBT("custom_name")));

    private static final BlockHandler SIGN = new StaticTagHandler("minecraft:sign", List.of(
            Tag.Boolean("is_waxed"),
            Tag.NBT("front_text"),
            Tag.NBT("back_text")));

    private static final BlockHandler HANGING_SIGN = new StaticTagHandler("minecraft:hanging_sign", List.of(
            Tag.Boolean("is_waxed"),
            Tag.NBT("front_text"),
            Tag.NBT("back_text")));

    private static final BlockHandler BANNER = new StaticTagHandler("minecraft:banner", List.of(
            Tag.NBT("patterns"),
            Tag.NBT("custom_name")));

    private static final BlockHandler DECORATED_POT = new StaticTagHandler("minecraft:decorated_pot", List.of(
            Tag.NBT("sherds"),
            Tag.NBT("item")));

    private static final BlockHandler CAMPFIRE = new StaticTagHandler("minecraft:campfire", List.of(
            Tag.NBT("Items")));

    private static final BlockHandler LECTERN = new StaticTagHandler("minecraft:lectern", List.of(
            Tag.NBT("Book"),
            Tag.Integer("Page")));

    private static final BlockHandler BEACON = new StaticTagHandler("minecraft:beacon", List.of(
            Tag.String("primary_effect"),
            Tag.String("secondary_effect"),
            Tag.Integer("Levels")));

    private static final BlockHandler JUKEBOX = new StaticTagHandler("minecraft:jukebox", List.of(
            Tag.NBT("RecordItem")));

    private static final BlockHandler CHISELED_BOOKSHELF = new StaticTagHandler("minecraft:chiseled_bookshelf", List.of(
            Tag.NBT("Items"),
            Tag.Integer("last_interacted_slot")));

    private static final BlockHandler BRUSHABLE_BLOCK = new StaticTagHandler("minecraft:brushable_block", List.of(
            Tag.NBT("item")));

    private static final List<BlockHandler> ALL = List.of(
            SKULL, SIGN, HANGING_SIGN, BANNER, DECORATED_POT, CAMPFIRE,
            LECTERN, BEACON, JUKEBOX, CHISELED_BOOKSHELF, BRUSHABLE_BLOCK);

    private WorldBlockHandlers() {
    }

    /** Registers all vanilla decorative block-entity handlers with Minestom's {@link BlockManager}. */
    public static void registerAll() {
        BlockManager manager = MinecraftServer.getBlockManager();
        for (BlockHandler handler : ALL) {
            if (manager.getHandler(handler.getKey().asString()) == null) {
                manager.registerHandler(handler.getKey(), () -> handler);
            }
        }
    }

    /**
     * Scans preloaded chunks and upgrades legacy pre-1.20.5 {@code SkullOwner} NBT on player heads to the
     * modern {@code profile} compound expected by 1.20.5+ clients, ensuring the {@code minecraft:skull}
     * handler is attached.
     */
    public static int normalizeBlockEntities(Instance instance) {
        int upgradedSkulls = 0;
        for (Chunk chunk : instance.getChunks()) {
            List<SkullUpdate> updates = null;
            chunk.lockReadLock();
            try {
                int minY = chunk.getMinSection() * Chunk.CHUNK_SECTION_SIZE;
                int maxY = chunk.getMaxSection() * Chunk.CHUNK_SECTION_SIZE;
                for (int sectionY = chunk.getMinSection(); sectionY < chunk.getMaxSection(); sectionY++) {
                    Section section = chunk.getSection(sectionY);
                    if (section.blockPalette().count() == 0) {
                        continue;
                    }
                    int baseY = sectionY * Chunk.CHUNK_SECTION_SIZE;
                    for (int ly = 0; ly < Chunk.CHUNK_SECTION_SIZE; ly++) {
                        int y = baseY + ly;
                        if (y < minY || y >= maxY) {
                            continue;
                        }
                        for (int lx = 0; lx < Chunk.CHUNK_SIZE_X; lx++) {
                            for (int lz = 0; lz < Chunk.CHUNK_SIZE_Z; lz++) {
                                Block block = chunk.getBlock(lx, y, lz, Block.Getter.Condition.CACHED);
                                if (block == null || block.blockEntityType() != BlockEntityType.SKULL) {
                                    continue;
                                }
                                Block updated = normalizeSkull(block);
                                if (updated != block) {
                                    if (updates == null) {
                                        updates = new java.util.ArrayList<>();
                                    }
                                    updates.add(new SkullUpdate(lx, y, lz, updated));
                                }
                            }
                        }
                    }
                }
            } finally {
                chunk.unlockReadLock();
            }
            if (updates != null) {
                chunk.lockWriteLock();
                try {
                    for (SkullUpdate u : updates) {
                        chunk.setBlock(u.lx(), u.y(), u.lz(), u.block());
                        upgradedSkulls++;
                    }
                } finally {
                    chunk.unlockWriteLock();
                }
            }
        }
        if (upgradedSkulls > 0) {
            LOGGER.info("Normalized {} skull block entit{} for modern client rendering.",
                    upgradedSkulls, upgradedSkulls == 1 ? "y" : "ies");
        }
        return upgradedSkulls;
    }

    private record SkullUpdate(int lx, int y, int lz, Block block) {
    }

    /** Upgrades a single skull block's NBT and handler if needed. Package-private for tests. */
    static Block normalizeSkull(Block block) {
        CompoundBinaryTag nbt = block.nbt();
        boolean needsHandler = block.handler() == null || block.handler() instanceof BlockHandler.Dummy;
        CompoundBinaryTag upgradedNbt = upgradeSkullNbt(nbt);
        if (!needsHandler && upgradedNbt == nbt) {
            return block;
        }
        Block result = needsHandler ? block.withHandler(SKULL) : block;
        if (upgradedNbt != nbt && upgradedNbt != null) {
            result = result.withNbt(upgradedNbt);
        }
        return result;
    }

    /**
     * Converts pre-1.20.5 {@code SkullOwner} NBT into 1.20.5+ {@code profile} NBT. Returns the original
     * tag unchanged if {@code profile} is already present or no {@code SkullOwner} exists.
     */
    static @Nullable CompoundBinaryTag upgradeSkullNbt(@Nullable CompoundBinaryTag nbt) {
        if (nbt == null || nbt.keySet().contains("profile")) {
            return nbt;
        }
        BinaryTag legacyOwner = nbt.get("SkullOwner");
        if (legacyOwner == null) {
            return nbt;
        }
        BinaryTag profile = convertLegacySkullOwner(legacyOwner);
        if (profile == null) {
            return nbt;
        }
        CompoundBinaryTag.Builder builder = CompoundBinaryTag.builder();
        for (String key : nbt.keySet()) {
            if (!key.equals("SkullOwner")) {
                BinaryTag val = nbt.get(key);
                if (val != null) {
                    builder.put(key, val);
                }
            }
        }
        builder.put("profile", profile);
        return builder.build();
    }

    private static @Nullable BinaryTag convertLegacySkullOwner(BinaryTag ownerTag) {
        if (ownerTag instanceof StringBinaryTag nameTag) {
            String name = nameTag.value().strip();
            return name.isEmpty() ? null : StringBinaryTag.stringBinaryTag(name);
        }
        if (!(ownerTag instanceof CompoundBinaryTag owner)) {
            return null;
        }
        CompoundBinaryTag.Builder profile = CompoundBinaryTag.builder();
        boolean hasAny = false;

        String name = owner.getString("Name", "");
        if (!name.isEmpty()) {
            profile.putString("name", name);
            hasAny = true;
        }

        BinaryTag idTag = owner.get("Id");
        int[] uuidInts = parseUuidInts(idTag);
        if (uuidInts != null) {
            profile.put("id", IntArrayBinaryTag.intArrayBinaryTag(uuidInts));
            hasAny = true;
        }

        CompoundBinaryTag propsCompound = owner.getCompound("Properties");
        if (propsCompound != null && !propsCompound.keySet().isEmpty()) {
            ListBinaryTag.Builder<CompoundBinaryTag> propList = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
            boolean hasProps = false;
            for (String propName : propsCompound.keySet()) {
                ListBinaryTag entries = propsCompound.getList(propName, BinaryTagTypes.COMPOUND);
                for (BinaryTag entryTag : entries) {
                    if (!(entryTag instanceof CompoundBinaryTag entry)) {
                        continue;
                    }
                    String value = entry.getString("Value", entry.getString("value", ""));
                    if (value.isEmpty()) {
                        continue;
                    }
                    CompoundBinaryTag.Builder prop = CompoundBinaryTag.builder()
                            .putString("name", propName)
                            .putString("value", value);
                    String sig = entry.getString("Signature", entry.getString("signature", ""));
                    if (!sig.isEmpty()) {
                        prop.putString("signature", sig);
                    }
                    propList.add(prop.build());
                    hasProps = true;
                }
            }
            if (hasProps) {
                profile.put("properties", propList.build());
                hasAny = true;
            }
        }

        return hasAny ? profile.build() : null;
    }

    private static int @Nullable [] parseUuidInts(@Nullable BinaryTag idTag) {
        if (idTag instanceof IntArrayBinaryTag intArray && intArray.value().length == 4) {
            return intArray.value();
        }
        if (idTag instanceof StringBinaryTag str) {
            try {
                UUID uuid = UUID.fromString(str.value().strip());
                long msb = uuid.getMostSignificantBits();
                long lsb = uuid.getLeastSignificantBits();
                return new int[]{
                        (int) (msb >>> 32), (int) msb,
                        (int) (lsb >>> 32), (int) lsb
                };
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static final class StaticTagHandler implements BlockHandler {
        private final Key key;
        private final Collection<Tag<?>> tags;

        StaticTagHandler(String key, List<Tag<?>> tags) {
            this.key = Key.key(key);
            this.tags = List.copyOf(tags);
        }

        @Override
        public @NotNull Collection<Tag<?>> getBlockEntityTags() {
            return tags;
        }

        @Override
        public @NotNull Key getKey() {
            return key;
        }
    }
}
