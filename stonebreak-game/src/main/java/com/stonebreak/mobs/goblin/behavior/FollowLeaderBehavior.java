package com.stonebreak.mobs.goblin.behavior;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import com.stonebreak.mobs.entities.ai.behavior.Behavior;
import com.stonebreak.mobs.goblin.Goblin;

import java.util.EnumSet;

/** Keeps a pack together: a member that strays too far from its leader heads back toward it. */
public final class FollowLeaderBehavior implements Behavior {

    public static final int PRIORITY = 90;

    /** Further than this from the leader, rejoin it... */
    static final float STRAY_DISTANCE = 8.0f;
    /** ...until this close. */
    static final float REJOINED_DISTANCE = 4.0f;
    private static final float SPEED = 1.0f;

    private final Goblin goblin;

    public FollowLeaderBehavior(Goblin goblin) {
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
        return distanceToLeader(context) > STRAY_DISTANCE;
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        return distanceToLeader(context) > REJOINED_DISTANCE;
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        Goblin leader = goblin.pack().leader();
        if (leader != null) {
            context.nav().moveTo(leader.getPosition(), REJOINED_DISTANCE * 0.75f, SPEED);
        }
    }

    @Override
    public void stop(AiContext context) {
        context.nav().stop();
    }

    @Override
    public MobBehaviorState animationState() {
        return MobBehaviorState.WANDERING;
    }

    @Override
    public String debugName() {
        return "FollowLeader";
    }

    /** Zero for the leader itself, or a goblin with no pack-mates. */
    private float distanceToLeader(AiContext context) {
        Goblin leader = goblin.pack().leader();
        return leader == null || leader == goblin ? 0.0f : context.distanceTo(leader.getPosition());
    }
}
