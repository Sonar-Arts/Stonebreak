package com.stonebreak.audio;

import com.openmason.engine.audio.MusicDirector;
import com.openmason.engine.audio.MusicDirector.Track;
import com.openmason.engine.audio.SoundSystem;

import java.util.List;
import java.util.Map;

/**
 * Stonebreak's background music: the menu playlist while a menu screen is active (main menu, world
 * select/creation, multiplayer menus, settings) and the battle playlist while a Focus battle runs;
 * {@link Scene#NONE} is silence (ordinary gameplay) apart from one-shot themes such as the Shade's
 * nightfall theme ({@link #playShadeTheme()}). Playlist rules — advance on finish, a one-track
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

    // Played in order from the first track (engine MusicDirector), then rotating: Look Beyond is the
    // main menu theme, so it is what a fresh launch opens on.
    private static final List<Track> MENU_PLAYLIST = List.of(
            new Track("music_look_beyond", "/songs/menu music/Look Beyond.ogg"),
            new Track("music_venusian_drift", "/songs/menu music/Venusian Drift.ogg"),
            new Track("music_whispering_woods", "/songs/menu music/Whispering Woods (Lofi Lullaby).ogg")
    );

    // "No More Weakness.ogg" remains bundled for future use.
    private static final List<Track> BATTLE_PLAYLIST = List.of(
            new Track("music_the_deadly_dance", "/songs/battle music/The Deadly Dance.ogg")
    );

    /**
     * The Shade's nightfall theme: a one-shot (never looped, never a playlist) started by
     * {@link #playShadeTheme()} — see {@link ShadeNightMusic} for when.
     */
    static final Track SHADE_THEME = new Track("music_the_shade", "/songs/biome music/The Shade.ogg");

    private final SoundSystem sounds;
    private final MusicDirector<Scene> director;
    /** A one-shot theme owns the music voice (only ever during {@link Scene#NONE}). */
    private boolean oneShotPlaying;

    public MusicManager(SoundSystem soundSystem) {
        this.sounds = soundSystem;
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
        if (oneShotPlaying) {
            if (!sounds.isMusicPlaying()) {
                oneShotPlaying = false; // finished on its own: it never loops
            } else if (scene != Scene.NONE || !director.isEnabled()) {
                sounds.stopMusic(); // a menu/battle playlist (or the setting) takes over
                oneShotPlaying = false;
            }
        }
        director.update(scene == Scene.NONE ? null : scene);
    }

    /**
     * Plays the Shade's nightfall theme once, if music is on and nothing else is playing (ordinary
     * gameplay is otherwise silent). It plays to its end and is not repeated; a menu or battle scene
     * cuts it off. Returns whether it started.
     */
    public boolean playShadeTheme() {
        if (!director.isEnabled() || director.playingScene() != null || sounds.isMusicPlaying()) {
            return false;
        }
        if (!sounds.isMusicLoaded(SHADE_THEME.name())) {
            GameSoundLoader.MUSIC.load(sounds, SHADE_THEME);
        }
        if (!sounds.isMusicLoaded(SHADE_THEME.name())) return false;
        sounds.playMusic(SHADE_THEME.name());
        oneShotPlaying = true;
        return true;
    }

    /** Loads a scene's tracks ahead of its first activation. Idempotent. */
    public void preload(Scene scene) {
        if (scene != null && scene != Scene.NONE) director.preload(scene);
    }
}
