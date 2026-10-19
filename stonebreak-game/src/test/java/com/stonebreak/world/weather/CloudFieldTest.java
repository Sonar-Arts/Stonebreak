package com.stonebreak.world.weather;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cloud field is the one place every client and the server learn where
 * clouds are, so it must be a pure function of seed, position and weather time,
 * and the weather modes must actually move the sky where their names say.
 */
class CloudFieldTest {

    private static final long SEED = 987654321L;
    private static final int SPAN = 400;

    @Test
    void sameSeedAndPositionAnswerIdentically() {
        CloudField a = new CloudField(SEED);
        CloudField b = new CloudField(SEED);
        for (int i = -50; i < 50; i++) {
            assertEquals(a.raw(i * 7, i * -3), b.raw(i * 7, i * -3));
        }
    }

    @Test
    void driftCarriesThePatternDownwind() {
        CloudField field = new CloudField(SEED);
        WeatherBias bias = WeatherMode.CLOUDY.fixedBias();
        double later = 1000.0;
        for (int x = 0; x < 200; x += 8) {
            CloudColumn now = field.columnAt(x, 40, 0.0, bias);
            CloudColumn moved = field.columnAt(
                    x + CloudField.driftX(later), 40 + CloudField.driftZ(later), later, bias);
            assertEquals(now, moved);
        }
    }

    @Test
    void clearModeEmptiesTheSky() {
        assertTrue(coverage(WeatherMode.CLEAR) == 0.0, "CLEAR should leave no cloud");
    }

    @Test
    void modesOrderByCoverage() {
        double cloudy = coverage(WeatherMode.CLOUDY);
        double rain = coverage(WeatherMode.RAIN);
        double storm = coverage(WeatherMode.STORM);
        assertTrue(cloudy > 0.15 && cloudy < 0.6, "CLOUDY coverage " + cloudy);
        assertTrue(rain > cloudy, "RAIN " + rain + " vs CLOUDY " + cloudy);
        assertTrue(storm > rain, "STORM " + storm + " vs RAIN " + rain);
    }

    @Test
    void cloudyRarelyRainsAndStormDoes() {
        // Localized showers are fine under CLOUDY; widespread rain is not.
        assertTrue(fractionOf(WeatherMode.CLOUDY, true) < 0.10, "CLOUDY should rarely precipitate");
        assertTrue(fractionOf(WeatherMode.STORM, true) > 0.2, "STORM should mostly precipitate");
        assertTrue(stormFraction(WeatherMode.STORM) > 0.05, "STORM needs cumulonimbus cells");
    }

    @Test
    void columnsStayInsideTheSlabOnVoxelBoundaries() {
        CloudField field = new CloudField(SEED);
        WeatherBias bias = WeatherMode.STORM.fixedBias();
        for (int x = 0; x < SPAN; x++) {
            CloudColumn col = CloudField.resolve(field.raw(x, x / 2), bias);
            if (col.isClear()) continue;
            assertTrue(col.baseY() >= CloudType.slabBottom() && col.topY() <= CloudType.slabTop());
            assertEquals(0, col.baseY() % CloudField.VOXEL);
            assertEquals(0, col.topY() % CloudField.VOXEL);
            assertTrue(col.topY() > col.baseY());
        }
    }

    @Test
    void autoBiasIsDeterministicAndWanders() {
        CloudField a = new CloudField(SEED);
        CloudField b = new CloudField(SEED);
        float min = 1f, max = -1f;
        for (int s = 0; s < 200_000; s += 500) {
            WeatherBias ba = a.bias(WeatherMode.AUTO, 100, -300, s);
            assertEquals(ba, b.bias(WeatherMode.AUTO, 100, -300, s));
            min = Math.min(min, ba.coverage());
            max = Math.max(max, ba.coverage());
        }
        assertTrue(max - min > 0.8f, "AUTO coverage should swing, got " + min + ".." + max);
    }

