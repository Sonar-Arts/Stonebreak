package com.stonebreak.world;

import org.joml.Vector3f;

/**
 * Manages the day/night cycle and time-based lighting calculations.
 * Time is measured in ticks, similar to Minecraft:
 * - 0 ticks = Dawn (6:00 AM)
 * - 6000 ticks = Noon (12:00 PM)
 * - 12000 ticks = Dusk (6:00 PM)
 * - 18000 ticks = Midnight (12:00 AM)
 * - 24000 ticks = Full cycle (returns to Dawn)
 */
public class TimeOfDay {

    // Time constants
    public static final int TICKS_PER_DAY = 24000;
    public static final int TICKS_PER_HOUR = TICKS_PER_DAY / 24;

    // Time of day milestones
    public static final int DAWN = 0;           // 6:00 AM
    public static final int SUNRISE = 1000;     // ~6:40 AM
    public static final int NOON = 6000;        // 12:00 PM
    public static final int SUNSET = 11000;     // ~5:20 PM
    public static final int DUSK = 12000;       // 6:00 PM
    public static final int MIDNIGHT = 18000;   // 12:00 AM

    // Current time in ticks
    private long ticks = DAWN;

    // Time progression speed multiplier (1.0 = normal speed)
    private float timeSpeed = 1.0f;

    // Whether time is frozen
    private boolean frozen = false;

    /**
     * Creates a new TimeOfDay system starting at dawn.
     */
    public TimeOfDay() {
        this(DAWN);
    }

    /**
     * Creates a new TimeOfDay system starting at the specified time.
     */
    public TimeOfDay(long startTicks) {
        this.ticks = startTicks % TICKS_PER_DAY;
    }

    /**
     * Updates the time of day based on delta time.
     * With default settings, a full day/night cycle takes 20 minutes real-time.
     * @param deltaTime Time elapsed since last update in seconds
     */
    public void update(float deltaTime) {
        if (!frozen) {
            // Calculate ticks per second for a 20-minute full cycle (10 min day, 10 min night)
            // 20 minutes = 1200 seconds
            // 24000 ticks per day / 1200 seconds = 20 ticks per second
            float ticksPerSecond = (TICKS_PER_DAY / 1200.0f) * timeSpeed;
            float ticksToAdd = ticksPerSecond * deltaTime;

            ticks = (long)((ticks + ticksToAdd) % TICKS_PER_DAY);
        }
    }

    /**
     * Gets the current time in ticks (0-23999).
     */
    public long getTicks() {
        return ticks;
    }

    /**
     * Sets the current time in ticks.
     */
    public void setTicks(long ticks) {
        this.ticks = ticks % TICKS_PER_DAY;
    }

    /**
     * Gets the time as a normalized value (0.0-1.0) representing progress through the day.
     */
    public float getNormalizedTime() {
        return (float) ticks / TICKS_PER_DAY;
    }

    /**
     * Gets the time speed multiplier.
     */
    public float getTimeSpeed() {
        return timeSpeed;
    }

    /**
     * Sets the time speed multiplier.
     * 1.0 = normal speed, 2.0 = double speed, 0.5 = half speed
     */
    public void setTimeSpeed(float speed) {
        this.timeSpeed = Math.max(0.0f, speed);
    }

    /**
     * Freezes or unfreezes time progression.
     */
    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    /**
     * Checks if time is frozen.
     */
    public boolean isFrozen() {
        return frozen;
    }

    /**
     * Calculates the sun direction vector based on current time.
     * The sun rises in the east, peaks at noon, and sets in the west.
     * Time progression:
     * - Dawn (0 ticks): Sun rising in the east
     * - Noon (6000 ticks): Sun at zenith
     * - Dusk (12000 ticks): Sun setting in the west
     * - Midnight (18000 ticks): Sun below horizon (night)
     */
    public Vector3f getSunDirection() {
        // Normalize ticks to 0-1 range
        float dayProgress = getNormalizedTime();

        // Map to angle where:
        // 0.0 (dawn) = -90 degrees (east horizon)
        // 0.25 (noon) = 0 degrees (zenith, south)
        // 0.5 (dusk) = 90 degrees (west horizon)
        // Shift by -0.25 so noon is at 0
        float normalizedAngle = (dayProgress - 0.25f) * 360.0f;

        // Calculate elevation (height above horizon)
        // Peaks at noon (0 degrees), below horizon at night
        float elevationAngle = (float) Math.sin(Math.toRadians(normalizedAngle)) * 110.0f;
        elevationAngle = Math.max(-90.0f, Math.min(90.0f, elevationAngle));

        // Calculate azimuth (compass direction)
        // East (-X) at dawn, South (+Z) at noon, West (+X) at dusk
        float azimuthAngle = normalizedAngle;

        // Convert to radians
        float elevRad = (float) Math.toRadians(elevationAngle);
        float azimRad = (float) Math.toRadians(azimuthAngle);

        // Convert spherical to Cartesian
        float y = (float) Math.sin(elevRad); // Height
        float horizontalDistance = (float) Math.cos(elevRad);

        // In OpenGL: +X = right, +Y = up, +Z = toward viewer
        // We want: East = -X, West = +X, South = +Z
        float x = horizontalDistance * (float) Math.sin(azimRad); // East-West
        float z = horizontalDistance * (float) Math.cos(azimRad); // North-South

        return new Vector3f(x, y, z).normalize();
    }

