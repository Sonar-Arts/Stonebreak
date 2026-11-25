package com.stonebreak.audio;

import com.openmason.engine.audio.DelayedSoundQueue;
import com.openmason.engine.audio.SoundSystem;
import com.stonebreak.world.World;
import com.stonebreak.world.weather.ClientWeather;
import com.stonebreak.world.weather.CloudColumn;
import com.stonebreak.world.weather.WeatherSurface;
import org.joml.Vector3dc;

/**
 * The sound of the weather at the listener: a rain loop that swells under
 * raining cloud, falls silent in snow, above the cloud and inside it, muffles
 * under a roof and dies away with depth under ground; and thunder, arriving as
 * late after each lightning flash as sound takes to travel, fading out deeper
 * down. Depth is the world above the ear around it ({@link WeatherSurface#coverAround}),
 * known or estimated, so a cave under unloaded (LOD-only) ground or under a
 * skylight is still underground.
 * Thunder is played 2D — the engine's positional sources fade out within
 * 50 blocks, and thunder must carry for over a kilometre.
 */
public final class WeatherAudio {

    public static final String RAIN_LOOP = "weather_rain";
    private static final String[] THUNDER = {"weather_thunder_1", "weather_thunder_2"};

    /** Loop volume at the heaviest rain. */
    private static final float RAIN_VOLUME = 0.7f;
    /** Share of the rain heard with a roof overhead. */
    static final float ROOFED = 0.3f;
    /** Blocks of cover over the ear that are only a roof: the rain is muffled ({@link #ROOFED}), not fading. */
    static final double ROOF_DEPTH = 8.0;
    /** Blocks of cover at which the rain is no longer heard at all. */
    static final double RAIN_SILENT_DEPTH = 24.0;
    /** Blocks of cover at which thunder, which carries through rock as a rumble, is no longer heard. */
    static final double THUNDER_SILENT_DEPTH = 48.0;
    /** Time constant of the swell, seconds. */
    private static final float RESPONSE_SECONDS = 2.5f;
    /** Spacing of the samples averaged around the listener: rain is heard from all around. */
    private static final float SAMPLE_SPREAD = 16f;
    /**
     * Blocks above a cloud's base over which its rain fades out: rain falls from
     * the cloud's underside, so a listener inside the cloud or above it hears
     * none of that column's rain.
     */
    static final float CLOUD_FADE = 16f;
    /** Below this the loop is silent rather than fading forever. */
    private static final float SILENT = 1e-3f;

    /** Thunder is heard this far; beyond it the flash is silent. */
    private static final double THUNDER_RANGE = 1500.0;
    /** Distance at which thunder's volume has halved. */
    private static final double THUNDER_HALF_DISTANCE = 300.0;
    /** Claps arriving closer together than this are heard as one, the louder. */
    private static final double THUNDER_MERGE_SECONDS = 0.4;

    /** Thunder on its way: as late after each flash as sound takes to travel. */
    private final DelayedSoundQueue pendingThunder = new DelayedSoundQueue(THUNDER_MERGE_SECONDS);
    private double lastSeconds = Double.NaN;
    private float level;
    /** Blocks of world over the ear this tick (0 = open sky, or no world). */
    private double cover;

    /** Registers the weather sounds; call once while the sound system boots. */
    public static void load(SoundSystem sounds) {
        GameSoundLoader.load(sounds, RAIN_LOOP, "/sounds/weather/rain_loop.wav");
        GameSoundLoader.load(sounds, THUNDER[0], "/sounds/weather/thunder_1.wav");
        GameSoundLoader.load(sounds, THUNDER[1], "/sounds/weather/thunder_2.wav");
    }

