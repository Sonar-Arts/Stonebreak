package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.network.packet.SequencedIntent;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: intent to set the snow layer count at a position ({@code layers} 1-8;
 * 0 = remove tracking). Covers the one snow mutation {@code BlockChangeC2S} misses — adding
 * a layer to an EXISTING snow block changes no block id. Placement/breaking of the SNOW
 * block itself still travels as a normal block change; the server derives layer bookkeeping
 * from it. The server validates (reach, range, target is SNOW) and broadcasts the result to
 * all clients as {@code BlockMetaS2C}. {@code seq} numbers the intent ({@link SequencedIntent}):
 * the added layer's SNOW already left the client's mirror.
 */
public record SnowLayerC2S(int x, int y, int z, byte layers, int seq) implements Packet, SequencedIntent {

    /** An unnumbered snow-layer intent. */
    public SnowLayerC2S(int x, int y, int z, byte layers) {
        this(x, y, z, layers, 0);
    }

    public static final PacketCodec<SnowLayerC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, SnowLayerC2S p) {
            out.writeInt(p.x());
            out.writeInt(p.y());
            out.writeInt(p.z());
            out.writeByte(p.layers());
            ByteBufIO.writeVarInt(out, p.seq());
        }

        @Override
        public SnowLayerC2S decode(ByteBuf in) {
            return new SnowLayerC2S(in.readInt(), in.readInt(), in.readInt(), in.readByte(),
                    ByteBufIO.readVarInt(in));
        }
    };
}
