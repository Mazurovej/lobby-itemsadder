package io.github.mohammadhadimohammadi2007_dot.lobby.server.config;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Everything in {@code integrations.yml}, already validated.
 *
 * @param database      shared MariaDB connection settings
 * @param luckPerms     LuckPerms settings
 * @param liteBans      LiteBans settings
 * @param skinsRestorer SkinsRestorer settings
 * @param bridge        proxy bridge settings
 * @param signedVelocity SignedVelocity sync settings
 * @param mineSkin      MineSkin settings
 * @param itemsAdder    ItemsAdder settings
 */
public record IntegrationsConfig(
        Database database,
        LuckPerms luckPerms,
        LiteBans liteBans,
        SkinsRestorer skinsRestorer,
        Bridge bridge,
        SignedVelocity signedVelocity,
        MineSkin mineSkin,
        ItemsAdder itemsAdder
) {

    private static final int MAX_PORT = 65535;
    private static final int MAX_POOL_SIZE = 50;
    private static final int MAX_CHECK_INTERVAL_SECONDS = 60;
    /** Table prefixes end up inside SQL, so only allow safe characters. */
    private static final Pattern SAFE_PREFIX = Pattern.compile("[A-Za-z0-9_]{0,32}");

    /** {@code database:} section. */
    public record Database(String host, int port, String database, String username, String password, int poolSize) {
        /** Hides the password, so this record can be logged safely. */
        @Override
        public String toString() {
            return "Database[" + username + "@" + host + ":" + port + "/" + database + "]";
        }
    }

    /** {@code luckperms:} section. */
    public record LuckPerms(boolean enabled, String serverName, String tablePrefix, String messagingService) {
    }

    /** {@code litebans:} section. */
    public record LiteBans(boolean enabled, String tablePrefix, String serverName, int checkIntervalSeconds) {
    }

    /** {@code skinsrestorer:} section. */
    public record SkinsRestorer(boolean enabled, String tablePrefix) {
    }

    /** {@code bridge:} section. */
    public record Bridge(boolean enabled) {
    }

    /** {@code signedvelocity:} section. */
    public record SignedVelocity(boolean enabled) {
    }

    /** {@code mineskin:} section; an empty key means only skins already on MineSkin can be used. */
    public record MineSkin(String apiKey) {

        public boolean hasKey() {
            return !apiKey.isBlank();
        }

        /** Hides the key, so this record can be logged safely. */
        @Override
        public String toString() {
            return "MineSkin[" + (hasKey() ? "key set" : "no key") + "]";
        }
    }

    /** {@code itemsadder:} section. Reloadable live with {@code /setup-lobby reload}. */
    public record ItemsAdder(
            boolean enabled,
            String dataPath,
            String packZip,
            ResourcePack resourcePack,
            Chat chat
    ) {
        public static final ItemsAdder DISABLED = new ItemsAdder(
                false, "itemsadder", "",
                new ResourcePack("", "", false, "", "", false),
                new Chat(true, ""));

        /** {@code itemsadder.resource-pack:} section. */
        public record ResourcePack(
                String url,
                String hash,
                boolean required,
                String prompt,
                String kickMessage,
                boolean replace
        ) {
            public boolean hasUrl() {
                return !url.isBlank();
            }
        }

        /** {@code itemsadder.chat:} section. */
        public record Chat(boolean replaceGlyphs, String glyphPermission) {
        }
    }

    private static final Pattern SHA1_HEX = Pattern.compile("[0-9a-fA-F]{40}");

    /** True if any enabled integration needs the shared database pool. */
    public boolean needsDatabase() {
        return luckPerms.enabled || liteBans.enabled || skinsRestorer.enabled;
    }

    /**
     * Reads and validates integrations.yml.
     *
     * @throws ConfigException if a table prefix contains characters that are not allowed
     */
    public static IntegrationsConfig read(ConfigReader reader) throws ConfigException {
        Database database = new Database(
                reader.string("database.host"),
                reader.integer("database.port", 1, MAX_PORT),
                reader.string("database.database"),
                reader.string("database.username"),
                reader.string("database.password"),
                reader.integer("database.pool-size", 1, MAX_POOL_SIZE));
        LuckPerms luckPerms = new LuckPerms(
                reader.bool("luckperms.enabled"),
                reader.string("luckperms.server-name"),
                prefix(reader, "luckperms.table-prefix"),
                reader.choice("luckperms.messaging-service", Set.of("sql", "pluginmsg")));
        LiteBans liteBans = new LiteBans(
                reader.bool("litebans.enabled"),
                prefix(reader, "litebans.table-prefix"),
                reader.string("litebans.server-name").trim(),
                reader.integer("litebans.check-interval", 1, MAX_CHECK_INTERVAL_SECONDS));
        SkinsRestorer skinsRestorer = new SkinsRestorer(
                reader.bool("skinsrestorer.enabled"),
                prefix(reader, "skinsrestorer.table-prefix"));
        Bridge bridge = new Bridge(reader.bool("bridge.enabled"));
        SignedVelocity signedVelocity = new SignedVelocity(reader.bool("signedvelocity.enabled"));
        MineSkin mineSkin = new MineSkin(reader.string("mineskin.api-key", "").strip());
        ItemsAdder itemsAdder = readItemsAdder(reader);
        return new IntegrationsConfig(database, luckPerms, liteBans, skinsRestorer, bridge, signedVelocity, mineSkin,
                itemsAdder);
    }

    private static ItemsAdder readItemsAdder(ConfigReader reader) {
        boolean enabled = reader.bool("itemsadder.enabled", false);
        String dataPath = reader.string("itemsadder.data-path", "itemsadder").strip();
        String packZip = reader.string("itemsadder.pack-zip", "").strip();

        String url = reader.string("itemsadder.resource-pack.url", "").strip();
        if (!url.isEmpty() && !isValidHttpUrl(url)) {
            reader.reportInvalid("itemsadder.resource-pack.url", url, "an http:// or https:// URL, or \"\"");
            url = "";
        }
        String hash = reader.string("itemsadder.resource-pack.hash", "").strip().toLowerCase(java.util.Locale.ROOT);
        if (!hash.isEmpty() && !SHA1_HEX.matcher(hash).matches()) {
            reader.reportInvalid("itemsadder.resource-pack.hash", hash, "a 40-character SHA-1 hex hash, or \"\"");
            hash = "";
        }
        ItemsAdder.ResourcePack resourcePack = new ItemsAdder.ResourcePack(
                url,
                hash,
                reader.bool("itemsadder.resource-pack.required", false),
                reader.string("itemsadder.resource-pack.prompt", "<gold>This server uses a custom resource pack."),
                reader.string("itemsadder.resource-pack.kick-message",
                        "<red>You must accept the server resource pack to play here."),
                reader.bool("itemsadder.resource-pack.replace", false));

        ItemsAdder.Chat chat = new ItemsAdder.Chat(
                reader.bool("itemsadder.chat.replace-glyphs", true),
                reader.string("itemsadder.chat.glyph-permission", "").strip());

        return new ItemsAdder(enabled, dataPath, packZip, resourcePack, chat);
    }

    private static boolean isValidHttpUrl(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String prefix(ConfigReader reader, String path) throws ConfigException {
        String value = reader.string(path).trim();
        if (!SAFE_PREFIX.matcher(value).matches()) {
            throw new ConfigException("integrations.yml: option '" + path + "' has an invalid value '" + value
                    + "'. Allowed: letters, numbers and _ only (max 32 characters).");
        }
        return value;
    }
}
