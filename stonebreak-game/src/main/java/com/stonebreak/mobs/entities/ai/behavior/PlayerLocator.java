package com.stonebreak.mobs.entities.ai.behavior;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.ai.perception.Loudness;
import com.stonebreak.world.World;
import org.joml.Vector3f;

import java.util.function.Consumer;

/**
 * Where the nearest player is, as far as a behaviour is concerned.
 *
 * <p>A seam rather than a direct {@code Game.getPlayer()} call, for two reasons: behaviours become
 * testable without booting the game, and mob AI runs on the server tick thread, where the client's
 * player is both the wrong answer (it is only the host) and another thread's state. The
 * authoritative world is handed a locator over the connected roster by the integrated server
 * ({@code network.server.ServerPlayerLocator}); mobs reach it through {@link #ofWorld}.
 */
public interface PlayerLocator {

    /**
     * Writes the position of the player nearest {@code from} into {@code out}.
     *
     * @return {@code out} when a player was found, {@code null} when there is nobody to find
     */
    Vector3f nearestPlayer(Vector3f from, Vector3f out);

    /**
     * Whether the player nearest {@code from} is sprinting. Skittish mobs notice a running player
     * from further away than a walking one.
     */
    default boolean nearestPlayerSprinting(Vector3f from) {
        return false;
    }

    /**
     * Whether nothing solid stands between {@code eye} and the body of the player nearest
     * {@code from}. Open by default, so stubs and tests need not model terrain.
     */
    default boolean canSeeNearestPlayer(Vector3f from, Vector3f eye) {
        return true;
    }

    /**
     * Hurts the player nearest {@code from} and shoves them horizontally away from it. The seam a
     * hostile behaviour lands its blow through, so attack timing stays testable without a player.
     *
     * @param knockback horizontal push speed in blocks per second; 0 for none
     * @return whether a player was there to take the hit
     */
    default boolean hurtNearestPlayer(Vector3f from, float amount, float knockback) {
        return false;
    }

    /**
     * Hurts whichever player's body is within {@code margin} of {@code point} — a mob's arrow
     * striking. No knockback: an arrow stings, it does not shove.
     *
     * @return whether a player was there to take the hit
     */
    default boolean hurtPlayerAt(Vector3f point, float margin, float amount) {
        return false;
    }

    /**
     * How loud the nearest player's movement is right now, on the {@link Loudness} scale, for
     * stealth awareness. The default knows only whether the player sprints; the server's roster
     * also knows stealth, pace and dodges from the replicated movement flags.
     */
    default float nearestPlayerLoudness(Vector3f from) {
        return nearestPlayerSprinting(from) ? Loudness.SPRINT : 0.0f;
    }

    /** Whether the nearest player is fully stealthed — a smaller shape for mobs to pick out. */
    default boolean nearestPlayerStealthed(Vector3f from) {
        return false;
    }

    /**
     * One player as a mob's senses take them in (#321). {@code id} names them to the targeted calls
     * ({@link #playerPosition}, {@link #canSeePlayer}, {@link #hurtPlayer}); {@code position} is a
     * fresh vector the receiver may keep.
     *
     * @param loudness     movement loudness on the {@link Loudness} scale, Stealth skill included
     * @param stealthed    fully stealthed: a smaller shape to pick out
     * @param stealthSkill Stealth skill points, which slow detection
     */
    record SensedPlayer(int id, Vector3f position, float loudness, boolean stealthed, float stealthSkill) {}

    /** The id a locator that cannot tell players apart gives its one player: "whoever is nearest". */
    int NEAREST = -1;

    /**
     * Hands every player within {@code radius} of {@code from} to {@code sink}, so a mob perceives
     * each of them and not just the nearest — a quiet player close by must not hide a loud one
     * further off. The default knows only the nearest player, as {@link #NEAREST}.
     */
    default void forEachPlayer(Vector3f from, float radius, Consumer<SensedPlayer> sink) {
        Vector3f position = nearestPlayer(from, new Vector3f());
        if (position != null && position.distance(from) <= radius) {
            sink.accept(new SensedPlayer(NEAREST, position, nearestPlayerLoudness(from),
                    nearestPlayerStealthed(from), 0f));
        }
    }

    /**
     * Writes where player {@code id} is into {@code out}.
     *
     * @return {@code out}, or {@code null} once that player is gone; the default ignores the id and
     *         answers for the nearest player
     */
    default Vector3f playerPosition(int id, Vector3f from, Vector3f out) {
        return nearestPlayer(from, out);
    }

    /** Whether nothing solid stands between {@code eye} and player {@code id}'s body. */
    default boolean canSeePlayer(int id, Vector3f from, Vector3f eye) {
        return canSeeNearestPlayer(from, eye);
    }

    /** Hurts player {@code id}, shoved away from {@code from}; see {@link #hurtNearestPlayer}. */
    default boolean hurtPlayer(int id, Vector3f from, float amount, float knockback) {
        return hurtNearestPlayer(from, amount, knockback);
    }

    /** Height above a player's feet that ranged attackers aim at and sight on. */
    float CHEST_HEIGHT = 1.1f;

    /** Nobody to react to; for tests and for worlds with no players. */
    PlayerLocator NONE = (from, out) -> null;

    /**
     * Whoever the entity's own world says is around, resolved on every call: the connected roster
     * on the authoritative server world, nobody anywhere else. Resolved lazily because a mob is
     * built before its world is wired to a server, and because the world is the one thing that
     * knows which players share it.
     */
    static PlayerLocator ofWorld(Entity entity) {
        return new PlayerLocator() {
            @Override
            public Vector3f nearestPlayer(Vector3f from, Vector3f out) {
                return players().nearestPlayer(from, out);
            }

            @Override
            public boolean nearestPlayerSprinting(Vector3f from) {
                return players().nearestPlayerSprinting(from);
            }

            @Override
            public float nearestPlayerLoudness(Vector3f from) {
                return players().nearestPlayerLoudness(from);
            }

            @Override
            public boolean nearestPlayerStealthed(Vector3f from) {
                return players().nearestPlayerStealthed(from);
            }

            @Override
            public boolean canSeeNearestPlayer(Vector3f from, Vector3f eye) {
                return players().canSeeNearestPlayer(from, eye);
            }

            @Override
            public boolean hurtNearestPlayer(Vector3f from, float amount, float knockback) {
                return players().hurtNearestPlayer(from, amount, knockback);
            }

            @Override
            public boolean hurtPlayerAt(Vector3f point, float margin, float amount) {
                return players().hurtPlayerAt(point, margin, amount);
            }

            @Override
            public void forEachPlayer(Vector3f from, float radius, Consumer<SensedPlayer> sink) {
                players().forEachPlayer(from, radius, sink);
            }

            @Override
            public Vector3f playerPosition(int id, Vector3f from, Vector3f out) {
                return players().playerPosition(id, from, out);
            }

            @Override
            public boolean canSeePlayer(int id, Vector3f from, Vector3f eye) {
                return players().canSeePlayer(id, from, eye);
            }

            @Override
            public boolean hurtPlayer(int id, Vector3f from, float amount, float knockback) {
                return players().hurtPlayer(id, from, amount, knockback);
            }

            private PlayerLocator players() {
                World world = entity.getWorld();
                PlayerLocator players = world != null ? world.getPlayerLocator() : null;
                return players != null ? players : NONE;
            }
        };
    }
}
