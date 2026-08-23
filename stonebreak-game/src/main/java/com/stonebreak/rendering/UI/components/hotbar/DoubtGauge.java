package com.stonebreak.rendering.UI.components.hotbar;

import com.stonebreak.player.Player;
import com.stonebreak.player.combat.illusionist.IllusionistAbilityController;
import com.stonebreak.player.combat.illusionist.IllusionistHudText;
import com.stonebreak.rendering.UI.masonryUI.MStyle;

/**
 * Illusionist Doubt gauge — header summarizing how many enemies carry Doubt (and how many
 * are Bewildered) plus one live status line per unlocked ability.
 */
public final class DoubtGauge implements ClassGauge {

    @Override
    public String classId() {
        return IllusionistAbilityController.CLASS_ID;
    }

    @Override
    public void draw(GaugePanel panel, Player player) {
        IllusionistAbilityController illusionist = player.getIllusionistAbilities();

        panel.header(null, IllusionistHudText.doubtStatus(illusionist.getDoubt()), GaugePanel.LABEL_GAP);

        panel.space(GaugePanel.LINE_GAP);
        var stats = player.getCharacterStats();
        if (stats.getSpentCp(IllusionistAbilityController.MIRRORED_DECEIT_KEY) > 0) {
            panel.line(null, IllusionistHudText.mirroredDeceitStatus(illusionist.getMirroredDeceit()),
                    MStyle.TEXT_PRIMARY, GaugePanel.LINE_GAP);
        }
        if (stats.getSpentCp(IllusionistAbilityController.FRACTURE_KEY) > 0) {
            panel.line(null, IllusionistHudText.fractureStatus(illusionist.getFracture()),
                    MStyle.TEXT_PRIMARY, 0f);
        }
    }
}
