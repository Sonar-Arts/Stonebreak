package com.stonebreak.rendering.gameWorld.regions;

import com.stonebreak.world.chunk.Chunk;
import com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The legacy-handle fallback routing is the one seam every &gt;65,536-vertex stamp
 * mesh renders through. u32 stamp meshes refuse region residency at upload
 * ({@code ChunkRegionRenderer.upload} — the region arenas are hard-coded u16), and
 * the shipped draw path never drew the resulting legacy stamp handle on any pass:
 * a chunk whose stamp mesh crossed 65,536 vertices (e.g. ~40 cacti at 1080 verts
 * per cell) vanished from the render while its block data and lighting heightmap
 * stayed intact — the "disappear but leave behind shadows" defect.
 *
 * <p>{@code MmsRenderableHandle} is final and GL-thread-only, so the routing is
 * pinned over pure presence flags and the chunk's handle layout over mocks — the
 * properties that matter are which pass reaches which handle, and that no chunk
 * ever draws a handle twice.
 */
class ChunkRegionRendererLegacyFallbackTest {

    private static final int ATLAS = ChunkRegionRenderer.LAYER_ATLAS;
    private static final int STAMP = ChunkRegionRenderer.LAYER_STAMP;
    private static final int WATER = ChunkRegionRenderer.LAYER_WATER;

    @Test
    void routingPinsTheFallbackCoverage() {
        // u32 stamp chunk (region-resident atlas + legacy stamp handle): drawn
        // ONLY through the stamp fallback — the shipped hole never reached it.
        assertTrue(ChunkRegionRenderer.needsLegacyDraw(false, false, true, STAMP),
                "a >65,536-vertex stamp mesh must render through the stamp fallback");
        assertFalse(ChunkRegionRenderer.needsLegacyDraw(false, false, true, ATLAS),
                "the atlas fallback must not draw a stamp-only chunk (the atlas is region-resident)");

        // Both handles legacy: the atlas fallback draws both via chunk.render() —
        // the stamp fallback must not also join, or both draw twice.
        assertTrue(ChunkRegionRenderer.needsLegacyDraw(false, true, true, ATLAS));
        assertFalse(ChunkRegionRenderer.needsLegacyDraw(false, true, true, STAMP),
                "an atlas-drawn chunk already drew its stamp alongside the atlas");

        // Region-resident everywhere: no legacy fallback at all.
        assertFalse(ChunkRegionRenderer.needsLegacyDraw(false, false, false, ATLAS));
        assertFalse(ChunkRegionRenderer.needsLegacyDraw(false, false, false, STAMP));

        // Water routing unchanged.
        assertTrue(ChunkRegionRenderer.needsLegacyDraw(true, false, false, WATER));
        assertFalse(ChunkRegionRenderer.needsLegacyDraw(true, false, false, ATLAS),
                "water never draws through the atlas fallback");
    }

    @Test
    void anAtlasResidentChunkWithALegacyStampHandleCarriesAnOrphanedStamp() {
        // Region-resident atlas + legacy stamp handle — the u32-stamp chunk layout.
        Chunk chunk = new Chunk(0, 0);
        chunk.setStampRenderableHandle(mock(MmsRenderableHandle.class));
        assertFalse(ChunkRegionRenderer.drawsOrphanedLegacyStamp(chunk, STAMP),
                "the orphaned-stamp fallback only rides the atlas layer's legacy pass");
        assertTrue(ChunkRegionRenderer.drawsOrphanedLegacyStamp(chunk, ATLAS),
                "an atlas-resident chunk carrying a legacy stamp handle must draw it in the fallback");

        // An atlas-drawn chunk (both handles legacy) already drew its stamp
        // alongside the atlas via chunk.render() — not orphaned.
        Chunk both = new Chunk(0, 0);
        both.setStampRenderableHandle(mock(MmsRenderableHandle.class));
        both.setMmsRenderableHandle(mock(MmsRenderableHandle.class));
        assertFalse(ChunkRegionRenderer.drawsOrphanedLegacyStamp(both, ATLAS),
                "an atlas-drawn chunk's stamp is not orphaned — the fallback would double-draw it");

        // A region-resident stamp (its mesh joined the stamp region) is drawn by
        // the stamp pass — not orphaned.
        Chunk regionStamp = new Chunk(0, 0);
        regionStamp.setRegionStampHandle(
                mock(com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle.class));
        regionStamp.setStampRenderableHandle(mock(MmsRenderableHandle.class));
        assertFalse(ChunkRegionRenderer.drawsOrphanedLegacyStamp(regionStamp, ATLAS),
                "a region-resident stamp is drawn by the stamp pass, not the fallback");
    }
}
