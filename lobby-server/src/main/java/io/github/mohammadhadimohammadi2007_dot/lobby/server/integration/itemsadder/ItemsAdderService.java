package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.itemsadder;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.IntegrationsConfig;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.IntegrationStatus;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.item.ItemResolver;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.item.ItemSpec;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder.LobbyText;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.player.PermissionService;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.trait.PlayerEvent;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Coordinates the ItemsAdder integration: loading custom items and font images from an ItemsAdder folder
 * and/or generated resource pack {@code .zip}, resolving {@link ItemSpec}s, replacing {@code :glyph:}
 * shortcodes in configs and chat, and sending the external resource pack URL to joining players.
 */
public final class ItemsAdderService implements ItemResolver {

    public static final String INTEGRATION_NAME = "ItemsAdder";
    private static final Logger LOGGER = LoggerFactory.getLogger(ItemsAdderService.class);

    private final Path serverDir;
    private final ItemsAdderResourcePack resourcePack;
    private final PermissionService permissions;
    private volatile IntegrationsConfig.ItemsAdder config = IntegrationsConfig.ItemsAdder.DISABLED;
    private volatile ItemsAdderRegistry registry = ItemsAdderRegistry.EMPTY;

    public ItemsAdderService(Path serverDir, LobbyText text, PermissionService permissions) {
        this.serverDir = serverDir;
        this.resourcePack = new ItemsAdderResourcePack(text);
        this.permissions = permissions;
    }

    /** Loads (or reloads) ItemsAdder definitions and resource pack configuration. */
    public IntegrationStatus reload(IntegrationsConfig.ItemsAdder settings) {
        this.config = settings;
        if (!settings.enabled()) {
            this.registry = ItemsAdderRegistry.EMPTY;
            this.resourcePack.configure(false, settings.resourcePack(), "");
            return IntegrationStatus.disabled(INTEGRATION_NAME);
        }

        Path dataDir = resolvePath(settings.dataPath());
        Path packZip = resolvePath(settings.packZip());
        ItemsAdderRegistry loaded = ItemsAdderLoader.load(dataDir, packZip);
        this.registry = loaded;

        String hash = settings.resourcePack().hash();
        if (hash.isEmpty() && settings.resourcePack().hasUrl()) {
            Path zipForHash = packZip;
            if ((zipForHash == null || !Files.isRegularFile(zipForHash)) && dataDir != null) {
                Path autoZip = dataDir.resolve("output").resolve("generated.zip");
                if (Files.isRegularFile(autoZip)) {
                    zipForHash = autoZip;
                }
            }
            if (zipForHash != null && Files.isRegularFile(zipForHash)) {
                hash = ItemsAdderLoader.sha1Hex(zipForHash);
            }
        }
        this.resourcePack.configure(true, settings.resourcePack(), hash);

        String details = loaded.itemCount() + " items, " + loaded.glyphCount() + " glyphs"
                + (resourcePack.active() ? ", resource pack configured" : "");
        if (loaded.isEmpty() && !resourcePack.active()) {
            LOGGER.warn("ItemsAdder is enabled, but no items/glyphs were found (data-path='{}', pack-zip='{}') "
                    + "and no resource-pack.url is set.", settings.dataPath(), settings.packZip());
        } else {
            LOGGER.info("ItemsAdder loaded: {}", details);
        }
        return IntegrationStatus.active(INTEGRATION_NAME, details);
    }

    /** Registers join and resource-pack status listeners. */
    public void register(EventNode<PlayerEvent> events) {
        resourcePack.register(events);
    }

    /** True if the integration is currently enabled. */
    public boolean enabled() {
        return config.enabled();
    }

    /** Current loaded registry snapshot. */
    public ItemsAdderRegistry registry() {
        return registry;
    }

    /** Resource pack sender. */
    public ItemsAdderResourcePack resourcePack() {
        return resourcePack;
    }

    /** Placeholder namespace for {@code %img_<id>%} and {@code %img_offset_<n>%}. */
    public ItemsAdderPlaceholders placeholders() {
        return new ItemsAdderPlaceholders(this::registry);
    }

    /** Replaces {@code :glyph:}, {@code :namespace:glyph:} and {@code :offset_N:} in trusted MiniMessage text. */
    public String replaceGlyphsInTemplate(String template) {
        if (!config.enabled()) {
            return template;
        }
        return ItemsAdderGlyphReplacer.replaceInTemplate(template, registry);
    }

    /**
     * Replaces {@code :glyph:} and {@code :namespace:glyph:} in a player's chat message if enabled and
     * permitted. Never parses MiniMessage.
     */
    public Component replaceGlyphsInChat(Component message, @Nullable Player sender, boolean canUseEmojis) {
        IntegrationsConfig.ItemsAdder current = config;
        if (!current.enabled() || !current.chat().replaceGlyphs() || registry.isEmpty()) {
            return message;
        }
        String perm = current.chat().glyphPermission();
        boolean allowed = perm.isEmpty() ? canUseEmojis : (sender != null && permissions.hasPermission(sender, perm));
        if (!allowed) {
            return message;
        }
        return ItemsAdderGlyphReplacer.replaceInComponent(message, registry);
    }

    @Override
    public ItemStack build(ItemSpec spec, int amount) {
        int clamped = Math.max(1, amount);
        ItemsAdderItem iaItem = itemsAdderItem(spec);
        ItemStack base = iaItem != null ? iaItem.toItemStack(clamped) : ItemStack.of(spec.material(), clamped);
        return ItemResolver.applyOverrides(base, spec);
    }

    @Override
    public @Nullable ItemsAdderItem itemsAdderItem(ItemSpec spec) {
        if (!spec.isItemsAdder() || !config.enabled()) {
            return null;
        }
        return registry.item(spec.itemsAdderId());
    }

    private @Nullable Path resolvePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Path p = Path.of(raw.strip());
        return p.isAbsolute() ? p : serverDir.resolve(p);
    }
}
