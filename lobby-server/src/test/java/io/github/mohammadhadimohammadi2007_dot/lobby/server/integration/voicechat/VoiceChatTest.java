package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.voicechat;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.ConfigManager;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.ConfigSnapshot;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.IntegrationsConfig;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.litebans.MuteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceChatTest {

    @Test
    void cryptoRoundTripWorksForAllSupportedProtocols() throws Exception {
        byte[] sample = "hello simple voice chat opus frame".getBytes(StandardCharsets.UTF_8);

        assertFalse(VoiceChatCrypto.isSupportedProtocol(17));
        assertTrue(VoiceChatCrypto.isSupportedProtocol(VoiceChatCrypto.PROTOCOL_V18));
        assertTrue(VoiceChatCrypto.isSupportedProtocol(VoiceChatCrypto.PROTOCOL_V19));
        assertTrue(VoiceChatCrypto.isSupportedProtocol(VoiceChatCrypto.PROTOCOL_V20));
        assertTrue(VoiceChatCrypto.isSupportedProtocol(VoiceChatCrypto.PROTOCOL_V21));
        assertFalse(VoiceChatCrypto.isSupportedProtocol(22));

        // v19, v20 (SVC 2.6.x) and v21 (SVC 2.7.x) all use AES-GCM; only v21 uses connectionId.
        for (int version : new int[]{VoiceChatCrypto.PROTOCOL_V19, VoiceChatCrypto.PROTOCOL_V20, VoiceChatCrypto.PROTOCOL_V21}) {
            VoiceChatCrypto crypto = VoiceChatCrypto.generate(version);
            assertTrue(crypto.isGcm(), "v" + version + " must use AES-GCM");
            assertEquals(version >= VoiceChatCrypto.PROTOCOL_V21, crypto.usesConnectionId());
            byte[] encrypted = crypto.encrypt(sample);
            assertArrayEquals(sample, crypto.decrypt(encrypted));

            // Tampering with GCM ciphertext must fail authentication.
            encrypted[encrypted.length - 1] ^= 0x01;
            assertThrows(GeneralSecurityException.class, () -> crypto.decrypt(encrypted));
        }

        VoiceChatCrypto v18 = VoiceChatCrypto.generate(VoiceChatCrypto.PROTOCOL_V18);
        assertFalse(v18.isGcm());
        assertFalse(v18.usesConnectionId());
        byte[] enc18 = v18.encrypt(sample);
        assertArrayEquals(sample, v18.decrypt(enc18));
    }

    @Test
    void codecEncodesSecretAndUdpEnvelopes() {
        byte[] req = ByteBuffer.allocate(4).putInt(VoiceChatCrypto.PROTOCOL_V20).array();
        assertEquals(VoiceChatCrypto.PROTOCOL_V20, VoiceChatCodec.decodeRequestSecret(req));

        IntegrationsConfig.VoiceChat cfg = new IntegrationsConfig.VoiceChat(
                true, "127.0.0.1", 24454, "voice.example.com", 48.0, 24.0, "voip", 1024, 1000, true, true);
        UUID playerUuid = UUID.randomUUID();

        byte[] secretV21 = VoiceChatCodec.encodeSecret(VoiceChatCrypto.generate(VoiceChatCrypto.PROTOCOL_V21), playerUuid, cfg);
        byte[] secretV20 = VoiceChatCodec.encodeSecret(VoiceChatCrypto.generate(VoiceChatCrypto.PROTOCOL_V20), playerUuid, cfg);
        byte[] secretV19 = VoiceChatCodec.encodeSecret(VoiceChatCrypto.generate(VoiceChatCrypto.PROTOCOL_V19), playerUuid, cfg);
        byte[] secretV18 = VoiceChatCodec.encodeSecret(VoiceChatCrypto.generate(VoiceChatCrypto.PROTOCOL_V18), playerUuid, cfg);

        assertTrue(secretV21.length > 64);
        // v18, v19, and v20 all omit connectionId (16 bytes shorter than v21).
        assertEquals(secretV18.length, secretV19.length);
        assertEquals(secretV18.length, secretV20.length);
        assertEquals(16, secretV21.length - secretV20.length);

        byte[] payload = new byte[]{1, 2, 3, 4};
        byte[] framed = VoiceChatCodec.encodeServerUdp(payload);
        assertEquals(VoiceChatCodec.UDP_MAGIC, framed[0]);
        assertEquals(4, framed[1]);
    }

    @Test
    void configDefaultsAndUdpServerLifecycle(@TempDir Path dir) throws Exception {
        ConfigManager manager = new ConfigManager(dir);
        ConfigSnapshot snapshot = manager.load();
        IntegrationsConfig.VoiceChat vc = snapshot.integrations().voiceChat();
        assertFalse(vc.enabled());
        assertEquals(24454, vc.port());
        assertEquals(48.0, vc.maxDistance());
        assertEquals(0, vc.codecOrdinal());

        // Bind to an ephemeral port (0) to verify socket lifecycle cleanly.
        IntegrationsConfig.VoiceChat testCfg = new IntegrationsConfig.VoiceChat(
                true, "127.0.0.1", 0, "", 48.0, 24.0, "voip", 1024, 1000, true, true);
        VoiceChatServer server = VoiceChatServer.start(testCfg, MuteService.NONE);
        try {
            assertTrue(server.localPort() > 0);
            assertNotNull(server);
        } finally {
            server.shutdown();
        }
    }
}
