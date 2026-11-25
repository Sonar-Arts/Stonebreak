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
 *
 * <p>Threading: one thread writes a given clock (the server tick thread for the server's,
 * the game thread for a client's). Reads of the time are safe from any thread. The save
 * thread stamps the server's clock while it ticks, so the clock is kept as one absolute
 * {@code volatile long} and the day and tick are always read from the same value.
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

    /** Last valid day (about 2.7 million in-game years). Keeps day × ticks-per-day far from overflow. */
    public static final long MAX_DAY = 1_000_000_000L;
    /** Last valid absolute time: the final tick of {@link #MAX_DAY}. */
    public static final long MAX_TOTAL_TICKS = (MAX_DAY + 1) * TICKS_PER_DAY - 1;
    /** Fastest valid timeSpeed. Bounds a single update's step so it can't overflow the clock. */
    public static final float MAX_TIME_SPEED = 1000.0f;

    /**
     * The latitude the sky is seen from, degrees north. The sun keeps the equinox path:
     * it rises due east, crosses the meridian 90° − φ up (toward +Z, the south) and sets
     * due west, and the sky wheels about the celestial pole, φ up in the north (−Z). At
     * the poles' side of the equator the sun meets the horizon at a slant, so twilight is
     * longer than straight overhead: at 40° it is cos 40° = 0.77 of the overhead speed.
     */
    public static final float LATITUDE_DEG = 40f;
    /**
     * How much the sky slows around sunrise and sunset, and speeds up at noon and
     * midnight to make up for it: the day's progress p is warped by
     * p − D·sin(4πp)/(4π), whose rate 1 − D·cos(4πp) is 1 − D at the horizon crossings
     * (p = 0, 0.5) and 1 + D at noon and midnight. Sunrise, noon, sunset and midnight stay
     * on their ticks. At 0.4 the sun sinks 0.069° per real second at the horizon, so civil
     * twilight (0° to −6°) lasts about 85 s of a 2400 s day, where an overhead sun without
     * the warp crossed it in 25 s.
     */
    public static final float TWILIGHT_DILATION = 0.4f;
    private static final float SIN_LAT = (float) Math.sin(Math.toRadians(LATITUDE_DEG));
    private static final float COS_LAT = (float) Math.cos(Math.toRadians(LATITUDE_DEG));
    /** Sun elevation (degrees) from which the ambient light is full day. */
    private static final float FULL_DAY_ELEVATION = 15f;
    /** Ambient light with the sun on the horizon, and at its lowest, at midnight. */
    public static final float HORIZON_AMBIENT = 0.3f, MIDNIGHT_AMBIENT = 0.15f;

    // Time of day milestones
    public static final int DAWN = 0;           // 6:00 AM
    public static final int SUNRISE = 1000;     // ~6:40 AM
    public static final int NOON = 6000;        // 12:00 PM
    public static final int SUNSET = 11000;     // ~5:20 PM
    public static final int DUSK = 12000;       // 6:00 PM
    public static final int MIDNIGHT = 18000;   // 12:00 AM

    // Absolute time: day × TICKS_PER_DAY + tick. The day only matters to the moon:
    // the day/night cycle itself is the same every day.
    private volatile long totalTicks = DAWN;

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

    /** Whether {@code ticks} is a valid absolute time: 0 to {@link #MAX_TOTAL_TICKS}. */
    public static boolean isValidTotalTicks(long ticks) {
        return ticks >= 0 && ticks <= MAX_TOTAL_TICKS;
    }

    /**
     * The tick (0-23999) at clock time {@code hour}:{@code minute}, 24-hour (tick 0 is 6:00 AM).
     * A minute is 16⅔ ticks, so the fraction rounds up: {@link #getTimeString} then shows the
     * same minute back.
     */
    public static long ticksAtClock(int hour, int minute) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new IllegalArgumentException("clock time must be 0:00-23:59, got " + hour + ":" + minute);
        }
        long minutesSinceDawn = Math.floorMod((hour - 6) * 60 + minute, 24 * 60);
        return (minutesSinceDawn * TICKS_PER_HOUR + 59) / 60;
    }

    /** Whether {@code speed} is a valid timeSpeed: finite, 0 to {@link #MAX_TIME_SPEED}. */
    public static boolean isValidTimeSpeed(float speed) {
        return speed >= 0.0f && speed <= MAX_TIME_SPEED; // false for NaN
    }

    /**
     * Updates the time of day based on delta time.
     * With default settings, a full day/night cycle takes {@link #DAY_LENGTH_SECONDS}
     * seconds of real time.
     * @param deltaTime Time elapsed since last update in seconds
     */
    public void update(float deltaTime) {
        if (!Float.isFinite(deltaTime) || deltaTime < 0.0f) {
            // A NaN here would poison subTick and stop the clock for good.
            throw new IllegalArgumentException("deltaTime must be finite and >= 0, got " + deltaTime);
        }
        if (!frozen) {
            float ticksPerSecond = (TICKS_PER_DAY / DAY_LENGTH_SECONDS) * timeSpeed;
            float ticksToAdd = ticksPerSecond * deltaTime + subTick;

            long whole = (long) ticksToAdd;
            subTick = ticksToAdd - whole;
            if (whole != 0) {
                store(totalTicks + whole);
            }
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
        requireValidTotalTicks(serverTicks);
        long target = Math.floorMod(serverTicks, TICKS_PER_DAY);
        long tick = getTicks();
        long delta = target - tick;
        // Shortest wrapped distance, so 23900 -> 100 converges forward across midnight.
        if (delta > TICKS_PER_DAY / 2) {
            delta -= TICKS_PER_DAY;
        } else if (delta < -TICKS_PER_DAY / 2) {
            delta += TICKS_PER_DAY;
        }
        if (Math.abs(delta) > SNAP_THRESHOLD_TICKS) {
            snapTo(serverTicks);
            return;
        }
        if (delta != 0) {
            long step = Math.round(delta * CONVERGE_FRACTION);
            if (step == 0) {
                step = Long.signum(delta); // always make progress on tiny error
            }
            tick = Math.floorMod(tick + step, TICKS_PER_DAY);
        }
        long day = Math.floorDiv(serverTicks - tick + TICKS_PER_DAY / 2, TICKS_PER_DAY);
        if (day < 0) {
            // Behind a server still on its first ticks: there is no day before day 0.
            snapTo(serverTicks);
            return;
        }
        store(day * TICKS_PER_DAY + tick);
    }

    private void snapTo(long ticks) {
        store(ticks);
        subTick = 0.0f;
    }

    /**
     * Gets the current time in ticks (0-23999).
     */
    public long getTicks() {
        return totalTicks % TICKS_PER_DAY;
    }

    /**
     * Sets the current absolute time: day × {@link #TICKS_PER_DAY} + tick. A value under
     * one day keeps the world on day 0, which is what saves from before the day count hold.
     * @throws IllegalArgumentException if {@code ticks} is outside 0 to {@link #MAX_TOTAL_TICKS}
     */
    public void setTicks(long ticks) {
        snapTo(ticks);
    }

    /** Sets the time of day (wrapped into 0-23999), staying on the current day. */
    public void setTimeOfDayTicks(long ticks) {
        snapTo(getDay() * TICKS_PER_DAY + Math.floorMod(ticks, TICKS_PER_DAY));
    }

    /** Whole days elapsed since the world began. */
    public long getDay() {
        return totalTicks / TICKS_PER_DAY;
    }

    /** Absolute time: day × {@link #TICKS_PER_DAY} + tick. What saves and the network carry. */
    public long getTotalTicks() {
        return totalTicks;
    }

    private void store(long ticks) {
        requireValidTotalTicks(ticks);
        totalTicks = ticks;
    }

    private static void requireValidTotalTicks(long ticks) {
        if (!isValidTotalTicks(ticks)) {
            throw new IllegalArgumentException(
                "world time must be 0.." + MAX_TOTAL_TICKS + " ticks, got " + ticks);
        }
    }

    /**
     * Gets the time as a normalized value (0.0-1.0) representing progress through the day.
     */
    public float getNormalizedTime() {
        return (float) getTicks() / TICKS_PER_DAY;
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
     * @throws IllegalArgumentException unless {@link #isValidTimeSpeed}
     */
    public void setTimeSpeed(float speed) {
        if (!isValidTimeSpeed(speed)) {
            throw new IllegalArgumentException(
                "timeSpeed must be 0.." + MAX_TIME_SPEED + ", got " + speed);
        }
        this.timeSpeed = speed;
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
     * Direction to the sun now. It rises due east (−X) at dawn (tick 0), stands
     * {@code 90° − LATITUDE_DEG} up toward +Z at noon, sets due west (+X) at dusk
     * and is lowest, below the north horizon, at midnight. See {@link #directionAt}.
     */
    public Vector3f getSunDirection() {
        return directionAt(getNormalizedTime());
    }

    /**
     * Direction to a body on the sun's path at {@code dayProgress} through its own
     * day (0 = rising in the east, 0.25 = highest, 0.5 = setting in the west,
     * 0.75 = lowest). The moon travels the same path, lagging the sun.
     *
     * <p>The equinox sun from latitude φ ({@link #LATITUDE_DEG}): with hour angle h
     * the direction is (sin h, cos φ·cos h, sin φ·cos h), a great circle tilted φ from
     * the vertical about the east-west line, turning about {@link #celestialPole}. The
     * progress is first warped by {@link #TWILIGHT_DILATION} ({@link #skyAngle}).
     */
    public static Vector3f directionAt(float dayProgress) {
        float h = skyAngle(dayProgress);
        float cosH = (float) Math.cos(h);
        return new Vector3f((float) Math.sin(h), COS_LAT * cosH, SIN_LAT * cosH);
    }

    /**
     * The hour angle of the sky at {@code dayProgress}, radians: 0 with the sun on the
     * meridian at noon, −π/2 at sunrise, π/2 at sunset. The whole sky (sun, moon and
     * stars) turns by it about {@link #celestialPole}. The progress is warped by
     * p − D·sin(4πp)/(4π) ({@link #TWILIGHT_DILATION}) first, slowing the sky around
     * the horizon crossings.
     */
    public static float skyAngle(float dayProgress) {
        double p = dayProgress - Math.floor(dayProgress);
        p -= TWILIGHT_DILATION * Math.sin(4.0 * Math.PI * p) / (4.0 * Math.PI);
        return (float) (2.0 * Math.PI * (p - 0.25));
    }

    /**
     * The point the sky turns about, φ up in the north: (0, sin φ, −cos φ). A positive
     * (right-handed) turn about it carries the sun forward along its path.
     */
    public static Vector3f celestialPole() {
        return new Vector3f(0f, SIN_LAT, -COS_LAT);
    }

    /**
     * Gets the ambient light level, from the sun's elevation: full day once the sun
     * is {@value #FULL_DAY_ELEVATION}° up, {@link #HORIZON_AMBIENT} with it on the horizon,
     * falling to {@link #MIDNIGHT_AMBIENT} with it at its lowest.
     * @return Light level from 0.0 (darkest night) to 1.0 (brightest day)
     */
    public float getAmbientLightLevel() {
        return ambientAt(getSunDirection().y);
    }

    /** {@link #getAmbientLightLevel} for a sun whose direction has height {@code sunY}. */
    public static float ambientAt(float sunY) {
        float elevation = (float) Math.toDegrees(Math.asin(Math.clamp(sunY, -1f, 1f)));
        if (elevation >= 0f) {
            return HORIZON_AMBIENT + (1f - HORIZON_AMBIENT) * Math.min(elevation / FULL_DAY_ELEVATION, 1f);
        }
        // Below the horizon, by how far down the sun is of the most it can be (−cos φ at midnight).
        float depth = Math.min(-sunY / COS_LAT, 1f);
        return HORIZON_AMBIENT - (HORIZON_AMBIENT - MIDNIGHT_AMBIENT) * depth;
    }

    /**
     * Checks if it's currently daytime.
     */
    public boolean isDay() {
        return getTicks() < DUSK;
    }

    /**
     * Checks if it's currently nighttime.
     */
    public boolean isNight() {
        return !isDay();
    }

    /**
     * Gets a human-readable time string (e.g., "12:30 PM").
     */
    public String getTimeString() {
        // Whole minutes since midnight; 0 ticks = 6:00 AM. Integer math: float hours lost a
        // minute after the % 24 wrap (12:03 AM showed as 12:02).
        long minutes = (getTicks() * 60 / TICKS_PER_HOUR + 6 * 60) % (24 * 60);
        int hour = (int) (minutes / 60);
        int minute = (int) (minutes % 60);

        String period = hour >= 12 ? "PM" : "AM";
        int displayHour = hour % 12;
        if (displayHour == 0) displayHour = 12;

        return String.format("%d:%02d %s", displayHour, minute, period);
    }
}
