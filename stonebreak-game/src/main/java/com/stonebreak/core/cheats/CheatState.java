package com.stonebreak.core.cheats;

/**
 * Runtime cheats toggle. The flag is a per-world setting owned by the world's server
 * ({@code ServerLevel} persists it in {@code WorldData}); this class holds the client's
 * mirror of it and routes in-world toggles to that authority. The server echoes the
 * authoritative value back via {@code CheatsStateS2C} → {@link #setEnabled(boolean)}, both
 * on join (restoring a loaded world's saved flag) and after every accepted toggle.
 */
public final class CheatState {

    /** The world authority a toggle is requested from. */
    @FunctionalInterface
    public interface Authority {
        /** Ask the authority to adopt {@code enabled}; false = this client may not change it. */
        boolean requestSet(boolean enabled);
    }

    private final Authority authority;
    private volatile boolean cheatsEnabled = false;

    public CheatState(Authority authority) {
        this.authority = authority;
    }

    /**
     * Sets the runtime cheats flag without contacting the authority — used to adopt the
     * server's value and to reset on return to the main menu.
     */
    public void setEnabled(boolean enabled) {
        this.cheatsEnabled = enabled;
    }

    /** Returns whether cheats are enabled. */
    public boolean isEnabled() {
        return cheatsEnabled;
    }

    /**
     * Toggles cheats for the active world: asks the authority to set (and persist) the flag,
     * then applies it locally for immediate feedback. Returns false, leaving the flag
     * unchanged, when the authority refuses (e.g. a remote client on someone else's world).
     */
    public boolean applyToCurrentWorld(boolean enabled) {
        if (!authority.requestSet(enabled)) {
            return false;
        }
        this.cheatsEnabled = enabled;
        return true;
    }
}
