package com.stonebreak.audio;

import com.openmason.engine.audio.MusicDirector;
import com.openmason.engine.audio.SoundSystem;
import com.stonebreak.audio.music.MusicCatalog;
import com.stonebreak.audio.music.MusicCue;
import com.stonebreak.audio.music.MusicCues;
import com.stonebreak.audio.music.MusicPlace;
import com.stonebreak.world.TimeOfDay;
import com.stonebreak.world.World;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Stonebreak's background music, all of it declared in {@link MusicCatalog} ({@code songs/music.json}):
 * the {@code menu} playlist while a menu screen is active, the {@code battle} playlist while a Focus
 * battle runs, and silence during ordinary play apart from one-shot cues (biome themes and the like,
 * {@link #updateCues}). Playlist rules — advance on finish, a one-track playlist loops, coming back to
 * a scene resumes from its NEXT track — are the engine's {@link MusicDirector}.
 *
 * <p>Which scene is active is the caller's to classify — see {@code GameLoop.musicScene()}. Tracks
 * are Ogg Vorbis under {@code /songs/}, streamed while they play, and loaded on their scene's first
 * activation (or {@link #preload}); a cue's track on its first play.
 */
public final class MusicManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(MusicManager.class);

    /** What kind of moment the game is in, as far as music is concerned. */
    public enum Scene {
        NONE, MENU, BATTLE;

        /** The catalog playlist this scene plays ({@code menu}, {@code battle}); none for NONE. */
        String playlist() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final SoundSystem sounds;
    private final MusicDirector<Scene> director;
    private final MusicCues cues;
    /** A cue owns the music voice (only ever during {@link Scene#NONE}). */
    private boolean cuePlaying;

    public MusicManager(SoundSystem soundSystem) {
        this(soundSystem, loadCatalog());
    }

    public MusicManager(SoundSystem soundSystem, MusicCatalog catalog) {
        this.sounds = soundSystem;
        Map<Scene, java.util.List<MusicDirector.Track>> playlists = new EnumMap<>(Scene.class);
        playlists.put(Scene.MENU, catalog.playlist(Scene.MENU.playlist()));
        playlists.put(Scene.BATTLE, catalog.playlist(Scene.BATTLE.playlist()));
        this.director = new MusicDirector<>(soundSystem, playlists, GameSoundLoader.MUSIC);
        this.cues = new MusicCues(catalog.cues());
    }

    /** The shipped catalog; a broken one is logged and leaves the game silent rather than crashing. */
    private static MusicCatalog loadCatalog() {
        try {
            return MusicCatalog.load();
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Music catalog {} failed to load; no music: {}", MusicCatalog.RESOURCE, e.getMessage());
            return MusicCatalog.empty();
        }
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
        if (cuePlaying) {
            if (!sounds.isMusicPlaying()) {
                cuePlaying = false; // finished on its own: it never loops
            } else if (scene != Scene.NONE || !director.isEnabled()) {
                sounds.stopMusic(); // a menu/battle playlist (or the setting) takes over
                cuePlaying = false;
            }
        }
        director.update(scene == Scene.NONE ? null : scene);
    }

    /**
     * Advances the catalog's cues for one frame of ordinary play by a living player whose feet are at
     * {@code feet}, and starts the cue that is due, if any ({@link MusicCues} for when).
     */
    public void updateCues(World world, TimeOfDay time, Vector3dc feet, float deltaTime) {
        boolean night = time != null && time.isNight();
        MusicCue cue = cues.update(night, deltaTime, () -> MusicPlace.of(world, feet));
        if (cue != null) playCue(cue);
    }

    /** Forgets which cues already played: leaving (or not being in) a world. */
    public void resetCues() {
        cues.reset();
    }

    /**
     * Plays {@code cue}'s track once, if music is on and nothing else is playing (ordinary gameplay is
     * otherwise silent). It plays to its end and is not repeated; a menu or battle scene cuts it off.
     * Returns whether it started.
     */
    public boolean playCue(MusicCue cue) {
        if (!director.isEnabled() || director.playingScene() != null || sounds.isMusicPlaying()) {
            return false;
        }
        MusicDirector.Track track = cue.track();
        if (!sounds.isMusicLoaded(track.name())) {
            GameSoundLoader.MUSIC.load(sounds, track);
        }
        if (!sounds.isMusicLoaded(track.name())) return false;
        sounds.playMusic(track.name());
        cuePlaying = true;
        return true;
    }

    /** Loads a scene's tracks ahead of its first activation. Idempotent. */
    public void preload(Scene scene) {
        if (scene != null && scene != Scene.NONE) director.preload(scene);
    }
}
