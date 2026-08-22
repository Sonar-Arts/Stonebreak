package com.stonebreak.core.world;

import com.stonebreak.world.save.SaveService;
import com.stonebreak.world.save.model.WorldData;

/**
 * Bookkeeping for the world currently in session: its name, seed, in-memory
 * {@link WorldData} and the {@link SaveService} that persists it. Mutated by
 * {@link WorldLifecycle} and the client-world bootstrap; exposed through
 * {@link com.stonebreak.core.Game}'s delegating getters/setters.
 */
public final class WorldSession {

    private SaveService saveService;
    private WorldData currentWorldData;
    private String currentWorldName;
    private long currentWorldSeed;

    public SaveService saveService() { return saveService; }
    public void setSaveService(SaveService saveService) { this.saveService = saveService; }

    public WorldData currentWorldData() { return currentWorldData; }
    public void setCurrentWorldData(WorldData currentWorldData) { this.currentWorldData = currentWorldData; }

    public String currentWorldName() { return currentWorldName; }
    public void setCurrentWorldName(String currentWorldName) { this.currentWorldName = currentWorldName; }

    public long currentWorldSeed() { return currentWorldSeed; }
    public void setCurrentWorldSeed(long currentWorldSeed) { this.currentWorldSeed = currentWorldSeed; }
}
