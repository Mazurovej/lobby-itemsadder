package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.itemsadder;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.item.ItemSpec;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Immutable snapshot of all loaded ItemsAdder items, font images (glyphs), and pixel-offset space glyphs.
 * Held in a {@code volatile} reference by {@link ItemsAdderService} so reloads are atomic and lock-free.
 */
public final class ItemsAdderRegistry {

    public static final ItemsAdderRegistry EMPTY = new ItemsAdderRegistry(Map.of(), Map.of(), Map.of());

    /** Safety cap on pixel offsets so a malicious or mistyped {@code :offset_999999:} cannot allocate huge strings. */
    private static final int MAX_OFFSET_PIXELS = 4096;

    private final Map<String, ItemsAdderItem> itemsById;
    private final Map<String, ItemsAdderItem> itemsByShortName;
    private final Map<String, ItemsAdderGlyph> glyphsById;
    private final Map<String, ItemsAdderGlyph> glyphsByShortName;
    private final Map<Integer, ItemsAdderGlyph> spaceOffsets;
    private final List<Integer> positiveAdvances;
    private final List<Integer> negativeAdvances;

    public ItemsAdderRegistry(
            Map<String, ItemsAdderItem> itemsById,
            Map<String, ItemsAdderGlyph> glyphsById,
            Map<Integer, ItemsAdderGlyph> spaceOffsets
    ) {
        this.itemsById = Map.copyOf(itemsById);
        this.itemsByShortName = indexShortNames(itemsById.values(), ItemsAdderItem::shortName);
        this.glyphsById = Map.copyOf(glyphsById);
        this.glyphsByShortName = indexShortNames(glyphsById.values(), ItemsAdderGlyph::shortName);
        this.spaceOffsets = Map.copyOf(spaceOffsets);
        this.positiveAdvances = spaceOffsets.keySet().stream()
                .filter(v -> v > 0)
                .sorted(Comparator.reverseOrder())
                .toList();
        this.negativeAdvances = spaceOffsets.keySet().stream()
                .filter(v -> v < 0)
                .sorted() // most negative first, e.g. -128, -64, ..., -1
                .toList();
    }

    /**
     * Looks up a custom item by {@code namespace:id}, {@code itemsadder:namespace:id}, {@code ia:namespace:id},
     * or unambiguous short name.
     */
    public @Nullable ItemsAdderItem item(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        String key = rawId.strip().toLowerCase(Locale.ROOT);
        if (key.startsWith(ItemSpec.ITEMSADDER_PREFIX)) {
            key = key.substring(ItemSpec.ITEMSADDER_PREFIX.length());
        } else if (key.startsWith(ItemSpec.IA_PREFIX)) {
            key = key.substring(ItemSpec.IA_PREFIX.length());
        }
        ItemsAdderItem exact = itemsById.get(key);
        return exact != null ? exact : itemsByShortName.get(key);
    }

    /** Looks up a glyph by {@code namespace:name} or unambiguous short {@code name}. */
    public @Nullable ItemsAdderGlyph glyph(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        String key = rawId.strip().toLowerCase(Locale.ROOT);
        ItemsAdderGlyph exact = glyphsById.get(key);
        if (exact != null) {
            return exact;
        }
        return glyphsByShortName.get(key);
    }

    /**
     * Composes a pixel offset (positive or negative) using the loaded space-font glyphs.
     *
     * @return the sequence of glyphs whose advances sum to {@code pixels} (or as close as possible),
     * or empty if {@code pixels == 0}, out of range, or no space glyphs are loaded
     */
    public List<ItemsAdderGlyph> offset(int pixels) {
        if (pixels == 0 || Math.abs(pixels) > MAX_OFFSET_PIXELS) {
            return List.of();
        }
        ItemsAdderGlyph exact = spaceOffsets.get(pixels);
        if (exact != null) {
            return List.of(exact);
        }
        List<Integer> candidates = pixels > 0 ? positiveAdvances : negativeAdvances;
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<ItemsAdderGlyph> result = new ArrayList<>();
        int remaining = pixels;
        for (int step : candidates) {
            while ((pixels > 0 && remaining >= step) || (pixels < 0 && remaining <= step)) {
                result.add(spaceOffsets.get(step));
                remaining -= step;
                if (result.size() >= 128) {
                    return result;
                }
            }
            if (remaining == 0) {
                break;
            }
        }
        return result;
    }

    public int itemCount() {
        return itemsById.size();
    }

    public int glyphCount() {
        return glyphsById.size();
    }

    public int offsetCount() {
        return spaceOffsets.size();
    }

    public boolean isEmpty() {
        return itemsById.isEmpty() && glyphsById.isEmpty() && spaceOffsets.isEmpty();
    }

    public Map<String, ItemsAdderItem> items() {
        return itemsById;
    }

    public Map<String, ItemsAdderGlyph> glyphs() {
        return glyphsById;
    }

    private static <T> Map<String, T> indexShortNames(Iterable<T> values, java.util.function.Function<T, String> shortName) {
        Map<String, T> map = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (T value : values) {
            String name = shortName.apply(value);
            if (ambiguous.contains(name)) {
                continue;
            }
            if (map.containsKey(name)) {
                map.remove(name);
                ambiguous.add(name);
            } else {
                map.put(name, value);
            }
        }
        return Map.copyOf(map);
    }
}
