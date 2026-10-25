package com.stonebreak.mobs.entities;

import com.openmason.engine.entity.sbe.SbeModelGeometry;
import com.stonebreak.mobs.sbe.SbeEntityRegistry;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every creature's hitbox is its rendered model: as long, wide and tall as what the player
 * sees, turned the way the creature faces.
 *
 * <p>The boxes used to be hand-typed physics bodies that never turned: a goose's neck and head,
 * a cow's head and the front half of every animal stood outside them, so arrows passed through.
 * The model is placed here with JOML's own {@code rotateY} — as the renderer places it — rather
 * than with {@link ModelBounds#placed}'s arithmetic, so the two are checked against each other.
 */
class HitboxMatchesModelTest {

    /** How far the hitbox may stray from the model, in blocks. */
    private static final float TOLERANCE = 0.01f;
    private static final float[] CARDINAL_YAWS = {0f, 90f, 180f, 270f};

    @BeforeAll
    static void loadModels() {
        SbeEntityRegistry.scanAndLoad();
    }

    /** Every glossary creature as the game spawns it — real scale, leg height and variant — plus a player. */
    private static List<LivingEntity> creatures() {
        World world = mock(World.class); // getBlockAt → null: open air
        Chunk chunk = mock(Chunk.class);
        when(chunk.areFeaturesPopulated()).thenReturn(true);
        when(world.getChunkIfLoaded(anyInt(), anyInt())).thenReturn(chunk);
        EntityManager em = new EntityManager(world);
        List<LivingEntity> creatures = new ArrayList<>();
        for (EntityType type : EntityType.GLOSSARY_TYPES) {
            creatures.add((LivingEntity) em.spawnEntity(type, new Vector3f(10.5f, 64f, -3.25f)));
        }
        creatures.add(new RemotePlayer(world, new Vector3f(10.5f, 64f, -3.25f), 1, "archer"));
        em.cleanup();
        return creatures;
    }

    /** The world-space box around the model's vertices, placed as SbePoseSolver + SbeRenderSupport place it. */
    private static Entity.BoundingBox renderedModel(LivingEntity le) {
        SbeModelGeometry geometry = SbeEntityRegistry.get(le.modelObjectId()).geometryFor(le.getTextureVariant());
        Vector3f p = le.getPosition();
        float anchorY = p.y - le.getLegHeight() - geometry.restMinY() * le.getScale().y;
        Matrix4f model = new Matrix4f().translate(p.x, anchorY, p.z)
                .rotateY((float) Math.toRadians(le.modelYaw()))
                .scale(le.getScale());
        float[] v = geometry.vertices();
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
        for (int index : geometry.indices()) {
            Vector3f w = model.transformPosition(new Vector3f(v[index * 3], v[index * 3 + 1], v[index * 3 + 2]));
            min.min(w);
            max.max(w);
        }
        return new Entity.BoundingBox(min.x, min.y, min.z, max.x, max.y, max.z);
    }

    private static void assertBoxesMatch(String what, Entity.BoundingBox want, Entity.BoundingBox got) {
        assertEquals(want.minX, got.minX, TOLERANCE, what + " minX");
        assertEquals(want.maxX, got.maxX, TOLERANCE, what + " maxX");
        assertEquals(want.minY, got.minY, TOLERANCE, what + " minY");
        assertEquals(want.maxY, got.maxY, TOLERANCE, what + " maxY");
        assertEquals(want.minZ, got.minZ, TOLERANCE, what + " minZ");
        assertEquals(want.maxZ, got.maxZ, TOLERANCE, what + " maxZ");
    }

    @Test
    void theHitboxIsTheRenderedModelAtEveryCardinalHeading() {
        for (LivingEntity le : creatures()) {
            assertNotNull(SbeEntityRegistry.get(le.modelObjectId()), le.getType() + " has a model");
            for (float yaw : CARDINAL_YAWS) {
                le.rotation.y = yaw;
                if (le instanceof RemotePlayer rp) {
                    rp.updateClientVisuals(10f); // let the body settle onto the new heading
                }
                assertBoxesMatch(le.getType() + " facing " + yaw, renderedModel(le), le.getHitBox());
            }
        }
    }

    @Test
    void theHitboxHoldsTheWholeModelBetweenHeadings() {
        for (LivingEntity le : creatures()) {
            for (float yaw = 0f; yaw < 360f; yaw += 15f) {
                le.rotation.y = yaw;
                Entity.BoundingBox model = renderedModel(le);
                Entity.BoundingBox hit = le.getHitBox();
                String what = le.getType() + " facing " + yaw;
                assertEquals(true, hit.minX <= model.minX + TOLERANCE && hit.maxX >= model.maxX - TOLERANCE
                        && hit.minZ <= model.minZ + TOLERANCE && hit.maxZ >= model.maxZ - TOLERANCE,
                        what + ": model pokes out of the hitbox");
            }
        }
    }
}
