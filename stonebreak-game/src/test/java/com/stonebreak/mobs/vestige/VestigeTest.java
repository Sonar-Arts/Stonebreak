package com.stonebreak.mobs.vestige;

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
 * The Vestige's hard rules, on a world whose Shade ends at x=0 (east is plains) and where nothing is
 * solid: it never crosses out, it dissolves if it finds itself out, and dissolving pays nothing.
 */
class VestigeTest {

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
        Vestige vestige = new Vestige(rig.world(), new Vector3f(-0.3f, 64f, 0.5f));
        for (int i = 0; i < 20; i++) {
            vestige.setVelocity(new Vector3f(4f, vestige.getVelocity().y, 0f));
            vestige.update(TICK);
        }
        assertTrue(vestige.getPosition().x < 0f, "still in the Shade: " + vestige.getPosition());
        assertFalse(vestige.isDissolving());
    }

    @Test
    void outsideTheShadeItDissolvesAndIsGoneWithNothingToShow() {
        Rig rig = rig(PlayerLocator.NONE);
        Vestige vestige = new Vestige(rig.world(), new Vector3f(5f, 64f, 0.5f));
        vestige.update(TICK);
        assertTrue(vestige.isDissolving());
        assertTrue(vestige.hasStatusEffect(StatusEffectType.SMOKESCREEN));
        assertEquals(0, vestige.getXpReward());
        assertEquals(0, vestige.getDrops().length);
        assertFalse(vestige.isPlayerAttackable(), "smoke cannot be hit");

        vestige.update(TICK);
        assertEquals(MobBehaviorState.DISSOLVE, vestige.getAI().getCurrentState());
        verify(rig.manager(), never()).removeEntity(vestige);
        for (int i = 0; i < 40; i++) {
            vestige.update(TICK);
        }
        verify(rig.manager()).removeEntity(vestige);
    }

    @Test
    void itLocksOntoThePlayerItFound() {
        Rig rig = rig(playerAt(new Vector3f(-20f, 64f, 0.5f)));
        Vestige vestige = new Vestige(rig.world(), new Vector3f(-5f, 64f, 0.5f));
        vestige.update(TICK);
        assertEquals(7, vestige.lockedTargetPlayerId());
    }

    @Test
    void beingHitWhileStalkingRaisesTheMeterAndSendsItIntoTheVeil() {
        Rig rig = rig(playerAt(new Vector3f(-20f, 64f, 0.5f)));
        Vestige vestige = new Vestige(rig.world(), new Vector3f(-5f, 64f, 0.5f));
        vestige.update(TICK);
        vestige.damage(1f, LivingEntity.DamageSource.PLAYER);
        assertEquals(35f, vestige.mind().meter(), 0.5f);
        vestige.update(TICK);
        assertTrue(vestige.isVeiled(), "out of reach of a scratch: straight to smoke and veil");
        assertEquals(VestigeMind.Mode.STALK, vestige.mind().mode());
    }

    @Test
    void staredAtItSlipsAwayVeiledWithItsEyesDarkAndNoFright() {
        Rig rig = rig(playerAt(new Vector3f(-20f, 64f, 0.5f)));
        Vestige vestige = new Vestige(rig.world(), new Vector3f(-5f, 64f, 0.5f));
        vestige.update(TICK);
        vestige.update(TICK);
        assertTrue(vestige.isVeiled());
        assertTrue(vestige.eyesDark(), "nothing left glowing for them to follow");
        assertFalse(vestige.senses().watched(), "they cannot stare at what they cannot see");
        assertEquals("Evade", vestige.getAI().activeBehaviorName());
        assertEquals(0f, vestige.mind().meter(), 1e-4f, "being hunted is not one of its tricks");
    }

    @Test
    void aGlimpseIsNotEnoughButAVictimWhoKeepsItInViewIsSlipped() {
        // Looking 40 degrees off it: on their screen, not what they are looking at.
        double off = Math.toRadians(40);
        Rig rig = rig(playerAt(new Vector3f(-20f, 64f, 0.5f),
                new Vector3f((float) Math.cos(off), 0f, (float) Math.sin(off))));
        Vestige vestige = new Vestige(rig.world(), new Vector3f(-5f, 64f, 0.5f));
        vestige.update(TICK);
        assertTrue(vestige.senses().inView());
        assertFalse(vestige.isVeiled(), "a glimpse from a victim not hunting for it");
        for (int i = 0; i < 40 && !vestige.isVeiled(); i++) {
            vestige.update(TICK);
        }
        assertTrue(vestige.isVeiled(), "kept in view long enough, they are tracking it");
    }

    /** Stone up to y=63 under the whole rig, so a Vestige stands rather than sinks. */
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
        Vestige vestige = new Vestige(rig.world(), new Vector3f(-0.5f, 64f, 0.5f));
        for (int i = 0; i < 40 && !"Cornered".equals(vestige.getAI().activeBehaviorName()); i++) {
            vestige.update(TICK);
        }
        assertEquals("Cornered", vestige.getAI().activeBehaviorName());
        assertFalse(vestige.isVeiled(), "it turns to fight in plain sight");
        assertFalse(vestige.eyesDark());
        for (int i = 0; i < 30; i++) {
            vestige.update(TICK);
        }
        verify(rig.manager()).addEntity(any(WillOWispOrb.class));
    }

    @Test
    void aFailedFlightFromAFarOffVictimIsNotACorner() {
        Rig rig = rig(playerAt(new Vector3f(-20f, 64f, 0.5f)));
        floor(rig);
        Vestige vestige = new Vestige(rig.world(), new Vector3f(-5f, 64f, 0.5f));
        for (int i = 0; i < 60; i++) {
            vestige.update(TICK);
            assertFalse(vestige.mind().cornered(), "fifteen blocks off is room enough to slip away later");
        }
    }

    @Test
    void theCorneredFightEndsOnceTheVictimBacksOff() {
        Vector3f player = new Vector3f(-4f, 64f, 0.5f);
        Rig rig = rig(playerAt(player));
        floor(rig);
        Vestige vestige = new Vestige(rig.world(), new Vector3f(-0.5f, 64f, 0.5f));
        for (int i = 0; i < 40 && !vestige.mind().cornered(); i++) {
            vestige.update(TICK);
        }
        assertTrue(vestige.mind().cornered());
        player.set(-30f, 64f, 0.5f);
        for (int i = 0; i < 3; i++) {
            vestige.update(TICK);
        }
        assertFalse(vestige.mind().cornered(), "given room, it may run again");
    }

    @Test
    void atDawnItLeavesOnceNobodyIsWatching() {
        Rig rig = rig(playerAt(new Vector3f(-20f, 64f, 0.5f), new Vector3f(-1f, 0f, 0f)));
        when(rig.world().getNightFog()).thenReturn(0f);
        Vestige vestige = new Vestige(rig.world(), new Vector3f(-5f, 64f, 0.5f));
        for (int i = 0; i < 80; i++) {
            vestige.update(TICK);
        }
        verify(rig.manager()).removeEntity(vestige);
        assertFalse(vestige.isDissolving(), "a dawn exit is a quiet one");
    }

    @Test
    void atDawnItWaitsWhileItsEyesAreWatched() {
        Rig rig = rig(playerAt(new Vector3f(-20f, 64f, 0.5f), new Vector3f(1f, 0f, 0f)));
        when(rig.world().getNightFog()).thenReturn(0f);
        Vestige vestige = new Vestige(rig.world(), new Vector3f(-5f, 64f, 0.5f));
        for (int i = 0; i < 80; i++) {
            vestige.update(TICK);
        }
        verify(rig.manager(), never()).removeEntity(vestige);
        assertTrue(vestige.isVeiled());
    }
}
