package com.stonebreak.world.generation.sdf;

import com.stonebreak.world.generation.sdf.primitives.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SDF primitive implementations.
 *
 * <p>Validates that all SDF primitives satisfy the mathematical properties
 * of signed distance fields:</p>
 * <ul>
 *   <li>Negative distance inside the shape</li>
 *   <li>Zero distance on the surface</li>
 *   <li>Positive distance outside the shape</li>
 *   <li>Distance magnitude equals true Euclidean distance to surface</li>
 * </ul>
 */
class SdfPrimitivesTest {

    private static final float EPSILON = 0.001f;

    @Test
    void testSdfSphere_centerPoint() {
        SdfSphere sphere = new SdfSphere(100, 50, 200, 10);

        // Point at center should be -radius (inside, distance to surface = radius)
        float distAtCenter = sphere.evaluate(100, 50, 200);
        assertEquals(-10.0f, distAtCenter, EPSILON, "Center of sphere should be -radius");
    }

    @Test
    void testSdfSphere_surfacePoint() {
        SdfSphere sphere = new SdfSphere(100, 50, 200, 10);

        // Point on surface should be ~0
        float distOnSurface = sphere.evaluate(110, 50, 200); // 10 units to the right
        assertEquals(0.0f, distOnSurface, EPSILON, "Point on sphere surface should be 0");
    }

    @Test
    void testSdfSphere_outsidePoint() {
        SdfSphere sphere = new SdfSphere(100, 50, 200, 10);

        // Point outside should be positive (distance to nearest surface)
        float distOutside = sphere.evaluate(120, 50, 200); // 20 units from center
        assertEquals(10.0f, distOutside, EPSILON, "Point outside sphere should have positive distance");
    }

    @Test
    void testSdfSphere_bounds() {
        SdfSphere sphere = new SdfSphere(100, 50, 200, 10);
        float[] bounds = sphere.getBounds();

        assertEquals(90, bounds[0], EPSILON);  // minX
        assertEquals(40, bounds[1], EPSILON);  // minY
        assertEquals(190, bounds[2], EPSILON); // minZ
        assertEquals(110, bounds[3], EPSILON); // maxX
        assertEquals(60, bounds[4], EPSILON);  // maxY
        assertEquals(210, bounds[5], EPSILON); // maxZ
    }

    @Test
    void testSdfCapsule_centerPoint() {
        SdfCapsule capsule = new SdfCapsule(0, 0, 0, 10, 0, 0, 2);

        // Point at center of capsule should be -radius
        float distAtCenter = capsule.evaluate(5, 0, 0);
        assertEquals(-2.0f, distAtCenter, EPSILON, "Center of capsule should be -radius");
    }

    @Test
    void testSdfCapsule_surfacePoint() {
        SdfCapsule capsule = new SdfCapsule(0, 0, 0, 10, 0, 0, 2);

        // Point on surface (2 units perpendicular to axis)
        float distOnSurface = capsule.evaluate(5, 2, 0);
        assertEquals(0.0f, distOnSurface, EPSILON, "Point on capsule surface should be 0");
    }

    @Test
    void testSdfCapsule_endpointSurface() {
        SdfCapsule capsule = new SdfCapsule(0, 0, 0, 10, 0, 0, 2);

        // Point on hemispherical cap
        float distOnCap = capsule.evaluate(12, 0, 0); // 2 units past endpoint
        assertEquals(0.0f, distOnCap, EPSILON, "Point on capsule cap should be 0");
    }

    @Test
    void testSdfBox_centerPoint() {
        SdfBox box = new SdfBox(0, 0, 0, 5, 5, 5);

        // Point at center should be negative (inside)
        float distAtCenter = box.evaluate(0, 0, 0);
        assertTrue(distAtCenter < 0, "Center of box should be inside (negative distance)");
        assertEquals(-5.0f, distAtCenter, EPSILON, "Center should be 5 units from nearest face");
    }

    @Test
    void testSdfBox_facePoint() {
        SdfBox box = new SdfBox(0, 0, 0, 5, 5, 5);

        // Point on +X face
        float distOnFace = box.evaluate(5, 0, 0);
        assertEquals(0.0f, distOnFace, EPSILON, "Point on box face should be 0");
    }

