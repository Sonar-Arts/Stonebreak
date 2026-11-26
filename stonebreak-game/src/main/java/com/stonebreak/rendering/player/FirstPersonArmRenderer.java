package com.stonebreak.rendering.player;

import com.stonebreak.core.Game;
import com.openmason.engine.entity.model.EntityAsset;
import com.stonebreak.player.Player;
import com.stonebreak.player.animation.PlayerAnimationSet;
import com.stonebreak.player.animation.PlayerBodyAnimator;
import com.stonebreak.player.animation.PlayerPoseInputs;
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
    private EntityAsset asset;
    /** The arm's own graph (idle + the punch layer), driven by the player's {@code attacking} like the body's. */
    private PlayerBodyAnimator arm;
    private final PlayerPoseInputs inputs = new PlayerPoseInputs();
    private boolean unavailable;

    boolean render(Player player, Matrix4f projection) {
        if (unavailable) return false;
        if (asset == null) {
            try {
                arm = new PlayerBodyAnimator(PlayerAnimationSet.firstPersonArm());
                asset = arm.set().asset();
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
        // The bob follows the body's gait phase: the pose the body shows (#457).
        PlayerBodyAnimator body = player.getBodyAnimator();
        float gaitPhase = body != null ? body.gaitPhase() : 0f;
        FirstPersonArmPose.worldTransform(view, gaitPhase, moving, worldTransform);
        FirstPersonArmPose.projection(projection, handProjection);
        inputs.standing().attacking = player.isAttacking();
        arm.update(Game.getDeltaTime(), inputs, PlayerBodyAnimator.Events.NONE);

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
                renderer.renderPosed(asset, EntityAsset.DEFAULT_VARIANT, arm.palette(), worldTransform, view,
                        handProjection, Game.getWorld(), player.getCamera().getPosition());
            } finally {
                glDepthRange(depthRange.get(0), depthRange.get(1));
                glDepthFunc(depthFunc);
                glDepthMask(depthMask);
                if (!depthTest) glDisable(GL_DEPTH_TEST);
            }
        }
        return true;
    }

    void cleanup() {
        renderer.cleanup();
        if (arm != null) {
            arm.close();
            arm = null;
        }
    }
}