    @Test
    void autoWeatherIsRegional() {
        // At one moment, places a few weather systems apart see different skies.
        CloudField field = new CloudField(SEED);
        float min = 1f, max = -1f;
        for (int x = -30_000; x <= 30_000; x += 1500) {
            float c = field.bias(WeatherMode.AUTO, x, 7_000, 5000).coverage();
            min = Math.min(min, c);
            max = Math.max(max, c);
        }
        assertTrue(max - min > 0.8f, "AUTO coverage across the land should vary, got " + min + ".." + max);
    }

    @Test
    void zonesHoldTheirWeatherAndFadeAtTheEdge() {
        CloudField field = new CloudField(SEED);
        var zones = java.util.List.of(new WeatherZone(1000, 2000, 1500f, WeatherMode.STORM));
        assertEquals(WeatherMode.STORM.fixedBias(), field.bias(WeatherMode.CLEAR, zones, 1000, 2000, 50));
        assertEquals(WeatherMode.CLEAR.fixedBias(), field.bias(WeatherMode.CLEAR, zones, 1000, 3600, 50));
        float edge = field.bias(WeatherMode.CLEAR, zones, 1000, 2000 + 1250, 50).coverage();
        assertTrue(edge > WeatherMode.CLEAR.fixedBias().coverage() && edge < WeatherMode.STORM.fixedBias().coverage(),
                "the edge blends, got " + edge);
    }

    /**
     * AUTO is calibrated to continental mid-latitude climatology: sky cover
     * seen from a point (cloudy share within 1500 blocks) is clear (under 2/8),
     * broken or overcast (over 7/8) about a third of the time each; rain falls
     * on a point about 10% of the time, and thunderstorms are overhead 1-2%.
     * The weather is regional, so each cell takes the bias where it is.
     */
    @Test
    void autoMatchesClimatology() {
        CloudField field = new CloudField(SEED);
        int radius = 1500 / CloudField.CELL, stride = 5;
        int clear = 0, overcast = 0, samples = 0;
        long cells = 0, rain = 0, storm = 0;
        for (double t = 0; t < 2_000_000; t += 2500) {
            int ox = (int) (t / 7);
            int cloudy = 0, total = 0;
            for (int i = -radius; i <= radius; i += stride) {
                for (int j = -radius; j <= radius; j += stride) {
                    if (i * i + j * j > radius * radius) continue;
                    WeatherBias bias = field.bias(WeatherMode.AUTO, (ox + i + 0.5) * CloudField.CELL
                            + CloudField.driftX(t), (j + 0.5) * CloudField.CELL + CloudField.driftZ(t), t);
                    CloudColumn col = CloudField.resolve(field.raw(ox + i, j), bias);
                    total++;
                    if (col.isClear()) continue;
                    cloudy++;
                    if (col.precipitation() > 0f) rain++;
                    if (col.type().storm) storm++;
                }
            }
            cells += total;
            double cover = cloudy / (double) total;
            if (cover < 0.25) clear++;
            else if (cover > 0.875) overcast++;
            samples++;
        }
        double clearShare = clear / (double) samples, overcastShare = overcast / (double) samples;
        double rainShare = rain / (double) cells, stormShare = storm / (double) cells;
        String report = String.format("clear %.2f overcast %.2f rain %.3f storm %.4f",
                clearShare, overcastShare, rainShare, stormShare);
        assertTrue(clearShare > 0.25 && clearShare < 0.45, report);
        assertTrue(overcastShare > 0.2 && overcastShare < 0.4, report);
        assertTrue(1 - clearShare - overcastShare > 0.25, report);
        assertTrue(rainShare > 0.06 && rainShare < 0.14, report);
        assertTrue(stormShare > 0.005 && stormShare < 0.025, report);
    }

    /**
     * Rain follows the cloud block by block: the smooth field changes only a
     * little between neighbouring blocks (the old cell lookup jumped every
     * {@link CloudField#CELL} blocks), and somewhere it does rain.
     */
    @Test
    void rainChangesSmoothlyBetweenBlocks() {
        CloudField field = new CloudField(SEED);
        WeatherBias bias = WeatherMode.RAIN.fixedBias();
        float worst = 0f, most = 0f;
        for (int z = 0; z < 4000; z += 97) {
            float prev = field.columnAt(0, z, 0.0, bias).precipitation();
            for (int x = 1; x < 4000; x++) {
                float p = field.columnAt(x, z, 0.0, bias).precipitation();
                worst = Math.max(worst, Math.abs(p - prev));
                most = Math.max(most, p);
                prev = p;
            }
        }
        assertTrue(most > 0.5f, "it rains somewhere: " + most);
        assertTrue(worst < 0.12f, "rain jumps by " + worst + " between neighbouring blocks");
    }

