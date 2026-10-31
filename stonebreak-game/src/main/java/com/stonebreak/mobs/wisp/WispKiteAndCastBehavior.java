package com.stonebreak.mobs.wisp;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import org.joml.Vector3f;

/**
 * The attack: the Wisp keeps its distance — about {@link WispTuning#kiteDistance()} — behind a tree,
 * leans out to hurl a will-o'-wisp when it is ready, and ducks back. Too close and the break-melee
 * reaction takes over; too far and it drifts nearer, tree to tree.
 */
final class WispKiteAndCastBehavior extends WispBehavior {

    private enum Phase { POSITION, STEP_OUT, CAST }

    static final float SEARCH_RADIUS = 14f;
    static final float MOVE_SPEED = 1.5f;
    static final float REPLAN_SECONDS = 2f;
    static final float STEP_OUT_SECONDS = 1.5f;
    static final float CAST_SECONDS = 0.6f;
    static final float CAST_RELEASE = 0.35f;

    private Phase phase = Phase.POSITION;
    private float elapsed;
    private float castReadyAt;
    private float replanAt;
    private boolean released;
    private CoverFinder.PeekSetup post;
    private final Vector3f scratch = new Vector3f();

    WispKiteAndCastBehavior(Wisp wisp) {
        super(wisp);
    }

    @Override
    public int priority() {
        return 5;
    }

    @Override
    public boolean canStart(AiContext context) {
        return mind().mode() == WispMind.Mode.ATTACK && senses().hasVictim() && !wisp.isDissolving();
    }

    @Override
    public void start(AiContext context) {
        phase = Phase.POSITION;
        elapsed = 0f;
        post = null;
        replanAt = 0f;
        castReadyAt = Math.max(castReadyAt, wisp.getAge() + 1f);
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        elapsed += deltaTime;
        float age = wisp.getAge();
        WispSenses senses = senses();
        switch (phase) {
            case POSITION -> {
                float kite = tuning().kiteDistance();
                boolean offRange = senses.distance() < kite - 2f || senses.distance() > kite + 5f;
                if (age >= replanAt && (post == null || offRange)) {
                    replanAt = age + REPLAN_SECONDS;
                    post = wisp.cover().findPeek(wisp.getPosition(), SEARCH_RADIUS, senses.victimEye(),
                            kite - 1f, kite + 3f);
                }
                if (post != null && wisp.getPosition().distance(post.hide()) > 0.8f) {
                    goTo(context, post.hide(), 0.5f, MOVE_SPEED);
                } else {
                    context.nav().stop();
                    holdAndWatch(context);
                }
                if (age >= castReadyAt && senses.distance() <= tuning().castRange()) {
                    phase = Phase.STEP_OUT;
                    elapsed = 0f;
                    context.nav().stop();
                }
            }
            case STEP_OUT -> {
                context.faceTarget(WATCH_TURN_SPEED);
                Vector3f out = post != null ? post.peek() : null;
                if (out != null && wisp.getPosition().distance(out) > 0.2f) {
                    Vector3f heading = scratch.set(out).sub(wisp.getPosition());
                    heading.y = 0f;
                    float yaw = wisp.getYaw();
                    context.steering().steerAlong(heading.normalize(), MOVE_SPEED, deltaTime, false);
                    wisp.setYaw(yaw);
                } else {
                    context.steering().stopMoving();
                }
                if (context.canSeeTargetThroughLeaves(handPosition(scratch))) {
                    phase = Phase.CAST;
                    elapsed = 0f;
                    released = false;
                    context.steering().stopMoving();
                    wisp.emit("cast");
                } else if (elapsed >= STEP_OUT_SECONDS) {
                    // No clear throw from here: find another post and try again shortly.
                    post = null;
                    phase = Phase.POSITION;
                    castReadyAt = age + 1f;
                }
            }
            case CAST -> {
                context.steering().stopMoving();
                context.faceTarget(WATCH_TURN_SPEED);
                if (!released && elapsed >= CAST_RELEASE) {
                    released = true;
                    castOrb(context);
                }
                if (elapsed >= CAST_SECONDS) {
                    phase = Phase.POSITION;
                    elapsed = 0f;
                    castReadyAt = age + context.randomBetween(tuning().castIntervalMin(), tuning().castIntervalMax());
                }
            }
        }
    }

    @Override
    public void stop(AiContext context) {
        context.nav().stop();
        phase = Phase.POSITION;
    }

    @Override
    public MobBehaviorState animationState() {
        return switch (phase) {
            case CAST -> MobBehaviorState.CAST;
            case STEP_OUT -> MobBehaviorState.PEEK;
            case POSITION -> wisp.isMoving() ? MobBehaviorState.WANDERING : MobBehaviorState.IDLE;
        };
    }

    @Override
    public String debugName() {
        return "KiteAndCast";
    }
}
