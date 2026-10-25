package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.HitKind;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.StubMob;
import com.stonebreak.mobs.entities.status.StatusEffectBits;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.network.packet.entity.EntityDamageC2S;
import com.stonebreak.network.packet.entity.EntityStatusEffectC2S;
import com.stonebreak.network.packet.entity.ProjectileSpawnC2S;
import com.stonebreak.network.packet.player.InventorySyncS2C;
import com.stonebreak.network.packet.player.KillCreditS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.network.server.combat.CombatRules;
import com.stonebreak.player.PlayerConstants;
import com.stonebreak.player.combat.berserker.BerserkerAbilityController;
import com.stonebreak.player.combat.ranger.RangerAbilityController;
import com.stonebreak.player.combat.rogue.RogueAbilityController;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Server validation of client combat intents (#339): melee reach/cooldown/held-weapon cap,
 * ability hits by class profile, ARCANE kept, status intents applied to the REAL mob under
 * per-effect rules, and per-player projectile/zone caps that never skip the position check.
 */
class CombatIntentServerTest {

    private static final Vector3f PLAYER_POS = new Vector3f(0f, 64f, 0f);

    private final ServerWorldContext ctx = mock(ServerWorldContext.class);
    private final ServerEntityHandler handler = new ServerEntityHandler();

    /** A live, handshaken player standing at {@link #PLAYER_POS} with {@code classId} and abilities unlocked. */
    private static ServerPlayer player(String classId, String... unlockedKeys) {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 4);
        sp.markHandshakeDone();
        sp.updateState(PLAYER_POS.x, PLAYER_POS.y, PLAYER_POS.z, 0f, 0f);
        Map<String, Integer> cp = new java.util.HashMap<>();
        for (String key : unlockedKeys) {
            cp.put(grantingRank(key), 1);
        }
        sp.serverStats().restore(classId, classId == null ? List.of() : List.of(classId), cp, Map.of(), Set.of(), 0, 0, 0,
                new int[]{10, 10, 10, 10, 10, 10}, 0, 1, 0);
        return sp;
    }

    /** A talent rank that unlocks core ability {@code abilityKey}: abilities come with the paths that grant them. */
    static String grantingRank(String abilityKey) {
        return com.stonebreak.rpg.classes.ClassRegistry.pathsGranting(abilityKey).get(0).nodes().get(0).key();
    }

    /** A registered server-side mob {@code dx} blocks east of the player. */
    private StubMob mobAt(float dx) {
        StubMob mob = new StubMob(EntityType.COW, new Vector3f(PLAYER_POS.x + dx, PLAYER_POS.y, PLAYER_POS.z));
        handler.onEntitySpawned(mob, ctx);
        assertTrue(mob.getNetworkId() >= 0);
        return mob;
    }

    private static EntityDamageC2S hit(Entity target, float amount, LivingEntity.DamageSource source, HitKind kind) {
        return new EntityDamageC2S(target.getNetworkId(), amount, (byte) source.ordinal(),
                (byte) source.defaultDamageType().ordinal(), kind.wireId());
    }

    private static List<Packet> sent(ServerPlayer sp) {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(sp.connection(), atLeast(0)).send(captor.capture(), anyBoolean());
        return captor.getAllValues();
    }

    // ─── Melee ──────────────────────────────────────────────────────────────────

    @Test
    void meleeWithinReachLandsAndReportsTheAppliedResult() {
        ServerPlayer sp = player(null);
        StubMob mob = mobAt(2f);
        float before = mob.getHealth();

        handler.handleEntityDamage(sp, hit(mob, 1f, LivingEntity.DamageSource.PLAYER, HitKind.MELEE), ctx);

        assertEquals(before - 1f, mob.getHealth(), 1e-4f);
        KillCreditS2C result = sent(sp).stream().filter(p -> p instanceof KillCreditS2C)
                .map(KillCreditS2C.class::cast).findFirst().orElseThrow();
        assertEquals(mob.getNetworkId(), result.targetNetworkId(), "the result names the target for the client's feedback");
        assertEquals(HitKind.MELEE.wireId(), result.hitKind());
        assertEquals(1f, result.damageDealt(), 1e-4f);
    }

    @Test
    void playerShapedTargetsTakeNoPlayerHitsOrEffects() {
        // A decoy is a replicated server entity now (#341); until PvP exists (#343/#374) no
        // player's hit or ability effect may land on it (or on a remote player).
        ServerPlayer sp = player(null);
        com.stonebreak.mobs.entities.IllusionDecoy decoy = new com.stonebreak.mobs.entities.IllusionDecoy(
                null, new Vector3f(PLAYER_POS.x + 1.5f, PLAYER_POS.y, PLAYER_POS.z), null, 120f);
        handler.onEntitySpawned(decoy, ctx);
        assertTrue(decoy.getNetworkId() >= 0);

        handler.handleEntityDamage(sp, hit(decoy, 1f, LivingEntity.DamageSource.PLAYER, HitKind.MELEE), ctx);
        handler.handleEntityStatusEffect(sp, new com.stonebreak.network.packet.entity.EntityStatusEffectC2S(
                decoy.getNetworkId(), (byte) com.stonebreak.mobs.entities.status.StatusEffectBits.wireId(
                        com.stonebreak.mobs.entities.status.StatusEffectType.CRIPPLE), 2f, 0.5f));

        assertTrue(decoy.isAlive());
        assertFalse(decoy.hasStatusEffect(com.stonebreak.mobs.entities.status.StatusEffectType.CRIPPLE));
        assertTrue(sent(sp).stream().noneMatch(p -> p instanceof KillCreditS2C));
    }

    @Test
    void meleeOutOfReachOfTheServerPositionIsRefused() {
        ServerPlayer sp = player(null);
        StubMob mob = mobAt(CombatRules.MELEE_MAX_REACH + 3f);
        float before = mob.getHealth();

        handler.handleEntityDamage(sp, hit(mob, 1f, LivingEntity.DamageSource.PLAYER, HitKind.MELEE), ctx);

        assertEquals(before, mob.getHealth(), "reach is measured from where the SERVER has the player");
        assertTrue(sent(sp).stream().noneMatch(p -> p instanceof KillCreditS2C));
    }

    @Test
    void meleeIsRefusedBeforeAPositionIsReported() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 4);
        sp.markHandshakeDone(); // no PlayerStateC2S yet
        StubMob mob = mobAt(0.5f);
        float before = mob.getHealth();

        handler.handleEntityDamage(sp, hit(mob, 1f, LivingEntity.DamageSource.PLAYER, HitKind.MELEE), ctx);

        assertEquals(before, mob.getHealth());
    }

    @Test
    void meleeSwingCooldownRefusesASecondHitStraightAway() {
        ServerPlayer sp = player(null);
        StubMob first = mobAt(1.5f);
        StubMob second = mobAt(-1.5f); // a different mob: its i-frames cannot be what refuses it
        float before = second.getHealth();

        handler.handleEntityDamage(sp, hit(first, 1f, LivingEntity.DamageSource.PLAYER, HitKind.MELEE), ctx);
        handler.handleEntityDamage(sp, hit(second, 1f, LivingEntity.DamageSource.PLAYER, HitKind.MELEE), ctx);

        assertEquals(before, second.getHealth(), "a swing faster than the fastest legal attack is refused");
    }

    @Test
    void meleeDamageIsCappedByTheServerKnownHeldWeapon() {
        ServerPlayer sp = player(null); // empty hand: the fist
        StubMob mob = mobAt(1.5f);
        float before = mob.getHealth();
        float fistCap = CombatRules.maxMeleeDamage(sp.inventory().heldItemId(), null);
        assertTrue(fistCap < before, "test needs a mob that survives the capped hit");

        handler.handleEntityDamage(sp, hit(mob, 1000f, LivingEntity.DamageSource.PLAYER, HitKind.MELEE), ctx);

        assertEquals(before - fistCap, mob.getHealth(), 1e-4f, "the claim is capped at the fist's best legal hit");
        assertTrue(CombatRules.maxMeleeDamage(ItemType.SWORD.getId(), null) > fistCap, "a sword raises the cap");
    }

    @Test
    void meleeIsAlwaysPhysicalDamage() {
        ServerPlayer sp = player(null);
        StubMob mob = mobAt(1.5f);
        mob.applyStatusEffect(StatusEffectType.SPELLMARKED, 5f, 0f);

        handler.handleEntityDamage(sp, hit(mob, 1f, LivingEntity.DamageSource.ARCANE, HitKind.MELEE), ctx);

        assertTrue(mob.hasStatusEffect(StatusEffectType.SPELLMARKED),
                "a melee swing claiming ARCANE must not consume the Spellmark");
    }

    // ─── Ability hits ───────────────────────────────────────────────────────────

    @Test
    void arcaneAbilityDamageKeepsItsTypeOnTheServer() {
        ServerPlayer sp = player(BerserkerAbilityController.CLASS_ID, BerserkerAbilityController.RAMPAGE_KEY);
        StubMob mob = mobAt(3f);
        mob.applyStatusEffect(StatusEffectType.SPELLMARKED, 5f, 0f);
        float before = mob.getHealth();

        handler.handleEntityDamage(sp, hit(mob, 2f, LivingEntity.DamageSource.ARCANE, HitKind.ABILITY), ctx);

        assertFalse(mob.hasStatusEffect(StatusEffectType.SPELLMARKED), "ARCANE consumed the Spellmark");
        assertEquals(before - 2f * (1f + PlayerConstants.SPELLMARKED_BONUS_DAMAGE_MULT), mob.getHealth(), 1e-4f);
    }

    @Test
    void abilityHitsNeedAClassWithAnUnlockedDamagingAbility() {
        StubMob mob = mobAt(3f);
        float before = mob.getHealth();

        handler.handleEntityDamage(player(null), hit(mob, 2f, LivingEntity.DamageSource.PLAYER, HitKind.ABILITY), ctx);
        handler.handleEntityDamage(player(RogueAbilityController.CLASS_ID, RogueAbilityController.CALTROP_KEY),
                hit(mob, 2f, LivingEntity.DamageSource.PLAYER, HitKind.ABILITY), ctx);
        handler.handleEntityDamage(player(BerserkerAbilityController.CLASS_ID), // class, nothing unlocked
                hit(mob, 2f, LivingEntity.DamageSource.PLAYER, HitKind.ABILITY), ctx);

        assertEquals(before, mob.getHealth());
    }

    @Test
    void abilityHitsAreCappedAndRangedByTheClassProfile() {
        ServerPlayer sp = player(RangerAbilityController.CLASS_ID, RangerAbilityController.CULLING_SHOT_KEY);
        CombatRules.AbilityHitProfile profile = CombatRules.abilityProfile(RangerAbilityController.CLASS_ID);
        StubMob far = mobAt(profile.maxRange() + 2f);
        float farBefore = far.getHealth();
        handler.handleEntityDamage(sp, hit(far, 1f, LivingEntity.DamageSource.PLAYER, HitKind.ABILITY), ctx);
        assertEquals(farBefore, far.getHealth(), "beyond Culling Shot's range");

        // Culling Shot at range — well past melee reach — still lands, capped at its maximum.
        StubMob mob = mobAt(20f);
        float before = mob.getHealth();
        handler.handleEntityDamage(sp, hit(mob, 1000f, LivingEntity.DamageSource.PLAYER, HitKind.ABILITY), ctx);
        assertEquals(Math.max(0f, before - profile.maxDamage()), Math.max(0f, mob.getHealth()), 1e-4f);
    }

    @Test
    void abilityHitBudgetRefusesASpamBurst() {
        ServerPlayer sp = player(RangerAbilityController.CLASS_ID, RangerAbilityController.CULLING_SHOT_KEY);
        int burst = CombatRules.abilityProfile(RangerAbilityController.CLASS_ID).burst();
        List<StubMob> mobs = new ArrayList<>();
        for (int i = 0; i <= burst; i++) {
            mobs.add(mobAt(3f + i));
        }
        for (StubMob mob : mobs) {
            handler.handleEntityDamage(sp, hit(mob, 1f, LivingEntity.DamageSource.PLAYER, HitKind.ABILITY), ctx);
        }
        StubMob last = mobs.get(burst);
        assertEquals(last.getMaxHealth(), last.getHealth(), "the hit past the burst budget is refused");
    }

    @Test
    void projectileHitsAreNeverClaimedByAClient() {
        ServerPlayer sp = player(RangerAbilityController.CLASS_ID, RangerAbilityController.CULLING_SHOT_KEY);
        StubMob mob = mobAt(3f);
        float before = mob.getHealth();

        handler.handleEntityDamage(sp, hit(mob, 3f, LivingEntity.DamageSource.ARROW, HitKind.PROJECTILE), ctx);

        assertEquals(before, mob.getHealth());
    }

    // ─── Status intents ─────────────────────────────────────────────────────────

    private static EntityStatusEffectC2S effect(Entity target, StatusEffectType type, float duration, float magnitude) {
        return new EntityStatusEffectC2S(target.getNetworkId(), (byte) StatusEffectBits.wireId(type), duration, magnitude);
    }

    @Test
    void statusIntentIsAppliedToTheRealMobWithClampedMagnitude() {
        ServerPlayer sp = player(BerserkerAbilityController.CLASS_ID, BerserkerAbilityController.SKULL_CRUSHER_KEY);
        StubMob mob = mobAt(3f);

        handler.handleEntityStatusEffect(sp, effect(mob, StatusEffectType.STUNNED, 1f, 0f));
        handler.handleEntityStatusEffect(sp, effect(mob, StatusEffectType.ARMOR_BREAK, 3f, 50f));

        assertTrue(mob.isStunned(), "the real mob is stunned — its AI is what reads it");
        assertEquals(PlayerConstants.SKULLCRUSHER_ARMOR_BREAK_MAGNITUDE,
                mob.getStatusEffectMagnitude(StatusEffectType.ARMOR_BREAK), 1e-6f,
                "magnitude is clamped to the effect's legal maximum");
    }

    @Test
    void statusIntentDurationIsClampedToTheEffectMaximum() {
        CombatRules.StatusIntentRule rule = CombatRules.statusRule(StatusEffectType.STUNNED);
        assertEquals(PlayerConstants.SKULLCRUSHER_STUN_DURATION, rule.maxDuration(), 1e-6f,
                "the longest legal stun is Skull Crusher's");
        assertTrue(CombatRules.statusRule(StatusEffectType.ROOT).maxDuration()
                >= PlayerConstants.RANGER_SNARE_ROOT_DURATION + PlayerConstants.RANGER_SNARE_ROOT_EXTENSION,
                "a studied Quarry's extended Snare root stays legal");
    }

    @Test
    void serverOnlyEffectsAndOtherClassesEffectsAreRefused() {
        ServerPlayer ranger = player(RangerAbilityController.CLASS_ID, RangerAbilityController.SNARE_KEY);
        StubMob mob = mobAt(3f);

        handler.handleEntityStatusEffect(ranger, effect(mob, StatusEffectType.AMPLIFIED, 2f, 0.2f));
        handler.handleEntityStatusEffect(ranger, effect(mob, StatusEffectType.SPELLMARKED, 2f, 0f));
        handler.handleEntityStatusEffect(ranger, effect(mob, StatusEffectType.STUNNED, 1f, 0f));

        assertFalse(mob.hasStatusEffect(StatusEffectType.AMPLIFIED), "Amplified only comes from server zones");
        assertFalse(mob.hasStatusEffect(StatusEffectType.SPELLMARKED), "Spellmarked only comes from server projectiles");
        assertFalse(mob.isStunned(), "a Ranger has no stun");

        handler.handleEntityStatusEffect(ranger, effect(mob, StatusEffectType.ROOT, 2f, 0f));
        assertTrue(mob.isRooted(), "Snare's root is the Ranger's to apply");
    }

    @Test
    void statusIntentOutOfRangeOrWithoutPositionIsRefused() {
        ServerPlayer sp = player(RangerAbilityController.CLASS_ID, RangerAbilityController.SNARE_KEY);
        StubMob far = mobAt(CombatRules.STATUS_INTENT_MAX_RANGE + 5f);
        handler.handleEntityStatusEffect(sp, effect(far, StatusEffectType.ROOT, 2f, 0f));
        assertFalse(far.isRooted());

        ServerPlayer noPos = new ServerPlayer(mock(ServerConnection.class), 5);
        noPos.markHandshakeDone();
        StubMob near = mobAt(1f);
        handler.handleEntityStatusEffect(noPos, effect(near, StatusEffectType.FLAT_FOOTED, 2f, 0f));
        assertFalse(near.hasStatusEffect(StatusEffectType.FLAT_FOOTED));
    }

    @Test
    void anyClassMayApplyTheStealthOpenersFlatFooted() {
        StubMob mob = mobAt(2f);
        handler.handleEntityStatusEffect(player(null), effect(mob, StatusEffectType.FLAT_FOOTED, 2f, 0f));
        assertTrue(mob.hasStatusEffect(StatusEffectType.FLAT_FOOTED));
    }

    // ─── Projectile / zone spawns ───────────────────────────────────────────────

    private record Spawning(ServerWorldContext ctx, EntityManager em) {}

    private static Spawning spawning() {
        ServerWorldContext ctx = mock(ServerWorldContext.class);
        World world = mock(World.class);
        EntityManager em = Mockito.spy(new EntityManager(world));
        when(ctx.world()).thenReturn(world);
        when(ctx.entityManager()).thenReturn(em);
        return new Spawning(ctx, em);
    }

    private static ProjectileSpawnC2S caltrop() {
        return new ProjectileSpawnC2S(ProjectileSpawnC2S.KIND_CALTROP,
                PLAYER_POS.x + 3f, PLAYER_POS.y, PLAYER_POS.z, 0f, 0f, 0f, new float[]{75f});
    }

    @Test
    void caltropFanIsAcceptedThenTheCooldownBudgetRefusesMore() {
        Spawning s = spawning();
        ServerPlayer rogue = player(RogueAbilityController.CLASS_ID, RogueAbilityController.CALTROP_KEY);

        for (int i = 0; i < PlayerConstants.CALTROP_CLUSTER_COUNT + 3; i++) {
            handler.handleProjectileSpawn(rogue, caltrop(), s.ctx());
        }

        verify(s.em(), times(PlayerConstants.CALTROP_CLUSTER_COUNT)).spawnCaltropCluster(any(), anyFloat());
    }

    @Test
    void caltropsNeedTheRogueClassAndTheAbility() {
        Spawning s = spawning();
        handler.handleProjectileSpawn(player(null), caltrop(), s.ctx());
        handler.handleProjectileSpawn(player(RogueAbilityController.CLASS_ID), caltrop(), s.ctx());
        verify(s.em(), never()).spawnCaltropCluster(any(), anyFloat());
    }

    @Test
    void liveCaltropsAreCappedPerOwner() {
        Spawning s = spawning();
        ServerPlayer rogue = player(RogueAbilityController.CLASS_ID, RogueAbilityController.CALTROP_KEY);
        for (int i = 0; i < CombatRules.MAX_LIVE_CALTROPS; i++) {
            Entity live = s.em().spawnCaltropCluster(new Vector3f(), 75f);
            live.setOwnerPlayerId(rogue.playerId());
        }
        Mockito.clearInvocations(s.em());

        handler.handleProjectileSpawn(rogue, caltrop(), s.ctx());

        verify(s.em(), never()).spawnCaltropCluster(any(), anyFloat());
    }

    @Test
    void spawnsAreRefusedUntilAPositionIsReported() {
        Spawning s = spawning();
        ServerPlayer rogue = new ServerPlayer(mock(ServerConnection.class), 6);
        rogue.markHandshakeDone();
        rogue.serverStats().restore(RogueAbilityController.CLASS_ID, List.of(RogueAbilityController.CLASS_ID),
                Map.of(grantingRank(RogueAbilityController.CALTROP_KEY), 1), Map.of(), Set.of(), 0, 0, 0,
                new int[]{10, 10, 10, 10, 10, 10}, 0, 1, 0);

        handler.handleProjectileSpawn(rogue, caltrop(), s.ctx());

        verify(s.em(), never()).spawnCaltropCluster(any(), anyFloat());
    }

    @Test
    void fireBoltNeedsTheStaffInHand() {
        Spawning s = spawning();
        ServerPlayer sp = player(null);
        ProjectileSpawnC2S bolt = new ProjectileSpawnC2S(ProjectileSpawnC2S.KIND_FIRE_BOLT,
                PLAYER_POS.x, PLAYER_POS.y + 1.5f, PLAYER_POS.z, 1f, 0f, 0f, new float[0]);

        handler.handleProjectileSpawn(sp, bolt, s.ctx());
        verify(s.em(), never()).spawnFireBolt(any(), any());

        sp.inventory().add(ItemType.STAFF.getId(), 1); // hotbar slot 0, selected
        handler.handleProjectileSpawn(sp, bolt, s.ctx());
        verify(s.em(), times(1)).spawnFireBolt(any(), any());
    }

    @Test
    void anArrowWithoutTheBowInHandIsRefusedAndTheArrowGivenBack() {
        Spawning s = spawning();
        ServerPlayer sp = player(null);
        sp.inventory().add(ItemType.ARROW.getId(), 5); // arrows, but no bow held

        handler.handleProjectileSpawn(sp, new ProjectileSpawnC2S(ProjectileSpawnC2S.KIND_ARROW,
                PLAYER_POS.x, PLAYER_POS.y + 1.5f, PLAYER_POS.z, 30f, 0f, 0f, new float[0]), s.ctx());

        verify(s.em(), never()).spawnArrow(any(), any());
        assertEquals(5, sp.inventory().countOf(ItemType.ARROW.getId()), "nothing was debited");
        assertInstanceOf(InventorySyncS2C.class, sent(sp).get(sent(sp).size() - 1),
                "the client's predicted debit is rolled back");
    }
}
