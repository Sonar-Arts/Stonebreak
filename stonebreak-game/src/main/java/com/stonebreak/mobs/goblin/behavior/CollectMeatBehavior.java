package com.stonebreak.mobs.goblin.behavior;

import com.stonebreak.items.FoodRules;
import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.ItemDrop;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import com.stonebreak.mobs.entities.ai.behavior.Behavior;
import com.stonebreak.mobs.goblin.Goblin;
import com.stonebreak.mobs.goblin.pack.NearbyEntities;

import java.util.EnumSet;

/**
 * Picks up meat lying about — the pack's kills, or anything a careless player dropped — into the
 * goblin's pockets, which spill again when it dies.
 */
public final class CollectMeatBehavior implements Behavior {

    public static final int PRIORITY = 25;

    static final float SCAN_RADIUS = 12.0f;
    static final float SCAN_SECONDS = 1.0f;
    static final float PICKUP_RANGE = 1.2f;
    private static final float SPEED = 1.1f;

    private final Goblin goblin;
    private final NearbyEntities nearby;

    private ItemDrop target;
    private float scanTimer;

    public CollectMeatBehavior(Goblin goblin, NearbyEntities nearby) {
        this.goblin = goblin;
        this.nearby = nearby;
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
        scanTimer -= context.deltaTime();
        if (scanTimer <= 0.0f) {
            scanTimer = SCAN_SECONDS;
            target = findMeat();
        }
        return isWanted(target);
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        return isWanted(target);
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        if (context.distanceTo(target.getPosition()) > PICKUP_RANGE) {
            context.nav().moveTo(target.getPosition(), PICKUP_RANGE * 0.5f, SPEED);
            return;
        }
        int count = target.getStackCount();
        ItemStack stack = target.getItemStack();
        ItemStack left = goblin.inventory().add(new ItemStack(stack.getItem(), count, stack.getState()));
        if (left == null) {
            target.setAlive(false);
        } else {
            target.setStackCount(left.getCount());
        }
        target = null;
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
        return "CollectMeat";
    }

    private boolean isWanted(ItemDrop drop) {
        return drop != null && drop.isAlive() && drop.getItemStack() != null
                && FoodRules.isMeat(drop.getItemStack().getItem())
                && goblin.inventory().hasRoomFor(drop.getItemStack());
    }

    private ItemDrop findMeat() {
        ItemDrop best = null;
        float bestDistance = Float.MAX_VALUE;
        for (Entity e : nearby.within(goblin.getPosition(), SCAN_RADIUS)) {
            if (e instanceof ItemDrop drop && isWanted(drop)) {
                float d = drop.getPosition().distanceSquared(goblin.getPosition());
                if (d < bestDistance) {
                    bestDistance = d;
                    best = drop;
                }
            }
        }
        return best;
    }
}
