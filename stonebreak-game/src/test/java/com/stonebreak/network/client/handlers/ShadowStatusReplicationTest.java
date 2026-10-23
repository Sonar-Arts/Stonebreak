package com.stonebreak.network.client.handlers;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.status.StatusEffectBits;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.network.packet.entity.EntitySpawnS2C;
import com.stonebreak.network.packet.entity.EntityStatusS2C;
import com.stonebreak.player.combat.DoubtController;
import com.stonebreak.player.combat.QuarryController;
import com.stonebreak.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Client half of #371/#339 on a mob's network copy: the server's replicated health fraction and
 * status set are what class mechanics read; abilities' effects on the copy go to the server
 * instead of sticking to the copy; and a despawned copy reports dead so Quarry/Doubt drop it.
 */
class ShadowStatusReplicationTest {

    private static EntitySpawnS2C cowSpawn(int netId) {
        return new EntitySpawnS2C(netId, EntityType.COW.ordinal(), 1f, 64f, 3f, 0f, "Default");
    }

    /** Spawns a cow shadow through the handler and returns it. */
    private static LivingEntity spawnShadow(ClientEntityHandler handler, EntityManager em, int netId) {
        handler.applySpawn(cowSpawn(netId));
        ArgumentCaptor<Entity> added = ArgumentCaptor.forClass(Entity.class);
        verify(em).addEntity(added.capture());
        return (LivingEntity) added.getValue();
    }

    @Test
    void replicatedHealthAndEffectsAreWhatTheCopyReports() {
        EntityManager em = mock(EntityManager.class);
        try (MockedStatic<Game> game = mockStatic(Game.class)) {
            game.when(Game::getWorld).thenReturn(mock(World.class));
            game.when(Game::getEntityManager).thenReturn(em);
            game.when(Game::isClientWorldReady).thenReturn(true);
            ClientEntityHandler handler = new ClientEntityHandler();
            LivingEntity cow = spawnShadow(handler, em, 7);
            assertEquals(cow.getMaxHealth(), cow.getHealth(), "a copy starts at full health");

            handler.applyStatus(new EntityStatusS2C(7, 0.2f,
                    StatusEffectBits.bit(StatusEffectType.FLAT_FOOTED) | StatusEffectBits.bit(StatusEffectType.STUNNED)));

            assertEquals(0.2f, cow.getHealth() / cow.getMaxHealth(), 1e-4f);
            assertTrue(cow.hasStatusEffect(StatusEffectType.FLAT_FOOTED), "Caltrops' Flat-Footed reaches the Rogue's crit roll");
            assertTrue(cow.isStunned());
            assertFalse(cow.hasStatusEffect(StatusEffectType.AMPLIFIED));
            assertTrue(cow.isAlive(), "a low replicated HP never kills the copy — the despawn does");

            QuarryController quarry = new QuarryController();
            quarry.onPlayerHit(cow);
            assertTrue(quarry.isPreyLowHp(), "Marked Prey's low-HP read works on the copy");

            // Effects expire on the server: the next replicated set clears them on the copy.
            handler.applyStatus(new EntityStatusS2C(7, 0.2f, 0));
            assertFalse(cow.hasStatusEffect(StatusEffectType.FLAT_FOOTED));
        }
    }

    @Test
    void statusForABufferedSpawnIsAppliedOnDrain() {
        EntityManager em = mock(EntityManager.class);
        try (MockedStatic<Game> game = mockStatic(Game.class)) {
            game.when(Game::getWorld).thenReturn(mock(World.class));
            game.when(Game::getEntityManager).thenReturn(em);
            ClientEntityHandler handler = new ClientEntityHandler();

            game.when(Game::isClientWorldReady).thenReturn(false);
            handler.applySpawn(cowSpawn(3));
            handler.applyStatus(new EntityStatusS2C(3, 0.5f, StatusEffectBits.bit(StatusEffectType.REVEALED)));

            game.when(Game::isClientWorldReady).thenReturn(true);
            handler.tick();
            ArgumentCaptor<Entity> added = ArgumentCaptor.forClass(Entity.class);
            verify(em).addEntity(added.capture());
            LivingEntity cow = (LivingEntity) added.getValue();
            assertEquals(0.5f, cow.getHealth() / cow.getMaxHealth(), 1e-4f);
            assertTrue(cow.hasStatusEffect(StatusEffectType.REVEALED));
        }
    }

