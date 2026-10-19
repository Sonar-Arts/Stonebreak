package com.stonebreak.world.fastlod;

import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Walls in FastLOD show the column's strata, as real terrain does: a ravine cut into red sand
 * is red sand, red sandstone, then stone, not one stretched sheet of the rim's red sand with
 * the stone of the cells inside it striped through (what "textures go haywire" looked like).
 */
class FastLodStrataTest {

    private static final List<BlockType> BLOCKS = List.of(BlockType.RED_SAND, BlockType.RED_SANDSTONE,
        BlockType.STONE, BlockType.GRASS, BlockType.DIRT, BlockType.SAND, BlockType.SANDSTONE);
    private static final int GROUND = 120;

    private FastLodMesher mesher;

    @BeforeEach
    void setUp() {
        MmsVertexFormat.override(MmsVertexFormat.LEGACY40);
        BlockTextureArray textures = mock(BlockTextureArray.class);
        // One layer per block (faces share it), so a quad's layer names its block.
        when(textures.getBlockFaceLayer(any(), anyInt()))
            .thenAnswer(inv -> 10 + BLOCKS.indexOf((BlockType) inv.getArgument(0)));
        mesher = new FastLodMesher(textures);
    }

    @AfterEach
    void restore() {
        MmsVertexFormat.override(MmsVertexFormat.DEFAULT);
    }

    private record Band(float top, float bottom, BlockType block) {}

    private static List<Band> bands(BlockType surface, int top, int bottom) {
        List<Band> out = new ArrayList<>();
        FastLodMesher.forEachBand(surface, top, bottom, (t, b, block) -> out.add(new Band(t, b, block)));
        return out;
    }

    @Test
    void aWallIsSurfaceThenThreeBlocksOfWhatLiesUnderThenStone() {
        assertEquals(List.of(new Band(100, 99, BlockType.RED_SAND), new Band(99, 96, BlockType.RED_SANDSTONE),
            new Band(96, 80, BlockType.STONE)), bands(BlockType.RED_SAND, 100, 80));
        assertEquals(List.of(new Band(100, 99, BlockType.GRASS), new Band(99, 98, BlockType.DIRT)),
            bands(BlockType.GRASS, 100, 98), "a short drop stops where it ends");
        assertEquals(List.of(new Band(100, 99, BlockType.SAND)), bands(BlockType.SAND, 100, 99));
    }

    @Test
    void stoneAndSandstoneTopsAreOneBandNotThree() {
        assertEquals(List.of(new Band(100, 80, BlockType.STONE)), bands(BlockType.STONE, 100, 80));
        assertEquals(List.of(new Band(100, 96, BlockType.SANDSTONE), new Band(96, 90, BlockType.STONE)),
            bands(BlockType.SANDSTONE, 100, 90));
    }

    @Test
    void aCoarseSlopeKeepsTheSurfacesSideButACutIsLayered() {
        // L2 (4-block cells): a 12-block drop between two grass cells is a slope of grass-topped
        // steps; the same drop into a stone-topped cell is a ravine wall.
        FastLodLevel level = FastLodLevel.L2;
        int stride = level.stride();
        int[] heights = new int[level.heightCount()];
        Arrays.fill(heights, GROUND);
        for (int z = 0; z < stride; z++) heights[3 * stride + z] = GROUND - 12;   // cell column x = 2
        BlockType[] surface = new BlockType[level.cellCount()];
        Arrays.fill(surface, BlockType.GRASS);
        int[] water = new int[level.cellCount()];
        Arrays.fill(water, TerrainTile.NO_WATER);

        MmsMeshData slope = mesher.build(new FastLodChunkData(FastLodKey.of(level, 0, 0), heights, water,
            surface, null)).mesh();
        assertEquals(List.of(BlockType.GRASS), wallBlocksBelow(slope, GROUND), "slope skirts keep the grass side");

        for (int z = 0; z < level.cellsPerAxis(); z++) surface[2 * level.cellsPerAxis() + z] = BlockType.STONE;
        MmsMeshData cut = mesher.build(new FastLodChunkData(FastLodKey.of(level, 0, 0), heights, water,
            surface, null)).mesh();
        assertTrue(wallBlocksBelow(cut, GROUND).containsAll(List.of(BlockType.GRASS, BlockType.DIRT, BlockType.STONE)),
            "a ravine wall is layered at coarse levels too");
    }

