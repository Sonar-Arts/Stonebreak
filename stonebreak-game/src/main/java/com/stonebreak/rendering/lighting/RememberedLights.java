package com.stonebreak.rendering.lighting;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The light sources in a world's unloaded chunks, so torches keep lighting the FastLOD
 * terrain those chunks hand over to. A loaded chunk's sources are indexed live (the
 * animated-block and furnace registries); those registries drop a chunk's entries when it
 * unloads, which is the moment a source is captured here. Reloading the chunk forgets them
 * again, because the live index takes over.
 *
 * <p>Only what was seen this session is known: a chunk saved with torches and not loaded
 * since the game started stays dark until it is. Thread-safe — chunk hooks may run off the
 * render thread.
 */
public final class RememberedLights {

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    /** A source frozen at unload: the cell it occupies and the point it emits from. */
    record Light(BlockPos pos, BlockLightSource source, float x, float y, float z) {}

    private final Map<Long, Light[]> byChunk = new ConcurrentHashMap<>();

    /**
     * Capture the chunk's sources; call before the world's registries drop them. The candidates
     * are the ones the renderer was lighting from those registries, so the remembered set is
     * exactly the live set the chunk leaves; the chunk itself, already out of the world, says
     * which still hold their block and where they emit from.
     */
    public void onChunkUnloaded(World world, Chunk chunk) {
        int baseX = chunk.getX() * CHUNK_SIZE;
        int baseZ = chunk.getZ() * CHUNK_SIZE;
        List<Light> lights = null;
        Vector3f emitter = new Vector3f();
        for (BlockLightSource source : BlockLightSource.VALUES) {
            for (BlockPos pos : source.candidates(world)) {
                int lx = pos.x() - baseX;
                int lz = pos.z() - baseZ;
                if (lx < 0 || lx >= CHUNK_SIZE || lz < 0 || lz >= CHUNK_SIZE) continue;
                if (!source.isBlock(chunk.getBlock(lx, pos.y(), lz))) continue;
                if (!source.rememberEmitter(chunk, lx, pos.y(), lz, pos, emitter)) continue;
                if (lights == null) lights = new ArrayList<>();
                lights.add(new Light(pos, source, emitter.x, emitter.y, emitter.z));
            }
        }
        long key = key(chunk.getX(), chunk.getZ());
        if (lights == null) {
            byChunk.remove(key);
        } else {
            byChunk.put(key, lights.toArray(Light[]::new));
        }
    }

    /** The chunk is live again; its sources are indexed by the world's registries. */
    public void onChunkLoaded(Chunk chunk) {
        byChunk.remove(key(chunk.getX(), chunk.getZ()));
    }

    /** Live view of every remembered chunk's sources. */
    Collection<Light[]> chunks() {
        return byChunk.values();
    }

    /** The remembered source occupying {@code pos}, or null. */
    Light find(BlockPos pos) {
        Light[] lights = byChunk.get(key(Math.floorDiv(pos.x(), CHUNK_SIZE), Math.floorDiv(pos.z(), CHUNK_SIZE)));
        if (lights == null) return null;
        for (Light light : lights) {
            if (light.pos().equals(pos)) return light;
        }
        return null;
    }

    int chunkCount() {
        return byChunk.size();
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }
}
