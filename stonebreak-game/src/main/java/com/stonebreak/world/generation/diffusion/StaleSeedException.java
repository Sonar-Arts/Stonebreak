package com.stonebreak.world.generation.diffusion;

/**
 * The bridge rejected a request because it is pinned to a different seed than the one the caller
 * asked for (its HTTP 400 — the only 400 it produces, see terrain-bridge/bridge/main.py
 * {@code _require_matching_seed}).
 *
 * <p>Deliberately a subtype rather than a swallowed error: the meaning depends entirely on who
 * asked.
 *
 * <ul>
 *   <li><b>Chunk generation</b> must still fail loudly. A mismatch there means the services were
 *       re-pinned under a live world, and quietly accepting those tiles would splice terrain from
 *       another seed's model into the world — precisely the silent corruption plan.md Phase 2
 *       forbids. Because this extends {@link TerrainBridgeException}, that path is unchanged.</li>
 *   <li><b>The terrain-mapper preview</b> should treat it as superseded work, not a failure. Its
 *       visualizers and tile cache are rebuilt per seed, so a sampling pass still in flight when
 *       the seed changes holds the old cache and keeps asking for the old seed until it notices it
 *       has been abandoned. Those requests are expected, harmless, and their results are already
 *       unwanted — reporting them as "Terrain preview failed" would be describing normal
 *       hand-off as breakage.</li>
 * </ul>
 */
public class StaleSeedException extends TerrainBridgeException {
    public StaleSeedException(String message) {
        super(message);
    }
}
