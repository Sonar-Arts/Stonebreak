package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.ui.masonry.MKeys;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tab order, spatial navigation, focus indication and what happens when focus is lost (#288). */
class FocusNavigationTest {

    private static UiDocs.N button(String id) {
        return node(id, "Button").style("width", 60).style("height", 30);
    }

    /** A 3×2 grid: a b c / d e f. */
    private static OmuiArchive grid() {
        return screen("t:ui/grid", box("root").style("width", 400).style("height", 300).kids(
            box("row1").style("flex-direction", "row").style("column-gap", 10).kids(button("a"), button("b"), button("c")),
            box("row2").style("flex-direction", "row").style("column-gap", 10).style("margin-top", 10)
                .kids(button("d"), button("e"), button("f"))));
    }

    @Test
    void tabCyclesInTreeOrderAndWraps() {
        try (InputRig r = new InputRig(grid())) {
            List<String> seen = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                assertTrue(r.press(MKeys.KEY_TAB));
                seen.add(r.focusKey());
            }
            assertEquals(List.of("a", "b", "c", "d", "e", "f", "a"), seen);
            assertTrue(r.press(MKeys.KEY_TAB, MKeys.MOD_SHIFT));
            assertEquals("f", r.focusKey(), "Shift+Tab goes back, wrapping");
        }
    }

    @Test
    void positiveTabIndexComesFirstAndNegativeIsSkipped() {
        OmuiArchive doc = screen("t:ui/tabindex", box("root").style("width", 400).style("height", 300).kids(
            button("x"), button("y").prop("tabIndex", 2), button("z").prop("tabIndex", 1), button("w").prop("tabIndex", -1)));
        try (InputRig r = new InputRig(doc)) {
            List<String> order = r.router.focus().tabOrder().stream().map(UiElement::key).toList();
            assertEquals(List.of("z", "y", "x"), order);
            r.click("w");
            assertEquals("w", r.focusKey(), "a negative tabIndex is still focusable by pointer");
        }
    }

    @Test
    void arrowsMoveSpatiallyWithoutWrapping() {
        try (InputRig r = new InputRig(grid())) {
            r.press(MKeys.KEY_RIGHT);
            assertEquals("a", r.focusKey(), "the first navigation focuses the first element");
            r.press(MKeys.KEY_RIGHT);
            assertEquals("b", r.focusKey());
            r.press(MKeys.KEY_DOWN);
            assertEquals("e", r.focusKey(), "straight down stays in the column");
            r.press(MKeys.KEY_RIGHT);
            assertEquals("f", r.focusKey());
            assertTrue(r.press(MKeys.KEY_RIGHT), "at the edge the key is still the UI's");
            assertEquals("f", r.focusKey(), "no wrap-around");
            r.press(MKeys.KEY_UP);
            assertEquals("c", r.focusKey());
        }
    }

    @Test
    void explicitNeighboursOverrideTheSpatialSearch() {
        OmuiArchive doc = screen("t:ui/explicit", box("root").style("width", 400).style("height", 300).kids(
            box("row").style("flex-direction", "row").kids(button("a").prop("navRight", "c"), button("b"), button("c")),
            button("d").prop("navUp", "missing")));
        try (InputRig r = new InputRig(doc)) {
            r.router.focus().focus(r.el("a"), InputDevice.KEYBOARD);
            r.press(MKeys.KEY_RIGHT);
            assertEquals("c", r.focusKey());
            r.router.focus().focus(r.el("d"), InputDevice.KEYBOARD);
            r.press(MKeys.KEY_UP);
            assertEquals("a", r.focusKey(), "a missing target falls back to the spatial search");
            assertTrue(UiDocs.has(r.ui, UiRuntimeDiagnostic.Code.NAV_TARGET_MISSING));
        }
    }

    @Test
    void navigationGroupsArePreferredBeforeTheWholeScope() {
        // Two columns; the left one is a group. Down from l1 reaches l2 even though r1 is closer below-right.
        OmuiArchive doc = screen("t:ui/groups", box("root").style("width", 400).style("height", 300)
            .style("flex-direction", "row").kids(
                box("left").prop("focusScope", "group").kids(button("l1"), box("gap").style("height", 80), button("l2")),
                box("right").style("margin-top", 35).kids(button("r1"))));
        try (InputRig r = new InputRig(doc)) {
            r.router.focus().focus(r.el("l1"), InputDevice.KEYBOARD);
            r.press(MKeys.KEY_DOWN);
            assertEquals("l2", r.focusKey());
        }
    }

    @Test
    void keyboardFocusIsVisiblePointerFocusIsNot() {
        try (InputRig r = new InputRig(grid())) {
            r.click("b");
            assertTrue(r.el("b").hasState(UiElement.FOCUS));
            assertFalse(r.el("b").hasState(UiElement.FOCUS_VISIBLE));
            assertFalse(r.router.focus().focusVisible());
            r.press(MKeys.KEY_RIGHT);
            assertEquals("c", r.focusKey());
            assertTrue(r.el("c").hasState(UiElement.FOCUS_VISIBLE));
            r.click("a");
            assertFalse(r.el("a").hasState(UiElement.FOCUS_VISIBLE));
        }
    }

    @Test
    void aKeyPressOnPointerFocusFirstRevealsIt() {
        OmuiArchive doc = screen("t:ui/reveal", box("root").style("width", 400).style("height", 300).kids(button("only")));
        try (InputRig r = new InputRig(doc)) {
            r.click("only");
            r.press(MKeys.KEY_DOWN);
            assertEquals("only", r.focusKey());
            assertTrue(r.el("only").hasState(UiElement.FOCUS_VISIBLE));
        }
    }

    @Test
    void submitClicksTheFocusedElementOnce() {
        try (InputRig r = new InputRig(grid())) {
            List<String> clicks = new ArrayList<>();
            r.el("root").on(UiEventType.CLICK, e -> clicks.add(e.target().key()));
            assertFalse(r.press(MKeys.KEY_ENTER), "nothing focused: Enter is not the UI's");
            r.press(MKeys.KEY_TAB);
            assertTrue(r.press(MKeys.KEY_ENTER));
            assertEquals(List.of("a"), clicks);
            assertEquals(InputDevice.KEYBOARD, r.router.lastDevice());
        }
    }

    @Test
    void disablingOrHidingTheFocusedElementMovesFocusToTheNextOne() {
        try (InputRig r = new InputRig(grid())) {
            r.router.focus().focus(r.el("b"), InputDevice.KEYBOARD);
            r.el("b").setEnabled(false);
            r.frame();
            assertEquals("c", r.focusKey(), "disabled: focus moves on to what followed");
            assertTrue(r.el("c").hasState(UiElement.FOCUS_VISIBLE), "and stays visible for a keyboard player");
            r.el("c").setStyle("display", UiValue.of("none"));
            r.frame();
            assertEquals("d", r.focusKey(), "collapsed");
            r.el("d").setStyle("visibility", UiValue.of("hidden"));
            r.frame();
            assertEquals("e", r.focusKey(), "hidden");
            r.el("e").remove();
            r.el("f").remove();
            r.frame();
            assertEquals("a", r.focusKey(), "removed with nothing after: focus goes back to the previous focusable");
        }
    }

    @Test
    void removingTheLastFocusableClearsFocus() {
        OmuiArchive doc = screen("t:ui/last", box("root").style("width", 400).style("height", 300).kids(button("solo")));
        try (InputRig r = new InputRig(doc)) {
            r.router.focus().focus(r.el("solo"), InputDevice.KEYBOARD);
            r.el("solo").remove();
            r.frame();
            assertNull(r.router.focus().focused());
        }
    }

    @Test
    void focusFollowsARecycledItemOrMovesOn() {
        try (InputRig r = new InputRig(grid())) {
            r.router.focus().focus(r.el("b"), InputDevice.KEYBOARD);
            r.router.focus().recycled(r.el("b"), r.el("e"));
            assertEquals("e", r.focusKey(), "the item now shown by e keeps focus");
            r.router.focus().recycled(r.el("e"), null);
            assertEquals("f", r.focusKey(), "an unrealized item behaves like a removal");
        }
    }

    @Test
    void liveReloadKeepsFocusOnTheRebuiltElement() {
        OmuiArchive doc = grid();
        try (InputRig r = new InputRig(doc)) {
            r.router.focus().focus(r.el("e"), InputDevice.KEYBOARD);
            r.ui.reload(doc);
            r.frame();
            assertEquals("e", r.focusKey());
            assertTrue(r.el("e").hasState(UiElement.FOCUS), "the twin carries :focus");
            r.press(MKeys.KEY_LEFT);
            assertEquals("d", r.focusKey(), "and navigation continues from it");
        }
    }

    @Test
    void navigatingIntoAScrollViewRevealsTheFocusedItem() {
        UiDocs.N view = node("view", "ScrollView").style("width", 100).style("height", 70);
        for (int i = 0; i < 6; i++) {
            view.kids(button("i" + i).style("flex-shrink", 0));
        }
        OmuiArchive doc = InputRig.withFeatures(screen("t:ui/reveal-scroll",
            box("root").style("width", 400).style("height", 300).kids(view)), UiFeatures.SCROLL);
        try (InputRig r = new InputRig(doc)) {
            for (int i = 0; i < 5; i++) {
                r.press(MKeys.KEY_DOWN);
            }
            assertEquals("i4", r.focusKey());
            UiElement v = r.el("view");
            assertTrue(v.scrollY() > 0);
            assertTrue(r.el("i4").rect().bottom() <= v.rect().bottom() + 0.01f, "the focused item is inside the view");
        }
    }

    @Test
    void focusedElementsOnlyComeFromFocusableWidgets() {
        try (InputRig r = new InputRig(grid())) {
            assertFalse(r.router.focus().focus(r.el("row1"), InputDevice.PROGRAM), "a Box does not take focus");
            r.el("row1").setProp("focusable", UiValue.TRUE);
            assertTrue(r.router.focus().focus(r.el("row1"), InputDevice.PROGRAM));
            r.el("a").setProp("focusable", UiValue.FALSE);
            assertFalse(r.router.focus().focus(r.el("a"), InputDevice.PROGRAM));
        }
    }

    @Test
    void anUnknownScopeValueIsAPropertyError() {
        UiNode n = node("bad", "Box").prop("focusScope", "window").build();
        OmuiArchive doc = screen("t:ui/badscope", box("root").kids());
        try (InputRig r = new InputRig(doc)) {
            r.el("root").insertChild(-1, n);
            assertTrue(UiDocs.has(r.ui, UiRuntimeDiagnostic.Code.PROPERTY_TYPE));
        }
    }
}
