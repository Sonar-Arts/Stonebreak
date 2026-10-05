package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MKeys;
import com.openmason.engine.ui.runtime.UiDocs;
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

/** Modal trapping, blocking and focus restoration; popup dismissal (#288). */
class ModalPopupTest {

    private static UiDocs.N button(String id) {
        return node(id, "Button").style("width", 60).style("height", 30);
    }

    /** A menu with two buttons, and a dialog (hidden at first) over the right side. */
    private static OmuiArchive menuAndDialog() {
        return screen("t:ui/modal", box("root").style("width", 400).style("height", 300).kids(
            box("menu").kids(button("open"), button("other")),
            box("dialog").prop("focusScope", "modal").style("display", "none").style("position", "absolute")
                .style("left", 200).style("top", 50).style("width", 150).style("height", 150).kids(
                    button("cancel"), button("ok").prop("autofocus", true))));
    }

    private static void show(InputRig r, String key) {
        r.el(key).setStyle("display", UiValue.of("flex"));
        r.frame();
    }

    private static void hide(InputRig r, String key) {
        r.el(key).setStyle("display", UiValue.of("none"));
        r.frame();
    }

    @Test
    void aModalTakesFocusTrapsTabAndRestoresFocusWhenItCloses() {
        try (InputRig r = new InputRig(menuAndDialog())) {
            r.press(MKeys.KEY_TAB);
            assertEquals("open", r.focusKey());
            show(r, "dialog");
            assertEquals("ok", r.focusKey(), "autofocus inside the modal");
            r.press(MKeys.KEY_TAB);
            assertEquals("cancel", r.focusKey(), "tab wraps inside the dialog");
            r.press(MKeys.KEY_TAB);
            assertEquals("ok", r.focusKey());
            assertFalse(r.router.focus().focus(r.el("other"), InputDevice.PROGRAM), "outside the modal cannot be focused");
            hide(r, "dialog");
            assertEquals("open", r.focusKey(), "focus returns to the element that had it");
            assertTrue(r.el("open").hasState("focus-visible"), "with its indication");
        }
    }

    @Test
    void aModalBlocksPointerInputOutsideItAndSwallowsKeys() {
        try (InputRig r = new InputRig(menuAndDialog())) {
            show(r, "dialog");
            List<String> clicks = new ArrayList<>();
            r.el("root").on(UiEventType.CLICK, e -> clicks.add(e.target().key()));
            assertTrue(r.click("other"), "blocked, but consumed: nothing reaches the world");
            assertTrue(r.router.pointerDown(390, 290, PointerEvent.PRIMARY, 0), "even on empty space");
            r.router.pointerUp(390, 290, PointerEvent.PRIMARY, 0);
            r.click("cancel");
            assertEquals(List.of("cancel"), clicks);
            assertFalse(r.el("other").hasState("hover"), "no hover under a modal");
            assertTrue(r.press(290 /* F1 */), "a modal swallows every key");
        }
    }

    @Test
    void cancelBubblesToTheDialogSoItCanClose() {
        try (InputRig r = new InputRig(menuAndDialog())) {
            r.press(MKeys.KEY_TAB);
            show(r, "dialog");
            r.el("dialog").on(UiEventType.CANCEL, e -> {
                r.el("dialog").setStyle("display", UiValue.of("none"));
                e.stopPropagation();
            });
            assertTrue(r.press(MKeys.KEY_ESCAPE));
            assertEquals("open", r.focusKey());
            assertNull(r.router.focus().activeModal());
            assertFalse(r.press(MKeys.KEY_ESCAPE), "with no modal and nothing to dismiss, Escape is the host's");
        }
    }

    @Test
    void nestedModalsRestoreInOrder() {
        OmuiArchive doc = screen("t:ui/nested", box("root").style("width", 400).style("height", 300).kids(
            button("base"),
            box("d1").prop("focusScope", "modal").style("display", "none").kids(button("d1b"),
                box("d2").prop("focusScope", "modal").style("display", "none").kids(button("d2b")))));
        try (InputRig r = new InputRig(doc)) {
            r.press(MKeys.KEY_TAB);
            show(r, "d1");
            assertEquals("d1b", r.focusKey());
            show(r, "d2");
            assertEquals("d2b", r.focusKey());
            hide(r, "d2");
            assertEquals("d1b", r.focusKey());
            hide(r, "d1");
            assertEquals("base", r.focusKey());
        }
    }

