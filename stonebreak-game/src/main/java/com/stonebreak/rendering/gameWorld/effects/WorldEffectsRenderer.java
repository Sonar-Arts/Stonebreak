package com.stonebreak.rendering.gameWorld.effects;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.FireBolt;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.effects.FireTrailParticles;
import com.stonebreak.rendering.effects.IllusionSmokeParticles;
import com.stonebreak.rendering.effects.WaterRippleParticles;
import com.stonebreak.rendering.effects.WaterSplashParticles;
import com.stonebreak.rendering.models.entities.EntityRenderer;
import com.stonebreak.world.World;

import static org.lwjgl.opengl.GL11.*;

/**
 * The ability and player VFX layer: fire bolts, illusion smoke, water splash and ripples, and the
 * see-through outlines on revealed enemies.
 *
 * <p>All of it runs after the transparent water pass so nothing gets blended underneath water, and
 * all of it is additive or alpha-blended point/line geometry rather than chunk meshes — which is why
 * it lives here and not in the world renderer's chunk passes.</p>
 */
public final class WorldEffectsRenderer {

    /** Emits one screen-facing point. Supplied to effect bodies by {@link #drawPointSprites}. */
    @FunctionalInterface
    public interface PointEmitter {
        void point(float x, float y, float z, float size, float r, float g, float b, float alpha);
    }

    private final ShaderProgram shaderProgram;
    private final Matrix4f projectionMatrix;
    private final EntityRenderer entityRenderer;

    /** Scratch list for the revealed-entity sweep, reused so the effect pass allocates nothing. */
    private final List<LivingEntity> revealedScratch = new ArrayList<>();

    /**
     * Scratch list of remote players included in the water effects, filled once per frame in
     * {@link #renderAll} and cleared again after the water passes so it never retains entities
     * (and through them a torn-down World) across a return to the menu.
     */
    private final List<RemotePlayer> remotePlayerScratch = new ArrayList<>();

    public WorldEffectsRenderer(ShaderProgram shaderProgram, Matrix4f projectionMatrix,
                                EntityRenderer entityRenderer) {
        this.shaderProgram = shaderProgram;
        this.projectionMatrix = projectionMatrix;
        this.entityRenderer = entityRenderer;
    }

    /** Draws every effect, in the order they must composite. Call after the water pass. */
    public void renderAll(Player player) {
        renderFireBoltCores(player);
        renderFireBoltParticles(player);
        renderIllusionSmoke(player);
        // One remote-player sweep serves both water effects: they run back to back on the
        // render thread and entity membership only changes in the update tick, so the two
        // can never observe different sets.
        collectRemotePlayersForWaterEffects();
        renderWaterSplash(player);
        renderWaterRipples(player);
        remotePlayerScratch.clear();
        renderRevealedOutlines(player);
    }

    /**
     * Fire bolt core cubes, drawn through the entity renderer so bolts that already impacted are
     * skipped — only their particles remain.
     */
    private void renderFireBoltCores(Player player) {
        EntityManager entities = Game.getEntityManager();
        if (entities == null || entityRenderer == null) {
            return;
        }
        World world = Game.getWorld();
        Vector3f cameraPos = player.getCamera().getPosition();
        for (Entity entity : entities.getAllEntities()) {
            if (entity.isAlive() && entity.getType() == EntityType.FIRE_BOLT) {
                entityRenderer.renderEntity(entity, player.getViewMatrix(), projectionMatrix, world, cameraPos);
            }
        }
    }

    /** Fire trail particles from every live bolt, additively blended for the glow. */
    private void renderFireBoltParticles(Player player) {
        EntityManager entities = Game.getEntityManager();
        if (entities == null) {
            return;
        }
        List<Entity> all = entities.getAllEntities();
        if (!hasLiveFireParticles(all)) {
            return;
        }

        drawPointSprites(player.getViewMatrix(), GL_ONE, emit -> {
            for (Entity entity : all) {
                if (!(entity instanceof FireBolt bolt) || !bolt.isAlive()) {
                    continue;
                }
                for (FireTrailParticles.FireParticle particle : bolt.particles.snapshot()) {
                    float opacity = particle.getOpacity();
                    Vector3f position = particle.getPosition();
                    // Lerp orange to red as the particle fades.
                    emit.point(position.x, position.y, position.z, particle.getSize(),
                            1.0f, 0.35f * opacity, 0.0f, opacity * 0.85f);
                }
            }
        });
    }

