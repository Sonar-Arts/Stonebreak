package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleMenu;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cursor rules of the command menu: wrap-around, the submenu gate, and the per-turn reset. */
class BattleMenuStateTest {

    private static final int QI_ARTS_ROW = FocusBattleLayout.qiArtsRowIndex();

    @Test
    void startsOnTheFirstRootRow() {
        BattleMenuState menu = new BattleMenuState();
        assertEquals(BattleMenuState.Level.ROOT, menu.level());
        assertEquals(0, menu.rootIndex());
        assertEquals(BattleCommand.STRIKE, menu.highlightedCommand());
        assertEquals(BattleMenu.ROOT.get(0), menu.selectedRow());
    }

    @Test
    void theRootCursorWrapsBothWays() {
        BattleMenuState menu = new BattleMenuState();
        menu.moveUp();
        assertEquals(BattleMenu.ROOT.size() - 1, menu.rootIndex(), "up from the top wraps to the bottom");
        assertEquals(BattleCommand.FOCUS_COMBO, menu.highlightedCommand());
        menu.moveDown();
        assertEquals(0, menu.rootIndex(), "down from the bottom wraps to the top");

        for (int i = 0; i < BattleMenu.ROOT.size(); i++) menu.moveDown();
        assertEquals(0, menu.rootIndex(), "a full lap returns to the start");
    }

    @Test
    void theSubmenuOpensOnlyFromItsOpenerRow() {
        BattleMenuState menu = new BattleMenuState();
        assertFalse(menu.openSubmenu(), "Strike does not open a submenu");
        assertFalse(menu.submenuOpen());

        menu.selectRoot(QI_ARTS_ROW);
        assertNull(menu.highlightedCommand(), "the opener row submits nothing");
        assertTrue(menu.selectedRow().opensQiArts());
        assertTrue(menu.openSubmenu());
        assertEquals(BattleMenuState.Level.SUBMENU, menu.level());
        assertEquals(BattleCommand.STUNNING_STRIKE, menu.highlightedCommand());
        assertFalse(menu.openSubmenu(), "already open");
    }

    @Test
    void theSubmenuCursorWrapsAndLeavesTheRootCursorAlone() {
        BattleMenuState menu = new BattleMenuState();
        menu.selectRoot(QI_ARTS_ROW);
        menu.openSubmenu();

        menu.moveUp();
        assertEquals(BattleMenu.QI_ARTS.size() - 1, menu.submenuIndex());
        assertEquals(BattleCommand.MARTIAL_SURGE, menu.highlightedCommand());
        menu.moveDown();
        assertEquals(0, menu.submenuIndex());
        menu.moveDown();
        assertEquals(BattleCommand.SWIFT_STEP, menu.highlightedCommand());
        assertEquals(QI_ARTS_ROW, menu.rootIndex(), "navigating the submenu never moves the root cursor");
        assertEquals(BattleMenu.QI_ARTS, menu.activeRows());
        assertEquals(1, menu.activeIndex());
    }

    @Test
    void closingReturnsToTheOpenerAndReopeningStartsAtTheTop() {
        BattleMenuState menu = new BattleMenuState();
        menu.selectRoot(QI_ARTS_ROW);
        menu.openSubmenu();
        menu.moveDown();

        assertTrue(menu.closeSubmenu());
        assertEquals(BattleMenuState.Level.ROOT, menu.level());
        assertEquals(QI_ARTS_ROW, menu.rootIndex());
        assertFalse(menu.closeSubmenu(), "nothing left to close");

        menu.openSubmenu();
        assertEquals(0, menu.submenuIndex(), "the submenu cursor restarts at its first row");
    }

    @Test
    void pointerSelectionIsBoundsCheckedAndLevelAware() {
        BattleMenuState menu = new BattleMenuState();
        menu.selectRoot(99);
        menu.selectRoot(-1);
        assertEquals(0, menu.rootIndex(), "out-of-range rows are ignored");

        menu.selectSubmenu(1);
        assertEquals(0, menu.submenuIndex(), "the submenu cannot be pointed at while closed");

        menu.selectRoot(QI_ARTS_ROW);
        menu.openSubmenu();
        menu.selectSubmenu(2);
        assertEquals(2, menu.submenuIndex());
        menu.selectSubmenu(7);
        assertEquals(2, menu.submenuIndex());

        menu.selectRoot(4);
        assertFalse(menu.submenuOpen(), "pointing at a root row puts the cursor back in the root list");
        assertEquals(BattleCommand.GUARD, menu.highlightedCommand());
    }

