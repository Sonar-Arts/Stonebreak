package com.stonebreak.audio;

import com.stonebreak.core.Game;

/**
 * Where data-driven sound plays actually run: on the main thread, which owns the OpenAL context.
 *
 * <p>Inline when already there — every current caller is — and otherwise handed over through
 * {@link Game#runOnMainThread}, never played in place. Mob AI, damage and block simulation tick on
 * the integrated server's thread, and a sound reached from there used to drive the client's mixer
 * from the wrong thread (#340). Handing over keeps the in-process host audible without that; it
 * does not make a server-side sound reach remote clients — server code replicates those instead
 * ({@link EntitySounds#emit}, {@code SoundEventS2C}, #344).
 */
final class MainThreadAudio {

    private MainThreadAudio() {
    }

    static void run(Runnable play) {
        Game game = Game.getInstance();
        if (game != null) {
            game.runOnMainThread(play);
        }
    }
}
