package com.stonebreak.mobs.wisp;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import org.joml.Vector3f;

/**
 * Peek-a-boo: from behind a tree the victim cannot see past, the Wisp leans out, laughs, and waits
 * to be noticed. The moment the victim looks at it, it jerks back into cover — and, out of sight
 * with their attention fixed on the wrong tree, it has the perfect moment to vanish
 * ({@link WispMind#armPerfectMoment}). Not for a victim already hunting for it: they would only
 * follow it.
 */
final class WispPeekABooBehavior extends WispBehavior {

    private enum Phase { HIDE, LEAN, JERK, DONE }

    static final float SEARCH_RADIUS = 15f;
    static final float HIDE_SECONDS = 8f;
    static final float HIDE_SPEED = 1.2f;
    /** How long it leans out laughing before it gives up on being noticed. */
    static final float LEAN_SECONDS = 8f;
    static final float LEAN_SPEED = 0.5f;
    static final float JERK_SECONDS = 0.45f;
    static final float JERK_SPEED = 2.2f;
    /** Before trying again after finding no tree to peek from. */
    static final float RETRY_SECONDS = 5f;

    private Phase phase = Phase.DONE;
    private float elapsed;
    private CoverFinder.PeekSetup setup;
    private boolean leaned;
    private boolean noticed;
    private float retryAt;
    private final Vector3f scratch = new Vector3f();

    WispPeekABooBehavior(Wisp wisp) {
        super(wisp);
    }

    @Override
    public int priority() {
        return 4;
    }

    @Override
    public boolean canStart(AiContext context) {
        WispSenses senses = senses();
        return mind().mode() == WispMind.Mode.STALK && mind().peekReady() && wisp.getAge() >= retryAt
                && !tracked() && senses.hasVictim() && senses.victimLook() != null && !wisp.isVeiled() && !senses.inView()
                && senses.distance() >= tuning().peekBandMin() - 2f
                && senses.distance() <= tuning().peekBandMax() + 4f;
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        return phase != Phase.DONE && mind().mode() == WispMind.Mode.STALK && senses().hasVictim();
    }

    @Override
    public void start(AiContext context) {
        elapsed = 0f;
        leaned = false;
        noticed = false;
        WispSenses senses = senses();
        setup = wisp.cover().findPeek(wisp.getPosition(), SEARCH_RADIUS, senses.victimEye(),
                tuning().peekBandMin(), tuning().peekBandMax());
        phase = setup != null ? Phase.HIDE : Phase.DONE;
        if (setup == null) {
            retryAt = wisp.getAge() + RETRY_SECONDS;
        }
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        elapsed += deltaTime;
        switch (phase) {
            case HIDE -> {
                // Seen on the way to its tree: the trick is spoiled before it starts.
                if (senses().inView() || elapsed >= HIDE_SECONDS) {
                    retryAt = wisp.getAge() + RETRY_SECONDS;
                    phase = Phase.DONE;
                    return;
                }
                goTo(context, setup.hide(), 0.4f, HIDE_SPEED);
                if (wisp.getPosition().distance(setup.hide()) <= 0.6f) {
                    context.nav().stop();
                    phase = Phase.LEAN;
                    elapsed = 0f;
                    leaned = true;
                    wisp.emit("laugh");
                }
            }
            case LEAN -> {
                context.faceTarget(WATCH_TURN_SPEED);
                if (!stepToward(context, setup.peek(), LEAN_SPEED)) {
                    context.steering().stopMoving();
                }
                if (senses().watched()) {
                    noticed = true;
                    phase = Phase.JERK;
                    elapsed = 0f;
                } else if (elapsed >= LEAN_SECONDS) {
                    phase = Phase.DONE;
                }
            }
            case JERK -> {
                stepToward(context, setup.hide(), JERK_SPEED);
                context.faceTarget(WATCH_TURN_SPEED);
                if (elapsed >= JERK_SECONDS) {
                    context.steering().stopMoving();
                    phase = Phase.DONE;
                }
            }
            case DONE -> { }
        }
    }

    /** Glides straight at {@code point}; false once there. Short hops beside a trunk need no route. */
    private boolean stepToward(AiContext context, Vector3f point, float speed) {
        Vector3f toward = scratch.set(point).sub(wisp.getPosition());
        toward.y = 0f;
        if (toward.lengthSquared() < 0.01f) {
            return false;
        }
        Vector3f heading = toward.normalize();
        Vector3f facing = wisp.getRotation(); // keep facing the victim while sidestepping
        context.steering().steerAlong(heading, speed, context.deltaTime(), false);
        wisp.setYaw(facing.y);
        return true;
    }

    @Override
    public void stop(AiContext context) {
        if (leaned) {
            mind().frighten(noticed ? tuning().peekGain() : tuning().peekIgnoredGain());
            if (noticed) {
                mind().armPerfectMoment();
            }
            mind().usedPeek(context.random());
        }
        phase = Phase.DONE;
        context.nav().stop();
    }

    @Override
    public MobBehaviorState animationState() {
        return switch (phase) {
            case LEAN -> MobBehaviorState.PEEK;
            case JERK -> MobBehaviorState.JERK_BACK;
            case HIDE, DONE -> MobBehaviorState.WANDERING;
        };
    }

    @Override
    public String debugName() {
        return "PeekABoo";
    }
}
