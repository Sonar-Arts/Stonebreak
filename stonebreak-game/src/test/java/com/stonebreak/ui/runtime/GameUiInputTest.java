package com.stonebreak.ui.runtime;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MKeys;
import com.openmason.engine.ui.runtime.UiMetrics;
import com.openmason.engine.ui.runtime.input.CancelReason;
import com.openmason.engine.ui.runtime.input.DragEvent;
import com.openmason.engine.ui.runtime.input.DragSession;
import com.openmason.engine.ui.runtime.input.InputCapability;
import com.openmason.engine.ui.runtime.input.UiEventType;
import com.openmason.engine.ui.runtime.input.UiInputGate;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.stonebreak.config.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The game window's document input host (#288): stacking, consumption, settings, cancellation. */
class GameUiInputTest {

    private final List<UiDocumentView> opened = new ArrayList<>();

    @AfterEach
    void closeAll() {
        for (UiDocumentView v : opened) {
            GameUiInput.get().close(v);
            v.close();
        }
        Settings.getInstance().setReducedMotion(false);
    }

    private static UiNode node(String id, String type, Map<String, UiValue> props, Map<String, UiValue> style,
                               List<UiNode> children) {
        return new UiNode(id, null, type, 1, List.of(), props, style, null, List.of(), null, children, Map.of());
    }

    private static Map<String, UiValue> rect(float x, float y, float w, float h) {
        return Map.of("position", UiValue.of("absolute"), "left", UiValue.of(x), "top", UiValue.of(y),
            "width", UiValue.of(w), "height", UiValue.of(h));
    }

    private UiDocumentView open(String id, List<UiNode> kids) throws Exception {
        UiNode root = node("root", "Box", Map.of(), Map.of("width", UiValue.of("100%"), "height", UiValue.of("100%"),
            "picking-mode", UiValue.of("ignore")), kids);
        OmuiArchive doc = OmuiArchive.of(UiManifest.create(id, UiManifest.DocumentKind.SCREEN, id),
            new UiDocument(root, List.of(), null, null, Map.of()));
        UiDocumentView v = GameUiDocuments.open(doc, () -> null, Map.of());
        v.instance().setMetrics(UiMetrics.of(400, 300, 1));
        v.instance().update();
        v.input().sync();
        opened.add(v);
        GameUiInput.get().open(v);
        return v;
    }

    @Test
    void theTopDocumentTakesInputFirstAndLowerOnesLoseHover() throws Exception {
        UiDocumentView menu = open("t:ui/menu", List.of(node("play", "Button", Map.of(), rect(10, 10, 100, 40), List.of())));
        UiDocumentView overlay = open("t:ui/overlay",
            List.of(node("panel", "Button", Map.of(), rect(0, 0, 60, 60), List.of())));
        GameUiInput in = GameUiInput.get();
        assertTrue(in.onMouseMove(80, 30, false), "over the menu's button, outside the overlay");
        assertTrue(menu.instance().find("play").hasState("hover"));
        assertTrue(in.onMouseMove(30, 30, false));
        assertTrue(overlay.instance().find("panel").hasState("hover"));
        assertFalse(menu.instance().find("play").hasState("hover"), "a document beneath sees the pointer leave");
        assertFalse(in.onMouseMove(300, 250, false), "empty space is the world's");
        assertTrue(in.onKey(MKeys.KEY_TAB, MKeys.PRESS, 0, false), "the overlay focuses its button");
        assertEquals("panel", overlay.input().focus().focused().key());
        assertEquals(null, menu.input().focus().focused());
    }

    @Test
    void aCapturedCursorNeverReachesDocuments() throws Exception {
        open("t:ui/hud", List.of(node("b", "Button", Map.of(), rect(0, 0, 400, 300), List.of())));
        GameUiInput in = GameUiInput.get();
        assertFalse(in.onMouseMove(50, 50, true), "camera look: the UI sees nothing");
        assertFalse(in.onMouseButton(50, 50, 0, MKeys.PRESS, 0, true));
        assertFalse(in.onScroll(50, 50, 0, 1, true), "the hotbar keeps the wheel");
        assertTrue(in.onMouseButton(50, 50, 0, MKeys.PRESS, 0, false));
    }

