package com.stonebreak.audio;

import com.openmason.engine.audio.SoundSystem;

import java.io.InputStream;

/**
 * Loads game-owned sound resources into the engine {@link SoundSystem}.
 *
 * <p>The engine audio classes live in the {@code openmason.engine} module, while the {@code .wav}
 * assets live in this game module under {@code /sounds/}. JPMS resource lookup is module-confined,
 * so an engine class cannot resolve a game resource via {@code getResourceAsStream}. This helper
 * resolves the resource against <em>this</em> (game) module and hands the engine a stream to decode,
 * keeping resource location in the game and audio decoding in the engine.
 */
public final class GameSoundLoader {

    private GameSoundLoader() {
    }

    /**
     * Resolves {@code resourcePath} from the game module's resources and loads it under {@code name}.
     *
     * @param soundSystem  the engine sound system to register the decoded buffer with
     * @param name         logical sound name used at playback time
     * @param resourcePath absolute resource path (e.g. {@code "/sounds/GrassWalk.wav"})
     */
    public static void load(SoundSystem soundSystem, String name, String resourcePath) {
        InputStream stream = GameSoundLoader.class.getResourceAsStream(resourcePath);
        if (stream == null) {
            System.err.println("[GameSoundLoader] Sound resource not found in game module: " + resourcePath);
        }
        // The engine handles a null stream (logs + skips) and closes the stream after decoding.
        soundSystem.loadSound(name, stream);
    }
}
