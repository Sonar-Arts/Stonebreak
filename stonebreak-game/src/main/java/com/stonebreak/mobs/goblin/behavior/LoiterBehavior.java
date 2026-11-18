package com.stonebreak.mobs.goblin.behavior;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import com.stonebreak.mobs.entities.ai.behavior.Behavior;
import com.stonebreak.mobs.entities.ai.nav.PathAgent;
import com.stonebreak.mobs.goblin.Goblin;
import com.stonebreak.mobs.goblin.camp.GoblinCamp;
import com.stonebreak.mobs.goblin.pack.GoblinPack;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.EnumSet;
import java.util.List;

/**
 * A camp goblin at rest mills about its camp: it ambles to a spot of the camp's home ground
 * ({@link GoblinCamp#homeGround} — floor its routes reach from the fire, level with it), stands
 * about a while, and picks another. It takes the place of plain wandering for camp goblins, which
 * would drop them down ledge after ledge into caves they cannot climb back out of.
 */
public final class LoiterBehavior implements Behavior {

    /** Below FollowLeader (a pack-mate keeps up with its leader first), above plain wandering. */
    public static final int PRIORITY = 95;

    private static final float SPEED = 0.7f;
    private static final float ARRIVAL_RADIUS = 1.0f;
    /** Give up on one spot after this long, however the walk is going. */
    private static final float MAX_WALK_SECONDS = 10.0f;
    private static final float MIN_REST_SECONDS = 2.0f;
    private static final float MAX_REST_SECONDS = 7.0f;

    private final Goblin goblin;
    private final Vector3f target = new Vector3f();
    private float walking;
    private float resting;

    public LoiterBehavior(Goblin goblin) {
        this.goblin = goblin;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public EnumSet<Flag> flags() {
        return EnumSet.of(Flag.MOVE);
    }

    @Override
    public boolean canStart(AiContext context) {
        GoblinPack pack = goblin.pack();
        return pack.camp() != null && pack.task() == GoblinPack.Task.ROAM && !pack.camp().homeGround().isEmpty();
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        return canStart(context);
    }

    @Override
    public void start(AiContext context) {
        pickSpot(context);
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        if (resting > 0.0f) {
            resting -= deltaTime;
            if (resting <= 0.0f) {
                pickSpot(context);
            }
            return;
        }
        walking += deltaTime;
        PathAgent.Status status = context.nav().status();
        if (walking > MAX_WALK_SECONDS || status == PathAgent.Status.ARRIVED
                || status == PathAgent.Status.NO_PATH || status == PathAgent.Status.STUCK) {
            context.nav().stop();
            resting = context.randomBetween(MIN_REST_SECONDS, MAX_REST_SECONDS);
        }
    }

    private void pickSpot(AiContext context) {
        List<Vector3i> ground = goblin.pack().camp().homeGround();
        Vector3i cell = ground.get(context.random().nextInt(ground.size()));
        target.set(cell.x + 0.5f, cell.y, cell.z + 0.5f);
        walking = 0.0f;
        resting = 0.0f;
        context.nav().moveTo(target, ARRIVAL_RADIUS, SPEED);
    }

    @Override
    public void stop(AiContext context) {
        context.nav().stop();
    }

    @Override
    public MobBehaviorState animationState() {
        return resting > 0.0f ? MobBehaviorState.IDLE : MobBehaviorState.WANDERING;
    }

    @Override
    public String debugName() {
        return "Loiter";
    }
}
