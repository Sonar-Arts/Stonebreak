package com.stonebreak.network.server;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.world.save.SaveService;
import com.stonebreak.world.save.WorldStorage;
import com.stonebreak.world.save.model.WorldData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Issue #366: {@link ServerLevel#registerLocalPlayer} must bind the player only. It used to
 * re-run {@code SaveService.initialize} with {@code ServerLevel.worldData} — the copy taken at
 * load time — throwing away the play time each autosave accumulated plus runtime edits.
 */
@Tag("regression")
class ServerLevelSaveBindingTest {

    @TempDir
    Path home;

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void registerLocalPlayerPreservesAccumulatedPlayTime() throws Exception {
        String previous = System.getProperty(WorldStorage.DATA_DIR_PROPERTY);
        System.setProperty(WorldStorage.DATA_DIR_PROPERTY, home.toString());
        try {
            String name = "bind-366";
            assertTrue(WorldStorage.worldDir(name).startsWith(home), "the test never touches the real worlds");
            World world = mock(World.class);
            when(world.getDirtyChunks()).thenReturn(List.of());
            try (MockedStatic<Game> globals = mockStatic(Game.class)) {
                globals.when(Game::getInstance).thenReturn(mock(Game.class));
                globals.when(Game::getWorld).thenReturn(world);
                Player player = new Player(world);
                try (SaveService svc = new SaveService(WorldStorage.worldPath(name))) {
                    svc.initialize(WorldData.builder().seed(99L).worldName(name)
                        .totalPlayTimeMillis(10_000L).build(), player, world);
                    svc.saveAll().get(10, TimeUnit.SECONDS);
                }
            }

            ServerLevel level = ServerLevel.createAndLoad(name, 1L);
            try {
                SaveService svc = level.saveService();
                long base = svc.getWorldData().getTotalPlayTimeMillis();
                assertTrue(base >= 10_000L, "the save kept its play time");

                // Runtime edits (as a /cheats toggle and an autosave would make) that the old
                // registration path discarded along with the play time.
                svc.updateWorldData(d -> d.withAddedPlayTime(3_600_000L).withCheatsEnabled(true));

                Player live = new Player(level.world());
                level.registerLocalPlayer(live);

                WorldData after = svc.getWorldData();
                assertTrue(after.getTotalPlayTimeMillis() >= base + 3_600_000L,
                    "registering the player must not revert play time to the load-time copy");
                assertTrue(after.isCheatsEnabled(),
                    "registering the player must not discard runtime WorldData edits");
            } finally {
                level.cleanup();
            }
        } finally {
            if (previous == null) {
                System.clearProperty(WorldStorage.DATA_DIR_PROPERTY);
            } else {
                System.setProperty(WorldStorage.DATA_DIR_PROPERTY, previous);
            }
        }
    }
}