    /** Call every game tick; a null weather or a null/closed world (no session, world switch) fades the rain out. */
    public void update(ClientWeather weather, World world, Vector3dc ear, float deltaSeconds) {
        float target = 0f;
        cover = 0.0;
        if (weather != null && world != null && !world.isClosed()) {
            long x = (long) Math.floor(ear.x()), z = (long) Math.floor(ear.z());
            // The median over the columns around the ear: a cave under a skylight is still a cave.
            cover = WeatherSurface.coverAround(world, ear.x(), ear.y(), ear.z());
            float shelter = rainShelter(cover);
            if (shelter > 0f && !WeatherSurface.isSnow(world, x, z)) {
                target = precipitationAround(weather, ear) * shelter;
            }
        }
        level += (target - level) * (1f - (float) Math.exp(-Math.max(0f, deltaSeconds) / RESPONSE_SECONDS));
        if (target == 0f && level < SILENT) level = 0f;
        SoundSystem.getInstance().setLoopVolume(RAIN_LOOP, level * RAIN_VOLUME);
        updateThunder(weather, ear);
    }

    private static float precipitationAround(ClientWeather weather, Vector3dc p) {
        float earY = (float) p.y();
        float sum = heardAt(weather, p.x(), p.z(), earY);
        sum += heardAt(weather, p.x() + SAMPLE_SPREAD, p.z(), earY);
        sum += heardAt(weather, p.x() - SAMPLE_SPREAD, p.z(), earY);
        sum += heardAt(weather, p.x(), p.z() + SAMPLE_SPREAD, earY);
        sum += heardAt(weather, p.x(), p.z() - SAMPLE_SPREAD, earY);
        return sum / 5f;
    }

    private static float heardAt(ClientWeather weather, double x, double z, float earY) {
        return rainHeard(weather.columnAt(x, z), earY);
    }

    /**
     * The rain of {@code column} as heard by a listener at height {@code earY}:
     * all of it below the cloud's base, fading to none {@link #CLOUD_FADE}
     * blocks up into the cloud (or at its top, for a thinner one), and none
     * above it — the rain is all below.
     */
    static float rainHeard(CloudColumn column, float earY) {
        float rain = column.precipitation();
        if (rain <= 0f) return 0f;
        float base = column.baseY();
        float silentAt = Math.min(base + CLOUD_FADE, column.topY());
        if (earY <= base) return rain;
        if (earY >= silentAt) return 0f;
        return rain * (silentAt - earY) / (silentAt - base);
    }

    /**
     * Share of the rain heard under {@code cover} blocks of world: all of it in the open (a block of cover
     * or less), {@link #ROOFED} under a roof, fading to none at {@link #RAIN_SILENT_DEPTH}.
     */
    static float rainShelter(double cover) {
        if (cover <= 1.0) return 1f;
        return ROOFED * fadeOut(cover, ROOF_DEPTH, RAIN_SILENT_DEPTH);
    }

    /** Share of thunder heard under {@code cover} blocks: all of it under a roof, fading to none at {@link #THUNDER_SILENT_DEPTH}. */
    static float thunderShelter(double cover) {
        return fadeOut(cover, ROOF_DEPTH, THUNDER_SILENT_DEPTH);
    }

    /** 1 up to {@code from}, falling linearly to 0 at {@code to}. */
    private static float fadeOut(double x, double from, double to) {
        return (float) Math.clamp((to - x) / (to - from), 0.0, 1.0);
    }

    /** Queues thunder for each new strike, delayed by distance, and plays what is due. */
    private void updateThunder(ClientWeather weather, Vector3dc ear) {
        if (weather == null) {
            pendingThunder.clear();
            lastSeconds = Double.NaN;
            return;
        }
        double now = weather.seconds();
        float shelter = thunderShelter(cover);
        if (!Double.isNaN(lastSeconds) && shelter > 0f) {
            for (var strike : weather.strikesBetween(lastSeconds, now, ear.x(), ear.z(), THUNDER_RANGE)) {
                double d = Math.hypot(strike.x() - ear.x(), strike.z() - ear.z());
                float volume = shelter * (float) (1.0 / (1.0 + d / THUNDER_HALF_DISTANCE));
                // Far thunder rumbles lower.
                float pitch = (float) (1.0 - 0.25 * Math.min(1.0, d / THUNDER_RANGE));
                pendingThunder.schedule(DelayedSoundQueue.arrival(strike.seconds(), d),
                        THUNDER[(int) Math.floorMod(strike.seed(), (long) THUNDER.length)], volume, pitch);
            }
        }
        lastSeconds = now;
        pendingThunder.playDue(now, SoundSystem.getInstance());
    }

}
