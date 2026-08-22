package com.stonebreak.rendering.UI.components.hotbar;

import com.stonebreak.player.Player;
import com.stonebreak.player.combat.RageController;
import com.stonebreak.player.combat.RageTier;
import com.stonebreak.player.combat.berserker.BerserkerAbilityController;
import com.stonebreak.player.combat.berserker.BerserkerTierText;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import static com.stonebreak.player.PlayerConstants.RAGE_T1_THRESHOLD;
import static com.stonebreak.player.PlayerConstants.RAGE_T2_THRESHOLD;
import static com.stonebreak.player.PlayerConstants.RAGE_T3_THRESHOLD;

/**
 * Berserker Rage gauge — three tier segments (T1/T2/T3), a "Rage: T&lt;N&gt;" label, and one
 * live tier-bonus line per unlocked ability.
 */
public final class RageGauge implements ClassGauge {

    private static final int SEGMENT_HEIGHT  = 10;
    private static final int SEGMENT_GAP     = 3;
    private static final int SEGMENT_FILLED  = 0xDCC83C32; // fierce red
    private static final int SEGMENT_PARTIAL = 0xDC9C5028; // dim ember

    private static final String RAGE_ICON_PATH          = "/ui/abilities/berserker/Rage.png";
    private static final String RAMPAGE_ICON_PATH       = "/ui/abilities/berserker/Rampage.png";
    private static final String SKULL_CRUSHER_ICON_PATH = "/ui/abilities/berserker/Skull_Crusher.png";

    @Override
    public String classId() {
        return BerserkerAbilityController.CLASS_ID;
    }

    @Override
    public void draw(GaugePanel panel, Player player) {
        RageController rage = player.getBerserkerAbilities().getRage();
        RageTier tier = rage.getTier();
        float currentRage = rage.getRage();
        float[] thresholds = { RAGE_T1_THRESHOLD, RAGE_T2_THRESHOLD, RAGE_T3_THRESHOLD };

        panel.header(RAGE_ICON_PATH, "Rage: T" + tier.ordinal(), GaugePanel.LABEL_GAP);

        for (int i = 0; i < thresholds.length; i++) {
            float segMin = i == 0 ? 0f : thresholds[i - 1];
            float segMax = thresholds[i];
            float fraction = Math.max(0f, Math.min(1f, (currentRage - segMin) / (segMax - segMin)));
            panel.bar(SEGMENT_HEIGHT, fraction, fraction >= 1f ? SEGMENT_FILLED : SEGMENT_PARTIAL, SEGMENT_GAP);
        }

        panel.space(GaugePanel.LINE_GAP);
        var stats = player.getCharacterStats();
        if (stats.getSpentCp(BerserkerAbilityController.RAMPAGE_KEY) > 0) {
            panel.line(RAMPAGE_ICON_PATH, BerserkerTierText.rampageBonus(tier), MStyle.TEXT_PRIMARY, GaugePanel.LINE_GAP);
        }
        if (stats.getSpentCp(BerserkerAbilityController.SKULL_CRUSHER_KEY) > 0) {
            panel.line(SKULL_CRUSHER_ICON_PATH, BerserkerTierText.skullCrusherBonus(tier), MStyle.TEXT_PRIMARY, 0f);
        }
    }
}
