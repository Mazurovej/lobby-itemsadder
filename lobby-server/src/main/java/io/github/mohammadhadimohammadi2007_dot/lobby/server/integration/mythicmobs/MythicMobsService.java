package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.mythicmobs;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.IntegrationsConfig;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.IntegrationStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * Holds loaded MythicMobs {@link MythicMobDefinition}s for spawning custom lobby NPCs.
 */
public final class MythicMobsService {

    public static final String INTEGRATION_NAME = "MythicMobs";
    private static final Logger LOGGER = LoggerFactory.getLogger(MythicMobsService.class);

    public static final class Active {
        private static volatile @Nullable MythicMobsService instance;

        private Active() {
        }

        public static @Nullable MythicMobsService get() {
            return instance;
        }

        public static void set(@Nullable MythicMobsService service) {
            instance = service;
        }

        public static void reset() {
            instance = null;
        }
    }

    private final Path serverDir;
    private volatile IntegrationsConfig.MythicMobs config = IntegrationsConfig.MythicMobs.DISABLED;
    private volatile Map<String, MythicMobDefinition> mobs = Map.of();

    public MythicMobsService(Path serverDir) {
        this.serverDir = serverDir;
    }

    /** Loads or reloads all MythicMobs {@code Mobs/*.yml} definitions. */
    public IntegrationStatus reload(IntegrationsConfig.MythicMobs settings) {
        this.config = settings != null ? settings : IntegrationsConfig.MythicMobs.DISABLED;
        if (!this.config.enabled()) {
            this.mobs = Map.of();
            return IntegrationStatus.disabled(INTEGRATION_NAME);
        }
        Path dataDir = resolvePath(this.config.dataPath());
        this.mobs = MythicMobsLoader.load(dataDir);
        LOGGER.info("MythicMobs: loaded {} mob definition(s)", this.mobs.size());
        return IntegrationStatus.active(INTEGRATION_NAME, this.mobs.size() + " mob(s)");
    }

    /** Directly installs mob definitions, for tests. */
    void setMobs(Map<String, MythicMobDefinition> mobs) {
        this.mobs = Map.copyOf(mobs);
    }

    public boolean enabled() {
        return config.enabled();
    }

    public @Nullable MythicMobDefinition mob(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return mobs.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<String> mobIds() {
        return mobs.values().stream().map(MythicMobDefinition::id).toList();
    }

    private @Nullable Path resolvePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Path p = Path.of(raw.strip());
        return p.isAbsolute() ? p : serverDir.resolve(p);
    }
}
