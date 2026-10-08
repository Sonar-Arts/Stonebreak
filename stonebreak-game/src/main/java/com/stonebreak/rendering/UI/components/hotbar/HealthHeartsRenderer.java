package com.stonebreak.rendering.UI.components.hotbar;

import com.stonebreak.player.Player;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.textures.MTexture;
import com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry;
import com.stonebreak.ui.hotbar.core.HotbarLayoutCalculator;
import io.github.humbleui.skija.Canvas;

/**
 * Health hearts above the hotbar: lays out full/half/empty heart sprites in one or more
 * rows (compressing the step once they no longer fit), and exposes the top row's y so the
 * stamina and mana bars can stack above it.
 */
public final class HealthHeartsRenderer {

    /** Target heart edge length, in pixels. Snapped at draw time to the
     *  nearest integer multiple of the source texture's native size to avoid
     *  nearest-neighbour sampling asymmetry. */
    private static final int   HEART_SIZE_TARGET         = 28;
    private static final int   HEART_SPACING             = 2;
    private static final int   HEART_Y_GAP               = 38; // pixels above hotbar background
    private static final int   HEART_ROW_GAP             = 4;
    private static final float HEART_MIN_VISIBLE_FRACTION = 0.40f;

    private static final String HEART_EMPTY_SBT = "/ui/shared/stonebreak/ui/hud/heart_empty.sbt";
    private static final String HEART_HALF_SBT  = "/ui/shared/stonebreak/ui/hud/heart_half.sbt";
    private static final String HEART_FULL_SBT  = "/ui/shared/stonebreak/ui/hud/heart_full.sbt";

    private record HeartLayout(int heartsPerRow, int numRows, float step) {}

    public void draw(Canvas canvas, Player player, HotbarLayoutCalculator.HotbarLayout layout) {
        float health      = player.getHealth();
        float maxHealth   = player.getMaxHealth();
        int   totalHearts = (int) Math.ceil(maxHealth / 2.0f);
        float filled      = health / 2.0f;

        MTexture empty = MTextureRegistry.get(HEART_EMPTY_SBT);
        MTexture half  = MTextureRegistry.get(HEART_HALF_SBT);
        MTexture full  = MTextureRegistry.get(HEART_FULL_SBT);

        int heartSize = heartSize(empty, half, full);

        float[][] rects = heartRects(totalHearts, heartSize, layout);
        for (int i = 0; i < rects.length; i++) {
            float fill = Math.max(0f, Math.min(1f, filled - i));

            MTexture sprite = fill >= 0.75f ? full : fill >= 0.25f ? half : empty;
            if (sprite != null) {
                MPainter.drawImage(canvas, sprite.image(), rects[i][0], rects[i][1], heartSize, heartSize);
            }
        }
    }

    /**
     * Where each of {@code totalHearts} hearts sits ({x, y, size, size}, device px): rows of hearts
     * starting at the hotbar's left edge {@value #HEART_Y_GAP} px above it, the step compressed once a row
     * no longer fits, further rows stacked upwards. The UI document's code-behind lays them out the same
     * way (#300).
     */
    public static float[][] heartRects(int totalHearts, int heartSize, HotbarLayoutCalculator.HotbarLayout layout) {
        HeartLayout hl = computeHeartLayout(totalHearts, heartSize, layout.backgroundWidth);
        if (hl.heartsPerRow() == 0) return new float[0][];
        float[][] out = new float[totalHearts][];
        for (int i = 0; i < totalHearts; i++) {
            int   row  = i / hl.heartsPerRow();
            int   col  = i % hl.heartsPerRow();
            float x    = layout.backgroundX + col * hl.step();
            float y    = layout.backgroundY - HEART_Y_GAP - row * (heartSize + HEART_ROW_GAP);
            out[i] = new float[]{x, y, heartSize, heartSize};
        }
        return out;
    }

    /** Y of the topmost heart row for this player's max health — the anchor for bars stacked above. */
    public float topRowY(Player player, HotbarLayoutCalculator.HotbarLayout layout) {
        int totalHearts = (int) Math.ceil(player.getMaxHealth() / 2.0f);
        MTexture empty = MTextureRegistry.get(HEART_EMPTY_SBT);
        MTexture half  = MTextureRegistry.get(HEART_HALF_SBT);
        MTexture full  = MTextureRegistry.get(HEART_FULL_SBT);
        int heartSize  = heartSize(empty, half, full);
        return topRowY(totalHearts, heartSize, layout);
    }

    /**
     * Y of the topmost heart row the layout plans for {@code totalHearts} (its row count, which a
     * compressed layout may not fill): the anchor the stamina and mana bars stack above.
     */
    public static float topRowY(int totalHearts, int heartSize, HotbarLayoutCalculator.HotbarLayout layout) {
        HeartLayout hl   = computeHeartLayout(totalHearts, heartSize, layout.backgroundWidth);
        int         rows = Math.max(1, hl.numRows());
        return layout.backgroundY - HEART_Y_GAP - (rows - 1) * (heartSize + HEART_ROW_GAP);
    }

    /** The heart edge the HUD draws at, in device pixels (the UI document reads it too, #300). */
    public static int heartSize() {
        return heartSize(MTextureRegistry.get(HEART_EMPTY_SBT), MTextureRegistry.get(HEART_HALF_SBT),
                MTextureRegistry.get(HEART_FULL_SBT));
    }

    /** {@code fill} (0..1 of one heart) as the sprite the HUD shows: full, half or empty. */
    public static String heartState(float fill) {
        return fill >= 0.75f ? "full" : fill >= 0.25f ? "half" : "empty";
    }

    /** Native heart edge snapped up to the nearest integer multiple near the target size. */
    private static int heartSize(MTexture empty, MTexture half, MTexture full) {
        int nativeSize = nativeHeartSize(empty, half, full);
        return nativeSize * Math.max(1, Math.round((float) HEART_SIZE_TARGET / nativeSize));
    }

    private static HeartLayout computeHeartLayout(int totalHearts, int heartSize, int availableWidth) {
        if (totalHearts <= 0) return new HeartLayout(0, 0, 0f);

        float naturalStep   = heartSize + HEART_SPACING;
        int   naturalPerRow = Math.max(1, (int) Math.floor((availableWidth + HEART_SPACING) / naturalStep));

        if (totalHearts <= naturalPerRow) {
            return new HeartLayout(totalHearts, 1, naturalStep);
        }

        float minStep   = heartSize * HEART_MIN_VISIBLE_FRACTION;
        int   maxPerRow = availableWidth <= heartSize ? 1
                : (int) Math.floor((availableWidth - heartSize) / minStep) + 1;

        int numRows      = (int) Math.ceil((float) totalHearts / Math.max(1, maxPerRow));
        int heartsPerRow = (int) Math.ceil((float) totalHearts / numRows);
        float step       = heartsPerRow <= 1 ? 0f
                : (float) (availableWidth - heartSize) / (heartsPerRow - 1);

        return new HeartLayout(heartsPerRow, numRows, Math.min(naturalStep, step));
    }

    /** Largest native edge across the loaded heart variants, or the target
     *  size as a fallback when no SBT loaded. */
    private static int nativeHeartSize(MTexture... variants) {
        int max = 0;
        for (MTexture t : variants) {
            if (t == null) continue;
            int side = Math.max(t.width(), t.height());
            if (side > max) max = side;
        }
        return max > 0 ? max : HEART_SIZE_TARGET;
    }
}
