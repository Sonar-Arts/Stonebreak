package com.stonebreak.rendering.UI.components;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MFonts;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Draws each remote (network) player's username as a billboard label floating above their head.
 * World position is projected to screen space; projection mirrors {@link EnemyAwarenessRenderer}.
 * Only meaningful in multiplayer, where {@link RemotePlayer} figures are present in the entity
 * manager.
 */
public class PlayerNameTagRenderer {

    private static final PlayerNameTagRenderer INSTANCE = new PlayerNameTagRenderer();

    private static final float NAME_FONT_SIZE = 18f;
    private static final float HEAD_OFFSET     = 0.55f; // blocks above the head
    private static final float MAX_DISTANCE    = 48f;   // don't clutter with far-away tags
    private static final float MAX_DISTANCE_SQ = MAX_DISTANCE * MAX_DISTANCE;

    private static final int NAME_COLOR   = 0xFFFFFFFF; // white
    private static final int NAME_SHADOW  = 0xCC101010;

    private SkijaUIBackend backend;
    private MFonts fonts;

    private PlayerNameTagRenderer() {}

    public static PlayerNameTagRenderer getInstance() {
        return INSTANCE;
    }

    public void setBackend(SkijaUIBackend backend) {
        this.backend = backend;
        this.fonts = new MFonts(backend);
    }

    public void render(Matrix4f proj, Matrix4f view, int screenW, int screenH) {
        if (backend == null || !backend.isAvailable() || fonts == null) return;
        if (!com.stonebreak.config.Settings.getInstance().getPlayerNameTagsEnabled()) return;

        EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return;

        Vector3f cameraPos = Game.getPlayer() != null ? Game.getPlayer().getPosition() : null;

        backend.beginFrame(screenW, screenH, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();
            if (canvas == null) return;

            Font font = fonts.get(NAME_FONT_SIZE);
            if (font == null) return;

            Matrix4f vp = new Matrix4f(proj).mul(view);
            for (LivingEntity entity : entityManager.getLivingEntities()) {
                if (!(entity instanceof RemotePlayer remote) || !remote.isAlive()) continue;

                String name = remote.getUsername();
                if (name == null || name.isEmpty()) continue;

                Vector3f pos = remote.getPosition();
                if (cameraPos != null && cameraPos.distanceSquared(pos) > MAX_DISTANCE_SQ) continue;

                float[] screen = project(vp, pos.x,
                        pos.y + remote.getType().getHeight() + HEAD_OFFSET, pos.z, screenW, screenH);
                if (screen == null) continue;

                MPainter.drawCenteredStringWithShadow(canvas, name, screen[0], screen[1],
                        font, NAME_COLOR, NAME_SHADOW);
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
