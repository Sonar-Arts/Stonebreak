package com.stonebreak.world.generation.sdf;

import com.stonebreak.world.generation.sdf.primitives.SdfSphere;
import com.stonebreak.world.generation.sdf.primitives.SdfCapsule;
import com.stonebreak.world.generation.sdf.primitives.SdfBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SpatialHashGrid.
 */
class SpatialHashGridTest {

    private SpatialHashGrid<SdfPrimitive> grid;

    @BeforeEach
    void setUp() {
        grid = new SpatialHashGrid<>(16); // 16-block cells
    }

    @Test
    void testInsertAndQuerySinglePrimitive() {
        SdfSphere sphere = new SdfSphere(100, 50, 200, 8);
        grid.insert(sphere);

        // Query at sphere center should find it
        List<SdfPrimitive> results = grid.query(100, 50, 200);
        assertEquals(1, results.size(), "Should find sphere at its center");
        assertEquals(sphere, results.get(0), "Should return the inserted sphere");
    }

    @Test
    void testQueryEmptyCell() {
        SdfSphere sphere = new SdfSphere(100, 50, 200, 8);
        grid.insert(sphere);

        // Query far away should return empty list
        List<SdfPrimitive> results = grid.query(500, 50, 500);
        assertTrue(results.isEmpty(), "Query far from primitives should return empty list");
    }

    @Test
    void testMultiplePrimitivesInSameCell() {
        // Insert multiple primitives in same 16-block cell
        SdfSphere sphere1 = new SdfSphere(100, 50, 200, 5);
        SdfSphere sphere2 = new SdfSphere(105, 52, 202, 4);
        grid.insert(sphere1);
        grid.insert(sphere2);

        // Query should find both
        List<SdfPrimitive> results = grid.query(102, 51, 201);
        assertEquals(2, results.size(), "Should find both spheres in same cell");
        assertTrue(results.contains(sphere1), "Should contain sphere1");
        assertTrue(results.contains(sphere2), "Should contain sphere2");
    }

    @Test
    void testLargePrimitiveSpansMultipleCells() {
        // Large sphere that spans multiple 16-block cells
        SdfSphere largeSphere = new SdfSphere(0, 0, 0, 20);
        grid.insert(largeSphere);

        // Queries at different positions should all find it
        assertTrue(grid.query(-15, 0, 0).contains(largeSphere), "Should find at -X edge");
        assertTrue(grid.query(15, 0, 0).contains(largeSphere), "Should find at +X edge");
        assertTrue(grid.query(0, -15, 0).contains(largeSphere), "Should find at -Y edge");
        assertTrue(grid.query(0, 15, 0).contains(largeSphere), "Should find at +Y edge");
        assertTrue(grid.query(0, 0, -15).contains(largeSphere), "Should find at -Z edge");
        assertTrue(grid.query(0, 0, 15).contains(largeSphere), "Should find at +Z edge");
    }

    @Test
    void testQueryRadius() {
        // Insert primitives in different cells
        SdfSphere sphere1 = new SdfSphere(0, 0, 0, 5);
        SdfSphere sphere2 = new SdfSphere(20, 0, 0, 5);
        SdfSphere sphere3 = new SdfSphere(100, 0, 0, 5);

        grid.insert(sphere1);
        grid.insert(sphere2);
        grid.insert(sphere3);

        // Query with radius should find nearby primitives
        List<SdfPrimitive> results = grid.queryRadius(10, 0, 0, 15);
        assertTrue(results.contains(sphere1), "Should find sphere1 within radius");
        assertTrue(results.contains(sphere2), "Should find sphere2 within radius");
        assertFalse(results.contains(sphere3), "Should not find sphere3 (too far)");
    }

    @Test
    void testHasNearbyPrimitives() {
        SdfSphere sphere = new SdfSphere(100, 50, 200, 8);
        grid.insert(sphere);

        assertTrue(grid.hasNearbyPrimitives(100, 50, 200), "Should detect primitive at sphere center");
        assertFalse(grid.hasNearbyPrimitives(500, 50, 500), "Should not detect primitive far away");
    }

