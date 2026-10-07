package com.stonebreak.ui.pauseMenu;

import com.stonebreak.core.Game;
import com.stonebreak.ui.DeathMenu;
import com.stonebreak.ui.PauseMenu;
import com.stonebreak.ui.runtime.screens.DocumentScreen;
import com.stonebreak.ui.runtime.screens.DocumentScreenHost;
import com.stonebreak.ui.runtime.screens.PresentedDocument;

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
 * <p>Falls back to the legacy renderer whenever the document is not showing (see
 * {@link PresentedDocument}), and also when opened under the death menu (whose Respawn click the
 * legacy pause never blocked).
 */
public final class PauseDocument extends PresentedDocument implements PauseMenu.Presentation {

    public static final String ID = "pause";

    public PauseDocument(DocumentScreenHost host) {
        super(ID, host, DocumentScreen.Options.screen(), PauseDocument::deathMenuVisible);
    }

    private static boolean deathMenuVisible() {
        Game game = Game.getInstance();
        DeathMenu death = game == null ? null : game.getDeathMenu();
        return death != null && death.isVisible();
    }
}