    @Test
    void smallCloudsRainLighterThanBigOnes() {
        // Same type and density, different growth.
        CloudColumn small = new CloudColumn(1f, CloudType.NIMBOSTRATUS, 160, 168, 0.05f, 0.8f);
        CloudColumn big = new CloudColumn(1f, CloudType.NIMBOSTRATUS, 160, 208, 1f, 0.8f);
        assertTrue(small.precipitation() > 0f);
        assertTrue(small.precipitation() < 0.5f * big.precipitation(),
                small.precipitation() + " vs " + big.precipitation());
    }

    /**
     * Whether a cloud rides over a mountain or is steered aside changes slowly
     * across the sky, so a cloud is never torn between the two: even at the
     * full {@link TerrainFlow#DEFLECT_REACH} the steering would move by under a
     * block per block (the baked shift is far smaller, and sheared at most
     * {@code TerrainRelief.MAX_SHEAR}, so the two together never fold the map).
     */
    @Test
    void massChangesSlowlyAcrossTheSky() {
        CloudField field = new CloudField(SEED);
        float worst = 0f;
        for (WeatherMode mode : new WeatherMode[]{WeatherMode.CLOUDY, WeatherMode.RAIN, WeatherMode.STORM}) {
            WeatherBias bias = mode.fixedBias();
            for (int z = 0; z < 3000; z += 211) {
                float prev = hold(field.sample(0, z), bias);
                for (int x = 4; x < 6000; x += 4) {
                    float h = hold(field.sample(x, z), bias);
                    worst = Math.max(worst, Math.abs(h - prev) / 4f * TerrainFlow.DEFLECT_REACH);
                    prev = h;
                }
            }
        }
        assertTrue(worst < 1f, "steering shifts by " + worst + " blocks per block as mass changes");
    }

    private static float hold(CloudField.Channels ch, WeatherBias bias) {
        return 1f - CloudField.smoothstep(TerrainFlow.PASS_MASS_LO, TerrainFlow.PASS_MASS_HI, CloudField.mass(ch, bias));
    }

    private static double coverage(WeatherMode mode) {
        CloudField field = new CloudField(SEED);
        WeatherBias bias = mode.fixedBias();
        int cloudy = 0;
        for (int x = 0; x < SPAN; x++)
            for (int z = 0; z < SPAN; z++)
                if (!CloudField.resolve(field.raw(x, z), bias).isClear()) cloudy++;
        return cloudy / (double) (SPAN * SPAN);
    }

    private static double fractionOf(WeatherMode mode, boolean precipitating) {
        CloudField field = new CloudField(SEED);
        WeatherBias bias = mode.fixedBias();
        int hits = 0, cloudy = 0;
        for (int x = 0; x < SPAN; x++)
            for (int z = 0; z < SPAN; z++) {
                CloudColumn col = CloudField.resolve(field.raw(x, z), bias);
                if (col.isClear()) continue;
                cloudy++;
                if (col.type().precipitates == precipitating) hits++;
            }
        return cloudy == 0 ? 0 : hits / (double) cloudy;
    }

    private static double stormFraction(WeatherMode mode) {
        CloudField field = new CloudField(SEED);
        WeatherBias bias = mode.fixedBias();
        int storm = 0, cloudy = 0;
        for (int x = 0; x < SPAN; x++)
            for (int z = 0; z < SPAN; z++) {
                CloudColumn col = CloudField.resolve(field.raw(x, z), bias);
                if (col.isClear()) continue;
                cloudy++;
                if (col.type().storm) storm++;
            }
        return cloudy == 0 ? 0 : storm / (double) cloudy;
    }
}
