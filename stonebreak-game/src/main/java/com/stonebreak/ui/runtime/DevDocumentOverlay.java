package com.stonebreak.ui.runtime;

import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.rendering.MasonryBackend;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.runtime.input.UiAutoClick;
import com.openmason.engine.ui.runtime.input.UiInputGate;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.engine.ui.script.UiScriptDiagnostic;
import com.openmason.engine.ui.script.UiScriptRuntime;
import com.stonebreak.ui.runtime.screens.DocumentScreen;
import com.stonebreak.ui.runtime.screens.DocumentScreenHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Developer overlay: {@code -Dstonebreak.uidoc=<file.omui|file.sbui>} draws that document over
 * every game state through the same {@link UiDocumentView} the Open Mason preview uses (#287).
 * It is a real input host ({@link GameUiInput}, #288): its buttons click, its fields edit and
 * its focus navigates, and what its document consumes never reaches the game; give a
 * full-screen root {@code picking-mode: ignore} to keep clicks reaching the world. Pair it with
 * {@code -Dstonebreak.autoscreenshot} to capture the game's rendering of a document for
 * comparison with the editor. The document's Lua code-behind runs (#292; the
 * {@code ui/script/*.omui} samples in the engine's test resources are made for this), and a
 * script's {@code ui.close()} closes the overlay for good: it leaves the input stack and is
 * disposed at the end of that frame. {@code -Dstonebreak.uidoc.autoclick=key@seconds,...} clicks
 * elements on a schedule for screenshot runs. Its runtime budgets (Lua time per frame, Lua heap,
 * relayout time) show in the F3 overlay and overruns are logged (#296).
 *
 * <p>The overlay is an {@link DocumentScreen} in the {@code OVERLAY} layer of the
 * {@link DocumentScreenHost}: it shares the frame clock, draw order and frame-end close of every
 * document screen. Its activation and input gates only warn (shipped screens go through
 * {@code GameUiDocuments.openBound}, which refuses). An {@code .sbui} is shown through its
 * embedded source, so it does not prove the shipped resolution path; use
 * {@code -Dstonebreak.uiscreen=<id>} for that.
 */
public final class DevDocumentOverlay {

    public static final String PROPERTY = "stonebreak.uidoc";

    private static final Logger LOGGER = LoggerFactory.getLogger(DevDocumentOverlay.class);

    private final String file = System.getProperty(PROPERTY);
    private DocumentScreen screen;
    private boolean failed;

    /** True until the overlay failed to open or its document closed it. */
    public boolean enabled() {
        return file != null && !file.isBlank() && !failed && (screen == null || !screen.isClosed());
    }

    /** Opens the overlay into {@code host} on the first frame the Skija backend is up. */
    public void ensureOpen(DocumentScreenHost host, MasonryBackend backend) {
        if (!enabled() || screen != null || backend == null || !backend.isAvailable()) {
            return;
        }
        UiDocumentView view = null;
        try {
            view = GameUiDocuments.open(Path.of(file), backend::typeface);
            UiHost uiHost = GameUiHost.get().host();
            for (UiDiagnostic d : GameUiDocuments.activationGate(view, uiHost)) {
                LOGGER.warn("[uidoc] activation: {}", d); // dev overlay: shown anyway, but loudly
            }
            DocumentScreen s = host.reserve("dev:" + file, DocumentScreen.Options.overlay());
            // Lua code-behind (#292): bindings use its converters; ui.close()/ui.navigate() queue on the host.
            UiScriptRuntime scripts = GameUiDocuments.scripts(view, uiHost, null, host.services(s));
            for (UiScriptDiagnostic d : scripts.diagnostics()) {
                LOGGER.warn("[uidoc] script: {}", d);
            }
            if (scripts.isScripted()) {
                LOGGER.info("[uidoc] scripts running: {}", scripts.modules());
            }
            // Runtime budgets (#296): F3 "UI Documents" card, [ui-budget] log lines on overruns
            GameUiDocuments.monitor(view, file);
            for (UiRuntimeDiagnostic d : view.instance().diagnostics()) {
                LOGGER.warn("[uidoc] {}", d);
            }
            for (UiInputGate.Block b : GameUiDocuments.inputGate(view, Locale.getDefault())) {
                LOGGER.warn("[uidoc] input gate: {}", b.reason()); // dev overlay: shown anyway, but loudly
            }
            host.adopt(s, view);
            // Scheduled clicks wait for a running world: the pause-screen samples' buttons are pause
            // actions, and firing one under the intro would wedge the game in PAUSED before the
            // world loads. -Dstonebreak.uidoc.autoclick.anystate=true clicks from the first frame.
            boolean anyState = Boolean.getBoolean(PROPERTY + ".autoclick.anystate");
            host.attachAutoClick(s, UiAutoClick.parse(System.getProperty(PROPERTY + ".autoclick")),
                anyState ? null : DocumentScreenHost::worldRunning);
            screen = s;
            LOGGER.info("[uidoc] showing {} ({} elements)", file, view.instance().elements().size());
        } catch (Exception e) {
            failed = true;
            if (view != null) {
                GameUiDocuments.close(view);
            }
            LOGGER.error("[uidoc] cannot show {}", file, e);
        }
    }

    /** Closes the overlay now (shutdown). */
    public void dispose(DocumentScreenHost host) {
        if (screen != null) {
            host.requestClose(screen);
            host.endFrame();
        }
    }
}
