package com.stonebreak.audio;

import com.openmason.engine.audio.MusicDirector;
import com.openmason.engine.audio.SoundEvents;
import com.openmason.engine.audio.SoundSystem;

import java.io.InputStream;

/**
 * Game-owned audio resources for the engine {@link SoundSystem}.
 *
 * <p>JPMS confines resource lookup to the module that asks, so engine code cannot open the
 * {@code .wav}/{@code .ogg} files under this module's {@code /sounds/} and {@code /songs/}. This
 * class opens them here and hands the engine the stream (or an opener) to decode.
 */
public final class GameSoundLoader {

    /** Opens game resources for {@link SoundEvents#resolve} (SBO/SBE resource-referenced sounds). */
    public static final SoundEvents.ResourceOpener RESOURCES = GameSoundLoader::open;

    /** Loads playlist tracks for {@link MusicDirector}. */
    public static final MusicDirector.TrackLoader MUSIC = (sounds, track) -> loadMusic(sounds, track.name(), track.resourcePath());

    private GameSoundLoader() {
    }

    private static InputStream open(String resourcePath) {
        return GameSoundLoader.class.getResourceAsStream(resourcePath);
    }

    /**
     * Resolves {@code resourcePath} from the game module's resources and loads it under {@code name}.
     *
     * @param resourcePath absolute resource path (e.g. {@code "/sounds/GrassWalk.wav"})
     */
    public static void load(SoundSystem soundSystem, String name, String resourcePath) {
        InputStream stream = open(resourcePath);
        if (stream == null) {
            System.err.println("[GameSoundLoader] Sound resource not found in game module: " + resourcePath);
        }
        // The engine handles a null stream (logs + skips) and closes the stream after decoding.
        soundSystem.loadSound(name, stream);
    }

    /** Same resolution as {@link #load}, registered as a streamed music track. */
    public static void loadMusic(SoundSystem soundSystem, String name, String resourcePath) {
        InputStream stream = open(resourcePath);
        if (stream == null) {
            System.err.println("[GameSoundLoader] Music resource not found in game module: " + resourcePath);
        }
        soundSystem.loadMusic(name, stream);
    }
}
