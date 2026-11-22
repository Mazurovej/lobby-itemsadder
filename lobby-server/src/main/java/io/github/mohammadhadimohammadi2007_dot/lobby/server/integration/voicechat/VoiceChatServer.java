package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.voicechat;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.config.IntegrationsConfig;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.litebans.MuteService;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.vanish.Vanished;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerPluginMessageEvent;
import net.minestom.server.event.trait.PlayerEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.timer.Task;
import net.minestom.server.timer.TaskSchedule;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Built-in Simple Voice Chat server for Minestom supporting protocols v18 (2.5.x), v19-v20 (2.6.x), and v21 (2.7.x).
 *
 * <p>Handles the TCP plugin-message handshake ({@code voicechat:request_secret} &rarr; {@code voicechat:secret}),
 * player voice state synchronization, AES-GCM/AES-CBC encrypted UDP proximity audio routing, per-instance
 * isolation (players in different lobby instances do not hear each other), vanish hiding, and LiteBans mutes.
 */
public final class VoiceChatServer {

    public static final String INTEGRATION_NAME = "Simple Voice Chat";
    private static final Logger LOGGER = LoggerFactory.getLogger(VoiceChatServer.class);
    private static final int UDP_BUFFER_SIZE = 4096;
    private static final long KEEP_ALIVE_TIMEOUT_FACTOR = 10L;

    private final IntegrationsConfig.VoiceChat config;
    private final MuteService mutes;
    private final DatagramSocket socket;
    private final Map<UUID, Session> sessionsByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Session> sessionsByConnection = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread udpThread;
    private @Nullable Task keepAliveTask;

    private VoiceChatServer(IntegrationsConfig.VoiceChat config, MuteService mutes, DatagramSocket socket) {
        this.config = config;
        this.mutes = mutes;
        this.socket = socket;
        this.udpThread = Thread.ofPlatform()
                .name("lobby-voicechat-udp")
                .daemon(true)
                .start(this::udpLoop);
    }

    /** Binds the UDP socket and starts the voice chat receiver thread. */
    public static VoiceChatServer start(IntegrationsConfig.VoiceChat config, MuteService mutes) throws IOException {
        DatagramSocket socket = new DatagramSocket(new InetSocketAddress(config.bindAddress(), config.port()));
        socket.setTrafficClass(0x04); // reliability/low-delay hint
        LOGGER.info("Simple Voice Chat listening on UDP {}:{} (protocols v18-v21, max-distance={})",
                config.bindAddress(), socket.getLocalPort(), config.maxDistance());
        return new VoiceChatServer(config, mutes, socket);
    }

    /** Actual bound UDP port (useful when binding to port 0 in tests). */
    public int localPort() {
        return socket.getLocalPort();
    }

    /** Registers plugin-message and disconnect listeners and starts keep-alives. */
    public void register(EventNode<PlayerEvent> events) {
        events.addListener(PlayerPluginMessageEvent.class, this::onPluginMessage);
        events.addListener(PlayerDisconnectEvent.class, event -> removePlayer(event.getPlayer().getUuid()));
        keepAliveTask = MinecraftServer.getSchedulerManager()
                .buildTask(this::tickKeepAlives)
                .repeat(TaskSchedule.duration(Duration.ofMillis(config.keepAliveMillis())))
                .schedule();
    }