    @Test
    void testClear() {
        SdfSphere sphere = new SdfSphere(100, 50, 200, 8);
        grid.insert(sphere);

        assertEquals(1, grid.query(100, 50, 200).size(), "Should find sphere before clear");

        grid.clear();

        assertTrue(grid.query(100, 50, 200).isEmpty(), "Should not find sphere after clear");
        assertEquals(0, grid.getCellCount(), "Cell count should be 0 after clear");
    }

    @Test
    void testGetCellCount() {
        SdfSphere sphere1 = new SdfSphere(0, 0, 0, 5);    // Small sphere in one cell
        SdfSphere sphere2 = new SdfSphere(50, 0, 0, 25);  // Large sphere spanning cells

        grid.insert(sphere1);
        assertTrue(grid.getCellCount() >= 1, "Should have at least 1 cell after first insert");

        grid.insert(sphere2);
        assertTrue(grid.getCellCount() > 1, "Should have multiple cells with large sphere");
    }

    @Test
    void testNegativeCoordinates() {
        // Test that grid works with negative world coordinates
        SdfSphere sphere = new SdfSphere(-100, 50, -200, 8);
        grid.insert(sphere);

        List<SdfPrimitive> results = grid.query(-100, 50, -200);
        assertEquals(1, results.size(), "Should find sphere at negative coordinates");
    }

    @Test
    void testCapsuleCrossingCellBoundaries() {
        // Capsule that crosses multiple cells
        SdfCapsule capsule = new SdfCapsule(0, 50, 0, 100, 50, 0, 3);
        grid.insert(capsule);

        // Query along the capsule's length
        assertTrue(grid.query(0, 50, 0).contains(capsule), "Should find at start");
        assertTrue(grid.query(50, 50, 0).contains(capsule), "Should find at middle");
        assertTrue(grid.query(100, 50, 0).contains(capsule), "Should find at end");
    }

    @Test
    void testBoxPrimitive() {
        SdfBox box = new SdfBox(0, 0, 0, 10, 10, 10);
        grid.insert(box);

        // Query at various points
        assertTrue(grid.query(0, 0, 0).contains(box), "Should find at center");
        assertTrue(grid.query(5, 5, 5).contains(box), "Should find inside box");
        assertTrue(grid.query(-9, 0, 0).contains(box), "Should find near edge");
    }

    @Test
    void testCellSizeParameter() {
        SpatialHashGrid<SdfPrimitive> smallCellGrid = new SpatialHashGrid<>(4);
        SpatialHashGrid<SdfPrimitive> largeCellGrid = new SpatialHashGrid<>(32);

        SdfSphere sphere = new SdfSphere(0, 0, 0, 20);

        smallCellGrid.insert(sphere);
        largeCellGrid.insert(sphere);

        // Smaller cells mean more cells for same primitive
        assertTrue(smallCellGrid.getCellCount() > largeCellGrid.getCellCount(),
                   "Smaller cell size should result in more cells for same primitive");
    }

    @Test
    void testManyPrimitives() {
        // Insert 100 primitives and verify we can find them all
        for (int i = 0; i < 100; i++) {
            float x = i * 20.0f;
            SdfSphere sphere = new SdfSphere(x, 50, 0, 5);
            grid.insert(sphere);
        }

        // Verify we can query each one
        for (int i = 0; i < 100; i++) {
            float x = i * 20.0f;
            List<SdfPrimitive> results = grid.query(x, 50, 0);
            assertFalse(results.isEmpty(), "Should find sphere " + i + " at position " + x);
        }
    }

    @Test
    void testGetStats() {
        SdfSphere sphere1 = new SdfSphere(0, 0, 0, 10);
        SdfSphere sphere2 = new SdfSphere(50, 0, 0, 10);

        grid.insert(sphere1);
        grid.insert(sphere2);

        String stats = grid.toString();
        assertTrue(stats.contains("SpatialHashGrid"), "Stats should include class name");
        assertTrue(stats.contains("cellSize=16"), "Stats should include cell size");
    }
}
