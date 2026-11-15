package com.stonebreak.mobs.goblin.behavior;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import com.stonebreak.mobs.entities.ai.behavior.Behavior;
import com.stonebreak.mobs.goblin.Goblin;
import com.stonebreak.mobs.goblin.camp.GoblinCamp;
import com.stonebreak.mobs.goblin.pack.GoblinPack;
import org.joml.Vector3f;

import java.util.EnumSet;

/** Off the hunt, a camp goblin keeps near its fire, out of the sun in its cave. */
public final class StayHomeBehavior implements Behavior {

    public static final int PRIORITY = 85;

    /** ...until this close again. */
    static final float BACK_HOME = 4.0f;
    private static final float SPEED = 0.9f;

    private final Goblin goblin;
    private final Vector3f fire = new Vector3f();

    public StayHomeBehavior(Goblin goblin) {
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
        return goblin.pack().task() == GoblinPack.Task.ROAM && distanceHome(context) > GoblinCamp.HOME_RADIUS;
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        return goblin.pack().task() == GoblinPack.Task.ROAM && distanceHome(context) > BACK_HOME;
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        GoblinCamp camp = goblin.pack().camp();
        if (camp != null) {
            context.nav().moveTo(camp.fireCenter(fire), BACK_HOME * 0.75f, SPEED);
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
        return "StayHome";
    }

    /** Zero for a goblin with no camp. */
    private float distanceHome(AiContext context) {
        GoblinCamp camp = goblin.pack().camp();
        return camp == null ? 0.0f : context.distanceTo(camp.fireCenter(fire));
    }
}
