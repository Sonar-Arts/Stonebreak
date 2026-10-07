package com.openmason.engine.ui.runtime.binding;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataCollection;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.masonry.MKeys;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.engine.ui.runtime.input.InputDevice;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.openmason.engine.ui.runtime.paint.UiPainter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.runtime.binding.BindingRig.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #326: keyboard/controller navigation walks a virtualized ListView grid past the visible
 * window in every direction, scrolling it a line at a time and keeping its column, with focus
 * following its item through the row recycling.
 */
class GridNavigationTest {

    private static final HostContract INV = HostContract.of("stonebreak:inventory", 1);
    private static final DataType.ListOf SLOTS = DataType.list(
        DataType.object("id", DataType.string(), "name", DataType.string()), "id");
    private static final int COLUMNS = 9;
    private static final int ITEMS = 200;
    private static final float WIDTH = 360; // 40 px cells
    private static final float HEIGHT = 400;

    /** A host frame like the game's: layout (input sync, binder sync) after every input. */
    private static final class Rig implements AutoCloseable {
        final UiDocumentInstance ui;
        final UiBinder binder;
        final UiDocumentView view;

        Rig(int itemHeight, int columns) {
            UiHost host = new UiHost();
            DataCollection inv = host.data().register("inventory", new DataCollection(SLOTS), INV);
            List<UiValue> items = new ArrayList<>();
            for (int i = 0; i < ITEMS; i++) {
                items.add(obj("id", "s" + i, "name", "item" + i));
            }
            inv.setAll(items);
            UiDocs.N list = node("list", "ListView").style("height", 100).prop("columns", columns)
                .bind("prop:items", "inventory").kids(
                    box("slot").prop("focusable", true).kids(label("name", "?").bind("prop:text", ".name")));
            if (itemHeight > 0) {
                list = list.prop("itemHeight", itemHeight);
            }
            OmuiArchive doc = BindingRig.withData(screen("t:ui/x",
                box("root").style("width", WIDTH).style("height", HEIGHT).kids(list)), "stonebreak:inventory@1");
            ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic().withMeasurer(UiDocs.FIXED_TEXT));
            binder = UiBinder.open(ui, host, UiConverters.NONE);
            view = new UiDocumentView(ui, new UiPainter(UiPaintHost.NONE, null)).bind(binder);
            frame();
        }

        void frame() {
            view.layout((int) WIDTH, (int) HEIGHT, 1, 1);
        }

        ListBinding list() {
            return binder.list("list");
        }

        void press(int key) {
            view.input().keyDown(key, 0, false);
            view.input().keyUp(key, 0);
            frame();
        }

        UiElement focused() {
            return view.input().focus().focused();
        }

        /** The item the focused slot shows. */
        String focusedItem() {
            return BindingRig.label(focused()).text("text");
        }

        /** The list's slot showing {@code item}. */
        UiElement slotShowing(int item) {
            for (UiElement r : list().rows()) {
                if (("item" + item).equals(BindingRig.label(r).text("text"))) {
                    return r;
                }
            }
            throw new AssertionError("item" + item + " is not realized");
        }

        @Override
        public void close() {
            view.close();
        }
    }

    private static void assertInView(Rig r, String why) {
        UiRect f = r.focused().rect();
        UiRect v = r.ui.find("list").rect();
        assertTrue(f.y() >= v.y() - 0.5f && f.bottom() <= v.bottom() + 0.5f,
            why + ": the focused slot " + f + " is scrolled into the list " + v);
    }

    @Test
    void downAndUpWalkTheWholeGridInOneColumn() {
        try (Rig r = new Rig(40, COLUMNS)) {
            assertTrue(r.list().rows().size() < ITEMS, "the grid is virtualized");
            r.view.input().focus().focus(r.slotShowing(1), InputDevice.KEYBOARD);
            int lines = (ITEMS + COLUMNS - 1) / COLUMNS;
            for (int line = 1; line < lines; line++) {
                r.press(MKeys.KEY_DOWN);
                assertEquals("item" + (1 + line * COLUMNS), r.focusedItem(), "down keeps column 1 (line " + line + ")");
                assertInView(r, "down to line " + line);
                assertTrue(r.focused().hasState(UiElement.FOCUS_VISIBLE), "still keyboard focus after recycling");
                assertSame(r.slotShowing(1 + line * COLUMNS), r.focused(), "focus is on the slot showing its item");
            }
            float bottom = r.ui.find("list").scrollY();
            assertEquals(r.ui.find("list").maxScrollY(), bottom, 0.5, "the last line scrolled fully into view");
            r.press(MKeys.KEY_DOWN);
            assertEquals("item199", r.focusedItem(), "at the last line, focus stays");

            for (int line = lines - 2; line >= 0; line--) {
                r.press(MKeys.KEY_UP);
                assertEquals("item" + (1 + line * COLUMNS), r.focusedItem(), "up keeps column 1 (line " + line + ")");
                assertInView(r, "up to line " + line);
            }
            assertEquals(0, r.ui.find("list").scrollY(), 0.5, "back at the top");
            assertEquals(0, r.list().firstVisibleIndex());
            r.press(MKeys.KEY_UP);
            assertEquals("item1", r.focusedItem(), "at the first line, focus stays");
        }
    }

    @Test
    void leftAndRightStayOnAScrolledLine() {
        try (Rig r = new Rig(40, COLUMNS)) {
            r.view.input().focus().focus(r.slotShowing(4), InputDevice.KEYBOARD);
            for (int i = 0; i < 10; i++) {
                r.press(MKeys.KEY_DOWN);
            }
            assertEquals("item94", r.focusedItem(), "line 10, column 4");
            assertTrue(r.list().firstVisibleIndex() > 0, "the window moved");
            for (int col = 5; col < COLUMNS; col++) {
                r.press(MKeys.KEY_RIGHT);
                assertEquals("item" + (90 + col), r.focusedItem());
            }
            r.press(MKeys.KEY_RIGHT);
            assertEquals("item98", r.focusedItem(), "the line's end: no wrap");
            for (int col = COLUMNS - 2; col >= 0; col--) {
                r.press(MKeys.KEY_LEFT);
                assertEquals("item" + (90 + col), r.focusedItem());
                assertInView(r, "left to column " + col);
            }
            r.press(MKeys.KEY_LEFT);
            assertEquals("item90", r.focusedItem(), "the line's start: no wrap");
        }
    }

    @Test
    void downInAPlainGridKeepsTheColumnOfCellsWhoseEdgesTouch() {
        try (Rig r = new Rig(0, 3)) {
            r.view.input().focus().focus(r.list().rows().get(1), InputDevice.KEYBOARD);
            r.press(MKeys.KEY_DOWN);
            assertEquals("item4", r.focusedItem(), "straight down, not the touching neighbour to the left");
            r.press(MKeys.KEY_DOWN);
            assertEquals("item7", r.focusedItem());
            r.press(MKeys.KEY_UP);
            assertEquals("item4", r.focusedItem());
        }
    }
}
