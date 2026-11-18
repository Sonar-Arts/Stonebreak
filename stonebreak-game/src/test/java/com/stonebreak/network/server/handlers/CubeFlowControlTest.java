package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.server.ServerConnection;
import com.openmason.engine.voxel.world.CubePos;
import com.stonebreak.network.server.PlayerCubeView;
import com.stonebreak.network.server.ServerPlayer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Cube flow control and request bookkeeping (cubic voxel runtime, #447; successor of the chunk flow-control
 * test): acks drain the cube AND byte windows and set the rate, a lost ack never stalls streaming for good,
 * requests are range-checked and a re-request of a held cube is a resync only once it can no longer be in
 * flight; unloads forget cubes.
 */
@Tag("regression")
class CubeFlowControlTest {

    private static ServerPlayer player() {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 1);
        sp.updateState(8.0, 64.0, 8.0, 0f, 0f);
        return sp;
    }

    @Test
    void acknowledgedCubesLeaveTheWindowAndSetTheRate() {
        ServerPlayer sp = player();
        PlayerCubeView v = sp.cubeView();
        for (int i = 0; i < 10; i++) {
            v.markSent(new CubePos(i, 2, 0), 1L, 0, 4096);
        }
        assertEquals(10, v.inFlightCubes());
        assertEquals(40960, v.inFlightBytes());

        new ServerChunkHandler().handleCubeAck(sp, 4, 16, 12f);
        assertEquals(6, v.inFlightCubes());
        assertEquals(40960 - 16 * 1024, v.inFlightBytes());
        assertEquals(12f, v.rate());

        new ServerChunkHandler().handleCubeAck(sp, 50, 1000, 100_000f);
        assertEquals(0, v.inFlightCubes(), "never negative");
        assertEquals(0, v.inFlightBytes());
        assertTrue(v.rate() <= ServerChunkHandler.REMOTE_MAX_RATE, "a remote client's asked-for rate is capped");
    }

    @Test
    void aLostAckReopensTheWindowAfterTheWatchdog() {
        PlayerCubeView v = player().cubeView();
        v.markSent(new CubePos(0, 2, 0), 1L, 0, 100);
        for (int i = 1; i < 100; i++) {
            assertFalse(v.ackOverdue(100));
        }
        assertTrue(v.ackOverdue(100));
        assertEquals(0, v.inFlightCubes());
        assertEquals(0, v.inFlightBytes());
    }

    @Test
    void requestsOutsideTheViewOrTheWorldAreIgnored() {
        ServerPlayer sp = player();
        sp.setViewDistanceChunks(8); // 128 blocks = 4 cubes (+ 3 margin)
        ServerChunkHandler handler = new ServerChunkHandler();
        handler.handleCubeRequests(sp, new long[]{
            0, 2, 0,          // here
            6, -12, 0,        // the bottom cube, inside the radius
            0, 20, 0,         // above the world (MAX_CUBE_Y is exclusive)
            0, -13, 0,        // below it
            40, 2, 0,         // far outside the view disc
            1L << 40, 2, 0});
        assertEquals(2, sp.cubeView().requested.size());
        assertTrue(sp.cubeView().requested.containsKey(new CubePos(0, 2, 0)));
        assertTrue(sp.cubeView().requested.containsKey(new CubePos(6, -12, 0)));
    }

    @Test
    void reRequestingAHeldCubeIsAResyncOnlyOnceItCannotBeInFlight() {
        ServerPlayer sp = player();
        ServerChunkHandler handler = new ServerChunkHandler();
        CubePos pos = new CubePos(0, 2, 0);
        sp.cubeView().markSent(pos, 1L, 0, 100); // sent at tick 0, the handler is at tick 0 too
        handler.handleCubeRequests(sp, new long[]{0, 2, 0});
        assertTrue(sp.cubeView().holds(pos), "a request right after the send crossed the cube on the wire");
        assertTrue(sp.cubeView().requested.isEmpty());

        sp.cubeView().forget(pos);
        sp.cubeView().markSent(pos, 1L, -ServerChunkHandler.RESEND_GUARD_TICKS - 1, 100);
        handler.handleCubeRequests(sp, new long[]{0, 2, 0});
        assertFalse(sp.cubeView().holds(pos), "the client lost it: forget and serve the request again");
        assertTrue(sp.cubeView().requested.containsKey(pos));
    }

    @Test
    void unloadsForgetCubesAndTheirRequests() {
        ServerPlayer sp = player();
        ServerChunkHandler handler = new ServerChunkHandler();
        CubePos held = new CubePos(0, 2, 0);
        CubePos asked = new CubePos(1, 2, 0);
        sp.cubeView().markSent(held, 1L, 0, 100);
        sp.cubeView().addRequest(asked, 0);
        sp.cubeView().queueResend(held);
        handler.handleCubesUnloaded(sp, new long[]{0, 2, 0, 1, 2, 0});
        assertFalse(sp.cubeView().holds(held));
        assertTrue(sp.cubeView().requested.isEmpty());
        assertEquals(null, sp.cubeView().pollResend());
    }

    @Test
    void theRequestBudgetBoundsAFlood() {
        ServerPlayer sp = player();
        sp.setViewDistanceChunks(24);
        ServerChunkHandler handler = new ServerChunkHandler();
        // Every cube of a 25 x 32 x 25 box: far more than one burst.
        long[] flood = new long[25 * 32 * 25 * 3];
        int i = 0;
        for (int x = -12; x <= 12; x++) {
            for (int y = -12; y < 20; y++) {
                for (int z = -12; z <= 12; z++) {
                    flood[i++] = x;
                    flood[i++] = y;
                    flood[i++] = z;
                }
            }
        }
        for (int from = 0; from < flood.length; from += 3 * 1024) {
            handler.handleCubeRequests(sp, java.util.Arrays.copyOfRange(flood, from, Math.min(flood.length, from + 3 * 1024)));
        }
        assertTrue(sp.cubeView().requested.size() <= PlayerCubeView.MAX_REQUESTED);
        assertTrue(sp.cubeView().requested.size() < 20_000, "the token bucket stops a flood");
    }
}
