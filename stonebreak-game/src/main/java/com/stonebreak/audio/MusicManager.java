package com.stonebreak.audio;

import com.openmason.engine.audio.SoundSystem;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Plays background music from small fixed playlists, one per {@link Scene}: the menu playlist while a
 * menu screen is active (main menu, world select/creation, multiplayer menus, settings) and the
 * battle playlist while a Focus battle is running. Within a scene it advances to the next track
 * whenever the current one finishes (a one-track playlist therefore loops). Changing scene stops the
 * old track at once and starts the new scene's playlist from its first track; {@link Scene#NONE}
 * is silence (ordinary gameplay).
 *
 * <p>Which scene is active is the caller's responsibility to classify, not this class's — see
 * {@code GameLoop.musicScene()}. Tracks are large (~30MB+ .wav files under {@code /songs/}) so each
 * scene's tracks are decoded lazily on its first activation rather than eagerly at startup alongside
 * the tiny SFX in {@code GameBootstrap.configureSoundSystem}.
 */
public final class MusicManager {

    /** What kind of moment the game is in, as far as music is concerned. */
    public enum Scene { NONE, MENU, BATTLE }

    private record Track(String name, String resourcePath) {
    }

    private static final List<Track> MENU_PLAYLIST = List.of(
            new Track("music_venusian_drift", "/songs/menu music/Venusian Drift.wav"),
            new Track("music_whispering_woods", "/songs/menu music/Whispering Woods (Lofi Lullaby).wav")
    );

    private static final List<Track> BATTLE_PLAYLIST = List.of(
            new Track("music_no_more_weakness", "/songs/battle music/No More Weakness.wav")
    );

    private final SoundSystem soundSystem;
    private final Set<Scene> loadedScenes = EnumSet.noneOf(Scene.class);

    private boolean enabled;
    private Scene playingScene = Scene.NONE;
    private int currentTrackIndex = -1;

    public MusicManager(SoundSystem soundSystem) {
        this.soundSystem = soundSystem;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Toggles the setting only — whether a track is actually audible is decided by
     * {@link #update(float, Scene)}'s scene on the next tick.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setVolume(float volume) {
        soundSystem.setMusicVolume(volume);
    }

    /**
     * Called every frame regardless of game state. Music plays while the setting is on and
     * {@code scene} is not {@link Scene#NONE}; it stops the instant that stops being true, and a
     * change of scene switches playlist immediately.
     */
    public void update(float deltaTime, Scene scene) {
        Scene wanted = enabled && scene != null ? scene : Scene.NONE;
        if (wanted != playingScene) {
            if (playingScene != Scene.NONE) {
                soundSystem.stopMusic();
            }
            playingScene = wanted;
            currentTrackIndex = -1; // every scene starts from its first track
        }
        if (wanted == Scene.NONE) {
            return;
        }
        ensureTracksLoaded(wanted);
        if (!soundSystem.isMusicPlaying()) {
            playTrack(wanted, nextTrackIndex(wanted));
        }
    }

    /**
     * Decodes a scene's tracks now instead of on its first activation, so the (large, synchronous)
     * decode can be paid at a moment of the caller's choosing rather than as a hitch when the scene
     * starts. Idempotent.
     */
    public void preload(Scene scene) {
        if (scene != null && scene != Scene.NONE) {
            ensureTracksLoaded(scene);
        }
    }

    private static List<Track> playlist(Scene scene) {
        return scene == Scene.BATTLE ? BATTLE_PLAYLIST : MENU_PLAYLIST;
    }

    private void ensureTracksLoaded(Scene scene) {
        if (!loadedScenes.add(scene)) return;
        for (Track track : playlist(scene)) {
            GameSoundLoader.loadMusic(soundSystem, track.name(), track.resourcePath());
        }
    }

    private int nextTrackIndex(Scene scene) {
        return (currentTrackIndex + 1) % playlist(scene).size();
    }

    private void playTrack(Scene scene, int index) {
        currentTrackIndex = index;
        soundSystem.playMusic(playlist(scene).get(index).name());
    }
}
