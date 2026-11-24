package io.github.mohammadhadimohammadi2007_dot.lobby.server.npc;

import net.minestom.server.color.TeamColor;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The {@code /npc} subcommands about how an NPC looks, with FancyNpcs' names: {@code glowing <npc>
 * [colour|disabled]}, {@code scale <npc> <factor>}, {@code scale_up_when_looking <npc> [true|false|factor]
 * [distance]}, {@code show_in_tab <npc>} and {@code attribute <npc> set pose <pose>}, plus the shorter
 * {@code pose <npc> <pose>}.
 */
final class NpcLookCommands {

    private final NpcCommandSupport support;

    NpcLookCommands(NpcCommandSupport support) {
        this.support = support;
    }

    void addTo(Command parent, ArgumentWord npc) {
        parent.addSubcommand(glowing(npc));
        parent.addSubcommand(scale(npc));
        parent.addSubcommand(scaleUpWhenLooking(npc));
        parent.addSubcommand(showInTab(npc));
        parent.addSubcommand(pose(npc));
        parent.addSubcommand(attribute(npc));
    }

    /** No value toggles; true/false/disabled set it; a colour turns it on in that colour, as in FancyNpcs. */
    private Command glowing(ArgumentWord npc) {
        Command command = new Command("glowing", "glow");
        command.setDefaultExecutor((sender, context) -> support.usage(sender));
        ArgumentWord value = ArgumentType.Word("value");
        value.setSuggestionCallback((sender, context, suggestion) -> {
            for (String option : new String[]{"true", "false", "disabled"}) {
                suggestion.addEntry(new SuggestionEntry(option));
            }
            for (TeamColor color : TeamColor.values()) {
                suggestion.addEntry(new SuggestionEntry(NpcCodec.colorName(color)));
            }
        });
        command.addSyntax((sender, context) -> glowing(sender, context.get(npc), null), npc);
        command.addSyntax((sender, context) -> glowing(sender, context.get(npc), context.get(value)), npc, value);
        return command;
    }

    private void glowing(CommandSender sender, String npcName, String value) {
        NpcData data = support.found(sender, npcName);
        if (data == null) {
            return;
        }
        TeamColor color = value == null ? null : NpcCodec.glowColor(value);
        if (color != null) {
            data.glowColor(color);
            data.glowing(true);
            support.changed(sender, data, "glowing", NpcCodec.colorName(color));
            return;
        }
        Boolean wanted = NpcCommandSupport.state(value, data.glowing());
        if (wanted == null) {
            support.invalid(sender, "glowing", "not a colour, true, false or disabled",
                    "true, false, disabled or one of " + colorNames());
            return;
        }
        data.glowing(wanted);
        support.changed(sender, data, "glowing", wanted ? NpcCodec.colorName(data.glowColor()) : "off");
    }

    private Command scale(ArgumentWord npc) {
        Command command = new Command("scale", "size");
        command.setDefaultExecutor((sender, context) -> support.usage(sender));
        var factor = ArgumentType.Double("factor").min(NpcData.MIN_SCALE).max(NpcData.MAX_SCALE);
        command.addSyntax((sender, context) -> {
            NpcData data = support.found(sender, context.get(npc));
            if (data != null) {
                data.scale(context.get(factor));
                support.changed(sender, data, "scale", data.scale() + " (1.20.5+ clients; older ones see 1)");
            }
        }, npc, factor);
        return command;
    }

    /**
     * Scales the NPC up per-viewer when a player looks at it:
     * {@code /npc scale_up_when_looking <npc> [true|false|toggle|disabled|<factor>] [distance]}.
     */
    private Command scaleUpWhenLooking(ArgumentWord npc) {
        Command command = new Command("scale_up_when_looking", "scaleupwhenlooking", "look_scale", "lookscale");
        command.setDefaultExecutor((sender, context) -> support.usage(sender));
        ArgumentWord value = ArgumentType.Word("value");
        value.setSuggestionCallback((sender, context, suggestion) -> {
            for (String option : new String[]{"true", "false", "toggle", "disabled", "1.1", "1.2", "1.3", "1.5"}) {
                suggestion.addEntry(new SuggestionEntry(option));
            }
        });
        var distance = ArgumentType.Double("distance").min(1.0).max(NpcData.MAX_LOOK_SCALE_DISTANCE);
        command.addSyntax((sender, context) -> scaleUpWhenLooking(sender, context.get(npc), null, null), npc);
        command.addSyntax((sender, context) -> scaleUpWhenLooking(sender, context.get(npc), context.get(value), null),
                npc, value);
        command.addSyntax((sender, context) -> scaleUpWhenLooking(sender, context.get(npc), context.get(value),
                context.get(distance)), npc, value, distance);
        return command;
    }

