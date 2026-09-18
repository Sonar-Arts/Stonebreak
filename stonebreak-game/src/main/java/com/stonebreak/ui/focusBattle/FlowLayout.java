package com.stonebreak.ui.focusBattle;

/**
 * Layout math of the flow elements that {@link FocusBattleLayout} (shared, read-only) does not
 * cover: where floaters may live, the rects inside the party window that the spark effects decorate,
 * and the encounter name card. Pure functions of {@code (windowWidth, windowHeight, uiScale)}, like
 * the layout it builds on; {@code uiScale} is always the user's raw UI scale.
 */
public final class FlowLayout {

    private FlowLayout() {}

    // ─────────────────────────────────────────────── Party window details

    /**
     * Square of Qi pip {@code index} of {@code slots}, inside {@code party}. Mirrors the slot formula
     * of {@code PartyStatusWindow.paintQi} so the spend effect lands exactly on the pips it empties.
     */
    public static float[] qiPipRect(float[] party, int index, int slots, float scale) {
        float[] area = FocusBattleLayout.partyBarRect(party, FocusBattleLayout.PARTY_ROW_QI, scale);
        int n = Math.max(1, slots);
        float gap = 4f * scale;
        float size = Math.max(0f, Math.min(area[3], Math.min(18f * scale, (area[2] - (n - 1) * gap) / n)));
        return new float[]{area[0] + index * (size + gap), area[1] + (area[3] - size) / 2f, size, size};
    }

    /** The Focus gauge's bar inside {@code party} (same inset the party window paints it with). */
    public static float[] focusBarRect(float[] party, float scale) {
        float[] cell = FocusBattleLayout.partyBarRect(party, FocusBattleLayout.PARTY_ROW_FOCUS, scale);
        float h = Math.min(cell[3], 14f * scale);
        return new float[]{cell[0], (float) Math.floor(cell[1] + (cell[3] - h) / 2f), cell[2], (float) Math.floor(h)};
    }

    // ─────────────────────────────────────────────── Floaters

    /**
     * The band of the screen floaters may spawn in, {@code {left, top, right, bottom}}: below the
     * action banner (so a number never sits on the enemy plate) and above the party window. The help
     * strip and command window are inside the band on purpose: they come and go, the monk often
     * stands behind them, and a number pinned above them would detach from the body it belongs to.
     */
    public static float[] floaterBounds(int w, int h, float uiScale) {
        float s = FocusBattleLayout.effectiveScale(w, h, uiScale);
        float[] banner = FocusBattleLayout.actionBannerRect(w, h, uiScale);
        float[] party = FocusBattleLayout.partyWindowRect(w, h, uiScale);
        float margin = 40f * s;
        float top = banner[1] + banner[3] + 26f * s;
        float bottom = Math.max(top, party[1] - 18f * s);
        return new float[]{margin, top, Math.max(margin, w - margin), bottom};
    }

    /**
     * Vertical span {@code {top, bottom}} in which a world-anchored cursor is usable: clear of the
     * enemy plate and banner above, and of the help strip and everything under it below.
     */
    public static float[] targetBand(int w, int h, float uiScale) {
        float s = FocusBattleLayout.effectiveScale(w, h, uiScale);
        float[] banner = FocusBattleLayout.actionBannerRect(w, h, uiScale);
        float[] help = FocusBattleLayout.helpStripRect(w, h, uiScale);
        float top = banner[1] + banner[3] - 14f * s;
        return new float[]{top, Math.max(top, help[1] - 18f * s)};
    }

    /** Where Archon floaters go when the Archon is off-screen: under the banner, top-centre. */
    public static float[] enemyFallbackPoint(int w, int h, float uiScale) {
        float[] bounds = floaterBounds(w, h, uiScale);
        float s = FocusBattleLayout.effectiveScale(w, h, uiScale);
        return new float[]{w / 2f, Math.min(bounds[3], bounds[1] + 44f * s)};
    }

    /** Where monk floaters go when the monk is off-screen: just above the party window. */
    public static float[] partyFallbackPoint(int w, int h, float uiScale) {
        float[] party = FocusBattleLayout.partyWindowRect(w, h, uiScale);
        float[] bounds = floaterBounds(w, h, uiScale);
        return new float[]{party[0] + party[2] * 0.5f, bounds[3]};
    }

    /** Qi gains rise from the party window's left shoulder, clear of monk damage numbers. */
    public static float[] qiFloaterPoint(int w, int h, float uiScale) {
        float[] party = FocusBattleLayout.partyWindowRect(w, h, uiScale);
        float[] bounds = floaterBounds(w, h, uiScale);
        return new float[]{party[0] + party[2] * 0.18f, bounds[3]};
    }

    /** Rejections appear just above the help strip, over the command window's column. */
    public static float[] commandFloaterPoint(int w, int h, float uiScale) {
        float s = FocusBattleLayout.effectiveScale(w, h, uiScale);
        float[] cmd = FocusBattleLayout.commandWindowRect(w, h, uiScale);
        float[] help = FocusBattleLayout.helpStripRect(w, h, uiScale);
        float[] bounds = floaterBounds(w, h, uiScale);
        float x = Math.max(bounds[0] + cmd[2] * 0.5f, cmd[0] + cmd[2] * 0.5f);
        return new float[]{x, Math.max(bounds[1], help[1] - 18f * s)};
    }

    // ─────────────────────────────────────────────── E15 name card

    /**
     * The encounter name card: a wide band in the lower third, above the bottom letterbox bar, so
     * the Archon's hero shot stays clear above it.
     */
    public static float[] nameCardRect(int w, int h, float uiScale) {
        float s = FocusBattleLayout.effectiveScale(w, h, uiScale);
        float cardW = Math.min(760f * s, w * 0.9f);
        float cardH = 104f * s;
        float bottomBar = FocusBattleLayout.letterboxBottomRect(w, h, 1f)[3];
        float y = Math.max(0f, h - bottomBar - 28f * s - cardH);
        return new float[]{(w - cardW) / 2f, y, cardW, cardH};
    }

    /** Right-aligned anchor {@code {rightX, centreY}} of the "skip" hint, inside the bottom bar. */
    public static float[] skipHintAnchor(int w, int h, float uiScale) {
        float s = FocusBattleLayout.effectiveScale(w, h, uiScale);
        float bottomBar = FocusBattleLayout.letterboxBottomRect(w, h, 1f)[3];
        return new float[]{w - 24f * s, h - bottomBar / 2f};
    }
}
