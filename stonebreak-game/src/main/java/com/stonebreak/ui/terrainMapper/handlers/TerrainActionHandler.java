package com.stonebreak.ui.terrainMapper.handlers;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager;
import com.stonebreak.ui.worldSelect.managers.WorldDiscoveryManager;
import com.stonebreak.world.save.model.WorldData;
import com.stonebreak.world.save.serialization.JsonWorldSerializer;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Random;

/**
 * Screen-level actions: create world, go back, simulate seed. Owns all
 * business logic for the terrain mapper; handlers call in, renderers never
 * do.
 *
 * World persistence mirrors {@code WorldActionHandler.createNewWorld} —
 * same JSON serializer, same spawn position, same directory layout. That
 * duplication is intentional: coupling world-select's handler into this
 * screen would leak its dialog-era API contract and force the terrain
 * mapper to care about its internal state.
 */
public final class TerrainActionHandler {

    private final TerrainMapperStateManager state;
    private final WorldDiscoveryManager discovery;

    public TerrainActionHandler(TerrainMapperStateManager state, WorldDiscoveryManager discovery) {
        this.state = state;
        this.discovery = discovery;
    }

    public void goBack() {
        state.reset();
        Game.getInstance().setState(GameState.WORLD_SELECT);
    }

    public void simulateSeed() {
        state.randomizeSeed();
    }

    /** Returns true if the world was created and generation was kicked off. */
    public boolean createWorld() {
        String name = state.getWorldName().trim();
        String validationError = discovery.validateWorldName(name);
        if (validationError != null) {
            state.setErrorMessage(validationError);
            return false;
        }

        long seed = state.getResolvedSeed();
        if (seed == 0L && state.getSeedText().isBlank()) {
            seed = new Random().nextLong();
            System.out.println("[WORLD-CREATE] No seed entered; using random seed: " + seed);
        }
        WorldData worldData = WorldData.builder()
                .worldName(name)
                .seed(seed)
                .spawnPosition(new Vector3f(0f, 100f, 0f))
                .createdTime(LocalDateTime.now())
                .lastPlayed(LocalDateTime.now())
                .totalPlayTimeMillis(0L)
                .build();

        if (!discovery.ensureWorldsDirectoryExists()) {
            state.setErrorMessage("Failed to prepare worlds directory.");
            return false;
        }

        try {
            Path worldDir = Paths.get("worlds", name);
            Files.createDirectories(worldDir);
            Files.createDirectories(worldDir.resolve("chunks"));

            byte[] json = new JsonWorldSerializer().serialize(worldData);
            Files.write(worldDir.resolve("world.json"), json);
            Files.write(worldDir.resolve("metadata.json"), json);
        } catch (IOException e) {
            state.setErrorMessage("Save failed: " + e.getMessage());
            return false;
        }

        state.setErrorMessage(null);
        state.reset();
        Game.getInstance().startWorldGeneration(name, seed);
        return true;
    }
}
