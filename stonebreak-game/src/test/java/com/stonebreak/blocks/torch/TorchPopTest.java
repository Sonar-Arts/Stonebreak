package com.stonebreak.blocks.torch;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.ServerMutationSinks;
import com.stonebreak.world.World;
import org.junit.jupiter.api.Test;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Torch support rules and the issue #247 pop, over a Mockito-mocked {@link World}
 * (stubbed {@code getBlockAt/getBlockStateAt/setBlockAt/serverSinks}, with
 * {@code DropUtil} statically mocked so the drop is deterministic):
 *
 * <ul>
 *   <li>{@link BlockType#canHoldTorch()} — full-cube solids only; foliage and the
 *       door's swinging panel are ruled out as torch supports at placement</li>
 *   <li>{@link TorchBlock#findUnsupported} — the five-neighbour scan (ground above,
 *       side torches on four faces), foreign-support torches ignored, solid-cell
 *       early-return, and the {@code treatCellAsNonSupport} override for state-only
 *       changes (a door toggled open keeps its solid type)</li>
 *   <li>{@link TorchBlock#popUnsupported} — item drop + funnel AIR write + replication
 *       sink report per popped torch, idempotent after the funnel sweep, and a no-op
 *       when the world has no block sink</li>
 * </ul>
 *
 * <p>The {@code World.setBlockAt} funnel sweep itself (recursion guard, renderOnly
 * gate) and the {@code ServerBlockHandler} door-open pop live on the authoritative
 * side and are covered by the harness + manual eyeball, not by this unit test — the
 * mocked {@code setBlockAt} deliberately simulates only the block write.</p>
 */
class TorchPopTest {

    /** The changed cell every scan is anchored to. */
    private static final Vector3i C = new Vector3i(5, 5, 5);

    private Map<String, BlockType> blocks;
    private Map<String, String> states;
    private List<String> airWrites;
    private List<Vector3f> dropPositions;
    private List<String> sinkReports;
    private World world;

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private World mockWorld(boolean withBlockSink) {
        blocks = new HashMap<>();
        states = new HashMap<>();
        airWrites = new ArrayList<>();
        dropPositions = new ArrayList<>();
        sinkReports = new ArrayList<>();
        world = mock(World.class);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> blocks.get(
                key(inv.getArgument(0, Integer.class), inv.getArgument(1, Integer.class),
                        inv.getArgument(2, Integer.class))));
        when(world.getBlockStateAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> states.get(
                key(inv.getArgument(0, Integer.class), inv.getArgument(1, Integer.class),
                        inv.getArgument(2, Integer.class))));
        when(world.setBlockAt(anyInt(), anyInt(), anyInt(), any(BlockType.class), anyBoolean()))
                .thenAnswer(inv -> {
                    int x = inv.getArgument(0, Integer.class);
                    int y = inv.getArgument(1, Integer.class);
                    int z = inv.getArgument(2, Integer.class);
                    BlockType type = inv.getArgument(3, BlockType.class);
                    blocks.put(key(x, y, z), type);
                    if (type == BlockType.AIR) {
                        airWrites.add(key(x, y, z));
                    }
                    return true;
                });
        ServerMutationSinks sinks = new ServerMutationSinks();
        if (withBlockSink) {
            sinks.setBlockSink((x, y, z, type) -> {
                blocks.put(key(x, y, z), type);
                if (type == BlockType.AIR) {
                    sinkReports.add(key(x, y, z));
                }
            });
        }
        when(world.serverSinks()).thenReturn(sinks);
        return world;
    }

    /** A torch in the mock world with the state that makes its support IS the given cell. */
    private void placeTorch(int tx, int ty, int tz, String stateString) {
        blocks.put(key(tx, ty, tz), BlockType.TORCH_PLACED);
        states.put(key(tx, ty, tz), stateString);
    }

    private List<Vector3f> recordDrops(Runnable scan) {
        try (MockedStatic<DropUtil> dropUtil = mockStatic(DropUtil.class)) {
            dropUtil.when(() -> DropUtil.handleBlockBroken(any(World.class), any(Vector3f.class),
                            any(BlockType.class)))
                    .thenAnswer(inv -> {
                        dropPositions.add(inv.getArgument(1, Vector3f.class));
                        return null;
                    });
            scan.run();
        }
        return dropPositions;
    }

    @Test
    void canHoldTorchRules() {
        assertFalse(BlockType.LEAVES.canHoldTorch(), "foliage decays away");
        assertFalse(BlockType.PINE_LEAVES.canHoldTorch());
        assertFalse(BlockType.ELM_LEAVES.canHoldTorch());
        assertFalse(BlockType.OAK_DOOR.canHoldTorch(), "the panel swings on toggle");
        assertFalse(BlockType.TORCH_PLACED.canHoldTorch(), "a torch is not solid");
        assertFalse(BlockType.WATER.canHoldTorch());
        assertFalse(BlockType.AIR.canHoldTorch());
        assertTrue(BlockType.STONE.canHoldTorch());
        assertTrue(BlockType.WOOD.canHoldTorch());
        assertTrue(BlockType.PINE.canHoldTorch());
        assertTrue(BlockType.ELM_WOOD_LOG.canHoldTorch());
    }

    @Test
    void placementRejectsLeavesAndDoors() {
        World w = mockWorld(true);
        // Top of a leaf block: the support cannot hold a torch (issue #247).
        assertNull(TorchBlock.placementState(w, C, new Vector3i(5, 6, 5)), "leaves refused");
        // Against the side of a leaf block: refused too.
        assertNull(TorchBlock.placementState(w, C, new Vector3i(6, 5, 5)), "side of leaves refused");
        // Against the door's swinging panel: refused.
        blocks.put(key(C.x, C.y, C.z), BlockType.OAK_DOOR);
        assertNull(TorchBlock.placementState(w, C, new Vector3i(5, 6, 5)), "door refused");
        // On a full-cube solid: accepted as a ground torch.
        blocks.put(key(C.x, C.y, C.z), BlockType.STONE);
        TorchState placed = TorchBlock.placementState(w, C, new Vector3i(5, 6, 5));
        assertTrue(placed != null && !placed.isSide(), "stone holds a ground torch");
        // On a torch (not solid): refused.
        blocks.put(key(C.x, C.y, C.z), BlockType.TORCH_PLACED);
        assertNull(TorchBlock.placementState(w, C, new Vector3i(5, 6, 5)), "torch refused");
        // The underside of a block is never accepted.
        blocks.put(key(C.x, C.y, C.z), BlockType.STONE);
        assertNull(TorchBlock.placementState(w, C, new Vector3i(5, 4, 5)), "underside refused");
    }

    @Test
    void isSupportedUsesCanHoldTorch() {
        World w = mockWorld(true);
        // Ground torch above a leaf: unsupported (issue #247).
        placeTorch(5, 6, 5, TorchState.ground().toStateString());
        assertFalse(TorchBlock.isSupported(w, 5, 6, 5, TorchState.ground()));
        // Same torch above stone: supported.
        blocks.put(key(5, 5, 5), BlockType.STONE);
        assertTrue(TorchBlock.isSupported(w, 5, 6, 5, TorchState.ground()));
        // Side torch hanging from a door: unsupported (its wall cell is the door).
        placeTorch(6, 5, 5, TorchState.side(TorchState.Facing.WEST).toStateString());
        blocks.put(key(5, 5, 5), BlockType.OAK_DOOR);
        assertFalse(TorchBlock.isSupported(w, 6, 5, 5, TorchState.side(TorchState.Facing.WEST)));
        // Hanging from stone: supported.
        blocks.put(key(5, 5, 5), BlockType.STONE);
        assertTrue(TorchBlock.isSupported(w, 6, 5, 5, TorchState.side(TorchState.Facing.WEST)));
        // validatePlacement follows isSupported: leaf support → null.
        blocks.put(key(5, 5, 5), BlockType.LEAVES);
        assertNull(TorchBlock.validatePlacement(w, 6, 5, 5,
                TorchState.side(TorchState.Facing.WEST).toStateString()));
    }

    @Test
    void findUnsupportedScansTheFiveNeighbours() {
        World w = mockWorld(true);
        // The changed cell is air: the five-neighbour scan runs.
        // Ground torch above, support IS C.
        placeTorch(5, 6, 5, TorchState.ground().toStateString());
        // Side torches on all four faces, each hanging from C.
        placeTorch(6, 5, 5, TorchState.side(TorchState.Facing.WEST).toStateString());
        placeTorch(4, 5, 5, TorchState.side(TorchState.Facing.EAST).toStateString());
        placeTorch(5, 5, 6, TorchState.side(TorchState.Facing.NORTH).toStateString());
        placeTorch(5, 5, 4, TorchState.side(TorchState.Facing.SOUTH).toStateString());
        // A foreign-support torch adjacent to C: its support ISN'T C → ignored.
        placeTorch(6, 6, 5, TorchState.ground().toStateString()); // support (6,5,5)
        assertEquals(List.of(new Vector3i(5, 6, 5), new Vector3i(6, 5, 5), new Vector3i(4, 5, 5),
                        new Vector3i(5, 5, 6), new Vector3i(5, 5, 4)),
                TorchBlock.findUnsupported(w, C.x, C.y, C.z));
        // The changed cell still holds things up (stone): early return.
        blocks.put(key(C.x, C.y, C.z), BlockType.STONE);
        assertEquals(List.of(), TorchBlock.findUnsupported(w, C.x, C.y, C.z));
    }

    @Test
    void findUnsupportedTreatCellAsNonSupport() {
        World w = mockWorld(true);
        // A door occupies the changed cell: solid type → the raw-solid early-return
        // fires, even though door-mounted torches hang off it.
        blocks.put(key(C.x, C.y, C.z), BlockType.OAK_DOOR);
        placeTorch(5, 6, 5, TorchState.ground().toStateString());
        placeTorch(6, 5, 5, TorchState.side(TorchState.Facing.WEST).toStateString());
        assertEquals(List.of(), TorchBlock.findUnsupported(w, C.x, C.y, C.z));
        // treatCellAsNonSupport asserts the swung-away door no longer holds things up.
        assertEquals(List.of(new Vector3i(5, 6, 5), new Vector3i(6, 5, 5)),
                TorchBlock.findUnsupported(w, C.x, C.y, C.z, true));
    }

    @Test
    void popUnsupportedDropsRemovesAndReports() {
        World w = mockWorld(true);
        placeTorch(5, 6, 5, TorchState.ground().toStateString());
        blocks.put(key(C.x, C.y, C.z), BlockType.AIR); // the support just vanished

        List<Vector3f> drops = recordDrops(() -> {
            List<Vector3i> popped = TorchBlock.popUnsupported(w, C.x, C.y, C.z);
            assertEquals(List.of(new Vector3i(5, 6, 5)), popped);
        });

        // One item drop at the torch's cell center.
        assertEquals(1, drops.size());
        assertEquals(new Vector3f(5.5f, 6.5f, 5.5f), drops.get(0));
        // The torch block removed through the funnel AIR write...
        assertTrue(airWrites.contains(key(5, 6, 5)));
        assertEquals(BlockType.AIR, blocks.get(key(5, 6, 5)));
        // ...and reported through the replication sink for remote clients.
        assertTrue(sinkReports.contains(key(5, 6, 5)));
    }

    @Test
    void popUnsupportedIsIdempotentAfterTheFunnelSweep() {
        World w = mockWorld(true);
        // The funnel sweep already removed the torch: nothing left to pop.
        blocks.put(key(C.x, C.y, C.z), BlockType.AIR);

        List<Vector3f> drops = recordDrops(() ->
                assertEquals(List.of(), TorchBlock.popUnsupported(w, C.x, C.y, C.z)));

        assertEquals(0, drops.size(), "no second drop");
        assertTrue(airWrites.isEmpty(), "no redundant AIR write");
        assertTrue(sinkReports.isEmpty(), "no redundant sink report");
    }

    @Test
    void popUnsupportedWithoutSink() {
        World w = mockWorld(false);
        placeTorch(5, 6, 5, TorchState.ground().toStateString());
        blocks.put(key(C.x, C.y, C.z), BlockType.AIR);

        List<Vector3f> drops = recordDrops(() -> {
            List<Vector3i> popped = TorchBlock.popUnsupported(w, C.x, C.y, C.z);
            assertEquals(List.of(new Vector3i(5, 6, 5)), popped);
        });

        // The drop and the funnel AIR write still happen (single-player / host world);
        // only the sink report is skipped — no exception.
        assertEquals(1, drops.size());
        assertTrue(airWrites.contains(key(5, 6, 5)));
        assertTrue(sinkReports.isEmpty());
    }

    @Test
    void popUnsupportedMultipleTorches() {
        World w = mockWorld(true);
        placeTorch(5, 6, 5, TorchState.ground().toStateString());
        placeTorch(6, 5, 5, TorchState.side(TorchState.Facing.WEST).toStateString());
        placeTorch(5, 5, 6, TorchState.side(TorchState.Facing.NORTH).toStateString());
        blocks.put(key(C.x, C.y, C.z), BlockType.AIR);

        List<Vector3f> drops = recordDrops(() ->
                assertEquals(3, TorchBlock.popUnsupported(w, C.x, C.y, C.z).size()));

        assertEquals(3, drops.size(), "one item drop per popped torch");
        assertEquals(3, airWrites.size(), "one funnel AIR write per popped torch");
        assertEquals(3, sinkReports.size(), "one sink report per popped torch");
        assertFalse(blocks.containsValue(BlockType.TORCH_PLACED), "no torch left");
    }
}
