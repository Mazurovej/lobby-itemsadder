package io.github.mohammadhadimohammadi2007_dot.lobby.server.world;

import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.utils.block.BlockUtils;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
class WorldBlockHandlersTest {

    @Test
    void modernPlayerHeadProfileIsPreservedInClientNbt(Env env) {
        WorldBlockHandlers.registerAll();
        BlockHandler skullHandler = MinecraftServer.getBlockManager().getHandler("minecraft:skull");
        assertNotNull(skullHandler, "minecraft:skull handler must be registered");

        CompoundBinaryTag textureProp = CompoundBinaryTag.builder()
                .putString("name", "textures")
                .putString("value", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly9leGFtcGxlLmNvbSJ9fX0=")
                .build();
        CompoundBinaryTag profile = CompoundBinaryTag.builder()
                .putString("name", "Notch")
                .put("properties", ListBinaryTag.builder(BinaryTagTypes.COMPOUND).add(textureProp).build())
                .build();
        CompoundBinaryTag nbt = CompoundBinaryTag.builder()
                .put("profile", profile)
                .build();

        Block head = Block.PLAYER_HEAD.withHandler(skullHandler).withNbt(nbt);
        CompoundBinaryTag clientNbt = BlockUtils.extractClientNbt(head);
        assertNotNull(clientNbt);
        CompoundBinaryTag extractedProfile = clientNbt.getCompound("profile");
        assertEquals("Notch", extractedProfile.getString("name"));
        ListBinaryTag props = extractedProfile.getList("properties", BinaryTagTypes.COMPOUND);
        assertEquals(1, props.size());
        assertEquals("textures", props.getCompound(0).getString("name"));
        assertTrue(props.getCompound(0).getString("value").startsWith("eyJ0ZXh0dXJlcy"));
    }

    @Test
    void legacySkullOwnerNbtIsUpgradedToModernProfile(Env env) {
        WorldBlockHandlers.registerAll();
        UUID ownerId = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

        CompoundBinaryTag legacyTexture = CompoundBinaryTag.builder()
                .putString("Value", "dGV4dHVyZQ==")
                .putString("Signature", "c2lnbmF0dXJl")
                .build();
        CompoundBinaryTag legacyProps = CompoundBinaryTag.builder()
                .put("textures", ListBinaryTag.builder(BinaryTagTypes.COMPOUND).add(legacyTexture).build())
                .build();
        CompoundBinaryTag legacyOwner = CompoundBinaryTag.builder()
                .putString("Name", "Notch")
                .putString("Id", ownerId.toString())
                .put("Properties", legacyProps)
                .build();
        CompoundBinaryTag legacyNbt = CompoundBinaryTag.builder()
                .put("SkullOwner", legacyOwner)
                .build();

        // Simulate a block loaded with a Dummy handler (as happened before WorldBlockHandlers existed).
        Block rawHead = Block.PLAYER_WALL_HEAD
                .withHandler(BlockHandler.Dummy.get("minecraft:skull"))
                .withNbt(legacyNbt);

        Block normalized = WorldBlockHandlers.normalizeSkull(rawHead);
        CompoundBinaryTag clientNbt = BlockUtils.extractClientNbt(normalized);
        assertNotNull(clientNbt);
        CompoundBinaryTag profile = clientNbt.getCompound("profile");
        assertEquals("Notch", profile.getString("name"));
        IntArrayBinaryTag id = (IntArrayBinaryTag) profile.get("id");
        assertNotNull(id);
        assertEquals(4, id.value().length);
        ListBinaryTag props = profile.getList("properties", BinaryTagTypes.COMPOUND);
        assertEquals(1, props.size());
        assertEquals("textures", props.getCompound(0).getString("name"));
        assertEquals("dGV4dHVyZQ==", props.getCompound(0).getString("value"));
        assertEquals("c2lnbmF0dXJl", props.getCompound(0).getString("signature"));
    }
}
