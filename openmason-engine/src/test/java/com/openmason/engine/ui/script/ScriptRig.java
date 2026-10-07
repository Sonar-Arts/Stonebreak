package com.openmason.engine.ui.script;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiMetrics;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.engine.ui.runtime.input.PointerEvent;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.openmason.engine.ui.runtime.paint.UiPainter;

import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A scripted document on a headless view: layout at 400x300, text measured by
 * {@link UiDocs#FIXED_TEXT}, scripts loaded through {@link UiScripts#open}. Not a test class.
 */
final class ScriptRig implements AutoCloseable {

    final UiDocumentInstance ui;
    final UiDocumentView view;
    final UiScriptRuntime rt;

    ScriptRig(OmuiArchive doc) {
        this(doc, UiDocumentSource.EMPTY, null, UiScriptOptions.DEFAULTS, UiScriptServices.NONE);
    }

    ScriptRig(OmuiArchive doc, UiDocumentSource source, UiHost host, UiScriptOptions options,
              UiScriptServices services) {
        assumeLua();
        ui = UiDocumentInstance.instantiate(doc,
            UiRuntimeContext.basic().withMeasurer(UiDocs.FIXED_TEXT).withSource(source));
        ui.setMetrics(UiMetrics.of(400, 300, 1));
        ui.update();
        view = new UiDocumentView(ui, new UiPainter(UiPaintHost.NONE, null));
        rt = UiScripts.open(view, host, null, options, services);
        layout();
    }

    /**
     * UI scripting has no Java fallback, so its suites must not pass silently without the native
     * library (#292): they fail with the load diagnostic. {@code -Dui.script.allowMissingNative=true}
     * turns that into a skip for machines that cannot build Cenda.
     */
    static void assumeLua() {
        if (CendaLua.isAvailable()) {
            return;
        }
        String why = "the Cenda Lua host is unavailable: " + failure()
            + " (build it: openmason-engine/cenda/build-kernels.sh)";
        assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), why);
        throw new AssertionError(why);
    }

    private static String failure() {
        try {
            CendaLua.require();
            return "unknown";
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    /** {@code doc} with {@code source} as its code-behind ({@code scripts/main.lua}). */
    static OmuiArchive withCode(OmuiArchive doc, String source) {
        return withCode(doc, "main", source);
    }

    static OmuiArchive withCode(OmuiArchive doc, String part, String source) {
        UiDocument d = doc.document();
        return doc.withDocument(new UiDocument(d.root(), d.styleSheets(), part, d.component(), d.unknown()))
            .withScript(part, source);
    }

    /** What {@code UiDocumentView.layout} does: update, reconcile input, then the settle point (#325). */
    void layout() {
        ui.update();
        view.input().sync();
        ui.update();
        if (rt != null) {
            rt.settle();
            ui.update();
        }
    }

    /** One host frame of {@code dt} seconds. */
    void frame(double dt) {
        view.input().tick(dt);
        view.frame(dt);
        layout();
    }

    /** Live reload: the document, then its scripts, then layout (as the hosts do). */
    void reload(OmuiArchive doc) {
        ui.reload(doc);
        rt.reload();
        layout();
    }

    void click(String key) {
        UiRect r = el(key).rect();
        float x = r.x() + r.width() / 2f;
        float y = r.y() + r.height() / 2f;
        view.input().pointerDown(x, y, PointerEvent.PRIMARY, 0);
        view.input().pointerUp(x, y, PointerEvent.PRIMARY, 0);
        layout();
    }

    UiElement el(String key) {
        UiElement e = ui.find(key);
        if (e == null) {
            throw new IllegalArgumentException("no element " + key);
        }
        return e;
    }

    String text(String key) {
        return el(key).text("text");
    }

    List<String> log() {
        return rt.console().entries().stream().map(UiScriptConsole.Entry::message).toList();
    }

    List<UiScriptDiagnostic.Code> codes() {
        return rt.diagnostics().stream().map(UiScriptDiagnostic::code).toList();
    }

    static UiValue num(double v) {
        return UiValue.of(v);
    }

    @Override
    public void close() {
        view.close();
    }
}
