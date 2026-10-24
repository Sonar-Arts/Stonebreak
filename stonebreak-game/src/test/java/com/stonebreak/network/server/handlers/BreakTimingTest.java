package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.server.ConnectionRegistry;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.items.ToolMiningRules;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.network.packet.world.BlockBreakStartC2S;
import com.stonebreak.network.packet.world.BlockChangeC2S;
import com.stonebreak.network.server.ServerLevel;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.ServerMutationSinks;
import com.stonebreak.world.SnowLayerManager;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Issue #333: the server times every break itself. A break of a block that takes time needs a
 * start for that cell; the finish must come no earlier than the block's break time with the
 * held tool the SERVER knows — an early finish waits until then (never refused for jitter), and
 * breaks are sequential, so a fast client cannot mine faster than an honest one. Edits from a
 * player that has not reported a position are refused.
 */
class BreakTimingTest {

    private static final long SECOND = 1_000_000_000L;

    private final Map<String, BlockType> blocks = new HashMap<>();
    private ServerWorldContext ctx;
    private ServerLevel level;
    private ServerBlockHandler handler;
    private ServerPlayer player;
    private MockedStatic<DropUtil> drops;
    private long now = 10 * SECOND;

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    @BeforeEach
    void setUp() {
        World world = mock(World.class);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> blocks.getOrDefault(
                key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)), BlockType.AIR));
        when(world.getChunkIfLoaded(anyInt(), anyInt())).thenAnswer(inv -> mock(Chunk.class));
        when(world.getWaterValueAt(anyInt(), anyInt(), anyInt())).thenReturn(-1);
        when(world.setBlockAt(anyInt(), anyInt(), anyInt(), any(BlockType.class), anyBoolean()))
                .thenAnswer(inv -> {
                    blocks.put(key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)), inv.getArgument(3));
                    return true;
                });
        when(world.serverSinks()).thenReturn(new ServerMutationSinks());
        when(world.getSnowLayerManager()).thenReturn(new SnowLayerManager());

        level = mock(ServerLevel.class);
        when(level.world()).thenReturn(world);
        when(level.entityManager()).thenReturn(mock(EntityManager.class));
        ctx = new ServerWorldContext(mock(ConnectionRegistry.class));
        ctx.setServerLevel(level);
        ServerChunkHandler chunkHandler = new ServerChunkHandler();
        chunkHandler.onSessionStart();
        handler = new ServerBlockHandler(chunkHandler);
        player = new ServerPlayer(mock(ServerConnection.class), 7);
        player.updateState(0.5f, 64f, 0.5f, 0f, 0f);
        ctx.addPlayer(player);
        drops = mockStatic(DropUtil.class);
    }

    @AfterEach
    void tearDown() {
        drops.close();
    }

    private void stoneAt(int x) {
        blocks.put(key(x, 64, 0), BlockType.STONE);
    }

    private BlockType cell(int x) {
        return blocks.getOrDefault(key(x, 64, 0), BlockType.AIR);
    }

    private void start(int x, long atNs) {
        handler.handleBreakStart(player, new BlockBreakStartC2S(x, 64, 0), atNs);
    }

    private void finish(int x, BlockType clientSaw, long atNs) {
        handler.handleBlockChange(player, new BlockChangeC2S(x, 64, 0, (short) BlockType.AIR.getId(),
                (short) clientSaw.getId()), ctx, atNs);
    }

    /** Seconds stone takes with whatever the player holds. */
    private long stoneNs() {
        float s = ToolMiningRules.breakSeconds(player.inventory().slot(player.inventory().selectedHotbarSlot()),
                BlockType.STONE);
        assumeTrue(s * SECOND > 2 * ServerBlockHandler.BREAK_TOLERANCE_NS, "stone must take a while to mine");
        return (long) (s * SECOND);
    }

    // ─── a finish needs its start ───────────────────────────────────────────────

    @Test
    void aBreakWithoutAStartIsRefused() {
        stoneAt(1);

        finish(1, BlockType.STONE, now);

        assertEquals(BlockType.STONE, cell(1));
    }

    @Test
    void aStartForAnotherCellDoesNotCount() {
        stoneAt(1);
        stoneAt(2);
        start(2, now);

        finish(1, BlockType.STONE, now + 60 * SECOND);

        assertEquals(BlockType.STONE, cell(1));
    }

    @Test
    void aStartOutOfReachIsIgnored() {
        blocks.put(key(40, 64, 0), BlockType.STONE);
        handler.handleBreakStart(player, new BlockBreakStartC2S(40, 64, 0), now);
        player.updateState(40.5f, 64f, 0.5f, 0f, 0f); // walks over, then claims the break

        handler.handleBlockChange(player, new BlockChangeC2S(40, 64, 0, (short) BlockType.AIR.getId(),
                (short) BlockType.STONE.getId()), ctx, now + 60 * SECOND);

        assertEquals(BlockType.STONE, blocks.get(key(40, 64, 0)));
    }

    // ─── an honest break applies at once ────────────────────────────────────────

    @Test
    void aFinishAfterTheBreakTimeAppliesAtOnce() {
        stoneAt(1);
        long t = stoneNs();
        start(1, now);

        finish(1, BlockType.STONE, now + t);

        assertEquals(BlockType.AIR, cell(1));
    }

    @Test
    void jitterWithinTheToleranceStillAppliesAtOnce() {
        stoneAt(1);
        long t = stoneNs();
        start(1, now);

        finish(1, BlockType.STONE, now + t - ServerBlockHandler.BREAK_TOLERANCE_NS);

        assertEquals(BlockType.AIR, cell(1));
    }

    @Test
    void theHeldToolTheServerKnowsSetsTheTime() {
        player.inventory().setSlot(0, new ItemStack(ItemType.STONE_PICKAXE, 1));
        float withTool = ToolMiningRules.breakSeconds(player.inventory().slot(0), BlockType.STONE);
        assumeTrue(withTool < BlockType.STONE.getHardness(), "a stone pickaxe speeds up stone");
        stoneAt(1);
        start(1, now);

        finish(1, BlockType.STONE, now + (long) (withTool * SECOND));

        assertEquals(BlockType.AIR, cell(1), "the pickaxe's time, not the bare-hand time");
    }

    // ─── an early finish waits; breaks are sequential ──────────────────────────

    @Test
    void anEarlyFinishWaitsUntilTheBlockWouldHaveBroken() {
        stoneAt(1);
        long t = stoneNs();
        start(1, now);

        finish(1, BlockType.STONE, now + 1); // a speed-mining client
        handler.tick(ctx, now + t / 2);
        assertEquals(BlockType.STONE, cell(1), "not yet");

        handler.tick(ctx, now + t);
        assertEquals(BlockType.AIR, cell(1), "broken at the honest time");
    }

    @Test
    void theNextBlocksClockStartsWhenTheWaitingBreakEnds() {
        stoneAt(1);
        stoneAt(2);
        long t = stoneNs();
        start(1, now);
        finish(1, BlockType.STONE, now + 1);   // waits until ~now + t
        start(2, now + 2);                      // its clock cannot start before that
        finish(2, BlockType.STONE, now + t + 3);

        handler.tick(ctx, now + t);
        assertEquals(BlockType.AIR, cell(1));
        assertEquals(BlockType.STONE, cell(2), "two blocks take two break times");

        handler.tick(ctx, now + 2 * t);
        assertEquals(BlockType.AIR, cell(2));
    }

    @Test
    void onlyAFewBreaksMayWait() {
        long t = stoneNs();
        List<Integer> cells = new ArrayList<>();
        for (int x = 1; x <= ServerBlockHandler.MAX_DEFERRED_BREAKS + 1; x++) {
            stoneAt(x);
            cells.add(x);
            start(x, now + x);
            finish(x, BlockType.STONE, now + x);
        }

        handler.tick(ctx, now + 100 * t);

        for (int i = 0; i < ServerBlockHandler.MAX_DEFERRED_BREAKS; i++) {
            assertEquals(BlockType.AIR, cell(cells.get(i)));
        }
        assertEquals(BlockType.STONE, cell(cells.get(cells.size() - 1)), "the one past the cap was refused");
    }

    @Test
    void aWaitingBreakOfACellSomeoneChangedIsRefused() {
        stoneAt(1);
        long t = stoneNs();
        start(1, now);
        finish(1, BlockType.STONE, now + 1);

        blocks.put(key(1, 64, 0), BlockType.DIRT); // replaced while the break waited
        handler.tick(ctx, now + t);

        assertEquals(BlockType.DIRT, cell(1));
    }

    // ─── what is not timed ─────────────────────────────────────────────────────

    @Test
    void anUnbreakableBlockIsRefused() {
        assumeTrue(!BlockType.BEDROCK.isBreakable());
        blocks.put(key(1, 64, 0), BlockType.BEDROCK);
        start(1, now);

        finish(1, BlockType.BEDROCK, now + 3600 * SECOND);

        assertEquals(BlockType.BEDROCK, cell(1));
    }

    @Test
    void creativeWithCheatsIsNotTimed() {
        when(level.cheatsEnabled()).thenReturn(true);
        player.setGameMode(1);
        stoneAt(1);

        finish(1, BlockType.STONE, now);

        assertEquals(BlockType.AIR, cell(1));
    }

    // ─── a player with no reported position edits nothing ───────────────────────

    @Test
    void editsBeforeTheFirstPositionAreRefused() {
        ServerPlayer fresh = new ServerPlayer(mock(ServerConnection.class), 8);
        fresh.inventory().setSlot(0, new ItemStack(BlockType.DIRT, 5));

        handler.handleBlockChange(fresh, new BlockChangeC2S(1, 64, 0, (short) BlockType.DIRT.getId(),
                (short) BlockType.AIR.getId()), ctx, now);

        assertEquals(BlockType.AIR, cell(1));
        assertEquals(5, fresh.inventory().slot(0).getCount(), "nothing debited");
        assertTrue(fresh.lastStateNs() == 0L);
    }
}
