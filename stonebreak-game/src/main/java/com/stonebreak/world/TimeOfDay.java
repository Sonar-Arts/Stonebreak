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

    /**
     * Real-time seconds for one full day/night cycle at timeSpeed 1.0. The single knob for
     * day length: 2400 s = 40 min (20 min of daylight). NOTE: before the multiplayer clock
     * work, sub-tick truncation silently froze time at frame rates above 20 FPS, so the
     * "old day length" players remember was an artifact, not a tuned value.
     */
    public static final float DAY_LENGTH_SECONDS = 2400.0f;

    // Time of day milestones
    public static final int DAWN = 0;           // 6:00 AM
    public static final int SUNRISE = 1000;     // ~6:40 AM
    public static final int NOON = 6000;        // 12:00 PM
    public static final int SUNSET = 11000;     // ~5:20 PM
    public static final int DUSK = 12000;       // 6:00 PM
    public static final int MIDNIGHT = 18000;   // 12:00 AM

    // Current time in ticks
    private long ticks = DAWN;

    // Whole days elapsed since the world began. Only the moon reads it: the
    // day/night cycle itself is the same every day.
    private long day = 0;

    // Fractional-tick carry between updates. Without it, sub-tick updates (ticksToAdd < 1,
    // i.e. any step shorter than 1/tick-rate seconds — every frame, and the 20 Hz server
    // step since the day was lengthened past 20 ticks/s) truncate to zero and time never
    // advances.
    private float subTick = 0.0f;

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
     * Creates a new TimeOfDay system starting at the specified absolute time
     * (day × {@link #TICKS_PER_DAY} + tick; anything under a day is day 0).
     */
    public TimeOfDay(long startTicks) {
        setTicks(startTicks);
    }

    /**
     * Updates the time of day based on delta time.
     * With default settings, a full day/night cycle takes {@link #DAY_LENGTH_SECONDS}
     * seconds of real time.
     * @param deltaTime Time elapsed since last update in seconds
     */
    public void update(float deltaTime) {
        if (!frozen) {
            float ticksPerSecond = (TICKS_PER_DAY / DAY_LENGTH_SECONDS) * timeSpeed;
            float ticksToAdd = ticksPerSecond * deltaTime + subTick;

            long whole = (long) ticksToAdd;
            subTick = ticksToAdd - whole;
            setTicks(getTotalTicks() + whole);
        }
    }

    /** Client-side error above which {@link #nudgeTo} snaps instead of converging. */
    public static final long SNAP_THRESHOLD_TICKS = 200;
    /** Fraction of the remaining error corrected per sync when converging. */
    private static final float CONVERGE_FRACTION = 0.10f;

    /**
     * Corrects this (client) clock toward an authoritative server sample (absolute ticks).
     * Large error (join, long stall) snaps outright; small error converges a fraction per
     * call so the sky/lighting never visibly jumps while 20 Hz drift is steered out.
     * The day follows the server's: whichever day puts this clock nearest its sample.
     */
    public void nudgeTo(long serverTicks) {
        nudgeTickTo(Math.floorMod(serverTicks, TICKS_PER_DAY));
        day = Math.floorDiv(serverTicks - ticks + TICKS_PER_DAY / 2, TICKS_PER_DAY);
    }

    private void nudgeTickTo(long target) {
        long delta = target - ticks;
        // Shortest wrapped distance, so 23900 -> 100 converges forward across midnight.
        if (delta > TICKS_PER_DAY / 2) {
            delta -= TICKS_PER_DAY;
        } else if (delta < -TICKS_PER_DAY / 2) {
            delta += TICKS_PER_DAY;
        }
        if (Math.abs(delta) > SNAP_THRESHOLD_TICKS) {
            ticks = target;
            subTick = 0.0f;
        } else if (delta != 0) {
            long step = Math.round(delta * CONVERGE_FRACTION);
            if (step == 0) {
                step = Long.signum(delta); // always make progress on tiny error
            }
            ticks = Math.floorMod(ticks + step, TICKS_PER_DAY);
        }
    }

    /**
     * Gets the current time in ticks (0-23999).
     */
    public long getTicks() {
        return ticks;
    }

    /**
     * Sets the current absolute time: day × {@link #TICKS_PER_DAY} + tick. A value under
     * one day keeps the world on day 0, which is what saves from before the day count hold.
     */
    public void setTicks(long ticks) {
        this.ticks = Math.floorMod(ticks, TICKS_PER_DAY);
        this.day = Math.floorDiv(ticks, TICKS_PER_DAY);
    }

    /** Sets the time of day, staying on the current day. */
    public void setTimeOfDayTicks(long ticks) {
        this.ticks = Math.floorMod(ticks, TICKS_PER_DAY);
    }

    /** Whole days elapsed since the world began. */
    public long getDay() {
        return day;
    }

    /** Absolute time: day × {@link #TICKS_PER_DAY} + tick. What saves and the network carry. */
    public long getTotalTicks() {
        return day * TICKS_PER_DAY + ticks;
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
     * - Dawn (0 ticks): Sun rising in the east (horizon)
     * - Noon (6000 ticks): Sun at zenith (directly overhead)
     * - Dusk (12000 ticks): Sun setting in the west (horizon)
     * - Midnight (18000 ticks): Sun below horizon (night)
     */
    public Vector3f getSunDirection() {
        return directionAt(getNormalizedTime());
    }

    /**
     * Direction to a body on the sun's path at {@code dayProgress} through its own
     * day (0 = rising in the east, 0.25 = overhead, 0.5 = setting in the west,
     * 0.75 = straight below). The moon travels the same path, lagging the sun.
     */
    public static Vector3f directionAt(float dayProgress) {
        dayProgress -= (float) Math.floor(dayProgress);

        // Elevation follows sine wave: sin(0°)=0, sin(90°)=1, sin(180°)=0, sin(270°)=-1
        float elevationAngle = (float) Math.sin(Math.toRadians(dayProgress * 360.0f)) * 90.0f;

        // Calculate azimuth (compass direction) - sun moves from east to west
        // dayProgress 0.0 (dawn) → -90° (east)
        // dayProgress 0.25 (noon) → 0° (south)
        // dayProgress 0.5 (dusk) → 90° (west)
        float azimuthAngle = (dayProgress * 2.0f - 0.5f) * 180.0f;

        float elevRad = (float) Math.toRadians(elevationAngle);
        float azimRad = (float) Math.toRadians(azimuthAngle);

        float y = (float) Math.sin(elevRad); // Height (up/down)
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
