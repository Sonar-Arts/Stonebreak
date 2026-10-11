package com.stonebreak.rendering.UI.components;

import static com.stonebreak.player.PlayerConstants.RANGER_MARKED_PREY_VISION_RANGE;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.player.Player;
import com.stonebreak.player.combat.QuarryController;
import com.stonebreak.player.combat.ranger.RangerAbilityController;
import com.stonebreak.player.combat.ranger.SnareAbility;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.openmason.engine.ui.masonry.MFonts;
import io.github.humbleui.skija.Canvas;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Screen-space projected markers for the Ranger: the Marked Prey indicator (a diamond,
 * name, and HP bar over the fully-studied Quarry — drawn in the HUD pass after the 3D
 * scene, so it is visible through terrain) and the Snare trap marker (orange while
 * arming, green once live). Projection is {@link WorldMarker#project}.
 */
public class QuarryMarkerRenderer {

    private static final QuarryMarkerRenderer INSTANCE = new QuarryMarkerRenderer();

    private static final float TRAP_HALF_SIZE     = 5f;
    private static final float PREY_HEAD_OFFSET   = 0.4f;

    private static final int TRAP_ARMING_FILL   = 0xC8E08A28; // ember orange
    private static final int TRAP_ARMED_FILL    = 0xDC58B858;

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
        List<WorldMarker> markers = new ArrayList<>();
        collect(new Matrix4f(proj).mul(view), screenW, screenH, markers);
        if (!markers.isEmpty()) {
            paint(markers, screenW, screenH);
        }
    }

    /**
     * The local Ranger's Marked Prey ({@link WorldMarker.Kind#PREY}) and live Snare
     * ({@link WorldMarker.Kind#TRAP}) within vision range, projected (#301).
     */
    public void collect(Matrix4f vp, int screenW, int screenH, List<WorldMarker> out) {
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
        if (showPrey) {
            LivingEntity prey = quarry.getQuarry();
            Vector3f pos = prey.getPosition();
            float[] screen = WorldMarker.project(vp, pos.x, pos.y + prey.getType().getHeight() + PREY_HEAD_OFFSET,
                pos.z, screenW, screenH);
            if (screen != null) {
                float hpFraction = prey.getMaxHealth() > 0f
                    ? Math.max(0f, Math.min(1f, prey.getHealth() / prey.getMaxHealth()))
                    : 0f;
                out.add(new WorldMarker(WorldMarker.id(WorldMarker.Kind.PREY, prey), WorldMarker.Kind.PREY, screen[0],
                    screen[1], prey.getType().getDisplayName(), WorldMarkerPaint.NAME_FONT_SIZE,
                    WorldMarkerPaint.NAME_COLOR, WorldMarkerPaint.NAME_SHADOW, hpFraction, 0f));
            }
        }
        if (showTrap) {
            Vector3f pos = snare.getTrapPosition();
            float[] screen = WorldMarker.project(vp, pos.x, pos.y + 0.2f, pos.z, screenW, screenH);
            if (screen != null) {
                int fill = snare.isTrapArmed() ? TRAP_ARMED_FILL : TRAP_ARMING_FILL;
                float half = snare.isTrapArmed() ? TRAP_HALF_SIZE : TRAP_HALF_SIZE * (0.5f + 0.5f * snare.getArmProgress());
                out.add(new WorldMarker(WorldMarker.id(WorldMarker.Kind.TRAP, snare), WorldMarker.Kind.TRAP, screen[0],
                    screen[1], "", 0f, fill, 0, 0f, half));
            }
        }
    }

    /** Draws collected {@link WorldMarker.Kind#PREY} and {@link WorldMarker.Kind#TRAP} markers in one UI frame. */
    public void paint(List<WorldMarker> markers, int screenW, int screenH) {
        if (backend == null || !backend.isAvailable()) return;
        backend.beginFrame(screenW, screenH, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();
            if (canvas == null) return;
            for (WorldMarker m : markers) {
                switch (m.kind()) {
                    case PREY -> WorldMarkerPaint.prey(canvas, fonts, m);
                    case TRAP -> WorldMarkerPaint.trap(canvas, m);
                    default -> { }
                }
            }
        } finally {
            backend.endFrame();
        }
    }
}
