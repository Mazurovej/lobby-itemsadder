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
    void cryptoRoundTripWorksForBothV18AndV21() throws Exception {
        byte[] sample = "hello simple voice chat opus frame".getBytes(StandardCharsets.UTF_8);

        VoiceChatCrypto v21 = VoiceChatCrypto.generate(VoiceChatCrypto.PROTOCOL_V21);
        assertTrue(v21.isGcm());
        byte[] enc21 = v21.encrypt(sample);
        assertArrayEquals(sample, v21.decrypt(enc21));

        // Tampering with GCM ciphertext must fail authentication.
        enc21[enc21.length - 1] ^= 0x01;
        assertThrows(GeneralSecurityException.class, () -> v21.decrypt(enc21));

        VoiceChatCrypto v18 = VoiceChatCrypto.generate(VoiceChatCrypto.PROTOCOL_V18);
        assertFalse(v18.isGcm());
        byte[] enc18 = v18.encrypt(sample);
        assertArrayEquals(sample, v18.decrypt(enc18));
    }

    @Test
    void codecEncodesSecretAndUdpEnvelopes() {
        byte[] req = ByteBuffer.allocate(4).putInt(VoiceChatCrypto.PROTOCOL_V21).array();
        assertEquals(VoiceChatCrypto.PROTOCOL_V21, VoiceChatCodec.decodeRequestSecret(req));

        VoiceChatCrypto v21 = VoiceChatCrypto.generate(VoiceChatCrypto.PROTOCOL_V21);
        IntegrationsConfig.VoiceChat cfg = new IntegrationsConfig.VoiceChat(
                true, "127.0.0.1", 24454, "voice.example.com", 48.0, 24.0, "voip", 1024, 1000, true, true);
        byte[] secretV21 = VoiceChatCodec.encodeSecret(v21, UUID.randomUUID(), cfg);
        assertTrue(secretV21.length > 64);

        VoiceChatCrypto v18 = VoiceChatCrypto.generate(VoiceChatCrypto.PROTOCOL_V18);
        byte[] secretV18 = VoiceChatCodec.encodeSecret(v18, UUID.randomUUID(), cfg);
        // v21 includes an extra 16-byte connectionId UUID compared to v18.
        assertEquals(16, secretV21.length - secretV18.length);

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
