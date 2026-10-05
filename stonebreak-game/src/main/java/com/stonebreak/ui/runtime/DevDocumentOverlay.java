package com.stonebreak.ui.runtime;

import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.rendering.MasonryBackend;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import org.joml.Vector2f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Developer overlay: {@code -Dstonebreak.uidoc=<file.omui|file.sbui>} draws that document over
 * every game state, with pointer hover routed into {@code :hover}/{@code :active}, through the
 * same {@link UiDocumentView} the Open Mason preview uses. It never consumes input, so the
 * game underneath keeps working. Pair it with {@code -Dstonebreak.autoscreenshot} to capture
 * the game's rendering of a document for comparison with the editor (#287).
 */
public final class DevDocumentOverlay {

    public static final String PROPERTY = "stonebreak.uidoc";

    private static final Logger LOGGER = LoggerFactory.getLogger(DevDocumentOverlay.class);

    private final String file = System.getProperty(PROPERTY);
    private UiDocumentView view;
    private MasonryUI masonry;
    private boolean failed;
    private boolean wasDown;

    public boolean enabled() {
        return file != null && !file.isBlank() && !failed;
    }

    /**
     * @param mouse     pointer in framebuffer pixels, or null when unknown
     * @param mouseDown primary button state
     */
    public void render(MasonryBackend backend, int width, int height, float uiScale, Vector2f mouse, boolean mouseDown) {
        if (!enabled() || backend == null || !backend.isAvailable()) {
            return;
        }
        if (view == null && !open(backend)) {
            return;
        }
        if (mouse != null) {
            if (mouseDown && !wasDown) {
                view.pointerDown(mouse.x, mouse.y);
            } else if (!mouseDown && wasDown) {
                view.pointerUp(mouse.x, mouse.y);
            } else {
                view.pointerMove(mouse.x, mouse.y);
            }
            wasDown = mouseDown;
        }
        GameUiDocuments.render(view, masonry, width, height, uiScale);
    }

    private boolean open(MasonryBackend backend) {
        try {
            view = GameUiDocuments.open(Path.of(file), backend::typeface);
            masonry = new MasonryUI(backend);
            for (UiRuntimeDiagnostic d : view.instance().diagnostics()) {
                LOGGER.warn("[uidoc] {}", d);
            }
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
            view.close();
            view = null;
        }
        if (masonry != null) {
            masonry.dispose();
            masonry = null;
        }
    }
}
