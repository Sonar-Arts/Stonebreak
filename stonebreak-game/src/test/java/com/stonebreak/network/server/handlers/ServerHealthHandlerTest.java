package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ConnectionRegistry;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.mobs.entities.HitKind;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.network.packet.player.KillCreditS2C;
import com.stonebreak.network.packet.player.PlayerDamageC2S;
import com.stonebreak.network.packet.player.PlayerHealthS2C;
import com.stonebreak.network.packet.player.PlayerKnockbackS2C;
import com.stonebreak.network.packet.player.PlayerStateFlags;
import com.stonebreak.network.packet.world.PvpStateS2C;
import com.stonebreak.network.server.ServerLevel;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerPlayerLocator;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.player.combat.berserker.BerserkerAbilityController;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #343: hits on players are resolved by the server, against the health it owns. Mobs land
 * blows and arrows on whichever player they reach — not the host — and players hurt each other
 * only where the world allows PvP, under the same reach/cooldown/cap rules as hits on mobs.
 */
class ServerHealthHandlerTest {

    private final ConnectionRegistry connections = mock(ConnectionRegistry.class);
    private final ServerWorldContext ctx = new ServerWorldContext(connections);
    private final ServerLevel level = mock(ServerLevel.class);
    private final ServerHealthHandler health = new ServerHealthHandler();
    private ServerPlayerLocator locator;

    @BeforeEach
    void setUp() {
        when(level.world()).thenReturn(mock(World.class));
        when(level.spawn()).thenReturn(new Vector3f(0f, 80f, 0f));
        ctx.setServerLevel(level);
        locator = new ServerPlayerLocator(ctx.players(), ctx, health);
    }

