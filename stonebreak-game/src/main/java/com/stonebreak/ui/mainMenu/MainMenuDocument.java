package com.stonebreak.ui.mainMenu;

import com.stonebreak.ui.MainMenu;
import com.stonebreak.ui.runtime.GameUiHost;
import com.stonebreak.ui.runtime.screens.DocumentScreen;
import com.stonebreak.ui.runtime.screens.DocumentScreenHost;
import com.stonebreak.ui.runtime.screens.PresentedDocument;

/**
 * The main menu as the shipped document {@value MainMenu#DOCUMENT_ID} (#299), shown while the game is
 * in MAIN_MENU. Each paint first advances the title animation (as the legacy {@code render} did) and
 * lets the UI host publish it, so the logo, splash and shake drawn are this frame's.
 */
public final class MainMenuDocument extends PresentedDocument {

    private final MainMenu menu;

    public MainMenuDocument(MainMenu menu, DocumentScreenHost host) {
        super(MainMenu.DOCUMENT_ID, host, DocumentScreen.Options.menu());
        this.menu = menu;
    }

    @Override
    public boolean paint(int width, int height) {
        if (!showing()) {
            return false;
        }
        menu.advance(width, height);
        GameUiHost.ifPresent(GameUiHost::drain);
        return super.paint(width, height);
    }
}