    private static boolean hasLiveFireParticles(List<Entity> entities) {
        for (Entity entity : entities) {
            if (entity instanceof FireBolt bolt && bolt.isAlive() && !bolt.particles.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The smoke puff emitted when Illusionist decoys appear or shatter. The particle list lives on
     * the active Mirrored Deceit ability, so it persists past the cast until it fades.
     */
    private void renderIllusionSmoke(Player player) {
        IllusionSmokeParticles smoke = player.getIllusionistAbilities().getMirroredDeceit().getSmoke();
        if (smoke.isEmpty()) {
            return;
        }
        drawPointSprites(player.getViewMatrix(), GL_ONE_MINUS_SRC_ALPHA, emit -> {
            for (IllusionSmokeParticles.SmokeParticle particle : smoke.snapshot()) {
                Vector3f position = particle.getPosition();
                // Pale violet smoke, fading out.
                emit.point(position.x, position.y, position.z, particle.getSize(),
                        0.72f, 0.66f, 0.85f, particle.getOpacity() * 0.55f);
            }
        });
    }

    /**
     * The droplet burst thrown up when a player enters water — the local player plus every
     * replicated remote player, whose splash is driven off the same water state sent over the wire.
     */
    private void renderWaterSplash(Player player) {
        WaterSplashParticles localSplash = player.getSplashParticles();
        boolean anyParticles = !localSplash.isEmpty();
        for (int i = 0; !anyParticles && i < remotePlayerScratch.size(); i++) {
            anyParticles = !remotePlayerScratch.get(i).getSplashParticles().isEmpty();
        }
        if (!anyParticles) {
            return;
        }

        drawPointSprites(player.getViewMatrix(), GL_ONE_MINUS_SRC_ALPHA, emit -> {
            emitSplash(localSplash, emit);
            for (RemotePlayer remote : remotePlayerScratch) {
                emitSplash(remote.getSplashParticles(), emit);
            }
        });
    }

    private static void emitSplash(WaterSplashParticles splash, PointEmitter emit) {
        for (WaterSplashParticles.SplashParticle particle : splash.snapshot()) {
            Vector3f position = particle.getPosition();
            // Pale blue-white droplets, fading out.
            emit.point(position.x, position.y, position.z, particle.getSize(),
                    0.8f, 0.9f, 1.0f, particle.getOpacity() * 0.9f);
        }
    }

    /**
     * Surface ripple rings trailing the player through water. Ring samples already swept over by
     * another ring's wavefront are omitted upstream (see {@link WaterRippleParticles}), so
     * overlapping ripples read as colliding rather than passing through each other.
     */
    private void renderWaterRipples(Player player) {
        // Cheap emptiness check before any snapshotting so the idle path allocates nothing.
        boolean anyRipples = !player.getRippleParticles().isEmpty();
        for (int i = 0; !anyRipples && i < remotePlayerScratch.size(); i++) {
            anyRipples = !remotePlayerScratch.get(i).getRippleParticles().isEmpty();
        }
        if (!anyRipples) {
            return;
        }

        // snapshotPoints() returns a fresh mutable copy, so the first snapshot doubles as
        // the accumulator for the remote players' points.
        List<WaterRippleParticles.RipplePoint> points = player.getRippleParticles().snapshotPoints();
        for (RemotePlayer remote : remotePlayerScratch) {
            points.addAll(remote.getRippleParticles().snapshotPoints());
        }
        if (points.isEmpty()) {
            return;
        }
        drawPointSprites(player.getViewMatrix(), GL_ONE_MINUS_SRC_ALPHA, emit -> {
            for (WaterRippleParticles.RipplePoint point : points) {
                // Lifted a hair off the surface so the ring is not z-fought by the water plane.
                emit.point(point.x(), point.y() + 0.02f, point.z(), RIPPLE_POINT_SIZE,
                        0.85f, 0.92f, 1.0f, point.opacity() * 0.55f);
            }
        });
    }

    private static final float RIPPLE_POINT_SIZE = 4.0f;

    /**
     * Refills {@link #remotePlayerScratch} with the live remote players whose world-space water
     * cosmetics this pass should draw. Called once per frame from {@link #renderAll} before the
     * water effects; the caller clears the list again after they run.
     */
    private void collectRemotePlayersForWaterEffects() {
        remotePlayerScratch.clear();
        EntityManager entities = Game.getEntityManager();
        if (entities == null) {
            return;
        }
        for (LivingEntity entity : entities.getLivingEntities()) {
            if (entity instanceof RemotePlayer remote && remote.isAlive()) {
                remotePlayerScratch.add(remote);
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
            Vector3f position = entity.getPosition();
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

    /**
     * Runs {@code body} with the shader bound, blending set up and depth writes off — the preamble
     * and teardown every particle effect here shares. {@code dstBlend} is the destination factor:
     * {@code GL_ONE} for additive glow, {@code GL_ONE_MINUS_SRC_ALPHA} for straight alpha.
     */
    private void drawPointSprites(Matrix4f viewMatrix, int dstBlend, Consumer<PointEmitter> body) {
        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        shaderProgram.setUniform("viewMatrix", viewMatrix);
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, dstBlend);
        glDepthMask(false);

        body.accept(this::emitPoint);

        glPointSize(1.0f);
        glDepthMask(true);
        glDisable(GL_BLEND);
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.unbind();
    }

    private void emitPoint(float x, float y, float z, float size, float r, float g, float b, float alpha) {
        shaderProgram.setUniform("u_color", new Vector4f(r, g, b, alpha));
        glPointSize(size);
        glBegin(GL_POINTS);
        glVertex3f(x, y, z);
        glEnd();
    }

    /** The 12 edges of an axis-aligned box in immediate mode; the caller sets shader and colour. */
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
