package com.stonebreak.ui.hotbar.core;

import com.openmason.engine.ui.masonry.textures.MTexture;
import com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry;

/**
 * Health hearts above the hotbar: the edge they are drawn at and which sprite (full, half, empty) each
 * shows. The HUD document lays them out and draws them ({@code stonebreak:hud}).
 */
public final class HealthHeartsLayout {

    /** Target heart edge length, in pixels. Snapped to the
     *  nearest integer multiple of the source texture's native size to avoid
     *  nearest-neighbour sampling asymmetry. */
    private static final int HEART_SIZE_TARGET = 28;

    private static final String HEART_EMPTY_SBT = "/ui/shared/stonebreak/ui/hud/heart_empty.sbt";
    private static final String HEART_HALF_SBT  = "/ui/shared/stonebreak/ui/hud/heart_half.sbt";
    private static final String HEART_FULL_SBT  = "/ui/shared/stonebreak/ui/hud/heart_full.sbt";

    private HealthHeartsLayout() {
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
