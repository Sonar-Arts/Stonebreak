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

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Issue #318: a runtime world setting written through {@link SaveService#updateWorldData}
 * (how {@code ServerLevel} persists the /cheats flag) survives a save and reload, without
 * discarding the play time the service accumulates.
 */
@Tag("regression")
@Timeout(30)
class SaveServiceWorldSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    void cheatsToggleSurvivesSaveAndReload() throws Exception {
        String worldPath = tempDir.resolve("cheats-world").toString();
        WorldData initial = WorldData.builder()
            .seed(7L)
            .worldName("cheats-world")
            .totalPlayTimeMillis(5_000L)
            .build();
        assertFalse(initial.isCheatsEnabled());

        World world = mock(World.class);
        Game game = mock(Game.class);
        try (MockedStatic<Game> globals = mockStatic(Game.class)) {
            globals.when(Game::getInstance).thenReturn(game);
            globals.when(Game::getWorld).thenReturn(world);
            Player player = new Player(world);

            try (SaveService svc = new SaveService(worldPath)) {
                svc.initialize(initial, player, world);
                svc.updateWorldData(d -> d.withCheatsEnabled(true));
                assertTrue(svc.getWorldData().isCheatsEnabled());

                svc.saveAll().get(10, TimeUnit.SECONDS);
                // The save snapshot must keep the edit, not revert to the initialized copy.
                assertTrue(svc.getWorldData().isCheatsEnabled());
            }
        }

        try (SaveService reloaded = new SaveService(worldPath)) {
            SaveService.LoadResult result = reloaded.loadWorld().get(10, TimeUnit.SECONDS);
            assertTrue(result.isSuccess(), result.getError());
            WorldData loaded = result.getWorldData();
            assertTrue(loaded.isCheatsEnabled(), "cheats flag should persist with the world");
            assertEquals(7L, loaded.getSeed());
            assertTrue(loaded.getTotalPlayTimeMillis() >= 5_000L, "play time must not be reset");
        }
    }

    @Test
    void updateBeforeInitializeIsANoOp() throws Exception {
        try (SaveService svc = new SaveService(tempDir.resolve("uninit").toString())) {
            svc.updateWorldData(d -> d.withCheatsEnabled(true));
            assertNull(svc.getWorldData());
        }
    }
}
