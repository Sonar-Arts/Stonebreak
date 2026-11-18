package com.stonebreak.mobs.goblin.camp;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.campfire.CampAnchor;
import com.stonebreak.blocks.campfire.CampfireRegistry;
import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.goblin.Goblin;
import com.stonebreak.mobs.goblin.GoblinLoadout;
import com.stonebreak.mobs.goblin.pack.GoblinPack;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.SimBlockEdits;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The goblin camps of one authoritative world — those whose fires are loaded. A camp lives in its
 * fire ({@link CampAnchor}): it thaws when the fire's chunk loads, its goblins brought back round
 * the fire; it freezes into the fire when the chunk unloads; it disbands when the fire goes out.
 */
public final class CampRegistry implements CampAnchor {

    /** No night band spawns this close to a camp: its land is the camp's own. */
    public static final float TERRITORY = 48.0f;
    /** Seconds between checks for a changed roster or stash to save into the fire. */
    static final float SAVE_INTERVAL = 1.0f;
    /** Goblins brought back per camp per tick, so a big camp thaws over a few ticks. */
    static final int THAW_PER_TICK = 4;
    /** Seconds between pieces of building — a camp goes up a little at a time. */
    static final float BUILD_INTERVAL = 5.0f;
    /** An enemy — a player, or a goblin not of the camp — this near the fire stops all building. */
    static final float ENEMY_RADIUS = 16.0f;
    /** How far round the fire a camp's goblins amble at rest, in columns. */
    static final int HOME_GROUND_RADIUS = 6;
    /** ...and how far above or below the fire: never down a drop they could not climb back up. */
    static final int HOME_GROUND_RISE = 1;
    /** Most cells of home ground kept per camp. */
    static final int HOME_GROUND_CELLS = 400;
    /** Seconds between each camp's look round for a rival to raid. */
    static final float RAID_PLAN_INTERVAL = 30.0f;

    private final World world;
    private final Map<BlockPos, GoblinCamp> camps = new LinkedHashMap<>();
    private final Map<BlockPos, String> saved = new HashMap<>();
    private final java.util.Random random = new java.util.Random();
    private float saveTimer;
    private float buildTimer = BUILD_INTERVAL;
    private float raidTimer = RAID_PLAN_INTERVAL;

    public CampRegistry(World world) {
        this.world = world;
        CampfireRegistry fires = world.getCampfireRegistry();
        if (fires != null) {
            fires.setAnchor(this);
        }
    }

    public Collection<GoblinCamp> camps() {
        return Collections.unmodifiableCollection(camps.values());
    }

