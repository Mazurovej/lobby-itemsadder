package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.ConfigException;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.ConfigManager;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.player.PermissionService;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.player.Permissions;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.util.Async;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.entity.Player;

import java.util.concurrent.CompletionException;

/**
 * {@code /settings [global|qoa|duels|practice|reload|dbtest]} (aliases: {@code /prefs}, {@code /options}).
 */
public final class BadcoreSettingsCommand extends Command {

    private final BadcoreSettingsService service;
    private final ConfigManager configManager;
    private final PermissionService permissions;

    public BadcoreSettingsCommand(BadcoreSettingsService service,
                                  ConfigManager configManager,
                                  PermissionService permissions) {
        super("settings", "prefs", "options");
        this.service = service;
        this.configManager = configManager;
        this.permissions = permissions;

        setCondition((sender, commandString) -> {
            boolean allowed = permissions.hasPermission(sender, Permissions.BADCORE_SETTINGS_USE)
                    || permissions.hasPermission(sender, Permissions.BADCORE_SETTINGS_ADMIN);
            if (!allowed && commandString != null) {
                service.sendMessage(sender, "no-permission");
            }
            return allowed;
        });

        setDefaultExecutor((sender, context) -> openSection(sender, "hub"));

        Command globalSub = new Command("global", "qoa");
        globalSub.setCondition(useCondition());
        globalSub.setDefaultExecutor((sender, context) -> openSection(sender, "global"));
        addSubcommand(globalSub);

        Command duelsSub = new Command("duels", "practice");
        duelsSub.setCondition(useCondition());
        duelsSub.setDefaultExecutor((sender, context) -> openSection(sender, "duels"));
        addSubcommand(duelsSub);

        Command reloadSub = new Command("reload");
        reloadSub.setCondition(adminCondition());
        reloadSub.setDefaultExecutor((sender, context) -> runReload(sender));
        addSubcommand(reloadSub);

        Command dbtestSub = new Command("dbtest");
        dbtestSub.setCondition(adminCondition());
        dbtestSub.setDefaultExecutor((sender, context) -> runDbTest(sender));
        addSubcommand(dbtestSub);
    }

    private net.minestom.server.command.builder.condition.CommandCondition useCondition() {
        return (sender, commandString) -> {
            boolean allowed = permissions.hasPermission(sender, Permissions.BADCORE_SETTINGS_USE);
            if (!allowed && commandString != null) {
                service.sendMessage(sender, "no-permission");
            }
            return allowed;
        };
    }

    private net.minestom.server.command.builder.condition.CommandCondition adminCondition() {
        return (sender, commandString) -> {
            boolean allowed = permissions.hasPermission(sender, Permissions.BADCORE_SETTINGS_ADMIN);
            if (!allowed && commandString != null) {
                service.sendMessage(sender, "no-permission");
            }
            return allowed;
        };
    }

    private void openSection(CommandSender sender, String section) {
        if (!(sender instanceof Player player)) {
            service.sendMessage(sender, "player-only");
            return;
        }
        if (!permissions.hasPermission(player, Permissions.BADCORE_SETTINGS_USE)) {
            service.sendMessage(player, "no-permission");
            return;
        }
        service.openMenu(player, section);
    }

    private void runReload(CommandSender sender) {
        Async.supply(() -> {
            try {
                configManager.reload();
                return null;
            } catch (ConfigException e) {
                throw new CompletionException(e);
            }
        }).whenComplete((ignored, error) -> Async.onTickThread(() -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                service.sendMessage(sender, "dbtest-fail",
                        "target", "config",
                        "latency", "0",
                        "error", cause.getMessage() != null ? cause.getMessage() : cause.toString());
            } else {
                service.sendMessage(sender, "reloaded");
            }
        }));
    }

    private void runDbTest(CommandSender sender) {
        if (!service.isEnabled()) {
            service.sendMessage(sender, "dbtest-fail",
                    "target", "disabled",
                    "latency", "0",
                    "error", "badcore-settings is disabled in integrations.yml");
            return;
        }
        service.sendMessage(sender, "dbtest-running");
        service.database().testConnectionAsync(service.config().database())
                .whenComplete((res, err) -> Async.onTickThread(() -> {
                    if (err != null) {
                        service.sendMessage(sender, "dbtest-fail",
                                "target", "?",
                                "latency", "0",
                                "error", err.getMessage() != null ? err.getMessage() : err.toString());
                    } else if (res.success()) {
                        service.sendMessage(sender, "dbtest-ok",
                                "target", res.targetSummary(),
                                "version", res.serverVersion(),
                                "latency", Long.toString(res.latencyMs()));
                    } else {
                        service.sendMessage(sender, "dbtest-fail",
                                "target", res.targetSummary(),
                                "latency", Long.toString(res.latencyMs()),
                                "error", res.errorMessage() != null ? res.errorMessage() : "unknown error");
                    }
                }));
    }
}
