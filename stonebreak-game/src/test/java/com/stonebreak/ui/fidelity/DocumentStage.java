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
 * A shipped screen document open on a {@link LegacyUiRaster}, exactly as the game opens it
 * ({@code GameUiDocuments.openBound} against a {@link GameUiHost}, Lua code-behind running), bound
 * to {@link RecordingServices}: the candidate side of the #299 migration gates. Not a test class.
 */
public class DocumentStage implements AutoCloseable {

    public final LegacyUiRaster raster;
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
        raster = new LegacyUiRaster(w, h, scale);
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

    /** Where the document's hit test answers for each named element, probed like the legacy captures. */
    public Map<String, float[]> hits(Map<String, float[]> rects) {
        Map<String, float[]> out = new LinkedHashMap<>();
        rects.forEach((n, r) -> {
            UiElement target = q("#" + n);
            LegacyPauseCapture.HitProbe probe = (x, y) -> within(view.instance().hitTest(x, y), target);
            out.put(n, LegacyDeathCapture.probeRegion(probe, r, raster.width, raster.height));
        });
        return out;
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