    /** Whether any camp's fire is within {@code radius} of {@code pos}. */
    public boolean campWithin(Vector3f pos, float radius) {
        float r2 = radius * radius;
        for (BlockPos f : camps.keySet()) {
            float dx = f.x() + 0.5f - pos.x;
            float dy = f.y() - pos.y;
            float dz = f.z() + 0.5f - pos.z;
            if (dx * dx + dy * dy + dz * dz <= r2) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code founder}'s band makes camp: lights a fire at {@code site} and settles round it.
     *
     * @return the new camp, or {@code null} when the fire could not be lit there
     */
    public GoblinCamp found(Goblin founder, Vector3i site) {
        if (!SimBlockEdits.set(world, site.x, site.y, site.z, BlockType.CAMPFIRE)) {
            return null;
        }
        BlockPos fire = new BlockPos(site.x, site.y, site.z);
        world.getCampfireRegistry().onBlockPlaced(world, site.x, site.y, site.z);
        GoblinCamp camp = new GoblinCamp(fire);
        camp.adopt(founder.pack());
        camps.put(fire, camp);
        save(camp);
        return camp;
    }

    /** Runs every camp for a tick: brings goblins back, eats, saves changes, buries the dead ones. */
    public void tick(float dt) {
        saveTimer -= dt;
        boolean saveNow = saveTimer <= 0.0f;
        if (saveNow) {
            saveTimer = SAVE_INTERVAL;
        }
        buildTimer -= dt;
        boolean buildNow = buildTimer <= 0.0f;
        if (buildNow) {
            buildTimer = BUILD_INTERVAL;
        }
        raidTimer -= dt;
        boolean planRaids = raidTimer <= 0.0f;
        if (planRaids) {
            raidTimer = RAID_PLAN_INTERVAL;
        }
        List<BlockPos> dead = null;
        for (GoblinCamp camp : camps.values()) {
            if (camp.readyToRaid(dt) && planRaids) {
                planRaid(camp);
            }
            if (buildNow) {
                build(camp);
                surveyHomeGround(camp);
            }
            thaw(camp);
            keepChest(camp, dt);
            keepRack(camp, buildNow);
            camp.tick(dt, random);
            if (camp.population() == 0) {
                if (dead == null) {
                    dead = new ArrayList<>();
                }
                dead.add(camp.fire());
            } else if (saveNow) {
                save(camp);
            }
        }
        if (dead != null) {
            for (BlockPos fire : dead) {
                // Every goblin is dead: the fire stays, a camp no more.
                camps.remove(fire);
                saved.remove(fire);
                world.getCampfireRegistry().setCampData(world, fire, null);
            }
        }
    }

    /**
     * A camp goblin stranded away from home (WayHome) leaves the world and goes back on its camp's
     * roster, carrying its meat into the stash; the camp brings it back out at the fire.
     *
     * @return whether it was brought home (false: it has no camp)
     */
    public boolean bringHome(Goblin goblin) {
        GoblinCamp camp = goblin.pack().camp();
        if (camp == null || !camps.containsValue(camp)) {
            return false;
        }
        goblin.pack().leave(goblin); // first, so its removal is not taken for a second recall
        camp.recall(goblin);
        EntityManager entities = world.getEntityManager();
        if (entities != null) {
            entities.removeEntity(goblin);
        }
        return true;
    }

    /* ── CampAnchor: the fire's lifecycle ─────────────────────── */

    @Override
    public void loaded(BlockPos fire, String campData) {
        camps.put(fire, CampCodec.decode(fire, campData));
        saved.put(fire, campData);
    }

    @Override
    public String unloading(BlockPos fire) {
        GoblinCamp camp = camps.remove(fire);
        saved.remove(fire);
        if (camp == null) {
            return null;
        }
        EntityManager entities = world.getEntityManager();
        for (Goblin g : camp.members()) {
            g.pack().leave(g); // first, so its removal is not taken for a recall
            camp.recall(g);
            if (entities != null) {
                entities.removeEntity(g);
            }
        }
        if (camp.chestBound()) {
            // The pockets just emptied into it; this runs before the chunk is saved, so write it now.
            world.getChestRegistry().persist(world, camp.chest());
        }
        if (camp.rack() != null) {
            world.getMeatRackRegistry().persist(world, camp.rack());
        }
        return camp.population() > 0 ? CampCodec.encode(camp) : null;
    }

    @Override
    public void extinguished(BlockPos fire) {
        GoblinCamp camp = camps.remove(fire);
        saved.remove(fire);
        if (camp == null) {
            return;
        }
        // The band breaks up: every survivor goes its own way, to join whatever pack it finds.
        for (GoblinPack pack : new ArrayList<>(camp.packs())) {
            for (Goblin g : new ArrayList<>(pack.members())) {
                new GoblinPack(g).recruit(g);
            }
            pack.setCamp(null);
        }
        // Those not in the world when it went out (away, or not yet back) are survivors too: they
        // come out at the dead fire as loners, like the rest.
        EntityManager entities = world.getEntityManager();
        if (entities != null) {
            int i = 0;
            for (GoblinLoadout loadout = camp.pollWaiting(); loadout != null; loadout = camp.pollWaiting()) {
                entities.addEntity(new Goblin(world, spotNear(fire, i++), loadout));
            }
        }
        // What the camp had outside its chest is left lying round the dead fire; the chest stays.
        Vector3f at = new Vector3f(fire.x() + 0.5f, fire.y() + 0.5f, fire.z() + 0.5f);
        for (ItemStack s : camp.pouch().takeAll()) {
            DropUtil.createItemDrop(world, at, s);
        }
    }

    /* ── Helpers ─────────────────────────────────────────────── */

    private void thaw(GoblinCamp camp) {
        EntityManager entities = world.getEntityManager();
        BlockPos fire = camp.fire();
        if (entities == null || camp.waiting().isEmpty()
                || world.getChunkIfLoaded(Math.floorDiv(fire.x(), WorldConfiguration.CHUNK_SIZE),
                        Math.floorDiv(fire.z(), WorldConfiguration.CHUNK_SIZE)) == null) {
            return;
        }
        for (int i = 0; i < THAW_PER_TICK; i++) {
            GoblinLoadout loadout = camp.pollWaiting();
            if (loadout == null) {
                return;
            }
            Goblin g = new Goblin(world, spotNear(fire, i), loadout);
            entities.addEntity(g);
            camp.join(g);
        }
    }

    /** A free cell beside the fire to bring a goblin back into; the fire's own cell failing that. */
    private Vector3f spotNear(BlockPos fire, int salt) {
        int[][] ring = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        CampTerrain terrain = CampTerrain.of(world);
        for (int k = 0; k < ring.length; k++) {
            int[] d = ring[(k + salt) % ring.length];
            int x = fire.x() + d[0];
            int z = fire.z() + d[1];
            for (int dy = 0; dy >= -1; dy--) {
                if (terrain.standable(x, fire.y() + dy, z)) {
                    return new Vector3f(x + 0.5f, fire.y() + dy, z + 0.5f);
                }
            }
        }
        return new Vector3f(fire.x() + 0.5f, fire.y(), fire.z() + 0.5f);
    }

    /**
     * The camp keeps its stash in a chest beside the fire: binds to it while it stands, builds one
     * when it has none, and notices when a player has broken it.
     */
    private void keepChest(GoblinCamp camp, float dt) {
        com.stonebreak.blocks.container.ChestRegistry chests = world.getChestRegistry();
        if (chests == null) {
            return;
        }
        BlockPos chest = camp.chest();
        if (chest != null) {
            if (world.getChunkIfLoaded(Math.floorDiv(chest.x(), WorldConfiguration.CHUNK_SIZE),
                    Math.floorDiv(chest.z(), WorldConfiguration.CHUNK_SIZE)) == null) {
                camp.unbindChest();
            } else if (world.getBlockAt(chest.x(), chest.y(), chest.z()) != BlockType.CHEST) {
                camp.loseChest(); // broken: its loot dropped with it
            } else if (!camp.chestBound()) {
                camp.bindChest(chests.getOrCreate(chest).getSlots());
                chests.persist(world, chest);
            }
            return;
        }
        if (camp.readyForChest(dt) && camp.population() > 0) {
            Vector3f spot = chestSpot(camp.fire());
            if (spot != null) {
                int x = (int) Math.floor(spot.x);
                int y = (int) Math.floor(spot.y);
                int z = (int) Math.floor(spot.z);
                if (SimBlockEdits.set(world, x, y, z, BlockType.CHEST)) {
                    chests.onBlockPlaced(world, x, y, z);
                    camp.setChest(new BlockPos(x, y, z));
                }
            }
        }
    }

    /** How far from the fire a camp looks for its meat rack: wherever CampBuilder would count one. */
    static final int RACK_SEARCH = CampBuilder.RADIUS + 1;

    /**
     * The camp hangs its cooked meat on a meat rack by the fire — one it built, or a player's. Keeps
     * it bound to the rack's live hooks while it stands; {@code look} lets an unbound camp search.
     */
    private void keepRack(GoblinCamp camp, boolean look) {
        com.stonebreak.blocks.meatrack.MeatRackRegistry racks = world.getMeatRackRegistry();
        BlockPos rack = camp.rack();
        if (rack != null) {
            com.stonebreak.blocks.meatrack.MeatRackState hooks = racks.get(rack);
            boolean standing = world.getChunkIfLoaded(Math.floorDiv(rack.x(), WorldConfiguration.CHUNK_SIZE),
                    Math.floorDiv(rack.z(), WorldConfiguration.CHUNK_SIZE)) != null
                    && world.getBlockAt(rack.x(), rack.y(), rack.z()) == BlockType.MEAT_RACK;
            // A reloaded chunk brings a fresh grid: the old hooks array no longer reaches the rack.
            if (standing && hooks != null && hooks.getSlots() == camp.rackStash().slots()) {
                return;
            }
            camp.unbindRack();
            if (!standing) {
                return;
            }
        } else if (!look) {
            return;
        }
        BlockPos fire = camp.fire();
        for (int dx = -RACK_SEARCH; dx <= RACK_SEARCH; dx++) {
            for (int dz = -RACK_SEARCH; dz <= RACK_SEARCH; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    int x = fire.x() + dx;
                    int y = fire.y() + dy;
                    int z = fire.z() + dz;
                    if (world.getBlockAt(x, y, z) == BlockType.MEAT_RACK) {
                        BlockPos found = new BlockPos(x, y, z);
                        camp.bindRack(found, racks.getOrCreate(found).getSlots());
                        return;
                    }
                }
            }
        }
    }

