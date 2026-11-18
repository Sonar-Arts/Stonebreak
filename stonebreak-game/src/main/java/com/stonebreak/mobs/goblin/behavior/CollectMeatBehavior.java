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
 * goblin's pockets, which spill again when it dies. A drop it gets no closer to — down a ledge, across
 * water — is let go after {@link #STALL_SECONDS} and left alone for {@link #IGNORE_SECONDS}, or the
 * goblin would stand staring at it forever instead of going home.
 */
public final class CollectMeatBehavior implements Behavior {

    public static final int PRIORITY = 25;

    static final float SCAN_RADIUS = 12.0f;
    static final float SCAN_SECONDS = 1.0f;
    static final float PICKUP_RANGE = 1.2f;
    private static final float SPEED = 1.1f;
    /** Seconds without getting a block closer before a drop counts as out of reach. */
    static final float STALL_SECONDS = 8.0f;
    /** ...and how long an out-of-reach drop is then ignored. */
    static final float IGNORE_SECONDS = 60.0f;

    private final Goblin goblin;
    private final NearbyEntities nearby;

    private ItemDrop target;
    private float scanTimer;
    private float best;
    private float stall;
    private float clock;
    /** Drops it could not reach, and when it may try them again. */
    private final java.util.Map<ItemDrop, Float> unreachable = new java.util.HashMap<>();

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
        clock += context.deltaTime();
        if (scanTimer <= 0.0f) {
            scanTimer = SCAN_SECONDS;
            unreachable.values().removeIf(until -> until < clock);
            target = findMeat();
        }
        return isWanted(target);
    }

    @Override
    public void start(AiContext context) {
        best = Float.MAX_VALUE;
        stall = 0.0f;
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        return isWanted(target);
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        float distance = context.distanceTo(target.getPosition());
        if (distance > PICKUP_RANGE) {
            if (distance < best - 1.0f) {
                best = distance;
                stall = 0.0f;
            } else if ((stall += deltaTime) > STALL_SECONDS) {
                unreachable.put(target, clock + IGNORE_SECONDS); // out of reach: leave it
                target = null;
                return;
            }
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
            if (e instanceof ItemDrop drop && isWanted(drop) && !unreachable.containsKey(drop)) {
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