    /**
     * Gets the ambient light level based on time of day.
     * @return Light level from 0.0 (darkest night) to 1.0 (brightest day)
     */
    public float getAmbientLightLevel() {
        long t = ticks;

        // Full daylight (8:00 AM to 4:00 PM)
        if (t >= 2000 && t <= 10000) {
            return 1.0f;
        }

        // Sunrise transition (6:00 AM to 8:00 AM)
        if (t >= DAWN && t < 2000) {
            float progress = (t - DAWN) / 2000.0f;
            return 0.3f + (progress * 0.7f); // 0.3 to 1.0
        }

        // Sunset transition (4:00 PM to 6:00 PM)
        if (t > 10000 && t <= DUSK) {
            float progress = (t - 10000) / 2000.0f;
            return 1.0f - (progress * 0.7f); // 1.0 to 0.3
        }

        // Night time (6:00 PM to 6:00 AM)
        if (t > DUSK && t < TICKS_PER_DAY) {
            // Gradual darkening to minimum at midnight, then lightening
            if (t < MIDNIGHT) {
                // Evening: 6:00 PM to 12:00 AM
                float progress = (t - DUSK) / (float)(MIDNIGHT - DUSK);
                return 0.3f - (progress * 0.15f); // 0.3 to 0.15
            } else {
                // Morning: 12:00 AM to 6:00 AM
                float progress = (t - MIDNIGHT) / (float)(TICKS_PER_DAY - MIDNIGHT);
                return 0.15f + (progress * 0.15f); // 0.15 to 0.3
            }
        }

        return 0.3f; // Default fallback
    }

    /**
     * Gets the sky color based on time of day.
     * @return RGB color vector for the sky
     */
    public Vector3f getSkyColor() {
        long t = ticks;

        // Day sky (bright blue)
        Vector3f daySky = new Vector3f(0.53f, 0.81f, 0.92f); // Light blue

        // Sunset/sunrise sky (orange/pink)
        Vector3f sunsetSky = new Vector3f(1.0f, 0.5f, 0.3f); // Orange

        // Night sky (dark blue)
        Vector3f nightSky = new Vector3f(0.01f, 0.01f, 0.05f); // Very dark blue

        // Transition during sunrise
        if (t >= DAWN && t < SUNRISE + 1000) {
            float progress = (t - DAWN) / 2000.0f; // 0 to 1
            return lerp(sunsetSky, daySky, progress);
        }

        // Full day
        if (t >= SUNRISE + 1000 && t <= SUNSET - 1000) {
            return new Vector3f(daySky);
        }

        // Transition during sunset
        if (t > SUNSET - 1000 && t <= DUSK + 1000) {
            float progress = (t - (SUNSET - 1000)) / 2000.0f; // 0 to 1
            return lerp(daySky, sunsetSky, progress);
        }

        // Transition to night
        if (t > DUSK + 1000 && t <= DUSK + 2000) {
            float progress = (t - (DUSK + 1000)) / 1000.0f; // 0 to 1
            return lerp(sunsetSky, nightSky, progress);
        }

        // Night time
        if (t > DUSK + 2000 && t < TICKS_PER_DAY - 2000) {
            return new Vector3f(nightSky);
        }

        // Transition from night to sunrise
        if (t >= TICKS_PER_DAY - 2000) {
            float progress = (t - (TICKS_PER_DAY - 2000)) / 2000.0f; // 0 to 1
            return lerp(nightSky, sunsetSky, progress);
        }

        return new Vector3f(daySky); // Default
    }

    /**
     * Linear interpolation between two colors.
     */
    private Vector3f lerp(Vector3f a, Vector3f b, float t) {
        t = Math.max(0.0f, Math.min(1.0f, t)); // Clamp to 0-1
        return new Vector3f(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t,
            a.z + (b.z - a.z) * t
        );
    }

    /**
     * Checks if it's currently daytime.
     */
    public boolean isDay() {
        return ticks >= DAWN && ticks < DUSK;
    }

    /**
     * Checks if it's currently nighttime.
     */
    public boolean isNight() {
        return ticks >= DUSK || ticks < DAWN;
    }

    /**
     * Gets a human-readable time string (e.g., "12:30 PM").
     */
    public String getTimeString() {
        // Convert ticks to hours and minutes
        // 0 ticks = 6:00 AM
        float hours = ((ticks / (float)TICKS_PER_HOUR) + 6) % 24;
        int hour = (int) hours;
        int minute = (int) ((hours - hour) * 60);

        String period = hour >= 12 ? "PM" : "AM";
        int displayHour = hour % 12;
        if (displayHour == 0) displayHour = 12;

        return String.format("%d:%02d %s", displayHour, minute, period);
    }
}
