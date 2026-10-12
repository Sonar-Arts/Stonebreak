package com.stonebreak.ui.worldHud;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.AwarenessController;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * The per-enemy stealth awareness indicator above each living entity's head: a yellow "?"
 * while SUSPICIOUS and a red "!" while ALERTED (nothing while UNAWARE). World position is projected
 * to screen space by {@link WorldMarker#project}.
 */
public class AwarenessMarkers {

    private static final AwarenessMarkers INSTANCE = new AwarenessMarkers();

    private static final float ICON_FONT_SIZE = 22f;
    private static final float HEAD_OFFSET    = 0.6f; // blocks above the head

    private static final int SUSPICIOUS_COLOR = 0xFFF2D24A; // amber
    private static final int ALERTED_COLOR    = 0xFFE0473C; // red
    private static final int ICON_SHADOW      = 0xCC101010;

    private AwarenessMarkers() {}

    public static AwarenessMarkers getInstance() {
        return INSTANCE;
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
}