    /** Stops the UDP socket and keep-alive scheduler cleanly. */
    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (keepAliveTask != null) {
            keepAliveTask.cancel();
        }
        socket.close();
        udpThread.interrupt();
        sessionsByPlayer.clear();
        sessionsByConnection.clear();
    }

    private void onPluginMessage(PlayerPluginMessageEvent event) {
        String channel = event.getIdentifier();
        if (!channel.startsWith("voicechat:")) {
            return;
        }
        Player player = event.getPlayer();
        byte[] data = event.getMessage();
        switch (channel) {
            case VoiceChatCodec.CHANNEL_REQUEST_SECRET -> handleRequestSecret(player, data);
            case VoiceChatCodec.CHANNEL_CLIENT_STATE_V21 -> handleClientStateV21(player, data);
            case VoiceChatCodec.CHANNEL_UPDATE_STATE_V18 -> handleUpdateStateV18(player, data);
            default -> {
                // Ignore unsupported optional channels (groups, categories).
            }
        }
    }

    private void handleRequestSecret(Player player, byte[] data) {
        int version = VoiceChatCodec.decodeRequestSecret(data);
        if (!VoiceChatCrypto.isSupportedProtocol(version)) {
            LOGGER.warn("Simple Voice Chat: unsupported client protocol v{} from {} (supported: v18-v21)",
                    version, player.getUsername());
            return;
        }
        removePlayer(player.getUuid());

        VoiceChatCrypto crypto = VoiceChatCrypto.generate(version);
        Session session = new Session(player, crypto);
        sessionsByPlayer.put(player.getUuid(), session);
        if (crypto.usesConnectionId()) {
            sessionsByConnection.put(crypto.connectionId(), session);
        }

        byte[] secretPayload = VoiceChatCodec.encodeSecret(crypto, player.getUuid(), config);
        player.sendPluginMessage(VoiceChatCodec.CHANNEL_SECRET, secretPayload);
        LOGGER.info("Simple Voice Chat: sent secret to {} (protocol v{}, UDP port {})",
                player.getUsername(), version, config.port());

        // Send all known player states to the newly handshaked player.
        List<VoiceChatCodec.PlayerVoiceState> states = new ArrayList<>();
        for (Session other : sessionsByPlayer.values()) {
            states.add(other.toState());
        }
        player.sendPluginMessage(VoiceChatCodec.CHANNEL_PLAYER_STATES,
                VoiceChatCodec.encodePlayerStates(states, version));
        broadcastState(session);
    }

    private void handleClientStateV21(Player player, byte[] data) {
        Session session = sessionsByPlayer.get(player.getUuid());
        if (session == null || data.length < 2) {
            return;
        }
        session.muted = data[0] != 0;
        session.disabled = data[1] != 0;
        broadcastState(session);
    }

    private void handleUpdateStateV18(Player player, byte[] data) {
        Session session = sessionsByPlayer.get(player.getUuid());
        if (session == null || data.length < 1) {
            return;
        }
        session.disabled = data[0] != 0;
        broadcastState(session);
    }

    private void removePlayer(UUID playerId) {
        Session removed = sessionsByPlayer.remove(playerId);
        if (removed == null) {
            return;
        }
        sessionsByConnection.remove(removed.crypto.connectionId());
        byte[] packet = VoiceChatCodec.encodeRemovePlayerState(playerId);
        for (Session other : sessionsByPlayer.values()) {
            if (other.player.isOnline()) {
                other.player.sendPluginMessage(VoiceChatCodec.CHANNEL_REMOVE_STATE, packet);
            }
        }
    }

    private void broadcastState(Session changed) {
        VoiceChatCodec.PlayerVoiceState state = changed.toState();
        byte[] v21 = null;
        byte[] v18 = null;
        for (Session other : sessionsByPlayer.values()) {
            if (!other.player.isOnline()) {
                continue;
            }
            if (other.crypto.protocolVersion() >= VoiceChatCrypto.PROTOCOL_V21) {
                if (v21 == null) {
                    v21 = VoiceChatCodec.encodePlayerState(state, VoiceChatCrypto.PROTOCOL_V21);
                }
                other.player.sendPluginMessage(VoiceChatCodec.CHANNEL_PLAYER_STATE, v21);
            } else {
                if (v18 == null) {
                    v18 = VoiceChatCodec.encodePlayerState(state, VoiceChatCrypto.PROTOCOL_V18);
                }
                other.player.sendPluginMessage(VoiceChatCodec.CHANNEL_PLAYER_STATE, v18);
            }
        }
    }

    private void udpLoop() {
        byte[] buffer = new byte[UDP_BUFFER_SIZE];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        while (running.get()) {
            datagram.setLength(buffer.length);
            try {
                socket.receive(datagram);
                handleUdpPacket(datagram.getSocketAddress(), buffer, datagram.getLength());
            } catch (SocketException e) {
                if (running.get()) {
                    LOGGER.warn("Simple Voice Chat UDP socket error: {}", e.getMessage());
                }
                break;
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.debug("Simple Voice Chat UDP read error: {}", e.getMessage());
                }
            } catch (RuntimeException e) {
                LOGGER.debug("Simple Voice Chat UDP packet handling failed", e);
            }
        }
    }

    /** Processes one incoming UDP datagram. Package-private for tests. */
    void handleUdpPacket(SocketAddress from, byte[] data, int length) {
        VoiceChatCodec.RawUdpEnvelope envelope = VoiceChatCodec.decodeClientUdp(data, length);
        if (envelope == null) {
            return;
        }
        Session session = sessionsByConnection.get(envelope.lookupId());
        if (session == null) {
            session = sessionsByPlayer.get(envelope.lookupId());
        }
        if (session == null) {
            return;
        }

        byte[] decrypted;
        try {
            decrypted = session.crypto.decrypt(envelope.encryptedPayload());
        } catch (GeneralSecurityException e) {
            LOGGER.debug("Simple Voice Chat: UDP decryption failed for {} (protocol v{}): {}",
                    session.player.getUsername(), session.crypto.protocolVersion(), e.getMessage());
            return;
        }
        if (decrypted.length == 0) {
            return;
        }

        VoiceChatCodec.Reader reader = new VoiceChatCodec.Reader(decrypted, decrypted.length);
        byte packetId = reader.readByte();
        switch (packetId) {
            case VoiceChatCodec.PACKET_AUTHENTICATE -> handleAuthenticate(session, from, reader);
            case VoiceChatCodec.PACKET_CONNECTION_CHECK -> handleConnectionCheck(session, from);
            case VoiceChatCodec.PACKET_KEEP_ALIVE -> {
                if (session.authenticated) {
                    session.lastKeepAliveMillis = System.currentTimeMillis();
                    sendUdp(session, VoiceChatCodec.encodeEmptyPacket(VoiceChatCodec.PACKET_KEEP_ALIVE));
                }
            }
            case VoiceChatCodec.PACKET_MIC -> handleMic(session, reader);
            default -> {
                // Ignore unknown UDP packet types.
            }
        }
    }

    private void handleAuthenticate(Session session, SocketAddress from, VoiceChatCodec.Reader reader) {
        if (!session.crypto.usesConnectionId()) {
            try {
                UUID playerUuid = reader.readUuid();
                UUID secretUuid = reader.readUuid();
                if (!playerUuid.equals(session.player.getUuid()) || !secretUuid.equals(session.crypto.secretUuid())) {
                    LOGGER.warn("Simple Voice Chat: UDP authentication failed for {} (UUID/secret mismatch)",
                            session.player.getUsername());
                    return;
                }
            } catch (RuntimeException e) {
                LOGGER.warn("Simple Voice Chat: malformed UDP authenticate packet from {}", session.player.getUsername());
                return;
            }
        }
        session.address = from;
        session.authenticated = true;
        session.disconnected = false;
        session.lastKeepAliveMillis = System.currentTimeMillis();
        sendUdp(session, VoiceChatCodec.encodeEmptyPacket(VoiceChatCodec.PACKET_AUTHENTICATE_ACK));
        broadcastState(session);
        LOGGER.info("Simple Voice Chat: {} authenticated over UDP from {} (protocol v{})",
                session.player.getUsername(), from, session.crypto.protocolVersion());
    }

    private void handleConnectionCheck(Session session, SocketAddress from) {
        if (!session.authenticated) {
            return;
        }
        session.address = from;
        session.lastKeepAliveMillis = System.currentTimeMillis();
        sendUdp(session, VoiceChatCodec.encodeEmptyPacket(VoiceChatCodec.PACKET_CONNECTION_CHECK_ACK));
    }

    private void handleMic(Session senderSession, VoiceChatCodec.Reader reader) {
        if (!senderSession.authenticated || senderSession.disabled || senderSession.muted
                || sessionsByPlayer.size() <= 1) {
            return;
        }
        Player sender = senderSession.player;
        if (!sender.isOnline() || Vanished.is(sender)) {
            return;
        }
        if (config.respectMutes() && mutes.isMuted(sender.getUuid())) {
            return;
        }
        VoiceChatCodec.MicData mic = VoiceChatCodec.decodeMicBody(reader);
        if (mic == null) {
            return;
        }
        Instance senderInstance = sender.getInstance();
        if (senderInstance == null) {
            return;
        }
        Pos senderPos = sender.getPosition();
        float distance = (float) (mic.whispering() ? config.whisperDistance() : config.maxDistance());
        double maxDistSq = (double) distance * distance;
        byte[] soundPlaintext = VoiceChatCodec.encodePlayerSound(
                sender.getUuid(), sender.getUuid(), mic.opusData(), mic.sequenceNumber(), distance, mic.whispering());

        for (Session receiverSession : sessionsByPlayer.values()) {
            if (receiverSession == senderSession || !receiverSession.authenticated || receiverSession.disabled) {
                continue;
            }
            Player receiver = receiverSession.player;
            if (!receiver.isOnline() || receiver.getInstance() != senderInstance) {
                continue;
            }
            if (receiver.getPosition().distanceSquared(senderPos) <= maxDistSq) {
                sendUdp(receiverSession, soundPlaintext);
            }
        }
    }

    private void tickKeepAlives() {
        long now = System.currentTimeMillis();
        long timeout = config.keepAliveMillis() * KEEP_ALIVE_TIMEOUT_FACTOR;
        byte[] keepAlive = VoiceChatCodec.encodeEmptyPacket(VoiceChatCodec.PACKET_KEEP_ALIVE);
        for (Session session : sessionsByPlayer.values()) {
            if (!session.authenticated) {
                continue;
            }
            if (now - session.lastKeepAliveMillis > timeout) {
                session.authenticated = false;
                session.disconnected = true;
                session.address = null;
                broadcastState(session);
                continue;
            }
            sendUdp(session, keepAlive);
        }
    }

    private void sendUdp(Session session, byte[] plaintext) {
        SocketAddress address = session.address;
        if (address == null || socket.isClosed()) {
            return;
        }
        try {
            byte[] encrypted = session.crypto.encrypt(plaintext);
            byte[] framed = VoiceChatCodec.encodeServerUdp(encrypted);
            socket.send(new DatagramPacket(framed, framed.length, address));
        } catch (IOException | GeneralSecurityException e) {
            LOGGER.debug("Simple Voice Chat UDP send failed for {}: {}", session.player.getUsername(), e.getMessage());
        }
    }

    private static final class Session {
        final Player player;
        final VoiceChatCrypto crypto;
        volatile @Nullable SocketAddress address;
        volatile boolean authenticated;
        volatile boolean muted;
        volatile boolean disabled;
        volatile boolean disconnected = true;
        volatile long lastKeepAliveMillis = System.currentTimeMillis();

        Session(Player player, VoiceChatCrypto crypto) {
            this.player = player;
            this.crypto = crypto;
        }

        VoiceChatCodec.PlayerVoiceState toState() {
            return new VoiceChatCodec.PlayerVoiceState(
                    player.getUuid(), player.getUsername(), muted, disabled, disconnected, null);
        }
    }
}
