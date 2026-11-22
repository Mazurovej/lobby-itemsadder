package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.voicechat;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.IntegrationsConfig;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.UUID;

/**
 * Binary wire codec for Simple Voice Chat TCP plugin messages and UDP packets (protocol v18 and v21).
 */
public final class VoiceChatCodec {

    public static final String CHANNEL_REQUEST_SECRET = "voicechat:request_secret";
    public static final String CHANNEL_SECRET = "voicechat:secret";
    public static final String CHANNEL_CLIENT_STATE_V21 = "voicechat:client_state";
    public static final String CHANNEL_UPDATE_STATE_V18 = "voicechat:update_state";
    public static final String CHANNEL_PLAYER_STATE = "voicechat:state";
    public static final String CHANNEL_PLAYER_STATES = "voicechat:states";
    public static final String CHANNEL_REMOVE_STATE = "voicechat:remove_state";

    public static final byte UDP_MAGIC = (byte) 0xFF;
    public static final byte PACKET_MIC = 0x01;
    public static final byte PACKET_PLAYER_SOUND = 0x02;
    public static final byte PACKET_AUTHENTICATE = 0x05;
    public static final byte PACKET_AUTHENTICATE_ACK = 0x06;
    public static final byte PACKET_KEEP_ALIVE = 0x08;
    public static final byte PACKET_CONNECTION_CHECK = 0x09;
    public static final byte PACKET_CONNECTION_CHECK_ACK = 0x0A;

    private static final int MAX_BYTE_ARRAY = 8192;

    private VoiceChatCodec() {
    }

    /** Player voice state broadcast over {@code voicechat:state} and {@code voicechat:states}. */
    public record PlayerVoiceState(
            UUID uuid,
            String name,
            boolean muted,
            boolean disabled,
            boolean disconnected,
            @Nullable UUID group
    ) {
    }

    /** Decoded client UDP packet header (magic byte already verified). */
    public record RawUdpEnvelope(UUID lookupId, byte[] encryptedPayload) {
    }

    /** Decoded {@code MicPacket} (0x01). */
    public record MicData(byte[] opusData, long sequenceNumber, boolean whispering) {
    }

    public static int decodeRequestSecret(byte[] data) {
        if (data.length < 4) {
            return -1;
        }
        return ByteBuffer.wrap(data).getInt();
    }

    public static byte[] encodeSecret(VoiceChatCrypto crypto, UUID playerUuid, IntegrationsConfig.VoiceChat config) {
        Writer w = new Writer();
        if (crypto.protocolVersion() >= VoiceChatCrypto.PROTOCOL_V21) {
            w.writeRawBytes(crypto.secretBytes());
            w.writeUuid(crypto.connectionId());
        } else {
            w.writeUuid(crypto.secretUuid());
        }
        w.writeInt(config.port());
        w.writeUuid(playerUuid);
        w.writeByte(config.codecOrdinal());
        w.writeInt(config.mtuSize());
        w.writeDouble(config.maxDistance());
        w.writeInt(config.keepAliveMillis());
        w.writeBoolean(false); // groups disabled in lobby
        w.writeString(config.voiceHost());
        w.writeBoolean(config.allowRecording());
        return w.toByteArray();
    }

    public static byte[] encodePlayerState(PlayerVoiceState state, int protocolVersion) {
        Writer w = new Writer();
        writePlayerState(w, state, protocolVersion);
        return w.toByteArray();
    }

    public static byte[] encodePlayerStates(Collection<PlayerVoiceState> states, int protocolVersion) {
        Writer w = new Writer();
        w.writeInt(states.size());
        for (PlayerVoiceState state : states) {
            writePlayerState(w, state, protocolVersion);
        }
        return w.toByteArray();
    }

    public static byte[] encodeRemovePlayerState(UUID uuid) {
        Writer w = new Writer();
        w.writeUuid(uuid);
        return w.toByteArray();
    }

    private static void writePlayerState(Writer w, PlayerVoiceState state, int protocolVersion) {
        if (protocolVersion >= VoiceChatCrypto.PROTOCOL_V21) {
            byte flags = 0;
            if (state.muted()) flags |= (1 << 0);
            if (state.disabled()) flags |= (1 << 2);
            if (state.disconnected()) flags |= (1 << 4);
            if (state.group() != null) flags |= (1 << 5);
            w.writeByte(flags);
            w.writeUuid(state.uuid());
            w.writeString(state.name());
            if (state.group() != null) {
                w.writeUuid(state.group());
            }
        } else {
            w.writeBoolean(state.disabled());
            w.writeBoolean(state.disconnected());
            w.writeUuid(state.uuid());
            w.writeString(state.name());
            w.writeBoolean(state.group() != null);
            if (state.group() != null) {
                w.writeUuid(state.group());
            }
        }
    }

