package com.stonebreak.audio;

import com.openmason.engine.audio.SoundSystem;

import java.util.List;

/**
 * Plays background music from a small fixed playlist while a menu screen is active (main menu,
 * singleplayer world select/creation, multiplayer menus, settings), advancing to the next track
 * whenever the current one finishes. Stops the instant gameplay actually starts (loading/playing)
 * and resumes on return to a menu — see {@code GameLoop.MUSIC_ACTIVE_STATES} for the exact state
 * list, which is the caller's responsibility to classify, not this class's. Tracks are large
 * (~30MB+ .wav files under {@code /songs/}) so they're decoded lazily on first activation rather
 * than eagerly at startup alongside the tiny SFX in {@code GameBootstrap.configureSoundSystem}.
 */
public final class MusicManager {

    private record Track(String name, String resourcePath) {
    }

    private static final List<Track> PLAYLIST = List.of(
            new Track("music_venusian_drift", "/songs/menu music/Venusian Drift.wav"),
            new Track("music_whispering_woods", "/songs/menu music/Whispering Woods (Lofi Lullaby).wav")
    );

    private final SoundSystem soundSystem;

    private boolean enabled;
    private boolean tracksLoaded;
    private boolean playing;
    private int currentTrackIndex = -1;

    public MusicManager(SoundSystem soundSystem) {
        this.soundSystem = soundSystem;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Toggles the setting only — whether a track is actually audible is decided by
     * {@link #update(float, boolean)}'s {@code menuActive} flag on the next tick.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setVolume(float volume) {
        soundSystem.setMusicVolume(volume);
    }

    /**
     * Called every frame regardless of game state. Music only plays while {@code menuActive} is
     * true; it stops the instant that flag goes false and resumes (from the next playlist track)
     * the next time it goes true.
     */
    public void update(float deltaTime, boolean menuActive) {
        boolean shouldPlay = enabled && menuActive;
        if (shouldPlay) {
            if (!playing) {
                ensureTracksLoaded();
            }
            if (!soundSystem.isMusicPlaying()) {
                playTrack(nextTrackIndex());
            }
        } else if (playing) {
            soundSystem.stopMusic();
        }
        playing = shouldPlay;
    }

    private void ensureTracksLoaded() {
        if (tracksLoaded) return;
        for (Track track : PLAYLIST) {
            GameSoundLoader.loadMusic(soundSystem, track.name(), track.resourcePath());
        }
        tracksLoaded = true;
    }

    private int nextTrackIndex() {
        return (currentTrackIndex + 1) % PLAYLIST.size();
    }

    private void playTrack(int index) {
        currentTrackIndex = index;
        soundSystem.playMusic(PLAYLIST.get(index).name());
    }
}
