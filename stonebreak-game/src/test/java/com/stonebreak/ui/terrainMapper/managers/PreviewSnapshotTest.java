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
    void worldExtentScalesWithZoom() {
        // Zoomed 2x, the same pixel grid covers half as much world.
        PreviewSnapshot snapshot = snapshotAt(0f, 0f, 2f);
        assertEquals(-200f, snapshot.worldLeft(), 0.001f);
        assertEquals(400f, snapshot.worldWidth(), 0.001f);
    }

    @Test
    void redrawingIntoAnUnmovedViewportIsAPlainOneToOneBlit() {
        // Mirrors TerrainMapRenderer.drawSnapshot: with the viewport unchanged the destination
        // must land back on the map rect exactly, or the image would drift on a still map.
        float panX = 1234f;
        float panZ = -567f;
        float zoom = 1f;
        PreviewSnapshot snapshot = snapshotAt(panX, panZ, zoom);

        float mapX = 320f;
        float mapY = 0f;
        float dstX = mapX + 800 * 0.5f + (snapshot.worldLeft() - panX) * zoom;
        float dstY = mapY + 600 * 0.5f + (snapshot.worldTop() - panZ) * zoom;

        assertEquals(mapX, dstX, 0.001f);
        assertEquals(mapY, dstY, 0.001f);
        assertEquals(800f, snapshot.worldWidth() * zoom, 0.001f);
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
