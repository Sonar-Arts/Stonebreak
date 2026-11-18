package com.stonebreak.ui.terrainMapper.handlers;

import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager;
import com.stonebreak.ui.worldSelect.managers.WorldDiscoveryManager;

import java.io.IOException;
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

    public void centerOnSpawn() {
        if (!state.hasSpawnPoint()) return;
        state.getViewport().centerOn(state.spawnWorldX(), state.spawnWorldZ());
    }

    public void goBack() {
        // Leaving without a world: nothing needs the Diffusion services this screen started.
        state.stopGeneratorServices();
        state.reset();
        Game.getInstance().setState(GameState.WORLD_SELECT);
    }

    public void goToCharacterCreation() {
        Game.getInstance().setState(GameState.CHARACTER_CREATION);
    }

    public void simulateSeed() {
        state.randomizeSeed();
    }

    /** Returns true if the world was created and generation was kicked off. */
    public boolean createWorld() {
        TerrainGeneratorType generator = state.getSelectedGenerator();
        if (generator == null) {
            state.setErrorMessage("Choose a terrain generator.");
            return false;
        }
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
        if (!discovery.ensureWorldsDirectoryExists()) {
            state.setErrorMessage("Failed to prepare worlds directory.");
            return false;
        }

        try {
            com.stonebreak.core.world.NewWorlds.write(new com.stonebreak.core.world.NewWorlds.Options(name, seed,
                generator, state.getSelectedScale(),
                new org.joml.Vector3d(state.hasSpawnPoint() ? state.spawnWorldX() : 0, 100,
                    state.hasSpawnPoint() ? state.spawnWorldZ() : 0),
                state.hasSpawnPoint()));
        } catch (IOException e) {
            state.setErrorMessage("Save failed: " + e.getMessage());
            return false;
        }

        state.setErrorMessage(null);
        state.reset();
        // Leave this screen immediately: state.reset() above already blanked the world-name
        // field and randomized the seed for the mapper's own next use, and until GameState
        // moves off TERRAIN_MAPPER the screen keeps rendering and ticking — which would start
        // sampling that throwaway seed, queueing preview tiles beside the world we just asked
        // to start. LoadingScreen.show() is idempotent; startClientWorld
        // calls it again once WelcomeS2C actually arrives.
        Game.getInstance().getLoadingScreen().show();
        // Two-world model: start the integrated server (it will load the just-written world)
        // + local client. The client builds the render world from WelcomeS2C.
        com.stonebreak.network.MultiplayerSession.startSingleplayer(name, seed);
        return true;
    }
}
