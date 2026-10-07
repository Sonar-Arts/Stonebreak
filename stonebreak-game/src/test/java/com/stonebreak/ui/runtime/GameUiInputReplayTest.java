package com.stonebreak.ui.runtime;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MKeys;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiMetrics;
import com.openmason.engine.ui.runtime.input.EventCallbacks;
import com.openmason.engine.ui.runtime.input.UiEventType;
import com.openmason.engine.ui.runtime.input.UiInputRouter;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.stonebreak.ui.runtime.screens.UiLayer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Input replay through the REAL game host (#288 review): the same session driven through
 * {@link GameUiInput} (GLFW-shaped callbacks, a HUD document stacked beneath, the frame clock)
 * and straight into the document's router must leave the same trace. The engine's replay only
 * ever called the router, so the host's stacking, key actions and modifiers went unproven.
 */
class GameUiInputReplayTest {

    private static final String SCRIPT = """
        move 60 30
        down 60 30
        up 60 30
        key TAB
        key TAB
        key ENTER
        down 60 110
        up 60 110
        text Steve
        key BACKSPACE
        key ENTER
        key ESCAPE
        wheel 60 30 -1
        move 390 290
        down 390 290
        up 390 290
        """;

    private static UiNode node(String id, String type, Map<String, UiValue> props, Map<String, UiValue> style) {
        return new UiNode(id, null, type, 1, List.of(), props, style, null, List.of(), null, List.of(), Map.of());
    }

    private static Map<String, UiValue> rect(float x, float y, float w, float h) {
        return Map.of("position", UiValue.of("absolute"), "left", UiValue.of(x), "top", UiValue.of(y),
            "width", UiValue.of(w), "height", UiValue.of(h));
    }

    private static UiDocumentView menu(String id, boolean pickable) throws Exception {
        UiNode root = new UiNode("root", null, "Box", 1, List.of(), Map.of(),
            Map.of("width", UiValue.of("100%"), "height", UiValue.of("100%"), "picking-mode", UiValue.of("ignore")),
            null, List.of(), null, pickable ? List.of(
                node("play", "Button", Map.of(), rect(10, 10, 100, 40)),
                node("options", "Button", Map.of(), rect(10, 60, 100, 40)),
                node("name", "TextField", Map.of(), rect(10, 100, 200, 24))) : List.of(
                node("hud", "Button", Map.of(), rect(0, 0, 400, 300))), Map.of());
        OmuiArchive doc = OmuiArchive.of(UiManifest.create(id, UiManifest.DocumentKind.SCREEN, id),
            new UiDocument(root, List.of(), null, null, Map.of()));
        UiDocumentView v = GameUiDocuments.open(doc, () -> null, Map.of());
        v.instance().setMetrics(UiMetrics.of(400, 300, 1));
        v.instance().update();
        v.input().sync();
        return v;
    }

    private static List<String> run(boolean throughHost) throws Exception {
        UiDocumentView hud = menu("t:ui/replay-hud", false);
        UiDocumentView view = menu("t:ui/replay", true);
        List<String> events = new ArrayList<>();
        for (UiEventType t : List.of(UiEventType.CLICK, UiEventType.FOCUS_IN, UiEventType.CHANGE, UiEventType.COMMIT,
            UiEventType.SUBMIT, UiEventType.CANCEL)) {
            view.instance().root().on(t, e -> events.add(t + ":" + (e.target() == null ? "-" : e.target().key())),
                EventCallbacks.Phase.TRICKLE_DOWN);
        }
        GameUiInput in = GameUiInput.get();
        if (throughHost) {
            in.open(view, UiLayer.OVERLAY);
            in.open(hud, UiLayer.SCREEN); // opened later, but a lower layer: it only gets what the menu leaves
        }
        UiInputRouter r = view.input();
        List<String> trace = new ArrayList<>();
        try {
            for (String raw : SCRIPT.split("\n")) {
                String line = raw.strip();
                if (line.isEmpty()) {
                    continue;
                }
                events.clear();
                String[] p = line.split("\\s+");
                boolean consumed = switch (p[0]) {
                    case "move" -> throughHost ? in.onMouseMove(f(p[1]), f(p[2]), false) : r.pointerMove(f(p[1]), f(p[2]));
                    case "down" -> throughHost ? in.onMouseButton(f(p[1]), f(p[2]), 0, MKeys.PRESS, 0, false)
                        : r.pointerDown(f(p[1]), f(p[2]), 0, 0);
                    case "up" -> throughHost ? in.onMouseButton(f(p[1]), f(p[2]), 0, MKeys.RELEASE, 0, false)
                        : r.pointerUp(f(p[1]), f(p[2]), 0, 0);
                    case "wheel" -> throughHost ? in.onScroll(f(p[1]), f(p[2]), 0, f(p[3]), 0, false)
                        : r.wheel(f(p[1]), f(p[2]), 0, f(p[3]), 0);
                    case "key" -> {
                        int k = key(p[1]);
                        boolean a = throughHost ? in.onKey(k, MKeys.PRESS, 0, false) : r.key(k, MKeys.PRESS, 0);
                        boolean b = throughHost ? in.onKey(k, MKeys.RELEASE, 0, false) : r.key(k, MKeys.RELEASE, 0);
                        yield a || b;
                    }
                    case "text" -> {
                        boolean any = false;
                        for (int cp : p[1].codePoints().toArray()) {
                            any |= throughHost ? in.onCharacter(cp, false) : r.text(cp);
                        }
                        yield any;
                    }
                    default -> throw new IllegalArgumentException(line);
                };
                view.instance().update();
                r.sync();
                view.instance().update();
                UiElement f = r.focus().focused();
                String value = view.instance().find("name").text("value");
                trace.add(String.format(Locale.ROOT, "%-16s %-5s focus=%s value=%s %s", line, consumed,
                    f == null ? "-" : f.key(), value, events));
            }
            return trace;
        } finally {
            in.close(view);
            in.close(hud);
            view.close();
            hud.close();
        }
    }

    private static float f(String s) {
        return Float.parseFloat(s);
    }

    private static int key(String name) {
        return switch (name) {
            case "TAB" -> MKeys.KEY_TAB;
            case "ENTER" -> MKeys.KEY_ENTER;
            case "ESCAPE" -> MKeys.KEY_ESCAPE;
            case "BACKSPACE" -> MKeys.KEY_BACKSPACE;
            default -> throw new IllegalArgumentException(name);
        };
    }

    @Test
    void theGameHostAndTheBareRouterLeaveTheSameTrace() throws Exception {
        List<String> direct = run(false);
        List<String> game = run(true);
        // The screen beneath takes what the menu leaves (the last click lands on empty menu space):
        // only that step may differ, and only in "consumed".
        assertEquals(direct.size(), game.size());
        for (int i = 0; i < direct.size(); i++) {
            String d = direct.get(i);
            String g = game.get(i);
            if (d.startsWith("down 390") || d.startsWith("up 390") || d.startsWith("move 390")) {
                assertEquals(d.replace(" false ", " true  "), g, "the screen below consumes the stray click");
            } else {
                assertEquals(d, g);
            }
        }
    }
}
