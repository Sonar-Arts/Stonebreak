package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: batched per-block METADATA changes within one 16×16×16 section —
 * companion to {@link MultiBlockChangeS2C}, which carries block ids. Each {@code packed}
 * entry is {@code (localPos << 16) | (value & 0xFFFF)} with
 * {@code localPos = (lx << 8) | (ly << 4) | lz} (same layout as the block-change packet).
 *
 * <p>{@code metaKind} selects the metadata channel:
 * <ul>
 *   <li>{@link #KIND_SNOW_LAYERS} — value = layer count 1-8 (0 = entry removed)</li>
 *   <li>{@link #KIND_WATER_LEVEL} — reserved (flow-height cosmetics, not yet emitted)</li>
 * </ul>
 */
public record BlockMetaS2C(int sectionX, int sectionY, int sectionZ, byte metaKind, int[] packed) implements Packet {

    public static final byte KIND_SNOW_LAYERS = 1;
    public static final byte KIND_WATER_LEVEL = 2; // reserved

    public static final PacketCodec<BlockMetaS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, BlockMetaS2C p) {
            out.writeInt(p.sectionX());
            out.writeInt(p.sectionY());
            out.writeInt(p.sectionZ());
            out.writeByte(p.metaKind());
            int[] packed = p.packed();
            ByteBufIO.writeVarInt(out, packed.length);
            for (int v : packed) {
                out.writeInt(v);
            }
        }

        @Override
        public BlockMetaS2C decode(ByteBuf in) {
            int sx = in.readInt();
            int sy = in.readInt();
            int sz = in.readInt();
            byte kind = in.readByte();
            int n = ByteBufIO.readVarInt(in);
            if (n < 0 || n > ByteBufIO.MAX_MULTI_BLOCK_ENTRIES) {
                throw new IllegalArgumentException("Invalid block-meta count: " + n);
            }
            int[] packed = new int[n];
            for (int i = 0; i < n; i++) {
                packed[i] = in.readInt();
            }
            return new BlockMetaS2C(sx, sy, sz, kind, packed);
        }
    };
}
