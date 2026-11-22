package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.voicechat;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

/**
 * Per-connection Simple Voice Chat encryption supporting both:
 * <ul>
 *   <li>Protocol v21 (Simple Voice Chat 2.6.x): {@code AES/GCM/NoPadding} with 12-byte IV and 128-bit tag</li>
 *   <li>Protocol v18 (Simple Voice Chat 2.5.x): {@code AES/CBC/PKCS5Padding} with 16-byte IV</li>
 * </ul>
 */
public final class VoiceChatCrypto {

    public static final int PROTOCOL_V18 = 18;
    public static final int PROTOCOL_V21 = 21;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SECRET_BYTES = 16;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int CBC_IV_BYTES = 16;

    private final int protocolVersion;
    private final byte[] secretBytes;
    private final UUID secretUuid;
    private final UUID connectionId;
    private final SecretKeySpec keySpec;

    private VoiceChatCrypto(int protocolVersion, byte[] secretBytes, UUID connectionId) {
        this.protocolVersion = protocolVersion;
        this.secretBytes = secretBytes.clone();
        this.secretUuid = bytesToUuid(secretBytes);
        this.connectionId = connectionId;
        this.keySpec = new SecretKeySpec(this.secretBytes, "AES");
    }

    /** Creates fresh random credentials for a client using {@code protocolVersion} (18 or 21). */
    public static VoiceChatCrypto generate(int protocolVersion) {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return new VoiceChatCrypto(protocolVersion, secret, UUID.randomUUID());
    }

    /** Creates credentials from explicit bytes (for deterministic unit tests). */
    static VoiceChatCrypto of(int protocolVersion, byte[] secretBytes, UUID connectionId) {
        if (secretBytes.length != SECRET_BYTES) {
            throw new IllegalArgumentException("secret must be 16 bytes");
        }
        return new VoiceChatCrypto(protocolVersion, secretBytes, connectionId);
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public boolean isGcm() {
        return protocolVersion >= PROTOCOL_V21;
    }

    public byte[] secretBytes() {
        return secretBytes.clone();
    }

    public UUID secretUuid() {
        return secretUuid;
    }

    public UUID connectionId() {
        return connectionId;
    }

    public byte[] encrypt(byte[] plaintext) throws GeneralSecurityException {
        if (isGcm()) {
            byte[] iv = new byte[GCM_IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] enc = cipher.doFinal(plaintext);
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            return out;
        }
        byte[] iv = new byte[CBC_IV_BYTES];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(iv));
        byte[] enc = cipher.doFinal(plaintext);
        byte[] out = new byte[iv.length + enc.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(enc, 0, out, iv.length, enc.length);
        return out;
    }

    public byte[] decrypt(byte[] payload) throws GeneralSecurityException {
        if (isGcm()) {
            if (payload.length <= GCM_IV_BYTES) {
                throw new GeneralSecurityException("GCM payload too short");
            }
            byte[] iv = Arrays.copyOfRange(payload, 0, GCM_IV_BYTES);
            byte[] body = Arrays.copyOfRange(payload, GCM_IV_BYTES, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(body);
        }
        if (payload.length <= CBC_IV_BYTES) {
            throw new GeneralSecurityException("CBC payload too short");
        }
        byte[] iv = Arrays.copyOfRange(payload, 0, CBC_IV_BYTES);
        byte[] body = Arrays.copyOfRange(payload, CBC_IV_BYTES, payload.length);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(iv));
        return cipher.doFinal(body);
    }

    private static UUID bytesToUuid(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        return new UUID(buf.getLong(), buf.getLong());
    }
}
