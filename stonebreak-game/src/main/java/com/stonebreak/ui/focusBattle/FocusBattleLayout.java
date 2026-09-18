package com.stonebreak.ui.focusBattle;

import com.stonebreak.battle.api.BattleMenu;
import com.stonebreak.rendering.UI.masonryUI.MMenuList;

import java.util.Collections;

/**
 * Pure layout math for the Focus battle HUD. Every rect is a function of
 * {@code (windowWidth, windowHeight, uiScale)} only and is shared by the painters (drawing) and
 * {@link FocusBattleScreen} (hit-testing), so the two can never disagree; nothing in a painter is
 * hardcoded.
 *
 * <p>Two mechanisms keep the HUD inside any window from 1024x600 to 3840x2160 at UI scales
 * 0.75–2.0:
 * <ol>
 *   <li>{@link #effectiveScale}: the user's UI scale is capped so the design-size HUD
 *       ({@value #REF_W}x{@value #REF_H} at scale 1) always fits. A 2.0 scale on a 1024x600 window
 *       would otherwise need 2000 px of width for the bottom row alone.</li>
 *   <li>Windows yield: a window that cannot have its design size shrinks, and the widgets inside
 *       compress to match (menu rows, party rows and the plate's lines never spill).</li>
 * </ol>
 *
 * <p>This class places <b>windows</b> only. What is inside a window (menu rows, gauge columns, the
 * plate's lines) belongs to the widget or window class that draws it; the menu-row helpers here are
 * thin delegates to {@link MMenuList}'s own slot formula, so drawing and hit-testing cannot disagree.
 *
 * <p>Window rects are snapped to whole pixels so borders and 1px bar outlines stay crisp. All rects
 * are {@code {x, y, w, h}} in screen pixels.
 */
public final class FocusBattleLayout {

    // The smallest window the unscaled design fits in; effectiveScale is derived from it.
    static final float REF_W = 1000f;
    static final float REF_H = 560f;
    // The window size the design metrics below are authored for; larger windows scale the HUD up.
    static final float DESIGN_W = 1280f;
    static final float DESIGN_H = 720f;

    // Base (unscaled) metrics.
    private static final float MARGIN        = 16f;
    private static final float COMMAND_W     = 250f;
    private static final float SUBMENU_W     = 236f;
    private static final float PARTY_W       = 430f;
    private static final float PARTY_H       = 140f;
    private static final float PARTY_MIN_GAP = 12f;   // submenu → party window
    private static final float HELP_H        = 28f;
    private static final float HELP_GAP      = 8f;
    private static final float COMBO_W       = 560f;
    private static final float COMBO_H       = 72f;
    private static final float COMBO_GAP     = 10f;
    private static final float ENEMY_W       = 460f;
    private static final float ENEMY_H       = 98f;
    private static final float BANNER_W      = 320f;
    private static final float BANNER_H      = 34f;
    private static final float BANNER_GAP    = 8f;
    private static final float TAG_W         = 96f;
    private static final float TAG_H         = 26f;
    private static final float RESULT_W      = 520f;
    private static final float RESULT_H      = 320f;
    private static final float LETTERBOX_FRACTION = 0.11f;

    private static final MMenuList.Row BLANK_ROW = new MMenuList.Row("");

    private FocusBattleLayout() {}

    // ─────────────────────────────────────────────── Scale

    /**
     * The scale the HUD is actually laid out (and its fonts sized) at: the UI scale, capped so the
     * whole HUD fits the window. Painters receive this value as their {@code uiScale}.
     */
    public static float effectiveScale(int windowWidth, int windowHeight, float uiScale) {
        float fit = Math.min(windowWidth / REF_W, windowHeight / REF_H);
        // A battle HUD is read at a glance mid-fight, so it grows with the window (design size is
        // authored for 1280x720: 1.5x at 1080p, 3x at 4K) on top of the user's UI scale.
        float resolution = Math.max(1f, Math.min(windowWidth / DESIGN_W, windowHeight / DESIGN_H));
        return Math.max(0.1f, Math.min(uiScale * resolution, fit));
    }

    public static int commandRowCount() { return BattleMenu.ROOT.size(); }

    public static int submenuRowCount() { return BattleMenu.QI_ARTS.size(); }

    /** Index of the root row that opens the Qi Arts submenu. */
    public static int qiArtsRowIndex() {
        for (int i = 0; i < BattleMenu.ROOT.size(); i++) {
            if (BattleMenu.ROOT.get(i).opensQiArts()) return i;
        }
        return 0;
    }