    @Test
    void testSdfBox_cornerPoint() {
        SdfBox box = new SdfBox(0, 0, 0, 5, 5, 5);

        // Point at corner (5, 5, 5)
        float distAtCorner = box.evaluate(5, 5, 5);
        assertEquals(0.0f, distAtCorner, EPSILON, "Point on box corner should be 0");
    }

    @Test
    void testSdfBox_outsidePoint() {
        SdfBox box = new SdfBox(0, 0, 0, 5, 5, 5);

        // Point outside along +X axis
        float distOutside = box.evaluate(10, 0, 0);
        assertEquals(5.0f, distOutside, EPSILON, "Point 5 units outside should have distance 5");
    }

    @Test
    void testSdfCylinder_axisPoint() {
        SdfCylinder cylinder = new SdfCylinder(0, 0, 0, 5, 10);

        // Point on cylinder axis
        float distOnAxis = cylinder.evaluate(0, 5, 0);
        assertEquals(-5.0f, distOnAxis, EPSILON, "Point on axis should be -radius");
    }

    @Test
    void testSdfCylinder_surfacePoint() {
        SdfCylinder cylinder = new SdfCylinder(0, 0, 0, 5, 10);

        // Point on curved surface
        float distOnSurface = cylinder.evaluate(5, 0, 0);
        assertEquals(0.0f, distOnSurface, EPSILON, "Point on cylinder surface should be 0");
    }

    @Test
    void testSdfCylinder_capSurface() {
        SdfCylinder cylinder = new SdfCylinder(0, 0, 0, 5, 10);

        // Point on top cap (center)
        float distOnCap = cylinder.evaluate(0, 10, 0);
        assertEquals(0.0f, distOnCap, EPSILON, "Point on cylinder cap should be 0");
    }

    @Test
    void testSdfHeightfield_aboveSurface() {
        SdfHeightfield heightfield = new SdfHeightfield((x, z) -> 64.0f);

        // Point above heightfield (y=100, surface at y=64)
        float distAbove = heightfield.evaluate(0, 100, 0);
        assertEquals(36.0f, distAbove, EPSILON, "Point 36 units above surface should have distance 36");
    }

    @Test
    void testSdfHeightfield_onSurface() {
        SdfHeightfield heightfield = new SdfHeightfield((x, z) -> 64.0f);

        // Point on surface
        float distOnSurface = heightfield.evaluate(0, 64, 0);
        assertEquals(0.0f, distOnSurface, EPSILON, "Point on heightfield surface should be 0");
    }

    @Test
    void testSdfHeightfield_belowSurface() {
        SdfHeightfield heightfield = new SdfHeightfield((x, z) -> 64.0f);

        // Point below surface (y=30, surface at y=64)
        float distBelow = heightfield.evaluate(0, 30, 0);
        assertEquals(-34.0f, distBelow, EPSILON, "Point 34 units below surface should have distance -34");
    }

    @Test
    void testSdfHeightfield_varyingTerrain() {
        // Sloped terrain: height = x
        SdfHeightfield heightfield = new SdfHeightfield((x, z) -> x);

        float dist1 = heightfield.evaluate(50, 60, 0);
        assertEquals(10.0f, dist1, EPSILON, "Point should be 10 units above slope");

        float dist2 = heightfield.evaluate(80, 80, 0);
        assertEquals(0.0f, dist2, EPSILON, "Point should be on slope surface");

        float dist3 = heightfield.evaluate(100, 90, 0);
        assertEquals(-10.0f, dist3, EPSILON, "Point should be 10 units below slope");
    }

    @Test
    void testBlendOperations_union() {
        float d1 = -2.0f; // Inside shape 1
        float d2 = 5.0f;  // Outside shape 2
        float union = SdfBlendOperations.union(d1, d2);
        assertEquals(-2.0f, union, EPSILON, "Union should take minimum distance");
    }

    @Test
    void testBlendOperations_subtract() {
        float terrain = -10.0f; // Inside terrain
        float cave = -5.0f;     // Inside cave (would subtract from terrain)
        float result = SdfBlendOperations.subtract(terrain, cave);
        assertEquals(5.0f, result, EPSILON, "Subtraction: max(terrain, -cave)");
    }

