package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: the client's render distance in chunks (horizontal radius = chunks x 16 blocks) and its
 * vertical render distance in blocks (the stream ellipsoid's half-height). Sent once after the handshake and
 * again whenever the player applies new settings. The server clamps both and uses them as this player's
 * stream center radii (see {@code ServerLevel.updateStreamCenters}) — its stream then generates, and the cube
 * stream sends, what the client's own REMOTE stream will ask for.
 */
public record ViewDistanceC2S(int chunks, int verticalBlocks) implements Packet {

    public static final PacketCodec<ViewDistanceC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, ViewDistanceC2S p) {
            out.writeInt(p.chunks());
            out.writeInt(p.verticalBlocks());
        }

        @Override
        public ViewDistanceC2S decode(ByteBuf in) {
            return new ViewDistanceC2S(in.readInt(), in.readInt());
        }
    };
}
