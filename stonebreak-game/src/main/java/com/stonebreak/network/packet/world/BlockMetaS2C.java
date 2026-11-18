package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.stonebreak.network.packet.NetCoords;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: batched per-block METADATA changes within one 16×16×16 section (64-bit section
 * coordinates, block coordinates {@code >> 4}) — companion to {@link MultiBlockChangeS2C}, which carries block
 * ids. Each {@code packed} entry is {@code (localPos << 16) | (value & 0xFFFF)} with
 * {@code localPos = (lx << 8) | (ly << 4) | lz} (same layout as the block-change packet).
 *
 * <p>{@code metaKind} selects the metadata channel (both are cube layers on the client):
 * <ul>
 *   <li>{@link #KIND_SNOW_LAYERS} — value = layer count 1-8 (0 = entry removed)</li>
 *   <li>{@link #KIND_WATER_LEVEL} — value = water value in the game vocabulary ({@code ChunkWaterLayer}):
 *       0 source, 1-7 flowing, 8 falling, 9-16 a worldgen river surface running in octant 0-7</li>
 * </ul>
 */
public record BlockMetaS2C(long sectionX, long sectionY, long sectionZ, byte metaKind, int[] packed)
        implements Packet {

    public static final byte KIND_SNOW_LAYERS = 1;
    public static final byte KIND_WATER_LEVEL = 2;

    public static final PacketCodec<BlockMetaS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, BlockMetaS2C p) {
            NetCoords.writeBlock(out, p.sectionX(), p.sectionY(), p.sectionZ());
            out.writeByte(p.metaKind());
            int[] packed = p.packed();
            ByteBufIO.writeVarInt(out, packed.length);
            for (int v : packed) {
                out.writeInt(v);
            }
        }

        @Override
        public BlockMetaS2C decode(ByteBuf in) {
            long sx = NetCoords.readVarLong(in);
            long sy = NetCoords.readVarLong(in);
            long sz = NetCoords.readVarLong(in);
            byte kind = in.readByte();
            int n = NetCoords.readCount(in, ByteBufIO.MAX_MULTI_BLOCK_ENTRIES, "block-meta");
            int[] packed = new int[n];
            for (int i = 0; i < n; i++) {
                packed[i] = in.readInt();
            }
            return new BlockMetaS2C(sx, sy, sz, kind, packed);
        }
    };
}