    @Test
    void removingTheModalAlsoRestoresFocus() {
        try (InputRig r = new InputRig(menuAndDialog())) {
            r.press(MKeys.KEY_TAB);
            show(r, "dialog");
            r.el("dialog").remove();
            r.frame();
            assertEquals("open", r.focusKey());
        }
    }

    /** A dropdown: a button and a popup list below it. */
    private static OmuiArchive dropdown() {
        return screen("t:ui/popup", box("root").style("width", 400).style("height", 300).kids(
            button("opener"), button("elsewhere"),
            box("list").prop("focusScope", "popup").style("display", "none").style("position", "absolute")
                .style("left", 0).style("top", 100).style("width", 100).kids(button("item1"), button("item2"))));
    }

    @Test
    void anOutsidePressDismissesThePopupAndIsConsumed() {
        try (InputRig r = new InputRig(dropdown())) {
            r.click("opener");
            show(r, "list");
            assertEquals("item1", r.focusKey(), "focus moves into the popup");
            List<String> clicks = new ArrayList<>();
            r.el("root").on(UiEventType.CLICK, e -> clicks.add(e.target().key()));
            List<DismissReason> reasons = new ArrayList<>();
            r.el("list").on(UiEventType.DISMISS, e -> reasons.add(((DismissEvent) e).reason()));
            assertTrue(r.click("elsewhere"));
            assertEquals(List.of(), clicks, "the dismissing press activates nothing");
            assertEquals(List.of(DismissReason.OUTSIDE_POINTER), reasons);
            assertFalse(r.el("list").isVisible());
            assertEquals("opener", r.focusKey(), "focus returns to the opener");
        }
    }

    @Test
    void escapeDismissesAndPreventDefaultKeepsItOpen() {
        try (InputRig r = new InputRig(dropdown())) {
            show(r, "list");
            boolean[] keep = {true};
            r.el("list").on(UiEventType.DISMISS, e -> {
                if (keep[0]) {
                    e.preventDefault();
                }
            });
            assertTrue(r.press(MKeys.KEY_ESCAPE));
            assertTrue(r.el("list").isVisible(), "a handler kept it open");
            keep[0] = false;
            assertTrue(r.press(MKeys.KEY_ESCAPE));
            assertFalse(r.el("list").isVisible());
            r.router.showPopup(r.el("list"));
            r.frame();
            assertTrue(r.el("list").isVisible(), "showPopup undoes the dismissal's display: none");
        }
    }

    @Test
    void pressingTheAnchorClosesACodeOpenedPopupWithoutReopeningIt() {
        OmuiArchive doc = screen("t:ui/anchor", box("root").style("width", 400).style("height", 300).kids(
            button("opener"),
            box("menu").style("position", "absolute").style("left", 0).style("top", 100).style("width", 100)
                .kids(button("m1"))));
        try (InputRig r = new InputRig(doc)) {
            List<DismissReason> reasons = new ArrayList<>();
            int[] opens = {0};
            r.el("opener").on(UiEventType.CLICK, e -> opens[0]++);
            r.router.openPopup(r.el("menu"), r.el("opener"), reasons::add);
            assertTrue(r.click("opener"));
            assertEquals(List.of(DismissReason.OUTSIDE_POINTER), reasons);
            assertEquals(0, opens[0], "the anchor press only closed the popup");
            assertFalse(r.router.focus().isOpen(r.el("menu")));
        }
    }

    @Test
    void windowFocusLossDismissesPopups() {
        try (InputRig r = new InputRig(dropdown())) {
            show(r, "list");
            r.router.windowFocusLost();
            r.frame();
            assertFalse(r.el("list").isVisible());
        }
    }

    @Test
    void popupsAboveAModalStayUsable() {
        OmuiArchive doc = screen("t:ui/modal-popup", box("root").style("width", 400).style("height", 300).kids(
            box("dialog").prop("focusScope", "modal").style("width", 300).style("height", 200).kids(
                button("pick"),
                box("choices").prop("focusScope", "popup").style("display", "none").style("position", "absolute")
                    .style("left", 0).style("top", 60).kids(button("c1")))));
        try (InputRig r = new InputRig(doc)) {
            show(r, "choices");
            List<String> clicks = new ArrayList<>();
            r.el("root").on(UiEventType.CLICK, e -> clicks.add(e.target().key()));
            r.click("c1");
            assertEquals(List.of("c1"), clicks);
        }
    }
}
