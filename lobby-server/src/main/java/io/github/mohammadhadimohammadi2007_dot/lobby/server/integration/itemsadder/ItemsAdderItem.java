package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.itemsadder;

import net.kyori.adventure.util.RGBLike;
import net.minestom.server.component.DataComponents;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.CustomModelData;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One custom item definition loaded from ItemsAdder ({@code contents/}, {@code storage/items_ids_cache.yml},
 * or a generated resource pack {@code .zip}).
 *
 * @param id              full namespaced ID in lowercase, e.g. {@code myitems:ruby_sword}
 * @param material        base Minecraft item material
 * @param customModelData legacy / 1.14-1.21.3+ custom model data integer, or {@code null}
 * @param itemModel       Minecraft 1.21.4+ {@code item_model} key (e.g. {@code myitems:ruby_sword}), or {@code null}
 * @param displayName     default display name from ItemsAdder YAML (MiniMessage / legacy), or {@code null}
 * @param lore            default lore lines from ItemsAdder YAML
 * @param color           optional leather dye color, or {@code null}
 */
public record ItemsAdderItem(
        String id,
        Material material,
        @Nullable Integer customModelData,
        @Nullable String itemModel,
        @Nullable String displayName,
        List<String> lore,
        @Nullable RGBLike color
) {

    public ItemsAdderItem {
        id = Objects.requireNonNull(id, "id").strip().toLowerCase(Locale.ROOT);
        Objects.requireNonNull(material, "material");
        lore = lore == null ? List.of() : List.copyOf(lore);
        if (itemModel != null) {
            itemModel = itemModel.strip().toLowerCase(Locale.ROOT);
            if (itemModel.isEmpty()) {
                itemModel = null;
            }
        }
    }

    /** Short name without namespace (e.g. {@code ruby_sword} for {@code myitems:ruby_sword}). */
    public String shortName() {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    /** Builds a Minestom {@link ItemStack} carrying this item's material, model data and dye color. */
    public ItemStack toItemStack(int amount) {
        ItemStack item = ItemStack.of(material, Math.max(1, amount));
        if (customModelData != null) {
            item = item.with(DataComponents.CUSTOM_MODEL_DATA,
                    new CustomModelData(List.of(customModelData.floatValue()), List.of(), List.of(), List.of()));
        }
        if (itemModel != null) {
            item = item.with(DataComponents.ITEM_MODEL, itemModel);
        }
        if (color != null && material.key().value().startsWith("leather_")) {
            item = item.with(DataComponents.DYED_COLOR, color);
        }
        return item;
    }
}
