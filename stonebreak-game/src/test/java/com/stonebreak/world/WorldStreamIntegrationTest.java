package com.stonebreak.world;

import com.openmason.engine.voxel.world.BlockFlags;
import com.openmason.engine.voxel.world.CubePos;
import com.openmason.engine.voxel.world.StreamCenter;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import com.stonebreak.world.generation.NativeTerrain;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.physics.WorldPhysics;
import com.stonebreak.world.voxel.StonebreakBlockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Headless end-to-end of the authoritative world (#447): the native generator behind the engine streamer
 * with a cube store, one viewer, ticked on this (owner) thread until the surface band around the viewer is
 * installed and READY; then surface/top/known-air answers, an edit, and the edit surviving a save + reopen.
 * Needs libstonebreak_worldgen (SBW ABI 3) - skipped without it.
 */
class WorldStreamIntegrationTest {

    private static final long SEED = 12345L;
    private static final long TIMEOUT_NANOS = 120_000_000_000L;

    private static World open(Path dir) {
        WorldConfiguration config = new WorldConfiguration(4, 4);
        return World.createHeadless(config, SEED, TerrainGeneratorType.STANDARD, TerrainScalePreset.ONE_TO_FOUR, dir);
    }

    private static void tickUntil(World world, BooleanSupplier done, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        while (!done.getAsBoolean()) {
            world.tickStream();
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what + "; stats " + world.streamStats());
            }
            Thread.sleep(2);
        }
    }

    @Test
    void surfaceBandStreamsInAndEditsPersist(@TempDir Path dir) throws Exception {
        assumeTrue(NativeTerrain.isAvailable(), () -> "worldgen natives: "
            + com.stonebreak.world.generation.NativeCubeGenerator.unavailableReason());

        World world = open(dir);
        long x = 7, z = 9;
        long top;
        try {
            Set<CubePos> installed = new HashSet<>();
            world.addInstallListener((w, cube) -> installed.add(cube.pos()));
            long guess = world.topY(x, z); // generator answer before anything loads
            assertTrue(guess > WorldConfiguration.MIN_Y && guess < WorldConfiguration.MAX_Y, "top " + guess);
            StreamCenter center = new StreamCenter(x, guess, z, 48, 48, World.BAND_BELOW, World.BAND_ABOVE);
            world.setStreamCenters(List.of(center));

            tickUntil(world, () -> world.isColumnReadyAt(x, z) && world.isColumnReadyAt(x + 32, z)
                && world.isColumnReadyAt(x, z - 32), "the viewer's columns to be READY");

            top = world.topY(x, z);
            long surface = world.surfaceY(x, z);
            assertTrue(world.isLoaded(x, top - 1, z), "the band holds the column's top cube");
            assertTrue(surface <= top);
            BlockType topBlock = world.getBlockAt(x, top - 1, z);
            assertTrue(topBlock != BlockType.AIR, "TOP - 1 is not air");
            for (long y = top; y < top + 40; y++) {
                assertEquals(BlockType.AIR, world.getBlockAt(x, y, z), "above TOP at y " + y);
            }
            BlockType sky = world.getBlockAt(x, surface - 1, z);
            assertTrue((StonebreakBlockTable.flags()[sky.getId()] & BlockFlags.SKY_OPAQUE) != 0,
                "block under the sky surface stops sky light: " + sky);
            assertTrue(world.isKnown(x, WorldConfiguration.MAX_Y - 10, z), "high air is known air");
            assertTrue(!installed.isEmpty() && world.getCubeAt(x, top - 1, z) != null,
                "INSTALLED events created cube objects");

            assertTrue(world.setBlockAt(x, top, z, BlockType.BRICKS_BLOCK));
            assertEquals(BlockType.BRICKS_BLOCK, world.getBlockAt(x, top, z));
            assertEquals(top + 1, world.topY(x, z));
            assertEquals(top + 1, world.surfaceY(x, z));
            world.saveAll();
        } finally {
            world.cleanup();
        }

        World again = open(dir);
        try {
            long t = top;
            again.setStreamCenters(List.of(new StreamCenter(x, t, z, 48, 48, World.BAND_BELOW, World.BAND_ABOVE)));
            tickUntil(again, () -> again.isLoaded(x, t, z), "the edited cube to load from the store");
            assertEquals(BlockType.BRICKS_BLOCK, again.getBlockAt(x, t, z), "the edit came back from the store");
            assertEquals(t + 1, again.topY(x, z));
        } finally {
            again.cleanup();
        }
    }

    /**
     * Building above the terrain: a SERVER write into a cube wholly in known air creates the cube (the stream
     * never loads all-air cubes); a cube with unknown cells (below some column top, not loaded) is never made
     * as air - that would wipe terrain still streaming.
     */
    @Test
    void writesIntoKnownAirMakeTheCubeButNeverOverUnknownCells(@TempDir Path dir) throws Exception {
        assumeTrue(NativeTerrain.isAvailable(), () -> "worldgen natives: "
            + com.stonebreak.world.generation.NativeCubeGenerator.unavailableReason());
        World world = open(dir);
        try {
            long x = 7, z = 9;
            long guess = world.topY(x, z);
            world.setStreamCenters(List.of(new StreamCenter(x, guess, z, 48, 48, World.BAND_BELOW, World.BAND_ABOVE)));
            tickUntil(world, () -> world.isColumnReadyAt(x, z), "the viewer's column to be READY");

            long cx = x >> 5, cz = z >> 5;
            long topMax = world.voxels().topRange(cx, cz).topMax();
            long skyCy = Math.floorDiv(topMax, 32) + 3; // three cubes above the highest top of the footprint
            long y = skyCy * 32 + 5;
            assertTrue(!world.hasCube(cx, skyCy, cz) && world.isCubeKnownAir(cx, skyCy, cz), "sky cube is known air");
            assertTrue(world.setBlockAt(x, y, z, BlockType.BRICKS_BLOCK), "a write into known air applies");
            assertTrue(world.hasCube(cx, skyCy, cz));
            assertEquals(BlockType.BRICKS_BLOCK, world.getBlockAt(x, y, z));
            world.tickStream(); // the stream adopts the host-made cube
            assertTrue(world.getCube(cx, skyCy, cz) != null);

            // Far outside every viewer: the terrain cube under the generator's top is unknown, never made as air.
            long fx = 100_000, fz = -100_000;
            long ftop = world.topY(fx, fz);
            long fcy = Math.floorDiv(ftop - 1, 32);
            assertTrue(!world.isKnown(fx, ftop - 1, fz), "unloaded terrain is unknown");
            assertTrue(!world.isCubeKnownAir(fx >> 5, fcy, fz >> 5));
            assertTrue(!world.setBlockAt(fx, ftop - 1, fz, BlockType.BRICKS_BLOCK), "no write over unknown cells");
            assertTrue(!world.hasCube(fx >> 5, fcy, fz >> 5));
        } finally {
            world.cleanup();
        }
    }

    /** Ticks until nothing is generating, populating, installing or meshing (the world stops changing). */
    private static void tickUntilQuiet(World world, String what) throws InterruptedException {
        tickUntil(world, () -> {
            var st = world.streamStats();
            return st.cubesGenerating() == 0 && st.populating() == 0 && st.installsPending() == 0
                && st.cubesMeshing() == 0 && st.meshesPending() == 0 && st.planPending() == 0;
        }, what);
        world.tickStream(); // dispatch the last CHANGED events
    }

    /**
     * The server stream builds no meshes ({@code CubeStream.NO_MESH}) and keeps physics fresh through
     * CV_EV_CHANGED: every cube's physics bricks are cached the moment it is INSTALLED, the populations that
     * run AFTER that (trees and features crossing into installed neighbours) change cells under the cache, and
     * the cached answers must still equal a fresh physics world's once the stream is quiet. Also: no tick
     * installs more than the default budget.
     */
    @Test
    void noMeshServerStreamInvalidatesPhysicsOnChanged(@TempDir Path dir) throws Exception {
        assumeTrue(NativeTerrain.isAvailable(), () -> "worldgen natives: "
            + com.stonebreak.world.generation.NativeCubeGenerator.unavailableReason());
        World world = open(dir);
        try {
            int[] meshes = {0};
            int[] installsThisTick = {0};
            Set<CubePos> primed = new HashSet<>();
            Set<CubePos> changed = new HashSet<>();
            world.addMeshConsumer((w, mesh) -> meshes[0]++);
            world.addChangeListener((w, cube) -> changed.add(cube.pos()));
            world.addInstallListener((w, cube) -> {
                installsThisTick[0]++;
                primed.add(cube.pos());
                for (int b = 0; b < 8; b++) { // cache all eight 16^3 physics bricks of the cube
                    w.physics().cellTop(cube.minX() + 8 + (b & 1) * 16, cube.minY() + 8 + ((b >> 1) & 1) * 16,
                        cube.minZ() + 8 + ((b >> 2) & 1) * 16, WorldPhysics.MOB_MASK);
                }
            });
            long x = 7, z = 9;
            world.setStreamCenters(List.of(new StreamCenter(x, world.topY(x, z), z, 48, 48,
                World.BAND_BELOW, World.BAND_ABOVE)));
            int maxInstalls = 0;
            long deadline = System.nanoTime() + TIMEOUT_NANOS;
            while (!(world.isColumnReadyAt(x, z) && world.isColumnReadyAt(x + 32, z)
                    && world.isColumnReadyAt(x, z - 32))) {
                installsThisTick[0] = 0;
                world.tickStream();
                maxInstalls = Math.max(maxInstalls, installsThisTick[0]);
                assertTrue(System.nanoTime() < deadline, "columns READY; stats " + world.streamStats());
                Thread.sleep(2);
            }
            tickUntilQuiet(world, "the stream to settle");

            assertTrue(maxInstalls > 0 && maxInstalls <= World.DEFAULT_MAX_INSTALLS, "installs per tick " + maxInstalls);
            assertEquals(0, meshes[0], "a NO_MESH stream hands out no meshes");
            assertEquals(0, world.streamStats().meshedTotal(), "a NO_MESH stream builds no meshes");
            changed.retainAll(primed);
            assertTrue(!changed.isEmpty(), "populations changed installed cubes (CV_EV_CHANGED)");

            List<String> stale = new ArrayList<>();
            try (WorldPhysics fresh = WorldPhysics.create(world)) {
                WorldPhysics cached = world.physics();
                for (CubePos c : changed) {
                    if (!world.hasCube(c.x(), c.y(), c.z())) continue;
                    long x0 = c.x() << 5, y0 = c.y() << 5, z0 = c.z() << 5;
                    for (int i = 0; i < 32 * 32 * 32 && stale.size() < 5; i++) {
                        long bx = x0 + (i & 31), by = y0 + ((i >> 5) & 31), bz = z0 + (i >> 10);
                        float a = cached.cellTop(bx, by, bz, WorldPhysics.MOB_MASK);
                        float b = fresh.cellTop(bx, by, bz, WorldPhysics.MOB_MASK);
                        if (a != b) stale.add("(" + bx + ", " + by + ", " + bz + ") cached " + a + " fresh " + b);
                    }
                }
            }
            assertEquals(List.of(), stale, "physics cache stale after populations (" + changed.size() + " changed cubes)");
        } finally {
            world.cleanup();
        }
    }

    /**
     * {@link World#rebuildAllLoadedChunks()} (the leaf-transparency / face-rule trigger) re-applies the tables
     * and re-meshes every installed cube with geometry ({@code CubeStream.remeshAll}). Uses a meshing headless
     * world (the chunk-lab mode).
     */
    @Test
    void rebuildRemeshesEveryInstalledCube() throws Exception {
        assumeTrue(NativeTerrain.isAvailable(), () -> "worldgen natives: "
            + com.stonebreak.world.generation.NativeCubeGenerator.unavailableReason());
        World world = World.createHeadless(new WorldConfiguration(4, 4), SEED, TerrainGeneratorType.STANDARD,
            TerrainScalePreset.ONE_TO_FOUR, null, true);
        try {
            Set<CubePos> withGeometry = new HashSet<>();
            Set<CubePos> meshed = new HashSet<>();
            world.addMeshConsumer((w, mesh) -> {
                CubePos p = new CubePos(mesh.cx(), mesh.cy(), mesh.cz());
                meshed.add(p);
                if (mesh.isEmpty()) withGeometry.remove(p); else withGeometry.add(p);
            });
            long x = 7, z = 9;
            world.setStreamCenters(List.of(new StreamCenter(x, world.topY(x, z), z, 48, 48,
                World.BAND_BELOW, World.BAND_ABOVE)));
            tickUntil(world, () -> world.isColumnReadyAt(x, z), "the viewer's column to be READY");
            tickUntilQuiet(world, "the stream to settle");
            Set<CubePos> before = new HashSet<>(withGeometry);
            assertTrue(!before.isEmpty(), "terrain meshes");

            meshed.clear();
            world.rebuildAllLoadedChunks();
            tickUntilQuiet(world, "the re-mesh");
            Set<CubePos> missing = new HashSet<>(before);
            missing.removeAll(meshed);
            assertEquals(Set.of(), missing, "cubes with geometry not re-meshed by rebuildAllLoadedChunks");
        } finally {
            world.cleanup();
        }
    }
}