    @Test
    void testBlendOperations_intersect() {
        float d1 = -2.0f; // Inside shape 1
        float d2 = -5.0f; // Inside shape 2
        float intersect = SdfBlendOperations.intersect(d1, d2);
        assertEquals(-2.0f, intersect, EPSILON, "Intersection should take maximum distance");
    }

    @Test
    void testBlendOperations_smoothUnion() {
        float d1 = -5.0f;
        float d2 = -3.0f;
        float smoothness = 2.0f;

        float result = SdfBlendOperations.smoothUnion(d1, d2, smoothness);
        float hardUnion = SdfBlendOperations.union(d1, d2);

        assertTrue(result <= -3.0f, "Smooth union should be <= minimum input");
        assertTrue(result <= hardUnion, "Smooth union should be <= hard union (blending creates more negative values)");
    }

    @Test
    void testSdfNode_leaf() {
        SdfSphere sphere = new SdfSphere(0, 0, 0, 10);
        SdfNode node = SdfNode.leaf(sphere);

        float dist = node.evaluate(0, 0, 0);
        assertEquals(-10.0f, dist, EPSILON, "Leaf node should evaluate to primitive value");
    }

    @Test
    void testSdfNode_union() {
        SdfSphere sphere1 = new SdfSphere(0, 0, 0, 5);
        SdfSphere sphere2 = new SdfSphere(20, 0, 0, 5);

        SdfNode union = SdfNode.union(
            SdfNode.leaf(sphere1),
            SdfNode.leaf(sphere2)
        );

        // Point near first sphere
        float dist1 = union.evaluate(0, 0, 0);
        assertEquals(-5.0f, dist1, EPSILON, "Union should show distance to nearest sphere");

        // Point between spheres
        float distMid = union.evaluate(10, 0, 0);
        assertEquals(5.0f, distMid, EPSILON, "Point between spheres should be 5 units from each");
    }

    @Test
    void testSdfNode_subtract() {
        SdfBox terrain = new SdfBox(0, 0, 0, 20, 20, 20);
        SdfSphere cave = new SdfSphere(0, 0, 0, 10);

        SdfNode carved = SdfNode.subtract(
            SdfNode.leaf(terrain),
            SdfNode.leaf(cave)
        );

        // Point at center should now be outside (cave carved out)
        float distCenter = carved.evaluate(0, 0, 0);
        assertTrue(distCenter > 0, "Center should be empty (cave carved from terrain)");
        assertEquals(10.0f, distCenter, EPSILON, "Distance should equal cave radius");
    }

    @Test
    void testSdfNode_complexTree() {
        // Create a terrain with two connected cave chambers
        SdfHeightfield terrain = new SdfHeightfield((x, z) -> 100.0f);
        SdfSphere chamber1 = new SdfSphere(0, 50, 0, 8);
        SdfSphere chamber2 = new SdfSphere(15, 55, 0, 6);

        SdfNode caves = SdfNode.smoothUnion(
            SdfNode.leaf(chamber1),
            SdfNode.leaf(chamber2),
            4.0f
        );

        SdfNode result = SdfNode.subtract(
            SdfNode.leaf(terrain),
            caves
        );

        // Point at chamber1 center should be cave (positive distance)
        float distChamber1 = result.evaluate(0, 50, 0);
        assertTrue(distChamber1 > 0, "Chamber 1 center should be carved out");

        // Point at chamber2 center should be cave
        float distChamber2 = result.evaluate(15, 55, 0);
        assertTrue(distChamber2 > 0, "Chamber 2 center should be carved out");

        // Point far above should be air (above terrain)
        float distAbove = result.evaluate(0, 150, 0);
        assertTrue(distAbove > 0, "Point above terrain should be air");

        // Point deep below should be solid
        float distBelow = result.evaluate(0, 10, 0);
        assertTrue(distBelow < 0, "Point below caves should be solid terrain");
    }

    @Test
    void testSdfPrimitive_boundsCheck() {
        SdfSphere sphere = new SdfSphere(100, 50, 200, 10);

        // Point far outside bounds
        assertTrue(sphere.isOutsideBounds(200, 50, 200),
                   "Point far from sphere should be outside bounds");

        // Point inside bounds
        assertFalse(sphere.isOutsideBounds(105, 50, 200),
                    "Point near sphere should be inside bounds");
    }
}
