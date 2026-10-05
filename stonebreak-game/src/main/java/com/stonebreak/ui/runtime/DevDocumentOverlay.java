package com.stonebreak.ui.runtime;

import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.rendering.MasonryBackend;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.runtime.binding.UiConverters;
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
 * comparison with the editor.
 */
public final class DevDocumentOverlay {

    public static final String PROPERTY = "stonebreak.uidoc";

    private static final Logger LOGGER = LoggerFactory.getLogger(DevDocumentOverlay.class);

    private final String file = System.getProperty(PROPERTY);
    private UiDocumentView view;
    private MasonryUI masonry;
    private boolean failed;

    public boolean enabled() {
        return file != null && !file.isBlank() && !failed;
    }

    public void render(MasonryBackend backend, int width, int height, float uiScale) {
        if (!enabled() || backend == null || !backend.isAvailable()) {
            return;
        }
        if (view == null && !open(backend)) {
            return;
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
            // No Lua code-behind yet (#292): converter bindings report MISSING_CONVERTER.
            GameUiDocuments.bind(view, host, UiConverters.NONE);
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
