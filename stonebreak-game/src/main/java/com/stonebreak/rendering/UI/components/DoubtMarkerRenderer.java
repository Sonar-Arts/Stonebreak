package com.stonebreak.rendering.UI.components;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.player.Player;
import com.stonebreak.player.combat.DoubtController;
import com.stonebreak.player.combat.illusionist.IllusionistAbilityController;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.openmason.engine.ui.masonry.MFonts;
import io.github.humbleui.skija.Canvas;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

import static com.stonebreak.player.PlayerConstants.ILLUSIONIST_DOUBT_MAX_STACKS;

/**
 * Screen-space projected Doubt markers for the Illusionist: a small stack count drawn over the head
 * of every enemy carrying Doubt, recolored to a warning hue once the enemy is Bewildered (full
 * stacks). Drawn in the HUD pass after the 3D scene, so it reads through terrain. Projection is
 * {@link WorldMarker#project}.
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
        List<WorldMarker> markers = new ArrayList<>();
        collect(new Matrix4f(proj).mul(view), screenW, screenH, markers);
        if (!markers.isEmpty()) {
            paint(markers, screenW, screenH);
        }
    }

    /** "Doubt n/max" over every doubted enemy of the local Illusionist ({@link WorldMarker.Kind#DOUBT}, #301). */
    public void collect(Matrix4f vp, int screenW, int screenH, List<WorldMarker> out) {
        Player player = Game.getPlayer();
        if (player == null) return;
        if (!IllusionistAbilityController.CLASS_ID.equals(player.getCharacterStats().getSelectedClassId())) return;

        DoubtController doubt = player.getIllusionistAbilities().getDoubt();
        for (LivingEntity target : doubt.getAllDoubted()) {
            int stacks = doubt.getStacks(target);
            if (stacks <= 0) continue;
            Vector3f pos = target.getPosition();
            float[] screen = WorldMarker.project(vp, pos.x, pos.y + target.getType().getHeight() + HEAD_OFFSET, pos.z,
                screenW, screenH);
            if (screen == null) continue;

            int color = stacks >= ILLUSIONIST_DOUBT_MAX_STACKS ? BEWILDERED_COLOR : DOUBT_COLOR;
            String label = "Doubt " + stacks + "/" + ILLUSIONIST_DOUBT_MAX_STACKS;
            out.add(WorldMarker.text(WorldMarker.id(WorldMarker.Kind.DOUBT, target), WorldMarker.Kind.DOUBT, screen[0],
                screen[1], label, LABEL_FONT, color, LABEL_SHADOW));
        }
    }

    /** Draws collected {@link WorldMarker.Kind#DOUBT} markers in one UI frame. */
    public void paint(List<WorldMarker> markers, int screenW, int screenH) {
        if (backend == null || !backend.isAvailable()) return;
        backend.beginFrame(screenW, screenH, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();
            if (canvas == null) return;
            for (WorldMarker m : markers) {
                if (m.kind() == WorldMarker.Kind.DOUBT) WorldMarkerPaint.text(canvas, fonts, m);
            }
        } finally {
            backend.endFrame();
        }
    }
}
