package com.stonebreak.core.cheats;

import com.stonebreak.core.services.GameServices;
import com.stonebreak.core.world.WorldSession;
import com.stonebreak.world.save.SaveService;
import com.stonebreak.world.save.model.WorldData;

/**
 * Runtime cheats toggle and its persistence hook. Holds the in-session flag and,
 * when toggled from inside a world, pushes the change into the session's
 * {@link WorldData} and {@link SaveService} so it survives the next save.
 */
public final class CheatState {

    private final GameServices services;
    private final WorldSession session;
    private boolean cheatsEnabled = false;

    public CheatState(GameServices services, WorldSession session) {
        this.services = services;
        this.session = session;
    }

    /**
     * Sets the runtime cheats flag. Does not modify any world's persisted
     * cheats state — use {@link #applyToCurrentWorld(boolean)} when the user
     * toggles cheats from inside a world session.
     */
    public void setEnabled(boolean enabled) {
        this.cheatsEnabled = enabled;
    }

    /** Returns whether cheats are enabled. */
    public boolean isEnabled() {
        return cheatsEnabled;
    }

    /**
     * Toggles cheats for the active world: updates the runtime flag, the
     * in-memory {@link WorldData}, and the {@link SaveService} so the change
     * persists on the next save. No-op when no world is loaded.
     */
    public void applyToCurrentWorld(boolean enabled) {
        this.cheatsEnabled = enabled;
        WorldData current = session.currentWorldData();
        if (current != null) {
            WorldData updated = current.withCheatsEnabled(enabled);
            session.setCurrentWorldData(updated);
            SaveService saveService = session.saveService();
            if (saveService != null && services.player() != null && services.world() != null) {
                saveService.initialize(updated, services.player(), services.world());
            }
        }
    }
}
