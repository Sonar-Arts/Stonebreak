package com.stonebreak.ui.terrainMapper.managers;

import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The geometry that lets a finished sample be drawn correctly after the viewport has already
 * moved on. Getting this wrong doesn't crash anything — it just slides the map slightly out
 * from under the cursor while panning, which is exactly the kind of bug that is hard to see and
 * easy to assert.
 *
 * <p>{@code image} is null throughout: none of the geometry touches it, and a real Skija image
 * would need a native library load.
 */
class PreviewSnapshotTest {

    private static final NoiseVisualizer STUB = new NoiseVisualizer() {
        @Override public String displayName() { return "stub"; }
        @Override public float sample(int worldX, int worldZ) { return 0f; }
    };

    /** 800x600 rect, step 2 -> a 400x300 sample grid; viewport centred on the origin at 1:1. */
    private static PreviewSnapshot snapshotAt(float panX, float panZ, float zoom) {
        SampleRequest request = new SampleRequest(STUB, null, 800, 600, 2, panX, panZ, zoom);
        float[] raw = new float[400 * 300];
        for (int z = 0; z < 300; z++) {
            for (int x = 0; x < 400; x++) {
                raw[z * 400 + x] = z * 400 + x;
            }
        }
        return new PreviewSnapshot(request, null, raw, 400, 300, true);
    }

    @Test
    void coversExactlyTheRectItWasSampledFor() {
        PreviewSnapshot snapshot = snapshotAt(0f, 0f, 1f);
        // At 1:1 the 800x600 rect centred on the origin spans world x[-400,400), z[-300,300).
        assertEquals(-400f, snapshot.worldLeft(), 0.001f);
        assertEquals(-300f, snapshot.worldTop(), 0.001f);
        assertEquals(800f, snapshot.worldWidth(), 0.001f);
        assertEquals(600f, snapshot.worldHeight(), 0.001f);
    }

    @Test
    void aMarginWidensTheExtentEvenlyAroundThePan() {
        // 800x600 plus 100 px each side at step 2 -> a 500x400 grid over world x[-500,500), z[-400,400).
        SampleRequest request = new SampleRequest(STUB, null, 800, 600, 2, 0f, 0f, 1f, 100, null);
        PreviewSnapshot snapshot = new PreviewSnapshot(request, null, new float[500 * 400], 500, 400, true);
        assertEquals(-500f, snapshot.worldLeft(), 0.001f);
        assertEquals(-400f, snapshot.worldTop(), 0.001f);
        assertEquals(1000f, snapshot.worldWidth(), 0.001f);
        assertEquals(800f, snapshot.worldHeight(), 0.001f);
        assertEquals(snapshotAt(0f, 0f, 1f).worldLeft(), request.core().worldXAt(0f), 0.001f,
                "the core must be exactly the visible rect");
    }

    @Test
    void worldExtentScalesWithZoom() {
        // Zoomed 2x, the same pixel grid covers half as much world.
        PreviewSnapshot snapshot = snapshotAt(0f, 0f, 2f);
        assertEquals(-200f, snapshot.worldLeft(), 0.001f);
        assertEquals(400f, snapshot.worldWidth(), 0.001f);
    }

    @Test
    void theLatticeIsWorldAlignedAndCoversTheWholeView() {
        // Mirrors TerrainMapRenderer.drawSnapshot. The image is snapped to the world lattice so
        // values can be reused, which may start it up to one cell before the map's edge — but
        // never after it, or a sliver of the map would be left blank.
        float panX = 1234f;
        float panZ = -567f;
        float zoom = 1f;
        SampleRequest request = new SampleRequest(STUB, null, 800, 600, 2, panX, panZ, zoom);
        int spacing = request.spacing();
        PreviewSnapshot snapshot = new PreviewSnapshot(request, null,
                new float[request.latticeColumns() * request.latticeRows()],
                request.latticeColumns(), request.latticeRows(), true);

        float mapX = 320f;
        float mapY = 0f;
        float dstX = mapX + 800 * 0.5f + (snapshot.worldLeft() - panX) * zoom;
        float dstY = mapY + 600 * 0.5f + (snapshot.worldTop() - panZ) * zoom;

        assertEquals(0, Math.floorMod((int) snapshot.worldLeft(), spacing), "left edge on the lattice");
        assertEquals(0, Math.floorMod((int) snapshot.worldTop(), spacing), "top edge on the lattice");
        assertTrue(dstX <= mapX && dstX > mapX - spacing * zoom, "dstX=" + dstX);
        assertTrue(dstY <= mapY && dstY > mapY - spacing * zoom, "dstY=" + dstY);
        assertTrue(dstX + snapshot.worldWidth() * zoom >= mapX + 800f, "must reach the right edge");
        assertTrue(dstY + snapshot.worldHeight() * zoom >= mapY + 600f, "must reach the bottom edge");
    }

