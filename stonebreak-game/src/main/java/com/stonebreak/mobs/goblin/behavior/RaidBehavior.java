package com.stonebreak.mobs.goblin.behavior;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.container.ChestRegistry;
import com.stonebreak.blocks.container.ChestState;
import com.stonebreak.items.FoodRules;
import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import com.stonebreak.mobs.entities.ai.behavior.Behavior;
import com.stonebreak.mobs.goblin.Goblin;
import com.stonebreak.mobs.goblin.camp.GoblinCamp;
import com.stonebreak.mobs.goblin.pack.GoblinPack;
import com.stonebreak.world.SimBlockEdits;
import com.stonebreak.world.World;
import org.joml.Vector3d;

import java.util.EnumSet;

/**
 * A raiding pack marches on a rival camp's fire. Defenders it meets on the way are fought
 * (FightRivalsBehavior, which outranks this). At the fire, the leader takes what it can carry out
 * of the rival's stores — its chest, or the stash of a camp with no chest — and, if the camp has no
 * one left to defend it, puts its fire out. Then the raid is over and the pack goes home with the
 * spoils (TendCampBehavior stows them).
 */
public final class RaidBehavior implements Behavior {

    public static final int PRIORITY = 35;

    /** Close enough to the rival fire to loot it. */
    static final float AT_FIRE = 2.5f;
    /** How far from the fire the rival's chest may stand. */
    static final int CHEST_SEARCH = 2;
    private static final float SPEED = 1.1f;

    private final Goblin goblin;
    private final Vector3d target = new Vector3d();

    public RaidBehavior(Goblin goblin) {
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
        return goblin.pack().raidTarget() != null && context.world() != null;
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        GoblinPack pack = goblin.pack();
        BlockPos fire = pack.raidTarget();
        if (fire == null) {
            return;
        }
        target.set(fire.x() + 0.5, fire.y(), fire.z() + 0.5);
        if (context.distanceTo(target) > AT_FIRE) {
            context.nav().moveTo(target, AT_FIRE * 0.6f, SPEED);
            return;
        }
        context.nav().stop();
        if (pack.isLeader(goblin) && pack.enemy() == null) {
            plunder(context.world(), fire);
            pack.endRaid();
        }
    }

    /** Takes the rival's meat into the raiders' pockets; a camp with no defenders left loses its fire. */
    private void plunder(World world, BlockPos fire) {
        GoblinCamp rival = null;
        for (GoblinCamp c : world.goblinCamps().camps()) {
            if (c.fire().equals(fire)) {
                rival = c;
            }
        }
        for (Goblin raider : goblin.pack().members()) {
            if (rival != null) {
                takeMeat(rival.stash().slots(), raider);
                if (rival.rackStash() != null) {
                    takeMeat(rival.rackStash().slots(), raider); // the hanging meat goes too
                }
            }
            ItemStack[] chest = chestNear(world, fire);
            if (chest != null) {
                takeMeat(chest, raider);
            }
        }
        persistChests(world, fire);
        if (rival != null && rival.rack() != null) {
            world.getMeatRackRegistry().persist(world, rival.rack());
        }
        if (rival == null || rival.population() == 0) {
            if (world.getBlockAt(fire.x(), fire.y(), fire.z()) == BlockType.CAMPFIRE) {
                SimBlockEdits.set(world, fire.x(), fire.y(), fire.z(), BlockType.AIR); // the camp is no more
            }
        }
    }

    private static void takeMeat(ItemStack[] slots, Goblin raider) {
        for (int i = 0; i < slots.length && raider.inventory().hasFreeSlot(); i++) {
            ItemStack s = slots[i];
            if (s != null && !s.isEmpty() && FoodRules.isMeat(s.getItem())) {
                ItemStack left = raider.inventory().add(s.copy());
                if (left == null) {
                    s.setCount(0);
                } else {
                    s.setCount(left.getCount());
                }
            }
        }
    }

    private static ItemStack[] chestNear(World world, BlockPos fire) {
        ChestRegistry chests = world.getChestRegistry();
        if (chests == null) {
            return null;
        }
        for (int dx = -CHEST_SEARCH; dx <= CHEST_SEARCH; dx++) {
            for (int dz = -CHEST_SEARCH; dz <= CHEST_SEARCH; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int x = fire.x() + dx;
                    int y = fire.y() + dy;
                    int z = fire.z() + dz;
                    if (world.getBlockAt(x, y, z) == BlockType.CHEST) {
                        ChestState s = chests.getOrCreate(new BlockPos(x, y, z));
                        return s.getSlots();
                    }
                }
            }
        }
        return null;
    }

    private static void persistChests(World world, BlockPos fire) {
        ChestRegistry chests = world.getChestRegistry();
        for (int dx = -CHEST_SEARCH; dx <= CHEST_SEARCH; dx++) {
            for (int dz = -CHEST_SEARCH; dz <= CHEST_SEARCH; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos p = new BlockPos(fire.x() + dx, fire.y() + dy, fire.z() + dz);
                    if (world.getBlockAt(p.x(), p.y(), p.z()) == BlockType.CHEST) {
                        chests.persist(world, p);
                    }
                }
            }
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
        return "Raid";
    }
}
