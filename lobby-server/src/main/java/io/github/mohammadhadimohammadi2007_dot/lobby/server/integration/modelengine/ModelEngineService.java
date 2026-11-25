package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.modelengine;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.IntegrationsConfig;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.IntegrationStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Holds loaded ModelEngine R4 {@link ModelBlueprint}s for packet-only NPC rendering.
 */
public final class ModelEngineService {

    public static final String INTEGRATION_NAME = "ModelEngine";
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelEngineService.class);

    /** Server-wide active ModelEngine service, mirroring {@code ItemResolver.Active}. */
    public static final class Active {
        private static volatile @Nullable ModelEngineService instance;

        private Active() {
        }

        public static @Nullable ModelEngineService get() {
            return instance;
        }

        public static void set(@Nullable ModelEngineService service) {
            instance = service;
        }

        public static void reset() {
            instance = null;
        }
    }

    private final Path serverDir;
    private volatile IntegrationsConfig.ModelEngine config = IntegrationsConfig.ModelEngine.DISABLED;
    private volatile Map<String, ModelBlueprint> blueprints = Map.of();

    public ModelEngineService(Path serverDir) {
        this.serverDir = serverDir;
    }

    /** Loads or reloads all ModelEngine R4 blueprints and bone item mappings. */
    public IntegrationStatus reload(IntegrationsConfig.ModelEngine settings,
                                    @Nullable IntegrationsConfig.ItemsAdder itemsAdder) {
        this.config = settings != null ? settings : IntegrationsConfig.ModelEngine.DISABLED;
        if (!this.config.enabled()) {
            this.blueprints = Map.of();
            return IntegrationStatus.disabled(INTEGRATION_NAME);
        }

        Path dataDir = resolvePath(this.config.dataPath());
        List<Path> packZips = new ArrayList<>();
        Path explicitPack = resolvePath(this.config.packZip());
        if (explicitPack != null && Files.isRegularFile(explicitPack)) {
            packZips.add(explicitPack);
        }
        // Automatically scan the merged ItemsAdder resource pack when available.
        if (itemsAdder != null) {
            Path iaZip = resolvePath(itemsAdder.packZip());
            if (iaZip != null && Files.isRegularFile(iaZip)) {
                packZips.add(iaZip);
            } else {
                Path iaData = resolvePath(itemsAdder.dataPath());
                if (iaData != null) {
                    Path autoIaZip = iaData.resolve("output").resolve("generated.zip");
                    if (Files.isRegularFile(autoIaZip)) {
                        packZips.add(autoIaZip);
                    }
                }
            }
        }

        this.blueprints = ModelEngineLoader.load(dataDir, packZips, this.config.defaultItem());
        int totalVisibleBones = this.blueprints.values().stream()
                .mapToInt(ModelBlueprint::visibleBoneCount)
                .sum();
        LOGGER.info("ModelEngine: loaded {} blueprint(s) ({} mapped bone(s))",
                this.blueprints.size(), totalVisibleBones);
        return IntegrationStatus.active(INTEGRATION_NAME,
                this.blueprints.size() + " model(s), " + totalVisibleBones + " mapped bone(s)");
    }

    /** Directly installs blueprints, for tests. */
    void setBlueprints(Map<String, ModelBlueprint> blueprints) {
        this.blueprints = Map.copyOf(blueprints);
    }

    public boolean enabled() {
        return config.enabled();
    }

    public @Nullable ModelBlueprint blueprint(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return blueprints.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<String> modelIds() {
        return blueprints.keySet();
    }

    private @Nullable Path resolvePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Path p = Path.of(raw.strip());
        return p.isAbsolute() ? p : serverDir.resolve(p);
    }
}
