package com.stonebreak.world.weather;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The local weather grid both rain and clouds read: it holds the weather of
 * each place, eases toward changes instead of popping, and zones are fixed
 * to the land and survive the save file.
 */
class WeatherMapTest {

    private static final long SEED = 42L;

    @Test
    void texelCentresHoldTheWeatherThere() {
        CloudField field = new CloudField(SEED);
        WeatherState state = new WeatherState(900, WeatherMode.AUTO);
        WeatherMap map = new WeatherMap();
        map.snap(field, state, 0, 0);
        for (int k = -20; k <= 20; k += 5) {
            double x = (k + 0.5) * WeatherMap.TEXEL_BLOCKS, z = (3 * k + 0.5) * WeatherMap.TEXEL_BLOCKS;
            WeatherBias expected = field.bias(WeatherMode.AUTO, state.zones(), x, z, 900);
            WeatherBias got = map.sample(x, z);
            assertEquals(expected.coverage(), got.coverage(), 1e-5f);
            assertEquals(expected.wetness(), got.wetness(), 1e-5f);
        }
    }

    @Test
    void aNewZoneRollsInRatherThanPopping() {
        CloudField field = new CloudField(SEED);
        WeatherState state = new WeatherState(0, WeatherMode.CLEAR);
        WeatherMap map = new WeatherMap();
        map.snap(field, state, 0, 0);
        state.placeZone(new WeatherZone(0, 0, WeatherZone.DEFAULT_RADIUS, WeatherMode.STORM));
        map.update(field, state, 0, 0, 1f);
        float after1s = map.sample(48, 48).coverage();
        assertTrue(after1s > WeatherMode.CLEAR.fixedBias().coverage() && after1s < 0f, "eases in: " + after1s);
        for (int i = 0; i < 240; i++) map.update(field, state, 0, 0, 0.5f);
        assertEquals(WeatherMode.STORM.fixedBias().coverage(), map.sample(48, 48).coverage(), 1e-3f);
        // Far outside the zone it stays clear.
        assertEquals(WeatherMode.CLEAR.fixedBias().coverage(), map.sample(3000, 0).coverage(), 1e-3f);
    }

    @Test
    void placingAZoneInPlaceReplacesIt() {
        WeatherState state = new WeatherState(0, WeatherMode.AUTO);
        state.placeZone(new WeatherZone(0, 0, 1500f, WeatherMode.STORM));
        state.placeZone(new WeatherZone(100, 0, 1500f, WeatherMode.CLEAR));
        state.placeZone(new WeatherZone(9000, 0, 1500f, WeatherMode.RAIN));
        assertEquals(2, state.zones().size());
        assertEquals(1, state.clearZonesAt(50, 0));
        assertEquals(List.of(new WeatherZone(9000, 0, 1500f, WeatherMode.RAIN)), state.zones());
        state.setMode(WeatherMode.CLOUDY);
        assertTrue(state.zones().isEmpty(), "a world-wide set lifts every zone");
    }

    @Test
    void zonesSurviveTheSaveFormat() {
        List<WeatherZone> zones = List.of(
                new WeatherZone(-1234.5, 99.25, 1500f, WeatherMode.STORM),
                new WeatherZone(7, -8, 600f, WeatherMode.CLEAR));
        assertEquals(zones, WeatherZone.parseAll(WeatherZone.formatAll(zones)));
        assertEquals(List.of(), WeatherZone.parseAll(null));
        assertEquals(List.of(), WeatherZone.parseAll(""));
        assertEquals(List.of(), WeatherZone.parseAll("1,2,oops,STORM;1,2,3,AUTO;garbage"));
    }
}
