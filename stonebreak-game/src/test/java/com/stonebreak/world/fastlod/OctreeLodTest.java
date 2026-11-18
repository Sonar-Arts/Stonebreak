package com.stonebreak.world.fastlod;

import com.openmason.engine.voxel.mms.mmsCore.MmsLodQuadCodec;
import com.openmason.engine.voxel.world.CubeLod;
import com.openmason.engine.voxel.world.LodMesh;
import com.openmason.engine.voxel.world.LodPolicy;
import com.openmason.engine.voxel.world.LodStats;
import com.openmason.engine.voxel.world.StreamCenter;
import com.stonebreak.world.World;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import com.stonebreak.world.generation.NativeTerrain;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The game's octree LOD wiring over a real streamed world (headless): READY columns are never covered (no octree
 * node over them, no FALLBACK footprint), explored land beyond them gets octree nodes whose node-mode records stay
 * inside their node, unexplored land falls back to FastLOD, and releasing the shared cube store closes the LOD.
 */
class OctreeLodTest {

    private static final long SEED = 4242L;
    private static final long TIMEOUT_NANOS = 120_000_000_000L;

    private record Node(int level, long x, long y, long z) {
    }

    @Test
    void coversTheViewOnceAroundReadyDetail(@TempDir Path dir) throws Exception {
        assumeTrue(NativeTerrain.isAvailable(), "worldgen natives");
        assumeTrue(CubeLod.isAvailable(), "cv_lod");
        World world = World.createHeadless(new WorldConfiguration(4, 4), SEED, TerrainGeneratorType.STANDARD,
            TerrainScalePreset.ONE_TO_FOUR, dir.resolve("world"));
        OctreeLod lod = null;
        try {
            long x = 5, z = 3;
            long top = world.topY(x, z);
            world.setStreamCenters(List.of(new StreamCenter(x, top, z, 96, 64, World.BAND_BELOW, World.BAND_ABOVE)));
            long deadline = System.nanoTime() + TIMEOUT_NANOS;
            while (!(world.isColumnReadyAt(x, z) && world.isColumnReadyAt(x + 64, z) && world.isColumnReadyAt(x, z - 64))) {
                world.tickStream();
                assertTrue(System.nanoTime() < deadline, "stream READY timeout " + world.streamStats());
                Thread.sleep(2);
            }
            world.saveAll();

            lod = OctreeLod.create(world, world.cubeStore(), dir.resolve("lod"));
            assertNotNull(lod);
            Set<Node> drawn = new HashSet<>();
            int[] meshes = {0, 0};
            OctreeLod.NodeSink sink = new OctreeLod.NodeSink() {
                @Override
                public void show(int level, long nx, long ny, long nz, LodMesh mesh, boolean replace, boolean snap) {
                    drawn.add(new Node(level, nx, ny, nz));
                    assertEquals(1 << level, mesh.cellSize());
                    assertEquals(nx * (32L << level), mesh.originX());
                    if (mesh.quadCount() > 0) {
                        meshes[0]++;
                        checkRecords(mesh);
                    }
                    meshes[1] += mesh.waterCount();
                }

                @Override
                public void hide(int level, long nx, long ny, long nz) {
                    drawn.remove(new Node(level, nx, ny, nz));
                }
            };
            LodPolicy policy = OctreeLod.policy(48, 600, 64);
            deadline = System.nanoTime() + TIMEOUT_NANOS;
            int quiet = 0;
            while (quiet < 20) {
                world.tickStream();
                lod.tick(x, top, z, policy, 0, sink);
                LodStats s = lod.stats();
                quiet = s.idle() && lod.lastEventCount() == 0 ? quiet + 1 : 0;
                assertTrue(System.nanoTime() < deadline, "LOD idle timeout " + s);
                Thread.sleep(5);
            }
            assertFalse(drawn.isEmpty(), "octree nodes drawn around the explored area");
            assertTrue(meshes[0] > 0, "non-empty node meshes");
            assertTrue(lod.fallbackFootprints() > 0, "unexplored land falls back to FastLOD");

            // Never over READY detail: no fallback footprint and no drawn node over the viewer's READY column.
            long cx = Math.floorDiv(x, 32), cz = Math.floorDiv(z, 32);
            assertTrue(world.isColumnReady(cx, cz));
            assertFalse(lod.fallbackCovers((int) (cx * 2), (int) (cz * 2)), "fallback over a READY column");
            for (Node n : drawn) {
                boolean over = (cx >> n.level()) == n.x() && (cz >> n.level()) == n.z();
                assertFalse(over, "octree node " + n + " over the READY column");
            }
            // Far, never generated land is FastLOD's.
            assertTrue(lod.fallbackCovers(500 / 16, 0), "far unexplored column falls back");

            OctreeLod.releaseStore(world.cubeStore());
            assertTrue(lod.isClosed(), "releasing the shared store closes the LOD");
        } finally {
            if (lod != null) {
                lod.close();
            }
            world.cleanup();
        }
    }

    /** Every node-mode corner stays inside the node's 32^3 cells (+ the codec's plane extent). */
    private static void checkRecords(LodMesh mesh) {
        ByteBuffer quads = mesh.quads().asByteBuffer().order(ByteOrder.nativeOrder());
        for (int q = 0; q < mesh.quadCount(); q++) {
            int w0 = quads.getInt(q * MmsLodQuadCodec.QUAD_BYTES);
            assertTrue(MmsLodQuadCodec.face(w0) <= 5, "face");
            for (int c = 0; c < 4; c++) {
                for (int a = 0; a < 3; a++) {
                    int p = MmsLodQuadCodec.nodeCellPosition(quads, q, c, a);
                    assertTrue(p >= 0 && p <= 32, "corner " + p + " outside the node (axis " + a + ")");
                }
            }
        }
    }
}
