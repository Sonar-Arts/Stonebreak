package com.stonebreak.core.dev;

import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.network.server.ServerLevel;
import com.stonebreak.world.TimeOfDay;
import org.joml.Vector3f;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Dev hook: captures the sky at fixed sun elevations so a sky change can be judged
 * shot for shot, before and after. {@code -Dstonebreak.autosky=<dir>[:<warmup s>[:<day>]][:quit]},
 * paired with {@code stonebreak.autoworld} and {@code stonebreak.autoview} (the vantage;
 * this hook overrides only its yaw and pitch) and optionally {@code stonebreak.autoweather}.
 *
 * <p>After the warm-up (the world streams in) it freezes the server clock and, for each of
 * {@link #ELEVATIONS} on the evening side of the day, sets the time so the sun stands at that
 * elevation and shoots three views: toward the sun, straight up and away from it, as
 * {@code e+10_toward.png} and the like. Each shot waits {@link #SETTLE_SECONDS} so the clouds'
 * temporal history converges on the new light. {@code day} picks the moon's phase (default 0,
 * a new moon: a dark night, so the stars show); {@code quit} closes the game when done.
 * Inert unless the property is set.
 */
public final class SkyCapture {

    /** Sun elevations (degrees) to capture, high to low. */
    static final float[] ELEVATIONS = {10f, 3f, 0f, -3f, -6f, -10f, -15f, -20f};
    private static final String[] VIEWS = {"toward", "overhead", "away"};
    /** Camera pitch (degrees) for the level views: the horizon sits in the lower part of the frame. */
    private static final float LEVEL_PITCH = 12f;
    private static final float OVERHEAD_PITCH = 89f;
    /** Seconds after a time or view change before the shot. */
    private static final double SETTLE_SECONDS = 2.0;

    private static SkyCapture instance;
    private static boolean parsed;

    private final Path dir;
    private final double warmupSeconds;
    private final long day;
    private final boolean quit;

    private long armedNanos = -1;
    private long shotAtNanos = -1;
    private int shot = -1;
    private boolean done;
    private final Vector3f sunAtShot = new Vector3f();

    private SkyCapture(Path dir, double warmupSeconds, long day, boolean quit) {
        this.dir = dir;
        this.warmupSeconds = warmupSeconds;
        this.day = day;
        this.quit = quit;
    }

    private static SkyCapture get() {
        if (!parsed) {
            parsed = true;
            String spec = System.getProperty("stonebreak.autosky");
            if (spec != null && !spec.isBlank()) {
                String[] p = spec.split(":");
                boolean quit = p[p.length - 1].equals("quit");
                int n = quit ? p.length - 1 : p.length;
                instance = new SkyCapture(Path.of(p[0]),
                        n > 1 ? Double.parseDouble(p[1]) : 30.0,
                        n > 2 ? Long.parseLong(p[2]) : 0L, quit);
            }
        }
        return instance;
    }

    /** Main thread, after the update and autoview: sets the clock and the camera for the current shot. */
    public static void update() {
        SkyCapture c = get();
        if (c != null) c.step();
    }

    /**
     * Main thread, before the buffer swap: writes the current shot when it has settled.
     * @return true when the run has finished and asked to quit
     */
    public static boolean shoot(int width, int height) {
        SkyCapture c = get();
        return c != null && c.capture(width, height);
    }

    private void step() {
        if (done || Game.getInstance().getState() != GameState.PLAYING) return;
        long now = System.nanoTime();
        if (armedNanos < 0) {
            armedNanos = now;
            System.out.println("[autosky] armed; warming up " + warmupSeconds + " s");
            return;
        }
        if (shot < 0) {
            if ((now - armedNanos) / 1e9 < warmupSeconds) return;
            freezeServerClock();
            next(now);
        }
        aim();
    }

    private void next(long now) {
        shot++;
        if (shot >= ELEVATIONS.length * VIEWS.length) {
            done = true;
            System.out.println("[autosky] done: " + dir.toAbsolutePath());
            return;
        }
        if (shot % VIEWS.length == 0) {
            long ticks = day * TimeOfDay.TICKS_PER_DAY + duskTick(ELEVATIONS[shot / VIEWS.length]);
            MultiplayerSession.requestServerTimeSet(ticks);
            TimeOfDay clock = Game.getTimeOfDay();
            if (clock != null) clock.setTicks(ticks);
            System.out.println("[autosky] sun at " + ELEVATIONS[shot / VIEWS.length] + " deg: ticks " + ticks);
        }
        shotAtNanos = now + (long) (SETTLE_SECONDS * 1e9);
    }

    private void aim() {
        var player = Game.getPlayer();
        TimeOfDay clock = Game.getTimeOfDay();
        if (done || player == null || clock == null) return;
        Vector3f sun = clock.getSunDirection();
        sunAtShot.set(sun);
        float towardSun = (float) Math.toDegrees(Math.atan2(sun.z, sun.x));
        String view = VIEWS[shot % VIEWS.length];
        player.getCamera().setYaw(view.equals("away") ? towardSun + 180f : towardSun);
        player.getCamera().setPitch(view.equals("overhead") ? OVERHEAD_PITCH : LEVEL_PITCH);
    }

    private boolean capture(int width, int height) {
        if (done || shot < 0 || System.nanoTime() < shotAtNanos) return done && quit;
        float elevation = ELEVATIONS[shot / VIEWS.length];
        String name = String.format(Locale.ROOT, "e%+03d_%s.png", Math.round(elevation), VIEWS[shot % VIEWS.length]);
        String file = dir.resolve(name).toString();
        if (DevScreenshot.capture(file, width, height)) {
            float actual = (float) Math.toDegrees(Math.asin(Math.clamp(sunAtShot.y, -1f, 1f)));
            System.out.printf(Locale.ROOT, "[autosky] wrote %s (sun %.2f deg)%n", file, actual);
        }
        next(System.nanoTime());
        return done && quit;
    }

    /** Freezes the integrated server's clock, so the sun holds still for each shot. */
    private static void freezeServerClock() {
        var server = MultiplayerSession.getServer();
        if (server == null) return;
        server.executeOnTick(() -> {
            ServerLevel level = server.worldContext().serverLevel();
            if (level != null && level.timeOfDay() != null) level.timeOfDay().setFrozen(true);
        });
        TimeOfDay clock = Game.getTimeOfDay();
        if (clock != null) clock.setFrozen(true);
    }

    /**
     * The tick of the day (0..23999) at which the sun, setting, stands at {@code elevation}
     * degrees: a bisection over the evening half of {@link TimeOfDay#directionAt}, along which
     * the sun only sinks.
     */
    static long duskTick(float elevation) {
        float target = (float) Math.sin(Math.toRadians(elevation));
        float lo = 0.25f, hi = 0.75f; // noon (highest) to midnight (lowest)
        for (int i = 0; i < 40; i++) {
            float mid = 0.5f * (lo + hi);
            if (TimeOfDay.directionAt(mid).y > target) lo = mid;
            else hi = mid;
        }
        return Math.round(0.5f * (lo + hi) * TimeOfDay.TICKS_PER_DAY) % TimeOfDay.TICKS_PER_DAY;
    }
}
