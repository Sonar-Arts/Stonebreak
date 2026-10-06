package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiSpriteSheet.Fill;
import com.openmason.engine.format.omui.UiSpriteSheet.Frame;
import com.openmason.engine.format.omui.UiSpriteSheet.ScaleMode;
import com.openmason.engine.format.omui.UiSpriteSheet.Slice;
import com.openmason.engine.ui.runtime.UiRect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Nine-slice layout, pixel snapping, minimum-size behaviour and frame timing (#294), canvas-free. */
class SpriteGeometryTest {

    private static final Slice S3 = new Slice(3, 3, 3, 3);

    private static List<SpriteSlices.Patch> nine(UiRect dst, float k, boolean snap, Fill edges, Fill center) {
        return SpriteSlices.layout(0, 0, 12, 12, S3, edges, center, ScaleMode.NINE_SLICE, 0.5, 0.5, dst, k, k, snap);
    }

    /** Patches never overlap and cover the rect exactly (centre included). */
    private static void assertTiles(List<SpriteSlices.Patch> patches, UiRect dst) {
        double area = 0;
        for (SpriteSlices.Patch p : patches) {
            assertTrue(p.dw() > 0 && p.dh() > 0, p::toString);
            assertTrue(p.dx() >= dst.x() - 1e-4 && p.dx() + p.dw() <= dst.right() + 1e-4, p::toString);
            assertTrue(p.dy() >= dst.y() - 1e-4 && p.dy() + p.dh() <= dst.bottom() + 1e-4, p::toString);
            area += (double) p.dw() * p.dh();
            for (SpriteSlices.Patch q : patches) {
                if (q != p) {
                    boolean overlapX = p.dx() < q.dx() + q.dw() - 1e-4 && q.dx() < p.dx() + p.dw() - 1e-4;
                    boolean overlapY = p.dy() < q.dy() + q.dh() - 1e-4 && q.dy() < p.dy() + p.dh() - 1e-4;
                    assertFalse(overlapX && overlapY, p + " overlaps " + q);
                }
            }
        }
        assertEquals((double) dst.width() * dst.height(), area, 1e-2, "the patches cover the rect");
    }

    @Test
    void cornersKeepTheirPixelsAtEverySize() {
        for (float w : new float[]{12, 30, 100, 257}) {
            UiRect dst = new UiRect(10, 20, w, 40);
            List<SpriteSlices.Patch> p = nine(dst, 1, true, Fill.STRETCH, Fill.STRETCH);
            assertEquals(9, p.size());
            SpriteSlices.Patch tl = p.getFirst();
            assertEquals(new SpriteSlices.Patch(0, 0, 3, 3, 10, 20, 3, 3, false, false, 1, 1), tl);
            SpriteSlices.Patch br = p.getLast();
            assertEquals(3, br.dw(), 1e-6, "corners keep their size at width " + w);
            assertEquals(dst.right(), br.dx() + br.dw(), 1e-6);
            assertTiles(p, dst);
        }
    }

    @Test
    void fractionalScaleSnapsCornersToWholeDevicePixelsPerTexel() {
        // 1.25x: an exact 3.75 px corner would duplicate one texel in four; snapped it is 3 (1 px/texel).
        UiRect dst = new UiRect(0, 0, 50, 30);
        SpriteSlices.Patch snapped = nine(dst, 1.25f, true, Fill.STRETCH, Fill.STRETCH).getFirst();
        assertEquals(3, snapped.dw(), 1e-6);
        // 1.5x rounds to 2 px per texel.
        assertEquals(6, nine(dst, 1.5f, true, Fill.STRETCH, Fill.STRETCH).getFirst().dw(), 1e-6);
        // Linear filtering keeps the exact scale.
        assertEquals(3.75, nine(dst, 1.25f, false, Fill.STRETCH, Fill.STRETCH).getFirst().dw(), 1e-6);
        // Snapped geometry: every patch edge on a whole pixel, even from a fractional rect.
        UiRect odd = new UiRect(0.4f, 1.6f, 33.3f, 21.7f);
        List<SpriteSlices.Patch> p = nine(odd, 1.5f, true, Fill.STRETCH, Fill.STRETCH);
        for (SpriteSlices.Patch q : p) {
            assertEquals(Math.round(q.dx()), q.dx(), 0);
            assertEquals(Math.round(q.dx() + q.dw()), q.dx() + q.dw(), 1e-4);
        }
    }

    @Test
    void belowTheMinimumSizeCornersShrinkTogetherAndEdgesVanish() {
        UiRect tiny = new UiRect(0, 0, 4, 20); // narrower than 3 + 3
        List<SpriteSlices.Patch> p = nine(tiny, 1, false, Fill.STRETCH, Fill.STRETCH);
        assertTiles(p, tiny);
        SpriteSlices.Patch tl = p.getFirst();
        assertEquals(2, tl.dw(), 1e-6, "corners scale by 4 / 6");
        assertEquals(2, tl.dh(), 1e-6, "and keep their aspect");
        assertTrue(p.stream().noneMatch(q -> q.sx() == 3 && q.sy() == 0), "no top edge between the corners");
        // Snapped, they stay whole pixels and still fit.
        assertTiles(nine(tiny, 1, true, Fill.STRETCH, Fill.STRETCH), tiny);
        // Degenerate rects draw nothing instead of negative patches.
        assertTrue(nine(new UiRect(0, 0, 0, 10), 1, true, Fill.STRETCH, Fill.STRETCH).isEmpty());
    }

    @Test
    void edgeAndCentreModes() {
        UiRect dst = new UiRect(0, 0, 40, 40);
        List<SpriteSlices.Patch> hollow = nine(dst, 1, true, Fill.TILE, Fill.HIDDEN);
        assertEquals(8, hollow.size(), "a hidden centre is not drawn");
        SpriteSlices.Patch top = hollow.get(1);
        assertTrue(top.tileX() && !top.tileY(), "the top edge tiles along its length only");
        assertEquals(1, top.tileKx(), 1e-6, "at the corner scale");
        SpriteSlices.Patch left = hollow.get(3);
        assertTrue(left.tileY() && !left.tileX());
        List<SpriteSlices.Patch> tiled = nine(dst, 2, true, Fill.STRETCH, Fill.TILE);
        SpriteSlices.Patch centre = tiled.get(4);
        assertTrue(centre.tileX() && centre.tileY());
        assertEquals(2, centre.tileKx(), 1e-6);
        assertFalse(tiled.get(1).tiled(), "stretched edges");
    }

    @Test
    void integerModePlacesByPivot() {
        UiRect dst = new UiRect(0, 0, 50, 30);
        SpriteSlices.Patch centred = SpriteSlices.layout(0, 0, 8, 8, Slice.NONE, Fill.STRETCH, Fill.STRETCH,
                ScaleMode.INTEGER, 0.5, 0.5, dst, 1, 1, true).getFirst();
        assertEquals(24, centred.dw(), 1e-6, "3x is the largest whole scale that fits 30 px");
        assertEquals(13, centred.dx(), 1e-6);
        SpriteSlices.Patch bottomLeft = SpriteSlices.layout(0, 0, 8, 8, Slice.NONE, Fill.STRETCH, Fill.STRETCH,
                ScaleMode.INTEGER, 0, 1, dst, 1, 1, true).getFirst();
        assertEquals(0, bottomLeft.dx(), 1e-6);
        assertEquals(6, bottomLeft.dy(), 1e-6);
    }

    @Test
    void frameTimingLoopsOncesAndPingPongs() {
        List<Frame> f = List.of(new Frame(0, 0, 0.1), new Frame(8, 0, 0.2), new Frame(16, 0, 0.1));
        assertEquals(0, SpriteFrames.frameAt(f, LoopMode.LOOP, 0));
        assertEquals(1, SpriteFrames.frameAt(f, LoopMode.LOOP, 0.1), "a frame starts exactly at its boundary");
        assertEquals(2, SpriteFrames.frameAt(f, LoopMode.LOOP, 0.35));
        assertEquals(0, SpriteFrames.frameAt(f, LoopMode.LOOP, 0.41));
        assertEquals(0.5, SpriteFrames.nextChange(f, LoopMode.LOOP, 0.41), 1e-9);

        assertEquals(2, SpriteFrames.frameAt(f, LoopMode.ONCE, 99), "once holds the last frame");
        assertEquals(Double.POSITIVE_INFINITY, SpriteFrames.nextChange(f, LoopMode.ONCE, 0.35));

        assertEquals(0.6, SpriteFrames.period(f, LoopMode.PING_PONG), 1e-9, "0 1 2 1");
        int[] expected = {0, 1, 1, 2, 1, 1, 0};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], SpriteFrames.frameAt(f, LoopMode.PING_PONG, i * 0.1 + 0.05), "t=" + i);
        }
        assertEquals(0, SpriteFrames.frameAt(List.of(new Frame(0, 0, 1)), LoopMode.LOOP, 5), "one frame is still");
    }
}
