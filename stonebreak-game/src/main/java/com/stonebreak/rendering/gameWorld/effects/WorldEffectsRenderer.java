package com.stonebreak.rendering.gameWorld.effects;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.models.entities.EntityRenderer;
import com.stonebreak.world.World;

import static org.lwjgl.opengl.GL11.*;

/**
 * The ability VFX that are not particles: fire bolt cores and the see-through outlines on revealed
 * enemies. Particle effects (fire trails, illusion smoke, water splashes and ripples) are FX drawn by
 * the particle pass since Stonebreak#441.
 *
 * <p>Runs after the transparent water pass so nothing gets blended underneath water.</p>
 */
public final class WorldEffectsRenderer {

    private final ShaderProgram shaderProgram;
    private final Matrix4f projectionMatrix;
    private final EntityRenderer entityRenderer;

    /** Scratch list for the revealed-entity sweep, reused so the effect pass allocates nothing. */
    private final List<LivingEntity> revealedScratch = new ArrayList<>();

    public WorldEffectsRenderer(ShaderProgram shaderProgram, Matrix4f projectionMatrix,
                                EntityRenderer entityRenderer) {
        this.shaderProgram = shaderProgram;
        this.projectionMatrix = projectionMatrix;
        this.entityRenderer = entityRenderer;
    }

    /** Draws every effect, in the order they must composite. Call after the water pass. */
    public void renderAll(Player player) {
        renderFireBoltCores(player);
        renderRevealedOutlines(player);
    }

    /**
     * Fire bolt core cubes, drawn through the entity renderer. Their flames (trail and impact burst)
     * are FX (Stonebreak#441), drawn by the particle pass.
     */
    private void renderFireBoltCores(Player player) {
        EntityManager entities = Game.getEntityManager();
        if (entities == null || entityRenderer == null) {
            return;
        }
        World world = Game.getWorld();
        org.joml.Vector3d cameraPos = player.getCamera().getPosition();
        for (Entity entity : entities.getAllEntities()) {
            if (entity.isAlive() && entity instanceof com.stonebreak.mobs.entities.FireBolt) {
                entityRenderer.renderEntity(entity, player.getViewMatrix(), projectionMatrix, world, cameraPos);
            }
        }
    }

    /**
     * A through-terrain wireframe box around every living entity carrying the REVEALED status
     * (Illusionist decoy hit). Depth testing is off so the outline shows through walls.
     */
    private void renderRevealedOutlines(Player player) {
        EntityManager entities = Game.getEntityManager();
        if (entities == null) {
            return;
        }
        revealedScratch.clear();
        for (LivingEntity entity : entities.getLivingEntities()) {
            if (entity.isAlive() && entity.hasStatusEffect(StatusEffectType.REVEALED)) {
                revealedScratch.add(entity);
            }
        }
        if (revealedScratch.isEmpty()) {
            return;
        }

        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        shaderProgram.setUniform("viewMatrix", player.getViewMatrix());
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_color", new Vector4f(0.85f, 0.30f, 0.95f, 0.9f));

        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);

        for (LivingEntity entity : revealedScratch) {
            // Render space in double: the box's centre relative to the origin, then the float extents.
            Vector3f position = RenderOrigin.toRender(entity.getPosition(), new Vector3f());
            EntityType type = entity.getType();
            float halfWidth = type.getWidth() * 0.5f;
            float halfLength = type.getLength() * 0.5f;
            drawWireBox(position.x - halfWidth, position.y - type.getLegHeight(), position.z - halfLength,
                    position.x + halfWidth, position.y + type.getHeight(), position.z + halfLength);
        }

        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.unbind();
        revealedScratch.clear();
    }

    /** The 12 edges of an axis-aligned RENDER-SPACE box in immediate mode; the caller sets shader and colour. */
    private static void drawWireBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        glBegin(GL_LINES);
        // Bottom rectangle
        glVertex3f(minX, minY, minZ); glVertex3f(maxX, minY, minZ);
        glVertex3f(maxX, minY, minZ); glVertex3f(maxX, minY, maxZ);
        glVertex3f(maxX, minY, maxZ); glVertex3f(minX, minY, maxZ);
        glVertex3f(minX, minY, maxZ); glVertex3f(minX, minY, minZ);
        // Top rectangle
        glVertex3f(minX, maxY, minZ); glVertex3f(maxX, maxY, minZ);
        glVertex3f(maxX, maxY, minZ); glVertex3f(maxX, maxY, maxZ);
        glVertex3f(maxX, maxY, maxZ); glVertex3f(minX, maxY, maxZ);
        glVertex3f(minX, maxY, maxZ); glVertex3f(minX, maxY, minZ);
        // Vertical edges
        glVertex3f(minX, minY, minZ); glVertex3f(minX, maxY, minZ);
        glVertex3f(maxX, minY, minZ); glVertex3f(maxX, maxY, minZ);
        glVertex3f(maxX, minY, maxZ); glVertex3f(maxX, maxY, maxZ);
        glVertex3f(minX, minY, maxZ); glVertex3f(minX, maxY, maxZ);
        glEnd();
    }
}
