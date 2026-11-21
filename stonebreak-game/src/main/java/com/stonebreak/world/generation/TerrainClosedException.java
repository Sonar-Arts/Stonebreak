package com.stonebreak.world.generation;

/**
 * A terrain query refused because its terrain is shutting down: the native generator is closing or closed
 * ({@link NativeCubeGenerator#close()}), or a DIFFUSION world's tile service was stopped on purpose (quit, world
 * switch). Expected during teardown, so callers drop the result quietly instead of logging a failure.
 */
public final class TerrainClosedException extends IllegalStateException {

    public TerrainClosedException(String message) {
        super(message);
    }

    public TerrainClosedException(String message, Throwable cause) {
        super(message, cause);
    }

    /** True when {@code t} or one of its causes is a {@link TerrainClosedException}. */
    public static boolean isCause(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof TerrainClosedException) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }
}