    @Test
    void spacingIsThePowerOfTwoNearestTheZoom() {
        assertEquals(2, new SampleRequest(STUB, null, 800, 600, 2, 0f, 0f, 1f).spacing());
        assertEquals(1, new SampleRequest(STUB, null, 800, 600, 2, 0f, 0f, 1.5f).spacing(), "1.33 blocks");
        assertEquals(2, new SampleRequest(STUB, null, 800, 600, 2, 0f, 0f, 0.8f).spacing(), "2.5 blocks");
        assertEquals(4, new SampleRequest(STUB, null, 800, 600, 2, 0f, 0f, 0.6f).spacing(), "3.33 blocks");
        assertEquals(1, new SampleRequest(STUB, null, 800, 600, 2, 0f, 0f, 8f).spacing(), "never below a block");
        assertEquals(64, new SampleRequest(STUB, null, 800, 600, 6, 0f, 0f, 0.0625f).spacing(), "capped");
    }

    @Test
    void aSlightZoomLandsOnTheSameWorldPoints() {
        // The property the value cache depends on: within an octave, zooming changes how much of
        // the lattice is in view, not where its points are.
        SampleRequest before = new SampleRequest(STUB, null, 800, 600, 2, 37f, -91f, 1f);
        SampleRequest after = new SampleRequest(STUB, null, 800, 600, 2, 37f, -91f, 1.1f);
        assertEquals(before.spacing(), after.spacing());
        assertEquals(0, Math.floorMod(after.latticeOriginX() - before.latticeOriginX(), before.spacing()));
        assertEquals(0, Math.floorMod(after.latticeOriginZ() - before.latticeOriginZ(), before.spacing()));
    }

    @Test
    void panningShiftsTheImageByExactlyThePanDistance() {
        float zoom = 1f;
        PreviewSnapshot snapshot = snapshotAt(0f, 0f, zoom);
        // User dragged the world 50 blocks east; the stale image must move 50 px west.
        float dstAtOrigin = 800 * 0.5f + (snapshot.worldLeft() - 0f) * zoom;
        float dstAfterPan = 800 * 0.5f + (snapshot.worldLeft() - 50f) * zoom;
        assertEquals(-50f, dstAfterPan - dstAtOrigin, 0.001f);
    }

    @Test
    void readsBackTheValueUnderAWorldPosition() {
        PreviewSnapshot snapshot = snapshotAt(0f, 0f, 1f);
        // Sample cell (sx, sz) covers step*step blocks starting at worldLeft + sx*step.
        assertEquals(0f, snapshot.valueAt(-400f, -300f), 0.001f, "top-left cell");
        assertEquals(1f, snapshot.valueAt(-398f, -300f), 0.001f, "one cell east");
        assertEquals(400f, snapshot.valueAt(-400f, -298f), 0.001f, "one cell south");
        assertEquals(400 * 300 - 1f, snapshot.valueAt(399f, 299f), 0.001f, "bottom-right cell");
    }

    @Test
    void reportsNaNOutsideItsCoverage() {
        PreviewSnapshot snapshot = snapshotAt(0f, 0f, 1f);
        // Hovering terrain this snapshot never sampled must read as "no value", not as a
        // clamped edge pixel — the footer should go blank rather than lie.
        assertTrue(Float.isNaN(snapshot.valueAt(-401f, 0f)), "west of coverage");
        assertTrue(Float.isNaN(snapshot.valueAt(400f, 0f)), "east of coverage");
        assertTrue(Float.isNaN(snapshot.valueAt(0f, -301f)), "north of coverage");
        assertTrue(Float.isNaN(snapshot.valueAt(0f, 300f)), "south of coverage");
    }

    @Test
    void aPartialCoversOnlyTheRowsItActuallyHas() {
        // A band-by-band pass publishes the top of the grid before the rest exists. Such a
        // snapshot must claim only the world it sampled, or the renderer would stretch a third
        // of a map over the whole viewport.
        SampleRequest request = new SampleRequest(STUB, null, 800, 600, 2, 0f, 0f, 1f);
        PreviewSnapshot partial = new PreviewSnapshot(request, null, new float[400 * 100], 400, 100, false);

        assertEquals(-300f, partial.worldTop(), 0.001f, "starts at the top of the requested rect");
        assertEquals(200f, partial.worldHeight(), 0.001f, "100 rows of 2 blocks, not the full 600");
        assertEquals(800f, partial.worldWidth(), 0.001f, "full width — bands are whole rows");
        assertTrue(Float.isNaN(partial.valueAt(0f, -99f)), "rows not sampled yet read as blank");
    }

    @Test
    void hoverLookupAgreesWithTheScreenPixelUnderTheCursor() {
        // The whole point of reading through the snapshot: the number in the footer must be the
        // one behind the pixel being drawn, even when the viewport has moved since the sample.
        PreviewSnapshot snapshot = snapshotAt(0f, 0f, 1f);
        SampleRequest request = snapshot.request();

        for (int screenX : new int[] {0, 1, 2, 3, 399, 400, 797, 799}) {
            float worldX = request.worldXAt(screenX + 0.5f);
            float value = snapshot.valueAt(worldX, request.worldZAt(0.5f));
            assertEquals(screenX / 2, (int) value, "sample column for screen x=" + screenX);
        }
    }
}
