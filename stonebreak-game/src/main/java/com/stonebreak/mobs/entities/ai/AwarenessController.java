package com.stonebreak.mobs.entities.ai;

import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.behavior.PlayerLocator;
import com.stonebreak.mobs.entities.ai.perception.AwarenessListener;
import com.stonebreak.mobs.entities.ai.perception.LightProbe;
import com.stonebreak.mobs.entities.ai.perception.Loudness;
import com.stonebreak.mobs.entities.ai.perception.NoiseBus;
import com.stonebreak.mobs.entities.ai.perception.PerceptionProfile;
import com.stonebreak.mobs.entities.ai.perception.PerceptionSnapshot;
import com.stonebreak.mobs.entities.ai.perception.ShoutRelay;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.player.PlayerConstants;
import com.stonebreak.player.interaction.RaycastEngine;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * One mob's perception of the nearest player (#321): a 0..100 meter filled by what the mob sees
 * and hears, and the awareness state it drives — UNAWARE → SUSPICIOUS → ALERTED, then SEARCHING
 * once the player is lost, and back to UNAWARE when the search runs out.
 *
 * <p>Every number comes from the mob type's {@link PerceptionProfile}. A perception tick runs at
 * {@link #PERCEPTION_HZ}, not every frame, and works out
 * <pre>rate = (vision + hearing) × alertness[state]</pre>
 * <ul>
 *   <li><b>vision</b> needs line of sight and fades with distance (1 point blank, 0 at range), with
 *       angle (full in the forward cone, weak at the sides, nothing behind) and with the light at
 *       the player (a dark-sighted mob sees by at least its floor, and further in the dark);</li>
 *   <li><b>hearing</b> is the player's movement loudness, fading to nothing at its audible radius;
 *       one-off sounds on the world's {@link NoiseBus} add a single bump each and point the mob at
 *       where they were made.</li>
 * </ul>
 * With nothing detected the meter drains. Being hit by a player alerts the mob outright; an
 * alerted mob shouts, and packmates in range turn suspicious (or alerted, if they can see the
 * player themselves). Each state change is announced to {@link AwarenessListener}s.
 *
 * <p>Runs on the authoritative world's tick thread. Client network shadows never tick it; the
 * server's state arrives through {@link #applyReplicatedState}, so the stealth UI and abilities can
 * read any mob's awareness on the client.
 */
public class AwarenessController {

    public enum AwarenessState { UNAWARE, SUSPICIOUS, ALERTED, SEARCHING }

    /** Perception ticks per second. */
    public static final float PERCEPTION_HZ = 5f;
    private static final float PERCEPTION_PERIOD = 1f / PERCEPTION_HZ;

    /** An alerted mob that has neither seen nor heard the player this long starts searching. */
    static final float LOST_TRACK_SECONDS = 1f;

    /** A fully stealthed player is a smaller, stiller shape: sight of them is scaled by this. */
    static final float STEALTHED_VISION_MULT = 0.6f;

    /** Perception beyond its furthest sense plus this margin is skipped outright. */
    private static final float ACTIVE_MARGIN = 8f;

    private final LivingEntity self;
    private final PerceptionProfile profile;
    private final PlayerLocator players;
    private final LightProbe light;
    private final ShoutRelay shouts;
    /** Null means "the mob's own world's bus", resolved per tick. */
    private final NoiseBus fixedBus;
    private final List<AwarenessListener> listeners = new ArrayList<>();

    private AwarenessState state = AwarenessState.UNAWARE;
    private float meter;
    private float sinceLastPerception;
    private float lostTrackTimer;
    private float memoryRemaining;
    /** The last sound number this mob has heard; {@code Long.MIN_VALUE} until its first tick. */
    private long heardUntil = Long.MIN_VALUE;
    private PerceptionSnapshot snapshot = PerceptionSnapshot.NONE;

    private final Vector3f lastKnownPos = new Vector3f();
    private boolean hasLastKnown;
    private final Vector3f playerScratch = new Vector3f();
    private final Vector3f noiseScratch = new Vector3f();
    private float loudestHeard;

    /** Line-of-sight marcher over the mob's world; rebuilt only if the mob changes worlds. */
    private RaycastEngine sightRay;
    private World sightRayWorld;

    /** Watches the players sharing the mob's world, with its type's profile. */
    public AwarenessController(LivingEntity self) {
        this(self, PerceptionProfile.forType(self.getType()));
    }

    /** Watches the players sharing the mob's world: sky light, its world's sounds, pack shouts. */
    public AwarenessController(LivingEntity self, PerceptionProfile profile) {
        this(self, profile, PlayerLocator.ofWorld(self), LightProbe.SKY, ShoutRelay.WORLD, null);
    }

    /**
     * Full form, for tests.
     *
     * @param bus the sounds to listen to; null for the mob's own world's bus
     */
    public AwarenessController(LivingEntity self, PerceptionProfile profile, PlayerLocator players,
                               LightProbe light, ShoutRelay shouts, NoiseBus bus) {
        this.self = self;
        this.profile = profile;
        this.players = players;
        this.light = light;
        this.shouts = shouts;
        this.fixedBus = bus;
        listeners.add(this::shoutOnAlert);
    }

    public AwarenessState getState() { return state; }

    public PerceptionProfile profile() { return profile; }

    /** What the last perception tick worked out, for the debug overlay. */
    public PerceptionSnapshot snapshot() { return snapshot; }

    /** Subscribes to state changes. */
    public void addListener(AwarenessListener listener) {
        listeners.add(listener);
    }

    // ── Perception ────────────────────────────────────────────────────────────

    /** Advances the clock, running a perception tick every {@link #PERCEPTION_PERIOD}. */
    public void update(float deltaTime) {
        if (!self.isAlive()) return;
        sinceLastPerception += deltaTime;
        if (sinceLastPerception + 1e-6f >= PERCEPTION_PERIOD) {
            perceive(sinceLastPerception);
            sinceLastPerception = 0f;
        }
    }

    private void perceive(float dt) {
        Vector3f selfPos = self.getPosition();
        Vector3f playerPos = players.nearestPlayer(selfPos, playerScratch);

        float vision = 0f;
        float hearing = 0f;
        float lightAtPlayer = 1f;
        float effRange = 0f;
        if (playerPos != null) {
            float dist = selfPos.distance(playerPos);
            if (dist <= activeRadius()) {
                lightAtPlayer = light.lightAt(self.getWorld(), playerPos);
                effRange = effectiveRange(lightAtPlayer);
                vision = vision(selfPos, playerPos, dist, lightAtPlayer, effRange);
                hearing = profile.hearGain()
                        * Loudness.strength(players.nearestPlayerLoudness(selfPos), dist, profile.hearingRange());
            }
        }
        float impulse = hearEvents(selfPos);

        float alertness = profile.alertness().of(state);
        float rate = (vision + hearing) * alertness;
        boolean detected = rate > 0f || impulse > 0f;
        if (detected) {
            meter += rate * dt + impulse * alertness;
        } else {
            rate = -profile.decayPerSec();
            meter += rate * dt;
        }
        meter = clampMeter(meter);

        if (vision > 0f || hearing > 0f) {
            remember(playerPos);
        } else if (impulse > 0f) {
            remember(noiseScratch);
        }

        advanceState(dt, vision > 0f, detected);
        snapshot = new PerceptionSnapshot(meter, rate, vision, hearing, lightAtPlayer, effRange,
                state == AwarenessState.SEARCHING ? memoryRemaining : 0f);
    }

    private void advanceState(float dt, boolean sawPlayer, boolean detected) {
        switch (state) {
            case ALERTED -> {
                if (detected) {
                    lostTrackTimer = 0f;
                } else if ((lostTrackTimer += dt) >= LOST_TRACK_SECONDS) {
                    memoryRemaining = profile.memorySeconds();
                    setState(AwarenessState.SEARCHING, false);
                }
            }
            case SEARCHING -> {
                if (sawPlayer) {
                    meter = Math.max(meter, profile.alarmThreshold());
                    setState(AwarenessState.ALERTED, false);
                } else {
                    memoryRemaining = detected ? profile.memorySeconds() : memoryRemaining - dt;
                    if (memoryRemaining <= 0f) {
                        meter = 0f;
                        hasLastKnown = false;
                        setState(AwarenessState.UNAWARE, false);
                    }
                }
            }
            case UNAWARE, SUSPICIOUS -> {
                if (meter >= profile.alarmThreshold()) {
                    setState(AwarenessState.ALERTED, false);
                } else if (meter >= profile.suspicionThreshold()) {
                    setState(AwarenessState.SUSPICIOUS, false);
                } else {
                    setState(AwarenessState.UNAWARE, false);
                }
            }
        }
    }

    /** Sight per second of the player, before alertness; 0 when out of range, behind, or blocked. */
    private float vision(Vector3f selfPos, Vector3f playerPos, float dist, float lightAtPlayer, float effRange) {
        if (dist >= effRange) return 0f;
        float angle = angleFactor(selfPos, playerPos);
        if (angle <= 0f || !lineOfSight(selfPos, playerPos)) return 0f;
        float lightFactor = Math.max(lightAtPlayer, profile.darkVisionFloor());
        float v = profile.sightGain() * (1f - dist / effRange) * angle * lightFactor;
        return players.nearestPlayerStealthed(selfPos) ? v * STEALTHED_VISION_MULT : v;
    }

    /** Vision range at {@code light}: the profile's, stretched toward its dark multiplier as light falls. */
    private float effectiveRange(float lightAtPlayer) {
        float darkness = 1f - Math.max(0f, Math.min(1f, lightAtPlayer));
        return profile.visionRange() * (1f + (profile.darkRangeMult() - 1f) * darkness);
    }

    /** 1 inside the forward cone, the side factor out to the side limit, 0 behind. */
    private float angleFactor(Vector3f selfPos, Vector3f playerPos) {
        float dx = playerPos.x - selfPos.x;
        float dz = playerPos.z - selfPos.z;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01f) return 1f; // on top of us
        // Forward from yaw, honoring the entity type's model yaw offset
        // (matches LivingEntity.faceDirection and Steering).
        Vector3f forward = self.getForwardDirection();
        float cos = (forward.x * dx + forward.z * dz) / len;
        float deg = (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, cos))));
        if (deg <= profile.fovDeg() * 0.5f) return 1f;
        if (deg <= PerceptionProfile.SIDE_LIMIT_DEG) return profile.sideVisionFactor();
        return 0f;
    }

    /** Whether nothing solid stands between the mob's eye and the player's chest. */
    private boolean lineOfSight(Vector3f selfPos, Vector3f playerPos) {
        Vector3f eye = new Vector3f(selfPos.x, selfPos.y + self.getType().getHeight() * 0.9f, selfPos.z);
        Vector3f dir = new Vector3f(playerPos.x, playerPos.y + PlayerConstants.PLAYER_HEIGHT * 0.5f, playerPos.z)
                .sub(eye);
        float losDist = dir.length();
        if (losDist < 0.0001f) return true;
        dir.div(losDist);
        RaycastEngine ray = sightRay();
        return ray == null || ray.distanceToFirstSolid(eye, dir, losDist) >= losDist;
    }

    /**
     * The one-off sounds made since the last tick: returns the meter bump (before alertness) of
     * the loudest one heard, and leaves its position in {@link #noiseScratch}. The loudest, not the
     * sum: a swing and the death it caused are one commotion, not two.
     */
    private float hearEvents(Vector3f selfPos) {
        NoiseBus bus = bus();
        if (bus == null) return 0f;
        long latest = bus.latest();
        if (heardUntil == Long.MIN_VALUE) {
            heardUntil = latest; // a mob arriving in the world does not hear what came before it
            return 0f;
        }
        loudestHeard = 0f;
        bus.heardSince(heardUntil, e -> {
            float s = Loudness.strength(e.loudness(), selfPos.distance(e.position()), profile.hearingRange());
            if (s > loudestHeard) {
                loudestHeard = s;
                noiseScratch.set(e.position());
            }
        });
        heardUntil = latest;
        return profile.eventGain() * loudestHeard;
    }

    /** Beyond this nothing the player does can reach the mob's sight or movement hearing. */
    private float activeRadius() {
        return Math.max(profile.visionRange() * Math.max(1f, profile.darkRangeMult()), profile.hearingRange())
                + ACTIVE_MARGIN;
    }

    // ── Outside triggers ──────────────────────────────────────────────────────

    /** A player hit this mob: it is alerted outright, knowing where the blow came from. */
    public void onAttacked(Vector3f attackerPosition) {
        if (!self.isAlive()) return;
        meter = PerceptionProfile.METER_MAX;
        remember(attackerPosition != null ? attackerPosition : self.getPosition());
        lostTrackTimer = 0f;
        setState(AwarenessState.ALERTED, false);
    }

    /**
     * A packmate shouted that it saw the player at {@code lastKnown}. Alerted outright if this mob
     * can see the player itself; otherwise suspicious and heading there (a searching mob just
     * keeps looking, pointed at the new spot). Already alerted mobs ignore it. A shout-made alert
     * does not shout again, so one sighting cannot chain across the map.
     */
    public void hearShout(Vector3f lastKnown) {
        if (!self.isAlive() || state == AwarenessState.ALERTED) return;
        Vector3f selfPos = self.getPosition();
        Vector3f playerPos = players.nearestPlayer(selfPos, playerScratch);
        if (playerPos != null && canSeeNow(selfPos, playerPos)) {
            meter = PerceptionProfile.METER_MAX;
            remember(playerPos);
            lostTrackTimer = 0f;
            setState(AwarenessState.ALERTED, true);
            return;
        }
        remember(lastKnown);
        if (state == AwarenessState.SEARCHING) {
            memoryRemaining = profile.memorySeconds();
        } else {
            meter = Math.max(meter, profile.suspicionThreshold());
            setState(AwarenessState.SUSPICIOUS, true);
        }
    }

    private boolean canSeeNow(Vector3f selfPos, Vector3f playerPos) {
        float lightAtPlayer = light.lightAt(self.getWorld(), playerPos);
        return vision(selfPos, playerPos, selfPos.distance(playerPos), lightAtPlayer,
                effectiveRange(lightAtPlayer)) > 0f;
    }

    /**
     * The server's state for this mob's client shadow, which never perceives anything itself.
     * Listeners are not told: replication is a view of changes already announced on the server.
     */
    public void applyReplicatedState(AwarenessState replicated) {
        state = replicated;
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private void setState(AwarenessState to, boolean byShout) {
        if (to == state) return;
        AwarenessState from = state;
        state = to;
        if (to == AwarenessState.ALERTED) {
            lostTrackTimer = 0f;
            // Becoming alerted shakes off the flat-footed opener window.
            self.removeStatusEffect(StatusEffectType.FLAT_FOOTED);
        }
        for (AwarenessListener listener : List.copyOf(listeners)) {
            listener.onStateChanged(self, from, to, byShout);
        }
    }

    private void shoutOnAlert(LivingEntity mob, AwarenessState from, AwarenessState to, boolean byShout) {
        if (to == AwarenessState.ALERTED && !byShout && profile.shoutRange() > 0f && hasLastKnown) {
            shouts.shout(self, new Vector3f(lastKnownPos), profile.shoutRange());
        }
    }

    private static float clampMeter(float value) {
        return Math.max(0f, Math.min(PerceptionProfile.METER_MAX, value));
    }

    private NoiseBus bus() {
        if (fixedBus != null) return fixedBus;
        World world = self.getWorld();
        return world != null ? world.noiseBus() : null;
    }

    /**
     * A ray marcher over the MOB's world — never the client player's, whose engine walks the
     * render world from the main thread. Only {@code distanceToFirstSolid} is used, which needs
     * nothing but the world. Null without a world: nothing there to block the view.
     */
    private RaycastEngine sightRay() {
        World world = self.getWorld();
        if (world == null) {
            return null;
        }
        if (sightRay == null || sightRayWorld != world) {
            sightRay = new RaycastEngine(null, null, world);
            sightRayWorld = world;
        }
        return sightRay;
    }

    // ── Memory ────────────────────────────────────────────────────────────────

    private void remember(Vector3f position) {
        lastKnownPos.set(position);
        hasLastKnown = true;
    }

    /**
     * Where the player was last perceived — seen, heard, or reported by a packmate's shout — or
     * {@code null} if this mob has nothing to go on.
     *
     * <p>Acting on it belongs to the behaviours
     * ({@link com.stonebreak.mobs.entities.ai.behavior.PursuePlayerBehavior},
     * {@link com.stonebreak.mobs.entities.ai.behavior.SearchBehavior}): this class decides
     * <em>whether</em> the mob has noticed the player, the behaviours decide what to do about it,
     * and navigation works out how to get there.
     */
    public Vector3f lastKnownPlayerPosition(Vector3f out) {
        return hasLastKnown ? out.set(lastKnownPos) : null;
    }
}
