package com.stonebreak.world.generation.diffusion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TerrainTileTest {

    @Test
    void indexesRowMajorByWorldCoordinates() {
        // Rows are the i axis (world X), columns the j axis (world Z), so a
        // 2-row x 3-col tile spans i[10,12) and j[20,23).
        // row0 (x=10): z=20,21,22 -> 1,2,3   row1 (x=11): z=20,21,22 -> 4,5,6
        short[] heights = {1, 2, 3, 4, 5, 6};
        short[] biomes = {9, 9, 9, 9, 9, 9};
        short[] water = {320, -1, 400, -1, -1, 320};
        TerrainTile tile = new TerrainTile(0, 0, 10, 20, 12, 23, 3, 2, heights, biomes, water);

        assertEquals(1, tile.heightAt(10, 20));
        assertEquals(3, tile.heightAt(10, 22));
        assertEquals(4, tile.heightAt(11, 20));
        assertEquals(6, tile.heightAt(11, 22));
        assertEquals(9, tile.biomeIdAt(11, 21));
        // The water plane indexes exactly as the other two do, and the -1 sentinel
        // survives as a negative rather than as an unsigned 65535.
        assertEquals(320, tile.waterLevelAt(10, 20));
        assertEquals(400, tile.waterLevelAt(10, 22));
        assertEquals(TerrainTile.NO_WATER, tile.waterLevelAt(11, 20));
    }

    /**
     * Regression guard for the transposed-tile bug: rows are world X, not
     * world Z. A square tile hides this from every length check, so assert the
     * asymmetry directly.
     */
    @Test
    void doesNotTransposeSquareTiles() {
        // 2x2 tile at origin: row-major {a, b, c, d} => (x=0,z=1) is b, (x=1,z=0) is c.
        short[] heights = {1, 2, 3, 4};
        short[] biomes = {0, 0, 0, 0};
        short[] water = {5, 6, 7, 8};
        TerrainTile tile = new TerrainTile(0, 0, 0, 0, 2, 2, 2, 2, heights, biomes, water);

        assertEquals(2, tile.heightAt(0, 1));
        assertEquals(3, tile.heightAt(1, 0));
        assertEquals(6, tile.waterLevelAt(0, 1));
        assertEquals(7, tile.waterLevelAt(1, 0));
    }

    @Test
    void carriesRiverTunnelPlanesWhenTheCarveProducedThem() {
        // Same 2x3 layout as above. Column (10,21) runs a river under standing
        // ground: floor 357, roof 365, with the terrain still up at 404.
        short[] heights = {404, 404, 404, 362, 362, 362};
        short[] biomes = {9, 9, 9, 9, 9, 9};
        short[] water = {-1, 360, -1, -1, -1, -1};
        short[] floors = {-1, 357, -1, -1, -1, -1};
        short[] roofs = {-1, 365, -1, -1, -1, -1};
        TerrainTile tile = new TerrainTile(0, 0, 10, 20, 12, 23, 3, 2,
                heights, biomes, water, floors, roofs);

        assertEquals(357, tile.riverFloorAt(10, 21));
        assertEquals(365, tile.riverRoofAt(10, 21));
        // The tunnelled column keeps its ground: the height is the hill, not the water.
        assertEquals(404, tile.heightAt(10, 21));
        assertEquals(360, tile.waterLevelAt(10, 21));
        // Its neighbours carry no tunnel, and the sentinel survives as a negative
        // rather than as an unsigned 65535 — the same trap the water plane has.
        assertEquals(TerrainTile.NO_TUNNEL, tile.riverFloorAt(10, 20));
        assertEquals(TerrainTile.NO_TUNNEL, tile.riverRoofAt(11, 22));
    }

    /**
     * Every tile source except the water carve — the bridge client and the test
     * fakes — builds tiles with the eleven-argument constructor and has no tunnels
     * to report. Those tiles must answer the tunnel questions rather than throwing,
     * so the block loop can ask unconditionally.
     */
    @Test
    void reportsNoTunnelForATileBuiltWithoutTheRiverPlanes() {
        TerrainTile tile = new TerrainTile(0, 0, 0, 0, 2, 2, 2, 2,
                new short[]{1, 2, 3, 4}, new short[]{0, 0, 0, 0}, new short[]{5, 6, 7, 8});

        assertEquals(TerrainTile.NO_TUNNEL, tile.riverFloorAt(0, 0));
        assertEquals(TerrainTile.NO_TUNNEL, tile.riverRoofAt(1, 1));
        // and it is still a working tile in every other respect
        assertEquals(2, tile.heightAt(0, 1));
        assertEquals(7, tile.waterLevelAt(1, 0));
    }

    @Test
    void throwsOnOutOfBoundsCoordinate() {
        TerrainTile tile = new TerrainTile(0, 0, 0, 0, 1, 1, 1, 1,
                new short[]{1}, new short[]{0}, new short[]{-1});
        assertThrows(IllegalStateException.class, () -> tile.heightAt(5, 5));
        assertThrows(IllegalStateException.class, () -> tile.heightAt(-1, 0));
        assertThrows(IllegalStateException.class, () -> tile.waterLevelAt(5, 5));
    }
}
