package io.github.mohammadhadimohammadi2007_dot.lobby.server.item;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.itemsadder.ItemsAdderItem;
import net.minestom.server.component.DataComponents;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.component.CustomModelData;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Resolves an {@link ItemSpec} into a Minestom {@link ItemStack}, applying ItemsAdder custom item
 * definitions when available as well as explicit {@code custom-model-data} / {@code item-model} overrides.
 */
public interface ItemResolver {

    /** Resolver that only understands vanilla materials and explicit model overrides. */
    ItemResolver VANILLA = new ItemResolver() {
        @Override
        public ItemStack build(ItemSpec spec, int amount) {
            return applyOverrides(ItemStack.of(spec.material(), amount), spec);
        }

        @Override
        public @Nullable ItemsAdderItem itemsAdderItem(ItemSpec spec) {
            return null;
        }
    };

    /** Builds the base item stack (material, amount, model components and dye color if defined). */
    ItemStack build(ItemSpec spec, int amount);

    /** Builds a single item stack. */
    default ItemStack build(ItemSpec spec) {
        return build(spec, 1);
    }

    /** The ItemsAdder item definition for {@code spec}, or {@code null} if not an ItemsAdder item or unknown. */
    @Nullable ItemsAdderItem itemsAdderItem(ItemSpec spec);

    /** Applies explicit {@code custom-model-data} and {@code item-model} overrides from {@code spec}. */
    static ItemStack applyOverrides(ItemStack base, ItemSpec spec) {
        ItemStack result = base;
        if (spec.customModelData() != null) {
            result = result.with(DataComponents.CUSTOM_MODEL_DATA,
                    new CustomModelData(List.of(spec.customModelData().floatValue()), List.of(), List.of(), List.of()));
        }
        if (spec.itemModel() != null) {
            result = result.with(DataComponents.ITEM_MODEL, spec.itemModel());
        }
        return result;
    }

    /** Global active resolver installed by {@code LobbyServer}; defaults to {@link #VANILLA}. */
    final class Active {
        private static volatile ItemResolver current = VANILLA;

        private Active() {
        }

        public static ItemResolver get() {
            return current;
        }

        public static void set(ItemResolver resolver) {
            current = Objects.requireNonNull(resolver, "resolver");
        }

        public static void reset() {
            current = VANILLA;
        }
    }
}
