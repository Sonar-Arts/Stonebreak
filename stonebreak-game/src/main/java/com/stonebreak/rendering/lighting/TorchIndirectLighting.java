package com.stonebreak.rendering.lighting;

import com.openmason.engine.rendering.lighting.IndirectLightAtlas;
import com.openmason.engine.voxel.lighting.IndirectLightVolume;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.door.DoorState;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.lighting.BlockOpacity;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;

/** Cached voxel bounce volumes. Never creates chunks; unloaded space blocks light and reflects none. */
public final class TorchIndirectLighting implements AutoCloseable {
    private static final int SIZE = IndirectLightAtlas.SIZE;
    private static final int HALF = SIZE / 2;
    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    private final Entry[] entries = new Entry[PointLightGlsl.MAX_LIGHTS];
    private final Vector3f position = new Vector3f();
    private IndirectLightAtlas atlas;
    private World previousWorld;

    static final class Entry {
        final IndirectLightVolume volume = new IndirectLightVolume(SIZE);
        final Chunk[] chunks = new Chunk[9];
        final Object[] metadata = new Object[9];
        final Object[] meshes = new Object[9];
        final long[] revisions = new long[9];
        int originX, originY, originZ, minChunkX, minChunkZ, chunkColumns;
        int qx = Integer.MIN_VALUE, qy, qz;

        boolean update(World world, Vector3f source) {
            // Quarter-block probes keep carried-light updates bounded and avoid jitter.
            int nx = (int) Math.floor(source.x * 4), ny = (int) Math.floor(source.y * 4), nz = (int) Math.floor(source.z * 4);
            boolean sourceChanged = nx != qx || ny != qy || nz != qz;
            boolean changed = sourceChanged;
            originX = (int) Math.floor(source.x) - HALF;
            originY = (int) Math.floor(source.y) - HALF;
            originZ = (int) Math.floor(source.z) - HALF;
            minChunkX = Math.floorDiv(originX, CHUNK);
            minChunkZ = Math.floorDiv(originZ, CHUNK);
            int maxChunkX = Math.floorDiv(originX + SIZE - 1, CHUNK);
            int maxChunkZ = Math.floorDiv(originZ + SIZE - 1, CHUNK);
            chunkColumns = maxChunkX - minChunkX + 1;
            int at = 0;
            for (int z = minChunkZ; z <= maxChunkZ; z++) for (int x = minChunkX; x <= maxChunkX; x++) {
                Chunk chunk = world.getChunkIfLoaded(x, z);
                Object state = chunk == null ? null : chunk.getCcoMetadata();
                Object mesh = chunk == null ? null : chunk.getRegionAtlasHandle();
                long revision = chunk == null || chunk.getCcoDirtyTracker() == null ? -1
                        : chunk.getCcoDirtyTracker().blockRevision();
                changed |= chunks[at] != chunk || metadata[at] != state || meshes[at] != mesh || revisions[at] != revision;
                chunks[at] = chunk; metadata[at] = state; meshes[at] = mesh;
                revisions[at] = revision;
                at++;
            }
            // The revision catches same-millisecond edits and door toggles even before remeshing.
            // Metadata/mesh identities additionally catch snapshots and chunk population.
            if (!changed) return false;
            qx = nx; qy = ny; qz = nz;
            boolean volumeChanged = sourceChanged;
            for (int z = 0; z < SIZE; z++) for (int x = 0; x < SIZE; x++) {
                int wx = originX + x, wz = originZ + z;
                Chunk chunk = chunks[(Math.floorDiv(wz, CHUNK) - minChunkZ) * chunkColumns
                        + Math.floorDiv(wx, CHUNK) - minChunkX];
                for (int y = 0; y < SIZE; y++) {
                    int wy = originY + y;
                    boolean known = chunk != null && wy >= 0 && wy < WorldConfiguration.WORLD_HEIGHT;
                    if (!known) { volumeChanged |= volume.setCell(x, y, z, true, false); continue; }
                    int lx = Math.floorMod(wx, CHUNK), lz = Math.floorMod(wz, CHUNK);
                    BlockType type = chunk.getBlock(lx, wy, lz);
                    boolean blocked = BlockOpacity.isOpaque(type);
                    if (type == BlockType.OAK_DOOR) {
                        blocked = !DoorState.parse(chunk.getBlockState(lx, wy, lz)).isOpen();
                    } else if (type == BlockType.AIR && wy > 0 && chunk.getBlock(lx, wy - 1, lz) == BlockType.OAK_DOOR) {
                        // Door models occupy two vertical cells but are anchored in the lower one.
                        blocked = !DoorState.parse(chunk.getBlockState(lx, wy - 1, lz)).isOpen();
                    }
                    volumeChanged |= volume.setCell(x, y, z, blocked, blocked);
                }
            }
            // Water flow, material swaps and edits outside this volume need no radiance rebuild/upload.
            if (!volumeChanged) return false;
            volume.rebuild((nx + .5f) / 4 - originX, (ny + .5f) / 4 - originY,
                    (nz + .5f) / 4 - originZ, TorchLight.RADIUS);
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
