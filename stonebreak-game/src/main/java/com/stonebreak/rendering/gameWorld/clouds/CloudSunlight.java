package com.stonebreak.rendering.gameWorld.clouds;

import com.stonebreak.world.weather.CloudType;
import org.joml.Vector3f;

/**
 * The sunlight reaching a cloud, by the cloud's height. A cloud stands kilometres up, so
 * its horizon dips below the ground's: it keeps the sun for a while after sunset, lit from
 * below in deep red, the high cloud last. And the light it gets has crossed a long slant of
 * air, reddened by how far.
 *
 * <p>The game's clouds hang a few hundred blocks up, far too low for that to show, so for
 * lighting alone each block height maps to a <em>virtual altitude</em>: the cloud slab
 * spans {@link #SLAB_BASE_M} to {@link #SLAB_TOP_M}, the cirrus sheet sits at
 * {@link #CIRRUS_M}. At virtual altitude h:
 * <ul>
 *   <li>the horizon dips by sqrt(2h/R) radians (R the Earth's radius), so the sun stands
 *       {@code elev = asin(sun.y) + dip} above it, and the cloud is lit while that is above
 *       zero (softened over the sun's own half-width, {@link #SUN_EDGE});</li>
 *   <li>the beam crosses Kasten and Young's (1989) relative air mass
 *       m = 1 / (sin e + 0.50572 (e° + 6.07995)^−1.6364) and keeps exp(−τ·m) of each
 *       channel, τ the zenith optical depth, larger for blue ({@link #TAU});</li>
 *   <li>the colour is that transmittance relative to the overhead sun's (so the noon sun
 *       is white, as the game has always drawn it), normalised to red, times the red
 *       channel's cube root. The cube root stands in for exposure until the scene is HDR:
 *       a 20x dimmer beam reads only ~3x darker, as the eye adapts.</li>
 * </ul>
 * {@link #bake} writes that over the heights the clouds use, for {@code clouds_march.frag}
 * to read from a small 1D texture instead of evaluating it per sample.
 */
public final class CloudSunlight {

    /** Texels in the table (clouds_march.frag KEY_LUT_TEXELS). */
    public static final int TEXELS = 32;
    /** The cirrus sheet's block height (clouds_march.frag CIRRUS_Y). */
    public static final float CIRRUS_Y = 560f;
    /** Virtual altitudes (metres) of the cloud slab's bottom and top, and of the cirrus. */
    static final float SLAB_BASE_M = 1500f, SLAB_TOP_M = 4000f, CIRRUS_M = 9000f;
    /** The Earth's mean radius, metres. */
    static final double EARTH_RADIUS_M = 6.371e6;
    /** Half-width (radians) of the sun's edge on the cloud's horizon: the disc is ~0.27° in radius. */
    static final float SUN_EDGE = 0.005f;
    /** Zenith optical depth of Rayleigh scattering at 610, 550 and 465 nm (red, green, blue). */
    static final Vector3f RAYLEIGH_TAU = new Vector3f(0.06f, 0.097f, 0.19f);
    /**
     * Zenith optical depth of a clear day's aerosol, about the same in every channel (haze
     * scatters colours alike). With Rayleigh's it makes {@link #TAU}.
     */
    static final Vector3f AEROSOL_TAU = new Vector3f(0.10f, 0.103f, 0.11f);
    /** Zenith optical depth per channel on a clear day: (0.16, 0.20, 0.30). */
    public static final Vector3f TAU = tau(1f, new Vector3f());

    private CloudSunlight() {}

    /**
     * The zenith optical depth with {@code aerosol} times a clear day's aerosol
     * ({@link com.stonebreak.rendering.lighting.SkyLight#aerosol()}), into {@code dest}: the
     * hazier evening air reddens and dims the beam more than dawn's.
     */
    public static Vector3f tau(float aerosol, Vector3f dest) {
        return dest.set(AEROSOL_TAU).mul(aerosol).add(RAYLEIGH_TAU);
    }

    /** The lowest block height the table covers: the bottom of the cloud slab. */
    public static float tableBottom() {
        return CloudType.slabBottom();
    }

