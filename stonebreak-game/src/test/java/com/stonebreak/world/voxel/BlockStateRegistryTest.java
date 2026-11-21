package com.stonebreak.world.voxel;

import com.stonebreak.blocks.stairs.StairState;
import com.stonebreak.blocks.stalagmite.Stalagmite;
import com.stonebreak.blocks.stalagmite.StalagmiteState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** State names <-> STATE layer ids: the fixed stalagmite ids the generator writes, growth, persistence. */
class BlockStateRegistryTest {

    @Test
    void fixedIdsMatchTheGeneratorLayout() {
        BlockStateRegistry r = new BlockStateRegistry();
        assertEquals(BlockStateRegistry.FIXED_IDS, r.size());
        StairState.Facing[] f = StairState.Facing.values();
        for (int size = 1; size <= 3; size++) {
            for (int hanging = 0; hanging < 2; hanging++) {
                for (int facing = 0; facing < 4; facing++) {
                    int index = ((size - 1) * 2 + hanging) * 4 + facing;
                    String name = new StalagmiteState(size, hanging == 1, f[facing]).toStateString();
                    assertEquals(r.stalagmiteAnchorIds()[index], r.find(name), name);
                }
            }
        }
        assertArrayEquals(new short[]{25, 26}, r.stalagmitePartIds());
        assertEquals(Stalagmite.UPPER, r.nameOf(25));
        assertEquals(Stalagmite.LOWER, r.nameOf(26));
    }

    @Test
    void namesGetStableIdsAndListenersHearNewOnes() {
        BlockStateRegistry r = new BlockStateRegistry();
        List<String> heard = new ArrayList<>();
        r.addListener((id, name) -> heard.add(id + "=" + name));
        int a = r.idOf("facing=north");
        assertEquals(a, r.idOf("facing=north"));
        int b = r.idOf("lit");
        assertEquals(a + 1, b);
        assertEquals(List.of(a + "=facing=north", b + "=lit"), heard);
        assertEquals(BlockStateRegistry.NONE, r.idOf(null));
        assertEquals(BlockStateRegistry.NONE, r.find("never"));
        assertNull(r.nameOf(0));
        assertNull(r.nameOf(9999));
    }

    @Test
    void saveAndLoadRoundTrip(@TempDir Path dir) throws Exception {
        BlockStateRegistry r = new BlockStateRegistry();
        int id = r.idOf("stairs:facing=EAST");
        Path file = dir.resolve("states.txt");
        r.save(file);
        BlockStateRegistry back = new BlockStateRegistry();
        assertTrue(back.load(file));
        assertEquals(id, back.find("stairs:facing=EAST"));
        assertEquals(r.names(), back.names());
        assertFalse(new BlockStateRegistry().load(dir.resolve("missing.txt")));
    }

    @Test
    void foreignTablesAreRejected() {
        BlockStateRegistry r = new BlockStateRegistry();
        assertThrows(IllegalArgumentException.class, () -> r.replaceAll(List.of("x", "y")));
    }
}
