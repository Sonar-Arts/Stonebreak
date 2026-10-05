package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.ui.masonry.MKeys;
import com.openmason.engine.ui.rendering.PreviewMapping;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiMetrics;
import com.openmason.engine.ui.runtime.UiRuntimeContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Headless input replay (#288): runs a script of host input against a document through either
 * host path and records what happened after every step. Not a test class.
 *
 * <p>Script lines (coordinates are viewport pixels; blank lines and {@code #} comments are
 * skipped):
 * <pre>
 * move X Y | down X Y | up X Y | wheel X Y DY | leave
 *                              (X Y may be {@code @key}: that element's centre, resolved when the step runs)
 * key NAME [shift|ctrl]...     press and release (NAME: TAB ENTER ESCAPE SPACE UP DOWN LEFT RIGHT
 *                              BACKSPACE DELETE HOME END A C V X Z, or a GLFW code)
 * hold NAME | repeat NAME | release NAME
 * text STRING                  committed text (the rest of the line)
 * pad BUTTON down|up           A B X Y LB RB DPAD_UP DPAD_DOWN DPAD_LEFT DPAD_RIGHT
 * tick SECONDS
 * focus-lost | screen-closed
 * </pre>
 * The preview host receives each pointer step in screen coordinates of an image drawn at an
 * offset and zoom, converted back by {@link PreviewInput}; the game host receives viewport
 * pixels directly. Both must produce the same trace.
 */
final class InputReplay {

    /** How input reaches the document. */
    enum Host { GAME, PREVIEW }

    static final PreviewMapping PREVIEW_MAPPING = new PreviewMapping(37, 91, 2);

    private InputReplay() {
    }

    /** Runs {@code script} and returns one trace line per step. */
    static List<String> run(OmuiArchive doc, String script, Host host, float width, float height, float uiScale,
                            java.util.function.Consumer<UiDocumentInstance> setup) {
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic().withMeasurer(UiDocs.FIXED_TEXT));
        try {
            ui.setMetrics(new UiMetrics(width, height, uiScale, 1));
            ui.update();
            UiInputRouter router = new UiInputRouter(ui);
            PreviewInput preview = new PreviewInput(router);
            preview.setMapping(PREVIEW_MAPPING);
            List<String> events = new ArrayList<>();
            for (UiEventType t : List.of(UiEventType.CLICK, UiEventType.FOCUS_IN, UiEventType.CHANGE, UiEventType.COMMIT,
                UiEventType.SUBMIT, UiEventType.CANCEL, UiEventType.NAVIGATE, UiEventType.DISMISS,
                UiEventType.DRAG_DROP, UiEventType.DRAG_END)) {
                ui.root().on(t, e -> events.add(t + ":" + (e.target() == null ? "-" : e.target().key())),
                    EventCallbacks.Phase.TRICKLE_DOWN);
            }
            setup.accept(ui);
            frame(ui, router);
            List<String> trace = new ArrayList<>();
            for (String raw : script.split("\n")) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                events.clear();
                boolean consumed = step(resolve(line, ui), router, preview, host);
                frame(ui, router);
                UiElement f = router.focus().focused();
                trace.add(String.format(Locale.ROOT, "%-22s %-5s focus=%s%s hover=%s %s", line, consumed,
                    f == null ? "-" : f.key(), router.focus().focusVisible() ? "*" : "",
                    deepestHover(ui), events));
            }
            return trace;
        } finally {
            ui.close();
        }
    }

    /** Replaces {@code @key} with the element's current centre. */
    private static String resolve(String line, UiDocumentInstance ui) {
        StringBuilder out = new StringBuilder();
        for (String tok : line.split("\\s+")) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            if (tok.startsWith("@") && !line.startsWith("text")) {
                UiElement e = ui.find(tok.substring(1));
                if (e == null) {
                    throw new IllegalArgumentException("no element " + tok);
                }
                out.append(e.rect().x() + e.rect().width() / 2f).append(' ').append(e.rect().y() + e.rect().height() / 2f);
            } else {
                out.append(tok);
            }
        }
        return out.toString();
    }

    private static void frame(UiDocumentInstance ui, UiInputRouter router) {
        ui.update();
        router.sync();
        ui.update();
    }

    private static boolean step(String line, UiInputRouter router, PreviewInput preview, Host host) {
        String[] p = line.split("\\s+");
        PreviewMapping m = PREVIEW_MAPPING;
        return switch (p[0]) {
            case "move" -> host == Host.GAME ? router.pointerMove(f(p[1]), f(p[2]))
                : preview.pointerMove(m.screenX(f(p[1])), m.screenY(f(p[2])), onImage(router, f(p[1]), f(p[2])));
            case "down", "up" -> {
                boolean down = p[0].equals("down");
                if (host == Host.GAME) {
                    yield down ? router.pointerDown(f(p[1]), f(p[2]), PointerEvent.PRIMARY, 0)
                        : router.pointerUp(f(p[1]), f(p[2]), PointerEvent.PRIMARY, 0);
                }
                yield preview.pointerButton(m.screenX(f(p[1])), m.screenY(f(p[2])), onImage(router, f(p[1]), f(p[2])),
                    PointerEvent.PRIMARY, down, 0);
            }
            case "wheel" -> host == Host.GAME ? router.wheel(f(p[1]), f(p[2]), 0, f(p[3]), 0)
                : preview.wheel(m.screenX(f(p[1])), m.screenY(f(p[2])), onImage(router, f(p[1]), f(p[2])), 0, f(p[3]), 0);
            case "leave" -> {
                if (host == Host.GAME) {
                    router.pointerLeave();
                    yield false;
                }
                yield preview.pointerMove(-1000, -1000, false);
            }
            case "key" -> {
                int mods = mods(p);
                boolean a = router.keyDown(key(p[1]), mods, false);
                boolean b = router.keyUp(key(p[1]), mods);
                yield a || b;
            }
            case "hold" -> router.keyDown(key(p[1]), mods(p), false);
            case "repeat" -> router.keyDown(key(p[1]), mods(p), true);
            case "release" -> router.keyUp(key(p[1]), mods(p));
            case "text" -> router.text(line.substring(5));
            case "pad" -> router.gamepadButton(pad(p[1]), p[2].equals("down"));
            case "tick" -> {
                router.tick(Double.parseDouble(p[1]));
                yield false;
            }
            case "focus-lost" -> {
                router.windowFocusLost();
                yield false;
            }
            case "screen-closed" -> {
                router.screenClosed();
                yield false;
            }
            default -> throw new IllegalArgumentException("unknown replay step: " + line);
        };
    }

    /** The image covers exactly the canvas. */
    private static boolean onImage(UiInputRouter router, float x, float y) {
        return UiCoordinates.onCanvas(router.instance().metrics(), x, y);
    }

    private static String deepestHover(UiDocumentInstance ui) {
        String deepest = "-";
        int depth = -1;
        for (UiElement e : ui.elements()) {
            if (e.hasState(UiElement.HOVER)) {
                int d = 0;
                for (UiElement a = e; a != null; a = a.parent()) {
                    d++;
                }
                if (d > depth) {
                    depth = d;
                    deepest = e.key();
                }
            }
        }
        return deepest;
    }

    private static float f(String s) {
        return Float.parseFloat(s);
    }

    private static int mods(String[] p) {
        int mods = 0;
        for (int i = 2; i < p.length; i++) {
            mods |= switch (p[i]) {
                case "shift" -> MKeys.MOD_SHIFT;
                case "ctrl" -> MKeys.MOD_CONTROL;
                default -> 0;
            };
        }
        return mods;
    }

    private static final Map<String, Integer> KEYS = Map.ofEntries(Map.entry("TAB", MKeys.KEY_TAB),
        Map.entry("ENTER", MKeys.KEY_ENTER), Map.entry("ESCAPE", MKeys.KEY_ESCAPE), Map.entry("SPACE", MKeys.KEY_SPACE),
        Map.entry("UP", MKeys.KEY_UP), Map.entry("DOWN", MKeys.KEY_DOWN), Map.entry("LEFT", MKeys.KEY_LEFT),
        Map.entry("RIGHT", MKeys.KEY_RIGHT), Map.entry("BACKSPACE", MKeys.KEY_BACKSPACE),
        Map.entry("DELETE", MKeys.KEY_DELETE), Map.entry("HOME", MKeys.KEY_HOME), Map.entry("END", MKeys.KEY_END),
        Map.entry("A", MKeys.KEY_A), Map.entry("C", MKeys.KEY_C), Map.entry("V", MKeys.KEY_V),
        Map.entry("X", MKeys.KEY_X), Map.entry("Z", MKeys.KEY_Z));

    private static int key(String name) {
        Integer k = KEYS.get(name);
        return k != null ? k : Integer.parseInt(name);
    }

    private static int pad(String name) {
        return switch (name) {
            case "A" -> GamepadButtons.A;
            case "B" -> GamepadButtons.B;
            case "X" -> GamepadButtons.X;
            case "Y" -> GamepadButtons.Y;
            case "LB" -> GamepadButtons.LEFT_BUMPER;
            case "RB" -> GamepadButtons.RIGHT_BUMPER;
            case "DPAD_UP" -> GamepadButtons.DPAD_UP;
            case "DPAD_DOWN" -> GamepadButtons.DPAD_DOWN;
            case "DPAD_LEFT" -> GamepadButtons.DPAD_LEFT;
            case "DPAD_RIGHT" -> GamepadButtons.DPAD_RIGHT;
            default -> throw new IllegalArgumentException(name);
        };
    }
}
