package com.stonebreak.ui.pauseMenu;

import com.stonebreak.ui.PauseMenu;
import com.stonebreak.ui.runtime.screens.DocumentScreen;
import com.stonebreak.ui.runtime.screens.DocumentScreenHost;
import com.stonebreak.ui.runtime.screens.PresentedDocument;

/**
 * The pause menu as the shipped UI document {@code ui/documents/pause.sbui} (#297): authored in
 * Open Mason, behaviour in its Lua code-behind, actions through the UI host's
 * {@code stonebreak:screen.pause.*} / {@code stonebreak:network.resync} ({@code PauseMenuActions}).
 *
 * <p>{@link PauseMenu} keeps the lifecycle: Escape, the PAUSED state and visibility stay where they
 * were, and it opens this presentation when it becomes visible and closes it when hidden. The
 * screen is {@code ownerPaints}: {@link #paint} draws it from {@code PauseMenu.render}, so the field
 * pause is still composited twice per frame (ledger hard visual 13) and the battle pause once.
 *
 * <p>Paused while dead, it opens above the death screen and takes the input until it closes; the
 * frame's modal pass paints the two in the order they opened ({@code FrameRenderer.renderModalMenus}).
 */
public final class PauseDocument extends PresentedDocument implements PauseMenu.Presentation {

    public static final String ID = "pause";

    public PauseDocument(DocumentScreenHost host) {
        super(ID, host, DocumentScreen.Options.screen());
    }
}
