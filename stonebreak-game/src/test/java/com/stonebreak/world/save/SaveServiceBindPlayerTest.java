package com.stonebreak.world.save;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.world.save.model.WorldData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.joml.Vector3f;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Issue #366: registering the local player with a save service rebinds only the player.
 * The old path re-ran {@code initialize} with the caller's load-time {@link WorldData}
 * copy, discarding both the play time accumulated since load and runtime edits like the
 * per-world cheats flag.
 */
@Tag("regression")
@Timeout(30)
class SaveServiceBindPlayerTest {

    @TempDir
    Path tempDir;

    @Test
    void bindPlayerKeepsAccumulatedWorldData() throws Exception {
        String worldPath = tempDir.resolve("bind-player").toString();
        World world = mock(World.class);
        when(world.getDirtyChunks()).thenReturn(List.of());

        try (MockedStatic<Game> globals = mockStatic(Game.class)) {
            Game game = mock(Game.class);
            globals.when(Game::getInstance).thenReturn(game);
            globals.when(Game::getWorld).thenReturn(world);

            WorldData atLoad = WorldData.builder()
                .seed(11L)
                .worldName("bind-player")
                .totalPlayTimeMillis(5_000L)
                .build();
            Player stale = new Player(world);
            stale.setPosition(1f, 2f, 3f);
            Player live = new Player(world);
            live.setPosition(77f, 80f, 99f);

            try (SaveService svc = new SaveService(worldPath)) {
                svc.initialize(atLoad, stale, world);
                svc.updateWorldData(d -> d.withAddedPlayTime(60_000L).withCheatsEnabled(true));
                svc.bindPlayer(live);
                svc.saveAll().get(10, TimeUnit.SECONDS);
            }
        }

        try (SaveService reloaded = new SaveService(worldPath)) {
            SaveService.LoadResult result = reloaded.loadWorld().get(10, TimeUnit.SECONDS);
            assertTrue(result.isSuccess(), result.getError());
            assertTrue(result.getWorldData().getTotalPlayTimeMillis() >= 65_000L,
                "registering the player must not reset the accumulated play time");
            assertTrue(result.getWorldData().isCheatsEnabled(),
                "registering the player must not discard runtime WorldData edits");
            assertEquals(new Vector3f(77f, 80f, 99f), result.getPlayerData().getPosition(),
                "the rebound (live) player is the one persisted");
        }
    }
}
