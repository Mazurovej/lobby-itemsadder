package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.modelengine;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.LobbyServerTestAccess;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.ConfigManager;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.IntegrationsConfig;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.mythicmobs.MythicMobDefinition;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.mythicmobs.MythicMobsLoader;
import net.minestom.server.color.Color;
import net.minestom.server.command.ConsoleSender;
import net.minestom.server.command.builder.CommandResult;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.Player;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket;
import net.minestom.server.network.packet.server.play.SpawnEntityPacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
class ModelEngineMythicMobsTest {

    private static final String SAMPLE_BBMODEL = """
            {
              "name": "knight",
              "elements": [
                { "uuid": "cube-body", "from": [-6, 0, -4], "to": [6, 24, 4] },
                { "uuid": "cube-head", "from": [-4, 24, -4], "to": [4, 32, 4] }
              ],
              "outliner": [
                {
                  "name": "body",
                  "uuid": "bone-body",
                  "origin": [0, 12, 0],
                  "rotation": [0, 0, 0],
                  "children": [
                    "cube-body",
                    {
                      "name": "h_head",
                      "uuid": "bone-head",
                      "origin": [0, 24, 0],
                      "rotation": [0, 0, 0],
                      "children": ["cube-head"]
                    }
                  ]
                }
              ],
              "animations": [
                {
                  "name": "idle",
                  "loop": "loop",
                  "length": 2.0,
                  "animators": {
                    "bone-body": {
                      "name": "body",
                      "keyframes": [
                        { "channel": "position", "time": 0.0, "interpolation": "linear", "data_points": [{ "x": "0", "y": "0", "z": "0" }] },
                        { "channel": "position", "time": 1.0, "interpolation": "linear", "data_points": [{ "x": "0", "y": "4", "z": "0" }] },
                        { "channel": "position", "time": 2.0, "interpolation": "linear", "data_points": [{ "x": "0", "y": "0", "z": "0" }] }
                      ]
                    }
                  }
                }
              ]
            }
            """;

    private static final String SAMPLE_MYTHIC_MOB = """
            RoyalKnight:
              Type: ZOMBIE
              Display: '<gold><bold>Royal Knight'
              Options:
                Scale: 1.0
              Equipment:
                - DIAMOND_SWORD HAND
              Skills:
                - model{mid=knight;drive=false;scale=1.25} @self ~onSpawn
            """;

    @TempDir
    Path dir;

    @Test
    void loadsBbmodelAndResolvesBonesFromMergedItemsAdderPack(Env env) throws Exception {
        Path megDir = dir.resolve("modelengine");
        Files.createDirectories(megDir.resolve("blueprints"));
        Files.writeString(megDir.resolve("blueprints/knight.bbmodel"), SAMPLE_BBMODEL);

        Path iaPack = dir.resolve("itemsadder/output/generated.zip");
        writeMergedPack(iaPack);

        Map<String, ModelBlueprint> blueprints = ModelEngineLoader.load(megDir, List.of(iaPack), "leather_horse_armor");
        ModelBlueprint knight = blueprints.get("knight");
        assertNotNull(knight, "knight blueprint should be loaded");
        assertEquals(2, knight.visibleBoneCount());
        assertTrue(knight.hasHeadBone());
        assertEquals(2.0, knight.height(), 0.05);
        assertNotNull(knight.animation("idle"));

        // Body mapped via custom_model_data on leather_horse_armor (with white dye!)
        ModelBlueprint.Bone body = knight.bones().get(0);
        assertNotNull(body.displayItem());
        assertEquals(Material.LEATHER_HORSE_ARMOR, body.displayItem().material());
        assertEquals(new Color(0xFFFFFF), body.displayItem().get(DataComponents.DYED_COLOR));
        assertEquals(1001f, body.displayItem().get(DataComponents.CUSTOM_MODEL_DATA).floats().getFirst());

        // Head mapped via 1.21.4+ assets/modelengine/items/knight/head.json (stripping h_ prefix!)
        ModelBlueprint.Bone head = knight.bones().get(1);
        assertTrue(head.head());
        assertNotNull(head.displayItem());
        assertEquals("modelengine:knight/head", head.displayItem().get(DataComponents.ITEM_MODEL));

        // Idle animation raises body Y by +4px (+0.25 blocks) at t=1.0s
        var poseAt0 = knight.samplePose("idle", 0.0, 0f, 0f, 0f, 1.0);
        var poseAt1 = knight.samplePose("idle", 1.0, 0f, 0f, 0f, 1.0);
        assertEquals(poseAt0.get(0).translation().y() + 0.25, poseAt1.get(0).translation().y(), 1e-4);
        // Child head moves up with parent body too!
        assertEquals(poseAt0.get(1).translation().y() + 0.25, poseAt1.get(1).translation().y(), 1e-4);
    }

