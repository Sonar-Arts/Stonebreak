package com.stonebreak.ui.pauseMenu;

import com.openmason.engine.ui.rendering.MasonryBackend;
import com.stonebreak.config.Settings;
import com.stonebreak.core.Game;
import com.stonebreak.ui.DeathMenu;
import com.stonebreak.ui.PauseMenu;
import com.stonebreak.ui.runtime.screens.DocumentScreen;
import com.stonebreak.ui.runtime.screens.DocumentScreenHost;

import java.util.Optional;

/**
 * The pause menu as the shipped UI document {@code ui/documents/pause.sbui} (#297): authored in
 * Open Mason, behaviour in its Lua code-behind, actions through the UI host's
 * {@code stonebreak:screen.pause.*} / {@code stonebreak:network.resync} (the same
 * {@code PauseMenuActions} the legacy clicks call).
 *
 * <p>{@link PauseMenu} keeps the lifecycle: Escape, the PAUSED state and visibility stay where they
 * were, and it opens this presentation when it becomes visible and closes it when hidden. The
 * screen is {@code ownerPaints}: {@link #paint} draws it from {@code PauseMenu.render}, so the field
 * pause is still composited twice per frame (ledger hard visual 13) and the battle pause once.
 *
 * <p>Falls back to the legacy renderer whenever the document is not showing: not shipped, rolled
 * back ({@code -Dstonebreak.ui.legacy=pause}), refused by a gate, closed after a failing frame, or
 * opened under the death menu (whose Respawn click the legacy pause never blocked).
 */
public final class PauseDocument implements PauseMenu.Presentation {

    public static final String ID = "pause";

    private final DocumentScreenHost host;
    private DocumentScreen screen;

    public PauseDocument(DocumentScreenHost host) {
        this.host = host;
    }

    @Override
    public void shown() {
        if (screen != null && !screen.isClosing()) {
            return;
        }
        screen = null;
        if (deathMenuVisible()) {
            return;
        }
        DocumentScreen.Options options = DocumentScreen.Options.screen()
            .withOwnerPaints(true)
            .withOnClosed(this::closed);
        Optional<DocumentScreen> opened = host.open(ID, options);
        screen = opened.orElse(null);
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
        MasonryBackend backend = backend();
        return host.paint(s, backend, width, height, Settings.getInstance().getUiScale());
    }

    /** True while the pause menu is the shipped document rather than the legacy renderer. */
    public boolean showing() {
        return screen != null && !screen.isClosing();
    }

    private void closed() {
        // A frame or render failure closes the screen from the host: the legacy menu takes over.
        if (screen != null && screen.isClosed()) {
            screen = null;
        }
    }

    private static boolean deathMenuVisible() {
        Game game = Game.getInstance();
        DeathMenu death = game == null ? null : game.getDeathMenu();
        return death != null && death.isVisible();
    }

    private static MasonryBackend backend() {
        var renderer = Game.getRenderer();
        return renderer == null ? null : renderer.getSkijaBackend();
    }
}
