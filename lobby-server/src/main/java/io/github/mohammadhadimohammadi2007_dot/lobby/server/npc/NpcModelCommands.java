package io.github.mohammadhadimohammadi2007_dot.lobby.server.npc;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.MessageKey;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.Messages;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.modelengine.ModelBlueprint;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.modelengine.ModelEngineService;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.mythicmobs.MythicMobDefinition;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.mythicmobs.MythicMobsService;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * ModelEngine R4 and MythicMobs subcommands of {@code /npc}:
 * <ul>
 *     <li>{@code /npc model <npc> <model_id|none> [anim]}</li>
 *     <li>{@code /npc animation <npc> <anim|none>}</li>
 *     <li>{@code /npc mythic <npc_name> <mob_id> [<x> <y> <z>]}</li>
 *     <li>{@code /npc models}</li>
 *     <li>{@code /npc mythics}</li>
 * </ul>
 */
final class NpcModelCommands {

    private final NpcCommandSupport support;

    NpcModelCommands(NpcCommandSupport support) {
        this.support = support;
    }

    void addTo(Command parent, ArgumentWord npc) {
        parent.addSubcommand(model(npc));
        parent.addSubcommand(animation(npc));
        parent.addSubcommand(mythic());
        parent.addSubcommand(models());
        parent.addSubcommand(mythics());
    }

    private Command model(ArgumentWord npc) {
        Command command = new Command("model", "meg");
        command.setDefaultExecutor((sender, context) -> support.usage(sender));
        ArgumentWord modelId = ArgumentType.Word("model_id");
        modelId.setSuggestionCallback((sender, context, suggestion) -> {
            suggestion.addEntry(new SuggestionEntry("none"));
            ModelEngineService meg = ModelEngineService.Active.get();
            if (meg != null) {
                for (String id : meg.modelIds()) {
                    suggestion.addEntry(new SuggestionEntry(id));
                }
            }
        });
        ArgumentWord anim = ArgumentType.Word("anim");
        anim.setSuggestionCallback((sender, context, suggestion) -> {
            suggestion.addEntry(new SuggestionEntry("idle"));
            suggestion.addEntry(new SuggestionEntry("none"));
            ModelEngineService meg = ModelEngineService.Active.get();
            if (meg != null) {
                String mId = context.get(modelId);
                ModelBlueprint bp = meg.blueprint(mId);
                if (bp != null) {
                    for (String a : bp.animations().keySet()) {
                        suggestion.addEntry(new SuggestionEntry(a));
                    }
                }
            }
        });
        command.addSyntax((sender, context) -> setModel(sender, context.get(npc), context.get(modelId), null),
                npc, modelId);
        command.addSyntax((sender, context) -> setModel(sender, context.get(npc), context.get(modelId), context.get(anim)),
                npc, modelId, anim);
        return command;
    }

    private void setModel(CommandSender sender, String npcName, String rawModelId, String rawAnim) {
        NpcData data = support.found(sender, npcName);
        if (data == null) {
            return;
        }
        String wanted = rawModelId.strip().toLowerCase(Locale.ROOT);
        if (wanted.equals("none") || wanted.equals("clear") || wanted.equals("off")) {
            data.model("");
            support.npcs.changed(data);
            support.reply(sender, "Cleared ModelEngine model on NPC <white>" + data.name() + "</white>.");
            return;
        }
        ModelEngineService meg = ModelEngineService.Active.get();
        ModelBlueprint bp = meg == null ? null : meg.blueprint(wanted);
        if (bp == null) {
            support.reply(sender, "<red>Unknown ModelEngine blueprint '<white>" + wanted
                    + "</white>'. Check modelengine.enabled in integrations.yml and run /npc models.");
            return;
        }
        data.model(bp.id());
        if (rawAnim != null) {
            data.modelAnimation(rawAnim);
        }
        support.npcs.changed(data);
        if (bp.visibleBoneCount() == 0) {
            support.reply(sender, "<yellow>Set model of <white>" + data.name() + "</white> to <aqua>" + bp.id()
                    + "</aqua>, but <red>0/" + bp.bones().size()
                    + " bones matched the resource pack</red> — falling back to vanilla entity until the merged pack contains its bone models.");
        } else {
            support.reply(sender, "Set model of <white>" + data.name() + "</white> to <aqua>" + bp.id()
                    + "</aqua> (" + bp.visibleBoneCount() + "/" + bp.bones().size()
                    + " bone(s), animation: <yellow>" + data.modelAnimation() + "</yellow>).");
        }
    }

    private Command animation(ArgumentWord npc) {
        Command command = new Command("animation", "anim");
        command.setDefaultExecutor((sender, context) -> support.usage(sender));
        ArgumentWord anim = ArgumentType.Word("anim");
        anim.setSuggestionCallback((sender, context, suggestion) -> {
            suggestion.addEntry(new SuggestionEntry("idle"));
            suggestion.addEntry(new SuggestionEntry("none"));
            NpcData data = support.npcs.get(context.get(npc));
            ModelBlueprint bp = data == null ? null : data.resolvedBlueprint();
            if (bp != null) {
                for (String a : bp.animations().keySet()) {
                    suggestion.addEntry(new SuggestionEntry(a));
                }
            }
        });
        command.addSyntax((sender, context) -> {
            NpcData data = support.found(sender, context.get(npc));
            if (data == null) {
                return;
            }
            data.modelAnimation(context.get(anim));
            support.npcs.changed(data);
            support.reply(sender, "Set animation of <white>" + data.name() + "</white> to <yellow>"
                    + data.modelAnimation() + "</yellow>.");
        }, npc, anim);
        return command;
    }

