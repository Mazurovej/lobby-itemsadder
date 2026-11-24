package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.IntegrationsConfig.BadcoreSettings;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dedicated MariaDB connection pool for badcoreSettings ({@code badcore_qoa_settings},
 * {@code practice_profiles}, and {@code practice_settings}).
 */
public final class BadcoreSettingsDatabase implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(BadcoreSettingsDatabase.class);
    private static final String DEFAULT_QOA_TABLE = "badcore_qoa_settings";

    public record ConnectionTestResult(
            boolean success,
            long latencyMs,
            String targetSummary,
            String serverVersion,
            @Nullable String errorMessage
    ) {
    }

    private final ExecutorService dbExecutor;
    private volatile @Nullable HikariDataSource dataSource;
    private volatile boolean connected;
    private volatile @Nullable BadcoreSettings.Database currentConfig;

    public BadcoreSettingsDatabase() {
        AtomicInteger idx = new AtomicInteger(1);
        this.dbExecutor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "BadcoreSettings-DB-" + idx.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Opens (or re-opens) the MariaDB pool and ensures all required tables/columns exist.
     *
     * @return {@code null} on success or disabled; an error message if connecting failed
     */
    public synchronized @Nullable String init(@Nullable BadcoreSettings.Database cfg) {
        closePoolOnly();
        this.currentConfig = cfg;
        if (cfg == null || !cfg.enabled()) {
            this.connected = false;
            return null;
        }
        try {
            HikariConfig hikari = new HikariConfig();
            hikari.setPoolName("BadcoreSettings-MariaDB");
            hikari.setDriverClassName(org.mariadb.jdbc.Driver.class.getName());
            hikari.setJdbcUrl(buildJdbcUrl(cfg));
            hikari.setUsername(cfg.username());
            hikari.setPassword(cfg.password());
            hikari.setMaximumPoolSize(cfg.poolSize());
            hikari.setMinimumIdle(Math.min(2, cfg.poolSize()));
            hikari.setConnectionTimeout(cfg.connectionTimeoutMs());
            hikari.setInitializationFailTimeout(-1L);
            hikari.setMaxLifetime(1_800_000L);
            hikari.setKeepaliveTime(60_000L);
            hikari.addDataSourceProperty("cachePrepStmts", "true");
            hikari.addDataSourceProperty("prepStmtCacheSize", "250");
            hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            hikari.addDataSourceProperty("useServerPrepStmts", "true");

            this.dataSource = new HikariDataSource(hikari);
            initSchema(cfg.qoaTable());
            this.connected = true;
            LOGGER.info("badcoreSettings connected to MariaDB ({}:{}/{}).", cfg.host(), cfg.port(), cfg.database());
            return null;
        } catch (Exception e) {
            this.connected = false;
            String message = e.getMessage() != null ? e.getMessage() : e.toString();
            LOGGER.error("badcoreSettings failed to connect to MariaDB: {}", message);
            closePoolOnly();
            return message;
        }
    }

    private void initSchema(String qoaTable) throws SQLException {
        String createQoa = """
                CREATE TABLE IF NOT EXISTS %s (
                    uuid CHAR(36) NOT NULL PRIMARY KEY,
                    msg_enabled TINYINT(1) NOT NULL DEFAULT 1,
                    find_enabled TINYINT(1) NOT NULL DEFAULT 1,
                    social_spy TINYINT(1) NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """.formatted(qoaTable);

        String createProfiles = """
                CREATE TABLE IF NOT EXISTS practice_profiles (
                    uuid CHAR(36) NOT NULL PRIMARY KEY,
                    username VARCHAR(16) NOT NULL,
                    wins INT NOT NULL DEFAULT 0,
                    losses INT NOT NULL DEFAULT 0,
                    win_streak INT NOT NULL DEFAULT 0,
                    best_streak INT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX idx_practice_profiles_username (username)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """;

        String createPracticeSettings = """
                CREATE TABLE IF NOT EXISTS practice_settings (
                    uuid CHAR(36) NOT NULL PRIMARY KEY,
                    duel_requests TINYINT(1) NOT NULL DEFAULT 1,
                    party_invites TINYINT(1) NOT NULL DEFAULT 1,
                    lobby_visibility TINYINT(1) NOT NULL DEFAULT 1,
                    time_preset VARCHAR(16) NOT NULL DEFAULT 'DAY',
                    lobby_fly TINYINT(1) NOT NULL DEFAULT 1,
                    vip_join_message TINYINT(1) NOT NULL DEFAULT 1,
                    allow_spectators TINYINT(1) NOT NULL DEFAULT 1,
                    silent_spectate TINYINT(1) NOT NULL DEFAULT 1,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    CONSTRAINT fk_practice_settings_profile FOREIGN KEY (uuid)
                        REFERENCES practice_profiles(uuid) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """;

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createQoa);
            stmt.execute(createProfiles);
            stmt.execute(createPracticeSettings);
            stmt.execute("ALTER TABLE practice_settings ADD COLUMN IF NOT EXISTS lobby_fly TINYINT(1) NOT NULL DEFAULT 1;");
            stmt.execute("ALTER TABLE practice_settings ADD COLUMN IF NOT EXISTS vip_join_message TINYINT(1) NOT NULL DEFAULT 1;");
            stmt.execute("ALTER TABLE practice_settings ADD COLUMN IF NOT EXISTS allow_spectators TINYINT(1) NOT NULL DEFAULT 1;");
            stmt.execute("ALTER TABLE practice_settings ADD COLUMN IF NOT EXISTS silent_spectate TINYINT(1) NOT NULL DEFAULT 1;");
        }
    }

    public Connection getConnection() throws SQLException {
        HikariDataSource ds = this.dataSource;
        if (ds == null || ds.isClosed()) {
            throw new SQLException("MariaDB DataSource is not initialized.");
        }
        return ds.getConnection();
    }

    public static String buildJdbcUrl(BadcoreSettings.Database cfg) {
        return String.format(
                "jdbc:mariadb://%s:%d/%s?useSSL=%s&autoReconnect=true&useUnicode=true&characterEncoding=utf8",
                cfg.host(), cfg.port(), cfg.database(), cfg.useSsl()
        );
    }

    public CompletableFuture<ConnectionTestResult> testConnectionAsync(@Nullable BadcoreSettings.Database cfg) {
        return CompletableFuture.supplyAsync(() -> testConnectionSync(cfg), dbExecutor);
    }

    public ConnectionTestResult testConnectionSync(@Nullable BadcoreSettings.Database cfg) {
        if (cfg == null || cfg.host().isBlank() || cfg.database().isBlank()) {
            return new ConnectionTestResult(false, 0L, "<invalid>", "", "Missing host or database.");
        }
        String target = cfg.host() + ":" + cfg.port() + "/" + cfg.database();
        long start = System.nanoTime();
        try (Connection conn = openProbe(cfg)) {
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT 1")) {
                if (!rs.next()) {
                    throw new SQLException("SELECT 1 returned no rows.");
                }
            }
            DatabaseMetaData meta = conn.getMetaData();
            String version = meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
            long ms = Math.max(0L, (System.nanoTime() - start) / 1_000_000L);
            return new ConnectionTestResult(true, ms, target, version, null);
        } catch (Exception e) {
            long ms = Math.max(0L, (System.nanoTime() - start) / 1_000_000L);
            String msg = e.getMessage() != null ? e.getMessage().replace('\r', ' ').replace('\n', ' ').trim() : e.toString();
            if (msg.length() > 160) {
                msg = msg.substring(0, 157) + "...";
            }
            return new ConnectionTestResult(false, ms, target, "", e.getClass().getSimpleName() + ": " + msg);
        }
    }

    private Connection openProbe(BadcoreSettings.Database cfg) throws SQLException {
        if (isConnected() && cfg.enabled()) {
            return getConnection();
        }
        try {
            Class.forName(org.mariadb.jdbc.Driver.class.getName());
        } catch (ClassNotFoundException e) {
            throw new SQLException("MariaDB driver missing", e);
        }
        Properties props = new Properties();
        props.setProperty("user", cfg.username());
        props.setProperty("password", cfg.password());
        props.setProperty("connectTimeout", String.valueOf((int) cfg.connectionTimeoutMs()));
        props.setProperty("socketTimeout", String.valueOf((int) cfg.connectionTimeoutMs()));
        return DriverManager.getConnection(buildJdbcUrl(cfg), props);
    }

    public boolean isConnected() {
        HikariDataSource ds = this.dataSource;
        return connected && ds != null && !ds.isClosed();
    }

    public String qoaTable() {
        BadcoreSettings.Database cfg = this.currentConfig;
        return cfg != null ? cfg.qoaTable() : DEFAULT_QOA_TABLE;
    }

    public @Nullable BadcoreSettings.Database currentConfig() {
        return currentConfig;
    }

    public ExecutorService executor() {
        return dbExecutor;
    }

    public synchronized void closePoolOnly() {
        this.connected = false;
        HikariDataSource ds = this.dataSource;
        if (ds != null && !ds.isClosed()) {
            ds.close();
        }
        this.dataSource = null;
    }

    @Override
    public void close() {
        closePoolOnly();
        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                dbExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            dbExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