    /** Raids the nearest rival camp in range, when the camp is hungry or clearly the stronger (RaidPlanner). */
    private void planRaid(GoblinCamp camp) {
        GoblinCamp rival = null;
        float best = RaidPlanner.RANGE * RaidPlanner.RANGE;
        for (GoblinCamp other : camps.values()) {
            if (other == camp) {
                continue;
            }
            float dx = other.fire().x() - camp.fire().x();
            float dz = other.fire().z() - camp.fire().z();
            float d = dx * dx + dz * dz;
            if (d < best) {
                best = d;
                rival = other;
            }
        }
        if (rival != null && RaidPlanner.shouldRaid(camp.population(),
                camp.ledger().foodInHand(camp.food()), rival.population())) {
            camp.sendRaid(rival.fire());
        }
    }

    /**
     * Puts up the next piece the camp is big enough for (CampBuilder), if any — unless one of its
     * pieces was broken lately (GoblinCamp#REPAIR_SECONDS) or an enemy is about.
     */
    private void build(GoblinCamp camp) {
        BlockPos fire = camp.fire();
        if (!loaded(fire)) {
            return;
        }
        BlockPos chest = camp.chest();
        java.util.List<com.stonebreak.mobs.goblin.Goblin> members = camp.members();
        if (members.isEmpty()) {
            return; // nobody to build it (and nobody whose walk out must stay clear)
        }
        CampTerrain terrain = CampTerrain.of(world);
        camp.checkPieces(at -> terrain.block(at.x(), at.y(), at.z()));
        CampWalker walker = CampWalker.of(world,
                com.stonebreak.mobs.entities.ai.nav.NavProfiles.forEntity(members.get(0)));
        CampBuilder.Survey survey = CampBuilder.survey(terrain, walker, fire, camp.population(),
                c -> (c.x == fire.x() && c.y == fire.y() && c.z == fire.z())
                        || (chest != null && c.x == chest.x() && c.y == chest.y() && c.z == chest.z()));
        for (Vector3i c : survey.standing()) {
            camp.pieceStanding(new BlockPos(c.x, c.y, c.z), terrain.block(c.x, c.y, c.z));
        }
        if (!camp.readyToBuild(BUILD_INTERVAL, enemyNear(camp))) {
            return;
        }
        CampBuilder.Placement next = survey.next();
        if (next != null && SimBlockEdits.set(world, next.cell().x, next.cell().y, next.cell().z, next.block(), next.state())) {
            camp.pieceStanding(new BlockPos(next.cell().x, next.cell().y, next.cell().z), next.block());
        }
    }

