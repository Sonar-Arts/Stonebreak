package com.stonebreak.ui.runtime;

import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.rendering.MasonryBackend;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.script.UiScriptDiagnostic;
import com.openmason.engine.ui.script.UiScriptRuntime;
import com.openmason.engine.ui.runtime.input.UiInputGate;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Developer overlay: {@code -Dstonebreak.uidoc=<file.omui|file.sbui>} draws that document over
 * every game state through the same {@link UiDocumentView} the Open Mason preview uses (#287).
 * Since #288 it is a real input host: {@link GameUiInput} routes pointer, keyboard, text and
 * controller input to it ahead of the game, so its buttons click, its fields edit and its
 * focus navigates. It consumes what its document consumes; give a full-screen root
 * {@code picking-mode: ignore} to keep clicks reaching the world. Pair it with
 * {@code -Dstonebreak.autoscreenshot} to capture the game's rendering of a document for
 * comparison with the editor. Since #292 the document's Lua code-behind runs too (the
 * {@code ui/script/*.omui} samples in the engine's test resources are made for this), and a
 * script's {@code ui.close()} hides the overlay. {@code -Dstonebreak.uidoc.autoclick=key@seconds,...}
 * clicks elements on a schedule for screenshot runs.
 */
public final class DevDocumentOverlay {

    public static final String PROPERTY = "stonebreak.uidoc";

    private static final Logger LOGGER = LoggerFactory.getLogger(DevDocumentOverlay.class);

    private final String file = System.getProperty(PROPERTY);
    private UiDocumentView view;
    private MasonryUI masonry;
    private UiScriptRuntime scripts;
    private boolean failed;
    private boolean hidden;
    private long lastFrame;
    /** {@code -Dstonebreak.uidoc.autoclick=key@seconds,...}: scripted clicks for screenshot runs. */
    private final com.openmason.engine.ui.runtime.input.UiAutoClick autoClick =
        com.openmason.engine.ui.runtime.input.UiAutoClick.parse(System.getProperty(PROPERTY + ".autoclick"));

    public boolean enabled() {
        return file != null && !file.isBlank() && !failed && !hidden;
    }

    public void render(MasonryBackend backend, int width, int height, float uiScale) {
        if (!enabled() || backend == null || !backend.isAvailable()) {
            return;
        }
        if (view == null && !open(backend)) {
            return;
        }
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0 : Math.min(0.25, (now - lastFrame) / 1e9);
        lastFrame = now;
        // UI clock always; game clock only while gameplay runs (#295); then scripts (#292)
        GameUiDocuments.frame(view, dt, GameUiDocuments.gameRunning());
        if (autoClick != null) {
            autoClick.tick(view.instance(), view.input(), dt);
        }
        GameUiDocuments.render(view, masonry, width, height, uiScale);
    }

    private boolean open(MasonryBackend backend) {
        try {
            view = GameUiDocuments.open(Path.of(file), backend::typeface);
            UiHost host = GameUiHost.get().host();
            for (UiDiagnostic d : GameUiDocuments.activationGate(view, host)) {
                LOGGER.warn("[uidoc] activation: {}", d); // dev overlay: shown anyway, but loudly
            }
            // Lua code-behind (#292): bindings use its converters; ui.close() hides the overlay.
            scripts = GameUiDocuments.scripts(view, host, null, new GameUiScriptServices(() -> hidden = true));
            for (UiScriptDiagnostic d : scripts.diagnostics()) {
                LOGGER.warn("[uidoc] script: {}", d);
            }
            if (scripts.isScripted()) {
                LOGGER.info("[uidoc] scripts running: {}", scripts.modules());
            }
            masonry = new MasonryUI(backend);
            for (UiRuntimeDiagnostic d : view.instance().diagnostics()) {
                LOGGER.warn("[uidoc] {}", d);
            }
            for (UiInputGate.Block b : GameUiDocuments.inputGate(view, Locale.getDefault())) {
                LOGGER.warn("[uidoc] input gate: {}", b.reason()); // dev overlay: shown anyway, but loudly
            }
            GameUiInput.get().open(view);
            LOGGER.info("[uidoc] showing {} ({} elements)", file, view.instance().elements().size());
            return true;
        } catch (Exception e) {
            failed = true;
            LOGGER.error("[uidoc] cannot show {}", file, e);
            return false;
        }
    }

    public void dispose() {
        scripts = null;
        if (view != null) {
            GameUiInput.get().close(view);
            view.close();
            view = null;
        }
        if (masonry != null) {
            masonry.dispose();
            masonry = null;
        }
    }
}
