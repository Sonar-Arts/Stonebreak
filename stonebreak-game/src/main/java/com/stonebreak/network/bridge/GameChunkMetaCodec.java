package com.stonebreak.network.bridge;

import com.stonebreak.world.chunk.utils.LocalBlockKey;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Encodes the game-specific per-chunk metadata that rides alongside the engine
 * {@code VoxelChunkCodec} block payload in {@code ChunkDataS2C.metaPayload} — the engine
 * codec stays game-agnostic. Sparse and cheap: chunks with no tracked metadata encode to
 * an empty array (zero wire bytes beyond the length prefix).
 *
 * <pre>
 * version (byte) = 1
 * snowCount (varint-free int)
 *   repeated: localX u8, y u16, localZ u8, layers u8
 * blockStateCount (int)
 *   repeated: localX u8, y u16, localZ u8, stateLen u16, stateBytes utf-8
 * </pre>
 *
 * The leading version byte lets the blob grow (new trailing sections) without an engine or
 * packet-shape change — bump it and gate reads exactly like the chunk save codec.
 */
public final class GameChunkMetaCodec {

    private static final int VERSION = 1;
    private static final byte[] EMPTY = new byte[0];
    /** Sanity bound on entry counts (a chunk column holds 65 536 cells). */
    private static final int MAX_ENTRIES = 16 * 16 * 256;

    private GameChunkMetaCodec() {}

    /** Decoded metadata sections. Maps are keyed by {@link LocalBlockKey}. */
    public record ChunkMeta(Map<Integer, Integer> snowLayers, Map<Integer, String> blockStates) {
        public boolean isEmpty() {
            return snowLayers.isEmpty() && blockStates.isEmpty();
        }
    }

    /** Encode; returns an empty array when there is nothing to carry. */
    public static byte[] encode(Map<Integer, Integer> snowLayers, Map<Integer, String> blockStates) {
        if ((snowLayers == null || snowLayers.isEmpty())
                && (blockStates == null || blockStates.isEmpty())) {
            return EMPTY;
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeByte(VERSION);

            Map<Integer, Integer> snow = snowLayers != null ? snowLayers : Map.of();
            out.writeInt(snow.size());
            for (Map.Entry<Integer, Integer> e : snow.entrySet()) {
                int key = e.getKey();
                out.writeByte(LocalBlockKey.x(key));
                out.writeShort(LocalBlockKey.y(key));
                out.writeByte(LocalBlockKey.z(key));
                out.writeByte(Math.max(1, Math.min(8, e.getValue())));
            }

            Map<Integer, String> states = blockStates != null ? blockStates : Map.of();
            out.writeInt(states.size());
            for (Map.Entry<Integer, String> e : states.entrySet()) {
                int key = e.getKey();
                out.writeByte(LocalBlockKey.x(key));
                out.writeShort(LocalBlockKey.y(key));
                out.writeByte(LocalBlockKey.z(key));
                byte[] state = e.getValue().getBytes(StandardCharsets.UTF_8);
                out.writeShort(state.length);
                out.write(state);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e); // ByteArrayOutputStream cannot actually throw
        }
    }

    /** Decode; an empty/null payload yields empty maps. */
    public static ChunkMeta decode(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) {
            return new ChunkMeta(Map.of(), Map.of());
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        int version = in.readUnsignedByte();
        if (version < 1 || version > VERSION) {
            throw new IOException("Unsupported chunk-meta version: " + version);
        }

        int snowCount = in.readInt();
        if (snowCount < 0 || snowCount > MAX_ENTRIES) {
            throw new IOException("Invalid chunk-meta snow count: " + snowCount);
        }
        Map<Integer, Integer> snow = new HashMap<>(Math.max(snowCount, 0));
        for (int i = 0; i < snowCount; i++) {
            int x = in.readUnsignedByte();
            int y = in.readUnsignedShort();
            int z = in.readUnsignedByte();
            int layers = in.readUnsignedByte();
            snow.put(LocalBlockKey.pack(x, y, z), Math.max(1, Math.min(8, layers)));
        }

        int stateCount = in.readInt();
        if (stateCount < 0 || stateCount > MAX_ENTRIES) {
            throw new IOException("Invalid chunk-meta state count: " + stateCount);
        }
        Map<Integer, String> states = new HashMap<>(Math.max(stateCount, 0));
        for (int i = 0; i < stateCount; i++) {
            int x = in.readUnsignedByte();
            int y = in.readUnsignedShort();
            int z = in.readUnsignedByte();
            int len = in.readUnsignedShort();
            byte[] bytes = in.readNBytes(len);
            if (bytes.length != len) {
                throw new IOException("Incomplete chunk-meta state string");
            }
            states.put(LocalBlockKey.pack(x, y, z), new String(bytes, StandardCharsets.UTF_8));
        }
        return new ChunkMeta(snow, states);
    }
}
