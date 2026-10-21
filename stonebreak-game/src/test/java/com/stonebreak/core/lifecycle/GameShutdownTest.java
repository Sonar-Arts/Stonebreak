package com.stonebreak.core.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.stonebreak.core.Game;
import com.stonebreak.core.world.WorldLifecycle;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Regression test for issue #364: closing the game window must tear down the
 * {@link MultiplayerSession} during {@link GameShutdown#shutdown}, so the integrated
 * server's SaveService is flushed exactly like Quit to Menu does. The session shutdown
 * must run after the crafting-grid return (issue #307) and before the world cleanup,
 * or the final save is missing data. Ordering is checked through a shared log because
 * Mockito's {@code InOrder} does not accept {@link MockedStatic} participants.
 */
class GameShutdownTest {

    @Test
    void shutdownFlushesSessionBetweenCraftingGridsAndWorldCleanup() {
        Game game = mock(Game.class);
        World world = mock(World.class);
        List<String> order = new ArrayList<>();
        try (MockedStatic<Game> globals = mockStatic(Game.class);
             MockedStatic<WorldLifecycle> lifecycle = mockStatic(WorldLifecycle.class);
             MockedStatic<MultiplayerSession> net = mockStatic(MultiplayerSession.class);
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            globals.when(Game::getWorld).thenReturn(world);
            lifecycle.when(() -> WorldLifecycle.returnCraftingGridsToPlayer(game))
                    .thenAnswer(inv -> order.add("crafting-grids"));
            net.when(MultiplayerSession::shutdown)
                    .thenAnswer(inv -> order.add("session-shutdown"));
            doAnswer(inv -> order.add("world-cleanup")).when(world).cleanup();

            GameShutdown.shutdown(game, executor);

            assertEquals(List.of("crafting-grids", "session-shutdown", "world-cleanup"), order);
        }
    }
}
