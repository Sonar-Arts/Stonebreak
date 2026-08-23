package com.stonebreak.rendering.UI.components.hotbar;

import com.stonebreak.player.Player;
import com.stonebreak.player.PlayerConstants;
import com.stonebreak.player.combat.rogue.RogueAbilityController;
import com.stonebreak.rendering.UI.masonryUI.MStyle;

/**
 * Rogue Momentum gauge — header with the current stack count, three discrete Momentum pips,
 * and a readiness/cooldown line per unlocked ability.
 */
public final class MomentumGauge implements ClassGauge {

    private static final int PIP_FILLED = 0xDCC8B43C; // honed brass — charged Momentum

    @Override
    public String classId() {
        return RogueAbilityController.CLASS_ID;
    }

    @Override
    public void draw(GaugePanel panel, Player player) {
        RogueAbilityController rogue = player.getRogueAbilities();
        int stacks = rogue.getMomentum().getStacks();
        int maxStacks = PlayerConstants.MOMENTUM_MAX_STACKS;

        panel.header(null, "Momentum " + stacks + "/" + maxStacks, GaugePanel.LABEL_GAP);

        for (int i = 0; i < maxStacks; i++) {
            panel.pip(i < stacks, PIP_FILLED);
        }

        panel.space(GaugePanel.LINE_GAP);
        var stats = player.getCharacterStats();
        if (stats.getSpentCp(RogueAbilityController.SHADOW_STEP_KEY) > 0) {
            String state = rogue.getShadowStep().isOnCooldown()
                    ? String.format("%.0fs", rogue.getShadowStep().getCooldownRemaining())
                    : (rogue.getShadowStep().canActivate(player) ? "Ready" : "No target");
            int color = "Ready".equals(state) ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
            panel.line(null, "Shadow Step: " + state, color, GaugePanel.LINE_GAP);
        }
        if (stats.getSpentCp(RogueAbilityController.CALTROP_KEY) > 0) {
            String state = rogue.getCaltrops().isOnCooldown()
                    ? String.format("%.0fs", rogue.getCaltrops().getCooldownRemaining())
                    : "Ready";
            int color = "Ready".equals(state) ? MStyle.TEXT_ACCENT : MStyle.TEXT_PRIMARY;
            panel.line(null, "Caltrop Scatter: " + state, color, 0f);
        }
    }
}
