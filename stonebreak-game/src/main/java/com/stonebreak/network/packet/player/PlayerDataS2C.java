package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: the joining player's restored {@code PlayerData} JSON blob (inventory +
 * position + stats), loaded from per-username storage. An <b>empty</b> payload means "no saved
 * data" — the client then gives a fresh player its starting items. Sent only to remote (TCP)
 * clients; the in-process local player is restored same-JVM.
 */
public record PlayerDataS2C(byte[] json) implements Packet {

    public static final PacketCodec<PlayerDataS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerDataS2C p) {
            ByteBufIO.writeByteArray(out, p.json(), PlayerDataC2S.MAX_PLAYER_BYTES);
        }

        @Override
        public PlayerDataS2C decode(ByteBuf in) {
            return new PlayerDataS2C(ByteBufIO.readByteArray(in, PlayerDataC2S.MAX_PLAYER_BYTES));
        }
    };
}
