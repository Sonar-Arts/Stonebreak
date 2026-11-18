package com.stonebreak.rendering.lighting;

import com.openmason.engine.rendering.lighting.IndirectLightAtlas;
import com.openmason.engine.voxel.lighting.IndirectLightVolume;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.door.DoorState;
import com.stonebreak.world.World;
import com.stonebreak.world.voxel.BlockIds;
import com.stonebreak.world.lighting.BlockOpacity;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;

/** Cached voxel bounce volumes. Never creates chunks; unloaded space blocks light and reflects none. */
public final class TorchIndirectLighting implements AutoCloseable {
    private static final int SIZE = IndirectLightAtlas.SIZE;
    private static final int HALF = SIZE / 2;
    private final Entry[] entries = new Entry[PointLightGlsl.MAX_LIGHTS];
    private final Vector3f position = new Vector3f();
    private IndirectLightAtlas atlas;
    private World previousWorld;

    static final class Entry {
        private static final int MISSING_ID = 0xFFFF;
        final IndirectLightVolume volume = new IndirectLightVolume(SIZE);
        final short[] ids = new short[SIZE * SIZE * SIZE];
        long revision = Long.MIN_VALUE;
        int originX, originY, originZ;
        int qx = Integer.MIN_VALUE, qy, qz;

        boolean update(World world, Vector3f source) {
            // Quarter-block probes keep carried-light updates bounded and avoid jitter.
            int nx = (int) Math.floor(source.x * 4), ny = (int) Math.floor(source.y * 4), nz = (int) Math.floor(source.z * 4);
            boolean sourceChanged = nx != qx || ny != qy || nz != qz;
            // The native world revision is monotonic over every block/layer write and cube install/unload
            // (cv_world_revision), so it subsumes the old per-chunk metadata/mesh/revision identities.
            long rev = world.voxels().worldRevision();
            if (!sourceChanged && rev == revision) return false;
            revision = rev;
            qx = nx; qy = ny; qz = nz;
            originX = (int) Math.floor(source.x) - HALF;
            originY = (int) Math.floor(source.y) - HALF;
            originZ = (int) Math.floor(source.z) - HALF;
            world.voxels().readBox(originX, originY, originZ, SIZE, SIZE, SIZE, ids, MISSING_ID);
            boolean volumeChanged = sourceChanged;
            for (int z = 0; z < SIZE; z++) for (int x = 0; x < SIZE; x++) {
                int wx = originX + x, wz = originZ + z;
                // Cells of absent cubes at or above the column TOP are KNOWN AIR (readBox reports every
                // absent cube as missing): light passes there. One top query per column, only when needed.
                long columnTop = Long.MIN_VALUE;
                for (int y = 0; y < SIZE; y++) {
                    int wy = originY + y;
                    int id = Short.toUnsignedInt(ids[(y * SIZE + z) * SIZE + x]);
                    if (id == MISSING_ID && WorldConfiguration.inBounds(wy)) {
                        if (columnTop == Long.MIN_VALUE) columnTop = world.topY(wx, wz);
                        if (wy >= columnTop || world.isKnown(wx, wy, wz)) id = BlockType.AIR.getId();
                    }
                    boolean known = id != MISSING_ID && WorldConfiguration.inBounds(wy);
                    if (!known) { volumeChanged |= volume.setCell(x, y, z, true, false); continue; }
                    BlockType type = BlockIds.of(id);
                    boolean blocked = BlockOpacity.isOpaque(type);
                    if (type == BlockType.OAK_DOOR) {
                        blocked = !DoorState.parse(world.getBlockStateAt(wx, wy, wz)).isOpen();
                    } else if (type == BlockType.AIR && y > 0
                            && BlockIds.of(Short.toUnsignedInt(ids[((y - 1) * SIZE + z) * SIZE + x])) == BlockType.OAK_DOOR) {
                        // Door models occupy two vertical cells but are anchored in the lower one.
                        blocked = !DoorState.parse(world.getBlockStateAt(wx, wy - 1, wz)).isOpen();
                    }
                    volumeChanged |= volume.setCell(x, y, z, blocked, blocked);
                }
            }
            // Water flow, material swaps and edits outside this volume need no radiance rebuild/upload.
            if (!volumeChanged) return false;
            volume.rebuild((nx + .5f) / 4 - originX, (ny + .5f) / 4 - originY,
                    (nz + .5f) / 4 - originZ, PointLightGlsl.RADIUS);
            return true;
        }
    }

    public void update(World world) {
        if (world != previousWorld) { suspend(); previousWorld = world; }
        if (world == null || DynamicLights.count() == 0) { suspend(); return; }
        if (atlas == null) atlas = new IndirectLightAtlas();
        for (int i = 0; i < DynamicLights.count(); i++) {
            if (entries[i] == null) entries[i] = new Entry();
            DynamicLights.position(i, position);
            if (entries[i].update(world, position)) atlas.upload(i, entries[i].volume.data());
        }
        for (int i = DynamicLights.count(); i < entries.length; i++) entries[i] = null;
        atlas.bind(DynamicLights.INDIRECT_TEXTURE_UNIT);
        DynamicLights.enableIndirectLight();
    }

    public void suspend() { java.util.Arrays.fill(entries, null); previousWorld = null; }

    @Override public void close() {
        if (atlas != null) { atlas.close(); atlas = null; }
        suspend();
    }
}
