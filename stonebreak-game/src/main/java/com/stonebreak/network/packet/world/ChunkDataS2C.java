package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.openmason.engine.net.protocol.codec.ChunkBlockPayload;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: a full chunk snapshot. {@code blocks} is the chunk's block storage — a
 * copy-on-write snapshot when the server built it, which a LOCAL (singleplayer) client installs
 * as is, or {@link com.openmason.engine.net.protocol.codec.VoxelChunkCodec} bytes, which a TCP
 * connection writes (encoding the snapshot on the network thread, once per snapshot however
 * many players receive it). {@code metaPayload} is the game-side chunk metadata blob (snow
 * layers, block states — see {@code GameChunkMetaCodec}), empty when the chunk carries none.
 * Keeping metadata in a separate game-encoded blob leaves the engine chunk codec game-agnostic.
 */
public record ChunkDataS2C(int chunkX, int chunkZ, ChunkBlockPayload blocks, byte[] metaPayload) implements Packet {

    private static final byte[] EMPTY = new byte[0];

    public ChunkDataS2C(int chunkX, int chunkZ, byte[] payload, byte[] metaPayload) {
        this(chunkX, chunkZ, ChunkBlockPayload.ofEncoded(payload), metaPayload);
    }

    /** Convenience for meta-less chunks (tests, plain terrain). */
    public ChunkDataS2C(int chunkX, int chunkZ, byte[] payload) {
        this(chunkX, chunkZ, payload, EMPTY);
    }

    /** The encoded block payload (encodes a snapshot on first use). */
    public byte[] payload() {
        return blocks.encoded();
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
