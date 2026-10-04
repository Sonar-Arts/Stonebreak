package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.LivingEntity;
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
     * Turns the mob toward the nearest player without moving it.
     *
     * @return degrees still left to turn, or {@link Float#MAX_VALUE} when there is no player
     */
    public float facePlayer(float turnSpeedDegPerSec) {
        Vector3f player = nearestPlayer();
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

    /** Whether the nearest player is in clear sight from {@code eye}. */
    public boolean canSeeNearestPlayer(Vector3f eye) {
        return players.canSeeNearestPlayer(entity.getPosition(), eye);
    }

    /** Lands a blow from this mob on the nearest player; see {@link PlayerLocator#hurtNearestPlayer}. */
    public boolean hurtNearestPlayer(float amount, float knockback) {
        return players.hurtNearestPlayer(entity.getPosition(), amount, knockback);
    }

    /** Whether the nearest player is sprinting — skittish mobs notice that from further off. */
    public boolean nearestPlayerSprinting() {
        return players.nearestPlayerSprinting(entity.getPosition());
    }
}
