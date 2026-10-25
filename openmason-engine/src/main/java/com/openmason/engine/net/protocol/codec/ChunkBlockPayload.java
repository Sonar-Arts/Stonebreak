package com.openmason.engine.net.protocol.codec;

import com.openmason.engine.voxel.cco.data.palette.CcoPalettedChunkStorage;

import java.util.Objects;

/**
 * The block half of a chunk snapshot packet: either a copy-on-write storage snapshot (built by
 * the server) or encoded {@link VoxelChunkCodec} bytes (read off a wire).
 *
 * <p>An in-process receiver (a LOCAL channel passes packet objects unserialized) installs
 * {@link #snapshot()} directly, so singleplayer never encodes or decodes a chunk. A wire
 * transport calls {@link #encoded()} from its packet encoder — off the server tick — and the
 * bytes are encoded once and shared by every connection the same payload is sent to.
 *
 * <p>The snapshot must be a storage no one writes: a {@code copy()} of live storage (sections
 * share their state copy-on-write, so the copy costs O(sections) and later writes to the live
 * chunk clone privately). Receivers may {@code copyFrom} it but never mutate it.
 */
public final class ChunkBlockPayload {

    private final CcoPalettedChunkStorage snapshot;
    private volatile byte[] encoded;

    private ChunkBlockPayload(CcoPalettedChunkStorage snapshot, byte[] encoded) {
        this.snapshot = snapshot;
        this.encoded = encoded;
    }

    /** A payload over a storage snapshot nobody mutates; encoded lazily, at most once. */
    public static ChunkBlockPayload ofSnapshot(CcoPalettedChunkStorage snapshot) {
        return new ChunkBlockPayload(Objects.requireNonNull(snapshot, "snapshot"), null);
    }

    /** A payload of already-encoded bytes (decoded off a wire, or a non-paletted chunk). */
    public static ChunkBlockPayload ofEncoded(byte[] encoded) {
        return new ChunkBlockPayload(null, Objects.requireNonNull(encoded, "encoded"));
    }

    /** The storage snapshot, or null when this payload only exists as bytes. */
    public CcoPalettedChunkStorage snapshot() {
        return snapshot;
    }

    /** The {@link VoxelChunkCodec} bytes, encoding the snapshot on first use (thread-safe). */
    public byte[] encoded() {
        byte[] bytes = encoded;
        if (bytes == null) {
            synchronized (this) {
                bytes = encoded;
                if (bytes == null) {
                    bytes = VoxelChunkCodec.encode(snapshot);
                    encoded = bytes;
                }
            }
        }
        return bytes;
    }

    /** True once the bytes exist (tests and diagnostics). */
    public boolean isEncoded() {
        return encoded != null;
    }

    /** Marks a codec whose output was not smaller than the raw bytes. */
    private static final byte[] NOT_SMALLER = new byte[0];
    /** Compressed {@link #encoded()} per {@link WireCompression} codec, built at most once each. */
    private final byte[][] compressed = new byte[3][];

    /**
     * {@link #encoded()} compressed with {@code codec} (issue #346), or null when it does not
     * shrink (send it raw). Like the encoding, compressed once and shared by every connection
     * that negotiated the same codec.
     */
    public byte[] compressed(int codec) {
        if (codec <= WireCompression.NONE || codec >= compressed.length) {
            return null;
        }
        byte[] bytes;
        synchronized (compressed) {
            bytes = compressed[codec];
        }
        if (bytes == null) {
            byte[] built = WireCompression.compress(codec, encoded());
            bytes = built != null ? built : NOT_SMALLER;
            synchronized (compressed) {
                if (compressed[codec] == null) {
                    compressed[codec] = bytes;
                } else {
                    bytes = compressed[codec]; // another event loop got there first
                }
            }
        }
        return bytes == NOT_SMALLER ? null : bytes;
    }
}