    @Test
    void theCursorResetsWhenTheCommandWindowClosesAndReopens() {
        BattleMenuState menu = new BattleMenuState();
        menu.syncWindowOpen(true);
        menu.selectRoot(QI_ARTS_ROW);
        menu.openSubmenu();
        menu.moveDown();

        menu.syncWindowOpen(true);
        assertTrue(menu.submenuOpen(), "a window that stays open (free action) keeps the cursor");
        assertEquals(1, menu.submenuIndex());

        menu.syncWindowOpen(false);
        assertEquals(BattleMenuState.Level.ROOT, menu.level(), "closing the window drops the submenu");
        assertEquals(0, menu.rootIndex());

        menu.selectRoot(3);
        menu.syncWindowOpen(true);
        assertEquals(0, menu.rootIndex(), "a new turn starts on the first row");
        assertEquals(0, menu.submenuIndex());
    }

    // ── target step ──────────────────────────────────────────────────────────

    @Test
    void onlyCommandsAimedAtTheEnemyNeedATarget() {
        for (BattleCommand command : BattleCommand.values()) {
            boolean expected = command == BattleCommand.STRIKE || command == BattleCommand.FLURRY
                    || command == BattleCommand.STUNNING_STRIKE || command == BattleCommand.FOCUS_COMBO;
            assertEquals(expected, BattleMenuState.needsTarget(command), command.name());
        }
        assertFalse(BattleMenuState.needsTarget(null));

        BattleMenuState menu = new BattleMenuState();
        assertFalse(menu.beginTargeting(BattleCommand.GUARD), "a reaction Guard never enters the target step");
        assertFalse(menu.beginTargeting(BattleCommand.MEDITATE));
        assertFalse(menu.targeting());
        assertEquals(BattleMenuState.Level.ROOT, menu.level());
    }

    @Test
    void targetingSitsOnTopOfTheListItCameFrom() {
        BattleMenuState menu = new BattleMenuState();
        menu.moveDown();
        assertTrue(menu.beginTargeting(BattleCommand.FLURRY));
        assertEquals(BattleMenuState.Level.TARGET, menu.level());
        assertEquals(BattleCommand.FLURRY, menu.targetCommand());
        assertFalse(menu.submenuOpen());

        menu.moveDown();
        menu.moveUp();
        menu.moveUp();
        assertEquals(1, menu.rootIndex(), "one target: directions move nothing, least of all the list");
        assertFalse(menu.openSubmenu(), "and cannot open a submenu from under the target cursor");

        assertTrue(menu.back());
        assertEquals(BattleMenuState.Level.ROOT, menu.level());
        assertEquals(1, menu.rootIndex(), "back returns to the row the command came from");
        assertNull(menu.targetCommand());
        assertFalse(menu.back(), "back at the root list belongs to the caller");
    }

    @Test
    void targetingFromTheSubmenuKeepsItOpenAndBacksOutOneStepAtATime() {
        BattleMenuState menu = new BattleMenuState();
        menu.selectRoot(QI_ARTS_ROW);
        menu.openSubmenu();
        assertTrue(menu.beginTargeting(BattleCommand.STUNNING_STRIKE));
        assertEquals(BattleMenuState.Level.TARGET, menu.level());
        assertTrue(menu.submenuOpen(), "the submenu stays drawn behind the target cursor");
        assertEquals(BattleCommand.STUNNING_STRIKE, menu.highlightedCommand());

        assertTrue(menu.back());
        assertEquals(BattleMenuState.Level.SUBMENU, menu.level(), "first back: out of the target step");
        assertTrue(menu.back());
        assertEquals(BattleMenuState.Level.ROOT, menu.level(), "second back: out of the submenu");
    }

    @Test
    void pointingAtARowOrLosingTheWindowEndsTargeting() {
        BattleMenuState menu = new BattleMenuState();
        menu.syncWindowOpen(true);
        menu.beginTargeting(BattleCommand.STRIKE);
        menu.selectRoot(4);
        assertFalse(menu.targeting(), "a click on another row takes over");
        assertEquals(BattleCommand.GUARD, menu.highlightedCommand());

        menu.beginTargeting(BattleCommand.STRIKE);
        menu.syncWindowOpen(false);
        assertFalse(menu.targeting(), "the window closing drops the target step with everything else");

        menu.syncWindowOpen(true);
        menu.beginTargeting(BattleCommand.FOCUS_COMBO);
        menu.reset();
        assertFalse(menu.targeting());
    }
}
