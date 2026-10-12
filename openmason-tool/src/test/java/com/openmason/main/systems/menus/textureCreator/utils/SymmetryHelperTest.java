package com.openmason.main.systems.menus.textureCreator.utils;

import com.openmason.main.systems.menus.textureCreator.SymmetryState.SymmetryMode;
import com.openmason.main.systems.menus.textureCreator.utils.SymmetryHelper.Point2i;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mirror-point and mirror-segment maths behind symmetric painting. */
class SymmetryHelperTest {

    @Test
    void axisSitsOnThePixelCentreForOddSizesAndBetweenPixelsForEven() {
        assertEquals(2.0f, SymmetryHelper.getAxisCenterX(5, 0));
        assertEquals(1.5f, SymmetryHelper.getAxisCenterX(4, 0));
        assertEquals(4.5f, SymmetryHelper.getAxisCenterY(4, 3));
        assertEquals(-0.5f, SymmetryHelper.getAxisCenterY(4, -2));
    }

    @Test
    void noneReturnsOnlyTheOriginal() {
        assertEquals(List.of(new Point2i(3, 1)),
                SymmetryHelper.calculateMirrorPoints(3, 1, SymmetryMode.NONE, 8, 8, 0, 0));
    }

    @Test
    void verticalMirrorsLeftRightAndHorizontalMirrorsTopBottom() {
        assertEquals(List.of(new Point2i(1, 2), new Point2i(6, 2)),
                SymmetryHelper.calculateMirrorPoints(1, 2, SymmetryMode.VERTICAL, 8, 8, 0, 0));
        assertEquals(List.of(new Point2i(1, 2), new Point2i(1, 5)),
                SymmetryHelper.calculateMirrorPoints(1, 2, SymmetryMode.HORIZONTAL, 8, 8, 0, 0));
    }

    @Test
    void quadrantProducesFourDistinctImages() {
        List<Point2i> points = SymmetryHelper.calculateMirrorPoints(1, 2, SymmetryMode.QUADRANT, 8, 8, 0, 0);

        assertEquals(List.of(new Point2i(1, 2), new Point2i(6, 2), new Point2i(1, 5), new Point2i(6, 5)), points);
    }

    @Test
    void pointsOnAnAxisAreNotDuplicated() {
        assertEquals(1, SymmetryHelper.calculateMirrorPoints(2, 0, SymmetryMode.VERTICAL, 5, 5, 0, 0).size());
        // On the vertical axis only: the vertical image is itself, the diagonal equals the horizontal one.
        assertEquals(List.of(new Point2i(2, 0), new Point2i(2, 4)),
                SymmetryHelper.calculateMirrorPoints(2, 0, SymmetryMode.QUADRANT, 5, 5, 0, 0));
        assertEquals(1, SymmetryHelper.calculateMirrorPoints(2, 2, SymmetryMode.QUADRANT, 5, 5, 0, 0).size());
    }

    @Test
    void offsetMovesTheAxisAndMayMirrorOffCanvas() {
        // Axis at x = 1.5 + 2 = 3.5.
        assertEquals(new Point2i(6, 0),
                SymmetryHelper.calculateMirrorPoints(1, 0, SymmetryMode.VERTICAL, 4, 4, 2, 0).get(1));
    }

    @Test
    void mirroringIsAnInvolution() {
        for (int x = -3; x < 12; x++) {
            Point2i mirrored = SymmetryHelper.calculateMirrorPoints(x, 0, SymmetryMode.VERTICAL, 9, 1, 1, 0)
                    .get(SymmetryHelper.calculateMirrorPoints(x, 0, SymmetryMode.VERTICAL, 9, 1, 1, 0).size() - 1);
            Point2i back = SymmetryHelper.calculateMirrorPoints(mirrored.x(), 0, SymmetryMode.VERTICAL, 9, 1, 1, 0)
                    .get(SymmetryHelper.calculateMirrorPoints(mirrored.x(), 0, SymmetryMode.VERTICAL, 9, 1, 1, 0).size() - 1);
            assertEquals(x, back.x(), "x=" + x);
        }
    }

    @Test
    @Tag("regression")
    void segmentFromTheAxisKeepsItsPairing() {
        List<Point2i[]> segments = SymmetryHelper.calculateMirrorSegments(4, 1, 7, 1, SymmetryMode.QUADRANT, 9, 9, 0, 0);

        // Vertical image (4,1)-(1,1), horizontal (4,7)-(7,7), diagonal (4,7)-(1,7).
        assertEquals(3, segments.size());
        assertArrayEquals(new Point2i[]{new Point2i(4, 1), new Point2i(1, 1)}, segments.get(0));
        assertArrayEquals(new Point2i[]{new Point2i(4, 7), new Point2i(7, 7)}, segments.get(1));
        assertArrayEquals(new Point2i[]{new Point2i(4, 7), new Point2i(1, 7)}, segments.get(2));
    }

    @Test
    void segmentsIdenticalToTheOriginalOrToEachOtherAreDropped() {
        // A vertical segment lying on the vertical axis mirrors onto itself.
        assertTrue(SymmetryHelper.calculateMirrorSegments(2, 0, 2, 4, SymmetryMode.VERTICAL, 5, 5, 0, 0).isEmpty());

        // In quadrant mode the same segment's diagonal image equals its horizontal one.
        List<Point2i[]> quadrant = SymmetryHelper.calculateMirrorSegments(2, 0, 2, 1, SymmetryMode.QUADRANT, 5, 5, 0, 0);
        assertEquals(1, quadrant.size());
        assertArrayEquals(new Point2i[]{new Point2i(2, 4), new Point2i(2, 3)}, quadrant.get(0));

        // A segment symmetric about the axis maps onto itself reversed.
        assertTrue(SymmetryHelper.calculateMirrorSegments(0, 1, 4, 1, SymmetryMode.VERTICAL, 5, 5, 0, 0).isEmpty());
        assertTrue(SymmetryHelper.calculateMirrorSegments(0, 1, 4, 1, SymmetryMode.NONE, 5, 5, 0, 0).isEmpty());
    }

    @Test
    void segmentImagesMatchThePointImagesWhenNoEndpointIsOnAnAxis() {
        for (SymmetryMode mode : new SymmetryMode[]{SymmetryMode.VERTICAL, SymmetryMode.HORIZONTAL, SymmetryMode.QUADRANT}) {
            List<Point2i> starts = SymmetryHelper.calculateMirrorPoints(1, 1, mode, 10, 10, 0, 0);
            List<Point2i> ends = SymmetryHelper.calculateMirrorPoints(3, 2, mode, 10, 10, 0, 0);
            List<Point2i[]> segments = SymmetryHelper.calculateMirrorSegments(1, 1, 3, 2, mode, 10, 10, 0, 0);

            assertEquals(starts.size() - 1, segments.size(), mode.name());
            for (int i = 0; i < segments.size(); i++) {
                assertEquals(starts.get(i + 1), segments.get(i)[0], mode + " start " + i);
                assertEquals(ends.get(i + 1), segments.get(i)[1], mode + " end " + i);
            }
        }
    }
}