    @Test
    void aDespawnedCopyIsDeadSoQuarryAndDoubtDropIt() {
        EntityManager em = mock(EntityManager.class);
        try (MockedStatic<Game> game = mockStatic(Game.class)) {
            game.when(Game::getWorld).thenReturn(mock(World.class));
            game.when(Game::getEntityManager).thenReturn(em);
            game.when(Game::isClientWorldReady).thenReturn(true);
            ClientEntityHandler handler = new ClientEntityHandler();
            LivingEntity cow = spawnShadow(handler, em, 9);
            assertSame(cow, handler.shadow(9));

            QuarryController quarry = new QuarryController();
            quarry.onPlayerHit(cow);
            DoubtController doubt = new DoubtController();
            try (MockedStatic<MultiplayerSession> ignored = mockStatic(MultiplayerSession.class)) {
                doubt.addStack(cow); // SHAKEN on the copy is forwarded, not applied
            }
            assertEquals(1, doubt.getAllDoubted().size());

            handler.applyDespawn(9);

            assertFalse(cow.isAlive());
            assertNull(handler.shadow(9));
            quarry.update(0.016f);
            assertNull(quarry.getQuarry(), "the mark clears instead of tracking a dead copy");
            assertTrue(doubt.getAllDoubted().isEmpty(), "Fracture no longer targets the despawned copy");
            verify(em).removeEntity(cow);
        }
    }

    @Test
    void anAbilitysEffectOnACopyIsForwardedNotApplied() {
        EntityManager em = mock(EntityManager.class);
        try (MockedStatic<Game> game = mockStatic(Game.class);
             MockedStatic<MultiplayerSession> session = mockStatic(MultiplayerSession.class)) {
            game.when(Game::getWorld).thenReturn(mock(World.class));
            game.when(Game::getEntityManager).thenReturn(em);
            game.when(Game::isClientWorldReady).thenReturn(true);
            ClientEntityHandler handler = new ClientEntityHandler();
            LivingEntity cow = spawnShadow(handler, em, 11);

            cow.applyStatusEffect(StatusEffectType.STUNNED, 1.5f, 0f);
            cow.applyStatusEffect(StatusEffectType.STUNNED, 1.4f, 0f); // a weaker repeat: coalesced
            cow.applyStatusEffect(StatusEffectType.ROOT, 2f, 0f);

            session.verify(() -> MultiplayerSession.onLocalEntityStatusEffect(
                    eq(cow), eq(StatusEffectType.STUNNED), anyFloat(), anyFloat()), times(1));
            session.verify(() -> MultiplayerSession.onLocalEntityStatusEffect(
                    eq(cow), eq(StatusEffectType.ROOT), anyFloat(), anyFloat()), times(1));
            assertFalse(cow.isStunned(), "the copy never holds an un-ticked local effect");

            // A damage hit is forwarded with its kind and shows nothing until the server answers.
            cow.damage(2f, LivingEntity.DamageSource.PLAYER, com.stonebreak.mobs.entities.HitKind.MELEE);
            session.verify(() -> MultiplayerSession.onLocalEntityDamage(eq(cow), eq(2f),
                    eq(LivingEntity.DamageSource.PLAYER), eq(com.stonebreak.mobs.entities.HitKind.MELEE)));
            assertEquals(cow.getMaxHealth(), cow.getHealth());
            session.verify(() -> MultiplayerSession.onLocalEntityKnockback(any(), any(), anyFloat(), anyFloat()),
                    never());
        }
    }
}