    /** Parses a client->server UDP datagram: {@code [0xFF][UUID][VarInt len][encrypted]}. */
    public static @Nullable RawUdpEnvelope decodeClientUdp(byte[] datagram, int length) {
        try {
            Reader r = new Reader(datagram, length);
            if (r.readByte() != UDP_MAGIC) {
                return null;
            }
            UUID id = r.readUuid();
            byte[] encrypted = r.readByteArray(MAX_BYTE_ARRAY);
            return new RawUdpEnvelope(id, encrypted);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return null;
        }
    }

    /** Wraps an encrypted server->client payload: {@code [0xFF][VarInt len][encrypted]}. */
    public static byte[] encodeServerUdp(byte[] encryptedPayload) {
        Writer w = new Writer();
        w.writeByte(UDP_MAGIC);
        w.writeByteArray(encryptedPayload);
        return w.toByteArray();
    }

    public static byte[] encodeEmptyPacket(byte packetId) {
        return new byte[]{packetId};
    }

    public static byte[] encodePlayerSound(UUID channelId, UUID senderUuid, byte[] opusData,
                                           long sequenceNumber, float distance, boolean whispering) {
        Writer w = new Writer();
        w.writeByte(PACKET_PLAYER_SOUND);
        w.writeUuid(channelId);
        w.writeUuid(senderUuid);
        w.writeByteArray(opusData);
        w.writeLong(sequenceNumber);
        w.writeFloat(distance);
        w.writeByte(whispering ? (byte) 0x01 : (byte) 0x00);
        return w.toByteArray();
    }

    public static @Nullable MicData decodeMicBody(Reader r) {
        try {
            byte[] opus = r.readByteArray(MAX_BYTE_ARRAY);
            long seq = r.readLong();
            boolean whispering = r.readBoolean();
            return new MicData(opus, seq, whispering);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return null;
        }
    }

    /** Minimal big-endian + Minecraft VarInt reader. */
    public static final class Reader {
        private final byte[] buf;
        private final int limit;
        private int pos;

        public Reader(byte[] buf, int length) {
            this.buf = buf;
            this.limit = Math.min(buf.length, length);
        }

        public byte readByte() {
            require(1);
            return buf[pos++];
        }

        public boolean readBoolean() {
            return readByte() != 0;
        }

        public long readLong() {
            require(8);
            long v = ByteBuffer.wrap(buf, pos, 8).getLong();
            pos += 8;
            return v;
        }

        public UUID readUuid() {
            return new UUID(readLong(), readLong());
        }

        public int readVarInt() {
            int value = 0;
            int shift = 0;
            while (true) {
                byte b = readByte();
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
                shift += 7;
                if (shift >= 35) {
                    throw new IllegalArgumentException("VarInt too big");
                }
            }
        }

        public byte[] readByteArray(int maxLen) {
            int len = readVarInt();
            if (len < 0 || len > maxLen) {
                throw new IllegalArgumentException("Invalid byte array length: " + len);
            }
            require(len);
            byte[] out = new byte[len];
            System.arraycopy(buf, pos, out, 0, len);
            pos += len;
            return out;
        }

        private void require(int n) {
            if (pos + n > limit) {
                throw new IndexOutOfBoundsException("Buffer underflow");
            }
        }
    }

    /** Minimal big-endian + Minecraft VarInt writer. */
    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(128);

        void writeByte(byte b) {
            out.write(b & 0xFF);
        }

        void writeBoolean(boolean b) {
            out.write(b ? 1 : 0);
        }

        void writeRawBytes(byte[] bytes) {
            out.writeBytes(bytes);
        }

        void writeInt(int v) {
            out.write((v >>> 24) & 0xFF);
            out.write((v >>> 16) & 0xFF);
            out.write((v >>> 8) & 0xFF);
            out.write(v & 0xFF);
        }

        void writeLong(long v) {
            for (int i = 56; i >= 0; i -= 8) {
                out.write((int) ((v >>> i) & 0xFF));
            }
        }

        void writeFloat(float v) {
            writeInt(Float.floatToIntBits(v));
        }

        void writeDouble(double v) {
            writeLong(Double.doubleToLongBits(v));
        }

        void writeUuid(UUID uuid) {
            writeLong(uuid.getMostSignificantBits());
            writeLong(uuid.getLeastSignificantBits());
        }

        void writeVarInt(int value) {
            int v = value;
            while ((v & ~0x7F) != 0) {
                out.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            out.write(v);
        }

        void writeByteArray(byte[] bytes) {
            writeVarInt(bytes.length);
            out.writeBytes(bytes);
        }

        void writeString(String s) {
            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
            writeVarInt(utf8.length);
            out.writeBytes(utf8);
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
