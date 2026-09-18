package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.TimedGrade;
import com.stonebreak.rendering.UI.masonryUI.MStyle;

/**
 * The battle HUD's ONLY private colours: the semantic gameplay colours the UI library should not
 * own. Everything else (frames, text ramp, tracks, outlines, selection, fonts) comes from
 * {@link MStyle} through the MasonryUI widgets, so the HUD restyles with the rest of the game.
 */
public final class BattlePalette {

    private BattlePalette() {}

    // Resources
    public static final int ATB       = 0xFF4FC3E0;
    public static final int ATB_READY = MStyle.TEXT_PRIMARY;
    public static final int QI        = 0xFF4FCF9A;
    public static final int FOCUS     = MStyle.TEXT_ACCENT;

    // Conditions
    public static final int FROST       = 0xFF7CC4FF;
    public static final int STATUS_GOOD = 0xFF6FD69A;
    public static final int STATUS_BAD  = 0xFFFF8A5C;

    // Enemy cast bar: calm while there is time, hot as the blow nears; the reaction window in gold.
    public static final int CAST_START    = 0xFF4A8FD8;
    public static final int CAST_END      = MStyle.VITAL_CRIT;
    public static final int REACT_WINDOW  = MStyle.TEXT_ACCENT;

    // Per-side accent hairlines on otherwise identical frames.
    public static final int ACCENT_MONK   = 0xFF7CC4FF;
    public static final int ACCENT_ARCHON = 0xFFE0625E;

    public static final int HIT_FLASH = MStyle.VITAL_CRIT;

    public static int accent(CombatantId side) {
        return side == CombatantId.MONK ? ACCENT_MONK : ACCENT_ARCHON;
    }

    public static int grade(TimedGrade grade) {
        if (grade == null) return MStyle.TEXT_SECONDARY;
        return switch (grade) {
            case PERFECT -> MStyle.TEXT_ACCENT;
            case GOOD -> MStyle.TEXT_PRIMARY;
            case MISS -> MStyle.TEXT_ERROR;
        };
    }

    public static int status(BattleStatus status) {
        if (status == null) return MStyle.TEXT_SECONDARY;
        return switch (status) {
            case CHILLED -> FROST;
            case SURGE -> FOCUS;
            default -> status.beneficial() ? STATUS_GOOD : STATUS_BAD;
        };
    }
}
