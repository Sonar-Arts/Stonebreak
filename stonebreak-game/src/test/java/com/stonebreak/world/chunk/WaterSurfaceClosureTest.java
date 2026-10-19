package com.stonebreak.world.chunk;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.core.CcoChunkData;
import com.openmason.engine.voxel.cco.data.CcoChunkMetadata;
import com.openmason.engine.voxel.mms.mmsCore.MmsBufferLayout;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsTexturing.MmsTextureMapper;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.FlowWorld;
import com.stonebreak.blocks.waterSystem.WaterSim;
import com.stonebreak.world.TestWorld;
import com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration.MmsCcoAdapter;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The water mesh must be a closed surface: every edge of every water triangle
 * either meets another water triangle, lies against a solid block, or is the
 * bottom of a side face deliberately sealed down into the water under it
 * (MmsWaterGenerator#sealSideBottomToNeighborSurface). An edge that does none
 * of those is a hole you can see the terrain through.
 *
 * <p>Regression: a falling column landing in a pool of sources had its corner
 * heights pulled down by the "waterfall edge pull" while the cell above still
 * started its side faces at full height — a slit into the column at every
 * waterfall that hits a lake or river.
 *
 * <p>Fully headless (real MmsCcoAdapter over a hand-built chunk). Checks the
 * meshed rest geometry; the shader's wave displacement is not modelled.
 */
public class WaterSurfaceClosureTest {

    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    private static final int POOL_Y = 11;

    @Test
    void fallingColumnIntoSourcePoolLeavesNoHole() {
        Chunk chunk = floorChunk(10);
        // A 7×7 pool of sources on the floor, a falling column landing in its middle.
        for (int x = 4; x <= 10; x++) {
            for (int z = 4; z <= 10; z++) {
                chunk.setBlock(x, POOL_Y, z, BlockType.WATER);
            }
        }
        chunk.setBlock(7, 15, 7, BlockType.WATER);
        for (int y = POOL_Y + 1; y < 15; y++) {
            chunk.setBlock(7, y, 7, BlockType.WATER);
            chunk.getWaterLayer().set(7, y, 7, ChunkWaterLayer.FALLING);
        }
        assertClosed(chunk);
    }

    @Test
    void simulatedCascadeOverPoolLeavesNoHole() {
        // Steps down in +Z between two walls, a source pool at the bottom,
        // water poured in at the top and run to rest by the real sim.
        Chunk chunk = new Chunk(0, 0);
        int[] top = new int[CHUNK];
        for (int z = 0; z < CHUNK; z++) {
            top[z] = z < 4 ? 14 : z < 7 ? 13 : z < 10 ? 12 : 11;
        }
        for (int x = 0; x < CHUNK; x++) {
            for (int z = 0; z < CHUNK; z++) {
                int t = (x <= 1 || x >= 14) ? 18 : top[z];
                for (int y = 0; y <= t; y++) {
                    chunk.setBlock(x, y, z, BlockType.STONE);
                }
            }
        }
        ChunkFlow flow = new ChunkFlow(chunk);
        WaterSim sim = new WaterSim(flow);
        for (int x = 2; x <= 13; x++) {
            for (int z = 10; z < CHUNK; z++) {
                pour(flow, sim, x, 12, z);
            }
        }
        pour(flow, sim, 3, 15, 1);
        pour(flow, sim, 12, 15, 1);
        pour(flow, sim, 8, 17, 12);
        for (int i = 0; i < 2000 && sim.getQueuedUpdateCount() > 0; i++) {
            sim.advanceTicks(1);
        }
        assertClosed(chunk);
    }

    // ---------------------------------------------------------------------

    private static Chunk floorChunk(int floorY) {
        Chunk chunk = new Chunk(0, 0);
        for (int x = 0; x < CHUNK; x++) {
            for (int z = 0; z < CHUNK; z++) {
                chunk.setBlock(x, floorY, z, BlockType.STONE);
            }
        }
        return chunk;
    }

    private static void pour(ChunkFlow flow, WaterSim sim, int x, int y, int z) {
        flow.setBlock(x, y, z, BlockType.WATER);
        sim.onBlockChanged(x, y, z, BlockType.AIR, BlockType.WATER);
    }

    private static void assertClosed(Chunk chunk) {
        TestWorld world = new TestWorld(new WorldConfiguration(8, 4), 1L, true);
        world.setChunk(0, 0, chunk);
        MmsMeshData water = new MmsCcoAdapter(new StubTextureMapper(), world)
                .generateChunkMesh(new ChunkDataView(chunk), chunk.getCcoStateManager(),
                        chunk.getCcoDirtyTracker())
                .waterMesh();
        List<String> holes = openEdges(chunk, water);
        assertTrue(holes.isEmpty(), () -> holes.size() + " open water edge(s), e.g. " + holes.get(0));
    }

    /** Triangles in the shared quad EBO's order: (0,2,1), (0,3,2). */
    private static List<String> openEdges(Chunk chunk, MmsMeshData mesh) {
        float[] p = mesh.getVertexPositions();
        int quads = mesh.getVertexCount() / 4;
        int[][] order = {{0, 2, 1}, {0, 3, 2}};
        float[][][] tris = new float[quads * 2][3][];
        for (int q = 0; q < quads; q++) {
            for (int t = 0; t < 2; t++) {
                for (int k = 0; k < 3; k++) {
                    int v = q * 4 + order[t][k];
                    tris[q * 2 + t][k] = new float[]{p[v * 3], p[v * 3 + 1], p[v * 3 + 2]};
                }
            }
        }
        List<String> holes = new ArrayList<>();
        for (int ti = 0; ti < tris.length; ti++) {
            for (int e = 0; e < 3; e++) {
                float[] a = tris[ti][e];
                float[] b = tris[ti][(e + 1) % 3];
                for (int s = 1; s < 8; s++) {
                    float f = s / 8f;
                    float[] pt = {lerp(a[0], b[0], f), lerp(a[1], b[1], f), lerp(a[2], b[2], f)};
                    if (againstSolid(chunk, pt) || coveredByOther(tris, ti, pt)
                            || (isSealedBottom(p, ti >> 1, a, b) && submerged(chunk, pt))) {
                        continue;
                    }
                    holes.add(String.format("(%.3f, %.3f, %.3f)", pt[0], pt[1], pt[2]));
                    break;
                }
            }
        }
        return holes;
    }

    private static boolean coveredByOther(float[][][] tris, int self, float[] pt) {
        for (int tj = 0; tj < tris.length; tj++) {
            if (tj != self && onTriangle(pt, tris[tj])) {
                return true;
            }
        }
        return false;
    }

    /** The bottom edge of a vertical (side) quad. */
    private static boolean isSealedBottom(float[] p, int q, float[] a, float[] b) {
        int v = q * 4;
        boolean vertical = p[v * 3] == p[(v + 2) * 3] || p[v * 3 + 2] == p[(v + 2) * 3 + 2];
        float minY = Float.MAX_VALUE;
        for (int k = 0; k < 4; k++) {
            minY = Math.min(minY, p[(v + k) * 3 + 1]);
        }
        return vertical && a[1] == minY && b[1] == minY;
    }

    /** Below the surface of an adjacent water cell, by more than the wave seal overlap. */
    private static boolean submerged(Chunk c, float[] pt) {
        int y = (int) Math.floor(pt[1]);
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                int x = (int) Math.floor(pt[0] + dx * 0.002f);
                int z = (int) Math.floor(pt[2] + dz * 0.002f);
                if (x < 0 || x >= CHUNK || z < 0 || z >= CHUNK || c.getBlock(x, y, z) != BlockType.WATER) {
                    continue;
                }
                int level = c.getWaterLayer().get(x, y, z);
                float h = (level == ChunkWaterLayer.SOURCE || level == ChunkWaterLayer.FALLING)
                        ? 0.875f : (8 - level) * 0.875f / 8f;
                if (pt[1] - y < h - 0.19f) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean againstSolid(Chunk c, float[] pt) {
        float eps = 0.002f;
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dy = -1; dy <= 1; dy += 2) {
                for (int dz = -1; dz <= 1; dz += 2) {
                    int x = (int) Math.floor(pt[0] + dx * eps);
                    int y = (int) Math.floor(pt[1] + dy * eps);
                    int z = (int) Math.floor(pt[2] + dz * eps);
                    if (x < 0 || x >= CHUNK || z < 0 || z >= CHUNK) {
                        return true; // unloaded neighbours cull border faces
                    }
                    if (c.getBlock(x, y, z) == BlockType.STONE) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean onTriangle(float[] pt, float[][] t) {
        float[] u = sub(t[1], t[0]);
        float[] v = sub(t[2], t[0]);
        float[] w = sub(pt, t[0]);
        float[] n = {u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]};
        float nl = (float) Math.sqrt(dot(n, n));
        if (nl < 1e-9f || Math.abs(dot(w, n)) / nl > 2e-3f) {
            return false;
        }
        float uu = dot(u, u), uv = dot(u, v), vv = dot(v, v), wu = dot(w, u), wv = dot(w, v);
        float d = uv * uv - uu * vv;
        float s = (uv * wv - vv * wu) / d;
        float r = (uv * wu - uu * wv) / d;
        float e = 1e-3f;
        return s >= -e && r >= -e && s + r <= 1 + e;
    }

    private static float lerp(float a, float b, float f) {
        return a + (b - a) * f;
    }

    private static float[] sub(float[] a, float[] b) {
        return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    // ---------------------------------------------------------------------
    // Stubs
    // ---------------------------------------------------------------------

    /** WaterSim over a single chunk; everything outside it is unloaded. */
    private static final class ChunkFlow implements FlowWorld {
        private final Chunk chunk;

        ChunkFlow(Chunk chunk) {
            this.chunk = chunk;
        }

        @Override
        public BlockType getBlock(int x, int y, int z) {
            return isLoaded(x, y, z) ? chunk.getBlock(x, y, z) : BlockType.AIR;
        }

        @Override
        public boolean isLoaded(int x, int y, int z) {
            return x >= 0 && x < CHUNK && z >= 0 && z < CHUNK && y >= 0 && y < WorldConfiguration.WORLD_HEIGHT;
        }

        @Override
        public void setBlock(int x, int y, int z, BlockType type) {
            if (isLoaded(x, y, z)) {
                chunk.setBlock(x, y, z, type);
            }
        }

        @Override
        public int getWater(int x, int y, int z) {
            return isLoaded(x, y, z) ? chunk.getWaterLayer().get(x, y, z) : 0;
        }

        @Override
        public void setWater(int x, int y, int z, int value) {
            if (isLoaded(x, y, z)) {
                chunk.getWaterLayer().set(x, y, z, value);
            }
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            BlockType block = getBlock(x, y, z);
            return block != null && block.isSolid();
        }

        @Override
        public void dropFragile(int x, int y, int z, BlockType type) {
        }

        @Override
        public void markWaterChanged(int x, int y, int z, int newValue) {
        }
    }

    private static final class StubTextureMapper implements MmsTextureMapper {
        @Override
        public float[] generateFaceTextureCoordinates(IBlockType blockType, int face) {
            return new float[MmsBufferLayout.TEXTURE_SIZE * MmsBufferLayout.VERTICES_PER_QUAD];
        }

        @Override
        public float[] generateCrossTextureCoordinates(IBlockType blockType) {
            return new float[MmsBufferLayout.TEXTURE_SIZE * MmsBufferLayout.VERTICES_PER_CROSS];
        }

        @Override
        public float[] generateFaceLayers(IBlockType blockType, int face) {
            return new float[MmsBufferLayout.VERTICES_PER_QUAD];
        }

        @Override
        public float[] generateCrossLayers(IBlockType blockType) {
            return new float[MmsBufferLayout.VERTICES_PER_CROSS];
        }

        @Override
        public float[] generateAlphaFlags(IBlockType blockType) {
            return new float[MmsBufferLayout.VERTICES_PER_QUAD];
        }

        @Override
        public boolean requiresAlphaTesting(IBlockType blockType) {
            return false;
        }
    }

    /** Minimal CcoChunkData view over a Chunk (the production wrapper is private to MmsAPI). */
    private static final class ChunkDataView implements CcoChunkData {
        private final Chunk chunk;

        ChunkDataView(Chunk chunk) {
            this.chunk = chunk;
        }

        @Override
        public IBlockType getBlock(int x, int y, int z) {
            return chunk.getBlock(x, y, z);
        }

        @Override
        public boolean isInBounds(int x, int y, int z) {
            return x >= 0 && x < CHUNK && y >= 0 && y < WorldConfiguration.WORLD_HEIGHT && z >= 0 && z < CHUNK;
        }

        @Override
        public CcoChunkMetadata getMetadata() {
            return null; // never called: getChunkX/getChunkZ are overridden below
        }

        @Override
        public int getChunkX() {
            return chunk.getChunkX();
        }

        @Override
        public int getChunkZ() {
            return chunk.getChunkZ();
        }

        @Override
        public String getBlockState(int x, int y, int z) {
            return chunk.getBlockState(x, y, z);
        }

        @Override
        public int getHighestNonAirY() {
            return chunk.getHighestNonAirY();
        }
    }
}
