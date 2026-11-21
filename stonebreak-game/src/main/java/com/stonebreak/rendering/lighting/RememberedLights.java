package com.stonebreak.rendering.lighting;

import com.openmason.engine.util.BlockPos;
import com.openmason.engine.voxel.world.CubePos;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Cube;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The light sources in a world's unloaded cubes, so torches keep lighting the LOD terrain those
 * cubes hand over to. A loaded cube's sources are indexed live (the animated-block and furnace
 * registries); those registries drop a cube's entries when it unloads, so a source is captured
 * here just before, while the cube is still resident ({@link #onCubeUnloadPending}). Installing the
 * cube again forgets them, because the live index takes over.
 *
 * <p>Only what was seen this session is known: a cube saved with torches and not loaded since the
 * game started stays dark until it is. Thread-safe — cube hooks may run off the render thread.
 */
public final class RememberedLights {

    /** A source frozen at unload: the cell it occupies and the point it emits from. */
    record Light(BlockPos pos, BlockLightSource source, float x, float y, float z) {}

    private final Map<CubePos, Light[]> byCube = new ConcurrentHashMap<>();

    /**
     * Capture the cube's sources; call while it is still resident, before the world's registries
     * drop them. The candidates are the ones the renderer was lighting from those registries, so the
     * remembered set is exactly the live set the cube leaves.
     */
    public void onCubeUnloadPending(World world, Cube cube) {
        List<Light> lights = null;
        Vector3f emitter = new Vector3f();
        for (BlockLightSource source : BlockLightSource.VALUES) {
            for (BlockPos pos : source.candidates(world)) {
                if (!cube.contains(pos.x(), pos.y(), pos.z())) continue;
                if (!source.isBlock(world.getBlockAt(pos.x(), pos.y(), pos.z()))) continue;
                if (!source.rememberEmitter(world, pos, emitter)) continue;
                if (lights == null) lights = new ArrayList<>();
                lights.add(new Light(pos, source, emitter.x, emitter.y, emitter.z));
            }
        }
        if (lights == null) {
            byCube.remove(cube.pos());
        } else {
            byCube.put(cube.pos(), lights.toArray(Light[]::new));
        }
    }

    /** The cube is live again; its sources are indexed by the world's registries. */
    public void onCubeInstalled(Cube cube) {
        byCube.remove(cube.pos());
    }

    /** Live view of every remembered cube's sources. */
    Collection<Light[]> chunks() {
        return byCube.values();
    }

    /** The remembered source occupying {@code pos}, or null. */
    Light find(BlockPos pos) {
        Light[] lights = byCube.get(CubePos.ofBlock(pos.x(), pos.y(), pos.z()));
        if (lights == null) return null;
        for (Light light : lights) {
            if (light.pos().equals(pos)) return light;
        }
        return null;
    }

    int chunkCount() {
        return byCube.size();
    }
}