    private ServerPlayer player(int id, float x) {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), id);
        sp.markHandshakeDone();
        sp.updateState(x, 64f, 0f, 0f, 0f);
        ctx.addPlayer(sp);
        return sp;
    }

    private void pvp(boolean on) {
        when(level.pvpEnabled()).thenReturn(on);
    }

    private static List<Packet> sent(ServerPlayer sp) {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(sp.connection(), atLeast(0)).send(captor.capture(), anyBoolean());
        return captor.getAllValues();
    }

    private static <T> List<T> sent(ServerPlayer sp, Class<T> type) {
        return sent(sp).stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static PlayerDamageC2S melee(ServerPlayer target, float amount) {
        return new PlayerDamageC2S(target.playerId(), amount, (byte) LivingEntity.DamageSource.PLAYER.ordinal(),
                (byte) 0, HitKind.MELEE.wireId());
    }

    // ─── mobs ───────────────────────────────────────────────────────────────────

    @Test
    void aMobsBlowLandsOnTheNearestPlayerAndPushesThemAway() {
        ServerPlayer host = player(1, 40f);
        ServerPlayer guest = player(2, 2f);

        assertTrue(locator.hurtNearestPlayer(new Vector3f(0f, 64f, 0f), 3f, 5f));

        assertEquals(17f, guest.health(), "the player the goblin reached, not the host");
        assertEquals(20f, host.health());
        List<PlayerKnockbackS2C> pushes = sent(guest, PlayerKnockbackS2C.class);
        assertEquals(1, pushes.size());
        assertTrue(pushes.get(0).vx() > 0f, "away from the mob");
    }

    @Test
    void aBlowReachesNobodyBeyondMeleeDistance() {
        ServerPlayer far = player(1, 30f);

        assertFalse(locator.hurtNearestPlayer(new Vector3f(0f, 64f, 0f), 3f, 5f));
        assertEquals(20f, far.health());
    }

    @Test
    void aMobsArrowHurtsThePlayerItIsInside() {
        ServerPlayer target = player(1, 0f);
        ServerPlayer bystander = player(2, 3f);

        assertTrue(locator.hitPlayerAt(new Vector3f(0.1f, 65f, 0f), 0.4f, 4f, -1));
        assertFalse(locator.hitPlayerAt(new Vector3f(10f, 65f, 0f), 0.4f, 4f, -1), "inside nobody");

        assertEquals(16f, target.health());
        assertEquals(20f, bystander.health());
    }

    @Test
    void theDeadAreNotTargets() {
        ServerPlayer dead = player(1, 1f);
        health.hurt(dead, 20f, PlayerHealthS2C.CAUSE_MOB, System.nanoTime(), ctx);
        ServerPlayer alive = player(2, 4f);

        locator.hurtNearestPlayer(new Vector3f(0f, 64f, 0f), 3f, 0f);

        assertEquals(17f, alive.health());
    }

    // ─── PvP ────────────────────────────────────────────────────────────────────

    @Test
    void withPvpOffPlayersCannotHurtEachOther() {
        pvp(false);
        ServerPlayer attacker = player(1, 0f);
        ServerPlayer target = player(2, 1.5f);

        health.handlePlayerDamage(attacker, melee(target, 1f), ctx);
        boolean arrowSpent = locator.hitPlayerAt(new Vector3f(1.5f, 65f, 0f), 0.1f, 4f, attacker.playerId());

        assertEquals(20f, target.health());
        assertFalse(arrowSpent, "the arrow flies on");
        assertTrue(sent(attacker, KillCreditS2C.class).isEmpty());
    }

    @Test
    void withPvpOnAMeleeHitInReachLandsAndIsCreditedToTheAttacker() {
        pvp(true);
        ServerPlayer attacker = player(1, 0f);
        ServerPlayer target = player(2, 1.5f);

        health.handlePlayerDamage(attacker, melee(target, 1f), ctx);

        assertEquals(19f, target.health());
        List<KillCreditS2C> credits = sent(attacker, KillCreditS2C.class);
        assertEquals(1, credits.size());
        assertEquals(1f, credits.get(0).damageDealt());
        assertFalse(credits.get(0).killed());
        assertEquals(1, sent(target, PlayerKnockbackS2C.class).size());
    }

    @Test
    void aPvpHitIsCappedLikeAHitOnAMob() {
        pvp(true);
        ServerPlayer attacker = player(1, 0f);
        ServerPlayer target = player(2, 1.5f);
        float cap = com.stonebreak.network.server.combat.CombatRules.maxMeleeDamage(
                attacker.inventory().heldItemId(), attacker.serverStats().getSelectedClassId());

        health.handlePlayerDamage(attacker, melee(target, 500f), ctx);

        assertEquals(20f - Math.min(cap, 20f), target.health(), 1e-4f);
    }

    @Test
    void aPvpHitOutOfReachOrOnYourselfIsRefused() {
        pvp(true);
        ServerPlayer attacker = player(1, 0f);
        ServerPlayer far = player(2, 20f);

        health.handlePlayerDamage(attacker, melee(far, 1f), ctx);
        health.handlePlayerDamage(attacker, melee(attacker, 1f), ctx);

        assertEquals(20f, far.health());
        assertEquals(20f, attacker.health());
    }

    @Test
    void withPvpOnAPlayersArrowHurtsAnotherPlayerButNeverTheShooter() {
        pvp(true);
        ServerPlayer shooter = player(1, 0f);
        ServerPlayer target = player(2, 5f);

        assertFalse(locator.hitPlayerAt(new Vector3f(0f, 65f, 0f), 0.1f, 4f, shooter.playerId()),
                "an arrow leaving the bow is inside its shooter");
        assertTrue(locator.hitPlayerAt(new Vector3f(5f, 65f, 0f), 0.1f, 4f, shooter.playerId()));

        assertEquals(20f, shooter.health());
        assertEquals(16f, target.health());
        assertEquals(1, sent(shooter, KillCreditS2C.class).size());
    }

    // ─── healing ────────────────────────────────────────────────────────────────

    @Test
    void lifestealHealsOnlyABerserkerReportingIt() {
        ServerPlayer berserker = player(1, 0f);
        berserker.serverStats().selectClass(BerserkerAbilityController.CLASS_ID);
        berserker.setMovementFlags((short) PlayerStateFlags.LIFESTEAL);
        ServerPlayer pretender = player(2, 0f);
        pretender.setMovementFlags((short) PlayerStateFlags.LIFESTEAL);
        health.hurt(berserker, 10f, PlayerHealthS2C.CAUSE_MOB, System.nanoTime(), ctx);
        health.hurt(pretender, 10f, PlayerHealthS2C.CAUSE_MOB, System.nanoTime(), ctx);

        ServerHealthHandler.applyLifesteal(berserker, 8f);
        ServerHealthHandler.applyLifesteal(pretender, 8f);

        assertEquals(12f, berserker.health(), "25 % of the 8 dealt");
        assertEquals(10f, pretender.health(), "only a Berserker has Rage");
    }

    // ─── replication ────────────────────────────────────────────────────────────

    @Test
    void healthChangesGoOutOnceAtTheEndOfTheTick() {
        ServerPlayer sp = player(1, 0f);
        health.tick(ctx, 0.05f, System.nanoTime()); // the join's own snapshot
        org.mockito.Mockito.clearInvocations(connections);

        health.hurt(sp, 2f, PlayerHealthS2C.CAUSE_FALL, System.nanoTime(), ctx);
        health.hurt(sp, 1f, PlayerHealthS2C.CAUSE_MOB, System.nanoTime(), ctx);
        health.tick(ctx, 0.05f, System.nanoTime());
        health.tick(ctx, 0.05f, System.nanoTime());

        verify(connections, times(1)).broadcast(
                eq(new PlayerHealthS2C(1, 17f, 20f, false, PlayerHealthS2C.CAUSE_MOB)), eq(false));
    }

    @Test
    void aJoiningPlayerLearnsThePvpRuleAndWhoIsDead() {
        pvp(true);
        ServerPlayer dead = player(1, 0f);
        health.hurt(dead, 20f, PlayerHealthS2C.CAUSE_MOB, System.nanoTime(), ctx);
        ServerPlayer joined = player(2, 5f);

        health.onPeerJoined(joined, ctx);

        assertTrue(sent(joined).contains(new PvpStateS2C(true)));
        assertTrue(sent(joined, PlayerHealthS2C.class).stream()
                .anyMatch(h -> h.playerId() == 1 && h.dead()));
    }
}
