package com.stonebreak.ui.mainMenu;

import com.stonebreak.ui.runtime.providers.DirtBackdropProvider;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.types.Rect;

/**
 * The main menu's scene behind the title and buttons, as {@link MainMenuStage} says it is: the dirt
 * backdrop with its tint, the space scene, the shockwave revealing one under the other, and the
 * shockwave rings. Drawn by the legacy renderer and by the {@code stonebreak:menu-stage} document
 * provider (#299) alike, so the two cannot drift. Owns the space scene's cached bake.
 */
public final class MainMenuBackdrop implements AutoCloseable {

    /** The tint over the dirt. */
    static final int DIRT_TINT = 0x3C000000;
    /** Dark fill beyond the screen, so the impact shake never shows an edge in space. */
    static final int SPACE_OVERSCAN_COLOR = 0xFF050510;
    static final float OVERSCAN_MARGIN = 40f;

    private final SpaceBackgroundRenderer space = new SpaceBackgroundRenderer();

    /**
     * @param stage  the menu's stage, or null for the idle dirt
     * @param uiScale scales the shockwave ring widths
     * @param time   seconds for the space scene's drift
     */
    public void paint(Canvas canvas, int w, int h, MainMenuStage stage, float uiScale, float time) {
        MainMenuStage.BackgroundMode mode = stage != null ? stage.getBackgroundMode() : MainMenuStage.BackgroundMode.DIRT;
        switch (mode) {
            case DIRT -> dirt(canvas, w, h);
            case SPACE -> {
                fillOverscan(canvas, w, h);
                space.draw(canvas, w, h, time);
            }
            case REVEALING -> reveal(canvas, w, h, stage, time);
        }
        if (stage != null && stage.isShockwaveActive()) {
            shockwave(canvas, stage, uiScale);
        }
    }

    private void reveal(Canvas canvas, int w, int h, MainMenuStage stage, float time) {
        fillOverscan(canvas, w, h);
        space.draw(canvas, w, h, time);
        // Keep the dirt everywhere the shockwave has not yet reached.
        int save = canvas.save();
        try (PathBuilder pb = new PathBuilder()) {
            float cx = stage.getShockwaveCenterX();
            float cy = stage.getShockwaveCenterY();
            float r = stage.getShockwaveRadius();
            int steps = 64;
            pb.moveTo(cx + r, cy);
            for (int i = 1; i <= steps; i++) {
                double a = 2 * Math.PI * i / steps;
                pb.lineTo((float) (cx + r * Math.cos(a)), (float) (cy + r * Math.sin(a)));
            }
            pb.closePath();
            try (Path circle = pb.build()) {
                canvas.clipPath(circle, ClipMode.DIFFERENCE, true);
            }
        }
        dirt(canvas, w, h);
        canvas.restoreToCount(save);
    }

    private static void dirt(Canvas canvas, int w, int h) {
        DirtBackdropProvider.paint(canvas, 0, 0, w, h, DirtBackdropProvider.TILE_SCALE);
        try (Paint p = new Paint().setColor(DIRT_TINT)) {
            canvas.drawRect(Rect.makeXYWH(0, 0, w, h), p);
        }
    }

    private static void fillOverscan(Canvas canvas, int w, int h) {
        try (Paint p = new Paint().setColor(SPACE_OVERSCAN_COLOR)) {
            canvas.drawRect(Rect.makeXYWH(-OVERSCAN_MARGIN, -OVERSCAN_MARGIN,
                    w + OVERSCAN_MARGIN * 2f, h + OVERSCAN_MARGIN * 2f), p);
        }
    }

    private static void shockwave(Canvas canvas, MainMenuStage stage, float scale) {
        float cx = stage.getShockwaveCenterX();
        float cy = stage.getShockwaveCenterY();
        float r = stage.getShockwaveRadius();
        int alpha = Math.round(stage.getShockwaveAlpha() * 255f);
        if (alpha <= 0 || r <= 0f) {
            return;
        }
        try (Paint glow = new Paint().setAntiAlias(true).setMode(PaintMode.STROKE)
                .setStrokeWidth(10f * scale)
                .setColor((0x8FE9FF) | (Math.round(alpha * 0.4f) << 24))) {
            canvas.drawCircle(cx, cy, r, glow);
        }
        try (Paint ring = new Paint().setAntiAlias(true).setMode(PaintMode.STROKE)
                .setStrokeWidth(3f * scale)
                .setColor((0xFFFFFF) | (alpha << 24))) {
            canvas.drawCircle(cx, cy, r, ring);
        }
    }

    @Override
    public void close() {
        space.dispose();
    }
}
