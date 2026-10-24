package com.stonebreak.world.save;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.world.World;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.model.WorldData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The host's {@code player.json} is written from a bound record source — the integrated
 * server's own {@code ServerPlayer}, as Minecraft's integrated server writes the host from its
 * server-side player — not from the client's predicted copy. And a world with no bound player
 * still autosaves its world data: a missing player snapshot skips only {@code player.json}.
 */
@Tag("regression")
@Timeout(30)
class SaveServiceHostRecordTest {

    @TempDir
    Path tempDir;

    @Test
    void playerJsonComesFromTheBoundRecord() throws Exception {
        String worldPath = tempDir.resolve("host-record").toString();
        World world = mock(World.class);
        when(world.getDirtyChunks()).thenReturn(List.of());
        try (SaveService svc = new SaveService(worldPath)) {
            svc.initialize(WorldData.builder().seed(7L).worldName("host-record").build(), null, world);
            svc.bindPlayerSource(name -> {
                PlayerData d = PlayerData.createDefault(name);
                ItemStack[] slots = new ItemStack[36];
                slots[0] = new ItemStack(BlockType.STONE, 33); // what the server's record holds
                return new PlayerData.Builder(d).inventory(slots).build();
            }, Runnable::run);
            svc.saveAll().get(10, TimeUnit.SECONDS);
        }
        try (SaveService reloaded = new SaveService(worldPath)) {
            SaveService.LoadResult result = reloaded.loadWorld().get(10, TimeUnit.SECONDS);
            assertTrue(result.isSuccess(), result.getError());
            ItemStack saved = result.getPlayerData().getInventory()[0];
            assertNotNull(saved);
            assertEquals(33, saved.getCount());
        }
    }

    @Test
    void autosaveWithoutABoundPlayerStillSavesTheWorld() throws Exception {
        String worldPath = tempDir.resolve("no-player").toString();
        World world = mock(World.class);
        when(world.getDirtyChunks()).thenReturn(List.of());
        try (SaveService svc = new SaveService(worldPath)) {
            svc.initialize(WorldData.builder().seed(8L).worldName("no-player").build(), null, world);
            svc.updateWorldData(d -> d.withCheatsEnabled(true));
            svc.performAutoSave();
            assertNotNull(svc.inFlightAutoSaveForTesting(), "the autosave ran without a player");
            svc.inFlightAutoSaveForTesting().get(10, TimeUnit.SECONDS);

            try (SaveService reloaded = new SaveService(worldPath)) {
                SaveService.LoadResult result = reloaded.loadWorld().get(10, TimeUnit.SECONDS);
                assertTrue(result.isSuccess(), result.getError());
                assertTrue(result.getWorldData().isCheatsEnabled(), "the world data reached disk");
            }
        }
    }
}
