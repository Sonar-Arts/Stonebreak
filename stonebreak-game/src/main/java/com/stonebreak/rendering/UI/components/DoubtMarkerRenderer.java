package com.stonebreak.rendering.UI.components;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.player.Player;
import com.stonebreak.player.combat.DoubtController;
import com.stonebreak.player.combat.illusionist.IllusionistAbilityController;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MFonts;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import static com.stonebreak.player.PlayerConstants.ILLUSIONIST_DOUBT_MAX_STACKS;

/**
 * Screen-space projected Doubt markers for the Illusionist: a small stack count drawn over the head
 * of every enemy carrying Doubt, recolored to a warning hue once the enemy is Bewildered (full
 * stacks). Drawn in the HUD pass after the 3D scene, so it reads through terrain. Projection mirrors
 * {@link QuarryMarkerRenderer}.
 */
public class DoubtMarkerRenderer {

    private static final DoubtMarkerRenderer INSTANCE = new DoubtMarkerRenderer();

    private static final float HEAD_OFFSET   = 0.5f;
    private static final float LABEL_FONT     = 14f;

    private static final int DOUBT_COLOR      = 0xFFC9B8F0; // soft violet
    private static final int BEWILDERED_COLOR = 0xFFE85C5C; // alarmed red
    private static final int LABEL_SHADOW     = 0x99151515;

    private SkijaUIBackend backend;
    private MFonts fonts;

    private DoubtMarkerRenderer() {}

    public static DoubtMarkerRenderer getInstance() {
        return INSTANCE;
    }

    public void setBackend(SkijaUIBackend backend) {
        this.backend = backend;
        this.fonts = new MFonts(backend);
    }

    public void render(Matrix4f proj, Matrix4f view, int screenW, int screenH) {
        if (backend == null || !backend.isAvailable()) return;

        Player player = Game.getPlayer();
        if (player == null) return;
        if (!IllusionistAbilityController.CLASS_ID.equals(player.getCharacterStats().getSelectedClassId())) return;

        DoubtController doubt = player.getIllusionistAbilities().getDoubt();
        var doubted = doubt.getAllDoubted();
        if (doubted.isEmpty()) return;

        backend.beginFrame(screenW, screenH, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();
            if (canvas == null) return;
            Font font = fonts != null ? fonts.get(LABEL_FONT) : null;
            if (font == null) return;

            Matrix4f vp = new Matrix4f(proj).mul(view);
            for (LivingEntity target : doubted) {
                int stacks = doubt.getStacks(target);
                if (stacks <= 0) continue;
                Vector3f pos = target.getPosition();
                float[] screen = project(vp, pos.x, pos.y + target.getType().getHeight() + HEAD_OFFSET, pos.z,
                    screenW, screenH);
                if (screen == null) continue;

                int color = stacks >= ILLUSIONIST_DOUBT_MAX_STACKS ? BEWILDERED_COLOR : DOUBT_COLOR;
                String label = "Doubt " + stacks + "/" + ILLUSIONIST_DOUBT_MAX_STACKS;
                MPainter.drawCenteredStringWithShadow(canvas, label, screen[0], screen[1], font, color, LABEL_SHADOW);
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
