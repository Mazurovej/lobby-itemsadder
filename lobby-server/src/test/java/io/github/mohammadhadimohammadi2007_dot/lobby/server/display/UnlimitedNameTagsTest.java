package io.github.mohammadhadimohammadi2007_dot.lobby.server.display;

import io.github.mohammadhadimohammadi2007_dot.lobby.common.ProtocolVersions;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.chat.ChatConfig;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.chat.ChatMessage;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.chat.render.ChatRenderer;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.chat.render.VariantKey;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.ConfigManager;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.ConfigSnapshot;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder.LobbyText;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder.Placeholder;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder.PlaceholderRegistry;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.placeholder.PlaceholderService;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.player.PermissionService;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.player.PlayerMeta;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.team.TeamManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.network.packet.server.play.SetPassengersPacket;
import net.minestom.server.network.packet.server.play.SpawnEntityPacket;
import net.minestom.server.network.packet.server.play.TeamsPacket;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import net.minestom.testing.TestConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
class UnlimitedNameTagsTest {

    @Test
    void chatNameInheritsLastHexColorFromLuckPermsPrefix(Env env, @TempDir Path dir) throws Exception {
        ConfigManager manager = new ConfigManager(dir);
        ConfigSnapshot snapshot = manager.load();
        PlaceholderRegistry registry = new PlaceholderRegistry();
        String lpPrefix = "<white>[DEV] <#74b9ff>";
        registry.register("luckperms", name -> name.equals("prefix")
                ? new Placeholder.Constant(lpPrefix, true)
                : new Placeholder.Constant("", false));
        LobbyText text = new LobbyText(manager, new PlaceholderService(registry));
        ChatRenderer renderer = new ChatRenderer(text, () -> snapshot.chat(), () -> snapshot.messages());

        Instance instance = env.createFlatInstance();
        Player sender = env.createPlayer(instance, new Pos(0, 42, 0));
        ChatConfig.Format staffFormat = snapshot.chat().formats().stream()
                .filter(f -> f.name().equals("staff"))
                .findFirst().orElseThrow();
        PlayerMeta meta = new PlayerMeta(lpPrefix, "", "developer", Map.of(), 100);
        ChatMessage message = new ChatMessage("1", sender, "asd");
        message.senderMeta(meta);
        message.format(staffFormat);

        Component rendered = renderer.prepare(message, false, false).variant(new VariantKey(false, null, false));
        TextColor nameColor = findTextColor(rendered, sender.getUsername());
        assertEquals(TextColor.fromHexString("#74b9ff"), nameColor,
                "chat name should inherit the trailing hex color from the LuckPerms prefix");
    }

    @Test
    void unlimitedNametagRendersHexColorAndSpawnsDisplayPassengerForModernViewers(Env env, @TempDir Path dir)
            throws Exception {
        ConfigManager manager = new ConfigManager(dir);
        manager.load();
        String displayYml = Files.readString(dir.resolve(ConfigManager.DISPLAY_FILE));
        assertTrue(displayYml.contains("mode: unlimited"));

        PlaceholderRegistry registry = new PlaceholderRegistry();
        String lpPrefix = "<white>[DEV] <#74b9ff>";
        registry.register("luckperms", name -> name.equals("prefix")
                ? new Placeholder.Constant(lpPrefix, true)
                : new Placeholder.Constant("", false));
        LobbyText text = new LobbyText(manager, new PlaceholderService(registry));
        PermissionService perms = new PermissionService() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public boolean hasPermission(Player player, String permission) {
                return false;
            }

            @Override
            public PlayerMeta meta(Player player) {
                return new PlayerMeta(lpPrefix, "", "developer", Map.of(), 100);
            }
        };

        Instance instance = env.createFlatInstance();
        TestConnection targetConn = env.createConnection();
        Player target = targetConn.connect(instance, new Pos(0, 42, 0));
        TestConnection modernConn = env.createConnection();
        Player modernViewer = modernConn.connect(instance, new Pos(2, 42, 0));
        TestConnection legacyConn = env.createConnection();
        Player legacyViewer = legacyConn.connect(instance, new Pos(-2, 42, 0));
        env.tick();

