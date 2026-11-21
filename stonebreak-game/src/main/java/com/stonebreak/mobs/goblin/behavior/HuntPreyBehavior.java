package com.stonebreak.mobs.goblin.behavior;

import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import com.stonebreak.mobs.entities.ai.behavior.Behavior;
import com.stonebreak.mobs.entities.ai.behavior.CloseIn;
import com.stonebreak.mobs.goblin.Goblin;

import java.util.EnumSet;

/**
 * Runs down the pack's quarry to a working distance — reach for a brawler, bow range for an archer
 * — and faces it, so the attack behaviour can cut in. Below the chase after a player, so a goblin
 * that notices one drops the hunt; once it loses them, the hunt picks up where it left off.
 */
public final class HuntPreyBehavior implements Behavior {

    public static final int PRIORITY = 30;

    private final Goblin goblin;
    private final float holdDistance;
    private final float speedMultiplier;
    private final CloseIn closeIn = new CloseIn();

    public HuntPreyBehavior(Goblin goblin, float holdDistance, float speedMultiplier) {
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
        // Full pockets: nothing to gain from another kill.
        return goblin.pack().quarry() != null && goblin.inventory().hasFreeSlot();
    }

    @Override
    public void start(AiContext context) {
        closeIn.reset();
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        LivingEntity quarry = goblin.pack().quarry();
        if (quarry != null) {
            closeIn.toward(context, quarry.getPosition(), holdDistance, speedMultiplier);
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
        return "Hunt";
    }
}
