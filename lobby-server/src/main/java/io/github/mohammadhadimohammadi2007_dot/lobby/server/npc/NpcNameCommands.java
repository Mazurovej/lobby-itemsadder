package io.github.mohammadhadimohammadi2007_dot.lobby.server.npc;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.MessageKey;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.Messages;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.hologram.HologramData;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.hologram.HologramProperties;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentStringArray;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.command.builder.arguments.number.ArgumentNumber;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The name above an NPC. {@code /npc displayname <npc> <text>} sets one line, as in FancyNpcs
 * ({@code @none} hides the name). {@code /npc name <npc> ...} edits it like any hologram: several lines
 * and the look.
 *
 * <pre>
 * /npc name &lt;npc&gt; addline &lt;text&gt;
 * /npc name &lt;npc&gt; setline|insertline|insertafter &lt;number&gt; &lt;text&gt;
 * /npc name &lt;npc&gt; removeline &lt;number&gt;
 * /npc name &lt;npc&gt; set &lt;property&gt; &lt;value&gt;
 * </pre>
 */
final class NpcNameCommands {

    /**
     * The hologram properties a name tag takes. Not its type, item, block, permission or visibility:
     * those belong to the NPC as a whole.
     */
    private static final Set<String> NAME_PROPERTIES = Set.of("scale", "size", "offset", "y-offset", "yoffset",
            "name-offset", "head-offset", "billboard", "alignment", "background",
            "text-shadow", "see-through", "view-distance", "update-interval", "line-spacing", "brightness",
            "shadow-radius", "shadow-strength",
            // FancyHolograms' spellings, which HologramProperties also accepts.
            "textshadow", "seethrough", "textalignment", "visibilitydistance", "updatetextinterval",
            "shadowradius", "shadowstrength");
    private static final Set<String> OFFSET_ALIASES = Set.of("offset", "y-offset", "yoffset", "name-offset", "head-offset");
    private static final String NONE = "@none";

    /** What a line command does with the line number. */
    private enum Edit {
        REPLACE,
        BEFORE,
        AFTER
    }

    private final NpcCommandSupport support;

    NpcNameCommands(NpcCommandSupport support) {
        this.support = support;
    }

    /** Adds {@code displayname} and {@code name} to {@code /npc}. */
    void addTo(Command npcCommand, ArgumentWord npc) {
        npcCommand.addSubcommand(displayName(npc));
        npcCommand.addSubcommand(name(npc));
    }

    private Command displayName(ArgumentWord npc) {
        Command command = new Command("displayname", "display_name");
        command.setDefaultExecutor((sender, context) -> support.usage(sender));
        ArgumentStringArray text = ArgumentType.StringArray("name");
        command.addSyntax((sender, context) -> {
            NpcData data = support.found(sender, context.get(npc));
            if (data == null) {
                return;
            }
            String written = String.join(" ", context.get(text));
            if (written.equalsIgnoreCase(NONE)) {
                data.nameTag().frames(List.of(List.of()));
                support.changed(sender, data, "displayname", "hidden");
                return;
            }
            List<String> lines = List.of(written.split("\\\\n", -1));
            if (lines.size() > HologramData.MAX_LINES) {
                support.invalid(sender, "displayname", "too many lines", "at most " + HologramData.MAX_LINES);
                return;
            }
            data.nameTag().frames(List.of(lines));
            support.changed(sender, data, "displayname", written);
        }, npc, text);
        return command;
    }

    private Command name(ArgumentWord npc) {
        Command command = new Command("name");
        command.setDefaultExecutor((sender, context) -> support.usage(sender));
        ArgumentStringArray text = ArgumentType.StringArray("text");
        ArgumentNumber<Integer> line = ArgumentType.Integer("line").min(1).max(HologramData.MAX_LINES);

        command.addSyntax((sender, context) -> addLine(sender, context.get(npc), String.join(" ", context.get(text))),
                npc, ArgumentType.Literal("addline"), text);
        command.addSyntax((sender, context) -> editLine(sender, context.get(npc), context.get(line),
                String.join(" ", context.get(text)), Edit.REPLACE), npc, ArgumentType.Literal("setline"), line, text);
        for (String literal : List.of("insertline", "insertbefore")) {
            command.addSyntax((sender, context) -> editLine(sender, context.get(npc), context.get(line),
                    String.join(" ", context.get(text)), Edit.BEFORE), npc, ArgumentType.Literal(literal), line, text);
        }
        command.addSyntax((sender, context) -> editLine(sender, context.get(npc), context.get(line),
                String.join(" ", context.get(text)), Edit.AFTER), npc, ArgumentType.Literal("insertafter"), line, text);
        command.addSyntax((sender, context) -> removeLine(sender, context.get(npc), context.get(line)),
                npc, ArgumentType.Literal("removeline"), line);
        command.addSyntax((sender, context) -> listLines(sender, context.get(npc)),
                npc, ArgumentType.Literal("list"));

        ArgumentWord property = ArgumentType.Word("property").from(NAME_PROPERTIES.stream().sorted()
                .toArray(String[]::new));
        ArgumentStringArray value = ArgumentType.StringArray("value");
        command.addSyntax((sender, context) -> set(sender, context.get(npc), context.get(property),
                String.join(" ", context.get(value))), npc, ArgumentType.Literal("set"), property, value);
        return command;
    }

