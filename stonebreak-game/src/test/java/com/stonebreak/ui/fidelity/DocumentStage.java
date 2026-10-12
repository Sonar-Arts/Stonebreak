package com.stonebreak.ui.fidelity;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.GameUiHost;
import com.stonebreak.ui.runtime.GameUiScriptServices;
import com.stonebreak.ui.runtime.screens.UiLayer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A shipped screen document open on a {@link UiRaster}, exactly as the game opens it
 * ({@code GameUiDocuments.openBound} against a {@link GameUiHost}, Lua code-behind running), bound
 * to {@link RecordingServices}: the candidate side of the #299 migration gates. Not a test class.
 */
public class DocumentStage implements AutoCloseable {

    public final UiRaster raster;
    public final RecordingServices services;
    public final GameUiHost host;
    public final UiDocumentView view;
    public final MasonryUI masonry;
    public final float scale;

    public DocumentStage(SbuiArchive sbui, int w, int h, float scale) throws Exception {
        this(sbui, w, h, scale, new RecordingServices(), MultiplayerSession.Mode.SINGLEPLAYER);
    }

    public DocumentStage(SbuiArchive sbui, int w, int h, float scale, RecordingServices services,
                         MultiplayerSession.Mode mode) throws Exception {
        this(sbui, w, h, scale, services, mode, Map.of());
    }

    /**
     * @param providers draw providers by id; GL providers (item icons, model previews) have no CPU
     *                  raster equivalent, so stages stand them in with {@link #NO_GL} as the legacy
     *                  raster captures draw nothing there either
     */
    public DocumentStage(SbuiArchive sbui, int w, int h, float scale, RecordingServices services,
                         MultiplayerSession.Mode mode,
                         Map<String, com.openmason.engine.ui.runtime.paint.UiPaintHost.UiDrawProvider> providers)
            throws Exception {
        raster = new UiRaster(w, h, scale);
        this.scale = scale;
        this.services = services;
        host = new GameUiHost(services, mode);
        host.drain(); // the game's host publishes before a screen opens (DocumentScreenHost.open)
        view = GameUiDocuments.openBound(sbui, Map.of(), raster.backend()::getMinecraftTypeface, providers,
            host.host(), null, new GameUiScriptServices(null), UiLayer.SCREEN);
        masonry = new MasonryUI(raster.backend());
        settle();
    }

    /** A provider that paints nothing: the stand-in for GL-only content on a CPU raster. */
    public static final com.openmason.engine.ui.runtime.paint.UiPaintHost.UiDrawProvider NO_GL =
        (canvas, element, rect, scale) -> { };

    /** Scripts, bindings and layout caught up (the game's frame, minus painting). */
    public void settle() {
        for (int i = 0; i < 3; i++) {
            host.drain();
            GameUiDocuments.frame(view, 0.016, 0);
            view.layout(raster.width, raster.height, scale, 1f);
        }
    }

    public void paint() {
        GameUiDocuments.render(view, masonry, raster.width, raster.height, scale);
    }

    public UiElement q(String selector) {
        return view.instance().q(selector);
    }

    public UiRect rect(String name) {
        return q("#" + name).rect();
    }

    public void hover(String name) {
        UiRect r = rect(name);
        view.pointerMove(r.x() + r.width() / 2f, r.y() + r.height() / 2f);
        settle();
    }

    /** Clicks the centre of element {@code name}; returns the host actions it reached. */
    public List<String> click(String name) {
        UiRect r = rect(name);
        return clickAt(r.x() + r.width() / 2f, r.y() + r.height() / 2f);
    }

    public List<String> clickAt(float x, float y) {
        int before = services.calls.size();
        view.pointerMove(x, y);
        view.pointerDown(x, y);
        view.pointerUp(x, y);
        settle();
        return List.copyOf(services.calls.subList(before, services.calls.size()));
    }

    /** Presses and releases a key (GLFW code); returns the host actions it reached. */
    public List<String> key(int key) {
        int before = services.calls.size();
        view.input().keyDown(key, 0, false);
        view.input().keyUp(key, 0);
        settle();
        return List.copyOf(services.calls.subList(before, services.calls.size()));
    }

