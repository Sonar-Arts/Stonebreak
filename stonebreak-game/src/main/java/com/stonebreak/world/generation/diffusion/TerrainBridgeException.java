package com.stonebreak.world.generation.diffusion;

/**
 * Thrown when the terrain bridge is unreachable, returns a non-2xx status,
 * or returns a payload that doesn't match its own headers. Deliberately
 * unchecked and never caught on the production chunk-generation path —
 * plan.md Phase 2 is explicit that there is no fallback to noise generation;
 * a chunk that can't reach the bridge must fail loudly, not silently
 * substitute different terrain.
 */
public class TerrainBridgeException extends RuntimeException {
    public TerrainBridgeException(String message) {
        super(message);
    }

    public TerrainBridgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
