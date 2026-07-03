package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: a full chunk snapshot. {@code payload} is the section-paletted blob
 * produced by {@link com.openmason.engine.net.protocol.codec.VoxelChunkCodec};
 * {@code metaPayload} is the game-side chunk metadata blob (snow layers, block states —
 * see {@code GameChunkMetaCodec}), empty when the chunk carries none. Keeping metadata in
 * a separate game-encoded blob leaves the engine chunk codec game-agnostic.
 */
public record ChunkDataS2C(int chunkX, int chunkZ, byte[] payload, byte[] metaPayload) implements Packet {

    private static final byte[] EMPTY = new byte[0];

    /** Convenience for meta-less chunks (tests, plain terrain). */
    public ChunkDataS2C(int chunkX, int chunkZ, byte[] payload) {
        this(chunkX, chunkZ, payload, EMPTY);
    }

    public static final PacketCodec<ChunkDataS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, ChunkDataS2C p) {
            out.writeInt(p.chunkX());
            out.writeInt(p.chunkZ());
            ByteBufIO.writeByteArray(out, p.payload(), ByteBufIO.MAX_CHUNK_BYTES);
            ByteBufIO.writeByteArray(out, p.metaPayload(), ByteBufIO.MAX_CHUNK_BYTES);
        }

        @Override
        public ChunkDataS2C decode(ByteBuf in) {
            return new ChunkDataS2C(
                in.readInt(), in.readInt(),
                ByteBufIO.readByteArray(in, ByteBufIO.MAX_CHUNK_BYTES),
                ByteBufIO.readByteArray(in, ByteBufIO.MAX_CHUNK_BYTES));
        }
    };
}
