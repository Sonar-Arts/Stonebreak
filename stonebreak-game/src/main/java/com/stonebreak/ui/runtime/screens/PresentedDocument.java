package com.stonebreak.ui.runtime.screens;

import com.openmason.engine.ui.rendering.MasonryBackend;
import com.stonebreak.config.Settings;
import com.stonebreak.core.Game;

import java.util.function.BooleanSupplier;

/**
 * A legacy screen shown as its shipped UI document {@code ui/documents/<id>.sbui}: the
 * {@link ScreenPresentation} every migrated screen (#297 onward) installs. The legacy screen keeps
 * its lifecycle and opens this when it shows and closes it when it hides. The document is an
 * {@code ownerPaints} screen: {@link #paint} draws it exactly where the legacy renderer drew, so the
 * frame's composite order does not change.
 *
 * <p>Falls back to the legacy renderer whenever the document is not showing: not shipped, rolled
 * back ({@code -Dstonebreak.ui.legacy=<id>}), refused by a gate, closed after a failing frame, or
 * {@link #suppressed} when it would open.
 */
public class PresentedDocument implements ScreenPresentation {

    private final String id;
    private final DocumentScreenHost host;
    private final DocumentScreen.Options options;
    private final BooleanSupplier suppressed;
    private DocumentScreen screen;

    /**
     * @param options    how the screen behaves; {@code ownerPaints} and {@code onClosed} are set here
     * @param suppressed asked on every show: true keeps the legacy screen this time
     */
    public PresentedDocument(String id, DocumentScreenHost host, DocumentScreen.Options options,
                             BooleanSupplier suppressed) {
        this.id = id;
        this.host = host;
        this.options = options.withOwnerPaints(true).withOnClosed(this::closed);
        this.suppressed = suppressed == null ? () -> false : suppressed;
    }

    public PresentedDocument(String id, DocumentScreenHost host, DocumentScreen.Options options) {
        this(id, host, options, null);
    }

    public final String id() {
        return id;
    }

    @Override
    public void shown() {
        if (screen != null && !screen.isClosing()) {
            return;
        }
        screen = null;
        if (suppressed.getAsBoolean()) {
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

    /** The open screen, or null while the legacy screen shows. */
    protected final DocumentScreen screen() {
        return showing() ? screen : null;
    }

    private void closed() {
        // A frame or render failure closes the screen from the host: the legacy screen takes over.
        if (screen != null && screen.isClosed()) {
            screen = null;
        }
    }

    private static MasonryBackend backend() {
        var renderer = Game.getRenderer();
        return renderer == null ? null : renderer.getSkijaBackend();
    }
}