    @Test
    void loadsMythicMobsYamlDefinitions() throws Exception {
        Path mmDir = dir.resolve("mythicmobs");
        Files.createDirectories(mmDir.resolve("Mobs"));
        Files.writeString(mmDir.resolve("Mobs/knights.yml"), SAMPLE_MYTHIC_MOB);

        Map<String, MythicMobDefinition> mobs = MythicMobsLoader.load(mmDir);
        MythicMobDefinition knight = mobs.get("royalknight");
        assertNotNull(knight);
        assertEquals("RoyalKnight", knight.id());
        assertEquals(EntityType.ZOMBIE, knight.entityType());
        assertEquals("<gold><bold>Royal Knight", knight.displayName());
        assertEquals(1.25, knight.scale(), 1e-6);
        assertEquals("knight", knight.modelId());
        assertEquals(Material.DIAMOND_SWORD, knight.equipment().get(EquipmentSlot.MAIN_HAND).material());
    }

    @Test
    void spawnsMythicModelEngineNpcAndSendsItemDisplayBones(Env env) throws Exception {
        // Prepare ModelEngine blueprint + merged ItemsAdder pack + MythicMobs mob inside server dir
        Files.createDirectories(dir.resolve("modelengine/blueprints"));
        Files.writeString(dir.resolve("modelengine/blueprints/knight.bbmodel"), SAMPLE_BBMODEL);
        writeMergedPack(dir.resolve("itemsadder/output/generated.zip"));

        Files.createDirectories(dir.resolve("mythicmobs/Mobs"));
        Files.writeString(dir.resolve("mythicmobs/Mobs/knights.yml"), SAMPLE_MYTHIC_MOB);

        ConfigManager config = new ConfigManager(dir);
        config.load();
        String integrations = Files.readString(dir.resolve(ConfigManager.INTEGRATIONS_FILE))
                .replace("modelengine:\n  enabled: false", "modelengine:\n  enabled: true")
                .replace("mythicmobs:\n  enabled: false", "mythicmobs:\n  enabled: true");
        Files.writeString(dir.resolve(ConfigManager.INTEGRATIONS_FILE), integrations);
        config.reload();

        IntegrationsConfig loaded = config.current().integrations();
        assertTrue(loaded.modelEngine().enabled());
        assertTrue(loaded.mythicMobs().enabled());

        var lobby = LobbyServerTestAccess.start(config);
        try {
            var commands = env.process().command();
            var console = new ConsoleSender();

            assertEquals(CommandResult.Type.SUCCESS, commands.execute(console, "npc models").getType());
            assertEquals(CommandResult.Type.SUCCESS, commands.execute(console, "npc mythics").getType());
            assertEquals(CommandResult.Type.SUCCESS,
                    commands.execute(console, "npc mythic guard RoyalKnight 0 65 0").getType());
            assertEquals(CommandResult.Type.SUCCESS,
                    commands.execute(console, "npc turn_to_player guard true").getType());
            assertEquals(CommandResult.Type.SUCCESS,
                    commands.execute(console, "npc animation guard idle").getType());

            // Connect a modern viewer near the NPC and verify INTERACTION + 2 ITEM_DISPLAY bones spawn
            var conn = env.createConnection(new GameProfile(UUID.randomUUID(), "Viewer"));
            var spawns = conn.trackIncoming(SpawnEntityPacket.class);
            var metas = conn.trackIncoming(EntityMetaDataPacket.class);
            Player viewer = conn.connect(lobby.world().instance(), new Pos(0, 65, 3));
            assertNotNull(viewer);

            lobby.renderer().refreshNow();

            List<SpawnEntityPacket> spawned = spawns.collect();
            assertTrue(spawned.stream().anyMatch(p -> p.type() == EntityType.INTERACTION),
                    "modeled NPC should spawn an Interaction hitbox");
            long itemDisplays = spawned.stream().filter(p -> p.type() == EntityType.ITEM_DISPLAY).count();
            assertEquals(2L, itemDisplays, "knight blueprint should spawn 2 ItemDisplay bones (body + h_head)");
            assertFalse(metas.collect().isEmpty(), "bones should receive metadata packets");
        } finally {
            LobbyServerTestAccess.stop(lobby);
        }
    }

    private static void writeMergedPack(Path zipPath) throws Exception {
        Files.createDirectories(zipPath.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath), StandardCharsets.UTF_8)) {
            // Classic custom_model_data override for knight/body
            zos.putNextEntry(new ZipEntry("assets/minecraft/models/item/leather_horse_armor.json"));
            zos.write("""
                    {
                      "overrides": [
                        { "predicate": { "custom_model_data": 1001 }, "model": "modelengine:entity/knight/body" }
                      ]
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // 1.21.4+ modern item_model definition for knight/head
            zos.putNextEntry(new ZipEntry("assets/modelengine/items/knight/head.json"));
            zos.write("""
                    {
                      "model": {
                        "type": "minecraft:model",
                        "model": "modelengine:entity/knight/head"
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
    }
}
