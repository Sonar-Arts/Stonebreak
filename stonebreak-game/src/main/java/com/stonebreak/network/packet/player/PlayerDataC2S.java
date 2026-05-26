package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: the sender's full player state (inventory + position + stats) as a
 * serialized {@code PlayerData} JSON blob (produced by {@code JsonPlayerSerializer}). The
 * server treats it opaquely — caching it per connected player and persisting it per username
 * — so remote players' inventories survive across sessions. The in-process local player does
 * NOT use this path (its state is persisted same-JVM); only TCP clients send it.
 */
public record PlayerDataC2S(byte[] json) implements Packet {

    /** Generous cap; a serialized PlayerData is a few KB. */
    public static final int MAX_PLAYER_BYTES = 256 * 1024;

    public static final PacketCodec<PlayerDataC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerDataC2S p) {
            ByteBufIO.writeByteArray(out, p.json(), MAX_PLAYER_BYTES);
        }

        @Override
        public PlayerDataC2S decode(ByteBuf in) {
            return new PlayerDataC2S(ByteBufIO.readByteArray(in, MAX_PLAYER_BYTES));
        }
    };
}
