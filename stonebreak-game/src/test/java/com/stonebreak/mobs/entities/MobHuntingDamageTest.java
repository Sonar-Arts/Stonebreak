package com.stonebreak.mobs.entities;

import com.stonebreak.mobs.sbe.SbeEntityRegistry;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Goblins hurting animals: the MOB damage source, and goblin arrows that fly past every player
 * landing on the livestock beyond — but never on the archer or its fellow goblins.
 */
class MobHuntingDamageTest {

    private static final float TICK = 0.05f;

    @BeforeAll
    static void loadModels() {
        SbeEntityRegistry.scanAndLoad();
    }

    @Test
    void mobDamageStaysLastOnTheWire() {
        LivingEntity.DamageSource[] sources = LivingEntity.DamageSource.values();
        assertEquals(LivingEntity.DamageSource.MOB, sources[sources.length - 1],
                "damage sources travel as their ordinal; a new one goes at the end");
    }

    @Test
    void aMobHitRemembersWhereTheAttackerStood() {
        StubMob sheep = new StubMob(EntityType.SHEEP, new Vector3f(0, 64, 0));
        Vector3f goblin = new Vector3f(2, 64, 0);
        float before = sheep.getHealth();
        assertTrue(sheep.damage(2.0f, LivingEntity.DamageSource.MOB, goblin, false));
        assertTrue(sheep.getHealth() < before);
        assertEquals(goblin, sheep.getLastAttackerPosition());
    }

    /** A level goblin arrow flown along +X from the archer through {@code targets}; whether each was hurt. */
    private static boolean[] shoot(StubMob archer, StubMob... targets) {
        World world = mock(World.class); // getBlockAt → null: nothing solid
        EntityManager em = mock(EntityManager.class);
        List<LivingEntity> living = new java.util.ArrayList<>(List.of(targets));
        living.add(archer);
        when(em.getLivingEntities()).thenReturn(living);
        when(world.getEntityManager()).thenReturn(em);
        float[] before = new float[targets.length];
        for (int i = 0; i < targets.length; i++) {
            before[i] = targets[i].getHealth();
        }
        float y = archer.getPosition().y + 0.5f;
        Arrow arrow = new Arrow(world, new Vector3f(archer.getPosition().x, y, 0f), new Vector3f(30f, 0f, 0f))
                .firedBy(archer, 3.0f);
        for (int i = 0; i < 100 && arrow.isAlive() && arrow.position.x < 12f; i++) {
            arrow.update(TICK);
            arrow.velocity.y = 0f;
            arrow.position.add(new Vector3f(arrow.velocity).mul(TICK));
        }
        boolean[] hurt = new boolean[targets.length];
        for (int i = 0; i < targets.length; i++) {
            hurt[i] = targets[i].getHealth() < before[i];
        }
        return hurt;
    }

    @Test
    void aGoblinArrowHitsTheSheepItWasLoosedAt() {
        StubMob archer = new StubMob(EntityType.GOBLIN, new Vector3f(0, 64, 0));
        StubMob sheep = new StubMob(EntityType.SHEEP, new Vector3f(8, 64, 0));
        assertTrue(shoot(archer, sheep)[0]);
    }

    @Test
    void aGoblinArrowFliesThroughAFellowGoblin() {
        StubMob archer = new StubMob(EntityType.GOBLIN, new Vector3f(0, 64, 0));
        StubMob packmate = new StubMob(EntityType.GOBLIN, new Vector3f(4, 64, 0));
        StubMob sheep = new StubMob(EntityType.SHEEP, new Vector3f(8, 64, 0));
        boolean[] hurt = shoot(archer, packmate, sheep);
        assertTrue(!hurt[0], "no friendly fire");
        assertTrue(hurt[1], "it carries on to the sheep");
    }
}
