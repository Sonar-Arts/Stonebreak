package com.stonebreak.mobs.goblin.behavior;

import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import com.stonebreak.mobs.entities.ai.behavior.Behavior;
import com.stonebreak.mobs.entities.ai.behavior.CloseIn;
import com.stonebreak.mobs.goblin.Goblin;

import java.util.EnumSet;

/**
 * While its pack is fighting a rival pack, a goblin closes in on the nearest rival to a working
 * distance; the attack behaviours do the fighting. Above the hunt — a fight comes before food —
 * and below the chase after a player, who comes before everything.
 */
public final class FightRivalsBehavior implements Behavior {

    public static final int PRIORITY = 20;

    private final Goblin goblin;
    private final float holdDistance;
    private final float speedMultiplier;
    private final CloseIn closeIn = new CloseIn();

    public FightRivalsBehavior(Goblin goblin, float holdDistance, float speedMultiplier) {
        this.goblin = goblin;
        this.holdDistance = holdDistance;
        this.speedMultiplier = speedMultiplier;
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
        return goblin.pack().nearestEnemy(goblin.getPosition()) != null;
    }

    @Override
    public void start(AiContext context) {
        closeIn.reset();
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        Goblin rival = goblin.pack().nearestEnemy(goblin.getPosition());
        if (rival != null) {
            closeIn.toward(context, rival.getPosition(), holdDistance, speedMultiplier);
        }
    }

    @Override
    public void stop(AiContext context) {
        context.nav().stop();
    }

    @Override
    public MobBehaviorState animationState() {
        return closeIn.holding() ? MobBehaviorState.IDLE : MobBehaviorState.WANDERING;
    }

    @Override
    public String debugName() {
        return "FightRivals";
    }
}
