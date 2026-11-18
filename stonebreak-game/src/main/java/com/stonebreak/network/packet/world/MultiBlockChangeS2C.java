package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.network.packet.NetCoords;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: batched block changes within one 16×16×16 section, addressed by its 64-bit section
 * coordinates {@code (sectionX, sectionY, sectionZ)} = block coordinates {@code >> 4} (a section lies inside
 * one 32³ cube). Each {@code packed} entry is {@code (localPos << 16) | (blockId & 0xFFFF)} where
 * {@code localPos = (lx << 8) | (ly << 4) | lz} — a section-LOCAL offset, not a coordinate key. ~3-4×
 * cheaper than individual {@link BlockChangeS2C} when edits cluster (explosions, fluid spread, fill tools).
 */
public record MultiBlockChangeS2C(long sectionX, long sectionY, long sectionZ, int[] packed) implements Packet {

    public static final PacketCodec<MultiBlockChangeS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, MultiBlockChangeS2C p) {
            NetCoords.writeBlock(out, p.sectionX(), p.sectionY(), p.sectionZ());
            int[] packed = p.packed();
            ByteBufIO.writeVarInt(out, packed.length);
            for (int v : packed) {
                out.writeInt(v);
            }
        }

        @Override
        public MultiBlockChangeS2C decode(ByteBuf in) {
            long sx = NetCoords.readVarLong(in);
            long sy = NetCoords.readVarLong(in);
            long sz = NetCoords.readVarLong(in);
            int n = NetCoords.readCount(in, ByteBufIO.MAX_MULTI_BLOCK_ENTRIES, "multi-block-change");
            int[] packed = new int[n];
            for (int i = 0; i < n; i++) {
                packed[i] = in.readInt();
            }
            return new MultiBlockChangeS2C(sx, sy, sz, packed);
        }
    };
}
