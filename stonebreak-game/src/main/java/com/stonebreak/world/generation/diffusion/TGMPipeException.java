package com.stonebreak.world.generation.diffusion;

/**
 * TGMPipe could not produce a tile: it failed to start, crashed past its restart
 * budget, or reported an error for the tile. Deliberately unchecked and never caught on the
 * production chunk-generation path — there is no fallback to noise generation; a chunk that
 * cannot get its tile must fail loudly, not silently substitute different terrain.
 */
public class TGMPipeException extends RuntimeException {
    public TGMPipeException(String message) {
        super(message);
    }

    public TGMPipeException(String message, Throwable cause) {
        super(message, cause);
    }
}
