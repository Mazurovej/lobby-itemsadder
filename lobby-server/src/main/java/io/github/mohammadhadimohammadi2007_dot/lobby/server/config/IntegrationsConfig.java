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
 * @param modelEngine     ModelEngine R4 blueprint & resource pack settings
 * @param mythicMobs      MythicMobs mob definition settings
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
        BadcoreSettings badcoreSettings,
        ModelEngine modelEngine,
        MythicMobs mythicMobs
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
            String scoreboardCommand,
            String vipLabel,
            String vipBadge,
            Database database,
            Map<String, String> messages,
            java.util.List<TabViewItem> tabViewItems,
            java.util.List<ChatColorPreset> chatColors
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

        public static final java.util.List<TabViewItem> DEFAULT_TAB_VIEW_ITEMS = java.util.List.of(
                new TabViewItem("pv", "Player Vault", "CHEST", "axvaults.vault.1", true),
                new TabViewItem("og", "OG Tag", "NAME_TAG", "badcore.tabview.og", false),
                new TabViewItem("winter", "Winter Tag", "SNOWBALL", "badcore.tabview.winter", false),
                new TabViewItem("booster", "Booster Tag", "AMETHYST_SHARD", "badcore.tabview.booster", false)
        );

        public static final java.util.List<ChatColorPreset> DEFAULT_CHAT_COLORS = java.util.List.of(
                new ChatColorPreset("fire", "Fire", "CREEPER_BANNER_PATTERN", "badcore.chatcolor.fire"),
                new ChatColorPreset("ice", "Ice", "GUSTER_BANNER_PATTERN", "badcore.chatcolor.ice"),
                new ChatColorPreset("toxic", "Toxic", "FLOW_BANNER_PATTERN", "badcore.chatcolor.toxic"),
                new ChatColorPreset("amethyst", "Amethyst", "FLOWER_BANNER_PATTERN", "badcore.chatcolor.amethyst"),
                new ChatColorPreset("gold", "Gold", "MOJANG_BANNER_PATTERN", "badcore.chatcolor.gold")
        );

        public static final BadcoreSettings DISABLED = new BadcoreSettings(
                false, 500L, "sb", "VIP", " &7%vip%",
                new Database(true, "localhost", 3306, "badcore_practice", "root", "", 6, 5000L, false, "badcore_qoa_settings"),
                DEFAULT_MESSAGES,
                DEFAULT_TAB_VIEW_ITEMS,
                DEFAULT_CHAT_COLORS);

        public String message(String key) {
            return messages.getOrDefault(key, DEFAULT_MESSAGES.getOrDefault(key,
                    "<white><bold>ꜱᴇʀᴠᴇʀ →</bold></white> <#CF8484>Missing message: " + key + "</#CF8484>"));
        }

        /** {@code badcore-settings.tabview.items.<id>:} entry. */
        public record TabViewItem(
                String id,
                String name,
                String material,
                String permission,
                boolean defaultEnabled
        ) {
        }

        /** {@code badcore-settings.chatcolors.<id>:} entry. */
        public record ChatColorPreset(
                String id,
                String name,
                String material,
                String permission
        ) {
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

    /** {@code modelengine:} section (ModelEngine R4 blueprints & bone models). Reloadable live. */
    public record ModelEngine(
            boolean enabled,
            String dataPath,
            String packZip,
            String defaultItem
    ) {
        public static final ModelEngine DISABLED = new ModelEngine(false, "modelengine", "", "leather_horse_armor");
    }

    /** {@code mythicmobs:} section (MythicMobs Mobs/*.yml definitions for lobby NPCs). Reloadable live. */
    public record MythicMobs(
            boolean enabled,
            String dataPath
    ) {
        public static final MythicMobs DISABLED = new MythicMobs(false, "mythicmobs");
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
        ModelEngine modelEngine = readModelEngine(reader);
        MythicMobs mythicMobs = readMythicMobs(reader);
        return new IntegrationsConfig(database, luckPerms, liteBans, skinsRestorer, bridge, signedVelocity, mineSkin,
                itemsAdder, voiceChat, badcoreSettings, modelEngine, mythicMobs);
    }

    private static ModelEngine readModelEngine(ConfigReader reader) {
        boolean enabled = reader.bool("modelengine.enabled", false);
        String dataPath = reader.string("modelengine.data-path", "modelengine").strip();
        String packZip = reader.string("modelengine.pack-zip", "").strip();
        String defaultItem = reader.string("modelengine.default-item", "leather_horse_armor").strip();
        if (defaultItem.isEmpty()) {
            defaultItem = "leather_horse_armor";
        }
        return new ModelEngine(enabled, dataPath, packZip, defaultItem);
    }

    private static MythicMobs readMythicMobs(ConfigReader reader) {
        boolean enabled = reader.bool("mythicmobs.enabled", false);
        String dataPath = reader.string("mythicmobs.data-path", "mythicmobs").strip();
        return new MythicMobs(enabled, dataPath);
    }

    private static BadcoreSettings readBadcoreSettings(ConfigReader reader) {
        boolean enabled = reader.bool("badcore-settings.enabled", false);
        long cooldownMs = reader.integer("badcore-settings.cooldown-ms", 0, 60_000, 500);
        String scoreboardCommand = reader.string("badcore-settings.scoreboard-command", "sb").trim();
        String vipLabel = reader.string("badcore-settings.messages.vip.label", "VIP").trim();
        if (vipLabel.isEmpty()) {
            vipLabel = "VIP";
        }
        String vipBadge = reader.string("badcore-settings.messages.vip.badge", " &7%vip%");
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

        java.util.List<BadcoreSettings.TabViewItem> tabViewItems = new java.util.ArrayList<>();
        for (String key : reader.optionalKeys("badcore-settings.tabview.items")) {
            String base = "badcore-settings.tabview.items." + key;
            String id = key.trim().toLowerCase(java.util.Locale.ROOT);
            if (id.isEmpty()) {
                continue;
            }
            String name = reader.string(base + ".name", key);
            String material = reader.string(base + ".material", "NAME_TAG").trim();
            String permission = reader.string(base + ".permission", "").trim();
            boolean defaultEnabled = reader.bool(base + ".default_enabled", false);
            tabViewItems.add(new BadcoreSettings.TabViewItem(id, name, material, permission, defaultEnabled));
        }

        java.util.List<BadcoreSettings.ChatColorPreset> chatColors = new java.util.ArrayList<>();
        for (String key : reader.optionalKeys("badcore-settings.chatcolors")) {
            String base = "badcore-settings.chatcolors." + key;
            String id = key.trim().toLowerCase(java.util.Locale.ROOT);
            if (id.isEmpty()) {
                continue;
            }
            String name = reader.string(base + ".name", key);
            String material = reader.string(base + ".material", "CREEPER_BANNER_PATTERN").trim();
            String permission = reader.string(base + ".permission", "badcore.chatcolor." + id).trim();
            chatColors.add(new BadcoreSettings.ChatColorPreset(id, name, material, permission));
        }

        return new BadcoreSettings(
                enabled,
                cooldownMs,
                scoreboardCommand,
                vipLabel,
                vipBadge,
                db,
                Map.copyOf(messages),
                java.util.List.copyOf(tabViewItems),
                java.util.List.copyOf(chatColors));
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
