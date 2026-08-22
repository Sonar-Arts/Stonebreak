package com.stonebreak.rendering.UI.components.hotbar;

import com.stonebreak.player.Player;
import com.stonebreak.player.PlayerConstants;
import com.stonebreak.player.combat.arcanist.ArcanistAbilityController;
import com.stonebreak.player.combat.arcanist.ArcanistHudText;
import com.stonebreak.player.combat.arcanist.ResonanceTracker;
import com.stonebreak.rendering.UI.masonryUI.MStyle;

/**
 * Arcanist Resonance gauge — header with the current stack count (or "OVERLOADED"), four
 * discrete Resonance pips (all switching to hot gold while Overloaded — the distinct visual
 * indicator), the live same-school echo line, and one status line per unlocked spell.
 */
public final class ResonanceGauge implements ClassGauge {

    private static final int PIP_FILLED     = 0xDC9C50E8; // arcane violet
    private static final int PIP_OVERLOADED = 0xDCFFD24A; // hot gold — Overloaded
    private static final int LINE_GAP       = 8;

    @Override
    public String classId() {
        return ArcanistAbilityController.CLASS_ID;
    }

    @Override
    public void draw(GaugePanel panel, Player player) {
        ArcanistAbilityController arcanist = player.getArcanistAbilities();
        ResonanceTracker resonance = arcanist.getResonance();
        int stacks = resonance.getResonanceStacks();
        boolean overloaded = resonance.isOverloaded();

        panel.header(null, ArcanistHudText.resonanceStatus(resonance), GaugePanel.LABEL_GAP);

        int pipFill = overloaded ? PIP_OVERLOADED : PIP_FILLED;
        for (int i = 0; i < PlayerConstants.ARCANIST_RESONANCE_MAX_STACKS; i++) {
            panel.pip(i < stacks, pipFill);
        }

        String sameSchool = ArcanistHudText.sameSchoolStatus(resonance);
        if (sameSchool != null) {
            panel.line(null, sameSchool, MStyle.TEXT_PRIMARY, LINE_GAP);
        }

        panel.space(GaugePanel.LINE_GAP);
        var stats = player.getCharacterStats();
        // Spell status lines turn gold while Overloaded — the next cast is the empowered one
        int spellColor = overloaded ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
        if (stats.getSpentCp(ArcanistAbilityController.LEYLINE_BREACH_KEY) > 0) {
            panel.line(null, ArcanistHudText.spellStatus("Leyline Breach", arcanist.getLeylineBreach()),
                    spellColor, GaugePanel.LINE_GAP);
        }
        if (stats.getSpentCp(ArcanistAbilityController.NULL_SPIKE_KEY) > 0) {
            panel.line(null, ArcanistHudText.spellStatus("Null Spike", arcanist.getNullSpike()),
                    spellColor, 0f);
        }
    }
}
