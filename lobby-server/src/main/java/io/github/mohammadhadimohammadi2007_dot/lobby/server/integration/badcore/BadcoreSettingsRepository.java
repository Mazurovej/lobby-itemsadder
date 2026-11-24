package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore;

import net.minestom.server.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * In-memory cache, toggle cooldowns, and serialized per-player MariaDB persistence
 * for both Global (QOA) and Practice/Duels settings.
 */
public final class BadcoreSettingsRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(BadcoreSettingsRepository.class);

    public enum TimePreset {
        DAY("Day"),
        SUNSET("Sunset"),
        NIGHT("Night");

        private final String label;

        TimePreset(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public TimePreset next() {
            TimePreset[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }

        public static TimePreset parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return DAY;
            }
            try {
                return TimePreset.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return DAY;
            }
        }
    }

    public enum DuelsSetting {
        DUEL_REQUESTS,
        PARTY_INVITES,
        LOBBY_VISIBILITY,
        TIME_OF_DAY,
        LOBBY_FLY,
        VIP_JOIN_MESSAGE,
        ALLOW_SPECTATORS,
        SILENT_SPECTATE
    }

    public record QoaState(boolean msgEnabled, boolean findEnabled, boolean socialSpy) {
        public static final QoaState DEFAULT = new QoaState(true, true, false);
    }

    public record DuelsState(
            boolean duelRequests,
            boolean partyInvites,
            boolean lobbyVisibility,
            TimePreset timePreset,
            boolean lobbyFly,
            boolean vipJoinMessage,
            boolean allowSpectators,
            boolean silentSpectate
    ) {
        public static final DuelsState DEFAULT = new DuelsState(true, true, true, TimePreset.DAY, true, true, true, true);
    }

    private final BadcoreSettingsDatabase db;
    private final BadcoreSettingsBridge bridge;
    private final LongSupplier clock;

    private final Map<UUID, QoaState> qoaCache = new ConcurrentHashMap<>();
    private final Map<UUID, DuelsState> duelsCache = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> pendingOps = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastClickAt = new ConcurrentHashMap<>();

    public BadcoreSettingsRepository(BadcoreSettingsDatabase db, BadcoreSettingsBridge bridge) {
        this(db, bridge, System::currentTimeMillis);
    }

    BadcoreSettingsRepository(BadcoreSettingsDatabase db, BadcoreSettingsBridge bridge, LongSupplier clock) {
        this.db = db;
        this.bridge = bridge;
        this.clock = clock;
    }

    public long checkCooldown(UUID uuid, long cooldownMs) {
        if (cooldownMs <= 0L) {
            return 0L;
        }
        long now = clock.getAsLong();
        Long prev = lastClickAt.get(uuid);
        if (prev != null) {
            long elapsed = now - prev;
            if (elapsed >= 0L && elapsed < cooldownMs) {
                return cooldownMs - elapsed;
            }
        }
        lastClickAt.put(uuid, now);
        return 0L;
    }

    public QoaState getQoaState(UUID uuid) {
        return qoaCache.getOrDefault(uuid, QoaState.DEFAULT);
    }

    public DuelsState getDuelsState(UUID uuid) {
        return duelsCache.getOrDefault(uuid, DuelsState.DEFAULT);
    }

    public void updateQoaCacheFromProxy(UUID uuid, QoaState state) {
        qoaCache.put(uuid, state);
    }

    public void updateDuelsCacheFromProxy(UUID uuid, DuelsState state) {
        duelsCache.put(uuid, state);
    }

    public CompletableFuture<Void> loadAllAsync(UUID uuid) {
        if (uuid == null || !db.isConnected()) {
            return CompletableFuture.completedFuture(null);
        }
        return enqueue(uuid, () -> {
            loadQoaSync(uuid);
            loadDuelsSync(uuid);
        });
    }

    private void loadQoaSync(UUID uuid) {
        String sql = "SELECT msg_enabled, find_enabled, social_spy FROM " + db.qoaTable() + " WHERE uuid = ?;";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    qoaCache.put(uuid, new QoaState(
                            rs.getBoolean("msg_enabled"),
                            rs.getBoolean("find_enabled"),
                            rs.getBoolean("social_spy")
                    ));
                } else {
                    qoaCache.putIfAbsent(uuid, QoaState.DEFAULT);
                }
            }
        } catch (SQLException e) {
            LOGGER.warn("Failed to load QOA settings for {}: {}", uuid, e.getMessage());
        }
    }

    private void loadDuelsSync(UUID uuid) {
        String sql = "SELECT duel_requests, party_invites, lobby_visibility, time_preset, lobby_fly, vip_join_message, allow_spectators, silent_spectate FROM practice_settings WHERE uuid = ?;";
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    duelsCache.put(uuid, new DuelsState(
                            rs.getBoolean("duel_requests"),
                            rs.getBoolean("party_invites"),
                            rs.getBoolean("lobby_visibility"),
                            TimePreset.parse(rs.getString("time_preset")),
                            rs.getBoolean("lobby_fly"),
                            rs.getBoolean("vip_join_message"),
                            rs.getBoolean("allow_spectators"),
                            rs.getBoolean("silent_spectate")
                    ));
                } else {
                    duelsCache.putIfAbsent(uuid, DuelsState.DEFAULT);
                }
            }
        } catch (SQLException e) {
            LOGGER.warn("Failed to load Duels settings for {}: {}", uuid, e.getMessage());
        }
    }

    public boolean toggleQoa(Player player, String key) {
        UUID uuid = player.getUuid();
        QoaState updated = qoaCache.compute(uuid, (id, cur) -> {
            QoaState s = cur != null ? cur : QoaState.DEFAULT;
            return switch (key) {
                case "msg" -> new QoaState(!s.msgEnabled(), s.findEnabled(), s.socialSpy());
                case "find" -> new QoaState(s.msgEnabled(), !s.findEnabled(), s.socialSpy());
                case "socialspy" -> new QoaState(s.msgEnabled(), s.findEnabled(), !s.socialSpy());
                default -> s;
            };
        });

        boolean newVal = switch (key) {
            case "msg" -> updated.msgEnabled();
            case "find" -> updated.findEnabled();
            case "socialspy" -> updated.socialSpy();
            default -> false;
        };

        bridge.sendSet(player, key, newVal);

        if (db.isConnected()) {
            enqueue(uuid, () -> saveQoaSync(uuid, updated));
        }
        return newVal;
    }

    private void saveQoaSync(UUID uuid, QoaState state) {
        String sql = """
                INSERT INTO %s (uuid, msg_enabled, find_enabled, social_spy)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    msg_enabled = VALUES(msg_enabled),
                    find_enabled = VALUES(find_enabled),
                    social_spy = VALUES(social_spy);
                """.formatted(db.qoaTable());
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setBoolean(2, state.msgEnabled());
            ps.setBoolean(3, state.findEnabled());
            ps.setBoolean(4, state.socialSpy());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.warn("Failed to save QOA settings for {}: {}", uuid, e.getMessage());
        }
    }

    public DuelsState toggleDuels(Player player, DuelsSetting setting) {
        UUID uuid = player.getUuid();
        DuelsState updated = duelsCache.compute(uuid, (id, cur) -> {
            DuelsState s = cur != null ? cur : DuelsState.DEFAULT;
            return switch (setting) {
                case DUEL_REQUESTS -> new DuelsState(!s.duelRequests(), s.partyInvites(), s.lobbyVisibility(), s.timePreset(), s.lobbyFly(), s.vipJoinMessage(), s.allowSpectators(), s.silentSpectate());
                case PARTY_INVITES -> new DuelsState(s.duelRequests(), !s.partyInvites(), s.lobbyVisibility(), s.timePreset(), s.lobbyFly(), s.vipJoinMessage(), s.allowSpectators(), s.silentSpectate());
                case LOBBY_VISIBILITY -> new DuelsState(s.duelRequests(), s.partyInvites(), !s.lobbyVisibility(), s.timePreset(), s.lobbyFly(), s.vipJoinMessage(), s.allowSpectators(), s.silentSpectate());
                case TIME_OF_DAY -> new DuelsState(s.duelRequests(), s.partyInvites(), s.lobbyVisibility(), s.timePreset().next(), s.lobbyFly(), s.vipJoinMessage(), s.allowSpectators(), s.silentSpectate());
                case LOBBY_FLY -> new DuelsState(s.duelRequests(), s.partyInvites(), s.lobbyVisibility(), s.timePreset(), !s.lobbyFly(), s.vipJoinMessage(), s.allowSpectators(), s.silentSpectate());
                case VIP_JOIN_MESSAGE -> new DuelsState(s.duelRequests(), s.partyInvites(), s.lobbyVisibility(), s.timePreset(), s.lobbyFly(), !s.vipJoinMessage(), s.allowSpectators(), s.silentSpectate());
                case ALLOW_SPECTATORS -> new DuelsState(s.duelRequests(), s.partyInvites(), s.lobbyVisibility(), s.timePreset(), s.lobbyFly(), s.vipJoinMessage(), !s.allowSpectators(), s.silentSpectate());
                case SILENT_SPECTATE -> new DuelsState(s.duelRequests(), s.partyInvites(), s.lobbyVisibility(), s.timePreset(), s.lobbyFly(), s.vipJoinMessage(), s.allowSpectators(), !s.silentSpectate());
            };
        });

        bridge.sendSetDuels(player, updated);

        String playerName = player.getUsername();
        if (db.isConnected()) {
            enqueue(uuid, () -> saveDuelsSync(uuid, playerName, updated));
        }
        return updated;
    }

    private void saveDuelsSync(UUID uuid, String username, DuelsState s) {
        String safeName = (username != null && !username.isBlank()) ? username : "Unknown";
        String uuidStr = uuid.toString();
        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String ensureProfile = """
                        INSERT INTO practice_profiles (uuid, username)
                        VALUES (?, ?)
                        ON DUPLICATE KEY UPDATE uuid = uuid;
                        """;
                try (PreparedStatement ps = conn.prepareStatement(ensureProfile)) {
                    ps.setString(1, uuidStr);
                    ps.setString(2, safeName);
                    ps.executeUpdate();
                }

                String upsertSettings = """
                        INSERT INTO practice_settings (uuid, duel_requests, party_invites, lobby_visibility, time_preset, lobby_fly, vip_join_message, allow_spectators, silent_spectate)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            duel_requests = VALUES(duel_requests),
                            party_invites = VALUES(party_invites),
                            lobby_visibility = VALUES(lobby_visibility),
                            time_preset = VALUES(time_preset),
                            lobby_fly = VALUES(lobby_fly),
                            vip_join_message = VALUES(vip_join_message),
                            allow_spectators = VALUES(allow_spectators),
                            silent_spectate = VALUES(silent_spectate);
                        """;
                try (PreparedStatement ps = conn.prepareStatement(upsertSettings)) {
                    ps.setString(1, uuidStr);
                    ps.setBoolean(2, s.duelRequests());
                    ps.setBoolean(3, s.partyInvites());
                    ps.setBoolean(4, s.lobbyVisibility());
                    ps.setString(5, s.timePreset().name());
                    ps.setBoolean(6, s.lobbyFly());
                    ps.setBoolean(7, s.vipJoinMessage());
                    ps.setBoolean(8, s.allowSpectators());
                    ps.setBoolean(9, s.silentSpectate());
                    ps.executeUpdate();
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            LOGGER.warn("Failed to save Duels settings for {}: {}", uuid, e.getMessage());
        }
    }

    public void cleanupDisconnect(UUID uuid) {
        lastClickAt.remove(uuid);
        CompletableFuture<Void> pending = pendingOps.get(uuid);
        if (pending != null && !pending.isDone()) {
            pending.whenComplete((u, ex) -> {
                qoaCache.remove(uuid);
                duelsCache.remove(uuid);
            });
        } else {
            qoaCache.remove(uuid);
            duelsCache.remove(uuid);
        }
    }

    public void shutdown() {
        for (CompletableFuture<Void> op : pendingOps.values()) {
            try {
                op.get(4, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {
            }
        }
        pendingOps.clear();
        qoaCache.clear();
        duelsCache.clear();
        lastClickAt.clear();
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Void> enqueue(UUID uuid, Runnable action) {
        CompletableFuture<Void>[] holder = new CompletableFuture[1];
        CompletableFuture<Void> chained = pendingOps.compute(uuid, (id, prev) -> {
            CompletableFuture<Void> base = (prev != null)
                    ? prev.exceptionally(ex -> null)
                    : CompletableFuture.completedFuture(null);
            CompletableFuture<Void> next = base.thenRunAsync(action, db.executor());
            holder[0] = next;
            return next;
        });
        CompletableFuture<Void> created = holder[0];
        chained.whenComplete((u, ex) -> pendingOps.remove(uuid, created));
        return chained;
    }
}