    /** Distinct blocks faced by quads at or below {@code y} whose normal is horizontal. */
    private static List<BlockType> wallBlocksBelow(MmsMeshData mesh, float y) {
        float[] pos = mesh.getVertexPositions();
        float[] nrm = mesh.getVertexNormals();
        float[] layer = mesh.getLayerIndices();
        List<BlockType> out = new ArrayList<>();
        for (int q = 0; q < mesh.getVertexCount() / 4; q++) {
            int v = q * 4;
            if (Math.abs(nrm[v * 3 + 1]) > 0.5f || mesh.getLightValues()[v] < 0.5f) continue; // tops, foundations
            float top = Math.max(Math.max(pos[v * 3 + 1], pos[(v + 1) * 3 + 1]),
                Math.max(pos[(v + 2) * 3 + 1], pos[(v + 3) * 3 + 1]));
            if (top > y) continue;
            BlockType block = BLOCKS.get((int) layer[v] - 10);
            if (!out.contains(block)) out.add(block);
        }
        return out;
    }

    @Test
    void aRavineAtTheFinestLevelIsLayeredDownItsWalls() {
        FastLodLevel level = FastLodLevel.L0;
        int stride = level.stride();
        int[] heights = new int[level.heightCount()];
        BlockType[] surface = new BlockType[level.cellCount()];
        Arrays.fill(heights, GROUND);
        Arrays.fill(surface, BlockType.RED_SAND);
        // A 2-wide ravine 30 deep down the node, x = 7..8, its floor stone.
        for (int x = 7; x <= 8; x++) {
            for (int z = 0; z < stride; z++) heights[(x + 1) * stride + z] = GROUND - 30;
            for (int z = 0; z < level.cellsPerAxis(); z++) surface[x * level.cellsPerAxis() + z] = BlockType.STONE;
        }
        int[] water = new int[level.cellCount()];
        Arrays.fill(water, TerrainTile.NO_WATER);
        MmsMeshData mesh = mesher.build(new FastLodChunkData(FastLodKey.of(level, 0, 0), heights, water,
            surface, null)).mesh();

        assertEquals(List.of(BlockType.RED_SAND), wallBlocksBelow(mesh, GROUND).subList(0, 1),
            "the rim's own block tops the wall");
        assertTrue(wallBlocksBelow(mesh, GROUND - 1).contains(BlockType.RED_SANDSTONE), "then red sandstone");
        assertEquals(List.of(BlockType.STONE), wallBlocksBelow(mesh, GROUND - 4), "then stone, all the way down");
    }

    @Test
    void aCaveMouthCutsTheCellsTopAndIsLayeredToo() {
        FastLodLevel level = FastLodLevel.L4;
        int[] heights = new int[level.heightCount()];
        Arrays.fill(heights, GROUND);
        int[] floor = {GROUND - 12};
        byte[] cover = {(byte) 128};
        FastLodChunkData data = new FastLodChunkData(FastLodKey.of(level, 0, 0), heights,
            new int[]{TerrainTile.NO_WATER}, new BlockType[]{BlockType.GRASS}, null, floor, cover);
        MmsMeshData mesh = mesher.build(data).mesh();

        int[] notch = FastLodMesher.notchSquare(16, 128);
        float cx = notch[0] + notch[1] / 2f, cz = cx;
        float[] pos = mesh.getVertexPositions();
        float[] nrm = mesh.getVertexNormals();
        for (int q = 0; q < mesh.getVertexCount() / 4; q++) {
            int v = q * 4;
            if (nrm[v * 3 + 1] < 0.5f || Math.abs(pos[v * 3 + 1] - GROUND) > 1e-4f) continue;
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int c = 0; c < 4; c++) {
                minX = Math.min(minX, pos[(v + c) * 3]); maxX = Math.max(maxX, pos[(v + c) * 3]);
                minZ = Math.min(minZ, pos[(v + c) * 3 + 2]); maxZ = Math.max(maxZ, pos[(v + c) * 3 + 2]);
            }
            assertTrue(!(cx > minX && cx < maxX && cz > minZ && cz < maxZ),
                "a top quad at the surface covers the mouth, hiding the pit");
        }
        List<BlockType> walls = wallBlocksBelow(mesh, GROUND);
        assertTrue(walls.containsAll(List.of(BlockType.GRASS, BlockType.DIRT, BlockType.STONE)), "walls " + walls);
    }

    @Test
    void notchesSitOnTheBlockGridInsideARim() {
        for (int cellSize : new int[]{2, 4, 8, 16}) {
            for (int coverage = 0; coverage <= 255; coverage++) {
                int[] n = FastLodMesher.notchSquare(cellSize, coverage);
                assertTrue(n[1] >= 1 && n[0] >= 0 && n[0] + n[1] <= cellSize && n[1] < cellSize,
                    cellSize + "/" + coverage + " -> " + Arrays.toString(n));
            }
        }
    }
}
