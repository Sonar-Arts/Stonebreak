package com.stonebreak.mobs.entities;

import com.openmason.engine.voxel.world.CubePos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Cube;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.model.EntityData;
import com.stonebreak.world.save.serialization.EntitySectionCodec;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Entities kept with their cubes (3B-E): the save hook writes a cube's persistent entities into its
 * {@code "entities"} blob section, unload takes them out of the world, install brings them back, and
 * an entity in known air above a column belongs to the highest loaded cube below it.
 */
class CubeEntityStoreTest {

    private World world;
    private EntityManager em;
    private CubeEntityStore store;

    @BeforeEach
    void setUp() {
        world = World.createDetached(new WorldConfiguration(), 11L);
        em = new EntityManager(world);
        world.setEntityManager(em);
        store = em.cubeStore(); // the one the manager keeps current
    }

    @AfterEach
    void tearDown() {
        em.cleanup();
        world.cleanup();
    }

    private Cube groundCube(long x, long y, long z) {
        assertTrue(world.setBlockAt(x, y - 1, z, BlockType.STONE));
        return world.getCubeAt(x, y - 1, z);
    }

    private Entity cow(double x, double y, double z) {
        Entity cow = new com.stonebreak.mobs.cow.Cow(world, new Vector3d(x, y, z), "Default");
        em.addEntity(cow);
        return cow;
    }

    @Test
    void ownerIsTheEntitysCubeOrTheHighestLoadedCubeBelowIt() {
        Cube ground = groundCube(5, 70, 5); // cube (0, 2, 0)
        assertEquals(new CubePos(0, 2, 0), ground.pos());
        assertEquals(ground.pos(), CubeEntityStore.ownerCube(world, 5.5, 70, 5.5));
        // Known air far above the column's only loaded cube: still that cube's.
        assertEquals(ground.pos(), CubeEntityStore.ownerCube(world, 5.5, 300, 5.5));
        // Below every loaded cube of the column: no owner (an orphan).
        assertNull(CubeEntityStore.ownerCube(world, 5.5, -100, 5.5));
        // Another column entirely.
        assertNull(CubeEntityStore.ownerCube(world, 100.5, 70, 5.5));
    }

    @Test
    void saveWritesTheSectionAndAnEmptiedCubeDropsIt() throws Exception {
        Cube cube = groundCube(5, 70, 5);
        Entity cow = cow(5.5, 70, 5.5);
        cow(6.5, 250, 6.5); // a second one in the known air above
        store.onCubeSave(world, cube);

        byte[] section = cube.blobSection(EntitySectionCodec.SECTION);
        assertNotNull(section);
        List<EntityData> saved = EntitySectionCodec.decode(section);
        assertEquals(2, saved.size());
        assertEquals(EntityType.COW, saved.get(0).getEntityType());
        assertEquals(70.0, saved.get(0).getPosition().y, 1e-9, "positions are saved as doubles");
        assertTrue(cube.isPayloadDirty());

        em.removeEntity(cow);
        em.getAllEntitiesIncludingPending().forEach(em::removeEntity);
        em.update(0.05f);
        store.onCubeSave(world, cube);
        assertNull(cube.blobSection(EntitySectionCodec.SECTION), "no entities left: the section goes");
    }

    @Test
    void unloadTakesTheEntitiesOutAndInstallBringsThemBack() {
        Cube cube = groundCube(5, 70, 5);
        cow(5.5, 70, 5.5);
        em.update(0.05f);
        assertEquals(1, em.getEntityCount());

        store.onCubeSave(world, cube);
        store.onCubeUnloadPending(world, cube);
        em.update(0.05f);
        assertEquals(0, em.getEntityCount(), "unloaded with its cube");

        // The cube comes back with the blob it was saved with.
        byte[] blob = cube.encodeBlob();
        Cube reloaded = new Cube(world, cube.pos());
        reloaded.decodeBlob(blob);
        store.onCubeInstalled(world, reloaded);
        // Queued (joins at the next update); read before any AI moves it.
        assertEquals(1, em.getAllEntitiesIncludingPending().size());
        Entity back = em.getAllEntitiesIncludingPending().get(0);
        assertEquals(EntityType.COW, back.getType());
        assertEquals(5.5, back.getX(), 1e-9);
        assertEquals(5.5, back.getZ(), 1e-9);
    }

    @Test
    void entitiesFarFromTheOriginKeepFullPrecision() {
        long far = 1L << 34; // beyond float precision for whole blocks (2^24)
        Cube cube = groundCube(far + 3, 70, -far + 3);
        Entity cow = cow(far + 3.25, 70, -far + 3.75);
        store.onCubeSave(world, cube);
        store.onCubeUnloadPending(world, cube);
        em.update(0.05f);
        assertFalse(em.getAllEntities().contains(cow));

        Cube reloaded = new Cube(world, cube.pos());
        reloaded.decodeBlob(cube.encodeBlob());
        store.onCubeInstalled(world, reloaded);
        Entity back = em.getAllEntitiesIncludingPending().get(0);
        assertEquals(far + 3.25, back.getX(), 1e-9);
        assertEquals(-far + 3.75, back.getZ(), 1e-9);
    }

    @Test
    void theGeneratedMarkSurvivesTheBlob() {
        Cube cube = groundCube(5, 70, 5);
        assertFalse(cube.areEntitiesGenerated());
        CubeEntityStore.markGenerated(cube);
        Cube reloaded = new Cube(world, cube.pos());
        reloaded.decodeBlob(cube.encodeBlob());
        store.onCubeInstalled(world, reloaded);
        assertTrue(reloaded.areEntitiesGenerated());
    }
}
