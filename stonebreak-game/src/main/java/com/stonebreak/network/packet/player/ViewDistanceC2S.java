package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: the client's render distance in chunks. Sent once after the
 * handshake and again whenever the player applies a new render-distance setting.
 * The server clamps it and uses it as this player's chunk-streaming view radius
 * (see {@code ServerChunkHandler}) — without this the server streams a fixed
 * default no matter what the client's setting says.
 */
public record ViewDistanceC2S(int chunks) implements Packet {

    public static final PacketCodec<ViewDistanceC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, ViewDistanceC2S p) {
            out.writeInt(p.chunks());
        }

        @Override
        public ViewDistanceC2S decode(ByteBuf in) {
            return new ViewDistanceC2S(in.readInt());
        }
    };
}
