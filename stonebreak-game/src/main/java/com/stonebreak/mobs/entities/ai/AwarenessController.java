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
import com.stonebreak.mobs.entities.ai.perception.StealthSkill;
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
 * Every player in range is perceived, and the one detected fastest is the one the mob tracks
 * ({@link #targetPlayerId()}): a quiet player close by does not hide a loud one further off. A
 * player's Stealth skill slows their detection ({@link StealthSkill}). With nothing detected the
 * meter drains. Being hit by a player alerts the mob outright; an alerted mob shouts (at most once
 * per {@link #SHOUT_COOLDOWN_SECONDS}), and packmates in range turn suspicious (or alerted, if they
 * can see the player themselves). Each state change is announced to {@link AwarenessListener}s.
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

    /** An alert shouts at most this often, so a mob flickering between ALERTED and SEARCHING is not a siren. */
    static final float SHOUT_COOLDOWN_SECONDS = 8f;

    /** A hit's attacker is matched to a player standing within this of where the blow came from. */
    private static final float ATTACKER_MATCH_RADIUS = 4f;

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
    private float shoutCooldown;
    /** The player this mob is tracking: the one last detected, or {@link PlayerLocator#NEAREST}. */
    private int targetId = PlayerLocator.NEAREST;
    /** The last sound number this mob has heard; {@code Long.MIN_VALUE} until its first tick. */
    private long heardUntil = Long.MIN_VALUE;
    private PerceptionSnapshot snapshot = PerceptionSnapshot.NONE;

    private final Vector3f lastKnownPos = new Vector3f();
    private boolean hasLastKnown;
    private final Vector3f noiseScratch = new Vector3f();
    private float loudestHeard;

    /** The player detected fastest this perception tick, filled by {@link #sense}. */
    private PlayerLocator.SensedPlayer best;
    private float bestRate;
    private float bestVision;
    private float bestHearing;
    private float bestLight;
    private float bestRange;

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

    /**
     * The player this mob is after — the one it last detected, was hit by, or was shouted about —
     * for the behaviours to chase and strike; {@link PlayerLocator#NEAREST} when it has nobody.
     */
    public int targetPlayerId() { return targetId; }

    /**
     * Whether the mob has no idea it is being hunted: UNAWARE, or SUSPICIOUS without knowing what
     * of. A kill on such a mob is a stealth kill, and makes little sound (#321).
     */
    public boolean caughtUnaware() {
        return state == AwarenessState.UNAWARE || state == AwarenessState.SUSPICIOUS;
    }

    /** Subscribes to state changes. */
    public void addListener(AwarenessListener listener) {
        listeners.add(listener);
    }

    // ── Perception ────────────────────────────────────────────────────────────

    /** Advances the clock, running a perception tick every {@link #PERCEPTION_PERIOD}. */
    public void update(float deltaTime) {
        if (!self.isAlive()) return;
        shoutCooldown = Math.max(0f, shoutCooldown - deltaTime);
        sinceLastPerception += deltaTime;
        if (sinceLastPerception + 1e-6f >= PERCEPTION_PERIOD) {
            perceive(sinceLastPerception);
            sinceLastPerception = 0f;
        }
    }

    private void perceive(float dt) {
        Vector3f selfPos = self.getPosition();
        best = null;
        bestRate = -1f;
        players.forEachPlayer(selfPos, activeRadius(), this::sense);

        float vision = best != null ? bestVision : 0f;
        float hearing = best != null ? bestHearing : 0f;
        float impulse = hearEvents(selfPos);

        float alertness = profile.alertness().of(state);
        float rate = Math.max(0f, bestRate) * alertness;
        boolean detected = rate > 0f || impulse > 0f;
        if (detected) {
            meter += rate * dt + impulse * alertness;
        } else {
            rate = -profile.decayPerSec();
            meter += rate * dt;
        }
        meter = clampMeter(meter);

        if (vision > 0f || hearing > 0f) {
            targetId = best.id();
            remember(best.position());
        } else if (impulse > 0f) {
            remember(noiseScratch);
        }

        advanceState(dt, vision > 0f, detected);
        snapshot = new PerceptionSnapshot(meter, rate, vision, hearing,
                best != null ? bestLight : 1f, best != null ? bestRange : 0f,
                state == AwarenessState.SEARCHING ? memoryRemaining : 0f);
    }

    /**
     * Perceives one player, keeping them as {@link #best} if they are detected faster than anyone
     * so far — or, while nobody is detected at all, if they are the closest, so the overlay still
     * shows the light and range at someone.
     */
    private void sense(PlayerLocator.SensedPlayer player) {
        Vector3f selfPos = self.getPosition();
        float dist = selfPos.distance(player.position());
        float lightAtPlayer = light.lightAt(self.getWorld(), player.position());
        float effRange = effectiveRange(lightAtPlayer);
        float vision = vision(selfPos, player.position(), dist, lightAtPlayer, effRange, player.stealthed());
        float hearing = profile.hearGain() * Loudness.strength(player.loudness(), dist, profile.hearingRange());
        float rate = (vision + hearing) * StealthSkill.factor(player.stealthSkill());
        boolean better = rate > bestRate
                || (rate == bestRate && best != null && dist < selfPos.distance(best.position()));
        if (better) {
            best = player;
            bestRate = rate;
            bestVision = vision;
            bestHearing = hearing;
            bestLight = lightAtPlayer;
            bestRange = effRange;
        }
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
                        targetId = PlayerLocator.NEAREST;
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

    /** Sight per second of a player, before alertness; 0 when out of range, behind, or blocked. */
    private float vision(Vector3f selfPos, Vector3f playerPos, float dist, float lightAtPlayer, float effRange,
                         boolean stealthed) {
        if (dist >= effRange) return 0f;
        float angle = angleFactor(selfPos, playerPos);
        if (angle <= 0f || !lineOfSight(selfPos, playerPos)) return 0f;
        float lightFactor = Math.max(lightAtPlayer, profile.darkVisionFloor());
        float v = profile.sightGain() * (1f - dist / effRange) * angle * lightFactor;
        return stealthed ? v * STEALTHED_VISION_MULT : v;
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
            if (e.source() == self) {
                return; // a mob does not hear itself
            }
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

    /**
     * A player hit this mob: it is alerted outright, knowing where the blow came from, and goes
     * after whoever stands there.
     */
    public void onAttacked(Vector3f attackerPosition) {
        if (!self.isAlive()) return;
        meter = PerceptionProfile.METER_MAX;
        remember(attackerPosition != null ? attackerPosition : self.getPosition());
        if (attackerPosition != null) {
            targetId = playerAt(attackerPosition);
        }
        lostTrackTimer = 0f;
        setState(AwarenessState.ALERTED, false);
    }

    /**
     * A packmate shouted that it saw player {@code targetId} at {@code lastKnown}. Alerted outright
     * if this mob can see a player itself; otherwise suspicious and heading there (a searching mob
     * just keeps looking, pointed at the new spot). Already alerted mobs ignore it. A shout-made
     * alert does not shout again, so one sighting cannot chain across the map.
     */
    public void hearShout(Vector3f lastKnown, int targetId) {
        if (!self.isAlive() || state == AwarenessState.ALERTED) return;
        PlayerLocator.SensedPlayer seen = visiblePlayer();
        if (seen != null) {
            meter = PerceptionProfile.METER_MAX;
            this.targetId = seen.id();
            remember(seen.position());
            lostTrackTimer = 0f;
            setState(AwarenessState.ALERTED, true);
            return;
        }
        this.targetId = targetId;
        remember(lastKnown);
        if (state == AwarenessState.SEARCHING) {
            memoryRemaining = profile.memorySeconds();
        } else {
            meter = Math.max(meter, profile.suspicionThreshold());
            setState(AwarenessState.SUSPICIOUS, true);
        }
    }

    /** A player this mob can see right now, or null. */
    private PlayerLocator.SensedPlayer visiblePlayer() {
        Vector3f selfPos = self.getPosition();
        PlayerLocator.SensedPlayer[] seen = new PlayerLocator.SensedPlayer[1];
        players.forEachPlayer(selfPos, activeRadius(), p -> {
            float lightAtPlayer = light.lightAt(self.getWorld(), p.position());
            if (seen[0] == null && vision(selfPos, p.position(), selfPos.distance(p.position()), lightAtPlayer,
                    effectiveRange(lightAtPlayer), p.stealthed()) > 0f) {
                seen[0] = p;
            }
        });
        return seen[0];
    }

    /** The id of the player standing nearest {@code position}, or {@link PlayerLocator#NEAREST} if none is close. */
    private int playerAt(Vector3f position) {
        int[] id = {PlayerLocator.NEAREST};
        float[] bestSq = {Float.MAX_VALUE};
        players.forEachPlayer(position, ATTACKER_MATCH_RADIUS, p -> {
            float d = p.position().distanceSquared(position);
            if (d < bestSq[0]) {
                bestSq[0] = d;
                id[0] = p.id();
            }
        });
        return id[0];
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
        if (to == AwarenessState.ALERTED && !byShout && profile.shoutRange() > 0f && hasLastKnown
                && shoutCooldown <= 0f) {
            shoutCooldown = SHOUT_COOLDOWN_SECONDS;
            shouts.shout(self, new Vector3f(lastKnownPos), targetId, profile.shoutRange());
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
