package com.stonebreak.audio;

import com.openmason.engine.audio.MusicDirector;
import com.openmason.engine.audio.MusicDirector.Track;
import com.openmason.engine.audio.SoundSystem;

import java.util.List;
import java.util.Map;

/**
 * Stonebreak's background music: the menu playlist while a menu screen is active (main menu, world
 * select/creation, multiplayer menus, settings) and the battle playlist while a Focus battle runs;
 * {@link Scene#NONE} is silence (ordinary gameplay). Playlist rules — advance on finish, a one-track
 * playlist loops, coming back to a scene resumes from its NEXT track — are the engine's
 * {@link MusicDirector}; this class holds the playlists.
 *
 * <p>Which scene is active is the caller's to classify — see {@code GameLoop.musicScene()}. Tracks
 * are Ogg Vorbis under {@code /songs/}, streamed while they play, and loaded on their scene's first
 * activation (or {@link #preload}).
 */
public final class MusicManager {

    /** What kind of moment the game is in, as far as music is concerned. */
    public enum Scene { NONE, MENU, BATTLE }

    private static final List<Track> MENU_PLAYLIST = List.of(
            new Track("music_venusian_drift", "/songs/menu music/Venusian Drift.ogg"),
            new Track("music_whispering_woods", "/songs/menu music/Whispering Woods (Lofi Lullaby).ogg")
    );

    // "No More Weakness.ogg" remains bundled for future use.
    private static final List<Track> BATTLE_PLAYLIST = List.of(
            new Track("music_the_deadly_dance", "/songs/battle music/The Deadly Dance.ogg")
    );

    private final MusicDirector<Scene> director;

    public MusicManager(SoundSystem soundSystem) {
        this.director = new MusicDirector<>(soundSystem,
                Map.of(Scene.MENU, MENU_PLAYLIST, Scene.BATTLE, BATTLE_PLAYLIST),
                GameSoundLoader.MUSIC);
    }

    public boolean isEnabled() {
        return director.isEnabled();
    }

    /** Toggles the setting only; {@link #update(float, Scene)} applies it on the next tick. */
    public void setEnabled(boolean enabled) {
        director.setEnabled(enabled);
    }

    public void setVolume(float volume) {
        director.setVolume(volume);
    }

    /**
     * Called every frame regardless of game state. Music plays while the setting is on and
     * {@code scene} is not {@link Scene#NONE}; it stops the instant that stops being true, and a
     * change of scene switches playlist immediately.
     */
    public void update(float deltaTime, Scene scene) {
        director.update(scene == Scene.NONE ? null : scene);
    }

    /** Loads a scene's tracks ahead of its first activation. Idempotent. */
    public void preload(Scene scene) {
        if (scene != null && scene != Scene.NONE) director.preload(scene);
    }
}
