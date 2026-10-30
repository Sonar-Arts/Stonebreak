package com.stonebreak.network.server;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.world.save.SaveService;
import com.stonebreak.world.save.WorldStorage;
import com.stonebreak.world.save.model.WorldData;
import org.joml.Vector3f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * The world spawn is the world's, not the returning player's (#302): loading a save used to make the
 * player's saved position the world spawn, so every death respawned where the world was last saved.
 */
@Tag("integration")
class ServerLevelSpawnTest {

    @TempDir
    Path home;

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void aSavedPlayerPositionNeverBecomesTheWorldSpawn() throws Exception {
        String previous = System.getProperty(WorldStorage.DATA_DIR_PROPERTY);
        System.setProperty(WorldStorage.DATA_DIR_PROPERTY, home.toString());
        try {
            String name = "spawn-302";
            // Checked before anything is written, so a run that cannot redirect saves fails clean.
            assertTrue(WorldStorage.worldDir(name).startsWith(home), "the test never touches the real worlds");
            Vector3f worldSpawn = new Vector3f(8.5f, 90f, 8.5f);
            Vector3f saved = new Vector3f(400f, 70f, -300f);
            World world = mock(World.class);
            try (MockedStatic<Game> globals = mockStatic(Game.class)) {
                globals.when(Game::getInstance).thenReturn(mock(Game.class));
                globals.when(Game::getWorld).thenReturn(world);
                Player player = new Player(world);
                player.setPosition(saved.x, saved.y, saved.z);
                try (SaveService svc = new SaveService(WorldStorage.worldPath(name))) {
                    svc.initialize(WorldData.builder().seed(4242L).worldName(name)
                        .spawnPosition(worldSpawn).hasExplicitSpawn(true).build(), player, world);
                    svc.saveAll().get(10, TimeUnit.SECONDS);
                }
            }

            ServerLevel level = ServerLevel.createAndLoad(name, 1L);
            try {
                assertEquals(saved, level.loadedPlayerData().getPosition(), "the player still arrives where they saved");
                Vector3f spawn = level.world().getSpawnPosition();
                assertEquals(spawn, level.spawn(), "one spawn for the handshake and for respawns");
                assertEquals(worldSpawn.x, spawn.x, "the world's own spawn column (snapped to its surface)");
                assertEquals(worldSpawn.z, spawn.z, "a block-centred spawn stays in its own column");
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
