package com.openmason.main.systems.menus.textureCreator.tools.move;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.selection.RectangularSelection;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move-tool geometry: what a translate / rotate / flip (negative scale) / scale
 * does to a captured selection, plus the {@link TransformationState} value rules.
 */
class TransformMathTest {

    private static final int A = 0xFF0000AA;
    private static final int B = 0xFF0000BB;
    private static final int C = 0xFF0000CC;
    private static final int D = 0xFF0000DD;

    /** A 3x1 strip A,B,C at (2..4, 2) on an 8x8 canvas. */
    private static SelectionSnapshot strip() {
        PixelCanvas canvas = new PixelCanvas(8, 8);
        canvas.setPixel(2, 2, A);
        canvas.setPixel(3, 2, B);
        canvas.setPixel(4, 2, C);
        return SelectionSnapshot.capture(canvas, new RectangularSelection(2, 2, 4, 2));
    }

    @Test
    void identityReturnsACopyOfTheSnapshot() {
        SelectionSnapshot snapshot = strip();

        TransformedImage image = TransformMath.computeTransformedImage(snapshot, TransformationState.identity());

        assertEquals(new Rectangle(2, 2, 3, 1), image.bounds());
        assertArrayEquals(new int[]{A, B, C}, image.pixels());
        assertEquals(3, image.pixelCount());
        image.pixels()[0] = 0;
        assertEquals(A, snapshot.pixels()[0], "the result must not alias the snapshot");
    }

    @Test
    void translationShiftsBoundsAndKeepsPixelOrder() {
        TransformedImage image = TransformMath.computeTransformedImage(strip(),
                TransformationState.identity().withTranslation(2, 3));

        assertEquals(new Rectangle(4, 5, 3, 1), image.bounds());
        assertArrayEquals(new int[]{A, B, C}, image.pixels());
    }

    @Test
    void ninetyDegreesTurnsTheRowIntoAColumnAboutItsCentre() {
        TransformedImage image = TransformMath.computeTransformedImage(strip(),
                TransformationState.identity().withRotation(90));

        assertEquals(new Rectangle(3, 1, 1, 3), image.bounds());
        assertArrayEquals(new int[]{A, B, C}, image.pixels(), "+90 deg (y down) carries the left end to the top");
    }

    @Test
    void minusNinetyAndTwoSeventyAgree() {
        TransformedImage minus90 = TransformMath.computeTransformedImage(strip(),
                TransformationState.identity().withRotation(-90));
        TransformedImage plus270 = TransformMath.computeTransformedImage(strip(),
                TransformationState.identity().withRotation(270));

        assertEquals(new Rectangle(3, 1, 1, 3), minus90.bounds());
        assertArrayEquals(new int[]{C, B, A}, minus90.pixels());
        assertEquals(minus90.bounds(), plus270.bounds());
        assertArrayEquals(minus90.pixels(), plus270.pixels());
    }

    @Test
    void oneEightyReversesInPlace() {
        TransformedImage image = TransformMath.computeTransformedImage(strip(),
                TransformationState.identity().withRotation(180));

        assertEquals(new Rectangle(2, 2, 3, 1), image.bounds());
        assertArrayEquals(new int[]{C, B, A}, image.pixels());
    }

    @Test
    void negativeScaleFlipsHorizontallyAndVertically() {
        PixelCanvas canvas = new PixelCanvas(6, 6);
        canvas.setPixel(1, 1, A);
        canvas.setPixel(2, 1, B);
        canvas.setPixel(1, 2, C);
        canvas.setPixel(2, 2, D);
        SelectionSnapshot square = SelectionSnapshot.capture(canvas, new RectangularSelection(1, 1, 2, 2));

        TransformedImage flipX = TransformMath.computeTransformedImage(square,
                TransformationState.identity().withScale(-1, 1));
        TransformedImage flipY = TransformMath.computeTransformedImage(square,
                TransformationState.identity().withScale(1, -1));

        assertEquals(new Rectangle(1, 1, 2, 2), flipX.bounds());
        assertArrayEquals(new int[]{B, A, D, C}, flipX.pixels());
        assertEquals(new Rectangle(1, 1, 2, 2), flipY.bounds());
        assertArrayEquals(new int[]{C, D, A, B}, flipY.pixels());
    }

    @Test
    void doublingAnEvenSquareRepeatsEachPixelTwoByTwo() {
        PixelCanvas canvas = new PixelCanvas(8, 8);
        canvas.setPixel(3, 3, A);
        canvas.setPixel(4, 3, B);
        canvas.setPixel(3, 4, C);
        canvas.setPixel(4, 4, D);
        SelectionSnapshot square = SelectionSnapshot.capture(canvas, new RectangularSelection(3, 3, 4, 4));

        TransformedImage image = TransformMath.computeTransformedImage(square,
                TransformationState.identity().withScale(2, 2));

        assertEquals(new Rectangle(2, 2, 4, 4), image.bounds());
        assertArrayEquals(new int[]{
                A, A, B, B,
                A, A, B, B,
                C, C, D, D,
                C, C, D, D}, image.pixels());
        assertEquals(16, image.pixelCount());
    }

    /**
     * Upscaling an odd-sized selection lost its outer row/column: the destination
     * bounds were derived from the corner pixel <em>centres</em> plus half a pixel,
     * which under-covers by {@code (scale - 1) / 2} pixels on every side.
     */
    @Test
    @Tag("regression")
    void doublingAnOddStripKeepsEveryRowAndColumn() {
        TransformedImage image = TransformMath.computeTransformedImage(strip(),
                TransformationState.identity().withScale(2, 2));

        // 3x1 centred on (3.5, 2.5) becomes 6x2 spanning x 0.5..6.5, y 1.5..3.5.
        assertEquals(new Rectangle(0, 1, 6, 2), image.bounds());
        assertArrayEquals(new int[]{
                A, A, B, B, C, C,
                A, A, B, B, C, C}, image.pixels());
    }

