package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minestom.server.component.DataComponents;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.TooltipDisplay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Small-caps and MiniMessage styling for the badcoreSettings chest GUIs. */
public final class BadcoreSettingsGuiStyle {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String SMALL_CAPS = "ᴀʙᴄᴅᴇғɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";
    private static final TooltipDisplay HIDE_ITEM_FLAGS = new TooltipDisplay(
            false, Set.of(DataComponents.ATTRIBUTE_MODIFIERS, DataComponents.ENCHANTMENTS));
    private static final ItemStack FILLER_PANE = ItemStack.builder(Material.GRAY_STAINED_GLASS_PANE)
            .customName(line(" "))
            .set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(true, Set.of()))
            .build();

    private BadcoreSettingsGuiStyle() {
    }

    public static String toSmallCaps(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch == '<') {
                int close = input.indexOf('>', i + 1);
                if (close != -1) {
                    out.append(input, i, close + 1);
                    i = close;
                    continue;
                }
            }
            char lower = Character.toLowerCase(ch);
            if (lower >= 'a' && lower <= 'z') {
                out.append(SMALL_CAPS.charAt(lower - 'a'));
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    public static Component title(String plain) {
        return MM.deserialize("<dark_gray>" + toSmallCaps(plain) + "</dark_gray>");
    }

    public static String name(String text) {
        return "<white>" + text + "</white>";
    }

    public static String dangerName(String text) {
        return "<#CF8484>" + text + "</#CF8484>";
    }

    public static String subtitle(String category) {
        return "<dark_gray>" + toSmallCaps(category) + "</dark_gray>";
    }

    public static String bullet(String text) {
        return "<white>→</white> <#DDDDDD>" + text + "</#DDDDDD>";
    }

    public static String info(String label, Object value) {
        return "<white>→</white> <#DDDDDD>" + label + ": <white>" + value + "</white></#DDDDDD>";
    }

    public static String statusLine(String label, boolean enabled) {
        String badge = enabled ? "<#8ECF84>✔ Enabled</#8ECF84>" : "<#CF8484>✖ Disabled</#CF8484>";
        return "<white>→</white> <#DDDDDD>" + label + ": " + badge + "</#DDDDDD>";
    }

    public static String action(String hint) {
        return "<white>⋆</white> <#DDDDDD>" + hint + "</#DDDDDD>";
    }

    public static Component line(String miniMessage) {
        if (miniMessage == null || miniMessage.isBlank()) {
            return Component.empty().decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        }
        return MM.deserialize(miniMessage).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> lore(String... lines) {
        return lore(Arrays.asList(lines));
    }

    public static List<Component> lore(List<String> lines) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String s : lines) {
            out.add(line(s));
        }
        return out;
    }

    public static ItemStack item(Material material, String displayName, String... loreLines) {
        return item(material, false, displayName, loreLines);
    }

    public static ItemStack item(Material material, boolean glowing, String displayName, String... loreLines) {
        ItemStack.Builder builder = ItemStack.builder(material)
                .customName(line(displayName))
                .set(DataComponents.TOOLTIP_DISPLAY, HIDE_ITEM_FLAGS);
        if (glowing) {
            builder.glowing();
        }
        if (loreLines != null && loreLines.length > 0) {
            builder.lore(lore(loreLines));
        }
        return builder.build();
    }

    public static Material resolveMaterial(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String key = raw.trim().toLowerCase(java.util.Locale.ROOT);
        Material mat = Material.fromKey(key.contains(":") ? key : "minecraft:" + key);
        if (mat == null || mat == Material.AIR) {
            return fallback;
        }
        return mat;
    }

    public static ItemStack fillerPane() {
        return FILLER_PANE;
    }

    public static void fillEmpty(Inventory inventory) {
        int size = inventory.getSize();
        for (int i = 0; i < size; i++) {
            if (inventory.getItemStack(i).isAir()) {
                inventory.setItemStack(i, FILLER_PANE);
            }
        }
    }

    public static ItemStack closeButton() {
        return item(
                Material.BARRIER,
                dangerName("Close"),
                subtitle("menu"),
                "",
                action("Click to close")
        );
    }

    public static ItemStack backButton() {
        return item(
                Material.ARROW,
                name("Back"),
                subtitle("navigation"),
                "",
                action("Click to return")
        );
    }
}
