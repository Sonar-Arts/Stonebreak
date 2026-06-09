package com.stonebreak.rendering.UI.components;

import static com.stonebreak.player.PlayerConstants.RANGER_MARKED_PREY_VISION_RANGE;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.player.Player;
import com.stonebreak.player.combat.QuarryController;
import com.stonebreak.player.combat.ranger.RangerAbilityController;
import com.stonebreak.player.combat.ranger.SnareAbility;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.MFonts;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Screen-space projected markers for the Ranger: the Marked Prey indicator (a diamond,
 * name, and HP bar over the fully-studied Quarry — drawn in the HUD pass after the 3D
 * scene, so it is visible through terrain) and the Snare trap marker (orange while
 * arming, green once live). Projection mirrors {@link DamageNumberRenderer}.
 */
public class QuarryMarkerRenderer {

    private static final QuarryMarkerRenderer INSTANCE = new QuarryMarkerRenderer();

    private static final float MARKER_HALF_SIZE   = 7f;
    private static final float TRAP_HALF_SIZE     = 5f;
    private static final float NAME_FONT_SIZE     = 14f;
    private static final float NAME_GAP           = 6f;
    private static final float HP_BAR_WIDTH       = 56f;
    private static final float HP_BAR_HEIGHT      = 5f;
    private static final float HP_BAR_GAP         = 4f;
    private static final float PREY_HEAD_OFFSET   = 0.4f;

    private static final int PREY_MARKER_FILL   = 0xDC58B858; // hunter green
    private static final int PREY_MARKER_BORDER = 0xFF1A3D1A;
    private static final int NAME_COLOR         = 0xFFFFFFFF;
    private static final int NAME_SHADOW        = 0x991A1A1A;
    private static final int HP_BAR_BG          = 0xC83C3C3C;
    private static final int HP_BAR_FILL        = 0xDCC83C32;
    private static final int HP_BAR_BORDER      = 0xFF000000;
    private static final int TRAP_ARMING_FILL   = 0xC8E08A28; // ember orange
    private static final int TRAP_ARMED_FILL    = 0xDC58B858;
    private static final int TRAP_BORDER        = 0xFF000000;

    private SkijaUIBackend backend;
    private MFonts fonts;

    private QuarryMarkerRenderer() {}

    public static QuarryMarkerRenderer getInstance() {
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
        if (!RangerAbilityController.CLASS_ID.equals(player.getCharacterStats().getSelectedClassId())) return;

        QuarryController quarry = player.getRangerAbilities().getQuarry();
        SnareAbility snare = player.getRangerAbilities().getSnare();
        boolean showPrey = quarry.isMarkedPrey()
            && quarry.getQuarry().isAlive()
            && quarry.getQuarry().getPosition().distance(player.getPosition()) <= RANGER_MARKED_PREY_VISION_RANGE;
        boolean showTrap = snare.hasActiveTrap()
            && snare.getTrapPosition().distance(player.getPosition()) <= RANGER_MARKED_PREY_VISION_RANGE;
        if (!showPrey && !showTrap) return;

        backend.beginFrame(screenW, screenH, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();
            if (canvas == null) return;

            Matrix4f vp = new Matrix4f(proj).mul(view);
            if (showPrey) {
                drawPreyMarker(canvas, vp, quarry.getQuarry(), screenW, screenH);
            }
            if (showTrap) {
                drawTrapMarker(canvas, vp, snare, screenW, screenH);
            }
        } finally {
            backend.endFrame();
        }
    }

    private void drawPreyMarker(Canvas canvas, Matrix4f vp, LivingEntity prey, int screenW, int screenH) {
        Vector3f pos = prey.getPosition();
        float[] screen = project(vp, pos.x, pos.y + prey.getType().getHeight() + PREY_HEAD_OFFSET, pos.z,
            screenW, screenH);
        if (screen == null) return;
        float sx = screen[0];
        float sy = screen[1];

        drawDiamond(canvas, sx, sy, MARKER_HALF_SIZE, PREY_MARKER_FILL, PREY_MARKER_BORDER);

        Font font = fonts != null ? fonts.get(NAME_FONT_SIZE) : null;
        float nameBaseline = sy - MARKER_HALF_SIZE - NAME_GAP;
        if (font != null) {
            MPainter.drawCenteredStringWithShadow(canvas, prey.getType().getDisplayName(),
                sx, nameBaseline, font, NAME_COLOR, NAME_SHADOW);
        }

        float hpFraction = prey.getMaxHealth() > 0f
            ? Math.max(0f, Math.min(1f, prey.getHealth() / prey.getMaxHealth()))
            : 0f;
        float barX = sx - HP_BAR_WIDTH / 2f;
        float barY = sy + MARKER_HALF_SIZE + HP_BAR_GAP;
        MPainter.fillRect(canvas, barX, barY, HP_BAR_WIDTH, HP_BAR_HEIGHT, HP_BAR_BG);
        if (hpFraction > 0f) {
            MPainter.fillRect(canvas, barX, barY, HP_BAR_WIDTH * hpFraction, HP_BAR_HEIGHT, HP_BAR_FILL);
        }
        MPainter.strokeRect(canvas, barX, barY, HP_BAR_WIDTH, HP_BAR_HEIGHT, HP_BAR_BORDER, 1f);
    }

    private void drawTrapMarker(Canvas canvas, Matrix4f vp, SnareAbility snare, int screenW, int screenH) {
        Vector3f pos = snare.getTrapPosition();
        float[] screen = project(vp, pos.x, pos.y + 0.2f, pos.z, screenW, screenH);
        if (screen == null) return;

        int fill = snare.isTrapArmed() ? TRAP_ARMED_FILL : TRAP_ARMING_FILL;
        float half = snare.isTrapArmed() ? TRAP_HALF_SIZE : TRAP_HALF_SIZE * (0.5f + 0.5f * snare.getArmProgress());
        MPainter.fillRect(canvas, screen[0] - half, screen[1] - half, half * 2f, half * 2f, fill);
        MPainter.strokeRect(canvas, screen[0] - half, screen[1] - half, half * 2f, half * 2f, TRAP_BORDER, 1f);
    }

    /** A filled square rotated 45° — drawn with plain rects (no Skija Path needed). */
    private void drawDiamond(Canvas canvas, float cx, float cy, float half, int fill, int border) {
        canvas.save();
        try {
            canvas.translate(cx, cy);
            canvas.rotate(45f);
            MPainter.fillRect(canvas, -half, -half, half * 2f, half * 2f, fill);
            MPainter.strokeRect(canvas, -half, -half, half * 2f, half * 2f, border, 1.5f);
        } finally {
            canvas.restore();
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