    /** The highest block height the table covers: the cirrus sheet. */
    public static float tableTop() {
        return CIRRUS_Y;
    }

    /** The virtual altitude (metres) a cloud at block height {@code y} is lit as. */
    static float virtualAltitude(float y) {
        float bottom = CloudType.slabBottom(), top = CloudType.slabTop();
        if (y <= top) {
            return SLAB_BASE_M + (SLAB_TOP_M - SLAB_BASE_M) * Math.clamp((y - bottom) / (top - bottom), 0f, 1f);
        }
        return SLAB_TOP_M + (CIRRUS_M - SLAB_TOP_M) * Math.clamp((y - top) / (CIRRUS_Y - top), 0f, 1f);
    }

    /** How far (radians) the horizon dips below level, seen from {@code altitude} metres. */
    static double horizonDip(double altitude) {
        return Math.sqrt(2.0 * altitude / EARTH_RADIUS_M);
    }

    /**
     * The sun's elevation (radians) above the horizon of a cloud at {@code altitude}
     * metres, for a sun whose true direction has height {@code sunY}.
     */
    static double elevationAbove(float sunY, double altitude) {
        return Math.asin(Math.clamp(sunY, -1f, 1f)) + horizonDip(altitude);
    }

    /** Kasten and Young's relative air mass at elevation {@code e} radians (finite above −6.08°). */
    static double airMass(double e) {
        double degrees = Math.toDegrees(e);
        return 1.0 / (Math.sin(e) + 0.50572 * Math.pow(degrees + 6.07995, -1.6364));
    }

    /**
     * Sunlight on a cloud at {@code altitude} metres, into {@code dest}: black once the sun
     * is below its horizon, deepening from white through gold to red as it nears it.
     * {@code tau} is the zenith optical depth per channel.
     */
    static Vector3f sunColor(float sunY, double altitude, Vector3f tau, Vector3f dest) {
        double e = elevationAbove(sunY, altitude);
        double t = Math.clamp((e + SUN_EDGE) / (2.0 * SUN_EDGE), 0.0, 1.0);
        double lit = t * t * (3.0 - 2.0 * t);
        if (lit <= 0.0) return dest.zero();
        // Never below the edge's foot, where the formula is still finite and the light ~0 anyway.
        double m = airMass(Math.max(e, -SUN_EDGE)) - 1.0; // relative to the overhead sun
        double r = Math.exp(-tau.x * m), g = Math.exp(-tau.y * m), b = Math.exp(-tau.z * m);
        double scale = lit * Math.cbrt(r) / r;
        return dest.set((float) (r * scale), (float) (g * scale), (float) (b * scale));
    }

    /**
     * Whether the sun still lights the highest cloud (the cirrus): until then it stays the
     * clouds' key light, whatever lights the ground.
     */
    public static boolean sunLightsHighestCloud(float sunY) {
        return elevationAbove(sunY, CIRRUS_M) > -SUN_EDGE;
    }

    /**
     * Fills {@code rgb} ({@link #TEXELS} × 3 floats) with the sunlight on a cloud at each of the
     * table's heights, {@link #tableBottom} to {@link #tableTop} with texel centres at the ends,
     * times {@code strength}.
     */
    public static void bake(float sunY, Vector3f tau, float strength, float[] rgb) {
        Vector3f c = new Vector3f();
        float bottom = tableBottom(), top = tableTop();
        for (int i = 0; i < TEXELS; i++) {
            float y = bottom + (top - bottom) * i / (TEXELS - 1);
            sunColor(sunY, virtualAltitude(y), tau, c).mul(strength);
            rgb[3 * i] = c.x;
            rgb[3 * i + 1] = c.y;
            rgb[3 * i + 2] = c.z;
        }
    }

    /** Fills {@code rgb} with one colour at every height (the moon's light, which is the same for all). */
    public static void fill(Vector3f color, float[] rgb) {
        for (int i = 0; i < TEXELS; i++) {
            rgb[3 * i] = color.x;
            rgb[3 * i + 1] = color.y;
            rgb[3 * i + 2] = color.z;
        }
    }
}
