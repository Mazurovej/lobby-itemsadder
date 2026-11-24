package io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore;

import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore.BadcoreSettingsRepository.DuelsState;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore.BadcoreSettingsRepository.QoaState;
import io.github.mohammadhadimohammadi2007_dot.lobby.server.integration.badcore.BadcoreSettingsRepository.TimePreset;
import net.minestom.server.entity.Player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Velocity plugin-messaging bridge on channel {@code badcore:settings}.
 *
 * <p>Wire-compatible with the {@code badcoreSettings} Paper/Velocity plugins:
 * <ul>
 *     <li>Outgoing: {@code GET}, {@code GET_DUELS}, {@code SET}, {@code SET_DUELS}</li>
 *     <li>Incoming: {@code STATE}, {@code DUELS_STATE}</li>
 * </ul>
 */
public final class BadcoreSettingsBridge {

    public static final String CHANNEL = "badcore:settings";

    private final BiConsumer<UUID, QoaState> onQoaState;
    private final BiConsumer<UUID, DuelsState> onDuelsState;

    public BadcoreSettingsBridge(BiConsumer<UUID, QoaState> onQoaState,
                                 BiConsumer<UUID, DuelsState> onDuelsState) {
        this.onQoaState = onQoaState;
        this.onDuelsState = onDuelsState;
    }

    public void requestState(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.sendPluginMessage(CHANNEL, encodeGet(player.getUuid()));
    }

    public void requestDuelsState(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.sendPluginMessage(CHANNEL, encodeGetDuels(player.getUuid()));
    }

    public void sendSet(Player player, String key, boolean value) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.sendPluginMessage(CHANNEL, encodeSet(player.getUuid(), key, value));
    }

    public void sendSetDuels(Player player, DuelsState state) {
        if (player == null || !player.isOnline() || state == null) {
            return;
        }
        player.sendPluginMessage(CHANNEL, encodeSetDuels(player.getUuid(), player.getUsername(), state));
    }

    /** Handles an incoming plugin message payload from Velocity. */
    public void handleIncoming(String channel, byte[] message) {
        if (!CHANNEL.equals(channel) || message == null || message.length == 0) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            String action = in.readUTF();
            if ("STATE".equalsIgnoreCase(action)) {
                UUID uuid = UUID.fromString(in.readUTF());
                QoaState state = new QoaState(in.readBoolean(), in.readBoolean(), in.readBoolean());
                onQoaState.accept(uuid, state);
            } else if ("DUELS_STATE".equalsIgnoreCase(action)) {
                UUID uuid = UUID.fromString(in.readUTF());
                DuelsState state = new DuelsState(
                        in.readBoolean(),
                        in.readBoolean(),
                        in.readBoolean(),
                        TimePreset.parse(in.readUTF()),
                        in.readBoolean(),
                        in.readBoolean(),
                        in.readBoolean(),
                        in.readBoolean()
                );
                onDuelsState.accept(uuid, state);
            }
        } catch (Exception ignored) {
            // Malformed or truncated plugin message from proxy: ignore safely.
        }
    }

    public static byte[] encodeGet(UUID uuid) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeUTF("GET");
            out.writeUTF(uuid.toString());
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encodeGetDuels(UUID uuid) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeUTF("GET_DUELS");
            out.writeUTF(uuid.toString());
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encodeSet(UUID uuid, String key, boolean value) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeUTF("SET");
            out.writeUTF(uuid.toString());
            out.writeUTF(key);
            out.writeBoolean(value);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encodeSetDuels(UUID uuid, String username, DuelsState state) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeUTF("SET_DUELS");
            out.writeUTF(uuid.toString());
            out.writeUTF(username);
            out.writeBoolean(state.duelRequests());
            out.writeBoolean(state.partyInvites());
            out.writeBoolean(state.lobbyVisibility());
            out.writeUTF(state.timePreset().name());
            out.writeBoolean(state.lobbyFly());
            out.writeBoolean(state.vipJoinMessage());
            out.writeBoolean(state.allowSpectators());
            out.writeBoolean(state.silentSpectate());
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
