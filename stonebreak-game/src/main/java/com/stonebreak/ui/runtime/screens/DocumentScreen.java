package com.stonebreak.ui.runtime.screens;

import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.runtime.input.UiAutoClick;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;

/**
 * One UI document open in the game window, owned by {@link DocumentScreenHost}: its view (with
 * bindings, scripts and budget monitor, which close with it), its layer and its per-view Masonry
 * handle. Main thread only.
 */
public final class DocumentScreen {

    /**
     * How a document screen behaves in the game window.
     *
     * @param layer           where it sits in the window's stack
     * @param releasesPointer while open, the cursor is free (camera look stops), as for menus
     * @param claimsKeyboard  while open, gameplay key polls see no key at all
     * @param claimsGamepad   receives controller buttons even while the cursor is captured
     * @param perWorld        closed automatically when the player leaves the world
     * @param onClosed        run after the screen closed (frame end), or null
     */
    public record Options(UiLayer layer, boolean releasesPointer, boolean claimsKeyboard, boolean claimsGamepad,
                          boolean perWorld, Runnable onClosed) {

        public Options {
            layer = layer == null ? UiLayer.SCREEN : layer;
        }

        /** A full screen or panel: frees the cursor; closed when the world is left. */
        public static Options screen() {
            return new Options(UiLayer.SCREEN, true, false, false, true, null);
        }

        /** A shell menu outside any world (main menu, settings). */
        public static Options menu() {
            return new Options(UiLayer.SCREEN, true, false, false, false, null);
        }

        /** Gameplay HUD: never takes the cursor or the keyboard. */
        public static Options hud() {
            return new Options(UiLayer.HUD, false, false, false, true, null);
        }

        /** Developer/diagnostic overlay above everything (survives world changes). */
        public static Options overlay() {
            return new Options(UiLayer.OVERLAY, false, false, false, false, null);
        }

        public Options withLayer(UiLayer l) {
            return new Options(l, releasesPointer, claimsKeyboard, claimsGamepad, perWorld, onClosed);
        }

        public Options withClaimsKeyboard(boolean claims) {
            return new Options(layer, releasesPointer, claims, claimsGamepad, perWorld, onClosed);
        }

        public Options withClaimsGamepad(boolean claims) {
            return new Options(layer, releasesPointer, claimsKeyboard, claims, perWorld, onClosed);
        }

        public Options withOnClosed(Runnable r) {
            return new Options(layer, releasesPointer, claimsKeyboard, claimsGamepad, perWorld, r);
        }
    }

    private final String id;
    private final Options options;
    private UiDocumentView view;
    private MasonryUI masonry;
    private UiAutoClick autoClick;
    private java.util.function.BooleanSupplier autoClickArmed = () -> true;
    private com.openmason.engine.ui.runtime.input.UiInputGate.Monitor inputGate;
    private boolean closeRequested;
    private boolean closed;

    DocumentScreen(String id, Options options) {
        this.id = id;
        this.options = options;
    }

    public String id() {
        return id;
    }

    public Options options() {
        return options;
    }

    /** The document view; null only while the screen is being opened. */
    public UiDocumentView view() {
        return view;
    }

    /** True once a close was requested (it happens at frame end). */
    public boolean isClosing() {
        return closeRequested;
    }

    public boolean isClosed() {
        return closed;
    }

    void attach(UiDocumentView v) {
        this.view = v;
    }

    void autoClick(UiAutoClick c) {
        this.autoClick = c;
    }

    UiAutoClick autoClick() {
        return autoClick;
    }

    boolean autoClickArmed() {
        return autoClickArmed.getAsBoolean();
    }

    void autoClickArmed(java.util.function.BooleanSupplier armed) {
        this.autoClickArmed = armed == null ? () -> true : armed;
    }

    com.openmason.engine.ui.runtime.input.UiInputGate.Monitor inputGate() {
        return inputGate;
    }

    void inputGate(com.openmason.engine.ui.runtime.input.UiInputGate.Monitor m) {
        this.inputGate = m;
    }

    MasonryUI masonry() {
        return masonry;
    }

    void masonry(MasonryUI m) {
        this.masonry = m;
    }

    /** @return true on the first request */
    boolean requestClose() {
        if (closeRequested || closed) {
            return false;
        }
        closeRequested = true;
        return true;
    }

    void markClosed() {
        closed = true;
    }
}