    // ─────────────────────────────────────────────── E1 command window

    /** E1: bottom-left, tall enough for every root row at the design height. */
    public static float[] commandWindowRect(int windowWidth, int windowHeight, float uiScale) {
        float s = effectiveScale(windowWidth, windowHeight, uiScale);
        float m = MARGIN * s;
        float w = Math.min(COMMAND_W * s, windowWidth - 2f * m);
        // Height yields before the window leaves the screen; the rows then compress to match.
        float h = Math.min(new MMenuList().scale(s).preferredHeight(commandRowCount()), windowHeight * 0.5f);
        return snap(m, windowHeight - m - h, w, h);
    }

    /** Slot of an E1 row: {@link MMenuList#rowRect}, the formula the list draws and hit-tests with. */
    public static float[] commandRowRect(int index, int windowWidth, int windowHeight, float uiScale) {
        return rowRect(commandWindowRect(windowWidth, windowHeight, uiScale), index, commandRowCount(),
                effectiveScale(windowWidth, windowHeight, uiScale));
    }

    // ─────────────────────────────────────────────── E2 Qi Arts submenu

    /** E2: attached to the right of E1 ({@link MMenuList#anchorRightOf}), level with the Qi Arts row. */
    public static float[] submenuRect(int windowWidth, int windowHeight, float uiScale) {
        float s = effectiveScale(windowWidth, windowHeight, uiScale);
        float m = MARGIN * s;
        float[] cmd = commandWindowRect(windowWidth, windowHeight, uiScale);
        float[] anchor = menuList(cmd, commandRowCount(), s).anchorRightOf(qiArtsRowIndex());
        float x = anchor[0];
        float w = Math.max(0f, Math.min(SUBMENU_W * s, windowWidth - m - x));
        float h = Math.min(new MMenuList().scale(s).preferredHeight(submenuRowCount()), cmd[3]);
        // Keep the window inside E1's vertical span so it can never reach the help strip or the
        // bottom margin, whatever row opens it.
        float y = Math.max(cmd[1], Math.min(anchor[1], cmd[1] + cmd[3] - h));
        return snap(x, y, w, h);
    }

    public static float[] submenuRowRect(int index, int windowWidth, int windowHeight, float uiScale) {
        return rowRect(submenuRect(windowWidth, windowHeight, uiScale), index, submenuRowCount(),
                effectiveScale(windowWidth, windowHeight, uiScale));
    }

    /** Row {@code index} of {@code count} inside a menu window: a thin delegate to {@link MMenuList#rowRect}. */
    public static float[] rowRect(float[] window, int index, int count, float scale) {
        return menuList(window, count, scale).rowRect(index);
    }

    // A geometry-only list: the HUD's real lists answer the same questions with the same maths.
    private static MMenuList menuList(float[] window, int rows, float scale) {
        return new MMenuList().rows(Collections.nCopies(Math.max(0, rows), BLANK_ROW)).scale(scale)
                .bounds(window[0], window[1], window[2], window[3]);
    }

    // ─────────────────────────────────────────────── E3 party status window

    /** E3: bottom-right. Its width yields to the command window and submenu on narrow windows. */
    public static float[] partyWindowRect(int windowWidth, int windowHeight, float uiScale) {
        float s = effectiveScale(windowWidth, windowHeight, uiScale);
        float m = MARGIN * s;
        float[] sub = submenuRect(windowWidth, windowHeight, uiScale);
        float leftLimit = sub[0] + sub[2] + PARTY_MIN_GAP * s;
        float w = Math.max(0f, Math.min(PARTY_W * s, windowWidth - m - leftLimit));
        float h = Math.min(PARTY_H * s, windowHeight * 0.4f);
        return snap(windowWidth - m - w, windowHeight - m - h, w, h);
    }

    // ─────────────────────────────────────────────── E4 help strip

    /** E4: one full-width line directly above the bottom windows. */
    public static float[] helpStripRect(int windowWidth, int windowHeight, float uiScale) {
        float s = effectiveScale(windowWidth, windowHeight, uiScale);
        float m = MARGIN * s;
        float top = Math.min(commandWindowRect(windowWidth, windowHeight, uiScale)[1],
                partyWindowRect(windowWidth, windowHeight, uiScale)[1]);
        float h = HELP_H * s;
        return snap(m, top - HELP_GAP * s - h, windowWidth - 2f * m, h);
    }

    // ─────────────────────────────────────────────── E5 enemy plate

