package com.stonebreak.world.generation;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.core.CcoChunkData;
import com.openmason.engine.voxel.cco.data.CcoChunkMetadata;
import com.openmason.engine.voxel.mms.mmsCore.ChunkMeshResult;
import com.openmason.engine.voxel.mms.mmsCore.MmsBufferLayout;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsTexturing.MmsTextureMapper;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.TestWorld;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration.MmsCcoAdapter;
import com.stonebreak.world.generation.diffusion.FakeTerrainTileSource;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the terrain you SEE (the GPU mesh) faithfully represents the block data the
 * terrain generator actually PRODUCES, at specific coordinates.
 *
 * <p>Strategy (fully headless — no OpenGL):
 * <ol>
 *   <li>Generate a chunk (and its 8 neighbors) from a fixed seed via
 *       {@link TerrainGenerationSystem#generateTerrainOnly(int, int)} — this is the
 *       <b>generated</b> source of truth.</li>
 *   <li>Run the <b>real</b> CPU mesh builder ({@link MmsCcoAdapter#generateChunkMesh}) on that
 *       data. This is the exact geometry that would be uploaded to the GPU verbatim. Only a
 *       no-op {@link MmsTextureMapper} stub is needed — textures are irrelevant to terrain shape,
 *       and this keeps us off the GL-bound {@code BlockTextureArray}/{@code MmsAPI} path.</li>
 *   <li>Reconstruct, from the emitted vertices, the set of {@code (block, face)} quads the mesh
 *       actually draws.</li>
 *   <li>Independently compute the <b>expected</b> visible-face set directly from the block data
 *       using the same culling rule the mesher uses (an opaque cube emits a face iff its neighbor
 *       in that direction is non-opaque).</li>
 *   <li>Compare per block and fail on any mismatch; print a detailed report at each requested
 *       coordinate.</li>
 * </ol>
 *
 * Water (variable-height special geometry) and cross blocks (flowers) are reported informationally
 * but excluded from the strict cube assertion. Terrain-only generation places no flowers/ores, so
 * the chunk is bedrock/stone/dirt/sand/grass + water.
 */
public class TerrainMeshConsistencyTest {

    /** Fixed seed so the run is reproducible. */
    private static final long SEED = 12345L;

    /**
     * Coordinates to verify in detail: {worldX, worldY, worldZ}.
     * Edit this list to point at the coordinates you care about. The full center chunk(s)
     * containing these points are asserted exhaustively regardless of this list; these entries
     * just drive the per-point printed report.
     */
    private static final int[][] POINTS = {
            {8, 64, 8},
            {8, 70, 8},
            {1, 96, 1},
            {15, 80, 15},   // chunk-boundary column (verified against real neighbor chunk data)
            {1608, 80, 1608}, // far from origin (chunk 100,100)
    };

    /**
     * Chunk coordinates to verify exhaustively (every cube block in the chunk), independent of
     * {@link #POINTS}. Use this to sweep whole chunks — including ones far from the origin.
     */
    private static final int[][] CHUNKS = {
            {0, 0},
            {100, 100},
    };

    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;

    // Face indexing matches MmsCuboidGenerator / MmsCcoAdapter:
    // 0:Top(+Y) 1:Bottom(-Y) 2:North(-Z) 3:South(+Z) 4:East(+X) 5:West(-X)
    private static final int[][] FACE_OFFSET = {
            {0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}
    };
    private static final String[] FACE_NAME = {"TOP", "BOTTOM", "NORTH", "SOUTH", "EAST", "WEST"};

    private TerrainGenerationSystem terrain;
    private TestWorld world;
    private MmsCcoAdapter adapter;
    private final Map<Long, Chunk> chunks = new HashMap<>();

    @Test
    public void renderedTerrainMatchesGeneratedTerrain() {
        terrain = new TerrainGenerationSystem(SEED, new FakeTerrainTileSource());
        world = new TestWorld(new WorldConfiguration(8, 4), SEED, true);
        adapter = new MmsCcoAdapter(new StubTextureMapper(), world);

        // Collect the unique center chunks to verify: those the requested points fall in, plus any
        // explicitly listed chunk coordinates (e.g. distant chunks).
        Set<Long> centerChunks = new LinkedHashSet<>();
        for (int[] p : POINTS) {
            centerChunks.add(key(Math.floorDiv(p[0], CHUNK), Math.floorDiv(p[2], CHUNK)));
        }
        for (int[] c : CHUNKS) {
            centerChunks.add(key(c[0], c[1]));
        }

        List<String> mismatches = new ArrayList<>();

        for (long ck : centerChunks) {
            int cx = (int) (ck >> 32);
            int cz = (int) (ck & 0xffffffffL);

            // Generate the center chunk plus its 8 neighbors so cross-chunk face culling at
            // chunk borders resolves against real generated data (registered in the world so the
            // adapter's getAdjacentBlock can see them).
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    genChunk(cx + dx, cz + dz);
                }
            }

            Chunk center = chunks.get(ck);

            // Build the real CPU mesh and reconstruct which (block, face) quads it draws.
            Map<Long, Set<Integer>> actual = reconstructFaces(buildMesh(center), cx, cz);

            // Compare every cube-meshed block in the center chunk: expected faces vs mesh faces.
            int checked = 0;
            for (int lx = 0; lx < CHUNK; lx++) {
                for (int lz = 0; lz < CHUNK; lz++) {
                    for (int ly = 0; ly < WORLD_HEIGHT; ly++) {
                        BlockType bt = center.getBlock(lx, ly, lz);
                        if (!isCubeMeshed(bt)) {
                            continue;
                        }
                        int wx = cx * CHUNK + lx;
                        int wz = cz * CHUNK + lz;
                        Set<Integer> exp = expectedFaces(bt, wx, ly, wz);
                        Set<Integer> act = actual.getOrDefault(key3(wx, ly, wz), Set.of());
                        checked++;
                        if (!exp.equals(act)) {
                            mismatches.add(String.format(
                                    "MISMATCH at (%d,%d,%d) %s: expected=%s actual=%s",
                                    wx, ly, wz, bt, names(exp), names(act)));
                        }
                    }
                }
            }
            System.out.printf("Chunk (%d,%d): verified %d cube blocks against mesh (seed %d)%n",
                    cx, cz, checked, SEED);
        }

        // Detailed per-point report.
        System.out.println("\n=== Per-point report (seed " + SEED + ") ===");
        for (int[] p : POINTS) {
            reportPoint(p[0], p[1], p[2]);
        }

        if (!mismatches.isEmpty()) {
            System.out.println("\n=== MISMATCHES (" + mismatches.size() + ") ===");
            mismatches.forEach(System.out::println);
        }
        assertTrue(mismatches.isEmpty(),
                mismatches.size() + " block(s) render faces inconsistent with generated data. See log.");
    }

    // ------------------------------------------------------------------------------------------
    // Mesh build + reconstruction
    // ------------------------------------------------------------------------------------------

    private MmsMeshData buildMesh(Chunk center) {
        ChunkMeshResult result = adapter.generateChunkMesh(
                new ChunkDataView(center),
                center.getCcoStateManager(),
                center.getCcoDirtyTracker());
        return result.atlasMesh();
    }

    /**
     * Reconstructs the set of unit cube faces emitted by the mesh, keyed by world block.
     * Faces are emitted as contiguous groups of 4 vertices; cube faces are integer-aligned unit
     * quads with axis-aligned normals. Cross (diagonal normal) and water (fractional height) faces
     * fail the integer-unit-quad test and are skipped.
     */
    private Map<Long, Set<Integer>> reconstructFaces(MmsMeshData mesh, int cx, int cz) {
        Map<Long, Set<Integer>> out = new HashMap<>();
        if (mesh == null || mesh.isEmpty()) {
            return out;
        }
        float[] pos = mesh.getVertexPositions();
        float[] nrm = mesh.getVertexNormals();
        int verts = mesh.getVertexCount();

        for (int g = 0; g + 4 <= verts; g += 4) {
            float nx = nrm[g * 3], ny = nrm[g * 3 + 1], nz = nrm[g * 3 + 2];
            int face = faceFromNormal(nx, ny, nz);
            if (face < 0) {
                continue; // not an axis-aligned unit normal (e.g. cross plane)
            }

            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int v = g; v < g + 4; v++) {
                float x = pos[v * 3], y = pos[v * 3 + 1], z = pos[v * 3 + 2];
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            }
            if (!isIntegerUnitQuad(face, minX, maxX, minY, maxY, minZ, maxZ)) {
                continue; // water / non-unit geometry — out of scope for the cube assertion
            }

            // Map the face plane back to its owning block. For a positive-facing normal the plane
            // sits at block+1 along that axis; for a negative-facing normal it sits at block.
            int bx = (face == 4) ? round(minX) - 1 : (face == 5) ? round(minX) : round(minX);
            int by = (face == 0) ? round(minY) - 1 : (face == 1) ? round(minY) : round(minY);
            int bz = (face == 3) ? round(minZ) - 1 : (face == 2) ? round(minZ) : round(minZ);

            out.computeIfAbsent(key3(bx, by, bz), k -> new LinkedHashSet<>()).add(face);
        }
        return out;
    }

    private static boolean isIntegerUnitQuad(int face,
                                             float minX, float maxX, float minY, float maxY,
                                             float minZ, float maxZ) {
        // The normal axis must be flat (min==max) and integer; the two tangent axes must span
        // exactly one integer unit.
        boolean flatX = approxEqual(minX, maxX), flatY = approxEqual(minY, maxY), flatZ = approxEqual(minZ, maxZ);
        boolean unitX = approxEqual(maxX - minX, 1f), unitY = approxEqual(maxY - minY, 1f), unitZ = approxEqual(maxZ - minZ, 1f);
        boolean intX = isInt(minX), intY = isInt(minY), intZ = isInt(minZ);
        return switch (face) {
            case 0, 1 -> flatY && intY && unitX && unitZ && intX && intZ; // Y faces
            case 2, 3 -> flatZ && intZ && unitX && unitY && intX && intY; // Z faces
            case 4, 5 -> flatX && intX && unitY && unitZ && intY && intZ; // X faces
            default -> false;
        };
    }

    private static int faceFromNormal(float nx, float ny, float nz) {
        if (approxEqual(nx, 0) && approxEqual(ny, 1) && approxEqual(nz, 0)) return 0;
        if (approxEqual(nx, 0) && approxEqual(ny, -1) && approxEqual(nz, 0)) return 1;
        if (approxEqual(nx, 0) && approxEqual(ny, 0) && approxEqual(nz, -1)) return 2;
        if (approxEqual(nx, 0) && approxEqual(ny, 0) && approxEqual(nz, 1)) return 3;
        if (approxEqual(nx, 1) && approxEqual(ny, 0) && approxEqual(nz, 0)) return 4;
        if (approxEqual(nx, -1) && approxEqual(ny, 0) && approxEqual(nz, 0)) return 5;
        return -1;
    }

    // ------------------------------------------------------------------------------------------
    // Expected-face computation (mirrors MmsCcoAdapter.shouldRenderAgainst / getAdjacentBlock)
    // ------------------------------------------------------------------------------------------

    private Set<Integer> expectedFaces(BlockType bt, int wx, int wy, int wz) {
        Set<Integer> faces = new LinkedHashSet<>();
        for (int face = 0; face < 6; face++) {
            int ax = wx + FACE_OFFSET[face][0];
            int ay = wy + FACE_OFFSET[face][1];
            int az = wz + FACE_OFFSET[face][2];
            BlockType neighbor = blockAtWorld(ax, ay, az);
            if (shouldRenderAgainst(bt, neighbor)) {
                faces.add(face);
            }
        }
        return faces;
    }

    /** Exact copy of MmsCcoAdapter.shouldRenderAgainst for the cube path. */
    private static boolean shouldRenderAgainst(BlockType bt, BlockType neighbor) {
        if (neighbor == BlockType.AIR) {
            return true;
        }
        if (bt.isTransparent()) {
            return bt != neighbor;
        }
        return neighbor.isTransparent();
    }

    /** World block lookup against our generated chunk map; out-of-range Y and unloaded chunks are AIR. */
    private BlockType blockAtWorld(int wx, int wy, int wz) {
        if (wy < 0 || wy >= WORLD_HEIGHT) {
            return BlockType.AIR;
        }
        Chunk c = chunks.get(key(Math.floorDiv(wx, CHUNK), Math.floorDiv(wz, CHUNK)));
        if (c == null) {
            return BlockType.AIR;
        }
        return c.getBlock(Math.floorMod(wx, CHUNK), wy, Math.floorMod(wz, CHUNK));
    }

    private static boolean isCubeMeshed(BlockType bt) {
        return bt != BlockType.AIR && bt != BlockType.WATER && !isCross(bt);
    }

    private static boolean isCross(BlockType bt) {
        return bt == BlockType.ROSE || bt == BlockType.DANDELION || bt == BlockType.WILDGRASS;
    }

    // ------------------------------------------------------------------------------------------
    // Reporting
    // ------------------------------------------------------------------------------------------

    private void reportPoint(int wx, int wy, int wz) {
        int cx = Math.floorDiv(wx, CHUNK), cz = Math.floorDiv(wz, CHUNK);
        BlockType bt = blockAtWorld(wx, wy, wz);
        int surface = terrain.getFinalTerrainHeightAt(wx, wz);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("(%d,%d,%d) chunk(%d,%d) local(%d,%d,%d) biome=%s surfaceH=%d%n",
                wx, wy, wz, cx, cz, Math.floorMod(wx, CHUNK), wy, Math.floorMod(wz, CHUNK),
                terrain.getBiomeAt(wx, wz), surface));
        sb.append("  generated block : ").append(bt).append('\n');
        sb.append("  neighbors       : ");
        for (int f = 0; f < 6; f++) {
            BlockType n = blockAtWorld(wx + FACE_OFFSET[f][0], wy + FACE_OFFSET[f][1], wz + FACE_OFFSET[f][2]);
            sb.append(FACE_NAME[f]).append('=').append(n).append(f < 5 ? "  " : "\n");
        }

        if (isCubeMeshed(bt)) {
            Chunk center = chunks.get(key(cx, cz));
            Map<Long, Set<Integer>> actual = reconstructFaces(buildMesh(center), cx, cz);
            Set<Integer> exp = expectedFaces(bt, wx, wy, wz);
            Set<Integer> act = actual.getOrDefault(key3(wx, wy, wz), Set.of());
            boolean ok = exp.equals(act);
            sb.append("  expected faces  : ").append(names(exp)).append('\n');
            sb.append("  rendered faces  : ").append(names(act)).append('\n');
            sb.append("  => ").append(ok ? "PASS" : "FAIL");
        } else {
            sb.append("  => ").append(bt == BlockType.WATER ? "WATER (special geometry — informational only)"
                    : bt == BlockType.AIR ? "AIR (no geometry of its own)"
                    : "non-cube block (informational only)");
        }
        System.out.println(sb);
    }

    private static String names(Set<Integer> faces) {
        if (faces.isEmpty()) {
            return "{none}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (int f = 0; f < 6; f++) {
            if (faces.contains(f)) {
                if (!first) sb.append(',');
                sb.append(FACE_NAME[f]);
                first = false;
            }
        }
        return sb.append('}').toString();
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private Chunk genChunk(int cx, int cz) {
        long k = key(cx, cz);
        Chunk existing = chunks.get(k);
        if (existing != null) {
            return existing;
        }
        Chunk c = terrain.generateTerrainOnly(cx, cz).chunk();
        chunks.put(k, c);
        world.setChunk(cx, cz, c); // make it visible to the adapter's cross-chunk neighbor lookups
        return c;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    private static long key3(int x, int y, int z) {
        // x,z in roughly [-2^20, 2^20], y in [0,WORLD_HEIGHT) — pack into one long.
        // y needs 10 bits for WORLD_HEIGHT=1024 (was 9, sized for the old 256); still fits
        // well clear of x's bits 43-63.
        return (((long) (x & 0x1FFFFF)) << 43) | (((long) (y & 0x3FF)) << 21) | (z & 0x1FFFFFL);
    }

    private static boolean approxEqual(float a, float b) {
        return Math.abs(a - b) < 1e-3f;
    }

    private static boolean isInt(float v) {
        return approxEqual(v, Math.round(v));
    }

    private static int round(float v) {
        return Math.round(v);
    }

    // ------------------------------------------------------------------------------------------
    // Stubs
    // ------------------------------------------------------------------------------------------

    /**
     * No-op texture mapper. Returns correctly-sized constant arrays so the mesh builder produces
     * valid interleaved data; texture coordinates/layers are irrelevant to terrain geometry.
     */
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
            return x >= 0 && x < CHUNK && y >= 0 && y < WORLD_HEIGHT && z >= 0 && z < CHUNK;
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
