package com.stonebreak.mobs.goblin.pack;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.goblin.Goblin;
import com.stonebreak.mobs.goblin.camp.CampRegistry;
import com.stonebreak.mobs.goblin.camp.CampSiteFinder;
import com.stonebreak.mobs.goblin.camp.CampSitePlan;
import com.stonebreak.mobs.goblin.camp.CampTerrain;
import com.stonebreak.mobs.goblin.camp.GoblinCamp;
import com.stonebreak.world.World;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.mobs.goblin.food.FoodLedger;
import com.stonebreak.mobs.goblin.food.FoodStore;
import org.joml.Vector3f;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A band of goblins that hunts together. Formed from a spawn band; a goblin on its own joins the
 * nearest pack it finds. Member 0 leads, and only the leader ticks the pack — the rest read it.
 *
 * <p>The pack eats from what its members carry ({@link FoodLedger}). Once that runs low it goes
 * {@link Task#HUNT hunting}: one quarry at a time, shared by every member, until there is a meal
 * for each of them — a sheep feeds two, a cow three — then it rests before hunting again.
 *
 * <p>A pack with no camp looks for one — a dark cave near the surface ({@link CampSiteFinder}) —
 * and its leader makes camp there. A camp's pack eats from, and hunts to fill, the camp's stash;
 * it prefers to hunt at night, and goes out by day only when the stores are nearly bare.
 *
 * <p>Server-side only: client shadows never build or tick a pack.
 */
public final class GoblinPack {

    public enum Task { ROAM, HUNT, RAID }

    /** The most goblins one pack holds. */
    public static final int MAX_SIZE = 12;
    /** What goblins hunt. */
    public static final Set<EntityType> PREY =
            Collections.unmodifiableSet(EnumSet.of(EntityType.COW, EntityType.SHEEP, EntityType.CHICKEN, EntityType.GOOSE));

    /** How far a lone goblin looks for a pack to join. */
    static final float JOIN_RADIUS = 16.0f;
    static final float JOIN_SCAN_SECONDS = 2.0f;
    /** How far the leader looks for prey, and how far it lets one get before giving it up. */
    static final float PREY_RADIUS = 32.0f;
    static final float PREY_LEASH = 48.0f;
    static final float PREY_SCAN_SECONDS = 1.0f;
    /** A hunt that finds nothing to chase for this long is called off. */
    static final float FRUITLESS_HUNT_SECONDS = 120.0f;
    /** A hunt that brings in no food for this long is called off, whatever it is chasing. */
    static final float HUNT_DEADLINE_SECONDS = 180.0f;
    /** A quarry the pack gets no closer to for this long is out of reach: it is let go... */
    static final float QUARRY_STALL_SECONDS = 15.0f;
    /** ...and left alone for this long. */
    static final float QUARRY_IGNORE_SECONDS = 60.0f;
    /** A camp hunts by day only below this much food per goblin. */
    static final float DAY_HUNT_BELOW = 0.2f;
    /** Daylight under which it counts as night for hunting. */
    static final float NIGHT_DAYLIGHT = 0.35f;
    /** How near a rival pack has to be to start a fight. */
    static final float ENCOUNTER_RADIUS = 16.0f;
    /** Beyond this the fight is over: the other side got away. */
    static final float DISENGAGE_RADIUS = 40.0f;
    static final float ENCOUNTER_SCAN_SECONDS = 1.0f;
    /** A raid that has not reached its target in this long is called off. */
    static final float RAID_TIMEOUT_SECONDS = 180.0f;
    /** Columns of ground a campless pack searches per tick. */
    static final int SEARCH_COLUMNS_PER_TICK = 96;
    /** After finding nowhere to camp, roam this long before looking again. */
    static final float SEARCH_RETRY_SECONDS = 60.0f;

    private final List<Goblin> members = new ArrayList<>();
    private final List<Goblin> membersView = Collections.unmodifiableList(members);
    private final FoodLedger ledger = new FoodLedger();
    private final FoodStore carried;

    private Task task = Task.ROAM;
    private LivingEntity quarry;
    private float joinTimer;
    private float preyTimer;
    private float fruitless;
    private float sinceFood;
    private float lastFood;
    private float quarryBest;
    private float quarryStall;
    /** Animals the pack could not reach, and when it may try them again. */
    private final java.util.Map<LivingEntity, Float> unreachable = new java.util.HashMap<>();
    private float clock;

    private GoblinCamp camp;
    private CampSiteFinder finder;
    private final java.util.ArrayDeque<CampSitePlan> plans = new java.util.ArrayDeque<>();
    private float searchCooldown;

    /** The rival pack this one is fighting, and how many it went into the fight with. */
    private GoblinPack enemy;
    private int startedWith;
    private float encounterTimer;

    /** The rival camp's fire this pack is raiding, while {@link Task#RAID}. */
    private BlockPos raidTarget;
    private float raidTime;

    public GoblinPack(Goblin founder) {
        members.add(founder);
        // A live view: food is whatever the current members carry.
        this.carried = FoodStore.union(new AbstractList<>() {
            @Override
            public FoodStore get(int index) {
                return members.get(index).inventory();
            }

            @Override
            public int size() {
                return members.size();
            }
        });
    }

    // ----- Membership ----------------------------------------------------------------------

    public List<Goblin> members() {
        return membersView;
    }

    public int size() {
        return members.size();
    }

    public Goblin leader() {
        return members.isEmpty() ? null : members.get(0);
    }

    public boolean isLeader(Goblin goblin) {
        return leader() == goblin;
    }

    /** Moves {@code goblin} out of whatever pack it is in and into this one. */
    public void recruit(Goblin goblin) {
        GoblinPack old = goblin.pack();
        if (old == this) {
            return;
        }
        old.leave(goblin);
        if (!members.contains(goblin)) {
            members.add(goblin);
        }
        goblin.setPack(this);
    }

    /** Drops {@code goblin}; the next member leads if it led. Safe to call twice. */
    public void leave(Goblin goblin) {
        members.remove(goblin);
    }

    /**
     * {@code goblin} left the world. Alive (despawned, or unloaded with a far chunk), a camp
     * goblin goes back on its camp's roster, to return at the fire.
     */
    public void memberRemoved(Goblin goblin) {
        if (members.remove(goblin) && camp != null && goblin.isAlive()) {
            camp.recall(goblin);
        }
    }

    /** The camp this pack belongs to, or {@code null} while it has none. */
    public GoblinCamp camp() {
        return camp;
    }

    /** Called by {@link GoblinCamp#adopt}, and with null when the camp is gone. */
    public void setCamp(GoblinCamp camp) {
        this.camp = camp;
        this.plans.clear();
        this.finder = null;
    }

    /** Where this campless pack means to make camp, or {@code null}. */
    public CampSitePlan plan() {
        return plans.peek();
    }

    /** The plan fell through (the way is blocked): on to the next site, or roam and look again later. */
    public void abandonPlan() {
        plans.poll();
        if (plans.isEmpty()) {
            searchCooldown = SEARCH_RETRY_SECONDS;
        }
    }

    /** Makes one pack of a band spawned together; the first goblin leads. */
    public static void formBand(List<Goblin> band) {
        if (band.isEmpty()) {
            return;
        }
        GoblinPack pack = band.get(0).pack();
        for (Goblin g : band) {
            pack.recruit(g);
        }
    }

    // ----- Hunting -------------------------------------------------------------------------

    public Task task() {
        return task;
    }

    /** What the pack is hunting right now, or {@code null} when it is not hunting anything. */
    public LivingEntity quarry() {
        return task == Task.HUNT && quarry != null && quarry.isAlive() ? quarry : null;
    }

    /** Everything the members carry, as one store. */
    public FoodStore carried() {
        return carried;
    }

    /** The stomach the pack hunts for: its camp's, or its own while it has none. */
    public FoodLedger ledger() {
        return camp != null ? camp.ledger() : ledger;
    }

    /** The food the pack hunts to fill: the camp's stash and the pockets, or the pockets alone. */
    public FoodStore food() {
        return camp != null ? camp.food() : carried;
    }

    /** How many mouths the hunt feeds: the whole camp, or just the pack. */
    public int population() {
        return camp != null ? camp.population() : size();
    }

    /** Runs the pack for one tick. Called by the leader only. */
    public void tick(float deltaSeconds, NearbyEntities nearby) {
        members.removeIf(g -> !g.isAlive());
        Goblin leader = leader();
        if (leader == null) {
            return;
        }
        if (camp == null) {
            ledger.tick(deltaSeconds, size(), carried); // a camp eats on its own tick
        }

        joinTimer -= deltaSeconds;
        if (joinTimer <= 0.0f) {
            joinTimer = JOIN_SCAN_SECONDS;
            if (size() == 1 && camp == null && joinNearbyPack(leader, nearby)) {
                return; // this pack is gone; the one it joined runs itself
            }
        }

        encounterTimer -= deltaSeconds;
        if (encounterTimer <= 0.0f) {
            encounterTimer = ENCOUNTER_SCAN_SECONDS;
            if (fightOrSurrender(leader, nearby)) {
                return; // this pack gave up and went over to the winners
            }
        }
        if (task == Task.RAID) {
            raidTime += deltaSeconds;
            if (raidTime > RAID_TIMEOUT_SECONDS || raidTarget == null) {
                endRaid();
            }
            return;
        }

        if (task == Task.ROAM && ledger().shouldHunt(food(), population()) && goodTimeToHunt(leader)
                && (camp == null || camp.mayHunt(this))) {
            task = Task.HUNT;
            preyTimer = 0.0f;
            fruitless = 0.0f;
            sinceFood = 0.0f;
            lastFood = ledger().foodInHand(food());
            if (camp != null) {
                camp.huntStarted(this); // the camp's hunting party: the other packs stay home
            }
        }
        if (task == Task.HUNT) {
            hunt(deltaSeconds, leader, nearby);
        } else {
            searchForCamp(deltaSeconds, leader);
        }
    }

    // ----- Fights between packs ---------------------------------------------------------

    /** The rival pack this one is fighting, or {@code null}. */
    public GoblinPack enemy() {
        return enemy != null && enemy.size() > 0 ? enemy : null;
    }

    /** The living enemy goblin nearest {@code from}, or {@code null}. */
    public Goblin nearestEnemy(Vector3f from) {
        GoblinPack e = enemy();
        if (e == null) {
            return null;
        }
        Goblin best = null;
        float bestDistance = Float.MAX_VALUE;
        for (Goblin g : e.members()) {
            float d = g.getPosition().distanceSquared(from);
            if (g.isAlive() && d < bestDistance) {
                bestDistance = d;
                best = g;
            }
        }
        return best;
    }

    /**
     * Starts a fight with {@code other}. It fights back — unless it is already in a fight with
     * another pack: then this pack just joins in against it, and that pack's own fight is left as
     * it was. How many a pack went into its fight with decides when it gives up, so it is set only
     * when a fight begins — never when another enemy joins in, nor when a pack turns on the one
     * that was already fighting it.
     */
    void engage(GoblinPack other) {
        if (other.enemy != this) {
            startedWith = size();
        }
        enemy = other;
        if (other.enemy() == null) {
            other.enemy = this;
            other.startedWith = other.size();
        }
        if (quarry != null) {
            quarry = null; // the hunt can wait
        }
    }

    private void disengage() {
        if (enemy != null && enemy.enemy == this) {
            enemy.enemy = null;
        }
        enemy = null;
    }

    /**
     * Looks for a rival pack to fight, or keeps an ongoing fight going: it ends when either side
     * is gone or out of reach, and a side that has lost enough gives up to the other.
     *
     * @return whether this pack surrendered (and so no longer exists)
     */
    private boolean fightOrSurrender(Goblin leader, NearbyEntities nearby) {
        GoblinPack e = enemy();
        if (e == null) {
            enemy = null;
            GoblinPack rival = nearestRival(leader, nearby);
            if (rival != null) {
                engage(rival);
            }
            return false;
        }
        Goblin nearest = nearestEnemy(leader.getPosition());
        if (nearest == null || nearest.getPosition().distance(leader.getPosition()) > DISENGAGE_RADIUS) {
            disengage();
            return false;
        }
        if (SurrenderRule.surrenders(startedWith, size(), e.size())) {
            surrenderTo(e);
            return true;
        }
        return false;
    }

    /** The nearest pack within reach that is not this one's own kind — another camp's, or a rival band's. */
    private GoblinPack nearestRival(Goblin leader, NearbyEntities nearby) {
        GoblinPack best = null;
        float bestDistance = Float.MAX_VALUE;
        for (Entity e : nearby.within(leader.getPosition(), ENCOUNTER_RADIUS)) {
            if (e instanceof Goblin g && g.isAlive() && !leader.isAllyOf(g)) {
                GoblinPack other = g.pack();
                // A campless loner does not fight: it joins someone (see joinNearbyPack).
                if (other.size() < 2 && other.camp() == null) {
                    continue;
                }
                float d = g.getPosition().distanceSquared(leader.getPosition());
                if (d < bestDistance) {
                    bestDistance = d;
                    best = other;
                }
            }
        }
        return best;
    }

    /** This pack gives up: every survivor goes over to the winners — their camp, or their band. */
    void surrenderTo(GoblinPack winner) {
        disengage();
        if (task == Task.HUNT) {
            endHunt();
        }
        endRaid();
        for (Goblin g : new java.util.ArrayList<>(members)) {
            if (winner.camp != null) {
                winner.camp.join(g);
            } else {
                winner.recruit(g);
            }
        }
    }

    // ----- Raids ---------------------------------------------------------------------------

    /** The camp sends this pack to raid the rival camp whose fire is at {@code target}. */
    public void startRaid(BlockPos target) {
        task = Task.RAID;
        raidTarget = target;
        raidTime = 0.0f;
        quarry = null;
    }

    /** The fire being raided, or {@code null}. */
    public BlockPos raidTarget() {
        return task == Task.RAID ? raidTarget : null;
    }

    /** The raid is over — done, called off or failed; the pack goes home. */
    public void endRaid() {
        if (task == Task.RAID) {
            task = Task.ROAM;
        }
        raidTarget = null;
    }

    /** Campless packs hunt whenever hungry; a camp waits for dark unless the stores are nearly bare. */
    private boolean goodTimeToHunt(Goblin leader) {
        if (camp == null) {
            return true;
        }
        World world = leader.getWorld();
        return world == null || world.getDaylight() < NIGHT_DAYLIGHT
                || ledger().foodInHand(food()) < DAY_HUNT_BELOW * population();
    }

    /** A campless pack looks round for a cave to make camp in, a slice of ground per tick. */
    private void searchForCamp(float deltaSeconds, Goblin leader) {
        World world = leader.getWorld();
        if (camp != null || !plans.isEmpty() || world == null || world.isRenderOnly()) {
            return;
        }
        if (finder == null) {
            searchCooldown -= deltaSeconds;
            if (searchCooldown > 0.0f) {
                return;
            }
            Vector3f at = leader.getPosition();
            CampRegistry camps = world.goblinCamps();
            if (camps == null) {
                return;
            }
            finder = new CampSiteFinder(CampTerrain.of(world),
                    new org.joml.Vector3i((int) Math.floor(at.x), (int) Math.floor(at.y), (int) Math.floor(at.z)),
                    cell -> !camps.campWithin(new Vector3f(cell.x, cell.y, cell.z), CampRegistry.TERRITORY));
        }
        if (finder.step(SEARCH_COLUMNS_PER_TICK)) {
            plans.addAll(finder.results());
            finder = null;
            if (plans.isEmpty()) {
                searchCooldown = SEARCH_RETRY_SECONDS; // nowhere here: keep roaming
            }
        }
    }

    private void hunt(float deltaSeconds, Goblin leader, NearbyEntities nearby) {
        clock += deltaSeconds;
        if (ledger().satisfied(food(), population())) {
            endHunt();
            return;
        }
        float inHand = ledger().foodInHand(food());
        if (inHand > lastFood + 0.5f) {
            sinceFood = 0.0f; // something was caught
        } else {
            sinceFood += deltaSeconds;
        }
        lastFood = Math.max(lastFood, inHand);
        if (sinceFood > HUNT_DEADLINE_SECONDS) {
            endHunt();
            return;
        }
        if (quarry != null) {
            float d = closestMemberDistance(quarry);
            if (d < quarryBest - 1.0f) {
                quarryBest = d;
                quarryStall = 0.0f;
            } else if ((quarryStall += deltaSeconds) > QUARRY_STALL_SECONDS) {
                unreachable.put(quarry, clock + QUARRY_IGNORE_SECONDS); // out of reach: try another
                quarry = null;
            }
        }
        if (quarry != null && (!quarry.isAlive()
                || quarry.getPosition().distance(leader.getPosition()) > PREY_LEASH)) {
            quarry = null;
        }
        preyTimer -= deltaSeconds;
        if (quarry == null && preyTimer <= 0.0f) {
            preyTimer = PREY_SCAN_SECONDS;
            unreachable.values().removeIf(until -> until < clock);
            quarry = nearestPrey(leader.getPosition(), nearby, unreachable.keySet());
            quarryBest = quarry != null ? closestMemberDistance(quarry) : Float.MAX_VALUE;
            quarryStall = 0.0f;
        }
        fruitless = quarry == null ? fruitless + deltaSeconds : 0.0f;
        if (fruitless >= FRUITLESS_HUNT_SECONDS) {
            endHunt();
        }
    }

    private void endHunt() {
        task = Task.ROAM;
        quarry = null;
        ledger().huntEnded();
        if (camp != null) {
            camp.huntEnded(this);
        }
    }

    private float closestMemberDistance(LivingEntity target) {
        float best = Float.MAX_VALUE;
        for (Goblin g : members) {
            best = Math.min(best, g.getPosition().distance(target.getPosition()));
        }
        return best;
    }

    private static LivingEntity nearestPrey(Vector3f from, NearbyEntities nearby, java.util.Set<LivingEntity> skip) {
        LivingEntity best = null;
        float bestDistance = Float.MAX_VALUE;
        for (Entity e : nearby.within(from, PREY_RADIUS)) {
            if (e instanceof LivingEntity le && PREY.contains(e.getType()) && le.isAlive() && !skip.contains(le)) {
                float d = e.getPosition().distanceSquared(from);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = le;
                }
            }
        }
        return best;
    }

    /** A lone goblin joins the nearest other pack with room. */
    private boolean joinNearbyPack(Goblin loner, NearbyEntities nearby) {
        GoblinPack best = null;
        float bestDistance = Float.MAX_VALUE;
        for (Entity e : nearby.within(loner.getPosition(), JOIN_RADIUS)) {
            if (e instanceof Goblin other && other != loner && other.isAlive()) {
                GoblinPack candidate = other.pack();
                if (candidate != this && candidate.size() < MAX_SIZE) {
                    float d = other.getPosition().distanceSquared(loner.getPosition());
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = candidate;
                    }
                }
            }
        }
        if (best == null) {
            return false;
        }
        best.recruit(loner);
        return true;
    }
}
