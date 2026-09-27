package com.stonebreak.world.generation.diffusion;

/**
 * A tile request was withdrawn before it was answered: its tile cache was closed (the terrain
 * mapper rebuilding for a new seed, a world unloading) or TGMPipe was stopped.
 *
 * <p>A subtype so that the meaning can depend on who asked. Chunk generation still fails loudly
 * (it never closes its own cache mid-generation, so this reaching it is a real fault). The
 * terrain-mapper preview treats it as superseded work: a sampling pass in flight when the seed
 * changes holds the old cache, and its unanswered tiles are expected and already unwanted.
 */
public class TileRequestCancelledException extends TGMPipeException {
    public TileRequestCancelledException(String message) {
        super(message);
    }
}