    @Test
    void aCapturedCursorKeepsGameplayKeysAwayFromAFocusedButton() throws Exception {
        UiDocumentView hud = open("t:ui/hud2", List.of(node("b", "Button", Map.of(), rect(0, 0, 50, 50), List.of())));
        GameUiInput in = GameUiInput.get();
        in.onMouseButton(25, 25, 0, MKeys.PRESS, 0, false);
        in.onMouseButton(25, 25, 0, MKeys.RELEASE, 0, false);
        assertEquals("b", hud.input().focus().focused().key());
        assertFalse(in.onKey(MKeys.KEY_SPACE, MKeys.PRESS, 0, true), "Space jumps while looking around");
        assertFalse(in.onCharacter(' ', true));
        assertTrue(in.onKey(MKeys.KEY_SPACE, MKeys.PRESS, 0, false), "with a free cursor it presses the button");
        assertTrue(in.onKey(MKeys.KEY_SPACE, MKeys.RELEASE, 0, true), "and its release comes back even if captured");
    }

    @Test
    void closingOrDisconnectingCancelsDrags() throws Exception {
        UiDocumentView inv = open("t:ui/inv", List.of(
            node("slot", "ItemSlot", Map.of("draggable", UiValue.TRUE), rect(0, 0, 50, 50), List.of())));
        List<String> ends = new ArrayList<>();
        inv.instance().find("slot").on(UiEventType.DRAG_START, e -> ((DragEvent) e).setPayload("stack"));
        inv.instance().find("slot").on(UiEventType.DRAG_END,
            e -> ends.add(String.valueOf(((DragEvent) e).session().cancelReason())));
        GameUiInput in = GameUiInput.get();
        in.onMouseButton(25, 25, 0, MKeys.PRESS, 0, false);
        in.onMouseMove(60, 25, false);
        DragSession s = inv.input().drag().session();
        assertTrue(s.isLive());
        in.cancelAll(CancelReason.DISCONNECT);
        assertEquals(List.of("DISCONNECT"), ends);
        in.onMouseButton(25, 25, 0, MKeys.PRESS, 0, false);
        in.onMouseMove(60, 25, false);
        GameUiInput.get().close(inv);
        assertEquals(List.of("DISCONNECT", "SCREEN_CLOSED"), ends);
        assertFalse(in.onKey(MKeys.KEY_TAB, MKeys.PRESS, 0, false), "a closed document gets nothing");
    }

    @Test
    void playerSettingsReachEveryDocument() throws Exception {
        UiDocumentView v = open("t:ui/prefs", List.of());
        Settings.getInstance().setReducedMotion(true);
        GameUiInput.get().frame();
        assertTrue(v.instance().preferences().reducedMotion());
    }

    @Test
    void theGameRefusesScreensItsInputCannotServe() throws Exception {
        UiDocumentView v = open("t:ui/name", List.of(node("field", "TextField", Map.of(), rect(0, 0, 100, 30), List.of())));
        assertTrue(GameUiDocuments.inputGate(v, Locale.ENGLISH).isEmpty());
        assertThrows(IllegalStateException.class, () -> GameUiDocuments.requireInputGate(v, Locale.JAPANESE),
            "no IME source in GLFW: Japanese name entry stays on the legacy screen");
        assertFalse(GameUiInput.CAPABILITIES.contains(InputCapability.IME_COMPOSITION));
        assertEquals(InputCapability.IME_COMPOSITION,
            UiInputGate.check(v.instance(), Locale.JAPANESE, GameUiInput.CAPABILITIES).getFirst().capability());
    }
}
