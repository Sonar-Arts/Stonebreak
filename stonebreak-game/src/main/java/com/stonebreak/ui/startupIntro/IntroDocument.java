package com.stonebreak.ui.startupIntro;

import com.stonebreak.ui.runtime.GameUiHost;
import com.stonebreak.ui.runtime.screens.DocumentScreen;
import com.stonebreak.ui.runtime.screens.DocumentScreenHost;
import com.stonebreak.ui.runtime.screens.PresentedDocument;

/**
 * The STARTUP_INTRO state screen as the shipped {@code ui/documents/intro.sbui} (#301). Before each paint
 * it sizes the scene to the window, publishes {@code stonebreak:screen.intro} and sets the document's
 * {@code intro} clock to the scene's time (the intro's explicit clock: clips on it follow the timeline,
 * never the frame clock). Falls back to the legacy renderer like every {@link PresentedDocument}.
 */
public final class IntroDocument extends PresentedDocument {

    /** The external UI clock the intro document's animations read. */
    public static final String CLOCK = "intro";

    private final SonarArtsIntroScreen intro;

    public IntroDocument(SonarArtsIntroScreen intro, DocumentScreenHost host) {
        super(SonarArtsIntroScreen.DOCUMENT_ID, host, DocumentScreen.Options.menu());
        this.intro = intro;
    }

    @Override
    public boolean paint(int width, int height) {
        DocumentScreen s = screen();
        if (s == null) {
            return false;
        }
        intro.enterIfNeeded(width, height);
        GameUiHost.get().intro().poll();
        if (s.view() != null) {
            s.view().instance().clocks().set(CLOCK, intro.scene().time());
        }
        return super.paint(width, height);
    }
}