    @Test
    void shrinkingNeverInventsPixels() {
        TransformedImage image = TransformMath.computeTransformedImage(strip(),
                TransformationState.identity().withScale(0.5, 1));

        assertTrue(image.pixelCount() >= 1 && image.pixelCount() <= 2, "count=" + image.pixelCount());
        for (int p : image.pixels()) {
            assertTrue(p == A || p == B || p == C, Integer.toHexString(p));
        }
    }

    @Test
    void movingEntirelyOffCanvasYieldsAnEmptyImage() {
        TransformedImage image = TransformMath.computeTransformedImage(strip(),
                TransformationState.identity().withTranslation(100, 0));

        assertTrue(image.isEmpty());
        assertEquals(0, image.width());
    }

    @Test
    void partiallyOffCanvasIsClipped() {
        TransformedImage image = TransformMath.computeTransformedImage(strip(),
                TransformationState.identity().withTranslation(4, 0)); // x 6..8, canvas ends at 7

        assertEquals(new Rectangle(6, 2, 2, 1), image.bounds());
        assertArrayEquals(new int[]{A, B}, image.pixels());
    }

    @Test
    void unselectedPixelsInsideTheBoundsStayOutOfTheResult() {
        PixelCanvas canvas = new PixelCanvas(6, 6);
        canvas.fill(A);
        // L-shaped selection: only (1,1),(1,2),(2,2) of the 2x2 box.
        var lShape = new com.openmason.main.systems.menus.textureCreator.selection.SelectionRegion() {
            @Override public boolean contains(int x, int y) {
                return (x == 1 && (y == 1 || y == 2)) || (x == 2 && y == 2);
            }
            @Override public Rectangle getBounds() { return new Rectangle(1, 1, 2, 2); }
            @Override public SelectionType getType() { return SelectionType.FREEFORM; }
            @Override public boolean isEmpty() { return false; }
            @Override public com.openmason.main.systems.menus.textureCreator.selection.SelectionRegion translate(int dx, int dy) {
                throw new UnsupportedOperationException();
            }
        };
        SelectionSnapshot snapshot = SelectionSnapshot.capture(canvas, lShape);

        TransformedImage image = TransformMath.computeTransformedImage(snapshot,
                TransformationState.identity().withTranslation(2, 0));

        assertEquals(3, image.pixelCount());
        assertArrayEquals(new boolean[]{true, false, true, true}, image.mask());
        assertFalse(TransformedSelectionRegion.fromImage(image).contains(4, 1));
        assertTrue(TransformedSelectionRegion.fromImage(image).contains(3, 1));
    }

    @Test
    void canvasToLocalInvertsLocalToCanvas() {
        Rectangle bounds = new Rectangle(5, 7, 9, 4);
        PixelCanvas canvas = new PixelCanvas(32, 32);
        SelectionSnapshot snapshot = SelectionSnapshot.capture(canvas, new RectangularSelection(bounds));
        Random random = new Random(221);

        for (int i = 0; i < 200; i++) {
            TransformationState t = new TransformationState(
                    random.nextDouble() * 20 - 10, random.nextDouble() * 20 - 10,
                    (random.nextBoolean() ? 1 : -1) * (0.25 + random.nextDouble() * 3),
                    (random.nextBoolean() ? 1 : -1) * (0.25 + random.nextDouble() * 3),
                    random.nextDouble() * 720 - 360);
            double lx = random.nextDouble() * bounds.width;
            double ly = random.nextDouble() * bounds.height;

            double[] canvasPoint = TransformMath.mapLocalToCanvas(lx, ly, snapshot, t);
            double[] back = TransformMath.mapCanvasToLocal(canvasPoint[0], canvasPoint[1], snapshot, t);

            assertEquals(lx, back[0], 1e-9, t.toString());
            assertEquals(ly, back[1], 1e-9, t.toString());
        }
    }

    @Test
    void stateNormalisesRotationIntoMinus180To180() {
        assertEquals(-90.0, TransformationState.identity().withRotation(270).rotationDegrees(), 1e-12);
        assertEquals(170.0, TransformationState.identity().withRotation(-190).rotationDegrees(), 1e-12);
        assertEquals(10.0, TransformationState.identity().withRotation(370).rotationDegrees(), 1e-12);
        assertEquals(180.0, TransformationState.identity().withRotation(540).rotationDegrees(), 1e-12);
    }

    @Test
    void stateKeepsScaleAwayFromZeroButPreservesItsSign() {
        TransformationState zero = TransformationState.identity().withScale(0, -0.001);
        assertEquals(0.01, zero.scaleX(), 1e-12);
        assertEquals(-0.01, zero.scaleY(), 1e-12);
        assertEquals(-2.0, TransformationState.identity().withScale(-2, 1).scaleX(), 1e-12);
    }

    @Test
    void identityDetectionToleratesRoundOffOnly() {
        assertTrue(TransformationState.identity().isIdentity());
        assertTrue(new TransformationState(1e-9, 0, 1 + 1e-9, 1, 360).isIdentity());
        assertFalse(TransformationState.identity().withTranslation(0.5, 0).isIdentity());
        assertFalse(TransformationState.identity().withScale(-1, 1).isIdentity(), "a flip is a change");
        assertEquals(TransformationState.identity().addTranslation(1, 2).addTranslation(3, 4),
                TransformationState.identity().withTranslation(4, 6));
    }
}
