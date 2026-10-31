package com.stonebreak.mobs.wisp;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.PlayerLocator;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.world.World;
import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.biomes.BiomeType;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Wisp's hard rules, on a world whose Shade ends at x=0 (east is plains) and where nothing is
 * solid: it never crosses out, it dissolves if it finds itself out, and dissolving pays nothing.
 */
class WispTest {

    private static final float TICK = 0.05f;

    private record Rig(World world, EntityManager manager) {}

    private static Rig rig(PlayerLocator players) {
        World world = mock(World.class);
        TerrainGenerator terrain = mock(TerrainGenerator.class);
        when(terrain.getBiomeAt(anyInt(), anyInt())).thenAnswer(inv ->
                (int) inv.getArgument(0) < 0 ? BiomeType.SHADE : BiomeType.PLAINS);
        when(world.terrain()).thenReturn(terrain);
        when(world.getNightFog()).thenReturn(1f);
        when(world.getPlayerLocator()).thenReturn(players);
        EntityManager manager = mock(EntityManager.class);
        when(world.getEntityManager()).thenReturn(manager);
        return new Rig(world, manager);
    }

    /** One player, standing still at {@code at}, looking east. */
    private static PlayerLocator playerAt(Vector3f at) {
        return playerAt(at, new Vector3f(1f, 0f, 0f));
    }

    /** One player, standing still at {@code at}, looking along {@code look}. */
    private static PlayerLocator playerAt(Vector3f at, Vector3f look) {
        return new PlayerLocator() {
            @Override
            public Vector3f nearestPlayer(Vector3f from, Vector3f out) {
                return out.set(at);
            }

            @Override
            public void forEachPlayer(Vector3f from, float radius, Consumer<SensedPlayer> sink) {
                sink.accept(new SensedPlayer(7, new Vector3f(at), 0f, false, 0f));
            }

            @Override
            public Vector3f playerPosition(int id, Vector3f from, Vector3f out) {
                return id == 7 ? out.set(at) : null;
            }

            @Override
            public Vector3f playerLook(int id, Vector3f from, Vector3f out) {
                return out.set(look);
            }
        };
    }

    @Test
    void theEdgeOfTheShadeIsAWall() {
        Rig rig = rig(PlayerLocator.NONE);
        Wisp wisp = new Wisp(rig.world(), new Vector3f(-0.3f, 64f, 0.5f));
        for (int i = 0; i < 20; i++) {
            wisp.setVelocity(new Vector3f(4f, wisp.getVelocity().y, 0f));
            wisp.update(TICK);
        }
        assertTrue(wisp.getPosition().x < 0f, "still in the Shade: " + wisp.getPosition());
        assertFalse(wisp.isDissolving());
    }

    @Test
    void outsideTheShadeItDissolvesAndIsGoneWithNothingToShow() {
        Rig rig = rig(PlayerLocator.NONE);
        Wisp wisp = new Wisp(rig.world(), new Vector3f(5f, 64f, 0.5f));
        wisp.update(TICK);
        assertTrue(wisp.isDissolving());
        assertTrue(wisp.hasStatusEffect(StatusEffectType.SMOKESCREEN));
        assertEquals(0, wisp.getXpReward());
        assertEquals(0, wisp.getDrops().length);
        assertFalse(wisp.isPlayerAttackable(), "smoke cannot be hit");

        wisp.update(TICK);
        assertEquals(MobBehaviorState.DISSOLVE, wisp.getAI().getCurrentState());
        verify(rig.manager(), never()).removeEntity(wisp);
        for (int i = 0; i < 40; i++) {
            wisp.update(TICK);
        }
        verify(rig.manager()).removeEntity(wisp);
    }

    @Test
    void itLocksOntoThePlayerItFound() {
        Rig rig = rig(playerAt(new Vector3f(-20f, 64f, 0.5f)));
        Wisp wisp = new Wisp(rig.world(), new Vector3f(-5f, 64f, 0.5f));
        wisp.update(TICK);
        assertEquals(7, wisp.lockedTargetPlayerId());
    }

    @Test
    void beingHitWhileStalkingRaisesTheMeterAndSendsItIntoTheVeil() {
        Rig rig = rig(playerAt(new Vector3f(-20f, 64f, 0.5f)));
        Wisp wisp = new Wisp(rig.world(), new Vector3f(-5f, 64f, 0.5f));
        wisp.update(TICK);
        wisp.damage(1f, LivingEntity.DamageSource.PLAYER);
        assertEquals(35f, wisp.mind().meter(), 0.5f);
        wisp.update(TICK);
        assertTrue(wisp.isVeiled(), "out of reach of a scratch: straight to smoke and veil");
        assertEquals(WispMind.Mode.STALK, wisp.mind().mode());
    }

