package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ConnectionRegistry;
import com.openmason.engine.net.server.ServerConnection;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.workbench.WorkbenchStateRegistry;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.network.packet.player.ContainerActionC2S;
import com.stonebreak.network.packet.world.BlockStateS2C;
import com.stonebreak.network.server.ServerLevel;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #353: the server acknowledges each player's numbered furnace/workbench actions
 * ({@code ContainerActionC2S.seq}, issue #334) in the echoes it sends THAT player, so a
 * client can recognise an echo built before its latest predicted edit arrived.
 */
class SlotIntentAckTest {

    private static final String GRID = "workbench:grid=";
    private static final BlockPos BENCH = new BlockPos(1, 64, 1);

    private ConnectionRegistry registry;
    private ServerWorldContext ctx;
    private ServerBlockHandler blockHandler;
    private ContainerActionProcessor processor;
    private ServerPlayer editor;
    private ServerPlayer watcher;

    @BeforeEach
    void setUp() {
        World world = mock(World.class);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(BlockType.WORKBENCH);
        when(world.getBlockStateAt(anyInt(), anyInt(), anyInt())).thenReturn(GRID);
        when(world.getWorkbenchRegistry()).thenReturn(new WorkbenchStateRegistry());
        ServerLevel level = mock(ServerLevel.class);
        when(level.world()).thenReturn(world);

        editor = new ServerPlayer(mock(ServerConnection.class), 1);
        watcher = new ServerPlayer(mock(ServerConnection.class), 2);
        editor.updateState(0.5f, 64f, 0.5f, 0f, 0f);
        registry = mock(ConnectionRegistry.class);
        when(registry.all()).thenReturn(List.of(editor.connection(), watcher.connection()));
        ctx = new ServerWorldContext(registry);
        ctx.setServerLevel(level);
        ctx.addPlayer(editor);
        ctx.addPlayer(watcher);
        blockHandler = new ServerBlockHandler(new ServerChunkHandler());
        processor = new ContainerActionProcessor(new CraftingManager(), new SmeltingManager());
    }

    private static ContainerActionC2S action(byte region, byte act, int seq) {
        return new ContainerActionC2S(BENCH.x(), BENCH.y(), BENCH.z(),
                ContainerActionC2S.ContainerKind.WORKBENCH, region, (byte) 0,
                ContainerActionC2S.Region.NONE, (byte) -1, act, (short) 0, false, seq);
    }

    /** The newest block-state echo sent straight to {@code p}. */
    private static BlockStateS2C lastEchoTo(ServerPlayer p) {
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(p.connection(), atLeastOnce()).send(sent.capture(), anyBoolean());
        List<Packet> all = sent.getAllValues();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i) instanceof BlockStateS2C echo) {
                return echo;
            }
        }
        throw new AssertionError("no BlockStateS2C sent to player " + p.playerId());
    }

    @Test
    void anAcceptedActionIsAcknowledgedWithTheStateThatIncludesIt() {
        editor.inventory().add(BlockType.DIRT.getId(), 4);
        assertTrue(processor.apply(editor, action(ContainerActionC2S.Region.PLAYER_HOTBAR,
                ContainerActionC2S.Action.TAKE_ALL, 41), ctx));
        assertTrue(processor.apply(editor, action(ContainerActionC2S.Region.WORKBENCH_GRID,
                ContainerActionC2S.Action.PUT_ALL, 42), ctx));

        BlockStateS2C echo = lastEchoTo(editor);
        assertEquals(42, echo.ack());
        assertTrue(echo.state().contains("B:" + BlockType.DIRT.getId() + ":4"), echo.state());
        verify(watcher.connection(), never()).send(any(), anyBoolean());
    }

    @Test
    void aRefusedActionIsStillAcknowledged() {
        // Out of reach: nothing applied, but the sender must learn the server is past seq 9,
        // or it would treat every later echo as stale.
        editor.updateState(500f, 64f, 500f, 0f, 0f);
        editor.inventory().add(BlockType.DIRT.getId(), 4);
        processor.apply(editor, action(ContainerActionC2S.Region.PLAYER_HOTBAR,
                ContainerActionC2S.Action.TAKE_ALL, 8), ctx);
        assertFalse(processor.apply(editor, action(ContainerActionC2S.Region.WORKBENCH_GRID,
                ContainerActionC2S.Action.PUT_ALL, 9), ctx));

        BlockStateS2C echo = lastEchoTo(editor);
        assertEquals(9, echo.ack());
        assertEquals(GRID, echo.state());
    }

    @Test
    void laterEchoesCarryTheEditorsAckAndOthersGetNone() {
        processor.apply(editor, action(ContainerActionC2S.Region.WORKBENCH_GRID,
                ContainerActionC2S.Action.BALANCE, 41), ctx);
        processor.apply(editor, action(ContainerActionC2S.Region.WORKBENCH_GRID,
                ContainerActionC2S.Action.BALANCE, 42), ctx);
        clearInvocations(editor.connection(), watcher.connection());

        blockHandler.queueStateEcho(BENCH.x(), BENCH.y(), BENCH.z());
        blockHandler.tick(ctx);

        assertEquals(42, lastEchoTo(editor).ack());
        assertEquals(0, lastEchoTo(watcher).ack());
        assertEquals(GRID, lastEchoTo(watcher).state());
        verify(registry, never()).broadcast(any(), anyBoolean());
    }

    @Test
    void acksArePerBlock() {
        processor.apply(editor, action(ContainerActionC2S.Region.WORKBENCH_GRID,
                ContainerActionC2S.Action.BALANCE, 5), ctx);

        blockHandler.queueStateEcho(2, 64, 1); // another table nobody edited: one plain broadcast
        blockHandler.tick(ctx);

        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(registry).broadcast(sent.capture(), anyBoolean());
        BlockStateS2C echo = assertInstanceOf(BlockStateS2C.class, sent.getValue());
        assertEquals(2, echo.x());
        assertEquals(0, echo.ack());
    }

    @Test
    void inventoryScreenActionsAreNeverRecorded() {
        editor.inventory().add(BlockType.DIRT.getId(), 4);
        processor.apply(editor, new ContainerActionC2S(0, 0, 0,
                ContainerActionC2S.ContainerKind.INVENTORY, ContainerActionC2S.Region.PLAYER_HOTBAR,
                (byte) 0, ContainerActionC2S.Region.NONE, (byte) -1,
                ContainerActionC2S.Action.TAKE_ALL, (short) 0, false, 7), ctx);

        assertEquals(0, editor.slotIntentAck(new BlockPos(0, 0, 0)));
    }

    @Test
    void inventorySyncsAcknowledgeTheNewestActionEvenWhenRefused() {
        // No spawnable world: the action is refused whole, but its sync still carries the ack.
        ServerWorldContext empty = new ServerWorldContext(registry);
        new ServerPlayerHandler().handleContainerAction(editor, new ContainerActionC2S(0, 0, 0,
                ContainerActionC2S.ContainerKind.INVENTORY, ContainerActionC2S.Region.PLAYER_HOTBAR,
                (byte) 0, ContainerActionC2S.Region.NONE, (byte) -1,
                ContainerActionC2S.Action.SORT, (short) 0, false, 77), empty);

        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(editor.connection(), atLeastOnce()).send(sent.capture(), anyBoolean());
        var sync = assertInstanceOf(com.stonebreak.network.packet.player.InventorySyncS2C.class,
                sent.getValue());
        assertEquals(77, sync.ack());
    }
}
