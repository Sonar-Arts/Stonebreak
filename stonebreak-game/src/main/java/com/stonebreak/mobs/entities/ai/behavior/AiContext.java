package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.AwarenessController;
import com.stonebreak.mobs.entities.ai.nav.PathAgent;
import com.stonebreak.mobs.entities.ai.nav.Steering;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import java.util.Random;

/**
 * Everything a behaviour is allowed to reach: its mob, its navigation, a source of randomness, and
 * where the players are.
 *
 * <p>Passing this rather than letting behaviours reach for {@code Game} statics is what makes them
 * testable — a test builds a context over a stub mob with a seeded {@link Random} and
 * {@link PlayerLocator#NONE}, and the behaviour cannot tell the difference.
 *
 * <p>The context is per-mob and lives as long as the mob does; {@link #deltaTime()} is refreshed
 * each tick so a behaviour's {@code canStart} can express a per-second probability.
 */
public final class AiContext {

    private final LivingEntity entity;
    private final PathAgent nav;
    private final Random random;
    private final PlayerLocator players;

    private final Vector3f playerScratch = new Vector3f();
    private final Vector3f faceScratch = new Vector3f();
    private final Vector3f attacker = new Vector3f();
    private boolean attackerKnown;
    private float deltaTime;

    public AiContext(LivingEntity entity, PathAgent nav, Random random, PlayerLocator players) {
        this.entity = entity;
        this.nav = nav;
        this.random = random;
        this.players = players;
    }

    public LivingEntity entity() {
        return entity;
    }

    public PathAgent nav() {
        return nav;
    }

    public Steering steering() {
        return nav.steering();
    }

    public World world() {
        return entity.getWorld();
    }

    public Random random() {
        return random;
    }

    /** Seconds elapsed in the tick currently being processed. */
    public float deltaTime() {
        return deltaTime;
    }

    void setDeltaTime(float deltaTime) {
        this.deltaTime = deltaTime;
    }

    /** A uniform float in {@code [min, max)}. */
    public float randomBetween(float min, float max) {
        return min + random.nextFloat() * (max - min);
    }

    /**
     * The nearest player's position, or {@code null}. The returned vector is scratch owned by this
     * context — read it, do not retain it.
     */
    public Vector3f nearestPlayer() {
        return players.nearestPlayer(entity.getPosition(), playerScratch);
    }

    /** Distance to the nearest player, or {@link Float#MAX_VALUE} when there is none. */
    public float distanceToNearestPlayer() {
        Vector3f player = nearestPlayer();
        return player == null ? Float.MAX_VALUE : entity.getPosition().distance(player);
    }

    /**
     * The player this mob is after — the one it has locked onto ({@link LivingEntity#lockedTargetPlayerId}),
     * else the one its stealth awareness tracks (#321), else the nearest — or {@code null}. Scratch
     * owned by this context: read it, do not retain it.
     */
    public Vector3f targetPlayer() {
        return players.playerPosition(targetId(), entity.getPosition(), playerScratch);
    }

    /** Distance to {@link #targetPlayer()}, or {@link Float#MAX_VALUE} when there is none. */
    public float distanceToTarget() {
        Vector3f player = targetPlayer();
        return player == null ? Float.MAX_VALUE : entity.getPosition().distance(player);
    }

    /**
     * Turns the mob toward {@link #targetPlayer()} without moving it.
     *
     * @return degrees still left to turn, or {@link Float#MAX_VALUE} when there is no player
     */
    public float faceTarget(float turnSpeedDegPerSec) {
        Vector3f player = targetPlayer();
        if (player == null) {
            return Float.MAX_VALUE;
        }
        Vector3f toPlayer = faceScratch.set(player).sub(entity.getPosition());
        toPlayer.y = 0;
        if (toPlayer.lengthSquared() < 1.0e-6f) {
            return 0.0f;
        }
        steering().faceDirection(toPlayer, turnSpeedDegPerSec, deltaTime);
        return steering().yawErrorTo(toPlayer);
    }

    /** Whether {@link #targetPlayer()} is in clear sight from {@code eye}. */
    public boolean canSeeTarget(Vector3f eye) {
        return players.canSeePlayer(targetId(), entity.getPosition(), eye);
    }

    /** Lands a blow from this mob on {@link #targetPlayer()}; see {@link PlayerLocator#hurtPlayer}. */
    public boolean hurtTarget(float amount, float knockback) {
        return players.hurtPlayer(targetId(), entity.getPosition(), amount, knockback);
    }

    /**
     * Writes the direction {@link #targetPlayer()} is looking into {@code out}; see
     * {@link PlayerLocator#playerLook}.
     *
     * @return {@code out}, or {@code null} when that is not known
     */
    public Vector3f targetLook(Vector3f out) {
        return players.playerLook(targetId(), entity.getPosition(), out);
    }

    /** The players this mob can perceive. */
    public PlayerLocator players() {
        return players;
    }

    private int targetId() {
        int locked = entity.lockedTargetPlayerId();
        if (locked != PlayerLocator.NEAREST) {
            return locked;
        }
        AwarenessController awareness = entity.getAwareness();
        return awareness != null ? awareness.targetPlayerId() : PlayerLocator.NEAREST;
    }

    /**
     * Whether this mob knows the player is there to fight: always for a mob without stealth
     * awareness, only while ALERTED for one with it — an unaware goblin does not swing at a
     * stealthed player who wanders into reach (#321).
     */
    public boolean alertedToPlayer() {
        AwarenessController awareness = entity.getAwareness();
        return awareness == null || awareness.getState() == AwarenessController.AwarenessState.ALERTED;
    }

    /** Whether the nearest player is sprinting — skittish mobs notice that from further off. */
    public boolean nearestPlayerSprinting() {
        return players.nearestPlayerSprinting(entity.getPosition());
    }

    /**
     * Where the hit being reported came from, or {@code null} when its source did not say (a
     * status effect ticking, an unattributed projectile). Meaningful inside
     * {@link Behavior#onDamaged}; the returned vector is owned by this context.
     */
    public Vector3f attacker() {
        return attackerKnown ? attacker : null;
    }

    /**
     * Records the attacker of the hit about to be reported to the behaviours. Copied, never
     * retained: a damage source may pass a live position (cactus contact passes the mob's own).
     */
    public void setAttacker(Vector3f attackerPosition) {
        attackerKnown = attackerPosition != null;
        if (attackerKnown) {
            attacker.set(attackerPosition);
        }
    }
}
