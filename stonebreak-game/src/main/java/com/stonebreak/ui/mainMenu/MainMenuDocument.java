package com.stonebreak.ui.mainMenu;

import com.stonebreak.ui.MainMenu;
import com.stonebreak.ui.runtime.GameUiHost;
import com.stonebreak.ui.runtime.screens.DocumentScreen;
import com.stonebreak.ui.runtime.screens.DocumentScreenHost;
import com.stonebreak.ui.runtime.screens.PresentedDocument;

/**
 * The main menu as the shipped document {@value MainMenu#DOCUMENT_ID} (#299), shown while the game is
 * in MAIN_MENU over the live world backdrop. Each paint first lets the UI host publish the backdrop's
 * veil and the menu's reveal, so the fade drawn is this frame's.
 */
public final class MainMenuDocument extends PresentedDocument {

    public MainMenuDocument(MainMenu menu, DocumentScreenHost host) {
        super(MainMenu.DOCUMENT_ID, host, DocumentScreen.Options.menu());
    }

    @Override
    public boolean paint(int width, int height) {
        if (!showing()) {
            return false;
        }
        GameUiHost.ifPresent(GameUiHost::drain);
        return super.paint(width, height);
    }
}
