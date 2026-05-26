package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: batched block changes within one 16×16×16 section. Each {@code packed}
 * entry is {@code (localPos << 16) | (blockId & 0xFFFF)} where
 * {@code localPos = (lx << 8) | (ly << 4) | lz}. ~3-4× cheaper than individual
 * {@link BlockChangeS2C} when edits cluster (explosions, fluid spread, fill tools).
 */
public record MultiBlockChangeS2C(int sectionX, int sectionY, int sectionZ, int[] packed) implements Packet {

    public static final PacketCodec<MultiBlockChangeS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, MultiBlockChangeS2C p) {
            out.writeInt(p.sectionX());
            out.writeInt(p.sectionY());
            out.writeInt(p.sectionZ());
            int[] packed = p.packed();
            ByteBufIO.writeVarInt(out, packed.length);
            for (int v : packed) {
                out.writeInt(v);
            }
        }

        @Override
        public MultiBlockChangeS2C decode(ByteBuf in) {
            int sx = in.readInt();
            int sy = in.readInt();
            int sz = in.readInt();
            int n = ByteBufIO.readVarInt(in);
            if (n < 0 || n > ByteBufIO.MAX_MULTI_BLOCK_ENTRIES) {
                throw new IllegalArgumentException("Invalid multi-block-change count: " + n);
            }
            int[] packed = new int[n];
            for (int i = 0; i < n; i++) {
                packed[i] = in.readInt();
            }
            return new MultiBlockChangeS2C(sx, sy, sz, packed);
        }
    };
}
