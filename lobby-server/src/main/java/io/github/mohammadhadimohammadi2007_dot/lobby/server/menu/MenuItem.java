package io.github.mohammadhadimohammadi2007_dot.lobby.server.menu;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.action.ActionList;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.item.ItemSpec;
import net.minestom.server.item.Material;

import java.util.List;

/**
 * One item of a menu, as written in menus.yml. Several items may share a slot: the first whose
 * {@code show-if} conditions all hold is the one shown, so a slot can look different per player (an
 * offline server, a locked game) with no code.
 *
 * @param slots    where it goes (0 is the top left corner)
 * @param item     the item (vanilla material, optional model overrides, or ItemsAdder custom item)
 * @param name     display name, MiniMessage with placeholders; empty keeps the item's own name
 * @param lore     lines under the name, each MiniMessage with placeholders
 * @param glow     true to make it shimmer like an enchanted item
 * @param showIf   conditions that must all hold
 * @param actions  what a click does
 */
public record MenuItem(List<Integer> slots, ItemSpec item, int amount, String name, List<String> lore,
                       boolean glow, List<MenuCondition> showIf, ActionList actions) {

    public MenuItem {
        slots = List.copyOf(slots);
        lore = List.copyOf(lore);
        showIf = List.copyOf(showIf);
    }

    /** Convenience constructor for a plain vanilla material. */
    public MenuItem(List<Integer> slots, Material material, int amount, String name, List<String> lore,
                    boolean glow, List<MenuCondition> showIf, ActionList actions) {
        this(slots, ItemSpec.of(material), amount, name, lore, glow, showIf, actions);
    }

    /** The base vanilla material of this item. */
    public Material material() {
        return item.material();
    }
}
