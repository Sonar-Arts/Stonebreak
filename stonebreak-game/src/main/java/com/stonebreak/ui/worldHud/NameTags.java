package com.stonebreak.ui.worldHud;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.RemotePlayer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Each remote (network) player's username as a billboard label floating above their head.
 * World position is projected to screen space by {@link WorldMarker#project}. Only meaningful in
 * multiplayer, where {@link RemotePlayer} figures are present in the entity manager.
 */
public class NameTags {

    private static final NameTags INSTANCE = new NameTags();

    private static final float NAME_FONT_SIZE = 18f;
    private static final float HEAD_OFFSET     = 0.55f; // blocks above the head
    private static final float MAX_DISTANCE    = 48f;   // don't clutter with far-away tags
    private static final float MAX_DISTANCE_SQ = MAX_DISTANCE * MAX_DISTANCE;

    private static final int NAME_COLOR   = 0xFFFFFFFF; // white
    private static final int NAME_SHADOW  = 0xCC101010;

    private NameTags() {}

    public static NameTags getInstance() {
        return INSTANCE;
    }

    /**
     * Remote players' names within 48 blocks of the local player, when name tags are enabled
     * ({@link WorldMarker.Kind#NAMETAG}, #301).
     */
    public void collect(Matrix4f vp, int screenW, int screenH, List<WorldMarker> out) {
        if (!com.stonebreak.config.Settings.getInstance().getPlayerNameTagsEnabled()) return;

        EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return;

        Vector3f cameraPos = Game.getPlayer() != null ? Game.getPlayer().getPosition() : null;
        for (LivingEntity entity : entityManager.getLivingEntities()) {
            if (!(entity instanceof RemotePlayer remote) || !remote.isAlive()) continue;

            String name = remote.getUsername();
            if (name == null || name.isEmpty()) continue;

            Vector3f pos = remote.getPosition();
            if (cameraPos != null && cameraPos.distanceSquared(pos) > MAX_DISTANCE_SQ) continue;

            float[] screen = WorldMarker.project(vp, pos.x,
                    pos.y + remote.getType().getHeight() + HEAD_OFFSET, pos.z, screenW, screenH);
            if (screen == null) continue;

            out.add(WorldMarker.text(WorldMarker.id(WorldMarker.Kind.NAMETAG, remote), WorldMarker.Kind.NAMETAG,
                screen[0], screen[1], name, NAME_FONT_SIZE, NAME_COLOR, NAME_SHADOW));
        }
    }
}
