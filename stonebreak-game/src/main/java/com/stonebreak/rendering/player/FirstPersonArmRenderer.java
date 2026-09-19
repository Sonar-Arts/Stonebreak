package com.stonebreak.rendering.player;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.sbe.SbeEntityAsset;
import com.stonebreak.mobs.sbe.SbeEntityLoader;
import com.stonebreak.mobs.sbe.SbeEntityRegistry;
import com.stonebreak.mobs.sbe.PlayerStateMapping;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.models.entities.SbeEntityRenderer;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL11.*;

/** Owns the first-person SBE GPU resources; held blocks/items stay with HandItemRenderer. */
final class FirstPersonArmRenderer {
    private static final Logger LOG = LoggerFactory.getLogger(FirstPersonArmRenderer.class);
    private final SbeEntityRenderer renderer = new SbeEntityRenderer();
    private final Matrix4f worldTransform = new Matrix4f();
    private final Matrix4f handProjection = new Matrix4f();
    private SbeEntityAsset asset;
    private boolean unavailable;

    boolean render(Player player, Matrix4f projection) {
        if (unavailable) return false;
        if (asset == null) {
            try {
                asset = SbeEntityLoader.load(FirstPersonArmPose.RESOURCE);
                renderer.initialize();
            } catch (RuntimeException e) {
                unavailable = true;
                LOG.error("Could not load first-person arm; using legacy arm", e);
                return false;
            }
        }
        var view = player.getViewMatrix();
        var velocity = player.getVelocity();
        boolean moving = player.isOnGround() && velocity.x * velocity.x + velocity.z * velocity.z > 0.25f;
        float gaitDuration = PlayerStateMapping.gaitDuration(player.getBaseMovementState(),
                SbeEntityRegistry.get(EntityType.REMOTE_PLAYER.getSbeObjectId()));
        float gaitPhase = gaitDuration > 0f ? player.getBodyAnimationTime() / gaitDuration : 0f;
        FirstPersonArmPose.worldTransform(view, gaitPhase, moving, worldTransform);
        FirstPersonArmPose.projection(projection, handProjection);
        var anim = FirstPersonArmPose.animation(asset, Game.getInstance().getTotalTimeElapsed(), player.getAttackOverlay());

        // Keep the hand in front of nearby terrain while retaining its own depth ordering.
        // World-space placement lets the shared shader sample torch/day/night lighting correctly.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var depthRange = stack.mallocDouble(2);
            glGetDoublev(GL_DEPTH_RANGE, depthRange);
            int depthFunc = glGetInteger(GL_DEPTH_FUNC);
            boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
            boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
            try {
                glDepthRange(0.0, 0.05);
                glDepthMask(true);
                renderer.render(asset, "Default", anim, worldTransform, view, handProjection,
                        Game.getWorld(), player.getCamera().getPosition(), 0f, 0f);
            } finally {
                glDepthRange(depthRange.get(0), depthRange.get(1));
                glDepthFunc(depthFunc);
                glDepthMask(depthMask);
                if (!depthTest) glDisable(GL_DEPTH_TEST);
            }
        }
        return true;
    }

    void cleanup() { renderer.cleanup(); }
}
