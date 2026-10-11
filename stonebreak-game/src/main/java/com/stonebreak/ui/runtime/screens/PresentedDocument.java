package com.stonebreak.ui.runtime.screens;

import com.openmason.engine.ui.rendering.MasonryBackend;
import com.stonebreak.config.Settings;
import com.stonebreak.core.Game;

/**
 * A screen shown as its shipped UI document {@code ui/documents/<id>.sbui}: the
 * {@link ScreenPresentation} every migrated screen (#297 onward) installs. The screen class keeps
 * its lifecycle and opens this when it shows and closes it when it hides. The document is an
 * {@code ownerPaints} screen: {@link #paint} draws it where the frame renderer draws that screen, so
 * the frame's composite order does not change.
 *
 * <p>Paints nothing while the document is not showing: not shipped, refused by a gate, or closed
 * after a failing frame.
 */
public class PresentedDocument implements ScreenPresentation {

    private final String id;
    private final DocumentScreenHost host;
    private final DocumentScreen.Options options;
    private DocumentScreen screen;

    /**
     * @param options how the screen behaves; {@code ownerPaints} and {@code onClosed} are set here
     */
    public PresentedDocument(String id, DocumentScreenHost host, DocumentScreen.Options options) {
        this.id = id;
        this.host = host;
        this.options = options.withOwnerPaints(true).withOnClosed(this::closed);
    }

    public final String id() {
        return id;
    }

    @Override
    public void shown() {
        if (screen != null && !screen.isClosing()) {
            return;
        }
        screen = host.open(id, options).orElse(null);
        if (screen != null && screen.view() != null) {
            opened(screen);
        }
    }

    /** The document just opened (input settings, initial state). */
    protected void opened(DocumentScreen opened) {
    }

    @Override
    public void hidden() {
        if (screen != null) {
            host.requestClose(screen);
            screen = null;
        }
    }

    @Override
    public boolean paint(int width, int height) {
        DocumentScreen s = screen;
        if (s == null || s.isClosing()) {
            return false;
        }
        return host.paint(s, backend(), width, height, Settings.getInstance().getUiScale());
    }

    @Override
    public boolean showing() {
        return screen != null && !screen.isClosing();
    }

    /** The open screen, or null while none is showing. */
    protected final DocumentScreen screen() {
        return showing() ? screen : null;
    }

    private void closed() {
        // A frame or render failure closes the screen from the host: nothing shows until the next open.
        if (screen != null && screen.isClosed()) {
            screen = null;
        }
    }

    private static MasonryBackend backend() {
        var renderer = Game.getRenderer();
        return renderer == null ? null : renderer.getSkijaBackend();
    }
}
