package io.github.mohammadhadimohammadi2007_dot.lobby.server.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Everything in {@code integrations.yml}, already validated.
 *
 * @param database        shared MariaDB connection settings
 * @param luckPerms       LuckPerms settings
 * @param liteBans        LiteBans settings
 * @param skinsRestorer   SkinsRestorer settings
 * @param bridge          proxy bridge settings
 * @param signedVelocity  SignedVelocity sync settings
 * @param mineSkin        MineSkin settings
 * @param itemsAdder      ItemsAdder settings
 * @param voiceChat       Simple Voice Chat settings
 * @param badcoreSettings badcoreSettings GUI, bridge and MariaDB settings
 */
public record IntegrationsConfig(
        Database database,
        LuckPerms luckPerms,
        LiteBans liteBans,
        SkinsRestorer skinsRestorer,
        Bridge bridge,
        SignedVelocity signedVelocity,
        MineSkin mineSkin,
        ItemsAdder itemsAdder,
        VoiceChat voiceChat,
        BadcoreSettings badcoreSettings
) {

    private static final int MAX_PORT = 65535;
    private static final int MAX_POOL_SIZE = 50;
    private static final int MAX_CHECK_INTERVAL_SECONDS = 60;
    /** Table prefixes end up inside SQL, so only allow safe characters. */
    private static final Pattern SAFE_PREFIX = Pattern.compile("[A-Za-z0-9_]{0,32}");
    private static final Pattern SAFE_TABLE = Pattern.compile("[A-Za-z0-9_]{1,64}");

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

    /** {@code voicechat:} section (Simple Voice Chat server). */
    public record VoiceChat(
            boolean enabled,
            String bindAddress,
            int port,
            String voiceHost,
            double maxDistance,
            double whisperDistance,
            String codec,
            int mtuSize,
            int keepAliveMillis,
            boolean allowRecording,
            boolean respectMutes
    ) {
        public static final VoiceChat DISABLED = new VoiceChat(
                false, "0.0.0.0", 24454, "", 48.0, 24.0, "voip", 1024, 1000, true, true);

        /** Simple Voice Chat codec ordinal: 0 = VOIP, 1 = AUDIO, 2 = RESTRICTED_LOWDELAY. */
        public byte codecOrdinal() {
            return switch (codec) {
                case "audio" -> 1;
                case "restricted_lowdelay" -> 2;
                default -> 0;
            };
        }
    }

    /** {@code badcore-settings:} section. Reloadable live with {@code /setup-lobby reload} or {@code /settings reload}. */
    public record BadcoreSettings(
            boolean enabled,
            long cooldownMs,
            Database database,
            Map<String, String> messages
    ) {
        public static final Map<String, String> DEFAULT_MESSAGES = Map.of(
                "player-only", "<white><bold>ꜱᴇʀᴠᴇʀ →</bold></white> <#CF8484>This command can only be used by players.</#CF8484>",
                "no-permission", "<white><bold>ꜱᴇʀᴠᴇʀ →</bold></white> <#CF8484>You do not have permission to use this command.</#CF8484>",
                "vip-required", "<white><bold>ꜱᴇʀᴠᴇʀ →</bold></white> <#CF8484>This setting requires a VIP rank.</#CF8484>",
                "cooldown", "<white><bold>ꜱᴇʀᴠᴇʀ →</bold></white> <#CF8484>Please wait <white><seconds>s</white> before changing this again.</#CF8484>",
                "reloaded", "<white><bold>ꜱᴇʀᴠᴇʀ →</bold></white> <#8ECF84>Settings configuration reloaded.</#8ECF84>",
                "dbtest-running", "<white><bold>ꜱᴇʀᴠᴇʀ →</bold></white> <#DDDDDD>Testing MariaDB connection...</#DDDDDD>",
                "dbtest-ok", "<white><bold>ꜱᴇʀᴠᴇʀ →</bold></white> <#8ECF84>MariaDB OK (<white><target></white>, <white><version></white>, <white><latency>ms</white>).</#8ECF84>",
                "dbtest-fail", "<white><bold>ꜱᴇʀᴠᴇʀ →</bold></white> <#CF8484>MariaDB failed (<white><target></white>, <white><latency>ms</white>): <white><error></white></#CF8484>"
        );

        public static final BadcoreSettings DISABLED = new BadcoreSettings(
                false, 500L,
                new Database(true, "localhost", 3306, "badcore_practice", "root", "", 6, 5000L, false, "badcore_qoa_settings"),
                DEFAULT_MESSAGES);

        public String message(String key) {
            return messages.getOrDefault(key, DEFAULT_MESSAGES.getOrDefault(key,
                    "<white><bold>ꜱᴇʀᴠᴇʀ →</bold></white> <#CF8484>Missing message: " + key + "</#CF8484>"));
        }

        /** {@code badcore-settings.database:} section. */
        public record Database(
                boolean enabled,
                String host,
                int port,
                String database,
                String username,
                String password,
                int poolSize,
                long connectionTimeoutMs,
                boolean useSsl,
                String qoaTable
        ) {
            @Override
            public String toString() {
                return "BadcoreDatabase[" + username + "@" + host + ":" + port + "/" + database + "]";
            }
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
        VoiceChat voiceChat = readVoiceChat(reader);
        BadcoreSettings badcoreSettings = readBadcoreSettings(reader);
        return new IntegrationsConfig(database, luckPerms, liteBans, skinsRestorer, bridge, signedVelocity, mineSkin,
                itemsAdder, voiceChat, badcoreSettings);
    }

    private static BadcoreSettings readBadcoreSettings(ConfigReader reader) {
        boolean enabled = reader.bool("badcore-settings.enabled", false);
        long cooldownMs = reader.integer("badcore-settings.cooldown-ms", 0, 60_000, 500);
        boolean dbEnabled = reader.bool("badcore-settings.database.enabled", true);
        String host = reader.string("badcore-settings.database.host", "localhost").trim();
        int port = reader.integer("badcore-settings.database.port", 1, MAX_PORT, 3306);
        String dbName = reader.string("badcore-settings.database.database", "badcore_practice").trim();
        String username = reader.string("badcore-settings.database.username", "root").trim();
        String password = reader.string("badcore-settings.database.password", "");
        int poolSize = reader.integer("badcore-settings.database.pool-size", 2, 32, 6);
        long timeoutMs = reader.integer("badcore-settings.database.connection-timeout-ms", 1000, 30_000, 5000);
        boolean useSsl = reader.bool("badcore-settings.database.use-ssl", false);
        String rawTable = reader.string("badcore-settings.database.qoa-table", "badcore_qoa_settings").trim();
        String qoaTable = SAFE_TABLE.matcher(rawTable).matches() ? rawTable : "badcore_qoa_settings";
        if (!SAFE_TABLE.matcher(rawTable).matches()) {
            reader.reportInvalid("badcore-settings.database.qoa-table", rawTable,
                    "letters, numbers and _ only (1-64 characters)");
        }
        BadcoreSettings.Database db = new BadcoreSettings.Database(
                dbEnabled, host, port, dbName, username, password, poolSize, timeoutMs, useSsl, qoaTable);

        Map<String, String> messages = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : BadcoreSettings.DEFAULT_MESSAGES.entrySet()) {
            String key = entry.getKey();
            messages.put(key, reader.string("badcore-settings.messages." + key, entry.getValue()));
        }
        return new BadcoreSettings(enabled, cooldownMs, db, Map.copyOf(messages));
    }

    private static VoiceChat readVoiceChat(ConfigReader reader) {
        boolean enabled = reader.bool("voicechat.enabled", false);
        String bindAddress = reader.string("voicechat.bind-address", "0.0.0.0").strip();
        if (bindAddress.isEmpty()) {
            bindAddress = "0.0.0.0";
        }
        int port = reader.integer("voicechat.port", 1, MAX_PORT, 24454);
        String voiceHost = reader.string("voicechat.voice-host", "").strip();
        double maxDistance = reader.decimal("voicechat.max-distance", 4.0, 256.0, 48.0);
        double whisperDistance = reader.decimal("voicechat.whisper-distance", 2.0, 128.0, 24.0);
        String codec = reader.string("voicechat.codec", "voip").strip().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("voip", "audio", "restricted_lowdelay").contains(codec)) {
            reader.reportInvalid("voicechat.codec", codec, "voip, audio or restricted_lowdelay");
            codec = "voip";
        }
        int mtuSize = reader.integer("voicechat.mtu-size", 256, 1024, 1024);
        int keepAlive = reader.integer("voicechat.keep-alive", 1000, 10_000, 1000);
        boolean allowRecording = reader.bool("voicechat.allow-recording", true);
        boolean respectMutes = reader.bool("voicechat.respect-mutes", true);
        return new VoiceChat(enabled, bindAddress, port, voiceHost, maxDistance, whisperDistance, codec,
                mtuSize, keepAlive, allowRecording, respectMutes);
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
