package com.stonebreak.rendering.UI.components.hotbar;

import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.player.Player;
import com.stonebreak.player.PlayerConstants;
import com.stonebreak.player.combat.QuarryController;
import com.stonebreak.player.combat.ranger.RangerAbilityController;
import com.stonebreak.player.combat.ranger.RangerHudText;
import com.stonebreak.rendering.UI.masonryUI.MStyle;

/**
 * Ranger Quarry gauge — header with the current Quarry and Study count, three discrete
 * Study pips (the topmost dims while the decay window has elapsed), the Quarry's HP bar,
 * the stack-1 armor/resistance reveal, and one live status line per unlocked ability.
 */
public final class QuarryGauge implements ClassGauge {

    private static final int PIP_FILLED = 0xDC58B858; // hunter green
    private static final int PIP_DIMMED = 0xDC4A6E3C; // decaying — dim moss
    private static final int HP_HEIGHT  = 5;
    private static final int HP_FILL    = 0xDCC83C32;
    private static final int LINE_GAP   = 8;

    private static final String QUARRY_ICON_PATH       = "/ui/abilities/ranger/Quarry.png";
    private static final String SNARE_ICON_PATH        = "/ui/abilities/ranger/Snare.png";
    private static final String CULLING_SHOT_ICON_PATH = "/ui/abilities/ranger/Culling_Shot.png";

    @Override
    public String classId() {
        return RangerAbilityController.CLASS_ID;
    }

    @Override
    public void draw(GaugePanel panel, Player player) {
        RangerAbilityController ranger = player.getRangerAbilities();
        QuarryController quarry = ranger.getQuarry();
        LivingEntity target = quarry.getQuarry();
        int stacks = quarry.getStudyStacks();
        boolean decaying = quarry.getDecayProgress() >= 1f;

        panel.header(QUARRY_ICON_PATH, RangerHudText.quarryStatus(quarry), GaugePanel.LABEL_GAP);

        for (int i = 0; i < PlayerConstants.RANGER_STUDY_MAX_STACKS; i++) {
            boolean topPip = i == stacks - 1;
            panel.pip(i < stacks, topPip && decaying ? PIP_DIMMED : PIP_FILLED);
        }

        if (target != null) {
            float hpFraction = target.getMaxHealth() > 0f
                    ? Math.max(0f, Math.min(1f, target.getHealth() / target.getMaxHealth()))
                    : 0f;
            panel.bar(HP_HEIGHT, hpFraction, HP_FILL, LINE_GAP);
        }

        if (target != null && stacks >= 1) {
            panel.line(null, RangerHudText.revealLine(target.getType()), MStyle.TEXT_PRIMARY, LINE_GAP);
        }
        if (target != null && stacks >= 2) {
            panel.line(null, "Weak point exposed", MStyle.TEXT_ACCENT, LINE_GAP);
        }

        panel.space(GaugePanel.LINE_GAP);
        var stats = player.getCharacterStats();
        if (stats.getSpentCp(RangerAbilityController.SNARE_KEY) > 0) {
            panel.line(SNARE_ICON_PATH, RangerHudText.snareStatus(ranger.getSnare()), MStyle.TEXT_PRIMARY, GaugePanel.LINE_GAP);
        }
        if (stats.getSpentCp(RangerAbilityController.CULLING_SHOT_KEY) > 0) {
            panel.line(CULLING_SHOT_ICON_PATH, RangerHudText.cullingShotStatus(ranger.getCullingShot()), MStyle.TEXT_PRIMARY, 0f);
        }
    }
}
