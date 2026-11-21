package com.stonebreak.mobs.goblin.behavior;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.campfire.CampfireRegistry;
import com.stonebreak.blocks.campfire.CampfireState;
import com.stonebreak.items.FoodRules;
import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import com.stonebreak.mobs.entities.ai.behavior.Behavior;
import com.stonebreak.mobs.goblin.Goblin;
import com.stonebreak.mobs.goblin.camp.GoblinCamp;
import com.stonebreak.mobs.goblin.pack.GoblinPack;
import com.stonebreak.world.World;
import org.joml.Vector3d;

import java.util.EnumSet;

/**
 * Bringing the hunt home: a camp goblin carrying meat — the hunt over, or its pockets full —
 * goes back to the fire, puts raw meat on to cook, and stows the rest in the camp's stash. The
 * cooked meat hops off the fire, is picked up again (CollectMeatBehavior) and stowed next time.
 */
public final class TendCampBehavior implements Behavior {

    public static final int PRIORITY = 40;

    /** Close enough to the fire to use it. */
    static final float FIRE_REACH = 2.0f;
    private static final float SPEED = 1.0f;

    private final Goblin goblin;
    private final Vector3d fire = new Vector3d();
    private final WayHome wayHome = new WayHome();
    private boolean done;

    public TendCampBehavior(Goblin goblin) {
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
        return pack.camp() != null && carriesMeat()
                && (pack.task() == GoblinPack.Task.ROAM || !goblin.inventory().hasFreeSlot())
                && campCanTakeSome(context.world(), pack.camp());
    }

    /**
     * Whether anything it carries would go on the fire or into the stash. When both are full it
     * keeps carrying — the camp eats out of its pockets too — rather than walking to the fire and
     * back for nothing, over and over.
     */
    private boolean campCanTakeSome(World world, GoblinCamp camp) {
        CampfireRegistry fires = world != null ? world.getCampfireRegistry() : null;
        CampfireState fire = fires != null ? fires.get(camp.fire()) : null;
        boolean fireFree = fire != null && fire.hasFreeSpot();
        for (ItemStack s : goblin.inventory().slots()) {
            if (s == null || s.isEmpty() || !FoodRules.isMeat(s.getItem())) {
                continue;
            }
            boolean cooked = fires != null && !fires.isCookable(s.getItem());
            if ((fireFree && !cooked) || camp.stash().hasRoomFor(s)
                    || (cooked && camp.rackStash() != null && camp.rackStash().hasRoomFor(s))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean shouldContinue(AiContext context) {
        return !done && goblin.pack().camp() != null;
    }

    @Override
    public void start(AiContext context) {
        done = false;
        wayHome.reset();
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        GoblinCamp camp = goblin.pack().camp();
        World world = context.world();
        if (camp == null || world == null) {
            done = true;
            return;
        }
        camp.fireCenter(fire);
        if (context.distanceTo(fire) > FIRE_REACH) {
            if (wayHome.check(goblin, context, fire, deltaTime)) {
                done = true; // stranded: brought home, its meat into the stash
                return;
            }
            context.nav().moveTo(fire, FIRE_REACH * 0.75f, SPEED);
            return;
        }
        context.nav().stop();
        context.facePoint(fire, 360.0f);
        unload(world, camp);
        done = true;
    }

    /** Raw meat onto the fire while there is room; cooked meat onto the rack, then everything into the stash. */
    private void unload(World world, GoblinCamp camp) {
        CampfireRegistry fires = world.getCampfireRegistry();
        BlockPos at = camp.fire();
        for (ItemStack stack : goblin.inventory().takeAll()) {
            while (!stack.isEmpty() && fires.isCookable(stack.getItem()) && fires.cook(world, at, stack.getItem())) {
                stack.decrementCount(1);
            }
            if (!stack.isEmpty()) {
                ItemStack left = fires.isCookable(stack.getItem()) ? camp.stash().add(stack) : camp.storeCooked(stack);
                if (left != null) {
                    goblin.inventory().add(left); // the stash is full: keep carrying it
                }
            }
        }
    }

    private boolean carriesMeat() {
        for (ItemStack s : goblin.inventory().slots()) {
            if (s != null && !s.isEmpty() && FoodRules.isMeat(s.getItem())) {
                return true;
            }
        }
        return false;
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
        return "TendCamp";
    }
}
