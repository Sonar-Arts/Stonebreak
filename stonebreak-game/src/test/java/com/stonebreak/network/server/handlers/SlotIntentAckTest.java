package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ConnectionRegistry;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.workbench.WorkbenchStateRegistry;
import com.stonebreak.network.packet.world.BlockStateS2C;
import com.stonebreak.network.packet.world.WorkbenchSlotsC2S;
import com.stonebreak.network.server.ServerLevel;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #353: the server acknowledges each player's furnace/workbench slot intents in the
 * echoes it sends THAT player, so a client can recognise an echo built before its latest
 * snapshot arrived.
 */
class SlotIntentAckTest {

    private static final String GRID = "workbench:grid=";

    private ConnectionRegistry registry;
    private ServerWorldContext ctx;
    private ServerBlockHandler blockHandler;
    private ServerPlayer editor;
    private ServerPlayer watcher;
    private World world;

    @BeforeEach
    void setUp() {
        world = mock(World.class);
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
    }

    private static BlockStateS2C sentTo(ServerPlayer p) {
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(p.connection()).send(sent.capture(), anyBoolean());
        return assertInstanceOf(BlockStateS2C.class, sent.getValue());
    }

    @Test
    void theEditorsEchoCarriesItsLatestSeqAndOthersGetNoAck() {
        blockHandler.handleWorkbenchSlots(editor, new WorkbenchSlotsC2S(1, 64, 1, 41, "B:0:0"), ctx);
        blockHandler.handleWorkbenchSlots(editor, new WorkbenchSlotsC2S(1, 64, 1, 42, "B:0:0"), ctx);
        blockHandler.tick(ctx);

        assertEquals(42, sentTo(editor).ack());
        assertEquals(0, sentTo(watcher).ack());
        assertEquals(GRID, sentTo(watcher).state());
        verify(registry, never()).broadcast(any(), anyBoolean());
    }

    @Test
    void aRefusedIntentIsStillAcknowledged() {
        // Out of reach: nothing applied, but the sender must learn the server is past seq 9,
        // or it would treat every later echo as stale.
        editor.updateState(500f, 64f, 500f, 0f, 0f);
        blockHandler.handleWorkbenchSlots(editor, new WorkbenchSlotsC2S(1, 64, 1, 9, "B:0:0"), ctx);
        blockHandler.tick(ctx);

        assertEquals(9, sentTo(editor).ack());
    }

    @Test
    void acksArePerBlock() {
        blockHandler.handleWorkbenchSlots(editor, new WorkbenchSlotsC2S(1, 64, 1, 5, "B:0:0"), ctx);
        blockHandler.tick(ctx);

        blockHandler.queueStateEcho(2, 64, 1); // another table nobody edited: one plain broadcast
        blockHandler.tick(ctx);

        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(registry).broadcast(sent.capture(), anyBoolean());
        BlockStateS2C echo = assertInstanceOf(BlockStateS2C.class, sent.getValue());
        assertEquals(2, echo.x());
        assertEquals(0, echo.ack());
    }
}
