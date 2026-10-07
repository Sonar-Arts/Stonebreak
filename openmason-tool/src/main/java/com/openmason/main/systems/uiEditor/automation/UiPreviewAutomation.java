package com.openmason.main.systems.uiEditor.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.openmason.engine.ui.data.FixtureHost;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.input.UiInputRouter;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.engine.ui.script.UiScriptConsole;
import com.openmason.engine.ui.script.UiScriptRuntime;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.service.UiSnapshot;
import com.openmason.main.systems.uiEditor.view.DesignerRuntime;
import com.openmason.main.systems.uiEditor.view.DocumentViewState;
import com.stonebreak.ui.runtime.GameUiDocuments;
import io.github.humbleui.skija.Typeface;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Preview and capture for automation (#324). Everything here is runtime or view state: the
 * designer's mode, forced pseudo-states, the frame size and scales, input fed to a running
 * preview. None of it is source, so no call here can change a document or its dirty state
 * (the runtime is thrown away when preview ends, exactly as for the author).
 */
public final class UiPreviewAutomation {

    /** Largest frame a capture paints, per side. */
    public static final int MAX_FRAME = 7680;
    private static final double FRAME_DT = 1.0 / 60.0;

    /** Reads the live GPU preview frame; absent headless, where the live view is painted on the CPU. */
    @FunctionalInterface
    public interface FrameGrabber {
        /** @return the frame as painted, or null to fall back to a CPU paint of the live view */
        BufferedImage grab(DesignerRuntime runtime, int width, int height, float uiScale, float pixelRatio);
    }

    /** A frame size and scales; nulls mean "the document's current view". */
    public record Frame(Integer width, Integer height, Float uiScale, Float pixelRatio) {
        public static final Frame CURRENT = new Frame(null, null, null, null);

        Frame resolve(DocumentViewState v) {
            int w = width != null ? width : v.frameWidth;
            int h = height != null ? height : v.frameHeight;
            float s = uiScale != null ? uiScale : v.uiScale;
            float r = pixelRatio != null ? pixelRatio : v.pixelRatio;
            if (w < 16 || h < 16 || w > MAX_FRAME || h > MAX_FRAME) {
                throw new IllegalArgumentException("frame " + w + "x" + h + " is out of range (16.." + MAX_FRAME + ")");
            }
            if (!(s >= 0.25f && s <= 8f) || !(r >= 0.5f && r <= 4f)) {
                throw new IllegalArgumentException("ui_scale must be 0.25-8 and pixel_ratio 0.5-4");
            }
            return new Frame(w, h, s, r);
        }
    }

    private final UiAutomation ui;

    UiPreviewAutomation(UiAutomation ui) {
        this.ui = ui;
    }

    private DesignerRuntime runtime(UiEditorDocument d) {
        DesignerRuntime rt = ui.context().runtime(d);
        if (rt == null) {
            throw new IllegalStateException("The UI runtime is unavailable (the game font failed to load)");
        }
        return rt;
    }

    // ── state ───────────────────────────────────────────────────────────────

    /**
     * Changes preview state. {@code mode} "design"/"preview" (null keeps it); {@code force} sets
     * pseudo-states per element key (an empty list clears that key), {@code clearForced} drops
     * all; {@code frame} changes the designer frame (view state, recorded with the editor's
     * workspace on save but never source).
     */
    public Map<String, Object> set(UiEditorDocument d, String mode, Map<String, List<String>> force,
                                   boolean clearForced, Frame frame) {
        DesignerRuntime rt = runtime(d);
        // validate everything first, so a refused call changes nothing
        DesignerRuntime.Mode m = mode == null ? null : switch (mode.toLowerCase(Locale.ROOT)) {
            case "design" -> DesignerRuntime.Mode.DESIGN;
            case "preview" -> DesignerRuntime.Mode.PREVIEW;
            default -> throw new IllegalArgumentException("mode is \"design\" or \"preview\"");
        };
        DocumentViewState v = ui.context().view(d);
        Frame f = frame == null || frame == Frame.CURRENT ? null : frame.resolve(v);
        Map<String, List<String>> forced = new LinkedHashMap<>();
        if (force != null) {
            UiDocumentInstance inst = ui.laidOut(d, true);
            for (Map.Entry<String, List<String>> e : force.entrySet()) {
                String key = com.openmason.main.systems.uiEditor.ops.UiInspector.resolveKey(
                    d.archive().document().root(), e.getKey());
                if (inst.find(key) == null) {
                    throw new IllegalArgumentException("force: no element '" + e.getKey() + "' (ui_tree lists keys)");
                }
                List<String> states = e.getValue().stream().map(st -> st.startsWith(":") ? st.substring(1) : st)
                    .toList();
                for (String st : states) {
                    if (!com.openmason.engine.format.omui.UiSelectors.isIdent(st)) {
                        throw new IllegalArgumentException("force: '" + st + "' is not a pseudo-state name"
                            + " (hover, active, focus, disabled, checked or a custom state)");
                    }
                }
                forced.put(key, states);
            }
        }
        if (m != null) {
            rt.setMode(m);
            if (d == ui.service().active()) {
                ui.reveal();
            }
        }
        if (clearForced) {
            rt.clearForcedStates();
        }
        forced.forEach((key, states) -> {
            for (String st : rt.forcedStates(key)) {
                if (!states.contains(st)) {
                    rt.forceState(key, st, false);
                }
            }
            states.forEach(st -> rt.forceState(key, st, true));
        });
        if (f != null) {
            v.frameWidth = f.width();
            v.frameHeight = f.height();
            v.uiScale = f.uiScale();
            v.pixelRatio = f.pixelRatio();
            v.fitPending = true;
        }
        return state(d);
    }

    public Map<String, Object> state(UiEditorDocument d) {
        DesignerRuntime rt = runtime(d);
        rt.sync();
        DocumentViewState v = ui.context().view(d);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("doc", d.archive().manifest().documentId());
        m.put("mode", rt.mode().name().toLowerCase(Locale.ROOT));
        m.put("frame", Map.of("width", v.frameWidth, "height", v.frameHeight, "uiScale", v.uiScale,
            "pixelRatio", v.pixelRatio));
        Map<String, Set<String>> forced = rt.forcedStateMap();
        if (!forced.isEmpty()) {
            m.put("forced", new TreeMap<>(forced));
        }
        if (rt.error() != null) {
            m.put("error", rt.error());
        }
        if (!rt.activationFindings().isEmpty()) {
            m.put("activation", rt.activationFindings().stream().map(f -> f.message()).toList());
        }
        if (!rt.requests().isEmpty()) {
            m.put("requests", rt.requests());
        }
        m.put("dirty", d.isDirty());
        return m;
    }

    // ── capture ─────────────────────────────────────────────────────────────

    /**
     * The document as the engine paints it at {@code frame}. {@code source} "design" paints the
     * source afresh (forced pseudo-states applied, no scripts); "preview" is the running
     * preview's live frame; "auto" follows the designer's mode.
     */
    public BufferedImage capture(UiEditorDocument d, String source, Frame frame, FrameGrabber grabber)
            throws java.io.IOException {
        DesignerRuntime rt = runtime(d);
        Frame f = frame.resolve(ui.context().view(d));
        String src = source == null || source.isBlank() ? "auto" : source.toLowerCase(Locale.ROOT);
        boolean live = switch (src) {
            case "auto" -> rt.mode() == DesignerRuntime.Mode.PREVIEW;
            case "preview" -> true;
            case "design" -> false;
            default -> throw new IllegalArgumentException("source is \"auto\", \"design\" or \"preview\"");
        };
        Typeface tf = ui.context().typeface();
        if (live) {
            if (rt.mode() != DesignerRuntime.Mode.PREVIEW) {
                throw new IllegalStateException("The document is not in preview; ui_preview mode:\"preview\" first,"
                    + " or capture source:\"design\"");
            }
            rt.sync();
            if (rt.view() == null) {
                throw new IllegalStateException("The preview cannot run: " + rt.error());
            }
            BufferedImage img = grabber == null ? null
                : grabber.grab(rt, f.width(), f.height(), f.uiScale(), f.pixelRatio());
            return img != null ? img
                : UiSnapshot.render(rt.view(), tf, f.width(), f.height(), f.uiScale(), f.pixelRatio());
        }
        try (UiDocumentView view = GameUiDocuments.open(d.archive(), ui.context().project.sources(), () -> tf,
            Map.of())) {
            UiDocumentInstance inst = view.instance();
            inst.setMetrics(new com.openmason.engine.ui.runtime.UiMetrics(f.width(), f.height(), f.uiScale(),
                f.pixelRatio()));
            inst.update();
            rt.forcedStateMap().forEach((key, states) -> {
                UiElement el = inst.find(key);
                if (el != null) {
                    states.forEach(st -> el.setState(st, true));
                }
            });
            return UiSnapshot.render(view, tf, f.width(), f.height(), f.uiScale(), f.pixelRatio());
        }
    }

    // ── input ───────────────────────────────────────────────────────────────

    /**
     * Feeds input to the running preview through its input router (what {@code PreviewInput}
     * drives from the canvas), then advances the preview clock by {@code advanceMs} in 60 Hz
     * steps so scripts, tweens and fixture results settle.
     *
     * <p>Events: {@code {"type":"click"|"move"|"down"|"up", "key":"quit" | "x":..,"y":.., "button":0}},
     * {@code {"type":"key","name":"enter","mods":["shift"]}}, {@code {"type":"text","text":"abc"}},
     * {@code {"type":"wheel", key|x,y, "dy":-1}}. Coordinates are frame device pixels.
     */
    public Map<String, Object> input(UiEditorDocument d, JsonNode events, int advanceMs) {
        DesignerRuntime rt = runtime(d);
        if (rt.mode() != DesignerRuntime.Mode.PREVIEW || rt.input() == null) {
            throw new IllegalStateException("Input goes to a running preview: ui_preview mode:\"preview\" first"
                + (rt.error() != null ? " (the preview cannot run: " + rt.error() + ")" : ""));
        }
        UiDocumentInstance inst = ui.laidOut(d, true);
        UiInputRouter router = rt.input().router();
        router.sync();
        List<Object> consumed = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            JsonNode e = events.get(i);
            try {
                consumed.add(dispatch(router, inst, e, d.archive().document().root()));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("events[" + i + "]: " + ex.getMessage());
            }
            inst.update();
            router.sync();
        }
        int steps = Math.max(0, Math.min(600, (int) Math.round(advanceMs / (FRAME_DT * 1000))));
        for (int s = 0; s < steps; s++) {
            rt.step(FRAME_DT);
            inst = ui.laidOut(d, true);
            router.sync();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("consumed", consumed);
        UiElement focused = router.focus().focused();
        if (focused != null) {
            m.put("focused", focused.key());
        }
        UiElement clicked = router.lastClick();
        if (clicked != null) {
            m.put("lastClick", clicked.key());
        }
        if (!rt.requests().isEmpty()) {
            m.put("requests", rt.requests());
        }
        m.put("dirty", d.isDirty());
        return m;
    }

    private static boolean dispatch(UiInputRouter router, UiDocumentInstance inst, JsonNode e,
                                    com.openmason.engine.format.omui.UiNode root) {
        String type = e.path("type").asText("");
        int mods = UiKeys.mods(e.path("mods"));
        int button = e.path("button").asInt(0);
        switch (type) {
            case "click" -> {
                float[] p = point(inst, e, root);
                router.pointerMove(p[0], p[1]);
                boolean down = router.pointerDown(p[0], p[1], button, mods);
                boolean up = router.pointerUp(p[0], p[1], button, mods);
                return down || up;
            }
            case "move" -> {
                float[] p = point(inst, e, root);
                return router.pointerMove(p[0], p[1]);
            }
            case "down" -> {
                float[] p = point(inst, e, root);
                router.pointerMove(p[0], p[1]);
                return router.pointerDown(p[0], p[1], button, mods);
            }
            case "up" -> {
                float[] p = point(inst, e, root);
                return router.pointerUp(p[0], p[1], button, mods);
            }
            case "leave" -> {
                router.pointerLeave();
                return false;
            }
            case "wheel" -> {
                float[] p = point(inst, e, root);
                return router.wheel(p[0], p[1], (float) e.path("dx").asDouble(0), (float) e.path("dy").asDouble(0),
                    mods);
            }
            case "key" -> {
                int code = UiKeys.code(e.path("name").asText(""));
                boolean down = router.keyDown(code, mods, false);
                boolean up = router.keyUp(code, mods);
                return down || up;
            }
            case "text" -> {
                return router.text(e.path("text").asText(""));
            }
            default -> throw new IllegalArgumentException("type is click, move, down, up, leave, wheel, key or text");
        }
    }

    /** The event's point: the centre of element {@code key}, else {@code x}/{@code y}. */
    private static float[] point(UiDocumentInstance inst, JsonNode e, com.openmason.engine.format.omui.UiNode root) {
        if (e.hasNonNull("key")) {
            String key = com.openmason.main.systems.uiEditor.ops.UiInspector.resolveKey(root, e.get("key").asText());
            UiElement el = inst.find(key);
            if (el == null) {
                throw new IllegalArgumentException("no element '" + key + "' in the preview");
            }
            UiRect r = el.rect();
            if (r.isEmpty()) {
                throw new IllegalArgumentException("'" + key + "' has no area (hidden or collapsed)");
            }
            return new float[]{r.x() + r.width() / 2f, r.y() + r.height() / 2f};
        }
        if (!e.has("x") || !e.has("y")) {
            throw new IllegalArgumentException("give key (an element) or x and y (frame pixels)");
        }
        return new float[]{(float) e.get("x").asDouble(), (float) e.get("y").asDouble()};
    }

    // ── console ─────────────────────────────────────────────────────────────

    /** The preview's script console, host requests, fixture action calls and script findings. */
    public Map<String, Object> console(UiEditorDocument d, int limit) {
        DesignerRuntime rt = runtime(d);
        rt.sync();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", rt.mode().name().toLowerCase(Locale.ROOT));
        UiScriptRuntime scripts = rt.scripts();
        if (scripts != null) {
            List<String> lines = new ArrayList<>();
            List<UiScriptConsole.Entry> entries = scripts.console().entries();
            for (int i = Math.max(0, entries.size() - limit); i < entries.size(); i++) {
                lines.add(entries.get(i).toString());
            }
            m.put("console", lines);
            m.put("modules", scripts.modules());
            if (!scripts.diagnostics().isEmpty()) {
                m.put("diagnostics", scripts.diagnostics().stream().map(Object::toString).toList());
            }
        } else {
            m.put("console", List.of());
            m.put("note", rt.mode() == DesignerRuntime.Mode.PREVIEW
                ? "this document has no code-behind or graphs" : "scripts only run in preview");
        }
        m.put("requests", rt.requests());
        FixtureHost fx = rt.fixtures();
        if (fx != null) {
            List<String> calls = new ArrayList<>();
            fx.calls().forEach(c -> calls.add(c.actionId() + (c.args() == null || c.args().isEmpty() ? ""
                : " " + c.args().fields())));
            m.put("fixtureCalls", calls.subList(Math.max(0, calls.size() - limit), calls.size()));
        }
        return m;
    }
}
