package com.stonebreak.core.lifecycle;

import static org.mockito.Mockito.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.stonebreak.core.Game;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Regression test for issue #364: closing the game window must tear down the
 * {@link MultiplayerSession} during {@link GameShutdown#shutdown}, so the integrated
 * server's SaveService is flushed exactly like Quit to Menu does.
 */
class GameShutdownTest {

    @Test
    void shutdownTearsDownNetworkSession() {
        Game game = mock(Game.class);
        try (MockedStatic<Game> globals = mockStatic(Game.class);
             MockedStatic<MultiplayerSession> net = mockStatic(MultiplayerSession.class);
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            globals.when(Game::getWorld).thenReturn(null);
            GameShutdown.shutdown(game, executor);
            net.verify(MultiplayerSession::shutdown, times(1));
        }
    }

    @Test
    void shutdownTearsDownNetworkSessionWithWorld() {
        Game game = mock(Game.class);
        World world = mock(World.class);
        try (MockedStatic<Game> globals = mockStatic(Game.class);
             MockedStatic<MultiplayerSession> net = mockStatic(MultiplayerSession.class);
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            globals.when(Game::getWorld).thenReturn(world);
            GameShutdown.shutdown(game, executor);
            net.verify(MultiplayerSession::shutdown, times(1));
            verify(world).cleanup();
        }
    }
}