        Map<UUID, Integer> protocols = Map.of(
                target.getUuid(), ProtocolVersions.V1_20_5,
                modernViewer.getUuid(), ProtocolVersions.V1_20_5,
                legacyViewer.getUuid(), ProtocolVersions.V1_8);

        TeamManager teamManager = new TeamManager(p -> protocols.getOrDefault(p.getUuid(), ProtocolVersions.V1_20_5), false);
        PlayerTeams playerTeams = new PlayerTeams(teamManager, perms);
        UnlimitedNameTags unlimited = new UnlimitedNameTags(text, perms,
                p -> protocols.getOrDefault(p.getUuid(), ProtocolVersions.V1_20_5));
        TextCache cache = new TextCache(text, new ClientTiers(p -> protocols.getOrDefault(p.getUuid(), ProtocolVersions.V1_20_5)));

        TabConfig tabConfig = manager.current().display().tab();
        Component tagComponent = unlimited.renderNameTag(tabConfig.nametags(), target, cache);
        assertEquals(TextColor.fromHexString("#74b9ff"), findTextColor(tagComponent, target.getUsername()),
                "UnlimitedNameTags should render the player's name in exact RGB hex color from LuckPerms prefix");

        var modernSpawnsCollector = modernConn.trackIncoming(SpawnEntityPacket.class);
        var modernPassengersCollector = modernConn.trackIncoming(SetPassengersPacket.class);
        var modernTeamsCollector = modernConn.trackIncoming(TeamsPacket.class);
        var legacySpawnsCollector = legacyConn.trackIncoming(SpawnEntityPacket.class);
        var legacyTeamsCollector = legacyConn.trackIncoming(TeamsPacket.class);

        List<Player> online = List.of(target, modernViewer, legacyViewer);
        playerTeams.refresh(tabConfig, online, cache);
        unlimited.refresh(tabConfig.nametags(), online, cache);

        // Modern viewer gets TEXT_DISPLAY spawn + SetPassengersPacket + hidden vanilla team nametag.
        List<SpawnEntityPacket> modernSpawns = modernSpawnsCollector.collect().stream()
                .filter(p -> p.type() == EntityType.TEXT_DISPLAY).toList();
        assertTrue(modernSpawns.size() >= 1, "modern viewer should receive TextDisplay spawn(s)");
        List<SetPassengersPacket> modernPassengers = modernPassengersCollector.collect().stream()
                .filter(p -> p.vehicleEntityId() == target.getEntityId()).toList();
        assertEquals(1, modernPassengers.size(), "modern viewer should receive SetPassengersPacket mounting tag on target");

        List<TeamsPacket> modernTeamCreates = modernTeamsCollector.collect().stream()
                .filter(p -> p.action() instanceof TeamsPacket.CreateTeamAction).toList();
        assertTrue(modernTeamCreates.stream().anyMatch(p ->
                        ((TeamsPacket.CreateTeamAction) p.action()).settings().nameTagVisibility()
                                == TeamsPacket.NameTagVisibility.NEVER),
                "modern viewer should have vanilla nametag hidden via team NameTagVisibility.NEVER");

        // Legacy viewer (< 1.19.4) gets NO TextDisplay and keeps vanilla NameTagVisibility.ALWAYS.
        assertEquals(0, legacySpawnsCollector.collect().stream()
                        .filter(p -> p.type() == EntityType.TEXT_DISPLAY).count(),
                "legacy viewer should not receive TextDisplay nametags");
        List<TeamsPacket> legacyTeamCreates = legacyTeamsCollector.collect().stream()
                .filter(p -> p.action() instanceof TeamsPacket.CreateTeamAction).toList();
        assertTrue(legacyTeamCreates.stream().anyMatch(p ->
                        ((TeamsPacket.CreateTeamAction) p.action()).settings().nameTagVisibility()
                                == TeamsPacket.NameTagVisibility.ALWAYS),
                "legacy viewer should keep vanilla NameTagVisibility.ALWAYS");
    }

    private static TextColor findTextColor(Component component, String needle) {
        if (component instanceof TextComponent tc && tc.content().contains(needle) && tc.color() != null) {
            return tc.color();
        }
        for (Component child : component.children()) {
            TextColor found = findTextColor(child, needle);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
