package com.stonebreak.network.server;

import com.stonebreak.network.packet.player.PlayerHealthS2C;
import com.stonebreak.network.packet.player.PlayerStateFlags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #343: the server's twin of the client's health rules, fed only by what the server knows
 * — reported positions and flags. Falls follow {@code FallDamageHandler} (4 free blocks, a quarter
 * heart per block past them, nothing landing in water, flying or under spawn protection); a
 * dodge's i-frames negate blows, but not a fall.
 */
class PlayerVitalsTest {

    private static final long MS = 1_000_000L;
    private static final short GROUND = (short) PlayerStateFlags.ON_GROUND;
    private static final short AIR = (short) PlayerStateFlags.AIRBORNE;

    private PlayerVitals vitals;
    private long now;

    @BeforeEach
    void setUp() {
        vitals = new PlayerVitals(20f, 20f);
        now = 1_000 * MS;
        // Arrive standing, then wait out the spawn protection.
        report(0f, 64f, GROUND);
        now += 3_000 * MS;
        report(0f, 64f, GROUND);
    }

    private float report(float x, float y, short flags) {
        now += 50 * MS;
        return vitals.onStateReport(x, y, 0f, flags, false, now);
    }

    /** A fall from 64 that peaks at {@code apex} and lands at {@code landY}, one report per block. */
    private float fall(float apex, float landY, short airFlags, short landFlags) {
        for (float y = 64f; y <= apex; y += 1f) {
            report(0f, y, airFlags);
        }
        for (float y = apex; y > landY; y -= 1f) {
            report(0f, y, airFlags);
        }
        return report(0f, landY, landFlags);
    }

    // ─── falls ──────────────────────────────────────────────────────────────────

    @Test
    void fourBlocksAreFree() {
        assertEquals(0f, fall(64f, 60f, AIR, GROUND));
    }

    @Test
    void eachBlockPastFourCostsAQuarterHeart() {
        // 10 blocks from the apex: 6 past the free 4, half a health point each.
        assertEquals(3f, fall(64f, 54f, AIR, GROUND), 1e-4f);
    }

    @Test
    void theFallIsMeasuredFromTheApexOfAJump() {
        // Jumped 2 up, landed 3 below the start: 5 blocks from the apex, 1 past the free 4.
        assertEquals(0.5f, fall(66f, 61f, AIR, GROUND), 1e-4f);
    }

    @Test
    void landingInWaterIsFree() {
        short wetLanding = (short) (PlayerStateFlags.ON_GROUND | PlayerStateFlags.PARTIALLY_IN_WATER);
        assertEquals(0f, fall(64f, 44f, AIR, wetLanding));
    }

    @Test
    void flyingNeverFalls() {
        short flyingAir = (short) (PlayerStateFlags.AIRBORNE | PlayerStateFlags.FLYING);
        for (float y = 64f; y > 40f; y -= 1f) {
            report(0f, y, flyingAir);
        }
        assertEquals(0f, report(0f, 40f, GROUND), "flight voids the fall in progress");
    }

    @Test
    void aTeleportSizedJumpIsNoFall() {
        report(0f, 64f, AIR);
        report(0f, 120f, AIR); // a /tp up, then a short drop
        assertEquals(0f, report(0f, 118f, GROUND));
    }

    @Test
    void spawnProtectionCoversTheFirstLanding() {
        PlayerVitals fresh = new PlayerVitals(20f, 20f);
        long t = 5_000 * MS;
        fresh.onStateReport(0f, 90f, 0f, AIR, false, t);    // arrives mid-air
        for (int i = 1; i <= 20; i++) {
            fresh.onStateReport(0f, 90f - i, 0f, AIR, false, t + i * 50 * MS);
        }
        assertEquals(0f, fresh.onStateReport(0f, 69f, 0f, GROUND, false, t + 21 * 50 * MS),
                "a 21-block drop inside the protection window costs nothing");
    }