    /**
     * Where the camp's goblins amble at rest: floor their own routes reach from the fire, within
     * {@link #HOME_GROUND_RADIUS} of it and level with it. A goblin wandering anywhere it liked
     * would drop down ledge after ledge into the caves below and never find its way back up.
     */
    private void surveyHomeGround(GoblinCamp camp) {
        BlockPos fire = camp.fire();
        java.util.List<Goblin> members = camp.members();
        if (members.isEmpty() || !loaded(fire)) {
            return;
        }
        CampTerrain terrain = CampTerrain.of(world);
        CampWalker walker = CampWalker.of(world,
                com.stonebreak.mobs.entities.ai.nav.NavProfiles.forEntity(members.get(0)));
        Vector3i start = CampBuilder.standingBeside(terrain, new Vector3i(fire.x(), fire.y(), fire.z()));
        com.openmason.engine.nav.NavWorld.Reach reach = walker.reach(start, HOME_GROUND_RADIUS, HOME_GROUND_CELLS);
        java.util.List<Vector3i> cells = new ArrayList<>();
        for (int i = 0; i < reach.count(); i++) {
            if (Math.abs(reach.y(i) - fire.y()) <= HOME_GROUND_RISE
                    && !(reach.x(i) == fire.x() && reach.z(i) == fire.z())) {
                cells.add(new Vector3i(reach.x(i), reach.y(i), reach.z(i)));
            }
        }
        camp.setHomeGround(cells);
    }

