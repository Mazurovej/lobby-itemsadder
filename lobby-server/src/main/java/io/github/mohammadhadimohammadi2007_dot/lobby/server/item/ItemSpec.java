package io.github.mohammadhadimohammadi2007_dot.lobby.server.item;

import net.minestom.server.item.Material;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * Describes an item configured in YAML or commands: either a vanilla Minecraft material (optionally
 * with explicit {@code custom-model-data} / {@code item-model}) or an ItemsAdder custom item
 * ({@code itemsadder:<namespace>:<id>} or {@code ia:<namespace>:<id>}).
 *
 * <p>ItemsAdder IDs are resolved lazily through {@link ItemResolver}, so reloading ItemsAdder
 * definitions updates menus, hotbars, cosmetics, holograms and NPCs without re-reading every file.
 */
public record ItemSpec(
        Material material,
        @Nullable String itemsAdderId,
        @Nullable Integer customModelData,
        @Nullable String itemModel
) {

    public static final String ITEMSADDER_PREFIX = "itemsadder:";
    public static final String IA_PREFIX = "ia:";

    public ItemSpec {
        Objects.requireNonNull(material, "material");
        if (itemsAdderId != null) {
            itemsAdderId = itemsAdderId.strip().toLowerCase(Locale.ROOT);
            if (itemsAdderId.isEmpty()) {
                itemsAdderId = null;
            }
        }
        if (itemModel != null) {
            itemModel = itemModel.strip().toLowerCase(Locale.ROOT);
            if (itemModel.isEmpty()) {
                itemModel = null;
            }
        }
    }

    /** A plain vanilla material. */
    public static ItemSpec of(Material material) {
        return new ItemSpec(material, null, null, null);
    }

    /** An ItemsAdder custom item, e.g. {@code myitems:ruby_sword}. */
    public static ItemSpec itemsAdder(String id) {
        return new ItemSpec(Material.PAPER, id, null, null);
    }

    /**
     * Parses a material string or an ItemsAdder reference ({@code itemsadder:ns:id} / {@code ia:ns:id}).
     *
     * @return the parsed spec, or {@code null} if the name is neither a valid material nor an ItemsAdder reference
     */
    public static @Nullable ItemSpec parse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith(ITEMSADDER_PREFIX)) {
            String id = trimmed.substring(ITEMSADDER_PREFIX.length()).strip();
            return id.isEmpty() ? null : itemsAdder(id);
        }
        if (trimmed.startsWith(IA_PREFIX)) {
            String id = trimmed.substring(IA_PREFIX.length()).strip();
            return id.isEmpty() ? null : itemsAdder(id);
        }
        Material material = Material.fromKey(trimmed.contains(":") ? trimmed : "minecraft:" + trimmed);
        return material == null ? null : of(material);
    }

    /** True if this spec refers to an ItemsAdder custom item. */
    public boolean isItemsAdder() {
        return itemsAdderId != null;
    }

    /** Returns a copy with explicit custom model data and/or item model overrides. */
    public ItemSpec withModelOverrides(@Nullable Integer customModelData, @Nullable String itemModel) {
        if (customModelData == null && (itemModel == null || itemModel.isBlank())) {
            return this;
        }
        return new ItemSpec(material, itemsAdderId,
                customModelData != null ? customModelData : this.customModelData,
                itemModel != null && !itemModel.isBlank() ? itemModel : this.itemModel);
    }

    /** String form stored in data files (e.g. {@code diamond_sword} or {@code itemsadder:myitems:ruby_sword}). */
    public String serialize() {
        return itemsAdderId != null ? ITEMSADDER_PREFIX + itemsAdderId : material.key().value();
    }
}
