package com.stonebreak.ui.terrainMapper.managers;

import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Panning must not restart sampling while the view is still inside what has been preloaded.
 * Before the planner, every drag frame produced a new request and abandoned the pass in flight,
 * so the map never finished filling in while it moved.
 *
 * <p>The registry is null throughout: the planner only compares it by identity.
 */
class PreviewRequestPlannerTest {

    private static final int W = 800;
    private static final int H = 600;
    private static final int FINE = 2;
    private static final int COARSE = 6;

    private static final NoiseVisualizer ONE = stub("one");
    private static final NoiseVisualizer OTHER = stub("other");

    private static NoiseVisualizer stub(String name) {
        return new NoiseVisualizer() {
            @Override public String displayName() { return name; }
            @Override public float sample(int worldX, int worldZ) { return 0f; }
        };
    }

    private final PreviewRequestPlanner planner = new PreviewRequestPlanner();

    private SampleRequest plan(float panX, float panZ, float zoom, int step) {
        return planner.plan(ONE, null, W, H, panX, panZ, zoom, step);
    }

    private static int halfMargin() {
        return PreviewRequestPlanner.marginFor(W, H) / 2;
    }

    @Test
    void theAnchorCarriesAPreloadMargin() {
        SampleRequest anchor = plan(0f, 0f, 1f, FINE);
        assertTrue(anchor.marginPx() > 0, "nothing beyond the visible rect would be preloaded");
        assertEquals(W, anchor.widthPx());
        assertEquals(H, anchor.heightPx());
    }

    @Test
    void aSmallPanKeepsTheSameRequest() {
        SampleRequest anchor = plan(0f, 0f, 1f, FINE);
        assertSame(anchor, plan(halfMargin(), -halfMargin(), 1f, FINE),
                "a pan inside the preload must not abandon the pass that is filling it");
    }

    @Test
    void panningTowardThePreloadEdgeReAnchorsOnTheCurrentView() {
        plan(0f, 0f, 1f, FINE);
        SampleRequest moved = plan(halfMargin() + 1f, 0f, 1f, FINE);
        assertEquals(halfMargin() + 1f, moved.panX(), 0f);
    }

    @Test
    void driftIsMeasuredOnScreenNotInWorldBlocks() {
        // Zoomed in 4x, the same world distance is four times as many screen pixels.
        plan(0f, 0f, 4f, FINE);
        SampleRequest moved = plan(halfMargin() / 4f + 1f, 0f, 4f, FINE);
        assertEquals(halfMargin() / 4f + 1f, moved.panX(), 0f);
    }

    @Test
    void zoomSizeOrVisualizerChangesReAnchor() {
        SampleRequest anchor = plan(0f, 0f, 1f, FINE);
        assertNotSame(anchor, plan(0f, 0f, 1.15f, FINE));

        anchor = plan(0f, 0f, 1f, FINE);
        assertNotSame(anchor, planner.plan(ONE, null, W + 10, H, 0f, 0f, 1f, FINE));

        anchor = planner.plan(ONE, null, W, H, 0f, 0f, 1f, FINE);
        assertNotSame(anchor, planner.plan(OTHER, null, W, H, 0f, 0f, 1f, FINE));
    }

    @Test
    void startingADragDoesNotThrowAwayAFineMap() {
        SampleRequest fine = plan(0f, 0f, 1f, FINE);
        assertSame(fine, plan(5f, 5f, 1f, COARSE));
    }

    @Test
    void aCoarseAnchorUpgradesOnceInteractionEnds() {
        plan(0f, 0f, 1f, COARSE);
        SampleRequest atRest = plan(3f, 0f, 1f, FINE);
        assertEquals(FINE, atRest.step());
        assertEquals(3f, atRest.panX(), 0f, "the upgrade samples where the user stopped");
    }

    @Test
    void resetForgetsTheAnchor() {
        SampleRequest anchor = plan(0f, 0f, 1f, FINE);
        planner.reset();
        assertNotSame(anchor, plan(0f, 0f, 1f, FINE));
    }
}
