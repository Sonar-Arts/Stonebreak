package com.stonebreak.ui.glossaryScreen;

import com.stonebreak.mobs.entities.EntityAttributes;

/**
 * Every string the glossary shows that is built from data, in one place: the legacy renderer and
 * the UI host's {@code glossary} roots (#299) both use these, so the document shows exactly the
 * legacy text (counts use the default locale's grouping, as {@code String.format} does).
 */
public final class GlossaryText {

    public static final String[] ATTRIBUTES = {"STR", "DEX", "CON", "INT", "WIS", "CHA"};
    public static final String[] DERIVED = {"HP", "SPD", "ATK"};
    public static final String LOCKED_VALUE = "???";
    public static final String DEFEAT_HINT = "Defeat one to reveal";
    public static final String UNLOCK_HINT = "Observe one in the world to unlock";
    public static final String WEAKNESS_UNKNOWN = "Unknown";
    public static final String WEAKNESS_HINT = "Study as Quarry (Ranger) to reveal";
    public static final String NO_ABILITIES = "None known";

    private GlossaryText() {
    }

    public static String observed(int seen, int total) {
        return seen + " / " + total + " observed";
    }

    public static String rowSubtitle(int seen, int totalVariants) {
        return seen > 0 ? seen + "/" + totalVariants + " variants" : "Not yet observed";
    }

    public static String badge(long kills) {
        return kills > 0 ? String.format("%,d", kills) + " defeated" : "Undefeated";
    }

    public static String chip(String variant, int index, int count) {
        return count > 1 ? variant + "  " + (index + 1) + "/" + count : variant;
    }

    public static String score(int score) {
        int mod = EntityAttributes.getModifier(score);
        return score + " (" + (mod >= 0 ? "+" : "") + mod + ")";
    }

    public static int[] scores(EntityAttributes a) {
        return new int[]{a.str(), a.dex(), a.con(), a.intel(), a.wis(), a.cha()};
    }

    public static String[] derived(EntityAttributes a) {
        return new String[]{String.format("%.0f", a.deriveMaxHealth()), String.format("%.1f", a.deriveMoveSpeed()),
            String.valueOf(a.deriveMeleeDamage())};
    }
}
