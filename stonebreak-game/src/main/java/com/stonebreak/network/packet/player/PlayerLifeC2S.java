package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.network.packet.NetCoords;
import io.netty.buffer.ByteBuf;

/**
 * Client → server, on the reliable channel: a life edge the CLIENT asks for (#357, reworked by
 * #343 — the server owns health and decides deaths itself). {@code !dead} asks to respawn; the
 * server puts the player at the spot it picks and answers with {@code PlayerPositionS2C} and
 * {@code PlayerHealthS2C}. {@code dead} gives the player's life up (a death only the client saw);
 * a death the server already decided makes it a no-op. The position is informational only.
 */
public record PlayerLifeC2S(boolean dead, double x, double y, double z) implements Packet {

    public static final PacketCodec<PlayerLifeC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, PlayerLifeC2S p) {
            out.writeBoolean(p.dead());
            NetCoords.writePosition(out, p.x(), p.y(), p.z());
        }

        @Override
        public PlayerLifeC2S decode(ByteBuf in) {
            return new PlayerLifeC2S(in.readBoolean(), NetCoords.readPosition(in), NetCoords.readPosition(in), NetCoords.readPosition(in));
        }
    };
}
