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
        TerrainTile tile = new TerrainTile(0, 0, 10, 20, 12, 23, 3, 2, heights, biomes);

        assertEquals(1, tile.heightAt(10, 20));
        assertEquals(3, tile.heightAt(10, 22));
        assertEquals(4, tile.heightAt(11, 20));
        assertEquals(6, tile.heightAt(11, 22));
        assertEquals(9, tile.biomeIdAt(11, 21));
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
        TerrainTile tile = new TerrainTile(0, 0, 0, 0, 2, 2, 2, 2, heights, biomes);

        assertEquals(2, tile.heightAt(0, 1));
        assertEquals(3, tile.heightAt(1, 0));
    }

    @Test
    void throwsOnOutOfBoundsCoordinate() {
        TerrainTile tile = new TerrainTile(0, 0, 0, 0, 1, 1, 1, 1, new short[]{1}, new short[]{0});
        assertThrows(IllegalStateException.class, () -> tile.heightAt(5, 5));
        assertThrows(IllegalStateException.class, () -> tile.heightAt(-1, 0));
    }
}