    @Test
    void staredAtItSlipsAwayVeiledWithItsEyesDarkAndNoFright() {
        Rig rig = rig(playerAt(new Vector3f(-20f, 64f, 0.5f)));
        Wisp wisp = new Wisp(rig.world(), new Vector3f(-5f, 64f, 0.5f));
        wisp.update(TICK);
        wisp.update(TICK);
        assertTrue(wisp.isVeiled());
        assertTrue(wisp.eyesDark(), "nothing left glowing for them to follow");
        assertFalse(wisp.senses().watched(), "they cannot stare at what they cannot see");
        assertEquals("Evade", wisp.getAI().activeBehaviorName());
        assertEquals(0f, wisp.mind().meter(), 1e-4f, "being hunted is not one of its tricks");
    }

    @Test
    void aGlimpseIsNotEnoughButAVictimWhoKeepsItInViewIsSlipped() {
        // Looking 40 degrees off it: on their screen, not what they are looking at.
        double off = Math.toRadians(40);
        Rig rig = rig(playerAt(new Vector3f(-20f, 64f, 0.5f),
                new Vector3f((float) Math.cos(off), 0f, (float) Math.sin(off))));
        Wisp wisp = new Wisp(rig.world(), new Vector3f(-5f, 64f, 0.5f));
        wisp.update(TICK);
        assertTrue(wisp.senses().inView());
        assertFalse(wisp.isVeiled(), "a glimpse from a victim not hunting for it");
        for (int i = 0; i < 40 && !wisp.isVeiled(); i++) {
            wisp.update(TICK);
        }
        assertTrue(wisp.isVeiled(), "kept in view long enough, they are tracking it");
    }

    /** Stone up to y=63 under the whole rig, so a Wisp stands rather than sinks. */
    private static void floor(Rig rig) {
        when(rig.world().getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv ->
                (int) inv.getArgument(1) < 64 ? BlockType.STONE : BlockType.AIR);
    }

    @Test
    void pinnedAgainstTheEdgeOfTheShadeItStopsRunningAndFights() {
        // Its back to the plains, the player three and a half blocks off and staring at it. Every
        // way out finds no route (this rig has no pathfinder): it is cornered.
        Rig rig = rig(playerAt(new Vector3f(-4f, 64f, 0.5f)));
        floor(rig);
        Wisp wisp = new Wisp(rig.world(), new Vector3f(-0.5f, 64f, 0.5f));
        for (int i = 0; i < 40 && !"Cornered".equals(wisp.getAI().activeBehaviorName()); i++) {
            wisp.update(TICK);
        }
        assertEquals("Cornered", wisp.getAI().activeBehaviorName());
        assertFalse(wisp.isVeiled(), "it turns to fight in plain sight");
        assertFalse(wisp.eyesDark());
        for (int i = 0; i < 30; i++) {
            wisp.update(TICK);
        }
        verify(rig.manager()).addEntity(any(WillOWispOrb.class));
    }

    @Test
    void aFailedFlightFromAFarOffVictimIsNotACorner() {
        Rig rig = rig(playerAt(new Vector3f(-20f, 64f, 0.5f)));
        floor(rig);
        Wisp wisp = new Wisp(rig.world(), new Vector3f(-5f, 64f, 0.5f));
        for (int i = 0; i < 60; i++) {
            wisp.update(TICK);
            assertFalse(wisp.mind().cornered(), "fifteen blocks off is room enough to slip away later");
        }
    }

    @Test
    void theCorneredFightEndsOnceTheVictimBacksOff() {
        Vector3f player = new Vector3f(-4f, 64f, 0.5f);
        Rig rig = rig(playerAt(player));
        floor(rig);
        Wisp wisp = new Wisp(rig.world(), new Vector3f(-0.5f, 64f, 0.5f));
        for (int i = 0; i < 40 && !wisp.mind().cornered(); i++) {
            wisp.update(TICK);
        }
        assertTrue(wisp.mind().cornered());
        player.set(-30f, 64f, 0.5f);
        for (int i = 0; i < 3; i++) {
            wisp.update(TICK);
        }
        assertFalse(wisp.mind().cornered(), "given room, it may run again");
    }

    @Test
    void atDawnItLeavesOnceNobodyIsWatching() {
        Rig rig = rig(playerAt(new Vector3f(-20f, 64f, 0.5f), new Vector3f(-1f, 0f, 0f)));
        when(rig.world().getNightFog()).thenReturn(0f);
        Wisp wisp = new Wisp(rig.world(), new Vector3f(-5f, 64f, 0.5f));
        for (int i = 0; i < 80; i++) {
            wisp.update(TICK);
        }
        verify(rig.manager()).removeEntity(wisp);
        assertFalse(wisp.isDissolving(), "a dawn exit is a quiet one");
    }

    @Test
    void atDawnItWaitsWhileItsEyesAreWatched() {
        Rig rig = rig(playerAt(new Vector3f(-20f, 64f, 0.5f), new Vector3f(1f, 0f, 0f)));
        when(rig.world().getNightFog()).thenReturn(0f);
        Wisp wisp = new Wisp(rig.world(), new Vector3f(-5f, 64f, 0.5f));
        for (int i = 0; i < 80; i++) {
            wisp.update(TICK);
        }
        verify(rig.manager(), never()).removeEntity(wisp);
        assertTrue(wisp.isVeiled());
    }
}
