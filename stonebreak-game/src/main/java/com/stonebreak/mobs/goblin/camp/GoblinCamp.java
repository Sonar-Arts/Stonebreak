package com.stonebreak.mobs.goblin.camp;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.mobs.goblin.Goblin;
import com.stonebreak.mobs.goblin.GoblinLoadout;
import com.stonebreak.mobs.goblin.food.FoodLedger;
import com.stonebreak.mobs.goblin.food.FoodStore;
import com.stonebreak.mobs.goblin.food.StackFoodStore;
import com.stonebreak.mobs.goblin.pack.GoblinPack;
import com.stonebreak.items.ItemStack;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A goblin camp: a hearth (its campfire), the goblins who live there, and the food they have put
 * by. Everything about it that survives a save lives in its fire's state ({@link CampCodec}), so
 * the camp is frozen and thawed with that chunk — its goblins are not saved as entities but
 * brought back from the roster ({@link #waiting()}).
 *
 * <p>Its goblins run in packs of up to {@link CampGrowth#PACK_SIZE}; one pack at a time goes out as
 * the hunting party, until the meat it carries plus the stash is a meal for everyone, and brings it
 * home to cook and store. Food put by beyond a day's eating pays for new goblins ({@link CampGrowth}).
 */
public final class GoblinCamp {

    /** Stash slots — the stores a camp can put by. */
    public static final int STASH_SLOTS = 9;
    /** How far a camp goblin wanders from its fire before heading home. */
    public static final float HOME_RADIUS = 10.0f;
    /** After its chest is broken, a camp waits this long before building another. */
    public static final float CHEST_REBUILD_SECONDS = 300.0f;
    /**
     * After one of its pieces is broken — a stake, a bedroll, the rack — a camp builds nothing for
     * this long, so breaking the wall really opens a way in. The clock stands still while an
     * enemy is about.
     */
    public static final float REPAIR_SECONDS = 180.0f;

    private final BlockPos fire;
    /** Where the stash is kept until the camp has a chest (and what a chestless camp saves). */
    private final StackFoodStore pouch = new StackFoodStore(STASH_SLOTS);
    private BlockPos chest;
    private StackFoodStore chestStash;
    private float chestCooldown;
    /** The meat rack by the fire, while the camp has one in the world; its hooks hold cooked meat. */
    private BlockPos rack;
    private StackFoodStore rackStash;
    private final FoodLedger ledger = new FoodLedger();
    /** Members not in the world right now — frozen with the camp, or recalled — to be brought back. */
    private final List<GoblinLoadout> waiting = new ArrayList<>();
    private final List<GoblinPack> packs = new ArrayList<>();
    /** The pack out hunting for the camp, or null. Only one goes at a time. */
    private GoblinPack huntingParty;
    private float growthTimer = CampGrowth.INTERVAL_SECONDS;
    /** The pieces the camp has standing, and what each is: one gone missing was broken. */
    private final java.util.Map<BlockPos, com.stonebreak.blocks.BlockType> pieces = new java.util.HashMap<>();
    /** Seconds still to wait before building again after a piece was broken. */
    private float repairWait;
    /** Where the camp's goblins mill about at rest: floor round the fire they walk to and back from. */
    private List<org.joml.Vector3i> homeGround = List.of();
    private float raidCooldown = RaidPlanner.COOLDOWN_SECONDS;

    public GoblinCamp(BlockPos fire) {
        this.fire = fire;
    }

    public BlockPos fire() {
        return fire;
    }

    /** A piece of the camp stands at {@code at}: watched from now on, so its breaking is noticed. */
    public void pieceStanding(BlockPos at, com.stonebreak.blocks.BlockType block) {
        pieces.put(at, block);
    }

    /**
     * Checks every piece the camp knows of against the world ({@code blockAt}, {@code null} for an
     * unloaded cell, which is left alone). One that is gone was broken: it is forgotten, and the
     * camp waits {@link #REPAIR_SECONDS} before building again.
     *
     * @return whether anything was found broken
     */
    public boolean checkPieces(java.util.function.Function<BlockPos, com.stonebreak.blocks.BlockType> blockAt) {
        boolean broken = false;
        for (var it = pieces.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            com.stonebreak.blocks.BlockType now = blockAt.apply(e.getKey());
            if (now != null && now != e.getValue()) {
                it.remove();
                broken = true;
            }
        }
        if (broken) {
            repairWait = REPAIR_SECONDS;
        }
        return broken;
    }

    /**
     * Counts down the wait after a breakage by {@code dt} — but not while {@code enemyNear}.
     *
     * @return whether the camp may build now
     */
    public boolean readyToBuild(float dt, boolean enemyNear) {
        if (enemyNear) {
            return false;
        }
        repairWait = Math.max(0.0f, repairWait - dt);
        return repairWait <= 0.0f;
    }

    /** Floor round the fire a goblin at rest may amble to (empty until surveyed). */
    public List<org.joml.Vector3i> homeGround() {
        return homeGround;
    }

    public void setHomeGround(List<org.joml.Vector3i> cells) {
        homeGround = List.copyOf(cells);
    }

    /** How many of the camp's standing pieces are {@code block}s. */
    public int pieceCount(com.stonebreak.blocks.BlockType block) {
        int n = 0;
        for (com.stonebreak.blocks.BlockType b : pieces.values()) {
            if (b == block) {
                n++;
            }
        }
        return n;
    }

    /** Seconds left before the camp builds again after a breakage (saved with the camp). */
    public float repairWait() {
        return repairWait;
    }

    public void setRepairWait(float seconds) {
        repairWait = Math.max(0.0f, seconds);
    }

    /** Where to stand to tend the fire. */
    public Vector3f fireCenter(Vector3f out) {
        return out.set(fire.x() + 0.5f, fire.y(), fire.z() + 0.5f);
    }

    /** The camp's stores: its chest when it has one in the world, its pouch otherwise. */
    public StackFoodStore stash() {
        return chestStash != null ? chestStash : pouch;
    }

    /** What the camp keeps outside a chest. */
    public StackFoodStore pouch() {
        return pouch;
    }

    /** The camp's chest, or {@code null} while it has none. */
    public BlockPos chest() {
        return chest;
    }

    public void setChest(BlockPos chest) {
        this.chest = chest;
        this.chestStash = null;
    }

    /** The chest is in the world: the stash lives in its slots, and the pouch empties into it. */
    public void bindChest(com.stonebreak.items.ItemStack[] slots) {
        chestStash = new StackFoodStore(slots);
        for (com.stonebreak.items.ItemStack s : pouch.takeAll()) {
            com.stonebreak.items.ItemStack left = chestStash.add(s);
            if (left != null) {
                pouch.add(left);
            }
        }
    }

    public boolean chestBound() {
        return chestStash != null;
    }

    /** The chest's chunk unloaded: until it is back, the pouch is the stash. */
    public void unbindChest() {
        chestStash = null;
    }

    /** The chest was broken: build another after a while. */
    public void loseChest() {
        chest = null;
        chestStash = null;
        chestCooldown = CHEST_REBUILD_SECONDS;
    }

    /** Counts down the wait to rebuild a chest; true once a new one may be built. */
    public boolean readyForChest(float dt) {
        chestCooldown = Math.max(0.0f, chestCooldown - dt);
        return chest == null && chestCooldown <= 0.0f;
    }

    /** The camp's meat rack, or {@code null} while it has none in the world. */
    public BlockPos rack() {
        return rack;
    }

    /** The rack's hooks as a store, or {@code null} while the camp has no rack. */
    public StackFoodStore rackStash() {
        return rackStash;
    }

    /** A rack stands by the fire: cooked meat hangs on {@code hooks} (its live slots). */
    public void bindRack(BlockPos rack, ItemStack[] hooks) {
        this.rack = rack;
        this.rackStash = new StackFoodStore(hooks);
    }

    /** The rack was broken or its chunk unloaded: look for one again. */
    public void unbindRack() {
        rack = null;
        rackStash = null;
    }

    /**
     * Puts cooked meat by: on the rack's hooks first, where it shows, then in the stash.
     *
     * @return what did not fit anywhere, or {@code null}
     */
    public ItemStack storeCooked(ItemStack stack) {
        ItemStack left = rackStash != null ? rackStash.add(stack) : stack;
        return left != null ? stash().add(left) : null;
    }

    /** Food put by at the camp itself — stash and rack — not counting what its goblins carry. */
    public FoodStore stores() {
        return rackStash != null ? FoodStore.union(List.of(stash(), rackStash)) : stash();
    }

    public FoodLedger ledger() {
        return ledger;
    }

    /** The camp's packs in the world. */
    public List<GoblinPack> packs() {
        return Collections.unmodifiableList(packs);
    }

    /** Every camp goblin in the world. */
    public List<Goblin> members() {
        List<Goblin> out = new ArrayList<>();
        for (GoblinPack p : packs) {
            out.addAll(p.members());
        }
        return out;
    }

    /** Makes {@code pack} one of this camp's own. */
    public void adopt(GoblinPack pack) {
        if (pack.camp() == null) {
            ledger.absorb(pack.ledger()); // its own, before it becomes the camp's
        }
        if (!packs.contains(pack)) {
            packs.add(pack);
        }
        pack.setCamp(this);
    }

    /** A camp goblin coming into the world joins a pack with room, or starts one. */
    public void join(Goblin goblin) {
        for (GoblinPack p : packs) {
            if (p.size() < CampGrowth.PACK_SIZE) {
                p.recruit(goblin);
                return;
            }
        }
        GoblinPack own = goblin.pack();
        if (own.size() == 1 && (own.camp() == null || own.camp() == this)) {
            adopt(own);
        } else {
            // It comes from another band: it starts a new pack here rather than bringing that one along.
            GoblinPack fresh = new GoblinPack(goblin);
            fresh.recruit(goblin);
            adopt(fresh);
        }
    }

    /** A pack may go hunting for the camp only while no other pack is. */
    public boolean mayHunt(GoblinPack pack) {
        return huntingParty == null || huntingParty == pack || !packs.contains(huntingParty);
    }

    public void huntStarted(GoblinPack pack) {
        huntingParty = pack;
    }

    public void huntEnded(GoblinPack pack) {
        if (huntingParty == pack) {
            huntingParty = null;
        }
    }

    public GoblinPack huntingParty() {
        return huntingParty;
    }

    /** Whether a raid is under way: one of the camp's packs is out raiding. */
    public boolean raiding() {
        for (GoblinPack p : packs) {
            if (p.raidTarget() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sends the camp's biggest pack that is not out hunting to raid the camp at {@code rivalFire}.
     *
     * @return whether a pack went
     */
    public boolean sendRaid(BlockPos rivalFire) {
        GoblinPack best = null;
        for (GoblinPack p : packs) {
            if (p != huntingParty && p.size() >= 2 && (best == null || p.size() > best.size())) {
                best = p;
            }
        }
        if (best == null) {
            return false;
        }
        best.startRaid(rivalFire);
        raidCooldown = RaidPlanner.COOLDOWN_SECONDS;
        return true;
    }

    /** Counts down to the next raid the camp may plan; true once it may. */
    public boolean readyToRaid(float dt) {
        raidCooldown = Math.max(0.0f, raidCooldown - dt);
        return raidCooldown <= 0.0f && !raiding();
    }

    public List<GoblinLoadout> waiting() {
        return Collections.unmodifiableList(waiting);
    }

    public void addWaiting(GoblinLoadout loadout) {
        waiting.add(loadout);
    }

    /** Takes the next goblin waiting to come back, or {@code null}. */
    public GoblinLoadout pollWaiting() {
        return waiting.isEmpty() ? null : waiting.remove(0);
    }

    public int population() {
        int n = waiting.size();
        for (GoblinPack p : packs) {
            n += p.size();
        }
        return n;
    }

    /** Food the camp can eat: the stash plus whatever the goblins out and about carry. */
    public FoodStore food() {
        List<FoodStore> stores = new ArrayList<>(packs.size() + 1);
        stores.add(stores()); // the stash before the rack, so the rack stays hung while there is other food
        for (GoblinPack p : packs) {
            stores.add(p.carried());
        }
        return FoodStore.union(stores);
    }

    /** Eats, keeps its packs in bands of {@link CampGrowth#PACK_SIZE}, and grows when it can afford to. */
    public void tick(float dt, java.util.Random random) {
        packs.removeIf(p -> p.size() == 0 || p.camp() != this); // empty, or gone over to another camp
        balancePacks();
        ledger.tick(dt, population(), food());
        growthTimer -= dt;
        if (growthTimer <= 0.0f) {
            growthTimer = CampGrowth.INTERVAL_SECONDS;
            if (CampGrowth.canGrow(population(), stores().food())) {
                payForNewGoblin();
                waiting.add(GoblinLoadout.roll(random)); // comes into the world at the fire
            }
        }
    }

    /**
     * Keeps the camp's goblins in packs of two to {@link CampGrowth#PACK_SIZE}: a pack that swelled
     * past its size (loners joined it) splits into even packs — five goes three and two, not four
     * and one — and a goblin left on its own (the camp's fifth, say) joins a pack with room or
     * takes a pack-mate from a full one. Only packs at rest are touched: never one out hunting,
     * raiding or in a fight.
     */
    private void balancePacks() {
        for (GoblinPack p : new ArrayList<>(packs)) {
            if (p.size() > CampGrowth.PACK_SIZE && atRest(p)) {
                split(p);
            }
        }
        for (GoblinPack lone : new ArrayList<>(packs)) {
            if (lone.size() != 1 || !atRest(lone)) {
                continue;
            }
            GoblinPack withRoom = null;
            GoblinPack full = null;
            for (GoblinPack p : packs) {
                if (p == lone || p.size() == 0 || !atRest(p)) {
                    continue;
                }
                if (p.size() < CampGrowth.PACK_SIZE && (withRoom == null || p.size() < withRoom.size())) {
                    withRoom = p;
                } else if (p.size() >= CampGrowth.PACK_SIZE && full == null) {
                    full = p;
                }
            }
            if (withRoom != null) {
                withRoom.recruit(lone.leader());
            } else if (full != null) {
                lone.recruit(full.members().get(full.size() - 1)); // never the leader
            }
        }
    }

    /** Splits {@code p} into the fewest even packs that fit: its first goblins stay, the rest go. */
    private void split(GoblinPack p) {
        int n = p.size();
        int count = (n + CampGrowth.PACK_SIZE - 1) / CampGrowth.PACK_SIZE;
        List<Goblin> all = new ArrayList<>(p.members());
        int from = 0;
        for (int k = 0; k < count; k++) {
            int size = n / count + (k < n % count ? 1 : 0);
            if (k > 0) {
                GoblinPack fresh = null;
                for (Goblin g : all.subList(from, from + size)) {
                    if (fresh == null) {
                        fresh = new GoblinPack(g);
                        fresh.recruit(g);
                        adopt(fresh);
                    } else {
                        fresh.recruit(g);
                    }
                }
            }
            from += size;
        }
    }

    private boolean atRest(GoblinPack p) {
        return p != huntingParty && p.task() == GoblinPack.Task.ROAM && p.enemy() == null;
    }

    /** Eats {@link CampGrowth#COST} meals' worth out of the stash; what is left of the last item stays as credit. */
    private void payForNewGoblin() {
        int paid = 0;
        while (paid < CampGrowth.COST) {
            int meal = stores().eatOne();
            if (meal <= 0) {
                break;
            }
            paid += meal;
        }
        ledger.addCredit(paid - CampGrowth.COST);
    }

    /**
     * A living member leaves the world without dying — unloaded with a far chunk, or frozen with
     * the camp: it goes back on the roster, and what it carried goes into the stash.
     */
    public void recall(Goblin goblin) {
        waiting.add(goblin.getLoadout());
        for (ItemStack carried : goblin.inventory().takeAll()) {
            stash().add(carried); // what does not fit is lost
        }
    }
}
