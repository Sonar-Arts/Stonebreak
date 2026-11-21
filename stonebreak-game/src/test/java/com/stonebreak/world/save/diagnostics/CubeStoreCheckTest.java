package com.stonebreak.world.save.diagnostics;

import com.openmason.engine.voxel.world.CendaVoxelWorld;
import com.openmason.engine.voxel.world.CubeStore;
import com.openmason.engine.voxel.world.CubeWorld;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import com.stonebreak.world.save.io.CubeRegionFiles;
import com.stonebreak.world.save.model.EntityData;
import com.stonebreak.world.save.serialization.EntitySectionCodec;
import com.stonebreak.world.voxel.VoxelLayers;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Java reader of committed cv_store region headers and the store check built on it (report only, never
 * deletes), plus the entity blob-section codec it validates.
 */
class CubeStoreCheckTest {

    private static byte[] cubeRecord(long cx, long cy, long cz, byte[] blob) {
        try (CubeWorld w = CubeWorld.create(WorldConfiguration.MIN_Y, WorldConfiguration.MAX_Y, 0, null)) {
            VoxelLayers.define(w);
            w.createCube(cx, cy, cz, 1);
            w.set(cx * 32 + 3, cy * 32 + 4, cz * 32 + 5, 2);
            if (blob != null) {
                w.setBlob(cx, cy, cz, blob);
            }
            return w.encode(cx, cy, cz, CubeWorld.PARTS_SAVED);
        }
    }

    private static byte[] blob(String section, byte[] data) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x53424331);
        out.writeShort(1);
        byte[] name = section.getBytes(StandardCharsets.UTF_8);
        out.writeShort(name.length);
        out.write(name);
        out.writeInt(data.length);
        out.write(data);
        return bytes.toByteArray();
    }

    @Test
    void headersListCommittedCubesAndTheCheckReadsThemBack(@TempDir Path world) throws Exception {
        assumeTrue(CendaVoxelWorld.isAvailable(), "needs the Cenda voxel library");
        byte[] entities = EntitySectionCodec.encode(List.of(EntityData.builder()
            .entityType(com.stonebreak.mobs.entities.EntityType.CHICKEN).addCustomData("aiState", "IDLE")
            .position(new Vector3d(1e10 + 0.5, 64, -3)).build()));
        try (CubeStore store = CubeStore.open(world.resolve("cubes"))) {
            store.writeCube(0, 2, 0, cubeRecord(0, 2, 0, blob(EntitySectionCodec.SECTION, entities)));
            store.writeCube(-1, -12, 9, cubeRecord(-1, -12, 9, null));
            store.writeColumn(0, 0, new byte[]{1, 2});
            store.flush();
            store.writeCube(5, 5, 5, cubeRecord(5, 5, 5, null)); // written, NOT committed
        }
        // close() commits; reopen-free view of the files:
        Files.writeString(world.resolve("cubes").resolve(World.BLOCK_STATES_FILE), "a\nb\n");

        long[] all = CubeRegionFiles.regionFiles(world.resolve("cubes")).stream()
            .filter(p -> p.toString().endsWith(".cvr"))
            .map(p -> {
                try {
                    return CubeRegionFiles.read(p).cubeKeys();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            })
            .flatMapToLong(Arrays::stream).toArray();
        assertEquals(9, all.length, "three cubes (close committed the last one): " + Arrays.toString(all));
        assertEquals(3, CubeRegionFiles.countCubes(world.resolve("cubes")));

        CubeStoreCheck.Report r = CubeStoreCheck.check(world, null, true);
        assertTrue(r.healthy(), r.problems().toString());
        assertEquals(3, r.cubeRecords());
        assertEquals(1, r.columnRecords());
        assertEquals(3, r.recordsRead());
        assertEquals(2, r.blockStates());
    }

    @Test
    void damageIsReportedNotRepaired(@TempDir Path world) throws Exception {
        assumeTrue(CendaVoxelWorld.isAvailable(), "needs the Cenda voxel library");
        try (CubeStore store = CubeStore.open(world.resolve("cubes"))) {
            store.writeCube(1, 1, 1, cubeRecord(1, 1, 1, new byte[]{0, 0, 0, 0, 0, 0, 0}));
        }
        Path garbage = world.resolve("cubes").resolve("r.9.9.9.cvr");
        Files.write(garbage, new byte[20_000]);
        byte[] before = Files.readAllBytes(garbage);

        CubeStoreCheck.Report r = CubeStoreCheck.check(world, null, true);
        assertFalse(r.healthy());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("r.9.9.9.cvr")), r.problems().toString());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("SBC1")), "a foreign blob is reported: "
            + r.problems());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("block-state table missing")), r.problems().toString());
        assertArrayEquals(before, Files.readAllBytes(garbage), "the check never rewrites files");
    }

    @Test
    void entitySectionRoundTripsAndRejectsDamage() throws Exception {
        assertNull(EntitySectionCodec.encode(List.of()));
        assertEquals(List.of(), EntitySectionCodec.decode(null));
        Vector3d far = new Vector3d(-123_456_789_012.25, 600.5, 7.125);
        byte[] bytes = EntitySectionCodec.encode(List.of(EntityData.builder()
            .entityType(com.stonebreak.mobs.entities.EntityType.CHICKEN).addCustomData("aiState", "IDLE")
            .position(far).health(4f).build()));
        List<EntityData> back = EntitySectionCodec.decode(bytes);
        assertEquals(1, back.size());
        assertEquals(far, back.get(0).getPosition());
        assertEquals(com.stonebreak.mobs.entities.EntityType.CHICKEN, back.get(0).getEntityType());
        assertEquals("IDLE", back.get(0).getCustomData().get("aiState"));
        assertThrows(java.io.IOException.class,
            () -> EntitySectionCodec.decode(Arrays.copyOf(bytes, bytes.length - 3)));
        assertEquals(null, CubeStoreCheck.checkBlob(null));
    }
}
