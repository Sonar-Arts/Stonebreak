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
}
