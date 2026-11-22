package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.itemsadder;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder.Placeholder;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder.PlaceholderNamespace;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * PlaceholderAPI-compatible {@code %img_<id>%} and {@code %img_offset_<n>%} placeholders for ItemsAdder
 * font images and pixel offsets.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code %img_smile%} &rarr; glyph with short name {@code smile}</li>
 *   <li>{@code %img_myitems:smile%} &rarr; glyph with full ID {@code myitems:smile}</li>
 *   <li>{@code %img_offset_-8%} &rarr; -8px space offset</li>
 * </ul>
 */
public final class ItemsAdderPlaceholders implements PlaceholderNamespace {

    private final Supplier<ItemsAdderRegistry> registry;

    public ItemsAdderPlaceholders(Supplier<ItemsAdderRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public @Nullable Placeholder lookup(String name) {
        ItemsAdderRegistry current = registry.get();
        if (current.isEmpty() || name.isBlank()) {
            return null;
        }
        String resolved = ItemsAdderGlyphReplacer.resolveMiniMessage(name, current);
        if (resolved == null) {
            return null;
        }
        return new Placeholder.Constant(resolved, true);
    }
}
