package com.openmason.main.systems.uiEditor.canvas;

import com.openmason.engine.format.omui.UiValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Screen/frame mapping, resize and move math, snapping and align/distribute (#293). */
class CanvasMathTest {

    @Test
    void zoomKeepsThePointUnderThePointer() {
        CanvasTransform t = new CanvasTransform();
        t.setOrigin(100, 50);
        t.set(1f, 20, 30);
        float fx = t.toFrameX(400);
        float fy = t.toFrameY(300);
        t.zoomAt(3f, 400, 300);
        assertEquals(400, t.toScreenX(fx), 1e-3);
        assertEquals(300, t.toScreenY(fy), 1e-3);
        assertEquals(fx, t.toFrameX(t.toScreenX(fx)), 1e-4);
    }

    @Test
    void fitCentresTheFrameWithoutUpscaling() {
        CanvasTransform t = new CanvasTransform();
        t.fit(0, 0, 1920, 1080, 1000, 800, 20, 1f);
        assertEquals((1000 - 40) / 1920f, t.zoom(), 1e-4);
        assertEquals(500, t.toScreenX(960), 1e-2, "frame centre lands on the panel centre");
        t.fit(0, 0, 100, 100, 1000, 800, 20, 1f);
        assertEquals(1f, t.zoom(), 1e-6, "never above maxZoom");
    }

    @Test
    void zoomStepsWalkTheStops() {
        CanvasTransform t = new CanvasTransform();
        t.set(1f, 0, 0);
        assertEquals(1.5f, t.step(1), 1e-6);
        assertEquals(0.75f, t.step(-1), 1e-6);
    }

    @Test
    void handlesHitCornersFirstAndHideMiddlesOnTinyBoxes() {
        assertSame(ResizeHandle.SE, ResizeHandle.hit(0, 0, 100, 50, 101, 51, 6));
        assertSame(ResizeHandle.N, ResizeHandle.hit(0, 0, 100, 50, 50, 1, 6));
        assertNull(ResizeHandle.hit(0, 0, 100, 50, 50, 25, 6));
        assertNotSame(ResizeHandle.N, ResizeHandle.hit(0, 0, 10, 10, 5, 0, 6), "no edge-middle handle on a 10px box");
    }

    @Test
    void resizeConvertsDevicePixelsToLogicalStyle() {
        Box start = new Box(100, 100, 300, 200); // 200 x 100 device px at scale 2 = 100 x 50 logical
        Box end = ResizeMath.resized(start, ResizeHandle.SE, 40, 20);
        Map<String, UiValue> style = ResizeMath.resizeStyle(start, end, ResizeHandle.SE, 2f, false, 0, 0);
        assertEquals(UiValue.of(120), style.get("width"));
        assertEquals(UiValue.of(60), style.get("height"));
        Box nw = ResizeMath.resized(start, ResizeHandle.NW, 20, 10);
        Map<String, UiValue> abs = ResizeMath.resizeStyle(start, nw, ResizeHandle.NW, 2f, true, 50, 40);
        assertEquals(UiValue.of(60), abs.get("left"), "absolute: the moved edge moves the element");
        assertEquals(UiValue.of(45), abs.get("top"));
        assertEquals(UiValue.of(90), abs.get("width"));
        Box crossed = ResizeMath.resized(start, ResizeHandle.E, -500, 0);
        assertTrue(crossed.width() >= 1, "edges never cross");
        assertEquals(Map.of("left", UiValue.of(15), "top", UiValue.of(25)), ResizeMath.moveStyle(10, 20, 10, 10, 2f));
    }

    @Test
    void snappingPrefersEdgesAndReportsGuides() {
        Box moving = new Box(0, 0, 50, 50);
        Box sibling = new Box(100, 0, 150, 50);
        SnapEngine.Settings s = new SnapEngine.Settings(true, true, 8, 5);
        SnapEngine.Result r = SnapEngine.move(moving, 47, 3, List.of(sibling), s);
        assertEquals(50, r.dx(), 1e-4, "the right edge snaps to the sibling's left edge");
        assertEquals(0, r.dy(), 1e-4);
        assertTrue(r.guides().stream().anyMatch(SnapEngine.Guide::vertical));
        SnapEngine.Result grid = SnapEngine.move(moving, 300, 300, List.of(), new SnapEngine.Settings(false, true, 8, 2));
        assertEquals(0, (grid.dx() % 8), 1e-4);
        SnapEngine.Result off = SnapEngine.move(moving, 47, 3, List.of(sibling), SnapEngine.Settings.OFF);
        assertEquals(47, off.dx(), 1e-4);
    }

    @Test
    void alignAndDistributeAbsoluteItems() {
        List<AlignDistribute.Item> items = List.of(
            new AlignDistribute.Item("a", new Box(0, 0, 10, 10), 0, 0),
            new AlignDistribute.Item("b", new Box(40, 20, 60, 30), 40, 20),
            new AlignDistribute.Item("c", new Box(100, 5, 110, 15), 100, 5));
        List<AlignDistribute.Placement> left = AlignDistribute.apply(AlignDistribute.Op.LEFT, items, null, 1f);
        assertEquals(2, left.size(), "the leftmost one does not move");
        assertTrue(left.stream().allMatch(p -> p.left() == 0));
        List<AlignDistribute.Placement> dist = AlignDistribute.apply(AlignDistribute.Op.DISTRIBUTE_X, items, null, 1f);
        // span 0..110, widths 10+20+10 = 40, gaps (110-40)/2 = 35: b goes to 45
        assertEquals(List.of(new AlignDistribute.Placement("b", 45, 20)), dist);
        List<AlignDistribute.Placement> single = AlignDistribute.apply(AlignDistribute.Op.CENTER_X,
            List.of(items.getFirst()), new Box(0, 0, 200, 100), 2f);
        assertEquals(48, single.getFirst().left(), 1e-4, "one item centres in its parent; offsets are logical");
    }
}
