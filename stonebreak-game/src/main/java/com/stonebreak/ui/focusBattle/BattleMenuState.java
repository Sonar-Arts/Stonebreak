package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.BattleCommand;
import com.stonebreak.battle.api.BattleMenu;

import java.util.List;

/**
 * Cursor state machine of the battle command menu: a root list, the Qi Arts submenu and the target
 * step. Pure logic — no Skija, no battle model — so navigation is unit-tested without a window.
 *
 * <p>The cursor wraps in both directions. It resets to the first root row whenever the command
 * window reopens ({@link #syncWindowOpen}), because a fresh turn should never inherit a half-open
 * submenu from the last one. That includes the reopening after a free action (Martial Surge): its
 * animation rests the window for its length, so the cursor comes back on the first root row.
 *
 * <p>Targeting sits <em>on top of</em> the list the command came from: while it is active
 * {@link #level()} answers {@link Level#TARGET}, but the list cursor (and an open submenu) stay
 * exactly where they were, so backing out returns to the same row and the painters keep drawing the
 * path that led here. Self-targeted commands never enter it (see {@link #needsTarget}) — a reaction
 * Guard is always a single confirm.
 */
public final class BattleMenuState {

    /** What the cursor is in: one of the two lists, or choosing a target for a confirmed command. */
    public enum Level { ROOT, SUBMENU, TARGET }

    /** The list the cursor is in; never {@link Level#TARGET}. */
    private Level list = Level.ROOT;
    private int rootIndex;
    private int submenuIndex;
    private boolean windowOpen;
    /** The command awaiting a target, or null while a list has the cursor. */
    private BattleCommand targetCommand;

    public Level level() { return targetCommand != null ? Level.TARGET : list; }
    /** True while the Qi Arts submenu is drawn — also while targeting a command chosen from it. */
    public boolean submenuOpen() { return list == Level.SUBMENU; }
    public int rootIndex() { return rootIndex; }
    public int submenuIndex() { return submenuIndex; }

    /** Rows of the list the cursor is in. */
    public List<BattleMenu.Row> activeRows() {
        return list == Level.ROOT ? BattleMenu.ROOT : BattleMenu.QI_ARTS;
    }

    /** Index of the cursor within {@link #activeRows()}. */
    public int activeIndex() {
        return list == Level.ROOT ? rootIndex : submenuIndex;
    }

    /** The row under the cursor. */
    public BattleMenu.Row selectedRow() {
        return activeRows().get(activeIndex());
    }

    /** The command under the cursor, or null on the Qi Arts opener row. */
    public BattleCommand highlightedCommand() {
        return selectedRow().command();
    }

    // ─────────────────────────────────────────────── Navigation

    public void moveUp() { move(-1); }

    public void moveDown() { move(1); }

    private void move(int delta) {
        if (targetCommand != null) return;   // one target in this encounter: nothing to cycle
        int n = activeRows().size();
        if (list == Level.ROOT) {
            rootIndex = Math.floorMod(rootIndex + delta, n);
        } else {
            submenuIndex = Math.floorMod(submenuIndex + delta, n);
        }
    }

    /**
     * Opens the Qi Arts submenu when the cursor is on its opener row.
     * @return true when the submenu opened
     */
    public boolean openSubmenu() {
        if (targetCommand != null || list != Level.ROOT || !selectedRow().opensQiArts()) return false;
        list = Level.SUBMENU;
        submenuIndex = 0;
        return true;
    }

    /** @return true when a submenu was open and is now closed */
    public boolean closeSubmenu() {
        if (list != Level.SUBMENU) return false;
        targetCommand = null;
        list = Level.ROOT;
        return true;
    }

    /** Pointer hover/click on a root row. Closes the submenu: the cursor can only be in one list. */
    public void selectRoot(int index) {
        if (index < 0 || index >= BattleMenu.ROOT.size()) return;
        targetCommand = null;
        list = Level.ROOT;
        rootIndex = index;
    }

    /** Pointer hover/click on a submenu row. Ignored while the submenu is closed. */
    public void selectSubmenu(int index) {
        if (list != Level.SUBMENU || index < 0 || index >= BattleMenu.QI_ARTS.size()) return;
        targetCommand = null;
        submenuIndex = index;
    }

    // ─────────────────────────────────────────────── Target step

    /** True for commands aimed at the enemy; everything else acts on the monk and submits at once. */
    public static boolean needsTarget(BattleCommand command) {
        if (command == null) return false;
        return switch (command) {
            case STRIKE, FLURRY, STUNNING_STRIKE, FOCUS_COMBO -> true;
            case GUARD, MEDITATE, SWIFT_STEP, MARTIAL_SURGE -> false;
        };
    }

    public boolean targeting() { return targetCommand != null; }

    /** The command waiting for its target, or null. */
    public BattleCommand targetCommand() { return targetCommand; }

    /**
     * Enters the target step for {@code command}.
     * @return false (and no change) for a command that takes no target
     */
    public boolean beginTargeting(BattleCommand command) {
        if (!needsTarget(command)) return false;
        targetCommand = command;
        return true;
    }

    /** @return true when the target step was active and the cursor is back in its list */
    public boolean cancelTargeting() {
        if (targetCommand == null) return false;
        targetCommand = null;
        return true;
    }

    /**
     * One step back: out of the target step, else out of the submenu.
     * @return false at the root list, where "back" belongs to the caller (pause menu)
     */
    public boolean back() {
        return cancelTargeting() || closeSubmenu();
    }

    // ─────────────────────────────────────────────── Lifecycle

    /**
     * Tracks the model's command window. The cursor resets on every closed → open edge and when the
     * window closes, so a stale submenu or target step can never be drawn or acted on.
     */
    public void syncWindowOpen(boolean open) {
        if (open != windowOpen) {
            windowOpen = open;
            reset();
        }
    }

    public void reset() {
        list = Level.ROOT;
        rootIndex = 0;
        submenuIndex = 0;
        targetCommand = null;
    }
}
