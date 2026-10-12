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
        UiDocumentView v = open("t:ui/name", List.of(node("field", "TextField",
            Map.of("inputFilter", UiValue.of("ascii")), rect(0, 0, 100, 30), List.of())));
        assertTrue(GameUiDocuments.inputGate(v, Locale.ENGLISH).isEmpty(), "an ASCII field is within the game font");
        assertThrows(IllegalStateException.class, () -> GameUiDocuments.requireInputGate(v, Locale.JAPANESE),
            "no IME source in GLFW: Japanese name entry stays on the legacy screen");
        assertFalse(GameUiInput.CAPABILITIES.contains(InputCapability.IME_COMPOSITION));
        assertEquals(InputCapability.IME_COMPOSITION,
            UiInputGate.check(v.instance(), Locale.JAPANESE, GameUiInput.CAPABILITIES).getFirst().capability());
        UiDocumentView any = open("t:ui/any", List.of(node("field", "TextField", Map.of(), rect(0, 0, 100, 30), List.of())));
        assertFalse(GameUiInput.CAPABILITIES.contains(InputCapability.FONT_FALLBACK), "one Latin font, no fallback");
        assertThrows(IllegalStateException.class, () -> GameUiDocuments.requireInputGate(any, Locale.ENGLISH),
            "a field accepting any text would draw emoji as boxes: the screen keeps its legacy form");
    }

    // ── polled-key ownership (#288 review F1) ──────────────────────────────

    @Test
    void aConsumedPressHidesTheKeyFromGameplayPollsUntilReleased() throws Exception {
        UiDocumentView menu = open("t:ui/esc", List.of(node("b", "Button", Map.of(), rect(0, 0, 50, 50), List.of())));
        menu.instance().find("b").on(UiEventType.KEY_DOWN, e -> e.stopPropagation());
        GameUiInput in = GameUiInput.get();
        in.onMouseButton(25, 25, 0, MKeys.PRESS, 0, false);
        in.onMouseButton(25, 25, 0, MKeys.RELEASE, 0, false);
        assertFalse(in.masksKey(MKeys.KEY_ESCAPE));
        assertTrue(in.onKey(MKeys.KEY_ESCAPE, MKeys.PRESS, 0, false), "the focused button takes Escape");
        assertTrue(in.masksKey(MKeys.KEY_ESCAPE), "so the pause toggle's poll must not see it held");
        assertFalse(in.masksKey(org.lwjgl.glfw.GLFW.GLFW_KEY_E), "other keys stay gameplay's");
        in.onKey(MKeys.KEY_ESCAPE, MKeys.RELEASE, 0, false);
        assertFalse(in.masksKey(MKeys.KEY_ESCAPE), "released: the next press is gameplay's again");
    }

    @Test
    void aFocusedTextFieldOwnsTheWholeKeyboard() throws Exception {
        open("t:ui/field", List.of(node("field", "TextField", Map.of(), rect(0, 0, 100, 30), List.of())));
        GameUiInput in = GameUiInput.get();
        assertFalse(in.ownsKeyboard());
        in.onMouseButton(10, 10, 0, MKeys.PRESS, 0, false);
        in.onMouseButton(10, 10, 0, MKeys.RELEASE, 0, false);
        assertTrue(in.ownsKeyboard(), "typing 'e' must not open the inventory");
        assertTrue(in.masksKey(org.lwjgl.glfw.GLFW.GLFW_KEY_E) && in.masksKey(org.lwjgl.glfw.GLFW.GLFW_KEY_W) && in.masksKey(org.lwjgl.glfw.GLFW.GLFW_KEY_3));
        in.onMouseMove(10, 10, true);
        assertFalse(in.ownsKeyboard(), "with the cursor captured presses never reach the field");
    }

    @Test
    void anOpenModalOwnsTheKeyboardEvenWhileLookingAround() throws Exception {
        UiDocumentView v = open("t:ui/modal", List.of(node("dialog", "Box", Map.of(), rect(0, 0, 100, 100),
            List.of(node("ok", "Button", Map.of(), rect(10, 10, 40, 20), List.of())))));
        GameUiInput in = GameUiInput.get();
        v.input().focus().openModal(v.instance().find("dialog"));
        in.onMouseMove(0, 0, true);
        assertTrue(in.ownsKeyboard());
        assertTrue(in.masksKey(MKeys.KEY_ESCAPE), "Escape closes the dialog only, never toggles pause too");
    }

    @Test
    void aScreenCanClaimTheKeyboardOutright() throws Exception {
        UiDocumentView v = open("t:ui/claim", List.of());
        GameUiInput in = GameUiInput.get();
        assertFalse(in.masksKey(org.lwjgl.glfw.GLFW.GLFW_KEY_W));
        in.setClaimsKeyboard(v, true);
        assertTrue(in.masksKey(org.lwjgl.glfw.GLFW.GLFW_KEY_W));
        in.close(v);
        assertFalse(in.masksKey(org.lwjgl.glfw.GLFW.GLFW_KEY_W), "closing the screen gives the keyboard back");
    }

    @Test
    void layersOrderBothDrawingAndInput() throws Exception {
        UiDocumentView overlay = open("t:ui/top", List.of(node("o", "Button", Map.of(), rect(0, 0, 60, 60), List.of())));
        UiNode root = node("root", "Box", Map.of(), Map.of("width", UiValue.of("100%"), "height", UiValue.of("100%"),
            "picking-mode", UiValue.of("ignore")), List.of(node("s", "Button", Map.of(), rect(0, 0, 60, 60), List.of())));
        UiDocumentView screen = GameUiDocuments.open(OmuiArchive.of(
            UiManifest.create("t:ui/screen", UiManifest.DocumentKind.SCREEN, "t:ui/screen"),
            new UiDocument(root, List.of(), null, null, Map.of())), () -> null, Map.of());
        screen.instance().setMetrics(UiMetrics.of(400, 300, 1));
        screen.instance().update();
        screen.input().sync();
        opened.add(screen);
        GameUiInput in = GameUiInput.get();
        in.open(screen, com.stonebreak.ui.runtime.screens.UiLayer.SCREEN); // opened later, but a lower layer
        assertEquals(List.of(screen, overlay), in.views(), "draw order: screens below overlays");
        assertTrue(in.onMouseMove(30, 30, false));
        assertTrue(overlay.instance().find("o").hasState("hover"), "input goes to the top of the same stack");
        assertFalse(screen.instance().find("s").hasState("hover"));
    }

    @Test
    void shiftWheelReachesDocumentsWithItsModifiers() throws Exception {
        UiDocumentView v = open("t:ui/wheel", List.of(node("list", "Button", Map.of(), rect(0, 0, 100, 100), List.of())));
        int[] mods = {-1};
        v.instance().find("list").on(UiEventType.WHEEL, e -> {
            mods[0] = ((com.openmason.engine.ui.runtime.input.WheelEvent) e).modifiers();
            e.stopPropagation();
        });
        GameUiInput.get().onScroll(50, 50, 0, 1, MKeys.MOD_SHIFT, false);
        assertEquals(MKeys.MOD_SHIFT, mods[0], "Shift+wheel scrolls sideways in the game as in the preview");
    }

    @Test
    void documentsSeeLayoutKeysWhileGameplayMasksThePhysicalOne() throws Exception {
        UiDocumentView v = open("t:ui/qwertz", List.of(node("b", "Button", Map.of(), rect(0, 0, 50, 50), List.of())));
        int[] seen = {-1};
        v.instance().find("b").on(UiEventType.KEY_DOWN, e -> {
            seen[0] = ((com.openmason.engine.ui.runtime.input.KeyEvent) e).key();
            e.stopPropagation();
        });
        GameUiInput in = GameUiInput.get();
        in.setKeyTranslator(k -> k == MKeys.KEY_Y ? MKeys.KEY_Z : k); // QWERTZ: the key labelled Z sends Y
        try {
            in.onMouseButton(25, 25, 0, MKeys.PRESS, 0, false);
            in.onMouseButton(25, 25, 0, MKeys.RELEASE, 0, false);
            assertTrue(in.onKey(MKeys.KEY_Y, MKeys.PRESS, MKeys.MOD_CONTROL, false));
            assertEquals(MKeys.KEY_Z, seen[0], "Ctrl+Z is the key labelled Z");
            assertTrue(in.masksKey(MKeys.KEY_Y), "gameplay polls physical keys: that one is masked");
            in.onKey(MKeys.KEY_Y, MKeys.RELEASE, MKeys.MOD_CONTROL, false);
        } finally {
            in.setKeyTranslator(null);
        }
    }
}
