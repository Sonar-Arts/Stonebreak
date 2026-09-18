package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleMenu;
import com.stonebreak.battle.api.BattlePhase;
import com.stonebreak.battle.api.ComboDirection;
import com.stonebreak.battle.api.FakeBattleView;
import com.stonebreak.battle.api.PromptView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the help strip says: the highlighted command, why it is dead, or the input being awaited. */
class BattleHelpTextTest {

    @Test
    void describesTheHighlightedCommand() {
        FakeBattleView view = new FakeBattleView();
        view.commandWindowOpen = true;
        BattleMenuState menu = new BattleMenuState();
        assertEquals(BattleCommand.STRIKE.helpText(), BattleHelpText.lineFor(view, menu).text());

        menu.selectRoot(FocusBattleLayout.qiArtsRowIndex());
        assertEquals(BattleMenu.QI_ARTS_HELP, BattleHelpText.lineFor(view, menu).text());

        menu.openSubmenu();
        BattleHelpText.Line art = BattleHelpText.lineFor(view, menu);
        assertTrue(art.text().startsWith(BattleCommand.STUNNING_STRIKE.helpText()));
        assertTrue(art.text().contains("2 Qi"), "a Qi Art's cost is part of its help line");
        assertFalse(art.warning());
    }

    @Test
    void anUnavailableCommandShowsItsReasonAsAWarning() {
        FakeBattleView view = new FakeBattleView();
        view.commandWindowOpen = true;
        view.unavailable.put(BattleCommand.STRIKE, "Not enough Qi.");
        BattleHelpText.Line line = BattleHelpText.lineFor(view, new BattleMenuState());
        assertEquals("Not enough Qi.", line.text());
        assertTrue(line.warning());

        view.unavailable.put(BattleCommand.STRIKE, "");
        assertEquals(BattleCommand.STRIKE.helpText(), BattleHelpText.lineFor(view, new BattleMenuState()).text(),
                "a blank reason falls back to the description rather than an empty strip");
    }

    @Test
    void promptsOutrankTheMenuAndTheIntroOutranksEverything() {
        FakeBattleView view = new FakeBattleView();
        view.commandWindowOpen = true;
        BattleMenuState menu = new BattleMenuState();

        view.prompt = new PromptView.Ring(0, 3, 0f, 1f, 0.5f, 0.6f, 0.4f, 0.7f);
        assertEquals(BattleHelpText.RING_HINT, BattleHelpText.lineFor(view, menu).text());
        view.prompt = new PromptView.Parry(0f, 0.8f, 1f, 1.03f);
        assertEquals(BattleHelpText.PARRY_HINT, BattleHelpText.lineFor(view, menu).text());
        view.prompt = new PromptView.Combo(List.of(ComboDirection.UP), 0, 0f, 1f, List.of());
        assertEquals(BattleHelpText.COMBO_HINT, BattleHelpText.lineFor(view, menu).text());

        view.phase = BattlePhase.INTRO;
        assertEquals(BattleHelpText.INTRO_HINT, BattleHelpText.lineFor(view, menu).text());
    }

    @Test
    void thereIsNothingToSayWhileWaiting() {
        FakeBattleView view = new FakeBattleView();
        assertTrue(BattleHelpText.lineFor(view, new BattleMenuState()).isEmpty());
        assertTrue(BattleHelpText.lineFor(null, null).isEmpty());
        view.phase = BattlePhase.RESULT;
        view.commandWindowOpen = true;
        assertTrue(BattleHelpText.lineFor(view, new BattleMenuState()).isEmpty());
    }
}
