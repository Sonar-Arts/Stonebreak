package com.stonebreak.mobs.entities;

import com.openmason.engine.voxel.world.CubePos;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Cube;
import com.stonebreak.world.generation.terrain.animals.AnimalGenerator;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.model.EntityData;
import com.stonebreak.world.save.serialization.EntitySectionCodec;
import com.stonebreak.world.save.serialization.EntitySerializer;
import com.stonebreak.world.voxel.CubeEvents;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entities kept WITH their cubes (#447, 3B-E): an authoritative world saves its persistent entities into
 * the blob of the cube they stand in, restores them when the cube is installed again, and takes them out
 * of the world when the cube unloads.
 *
 * <p><b>Owner cube.</b> An entity belongs to the cube holding its position when that cube is loaded.
 * Entities above every loaded cube of their column stand in KNOWN AIR (cells over the column's top that
 * are never loaded): they belong to the highest loaded cube below them in the column, so a bird over a
 * hill is saved with — and unloaded with — the hill. An entity with no loaded cube at or below it in its
 * column is an orphan: it stays (frozen, see {@link EntityManager#canSimulate}) and is not saved.
 *
 * <p><b>Blob sections.</b> {@link EntitySectionCodec#SECTION} ({@code "entities"}) holds the cube's
 * persistent entities in 3B-S's format ({@code u32 count | count x (u32 len, JsonEntitySerializer JSON)},
 * positions as doubles); it is rewritten from the live world at every save point (World save hooks) and
 * at unload, and removed when the cube holds none. {@link #SECTION_GENERATED} ({@code "entities.gen"},
 * one byte = {@link #GENERATED_VERSION}) marks cubes whose column already got its initial animals.
 *
 * <p><b>Initial animals.</b> When a cube column becomes READY (band cubes generated, populated and
 * meshed) and the cube holding its surface at the column centre carries no generated mark, the column
 * gets {@link AnimalGenerator#spawnAnimals} once (seeded from the world seed and the column) and every
 * loaded cube of the column is marked, so a reload never spawns a second herd.
 *
 * <p>Everything here runs on the world's owner thread (stream events and save hooks are dispatched there).
 */
final class CubeEntityStore implements CubeEvents.InstallListener, CubeEvents.SaveHook,
        CubeEvents.UnloadPendingHook, CubeEvents.ColumnReadinessListener {

    private static final Logger LOGGER = Logger.getLogger(CubeEntityStore.class.getName());

    /** Blob section marking a cube whose column got its initial animals. */
    static final String SECTION_GENERATED = "entities.gen";
    static final byte GENERATED_VERSION = 1;
    private static final long ANIMAL_SEED_MIX = 0x6A09E667F3BCC909L;

    private final EntityManager manager;
    private final World world;

    /** Owner cube -> its entities; rebuilt lazily after the entity set or positions may have changed. */
    private final Map<CubePos, List<Entity>> index = new HashMap<>();
    private boolean indexStale = true;

    CubeEntityStore(EntityManager manager, World world) {
        this.manager = manager;
        this.world = world;
    }

    void register() {
        world.addInstallListener(this);
        world.addSaveHook(this);
        world.addUnloadPendingHook(this);
        world.addColumnReadinessListener(this);
    }

    void unregister() {
        world.removeInstallListener(this);
        world.removeSaveHook(this);
        world.removeUnloadPendingHook(this);
        world.removeColumnReadinessListener(this);
    }

    /** The entity set or positions may have changed: the next hook rebuilds the owner index. */
    void invalidate() {
        indexStale = true;
    }

    // ---- events ---------------------------------------------------------------------------------------------

    @Override
    public void onCubeInstalled(World w, Cube cube) {
        if (!w.isAuthoritative()) {
            return;
        }
        cube.setEntitiesGenerated(cube.blobSection(SECTION_GENERATED) != null);
        byte[] section = cube.blobSection(EntitySectionCodec.SECTION);
        if (section == null) {
            return;
        }
        List<EntityData> records;
        try {
            records = EntitySectionCodec.decode(section);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Dropping unreadable entity section of cube " + cube.pos() + ": " + e.getMessage());
            cube.putBlobSection(EntitySectionCodec.SECTION, null);
            return;
        }
        // Synchronously: a save point right after the install must see them (queued = pending, which the
        // owner index counts), or the section would be rewritten without them.
        for (EntityData record : records) {
            Entity entity = EntitySerializer.deserialize(record, w);
            if (entity != null) {
                manager.addEntity(entity);
            }
        }
        invalidate();
    }

    @Override
    public void onCubeSave(World w, Cube cube) {
        if (!w.isAuthoritative()) {
            return;
        }
        List<Entity> owned = ownedBy(cube.pos());
        List<EntityData> records = new ArrayList<>(owned.size());
        for (Entity entity : owned) {
            if (!entity.isAlive() || !entity.isPersistent() || entity.isNetworkShadow()) {
                continue;
            }
            EntityData data = EntitySerializer.serialize(entity);
            if (data != null) {
                records.add(data);
            }
        }
        // Unchanged bytes do not dirty the cube (Cube.putBlobSection compares); null removes the section.
        cube.putBlobSection(EntitySectionCodec.SECTION, EntitySectionCodec.encode(records));
        if (cube.areEntitiesGenerated() && cube.blobSection(SECTION_GENERATED) == null) {
            cube.putBlobSection(SECTION_GENERATED, new byte[]{GENERATED_VERSION});
        }
    }

    /** The world already ran {@link #onCubeSave} for this cube; the entities leave with it. */
    @Override
    public void onCubeUnloadPending(World w, Cube cube) {
        if (!w.isAuthoritative()) {
            return;
        }
        for (Entity entity : ownedBy(cube.pos())) {
            if (!entity.isNetworkShadow() && entity.getType() != EntityType.REMOTE_PLAYER) {
                manager.removeEntity(entity);
            }
        }
        index.remove(cube.pos());
    }

    @Override
    public void onColumnReady(World w, long cx, long cz) {
        if (!w.isAuthoritative()) {
            return;
        }
        long centerX = (cx << WorldConfiguration.CUBE_SHIFT) + WorldConfiguration.CUBE_EDGE / 2;
        long centerZ = (cz << WorldConfiguration.CUBE_SHIFT) + WorldConfiguration.CUBE_EDGE / 2;
        long surface = w.topY(centerX, centerZ) - 1;
        if (!WorldConfiguration.inBounds(surface)) {
            return;
        }
        Cube surfaceCube = w.getCube(cx, surface >> WorldConfiguration.CUBE_SHIFT, cz);
        if (surfaceCube == null || surfaceCube.areEntitiesGenerated()) {
            return;
        }
        Random random = new Random(w.getSeed() ^ ANIMAL_SEED_MIX ^ (cx * 0x9E3779B97F4A7C15L) ^ (cz * 0xC2B2AE3D27D4EB4FL));
        try {
            AnimalGenerator.spawnAnimals(w, surfaceCube, random, random);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Initial animals failed for column (" + cx + ", " + cz + ")", e);
        }
        // Mark every loaded cube of the column: the surface cube of a later visit may be another one.
        for (long cy = WorldConfiguration.MIN_CUBE_Y; cy < WorldConfiguration.MAX_CUBE_Y; cy++) {
            Cube cube = w.getCube(cx, cy, cz);
            if (cube != null) {
                markGenerated(cube);
            }
        }
        invalidate();
    }

    @Override
    public void onColumnUnready(World w, long cx, long cz) {
        // Entities leave with their cubes (UNLOAD_PENDING), not with readiness.
    }

    static void markGenerated(Cube cube) {
        cube.setEntitiesGenerated(true);
        cube.putBlobSection(SECTION_GENERATED, new byte[]{GENERATED_VERSION});
    }

    // ---- ownership ------------------------------------------------------------------------------------------

    /** The entities owned by cube {@code pos} (live + queued), from the owner index. */
    List<Entity> ownedBy(CubePos pos) {
        if (indexStale) {
            rebuildIndex();
        }
        List<Entity> owned = index.get(pos);
        return owned != null ? owned : List.of();
    }

    private void rebuildIndex() {
        index.clear();
        for (Entity entity : manager.getAllEntitiesIncludingPending()) {
            CubePos owner = ownerCube(world, entity.getX(), entity.getY(), entity.getZ());
            if (owner != null) {
                index.computeIfAbsent(owner, p -> new ArrayList<>()).add(entity);
            }
        }
        indexStale = false;
    }

    /**
     * The loaded cube owning a body at (x, y, z): its own cube, else (known air over the column's loaded
     * cubes) the highest loaded cube below it in the column; null when there is none.
     */
    static CubePos ownerCube(World world, double x, double y, double z) {
        long cx = (long) Math.floor(x) >> WorldConfiguration.CUBE_SHIFT;
        long cz = (long) Math.floor(z) >> WorldConfiguration.CUBE_SHIFT;
        long cy = Math.min((long) Math.floor(y) >> WorldConfiguration.CUBE_SHIFT, WorldConfiguration.MAX_CUBE_Y - 1L);
        for (long c = cy; c >= WorldConfiguration.MIN_CUBE_Y; c--) {
            if (world.hasCube(cx, c, cz)) {
                return new CubePos(cx, c, cz);
            }
        }
        return null;
    }
}
