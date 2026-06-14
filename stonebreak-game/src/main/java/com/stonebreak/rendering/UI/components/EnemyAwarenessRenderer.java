package com.stonebreak.rendering.UI.components;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.AwarenessController;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MFonts;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Draws the per-enemy stealth awareness indicator above each living entity's head: a yellow "?"
 * while SUSPICIOUS and a red "!" while ALERTED (nothing while UNAWARE). World position is projected
 * to screen space; projection mirrors {@link QuarryMarkerRenderer}.
 */
public class EnemyAwarenessRenderer {

    private static final EnemyAwarenessRenderer INSTANCE = new EnemyAwarenessRenderer();

    private static final float ICON_FONT_SIZE = 22f;
    private static final float HEAD_OFFSET    = 0.6f; // blocks above the head

    private static final int SUSPICIOUS_COLOR = 0xFFF2D24A; // amber
    private static final int ALERTED_COLOR    = 0xFFE0473C; // red
    private static final int ICON_SHADOW      = 0xCC101010;

    private SkijaUIBackend backend;
    private MFonts fonts;

    private EnemyAwarenessRenderer() {}

    public static EnemyAwarenessRenderer getInstance() {
        return INSTANCE;
    }

    public void setBackend(SkijaUIBackend backend) {
        this.backend = backend;
        this.fonts = new MFonts(backend);
    }

    public void render(Matrix4f proj, Matrix4f view, int screenW, int screenH) {
        if (backend == null || !backend.isAvailable() || fonts == null) return;

        EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return;

        backend.beginFrame(screenW, screenH, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();
            if (canvas == null) return;

            Font font = fonts.get(ICON_FONT_SIZE);
            if (font == null) return;

            Matrix4f vp = new Matrix4f(proj).mul(view);
            for (LivingEntity entity : entityManager.getLivingEntities()) {
                if (!entity.isAlive()) continue;
                AwarenessController awareness = entity.getAwareness();
                if (awareness == null) continue;

                String glyph;
                int color;
                switch (awareness.getState()) {
                    case SUSPICIOUS -> { glyph = "?"; color = SUSPICIOUS_COLOR; }
                    case ALERTED    -> { glyph = "!"; color = ALERTED_COLOR; }
                    default -> { continue; }
                }

                Vector3f pos = entity.getPosition();
                float[] screen = project(vp, pos.x,
                        pos.y + entity.getType().getHeight() + HEAD_OFFSET, pos.z, screenW, screenH);
                if (screen == null) continue;

                MPainter.drawCenteredStringWithShadow(canvas, glyph, screen[0], screen[1],
                        font, color, ICON_SHADOW);
            }
        } finally {
            backend.endFrame();
        }
    }

    /** World position to screen coordinates, or null when behind the camera / off-screen. */
    private float[] project(Matrix4f vp, float wx, float wy, float wz, int screenW, int screenH) {
        Vector4f clip = new Vector4f(wx, wy, wz, 1f);
        vp.transform(clip);
        if (clip.w <= 0f) return null;
        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        if (Math.abs(ndcX) > 1f || Math.abs(ndcY) > 1f) return null;
        return new float[] {
            (ndcX + 1f) * 0.5f * screenW,
            (1f - ndcY) * 0.5f * screenH
        };
    }
}