    private Command mythic() {
        Command command = new Command("mythic", "mm");
        command.setDefaultExecutor((sender, context) -> support.usage(sender));
        ArgumentWord npcName = ArgumentType.Word("npc_name");
        ArgumentWord mobId = ArgumentType.Word("mob_id");
        mobId.setSuggestionCallback((sender, context, suggestion) -> {
            MythicMobsService mm = MythicMobsService.Active.get();
            if (mm != null) {
                for (String id : mm.mobIds()) {
                    suggestion.addEntry(new SuggestionEntry(id));
                }
            }
        });
        var x = ArgumentType.Double("x");
        var y = ArgumentType.Double("y");
        var z = ArgumentType.Double("z");

        command.addSyntax((sender, context) -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(support.text.message(MessageKey.NPC_CONSOLE_CREATE, sender));
                return;
            }
            spawnMythic(sender, context.get(npcName), context.get(mobId), player.getPosition());
        }, npcName, mobId);
        command.addSyntax((sender, context) -> spawnMythic(sender, context.get(npcName), context.get(mobId),
                new Pos(context.get(x), context.get(y), context.get(z))), npcName, mobId, x, y, z);
        return command;
    }

    private void spawnMythic(CommandSender sender, String rawName, String rawMobId, Pos where) {
        String wantedName = rawName.toLowerCase(Locale.ROOT);
        if (!NpcService.validName(wantedName)) {
            sender.sendMessage(support.text.message(MessageKey.NPC_INVALID_NAME, sender,
                    Messages.text("name", rawName)));
            return;
        }
        MythicMobsService mm = MythicMobsService.Active.get();
        MythicMobDefinition def = mm == null ? null : mm.mob(rawMobId);
        if (def == null) {
            support.reply(sender, "<red>Unknown MythicMob '<white>" + rawMobId
                    + "</white>'. Check mythicmobs.enabled in integrations.yml and run /npc mythics.");
            return;
        }
        NpcData data = support.npcs.create(wantedName, where);
        if (data == null) {
            sender.sendMessage(support.text.message(MessageKey.NPC_EXISTS, sender, Messages.text("name", wantedName)));
            return;
        }
        data.type(def.entityType());
        data.scale(def.scale());
        data.mythicMob(def.id());
        if (!def.displayName().isBlank()) {
            data.nameTag().lines(List.of(def.displayName()));
        }
        def.equipment().forEach(data::equipmentSpec);
        if (def.hasModel()) {
            data.model(def.modelId());
            data.modelAnimation(def.modelAnimation());
        }
        support.npcs.changed(data);
        String modelNote;
        if (!def.hasModel()) {
            modelNote = " <gray>(no ModelEngine model in mob YAML; use /npc model " + data.name() + " <model>)</gray>";
        } else {
            ModelBlueprint bp = data.resolvedBlueprint();
            if (bp == null) {
                modelNote = " <red>(model '" + def.modelId() + "' not found in modelengine/blueprints/)</red>";
            } else if (bp.visibleBoneCount() == 0) {
                modelNote = " <red>(model '" + bp.id() + "' has 0/" + bp.bones().size() + " mapped bones in pack)</red>";
            } else {
                modelNote = " with model <aqua>" + bp.id() + "</aqua> (" + bp.visibleBoneCount() + " bone(s))";
            }
        }
        support.reply(sender, "Spawned MythicMob <yellow>" + def.id() + "</yellow> as NPC <white>"
                + data.name() + "</white>" + modelNote + ".");
    }

    private Command models() {
        Command command = new Command("models");
        command.setDefaultExecutor((sender, context) -> {
            ModelEngineService meg = ModelEngineService.Active.get();
            if (meg == null || !meg.enabled()) {
                support.reply(sender, "<gray>ModelEngine is disabled in integrations.yml.");
                return;
            }
            var ids = meg.modelIds();
            if (ids.isEmpty()) {
                support.reply(sender, "<gray>No ModelEngine blueprints loaded (put .bbmodel files in modelengine/blueprints/).");
                return;
            }
            List<String> summary = ids.stream().map(id -> {
                ModelBlueprint bp = meg.blueprint(id);
                if (bp == null) {
                    return id;
                }
                return id + " <gray>(" + bp.visibleBoneCount() + "/" + bp.bones().size() + " bones)</gray>";
            }).toList();
            support.reply(sender, "<green>ModelEngine blueprints (" + ids.size() + "):</green> <white>"
                    + String.join(", ", summary) + "</white>");
        });
        return command;
    }

    private Command mythics() {
        Command command = new Command("mythics");
        command.setDefaultExecutor((sender, context) -> {
            MythicMobsService mm = MythicMobsService.Active.get();
            if (mm == null || !mm.enabled()) {
                support.reply(sender, "<gray>MythicMobs is disabled in integrations.yml.");
                return;
            }
            var ids = mm.mobIds();
            if (ids.isEmpty()) {
                support.reply(sender, "<gray>No MythicMobs definitions loaded (put *.yml files in mythicmobs/Mobs/).");
                return;
            }
            List<String> summary = ids.stream().map(id -> {
                MythicMobDefinition def = mm.mob(id);
                return (def != null && def.hasModel()) ? id + " <gray>(model: " + def.modelId() + ")</gray>" : id;
            }).toList();
            support.reply(sender, "<green>MythicMobs (" + ids.size() + "):</green> <white>"
                    + String.join(", ", summary) + "</white>");
        });
        return command;
    }
}