    @Test
    void spawnProtectionEndsEarlyOnceSafelyDown() {
        PlayerVitals fresh = new PlayerVitals(20f, 20f);
        long t = 5_000 * MS;
        fresh.onStateReport(0f, 64f, 0f, GROUND, false, t);
        assertTrue(fresh.isProtected(t + 100 * MS));
        fresh.onStateReport(0f, 64f, 0f, GROUND, false, t + 600 * MS);
        assertFalse(fresh.isProtected(t + 650 * MS), "on the ground past the grace: protection ends");
    }

    // ─── damage, healing, dodge ─────────────────────────────────────────────────

    @Test
    void damageAndHealingStayWithinZeroAndTheMaximum() {
        assertEquals(20f, vitals.damage(50f, PlayerHealthS2C.CAUSE_MOB, now));
        assertEquals(0f, vitals.health());
        assertEquals(0f, vitals.heal(5f), "a dead body heals nothing");

        PlayerVitals hurt = new PlayerVitals(15f, 20f);
        assertEquals(5f, hurt.heal(8f));
        assertEquals(20f, hurt.health());
    }

    @Test
    void aRaisedMaximumAddsTheDifferenceALoweredOneOnlyClamps() {
        PlayerVitals v = new PlayerVitals(15f, 20f);
        v.applyMaxHealth(24f);
        assertEquals(19f, v.health());
        v.applyMaxHealth(16f);
        assertEquals(16f, v.health());
    }

    @Test
    void aDodgeNegatesBlowsButNotFalls() {
        report(0f, 64f, (short) (PlayerStateFlags.ON_GROUND | PlayerStateFlags.DODGING));

        assertEquals(0f, vitals.damage(4f, PlayerHealthS2C.CAUSE_MOB, now + 100 * MS));
        assertEquals(0f, vitals.damage(4f, PlayerHealthS2C.CAUSE_PLAYER, now + 100 * MS));
        assertEquals(4f, vitals.damage(4f, PlayerHealthS2C.CAUSE_FALL, now + 100 * MS));
        assertEquals(4f, vitals.damage(4f, PlayerHealthS2C.CAUSE_MOB, now + 600 * MS),
                "the i-frames last the dodge's window only");
    }

    @Test
    void aClientHoldingTheDodgeFlagGetsOneWindowPerCooldown() {
        short dodging = (short) (PlayerStateFlags.ON_GROUND | PlayerStateFlags.DODGING);
        report(0f, 64f, dodging);
        report(0f, 64f, GROUND);
        report(0f, 64f, dodging); // a second rise 100 ms later: no new window

        assertEquals(4f, vitals.damage(4f, PlayerHealthS2C.CAUSE_MOB, now + 400 * MS));
    }

    @Test
    void cactusContactPulsesAtTheClientsInterval() {
        float total = 0f;
        for (int tick = 0; tick < 25; tick++) { // 1.25 s of contact
            total += vitals.onCactusContact(true, false, 0.05f, now, 0.5f, 0.5f);
        }
        assertEquals(1f, total, 1e-4f, "a pulse every half second");
        assertEquals(0f, vitals.onCactusContact(true, true, 1f, now, 0.5f, 0.5f), "flying touches nothing");
    }

    /** A wisp's fire burns for its duration at its rate, in pulses, and a fresh hit refreshes it. */
    @Test
    void aBurnDealsItsDamageOverItsDurationAndRefreshesRatherThanStacks() {
        vitals.ignite(3f, 0.5f);
        float total = 0f;
        for (int i = 0; i < 100; i++) {
            total += vitals.onBurnTick(0.05f);
        }
        assertEquals(1.5f, total, 1e-4f);
        assertFalse(vitals.isBurning());

        vitals.ignite(3f, 0.5f);
        vitals.ignite(3f, 0.5f);
        total = 0f;
        for (int i = 0; i < 100; i++) {
            total += vitals.onBurnTick(0.05f);
        }
        assertEquals(1.5f, total, 1e-4f, "two hits, one fire");
    }
}
