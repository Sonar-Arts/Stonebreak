package com.stonebreak.network.packet.world;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.openmason.engine.net.protocol.codec.ChunkBlockPayload;
import com.openmason.engine.net.protocol.codec.WireCompression;
import io.netty.buffer.ByteBuf;

/**
 * Server → client: a full chunk snapshot. {@code blocks} is the chunk's block storage — a
 * copy-on-write snapshot when the server built it, which a LOCAL (singleplayer) client installs
 * as is, or {@link com.openmason.engine.net.protocol.codec.VoxelChunkCodec} bytes, which a TCP
 * connection writes (encoding the snapshot on the network thread, once per snapshot however
 * many players receive it). {@code metaPayload} is the game-side chunk metadata blob (snow
 * layers, block states — see {@code GameChunkMetaCodec}), empty when the chunk carries none.
 * Keeping metadata in a separate game-encoded blob leaves the engine chunk codec game-agnostic.
 *
 * <p>Over TCP both blobs are {@link WireCompression} blobs (issue #346): compressed with a
 * codec the client listed in its handshake — the block payload once per codec, shared like its
 * encoding — or raw when small, incompressible, or the client decodes nothing.
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
            encode(out, p, 0);
        }

        @Override
        public void encode(ByteBuf out, ChunkDataS2C p, int peerCompression) {
            out.writeInt(p.chunkX());
            out.writeInt(p.chunkZ());
            byte[] raw = p.payload();
            int codec = WireCompression.choose(peerCompression, raw.length);
            WireCompression.writeBlob(out, raw, p.blocks().compressed(codec), codec, ByteBufIO.MAX_CHUNK_BYTES);
            WireCompression.writeBlob(out, p.metaPayload(), ByteBufIO.MAX_CHUNK_BYTES, peerCompression);
        }

        @Override
        public ChunkDataS2C decode(ByteBuf in) {
            return new ChunkDataS2C(
                in.readInt(), in.readInt(),
                WireCompression.readBlob(in, ByteBufIO.MAX_CHUNK_BYTES),
                WireCompression.readBlob(in, ByteBufIO.MAX_CHUNK_BYTES));
        }
    };
}