    /** E5: top-centre. */
    public static float[] enemyPlateRect(int windowWidth, int windowHeight, float uiScale) {
        float s = effectiveScale(windowWidth, windowHeight, uiScale);
        float m = MARGIN * s;
        float[] tag = modeTagRect(windowWidth, windowHeight, uiScale);
        // Centred, but never wider than the space the mode tag leaves on either side.
        float maxW = windowWidth - 2f * (tag[0] + tag[2] + m);
        float w = Math.max(0f, Math.min(ENEMY_W * s, maxW));
        float h = Math.min(ENEMY_H * s, windowHeight * 0.3f);
        return snap((windowWidth - w) / 2f, m, w, h);
    }

    // ─────────────────────────────────────────────── E7 action banner

    /** E7: centred, directly below the enemy plate. */
    public static float[] actionBannerRect(int windowWidth, int windowHeight, float uiScale) {
        float s = effectiveScale(windowWidth, windowHeight, uiScale);
        float[] plate = enemyPlateRect(windowWidth, windowHeight, uiScale);
        float w = Math.min(BANNER_W * s, windowWidth - 2f * MARGIN * s);
        return snap((windowWidth - w) / 2f, plate[1] + plate[3] + BANNER_GAP * s, w, BANNER_H * s);
    }

    // ─────────────────────────────────────────────── E13 mode tag

    /** E13: top-left chip. */
    public static float[] modeTagRect(int windowWidth, int windowHeight, float uiScale) {
        float s = effectiveScale(windowWidth, windowHeight, uiScale);
        float m = MARGIN * s;
        return snap(m, m, TAG_W * s, TAG_H * s);
    }

    // ─────────────────────────────────────────────── Reserved for Wave 2

    /** E10 (reserved): lower-centre, above the help strip. */
    public static float[] comboStripRect(int windowWidth, int windowHeight, float uiScale) {
        float s = effectiveScale(windowWidth, windowHeight, uiScale);
        float[] help = helpStripRect(windowWidth, windowHeight, uiScale);
        float w = Math.min(COMBO_W * s, windowWidth - 2f * MARGIN * s);
        float h = COMBO_H * s;
        return snap((windowWidth - w) / 2f, help[1] - COMBO_GAP * s - h, w, h);
    }

    /** E16 (reserved): centred modal; deliberately allowed to cover the rest of the HUD. */
    public static float[] resultPanelRect(int windowWidth, int windowHeight, float uiScale) {
        float s = effectiveScale(windowWidth, windowHeight, uiScale);
        float w = Math.min(RESULT_W * s, windowWidth * 0.92f);
        float h = Math.min(RESULT_H * s, windowHeight * 0.92f);
        return snap((windowWidth - w) / 2f, (windowHeight - h) / 2f, w, h);
    }

    /** E14 (reserved): top letterbox bar; {@code amount} 0 = hidden, 1 = fully in. */
    public static float[] letterboxTopRect(int windowWidth, int windowHeight, float amount) {
        return new float[]{0f, 0f, windowWidth, letterboxHeight(windowHeight, amount)};
    }

    public static float[] letterboxBottomRect(int windowWidth, int windowHeight, float amount) {
        float h = letterboxHeight(windowHeight, amount);
        return new float[]{0f, windowHeight - h, windowWidth, h};
    }

    private static float letterboxHeight(int windowHeight, float amount) {
        return Math.round(windowHeight * LETTERBOX_FRACTION * Math.max(0f, Math.min(1f, amount)));
    }

    // ─────────────────────────────────────────────── Helpers

    /** Copy of {@code rect} moved by {@code (dx, dy)} — slide-ins and shakes. */
    public static float[] offset(float[] rect, float dx, float dy) {
        return new float[]{rect[0] + dx, rect[1] + dy, rect[2], rect[3]};
    }

    public static boolean contains(float px, float py, float[] rect) {
        return px >= rect[0] && px <= rect[0] + rect[2]
            && py >= rect[1] && py <= rect[1] + rect[3];
    }

    // Whole-pixel snap that never grows the rect past the edges it was computed from.
    private static float[] snap(float x, float y, float w, float h) {
        float sx = (float) Math.ceil(x - 0.001f);
        float sy = (float) Math.ceil(y - 0.001f);
        float right = (float) Math.floor(x + w + 0.001f);
        float bottom = (float) Math.floor(y + h + 0.001f);
        return new float[]{sx, sy, Math.max(0f, right - sx), Math.max(0f, bottom - sy)};
    }
}
