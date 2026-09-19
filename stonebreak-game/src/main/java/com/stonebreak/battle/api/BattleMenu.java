package com.stonebreak.battle.api;

import java.util.List;

/** Command menu structure, shared by the HUD and its tests. */
public final class BattleMenu {
    private BattleMenu() {}

    /**
     * One row of a menu.
     *
     * @param command       the command this row submits, or null for the Qi Arts submenu opener
     * @param opensQiArts   true for the row that opens {@link #QI_ARTS}
     */
    public record Row(String label, BattleCommand command, boolean opensQiArts) {
        static Row of(BattleCommand c) { return new Row(c.displayName(), c, false); }
    }

    public static final String QI_ARTS_LABEL = "Qi Arts";
    public static final String QI_ARTS_HELP = "Spend Qi on special techniques.";

    /** Root command window, top to bottom. */
    public static final List<Row> ROOT = List.of(
            Row.of(BattleCommand.STRIKE),
            Row.of(BattleCommand.FLURRY),
            new Row(QI_ARTS_LABEL, null, true),
            Row.of(BattleCommand.MEDITATE),
            Row.of(BattleCommand.GUARD),
            Row.of(BattleCommand.FOCUS_COMBO));

    /** Qi Arts submenu, top to bottom. */
    public static final List<Row> QI_ARTS = List.of(
            Row.of(BattleCommand.STUNNING_STRIKE),
            Row.of(BattleCommand.SWIFT_STEP),
            Row.of(BattleCommand.MARTIAL_SURGE));
}
