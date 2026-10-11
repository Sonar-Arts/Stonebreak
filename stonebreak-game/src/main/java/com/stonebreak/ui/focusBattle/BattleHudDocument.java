package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.BattleView;
import com.stonebreak.ui.runtime.screens.DocumentScreen;
import com.stonebreak.ui.runtime.screens.DocumentScreenHost;
import com.stonebreak.ui.runtime.screens.PresentedDocument;

/**
 * The focus battle HUD as the shipped {@code ui/documents/battle_hud.sbui} (#301), shown while the screen is bound and
 * painted where the legacy HUD was (over the battle's world, under the encounter swirl). Before each paint it sets the
 * document's two battle clocks, so clips and tweens authored on them follow the battle and never the frame clock:
 * {@value #HUD_CLOCK} is C1 (the HUD clock: the screen's update steps, which slow-motion and pauses already shape)
 * and {@value #SIM_CLOCK} is C2 (the simulation's elapsed seconds). Falls back to the legacy renderer like every
 * {@link PresentedDocument}.
 */
public final class BattleHudDocument extends PresentedDocument {

    public static final String HUD_CLOCK = "battle";
    public static final String SIM_CLOCK = "battle.sim";

    private final FocusBattleScreen screen;

    public BattleHudDocument(FocusBattleScreen screen, DocumentScreenHost host) {
        super(FocusBattleScreen.DOCUMENT_ID, host, DocumentScreen.Options.screen());
        this.screen = screen;
    }

    @Override
    public boolean paint(int width, int height) {
        DocumentScreen s = screen();
        if (s != null && s.view() != null) {
            BattleView view = screen.view();
            s.view().instance().clocks().set(HUD_CLOCK, screen.hudClock());
            s.view().instance().clocks().set(SIM_CLOCK, view == null ? 0 : view.elapsedSeconds());
        }
        return super.paint(width, height);
    }
}
