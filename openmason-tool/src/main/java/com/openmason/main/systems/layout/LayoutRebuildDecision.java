package com.openmason.main.systems.layout;

/**
 * Whether the main dockspace layout should be rebuilt from defaults.
 *
 * <p>Pure and separate from the builder so the migration matrix can be pinned by tests —
 * getting this wrong either strands a new window outside the dock (it opens floating and
 * looks broken) or silently wipes a layout the user arranged themselves.
 */
public final class LayoutRebuildDecision {

    private LayoutRebuildDecision() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * @param hasSavedLayout  whether imgui.ini restored a real split layout
     * @param storedVersion   layout version recorded for this user (0 = never built)
     * @param currentVersion  the version this build ships
     * @param resetRequested  the user asked for View -> Layout -> Reset
     */
    public static boolean shouldRebuild(boolean hasSavedLayout, int storedVersion,
                                        int currentVersion, boolean resetRequested) {
        if (resetRequested) {
            return true;
        }
        if (!hasSavedLayout) {
            return true;
        }
        return storedVersion < currentVersion;
    }
}