    /** Rects of the named elements that are displayed, as {@code [x, y, w, h]}. */
    public Map<String, float[]> rects(List<String> names) {
        Map<String, float[]> out = new LinkedHashMap<>();
        for (String n : names) {
            UiElement el = q("#" + n);
            if (el != null && !el.isCollapsed()) {
                UiRect r = el.rect();
                out.put(n, new float[]{r.x(), r.y(), r.width(), r.height()});
            }
        }
        return out;
    }

    /** Layout rects of the named, displayed elements: before translation (legacy oracles report those). */
    public Map<String, float[]> layoutRects(List<String> names) {
        Map<String, float[]> out = new LinkedHashMap<>();
        for (String n : names) {
            UiElement el = q("#" + n);
            if (el != null && !el.isCollapsed()) {
                UiRect r = el.layoutRect();
                out.put(n, new float[]{r.x(), r.y(), r.width(), r.height()});
            }
        }
        return out;
    }

    /** Where the document's hit test answers for each named element ({@link #probeRegion}). */
    public Map<String, float[]> hits(Map<String, float[]> rects) {
        Map<String, float[]> out = new LinkedHashMap<>();
        rects.forEach((n, r) -> {
            UiElement target = q("#" + n);
            HitProbe probe = (x, y) -> within(view.instance().hitTest(x, y), target);
            out.put(n, probeRegion(probe, r, raster.width, raster.height));
        });
        return out;
    }

    /** Whether a point hits the part being measured. */
    @FunctionalInterface
    public interface HitProbe {
        boolean at(float x, float y);
    }

    /**
     * The region a probe answers for around a drawn rect: bisected out from its centre along both
     * axes ({@link #edge}); a centre that misses reports a zero-sized region.
     */
    public static float[] probeRegion(HitProbe probe, float[] r, int w, int h) {
        float cx = r[0] + r[2] / 2f;
        float cy = r[1] + r[3] / 2f;
        if (!probe.at(cx, cy)) {
            return new float[]{cx, cy, 0, 0};
        }
        float x0 = edge(probe, cx, cy, -1, 0, w, h);
        float x1 = edge(probe, cx, cy, 1, 0, w, h);
        float y0 = edge(probe, cx, cy, 0, -1, w, h);
        float y1 = edge(probe, cx, cy, 0, 1, w, h);
        return new float[]{x0, y0, x1 - x0, y1 - y0};
    }

    /**
     * The farthest coordinate from the centre along one axis that still hits, bisected down to
     * float resolution, so an inclusive {@code x + width} edge comes back exact (the gates compare
     * hit regions as strictly as painted rects).
     */
    public static float edge(HitProbe probe, float cx, float cy, int dx, int dy, int w, int h) {
        float from = dx != 0 ? cx : cy;
        float in = from;
        float out = from + (dx + dy) * (Math.max(w, h) + 1);
        for (int i = 0; i < 64; i++) {
            float mid = (in + out) / 2f;
            if (mid == in || mid == out) {
                break;
            }
            if (dx != 0 ? probe.at(mid, cy) : probe.at(cx, mid)) {
                in = mid;
            } else {
                out = mid;
            }
        }
        return in;
    }

    /** The host action a click on each named element reaches (exactly one, else what fired). */
    public Map<String, String> actions(Map<String, float[]> rects) {
        Map<String, String> out = new LinkedHashMap<>();
        rects.forEach((n, r) -> {
            List<String> fired = clickAt(r[0] + r[2] / 2f, r[1] + r[3] / 2f);
            out.put(n, fired.size() == 1 ? fired.getFirst() : "fired " + fired);
        });
        return out;
    }

    static boolean within(UiElement hit, UiElement ancestor) {
        for (UiElement e = hit; e != null; e = e.parent()) {
            if (e == ancestor) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        try {
            masonry.dispose();
            GameUiDocuments.close(view);
        } finally {
            raster.close();
        }
    }
}
