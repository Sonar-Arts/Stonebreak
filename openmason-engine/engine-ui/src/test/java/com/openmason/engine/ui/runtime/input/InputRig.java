package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiMetrics;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.UiRuntimeContext;

import java.util.ArrayList;
import java.util.List;

/**
 * A laid-out document plus its router, for headless input tests. Not a test class. Text is
 * measured by {@link UiDocs#FIXED_TEXT} (8 px per character), so geometry is deterministic.
 */
final class InputRig implements AutoCloseable {

    final UiDocumentInstance ui;
    final UiInputRouter router;
    final List<String> log = new ArrayList<>();

    InputRig(OmuiArchive doc) {
        this(doc, 400, 300, InputSettings.DEFAULTS);
    }

    InputRig(OmuiArchive doc, float width, float height, InputSettings settings) {
        this(doc, UiRuntimeContext.basic().withMeasurer(UiDocs.FIXED_TEXT), width, height, settings);
    }

    InputRig(OmuiArchive doc, UiRuntimeContext context, float width, float height, InputSettings settings) {
        ui = UiDocumentInstance.instantiate(doc, context);
        ui.setMetrics(UiMetrics.of(width, height, 1));
        ui.update();
        router = new UiInputRouter(ui, settings, UiActionMap.defaults());
        frame();
    }

    /** One host frame: layout, input reconciliation, layout again for state restyles. */
    void frame() {
        ui.update();
        router.sync();
        ui.update();
    }

    UiElement el(String key) {
        UiElement e = ui.find(key);
        if (e == null) {
            throw new IllegalArgumentException("no element " + key);
        }
        return e;
    }

    float cx(String key) {
        UiRect r = el(key).rect();
        return r.x() + r.width() / 2f;
    }

    float cy(String key) {
        UiRect r = el(key).rect();
        return r.y() + r.height() / 2f;
    }

    boolean click(String key) {
        float x = cx(key);
        float y = cy(key);
        boolean a = router.pointerDown(x, y, PointerEvent.PRIMARY, 0);
        boolean b = router.pointerUp(x, y, PointerEvent.PRIMARY, 0);
        frame();
        return a | b;
    }

    boolean press(int key) {
        return press(key, 0);
    }

    boolean press(int key, int mods) {
        boolean a = router.keyDown(key, mods, false);
        boolean b = router.keyUp(key, mods);
        frame();
        return a | b;
    }

    String focusKey() {
        UiElement f = router.focus().focused();
        return f == null ? null : f.key();
    }

    /** Logs every event of {@code types} reaching {@code key} as "TYPE@key/phase". */
    void listen(String key, EventCallbacks.Phase phase, UiEventType... types) {
        for (UiEventType t : types) {
            el(key).on(t, e -> log.add(t + "@" + e.currentTarget().key() + "/" + e.phase()), phase);
        }
    }

    /** {@code doc} with {@code features} added to the manifest's {@code requires}. */
    static OmuiArchive withFeatures(OmuiArchive doc, String... features) {
        com.openmason.engine.format.omui.UiManifest m = doc.manifest();
        List<String> req = new ArrayList<>(m.requires());
        req.addAll(List.of(features));
        return doc.withManifest(new com.openmason.engine.format.omui.UiManifest(m.schemaVersion(), m.documentId(),
            m.kind(), m.displayName(), m.uiApi(), m.layoutSemantics(), req, m.hostApis(), m.providers(), m.unknown()));
    }

    @Override
    public void close() {
        ui.close();
    }
}
