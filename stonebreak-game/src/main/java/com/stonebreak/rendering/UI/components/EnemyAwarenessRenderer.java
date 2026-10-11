package com.stonebreak.rendering.UI.components;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.AwarenessController;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.openmason.engine.ui.masonry.MFonts;
import io.github.humbleui.skija.Canvas;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the per-enemy stealth awareness indicator above each living entity's head: a yellow "?"
 * while SUSPICIOUS and a red "!" while ALERTED (nothing while UNAWARE). World position is projected
 * to screen space by {@link WorldMarker#project}.
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
        List<WorldMarker> markers = new ArrayList<>();
        collect(new Matrix4f(proj).mul(view), screenW, screenH, markers);
        if (!markers.isEmpty()) { // nothing to show: no UI frame (it used to flush one every frame)
            paint(markers, screenW, screenH);
        }
    }

    /**
     * "?" (SUSPICIOUS) and "!" (ALERTED) over the client entity manager's living entities
     * ({@link WorldMarker.Kind#AWARENESS}, #301). Network shadows never tick awareness, so server mobs
     * show nothing here (the two-world trap, recorded in the migration ledger).
     */
    public void collect(Matrix4f vp, int screenW, int screenH, List<WorldMarker> out) {
        EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return;
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
            float[] screen = WorldMarker.project(vp, pos.x,
                    pos.y + entity.getType().getHeight() + HEAD_OFFSET, pos.z, screenW, screenH);
            if (screen == null) continue;

            out.add(WorldMarker.text(WorldMarker.id(WorldMarker.Kind.AWARENESS, entity), WorldMarker.Kind.AWARENESS,
                screen[0], screen[1], glyph, ICON_FONT_SIZE, color, ICON_SHADOW));
        }
    }

    /** Draws collected {@link WorldMarker.Kind#AWARENESS} markers in one UI frame. */
    public void paint(List<WorldMarker> markers, int screenW, int screenH) {
        if (backend == null || !backend.isAvailable()) return;
        backend.beginFrame(screenW, screenH, 1.0f);
        try {
            Canvas canvas = backend.getCanvas();
            if (canvas == null) return;
            for (WorldMarker m : markers) {
                if (m.kind() == WorldMarker.Kind.AWARENESS) WorldMarkerPaint.text(canvas, fonts, m);
            }
        } finally {
            backend.endFrame();
        }
    }
}
