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
 * Per-connection Simple Voice Chat encryption supporting:
 * <ul>
 *   <li>Protocol v21 (Simple Voice Chat 2.7.x): {@code AES/GCM/NoPadding} (12-byte IV, 128-bit tag) + separate {@code connectionId}</li>
 *   <li>Protocol v19-v20 (Simple Voice Chat 2.6.x): {@code AES/GCM/NoPadding} (12-byte IV, 128-bit tag) with v18 packet layouts</li>
 *   <li>Protocol v18 (Simple Voice Chat 2.5.x): {@code AES/CBC/PKCS5Padding} (16-byte IV)</li>
 * </ul>
 */
public final class VoiceChatCrypto {

    public static final int PROTOCOL_V18 = 18;
    public static final int PROTOCOL_V19 = 19;
    public static final int PROTOCOL_V20 = 20;
    public static final int PROTOCOL_V21 = 21;

    private static final ThreadLocal<SecureRandom> THREAD_RANDOM = ThreadLocal.withInitial(SecureRandom::new);
    private static final ThreadLocal<Cipher> GCM_CIPHER = ThreadLocal.withInitial(() -> newCipher("AES/GCM/NoPadding"));
    private static final ThreadLocal<Cipher> CBC_CIPHER = ThreadLocal.withInitial(() -> newCipher("AES/CBC/PKCS5Padding"));
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

    /** True if {@code version} is supported (SVC 2.5.x v18 and SVC 2.6.x v19/v20/v21). */
    public static boolean isSupportedProtocol(int version) {
        return version >= PROTOCOL_V18 && version <= PROTOCOL_V21;
    }

    /** Creates fresh random credentials for a client using {@code protocolVersion} (18-21). */
    public static VoiceChatCrypto generate(int protocolVersion) {
        byte[] secret = new byte[SECRET_BYTES];
        THREAD_RANDOM.get().nextBytes(secret);
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

    /** v19, v20 and v21 use AES-GCM; v18 uses AES-CBC. */
    public boolean isGcm() {
        return protocolVersion >= PROTOCOL_V19;
    }

    /** Only v21+ uses a separate connectionId UUID, empty AuthenticatePacket, and bit-packed PlayerState. */
    public boolean usesConnectionId() {
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
            THREAD_RANDOM.get().nextBytes(iv);
            Cipher cipher = GCM_CIPHER.get();
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] out = new byte[GCM_IV_BYTES + cipher.getOutputSize(plaintext.length)];
            System.arraycopy(iv, 0, out, 0, GCM_IV_BYTES);
            int written = cipher.doFinal(plaintext, 0, plaintext.length, out, GCM_IV_BYTES);
            return written + GCM_IV_BYTES == out.length ? out : Arrays.copyOf(out, written + GCM_IV_BYTES);
        }
        byte[] iv = new byte[CBC_IV_BYTES];
        THREAD_RANDOM.get().nextBytes(iv);
        Cipher cipher = CBC_CIPHER.get();
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(iv));
        byte[] out = new byte[CBC_IV_BYTES + cipher.getOutputSize(plaintext.length)];
        System.arraycopy(iv, 0, out, 0, CBC_IV_BYTES);
        int written = cipher.doFinal(plaintext, 0, plaintext.length, out, CBC_IV_BYTES);
        return written + CBC_IV_BYTES == out.length ? out : Arrays.copyOf(out, written + CBC_IV_BYTES);
    }

    public byte[] decrypt(byte[] payload) throws GeneralSecurityException {
        if (isGcm()) {
            if (payload.length <= GCM_IV_BYTES) {
                throw new GeneralSecurityException("GCM payload too short");
            }
            Cipher cipher = GCM_CIPHER.get();
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, payload, 0, GCM_IV_BYTES));
            return cipher.doFinal(payload, GCM_IV_BYTES, payload.length - GCM_IV_BYTES);
        }
        if (payload.length <= CBC_IV_BYTES) {
            throw new GeneralSecurityException("CBC payload too short");
        }
        Cipher cipher = CBC_CIPHER.get();
        cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(payload, 0, CBC_IV_BYTES));
        return cipher.doFinal(payload, CBC_IV_BYTES, payload.length - CBC_IV_BYTES);
    }

    private static Cipher newCipher(String transformation) {
        try {
            return Cipher.getInstance(transformation);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Missing AES cipher " + transformation, e);
        }
    }

    private static UUID bytesToUuid(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        return new UUID(buf.getLong(), buf.getLong());
    }
}
