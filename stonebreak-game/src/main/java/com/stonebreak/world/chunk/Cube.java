package com.stonebreak.world.chunk;

import com.openmason.engine.voxel.world.CubePos;
import com.openmason.engine.voxel.world.CubeWorld;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.voxel.VoxelLayers;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One loaded 32^3 cube of a {@link World}, as the GAME sees it (Stonebreak #447). The blocks and per-cell
 * layers (water, block-state ids, snow) live in the native {@link CubeWorld}; this object only holds what
 * the engine cannot: the full text of payload-carrying block states (furnace inventories, crafting grids -
 * the STATE layer holds just their mesh-variant id), named blob sections other systems persist with the
 * cube (entities: 3B-S/3B-E), and slots for render / entity bookkeeping.
 *
 * <p>Created by the world when the stream reports the cube INSTALLED (or on demand in a detached test
 * world), dropped when it is UNLOADED. Coordinates are {@code long} cube coordinates
 * ({@code cx = x >> 5}). Thread-safe for reads; mutations happen on the world's owner thread.
 *
 * <p><b>Blob.</b> {@link #encodeBlob()} = {@code u32 'SBC1' | u16 n | n x (u16 nameLen, name utf8, u32 len,
 * bytes)}; section {@code "states"} = the payload states ({@code u32 n | n x (u16 cell, u32 len, utf8)}).
 * Other sections are opaque bytes owned by whoever put them ({@link #putBlobSection}).
 */
public final class Cube {

    public static final int EDGE = CubeWorld.CUBE_EDGE;
    private static final int BLOB_MAGIC = 0x53424331; // "SBC1"
    public static final String SECTION_STATES = "states";

    private final World world;
    private final CubePos pos;
    /** Cell index ({@link CubeWorld#cellIndex}) -> full state string, for states carrying payload. */
    private final Map<Integer, String> payloadStates = new ConcurrentHashMap<>();
    /** Named blob sections other systems keep with the cube (3B-S / 3B-E). */
    private final Map<String, byte[]> blobSections = new ConcurrentHashMap<>();
    private volatile boolean payloadDirty;
    private volatile Object renderState;
    private volatile Object entityState;
    private volatile boolean entitiesGenerated;

    public Cube(World world, CubePos pos) {
        this.world = world;
        this.pos = pos;
    }

    public World world() {
        return world;
    }

    public CubePos pos() {
        return pos;
    }

    public long cx() {
        return pos.x();
    }

    public long cy() {
        return pos.y();
    }

    public long cz() {
        return pos.z();
    }

    /** Lowest block coordinates of the cube. */
    public long minX() {
        return pos.minBlockX();
    }

    public long minY() {
        return pos.minBlockY();
    }

    public long minZ() {
        return pos.minBlockZ();
    }

    /** True when block (x, y, z) lies in this cube. */
    public boolean contains(long x, long y, long z) {
        return (x >> 5) == pos.x() && (y >> 5) == pos.y() && (z >> 5) == pos.z();
    }

    /** The cube-local cell index of a world block inside this cube. */
    public static int cellOf(long x, long y, long z) {
        return CubeWorld.cellIndex((int) (x & 31), (int) (y & 31), (int) (z & 31));
    }

    public static int localX(int cell) {
        return cell & 31;
    }

    public static int localZ(int cell) {
        return (cell >> 5) & 31;
    }

    public static int localY(int cell) {
        return (cell >> 10) & 31;
    }

    // ---- reads through the world ------------------------------------------------------------------------------

    /** Block at cube-local (lx, ly, lz). */
    public BlockType getBlock(int lx, int ly, int lz) {
        return world.getBlockAt(minX() + lx, minY() + ly, minZ() + lz);
    }

    /** Block state at cube-local (lx, ly, lz), as {@link World#getBlockStateAt}. */
    public String getBlockState(int lx, int ly, int lz) {
        return world.getBlockStateAt(minX() + lx, minY() + ly, minZ() + lz);
    }

    /** Visitor for {@link #forEachState}: world coordinates + the full state string. */
    @FunctionalInterface
    public interface StateVisitor {
        void accept(long x, long y, long z, String state);
    }

    /**
     * Every cell of this cube that carries a block state (STATE layer non-zero, or a payload state), with its
     * full state string. Cheap for cubes without states (the layer scan checks palettes first).
     */
    public void forEachState(StateVisitor visitor) {
        CubeWorld.LayerCells cells = world.voxels().scanLayer(VoxelLayers.STATE, pos.x(), pos.y(), pos.z());
        java.util.Set<Integer> seen = payloadStates.isEmpty() ? null : new java.util.HashSet<>();
        if (cells != null) {
            for (int i = 0; i < cells.count(); i++) {
                int cell = Short.toUnsignedInt(cells.index()[i]);
                String raw = payloadStates.get(cell);
                if (raw == null) {
                    raw = world.blockStates().nameOf(Short.toUnsignedInt(cells.value()[i]));
                } else {
                    seen.add(cell);
                }
                if (raw != null) {
                    visitor.accept(minX() + localX(cell), minY() + localY(cell), minZ() + localZ(cell), raw);
                }
            }
        }
        if (seen == null) {
            return;
        }
        for (Map.Entry<Integer, String> e : payloadStates.entrySet()) {
            if (!seen.contains(e.getKey())) {
                int cell = e.getKey();
                visitor.accept(minX() + localX(cell), minY() + localY(cell), minZ() + localZ(cell), e.getValue());
            }
        }
    }

    // ---- payload states (World internals) ------------------------------------------------------------------

    /** The payload state string of a cell, or null. */
    public String payloadState(int cell) {
        return payloadStates.get(cell);
    }

    /** Sets (non-null) or clears (null) a cell's payload state; marks the cube's game payload dirty. */
    public void setPayloadState(int cell, String raw) {
        String previous = raw == null ? payloadStates.remove(cell) : payloadStates.put(cell, raw);
        if (!java.util.Objects.equals(previous, raw)) {
            payloadDirty = true;
        }
    }

    /** Read-only view of the payload states (cell index -> string). */
    public Map<Integer, String> payloadStates() {
        return Collections.unmodifiableMap(payloadStates);
    }

    /** True when payload states or blob sections changed since the last {@link #encodeBlob}. */
    public boolean isPayloadDirty() {
        return payloadDirty;
    }

    public void markPayloadDirty() {
        payloadDirty = true;
    }

    // ---- blob sections ------------------------------------------------------------------------------------

    /** Sets a named blob section (null removes it); persisted with the cube at the next save/unload. */
    public void putBlobSection(String name, byte[] bytes) {
        if (bytes == null) {
            if (blobSections.remove(name) != null) payloadDirty = true;
        } else {
            byte[] previous = blobSections.put(name, bytes);
            if (previous == null || !java.util.Arrays.equals(previous, bytes)) {
                payloadDirty = true; // an unchanged section does not re-save the cube at every autosave
            }
        }
    }

    public byte[] blobSection(String name) {
        return blobSections.get(name);
    }

    /** The cube's blob bytes (payload states + sections); empty array when there is nothing to keep. */
    public byte[] encodeBlob() {
        payloadDirty = false;
        Map<String, byte[]> sections = new LinkedHashMap<>();
        if (!payloadStates.isEmpty()) {
            sections.put(SECTION_STATES, encodeStates(payloadStates));
        }
        blobSections.forEach((k, v) -> {
            if (!SECTION_STATES.equals(k)) sections.put(k, v);
        });
        if (sections.isEmpty()) {
            return new byte[0];
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(BLOB_MAGIC);
            out.writeShort(sections.size());
            for (Map.Entry<String, byte[]> e : sections.entrySet()) {
                byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
                out.writeShort(name.length);
                out.write(name);
                out.writeInt(e.getValue().length);
                out.write(e.getValue());
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Restores payload states and sections from blob bytes (unknown/corrupt blobs are ignored). */
    public void decodeBlob(byte[] blob) {
        payloadStates.clear();
        blobSections.clear();
        payloadDirty = false;
        if (blob == null || blob.length < 6) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(blob))) {
            if (in.readInt() != BLOB_MAGIC) {
                return;
            }
            int n = in.readUnsignedShort();
            for (int i = 0; i < n; i++) {
                byte[] name = new byte[in.readUnsignedShort()];
                in.readFully(name);
                byte[] data = new byte[in.readInt()];
                in.readFully(data);
                String key = new String(name, StandardCharsets.UTF_8);
                if (SECTION_STATES.equals(key)) {
                    payloadStates.putAll(decodeStates(data));
                } else {
                    blobSections.put(key, data);
                }
            }
        } catch (IOException | RuntimeException e) {
            payloadStates.clear();
            blobSections.clear();
        }
    }

    private static byte[] encodeStates(Map<Integer, String> states) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(states.size());
            for (Map.Entry<Integer, String> e : states.entrySet()) {
                byte[] s = e.getValue().getBytes(StandardCharsets.UTF_8);
                out.writeShort(e.getKey());
                out.writeInt(s.length);
                out.write(s);
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<Integer, String> decodeStates(byte[] data) throws IOException {
        Map<Integer, String> out = new LinkedHashMap<>();
        try (DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(data))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                int cell = in.readUnsignedShort();
                byte[] s = new byte[in.readInt()];
                in.readFully(s);
                out.put(cell, new String(s, StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    // ---- game-side slots ------------------------------------------------------------------------------------

    /** Renderer-owned per-cube state (region handles etc., 3B-R). */
    public Object renderState() {
        return renderState;
    }

    public void setRenderState(Object state) {
        this.renderState = state;
    }

    /** Entity-system-owned per-cube state (3B-E). */
    public Object entityState() {
        return entityState;
    }

    public void setEntityState(Object state) {
        this.entityState = state;
    }

    /** True once the initial mob population of this cube ran (persist through a blob section: 3B-E). */
    public boolean areEntitiesGenerated() {
        return entitiesGenerated;
    }

    public void setEntitiesGenerated(boolean generated) {
        this.entitiesGenerated = generated;
    }

    @Override
    public String toString() {
        return "Cube" + pos;
    }
}