    private void addLine(CommandSender sender, String npcName, String text) {
        NpcData npc = support.found(sender, npcName);
        if (npc == null) {
            return;
        }
        List<String> lines = new ArrayList<>(npc.nameTag().lines());
        if (lines.size() >= HologramData.MAX_LINES) {
            support.invalid(sender, "name", "too many lines", "at most " + HologramData.MAX_LINES);
            return;
        }
        lines.add(text);
        npc.nameTag().lines(lines);
        lineChanged(sender, npc, lines.size());
    }

    private void editLine(CommandSender sender, String npcName, int number, String text, Edit edit) {
        NpcData npc = support.found(sender, npcName);
        if (npc == null) {
            return;
        }
        List<String> lines = new ArrayList<>(npc.nameTag().lines());
        int limit = edit == Edit.REPLACE ? lines.size() : lines.size() + 1;
        if (number > limit) {
            noSuchLine(sender, npc, number, lines.size());
            return;
        }
        switch (edit) {
            case REPLACE -> lines.set(number - 1, text);
            case BEFORE -> lines.add(number - 1, text);
            case AFTER -> lines.add(Math.min(number, lines.size()), text);
        }
        npc.nameTag().lines(lines);
        lineChanged(sender, npc, number);
    }

    private void removeLine(CommandSender sender, String npcName, int number) {
        NpcData npc = support.found(sender, npcName);
        if (npc == null) {
            return;
        }
        List<String> lines = new ArrayList<>(npc.nameTag().lines());
        if (number > lines.size()) {
            noSuchLine(sender, npc, number, lines.size());
            return;
        }
        lines.remove(number - 1);
        npc.nameTag().lines(lines);
        lineChanged(sender, npc, number);
    }

    private void listLines(CommandSender sender, String npcName) {
        NpcData npc = support.found(sender, npcName);
        if (npc == null) {
            return;
        }
        List<String> lines = npc.nameTag().lines();
        StringBuilder sb = new StringBuilder();
        sb.append("<gray>Name of <white>").append(npc.name()).append("</white> (scale: <white>")
                .append(npc.nameTag().scale()).append("</white>, head offset: <white>")
                .append(npc.nameOffset()).append("</white>):");
        if (lines.isEmpty()) {
            sb.append(" <gray>(hidden)");
        } else {
            for (int i = 0; i < lines.size(); i++) {
                sb.append("\n<gray>").append(i + 1).append(". <white>").append(lines.get(i));
            }
        }
        sender.sendMessage(support.text.render(sb.toString(), null));
    }

    private void set(CommandSender sender, String npcName, String property, String value) {
        NpcData npc = support.found(sender, npcName);
        if (npc == null) {
            return;
        }
        String key = property.toLowerCase(Locale.ROOT);
        if (!NAME_PROPERTIES.contains(key)) {
            sender.sendMessage(support.text.message(MessageKey.NPC_NAME_PROPERTIES, sender,
                    Messages.text("properties", String.join(", ", NAME_PROPERTIES.stream().sorted().toList()))));
            return;
        }
        if (OFFSET_ALIASES.contains(key)) {
            double offset;
            try {
                offset = Double.parseDouble(value.strip());
            } catch (NumberFormatException e) {
                support.invalid(sender, "name offset", "not a number",
                        "a number between " + NpcData.MIN_NAME_OFFSET + " and " + NpcData.MAX_NAME_OFFSET);
                return;
            }
            if (!Double.isFinite(offset)) {
                support.invalid(sender, "name offset", "not a finite number",
                        "a number between " + NpcData.MIN_NAME_OFFSET + " and " + NpcData.MAX_NAME_OFFSET);
                return;
            }
            npc.nameOffset(offset);
            support.changed(sender, npc, "name offset", npc.nameOffset() + " blocks");
            return;
        }
        String resolvedProperty = key.equals("size") ? "scale" : key;
        HologramProperties.Result result = HologramProperties.apply(npc.nameTag(), resolvedProperty, value);
        if (result == null || result.failed()) {
            support.invalid(sender, "name " + resolvedProperty, result == null ? "unknown property" : result.error(),
                    HologramProperties.allowed(resolvedProperty));
            return;
        }
        support.changed(sender, npc, "name " + resolvedProperty, result.shown());
    }

    private void lineChanged(CommandSender sender, NpcData npc, int line) {
        support.npcs.changed(npc);
        sender.sendMessage(support.text.message(MessageKey.NPC_NAME_LINE, sender,
                Messages.text("name", npc.name()), Messages.text("line", line)));
    }

    private void noSuchLine(CommandSender sender, NpcData npc, int number, int lines) {
        sender.sendMessage(support.text.message(MessageKey.NPC_NAME_NO_SUCH_LINE, sender,
                Messages.text("name", npc.name()), Messages.text("line", number), Messages.text("lines", lines)));
    }
}
