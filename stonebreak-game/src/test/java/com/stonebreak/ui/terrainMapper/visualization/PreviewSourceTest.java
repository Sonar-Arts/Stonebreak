package com.stonebreak.ui.terrainMapper.visualization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreviewSourceTest {

    private static final TerrainColumns DETAIL = (x, z, out) -> out[PreviewChannel.HEIGHT.ordinal()] = 100f;
    private static final TerrainColumns OVERVIEW = (x, z, out) -> out[PreviewChannel.HEIGHT.ordinal()] = 7f;

    @Test
    void farZoomReadsTheOverviewAndCloseZoomTheFullTiles() {
        PreviewSource source = new PreviewSource(1L, DETAIL, new PreviewSampleStore(8L << 20), OVERVIEW, 8);
        assertEquals(100f, source.valueAt(PreviewChannel.HEIGHT, 1, 0, 0), 0f);
        assertEquals(100f, source.valueAt(PreviewChannel.HEIGHT, 4, 0, 0), 0f);
        assertEquals(7f, source.valueAt(PreviewChannel.HEIGHT, 8, 0, 0), 0f);
        assertEquals(7f, source.valueAt(PreviewChannel.HEIGHT, 16, 64, 64), 0f);
        // Back to close zoom on the same ground: the store keeps the two apart by spacing.
        assertEquals(100f, source.valueAt(PreviewChannel.HEIGHT, 2, 0, 0), 0f);
    }

    @Test
    void closeZoomSamplesAreNotCopiedIntoTheOverviewLevels() {
        PreviewSampleStore store = new PreviewSampleStore(8L << 20);
        PreviewSource source = new PreviewSource(1L, DETAIL, store, OVERVIEW, 8);
        source.valueAt(PreviewChannel.HEIGHT, 1, 64, 64);   // a full-detail sample on a coarse lattice point
        assertEquals(100f, source.valueAt(PreviewChannel.HEIGHT, 4, 64, 64), 0f, "still reused below the boundary");
        assertEquals(7f, source.valueAt(PreviewChannel.HEIGHT, 8, 64, 64), 0f, "overview levels read the overview");
        assertEquals(7f, source.valueAt(PreviewChannel.HEIGHT, 64, 64, 64), 0f);
    }

    @Test
    void withoutAnOverviewEveryZoomReadsTheFullTiles() {
        PreviewSource source = new PreviewSource(1L, DETAIL, new PreviewSampleStore(8L << 20));
        assertEquals(100f, source.valueAt(PreviewChannel.HEIGHT, 16, 0, 0), 0f);
    }
}