    private void scaleUpWhenLooking(CommandSender sender, String npcName, String value, Double distance) {
        NpcData data = support.found(sender, npcName);
        if (data == null) {
            return;
        }
        Boolean wanted = NpcCommandSupport.state(value, data.scaleUpWhenLooking());
        if (wanted != null) {
            data.scaleUpWhenLooking(wanted);
            if (distance != null) {
                data.lookScaleDistance(distance);
            }
            support.changed(sender, data, "scale_up_when_looking", describeLookScale(data));
            return;
        }
        double factor;
        try {
            factor = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            support.invalid(sender, "scale_up_when_looking", "not true, false, toggle or a number",
                    "true, false, toggle, disabled, or a scale multiplier (" + NpcData.MIN_SCALE + " to "
                            + (int) NpcData.MAX_SCALE + ")");
            return;
        }
        if (!Double.isFinite(factor) || factor < NpcData.MIN_SCALE || factor > NpcData.MAX_SCALE) {
            support.invalid(sender, "scale_up_when_looking", "out of range",
                    "a scale multiplier between " + NpcData.MIN_SCALE + " and " + (int) NpcData.MAX_SCALE);
            return;
        }
        data.lookScaleMultiplier(factor);
        if (distance != null) {
            data.lookScaleDistance(distance);
        }
        data.scaleUpWhenLooking(true);
        support.changed(sender, data, "scale_up_when_looking", describeLookScale(data));
    }

    private static String describeLookScale(NpcData data) {
        if (!data.scaleUpWhenLooking()) {
            return "off";
        }
        return data.lookScaleMultiplier() + "x within " + (int) data.lookScaleDistance()
                + " blocks (1.20.5+ clients)";
    }

    private Command showInTab(ArgumentWord npc) {
        Command command = new Command("show_in_tab", "showintab");
        command.setDefaultExecutor((sender, context) -> support.usage(sender));
        ArgumentWord state = ArgumentType.Word("state").from("true", "false", "toggle");
        command.addSyntax((sender, context) -> showInTab(sender, context.get(npc), null), npc);
        command.addSyntax((sender, context) -> showInTab(sender, context.get(npc), context.get(state)), npc, state);
        return command;
    }

    private void showInTab(CommandSender sender, String npcName, String value) {
        NpcData data = support.found(sender, npcName);
        if (data == null) {
            return;
        }
        if (!data.isPlayer()) {
            support.invalid(sender, "show_in_tab", "only player NPCs have a tab list entry", "a player NPC");
            return;
        }
        Boolean wanted = NpcCommandSupport.state(value, data.showInTab());
        if (wanted == null) {
            support.invalid(sender, "show_in_tab", "not true, false or toggle", "true, false or toggle");
            return;
        }
        data.showInTab(wanted);
        support.changed(sender, data, "show_in_tab", wanted);
    }

    private Command pose(ArgumentWord npc) {
        Command command = new Command("pose");
        command.setDefaultExecutor((sender, context) -> support.usage(sender));
        ArgumentWord pose = poseArgument();
        command.addSyntax((sender, context) -> setPose(sender, context.get(npc), context.get(pose)), npc, pose);
        return command;
    }

    /** FancyNpcs' {@code attribute <npc> set <attribute> <value>}; pose is the attribute this lobby has. */
    private Command attribute(ArgumentWord npc) {
        Command command = new Command("attribute");
        command.setDefaultExecutor((sender, context) -> support.usage(sender));
        ArgumentWord set = ArgumentType.Word("set").from("set");
        ArgumentWord name = ArgumentType.Word("attribute-name").from("pose");
        ArgumentWord pose = poseArgument();
        command.addSyntax((sender, context) -> setPose(sender, context.get(npc), context.get(pose)),
                npc, set, name, pose);
        return command;
    }

    private static ArgumentWord poseArgument() {
        ArgumentWord pose = ArgumentType.Word("pose-name");
        pose.setSuggestionCallback((sender, context, suggestion) -> {
            for (NpcPose value : NpcPose.values()) {
                suggestion.addEntry(new SuggestionEntry(value.configName()));
            }
        });
        return pose;
    }

    private void setPose(CommandSender sender, String npcName, String value) {
        NpcData data = support.found(sender, npcName);
        if (data == null) {
            return;
        }
        NpcPose pose = NpcPose.fromName(value);
        String poses = Arrays.stream(NpcPose.values()).map(NpcPose::configName).collect(Collectors.joining(", "));
        if (pose == null) {
            support.invalid(sender, "pose", "unknown pose '" + value + "'", poses);
            return;
        }
        if (!data.pose(pose)) {
            support.invalid(sender, "pose", "only player NPCs have poses", "a player NPC");
            return;
        }
        support.changed(sender, data, "pose", pose.configName());
    }

    private static String colorNames() {
        return Arrays.stream(TeamColor.values()).map(NpcCodec::colorName).collect(Collectors.joining(", "));
    }
}
