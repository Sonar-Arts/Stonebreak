package com.stonebreak.mobs.goblin.behavior;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;
import com.stonebreak.mobs.entities.ai.behavior.AiContext;
import com.stonebreak.mobs.entities.ai.behavior.Behavior;
import com.stonebreak.mobs.goblin.Goblin;
import com.stonebreak.mobs.goblin.camp.CampSitePlan;
import com.stonebreak.mobs.goblin.camp.CampTerrain;
import com.stonebreak.mobs.goblin.camp.NaturalGround;
import com.stonebreak.mobs.goblin.pack.GoblinPack;
import com.stonebreak.world.SimBlockEdits;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.EnumSet;

/**
 * The leader of a campless pack leads it to the site its search chose, digs the way in if the cave
 * is sealed, and lights the camp's fire there. Its pack-mates follow it (FollowLeaderBehavior).
 * If it cannot get where it needs to go, the plan is dropped and the pack roams until it finds
 * another.
 */
public final class MakeCampBehavior implements Behavior {

    public static final int PRIORITY = 50;

    /** Close enough to a cell to dig it. */
    static final float DIG_REACH = 2.5f;
    /** Seconds to dig out one cell. */
    static final float DIG_SECONDS = 0.6f;
    /** Close enough to the site to light the fire. */
    static final float SITE_REACH = 1.5f;
    /**
     * Seconds without getting a block closer (or digging one out) before the site is given up —
     * the pathfinder could not find the way the scan thought was there.
     */
    static final float STALL_SECONDS = 12.0f;
    private static final float SPEED = 1.0f;

    private final Goblin goblin;
    private final Vector3f target = new Vector3f();
    private float digTimer;
    private float stall;
    private float bestDistance;
    private CampSitePlan working;
    private boolean digging;

    public MakeCampBehavior(Goblin goblin) {
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
        return pack.camp() == null && pack.plan() != null && pack.isLeader(goblin)
                && pack.task() == GoblinPack.Task.ROAM && context.world() != null;
    }

    @Override
    public void start(AiContext context) {
        digTimer = 0.0f;
        digging = false;
        working = null;
    }

    @Override
    public void tick(AiContext context, float deltaTime) {
        GoblinPack pack = goblin.pack();
        CampSitePlan plan = pack.plan();
        World world = context.world();
        if (plan == null || world == null) {
            return;
        }
        if (plan != working) {
            working = plan; // a new site: progress is measured afresh
            stall = 0.0f;
            bestDistance = Float.MAX_VALUE;
        }
        stall += deltaTime;
        if (stall > STALL_SECONDS || context.nav().status() == com.stonebreak.mobs.entities.ai.nav.PathAgent.Status.NO_PATH) {
            pack.abandonPlan();
            context.nav().stop();
            return;
        }
        CampTerrain terrain = CampTerrain.of(world);
        Vector3i next = nextToDig(terrain, plan);
        if (next != null) {
            target.set(next.x + 0.5f, next.y, next.z + 0.5f);
            if (context.distanceTo(target) > DIG_REACH) {
                digging = false;
                progress(context.distanceTo(target));
                // Walk to open ground beside the block, not into the rock itself.
                Vector3i stand = standBeside(terrain, next);
                Vector3f goal = stand != null ? new Vector3f(stand.x + 0.5f, stand.y, stand.z + 0.5f) : target;
                context.nav().moveTo(goal, 0.8f, SPEED);
                return;
            }
            if (!NaturalGround.isDiggable(terrain.block(next.x, next.y, next.z))) {
                // Someone built across the way since it was planned: leave it be, look elsewhere.
                pack.abandonPlan();
                context.nav().stop();
                return;
            }
            digging = true;
            context.nav().stop();
            context.facePoint(target, 360.0f);
            digTimer += deltaTime;
            if (digTimer >= DIG_SECONDS) {
                digTimer = 0.0f;
                SimBlockEdits.set(world, next.x, next.y, next.z, BlockType.AIR); // no drops: goblin work
                stall = 0.0f;
            }
            return;
        }
        digging = false;
        Vector3i site = plan.site();
        target.set(site.x + 0.5f, site.y, site.z + 0.5f);
        if (context.distanceTo(target) > SITE_REACH) {
            progress(context.distanceTo(target));
            context.nav().moveTo(target, SITE_REACH * 0.5f, SPEED);
            return;
        }
        context.nav().stop();
        if (world.goblinCamps().found(goblin, site) == null) {
            pack.abandonPlan();
        }
    }

    /** Any block closer than ever before counts as progress, and resets the stall clock. */
    private void progress(float distance) {
        if (distance < bestDistance - 1.0f) {
            bestDistance = distance;
            stall = 0.0f;
        }
    }

    /** A standable cell next to {@code cell} — around it, a step above or a couple below — or null. */
    static Vector3i standBeside(CampTerrain terrain, Vector3i cell) {
        for (int dy = 1; dy >= -2; dy--) {
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {0, 0}}) {
                int x = cell.x + d[0];
                int y = cell.y + dy;
                int z = cell.z + d[1];
                if (terrain.standable(x, y, z)) {
                    return new Vector3i(x, y, z);
                }
            }
        }
        return null;
    }

    /** The first planned cell still solid, or {@code null} once the way is open. */
    static Vector3i nextToDig(CampTerrain terrain, CampSitePlan plan) {
        for (Vector3i cell : plan.dig()) {
            if (terrain.solid(cell.x, cell.y, cell.z)) {
                return cell;
            }
        }
        return null;
    }

    @Override
    public void stop(AiContext context) {
        context.nav().stop();
    }

    @Override
    public MobBehaviorState animationState() {
        return digging ? MobBehaviorState.IDLE : MobBehaviorState.WANDERING;
    }

    @Override
    public String debugName() {
        return digging ? "DigEntrance" : "MakeCamp";
    }
}