    /** Whether a player, or a goblin from outside the camp, is within {@link #ENEMY_RADIUS} of its fire. */
    private boolean enemyNear(GoblinCamp camp) {
        Vector3f at = camp.fireCenter(new Vector3f());
        Vector3f player = world.getPlayerLocator().nearestPlayer(at, new Vector3f());
        if (player != null && player.distance(at) <= ENEMY_RADIUS) {
            return true;
        }
        for (com.stonebreak.mobs.entities.Entity e
                : com.stonebreak.mobs.goblin.pack.NearbyEntities.of(world).within(at, ENEMY_RADIUS)) {
            if (e instanceof Goblin g && g.isAlive() && g.pack().camp() != camp) {
                return true;
            }
        }
        return false;
    }

    private boolean loaded(BlockPos at) {
        return world.getChunkIfLoaded(Math.floorDiv(at.x(), WorldConfiguration.CHUNK_SIZE),
                Math.floorDiv(at.z(), WorldConfiguration.CHUNK_SIZE)) != null;
    }

    /** An open, floored cell beside the fire for the chest — not where the goblins come back in. */
    private Vector3f chestSpot(BlockPos fire) {
        CampTerrain terrain = CampTerrain.of(world);
        for (int r = 1; r <= 2; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int x = fire.x() + dx;
                    int z = fire.z() + dz;
                    if (terrain.standable(x, fire.y(), z) && terrain.covered(x, fire.y(), z)) {
                        return new Vector3f(x, fire.y(), z);
                    }
                }
            }
        }
        return null;
    }

    private void save(GoblinCamp camp) {
        if (camp.chestBound()) {
            world.getChestRegistry().persist(world, camp.chest()); // writes (and echoes) only on change
        }
        if (camp.rack() != null) {
            world.getMeatRackRegistry().persist(world, camp.rack()); // so clients see the hooks fill
        }
        String data = CampCodec.encode(camp);
        if (!data.equals(saved.get(camp.fire()))) {
            saved.put(camp.fire(), data);
            world.getCampfireRegistry().setCampData(world, camp.fire(), data);
        }
    }
}
