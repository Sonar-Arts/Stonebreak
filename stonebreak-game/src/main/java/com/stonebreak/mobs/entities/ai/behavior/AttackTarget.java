package com.stonebreak.mobs.entities.ai.behavior;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * What an attack behaviour swings or shoots at. {@link MeleeAttackBehavior} and
 * {@link RangedAttackBehavior} only know how to fight; this says <em>whom</em> — the player the
 * mob is alerted to ({@link #PLAYER}, the default), or something else such as a goblin's prey.
 *
 * <p>Every method is asked afresh each call, so a target may change between them: a
 * {@link #playerFirst} target turns from the prey to a player the moment the mob notices one.
 */
public interface AttackTarget {

    /** Whether there is something to fight right now. */
    boolean engaged(AiContext context);

    /** The target's feet, or {@code null} when there is none. Scratch: read it, do not retain it. */
    Vector3d position(AiContext context);

    /** Whether the target is in clear sight from {@code eye}. */
    boolean canSee(AiContext context, Vector3dc eye);

    /** Lands a blow on the target, shoving it {@code knockback} away; whether it counted. */
    boolean hurt(AiContext context, float amount, float knockback);

    /** Where to aim above the target's feet. */
    float aimHeight(AiContext context);

    /**
     * Whether fighting it is heard (#321). A swing at a player draws in mobs from out of shout
     * range; a goblin knifing a sheep must not set its own pack investigating.
     */
    boolean noisy(AiContext context);

    /** The player this mob is alerted to — what every hostile mob fought before targets existed. */
    AttackTarget PLAYER = new AttackTarget() {
        @Override
        public boolean engaged(AiContext context) {
            return context.alertedToPlayer();
        }

        @Override
        public Vector3d position(AiContext context) {
            return context.targetPlayer();
        }

        @Override
        public boolean canSee(AiContext context, Vector3dc eye) {
            return context.canSeeTarget(eye);
        }

        @Override
        public boolean hurt(AiContext context, float amount, float knockback) {
            return context.hurtTarget(amount, knockback);
        }

        @Override
        public float aimHeight(AiContext context) {
            return PlayerLocator.CHEST_HEIGHT;
        }

        @Override
        public boolean noisy(AiContext context) {
            return true;
        }
    };

    /** {@code first} while it is engaged, otherwise {@code then}. */
    static AttackTarget firstEngaged(AttackTarget first, AttackTarget then) {
        return either(c -> first.engaged(c), first, then);
    }

    /** The player while the mob is alerted to one, otherwise {@code fallback}. */
    static AttackTarget playerFirst(AttackTarget fallback) {
        return either(c -> PLAYER.engaged(c) && PLAYER.position(c) != null, PLAYER, fallback);
    }

    /** {@code first} while {@code useFirst} holds, otherwise {@code fallback}. */
    private static AttackTarget either(java.util.function.Predicate<AiContext> useFirst,
                                         AttackTarget first, AttackTarget fallback) {
        return new AttackTarget() {
            private AttackTarget pick(AiContext context) {
                return useFirst.test(context) ? first : fallback;
            }

            @Override
            public boolean engaged(AiContext context) {
                return pick(context).engaged(context);
            }

            @Override
            public Vector3d position(AiContext context) {
                return pick(context).position(context);
            }

            @Override
            public boolean canSee(AiContext context, Vector3dc eye) {
                return pick(context).canSee(context, eye);
            }

            @Override
            public boolean hurt(AiContext context, float amount, float knockback) {
                return pick(context).hurt(context, amount, knockback);
            }

            @Override
            public float aimHeight(AiContext context) {
                return pick(context).aimHeight(context);
            }

            @Override
            public boolean noisy(AiContext context) {
                return pick(context).noisy(context);
            }
        };
    }
}
