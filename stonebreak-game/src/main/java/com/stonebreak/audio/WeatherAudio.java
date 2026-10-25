package com.stonebreak.audio;

import com.openmason.engine.audio.SoundSystem;
import com.stonebreak.world.World;
import com.stonebreak.world.weather.ClientWeather;
import com.stonebreak.world.weather.WeatherSurface;
import org.joml.Vector3f;

/**
 * The sound of the weather at the listener: a rain loop that swells under
 * raining cloud, falls silent in snow and muffles under a roof; and thunder,
 * arriving as late after each lightning flash as sound takes to travel.
 * Thunder is played 2D — the engine's positional sources fade out within
 * 50 blocks, and thunder must carry for over a kilometre.
 */
public final class WeatherAudio {

    public static final String RAIN_LOOP = "weather_rain";
    private static final String[] THUNDER = {"weather_thunder_1", "weather_thunder_2"};

    /** Loop volume at the heaviest rain. */
    private static final float RAIN_VOLUME = 0.7f;
    /** Share of the rain heard with a roof overhead. */
    private static final float ROOFED = 0.3f;
    /** Time constant of the swell, seconds. */
    private static final float RESPONSE_SECONDS = 2.5f;
    /** Spacing of the samples averaged around the listener: rain is heard from all around. */
    private static final float SAMPLE_SPREAD = 16f;
    /** Below this the loop is silent rather than fading forever. */
    private static final float SILENT = 1e-3f;

    /** Speed of sound, blocks per second: how late thunder follows its flash. */
    private static final double SOUND_SPEED = 343.0;
    /** Thunder is heard this far; beyond it the flash is silent. */
    private static final double THUNDER_RANGE = 1500.0;
    /** Distance at which thunder's volume has halved. */
    private static final double THUNDER_HALF_DISTANCE = 300.0;
    /** Claps arriving closer together than this are heard as one, the louder. */
    private static final double THUNDER_MERGE_SECONDS = 0.4;

    private record Thunder(double atSeconds, String sound, float volume, float pitch) {}

    private final java.util.List<Thunder> pendingThunder = new java.util.ArrayList<>();
    private double lastSeconds = Double.NaN;
    private float level;

    /** Registers the weather sounds; call once while the sound system boots. */
    public static void load(SoundSystem sounds) {
        GameSoundLoader.load(sounds, RAIN_LOOP, "/sounds/weather/rain_loop.wav");
        GameSoundLoader.load(sounds, THUNDER[0], "/sounds/weather/thunder_1.wav");
        GameSoundLoader.load(sounds, THUNDER[1], "/sounds/weather/thunder_2.wav");
    }

    /** Call every game tick; a null weather or world (no session) fades the rain out. */
    public void update(ClientWeather weather, World world, Vector3f ear, float deltaSeconds) {
        float target = 0f;
        if (weather != null && world != null) {
            int x = (int) Math.floor(ear.x), z = (int) Math.floor(ear.z);
            if (!WeatherSurface.isSnow(world, x, z)) {
                int ground = WeatherSurface.groundY(world, x, z);
                boolean roofed = ground != WeatherSurface.UNKNOWN && ground > ear.y + 1;
                target = precipitationAround(weather, ear) * (roofed ? ROOFED : 1f);
            }
        }
        level += (target - level) * (1f - (float) Math.exp(-Math.max(0f, deltaSeconds) / RESPONSE_SECONDS));
        if (target == 0f && level < SILENT) level = 0f;
        SoundSystem.getInstance().setLoopVolume(RAIN_LOOP, level * RAIN_VOLUME);
        updateThunder(weather, ear);
    }

    private static float precipitationAround(ClientWeather weather, Vector3f p) {
        float sum = weather.precipitationAt(p.x, p.z);
        sum += weather.precipitationAt(p.x + SAMPLE_SPREAD, p.z);
        sum += weather.precipitationAt(p.x - SAMPLE_SPREAD, p.z);
        sum += weather.precipitationAt(p.x, p.z + SAMPLE_SPREAD);
        sum += weather.precipitationAt(p.x, p.z - SAMPLE_SPREAD);
        return sum / 5f;
    }

    /** Queues thunder for each new strike, delayed by distance, and plays what is due. */
    private void updateThunder(ClientWeather weather, Vector3f ear) {
        if (weather == null) {
            pendingThunder.clear();
            lastSeconds = Double.NaN;
            return;
        }
        double now = weather.seconds();
        if (!Double.isNaN(lastSeconds)) {
            for (var strike : weather.strikesBetween(lastSeconds, now, ear.x, ear.z, THUNDER_RANGE)) {
                double d = Math.hypot(strike.x() - ear.x, strike.z() - ear.z);
                float volume = (float) (1.0 / (1.0 + d / THUNDER_HALF_DISTANCE));
                // Far thunder rumbles lower.
                float pitch = (float) (1.0 - 0.25 * Math.min(1.0, d / THUNDER_RANGE));
                queue(new Thunder(strike.seconds() + d / SOUND_SPEED,
                        THUNDER[(int) Math.floorMod(strike.seed(), (long) THUNDER.length)], volume, pitch));
            }
        }
        lastSeconds = now;
        for (var it = pendingThunder.iterator(); it.hasNext(); ) {
            Thunder t = it.next();
            if (t.atSeconds() <= now) {
                SoundSystem.getInstance().playAmbient(t.sound(), t.volume(), t.pitch());
                it.remove();
            }
        }
    }

    /** Adds {@code t}, or folds it into a clap already due within {@link #THUNDER_MERGE_SECONDS}. */
    private void queue(Thunder t) {
        for (int i = 0; i < pendingThunder.size(); i++) {
            Thunder other = pendingThunder.get(i);
            if (Math.abs(other.atSeconds() - t.atSeconds()) < THUNDER_MERGE_SECONDS) {
                if (t.volume() > other.volume()) pendingThunder.set(i, t);
                return;
            }
        }
        pendingThunder.add(t);
    }
}
