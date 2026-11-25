package com.stonebreak.rendering.gameWorld.clouds;

import com.stonebreak.world.weather.CloudType;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Clouds keep the sun after it sets on the ground, high cloud longest, in light reddened by
 * the air it crossed; the noon sun stays white.
 */
class CloudSunlightTest {

    private static float sunY(float elevationDegrees) {
        return (float) Math.sin(Math.toRadians(elevationDegrees));
    }

    private static Vector3f at(float elevationDegrees, double altitude) {
        return CloudSunlight.sunColor(sunY(elevationDegrees), altitude, CloudSunlight.TAU, new Vector3f());
    }

    @Test
    void theHorizonDipsWithHeight() {
        // sqrt(2h/R): about 1.24 degrees from 1.5 km, 3.05 from 9 km.
        assertEquals(1.24, Math.toDegrees(CloudSunlight.horizonDip(CloudSunlight.SLAB_BASE_M)), 0.01);
        assertEquals(3.05, Math.toDegrees(CloudSunlight.horizonDip(CloudSunlight.CIRRUS_M)), 0.01);
    }

    @Test
    void kastenYoungAirMass() {
        assertEquals(1.0, CloudSunlight.airMass(Math.PI / 2), 1e-3);
        assertEquals(2.0, CloudSunlight.airMass(Math.toRadians(30)), 0.01);
        assertEquals(38.1, CloudSunlight.airMass(0.0), 0.2); // the tabulated horizon value
    }

    @Test
    void theNoonSunIsWhiteAndTheLowSunRed() {
        Vector3f noon = at(50f, CloudSunlight.SLAB_BASE_M);
        assertTrue(noon.x > 0.97f && noon.z > 0.9f, "near white at noon, got " + noon);
        Vector3f gold = at(10f, CloudSunlight.SLAB_BASE_M);
        Vector3f red = at(0.5f, CloudSunlight.SLAB_BASE_M);
        assertTrue(gold.x > gold.y && gold.y > gold.z, "warm at 10 degrees, got " + gold);
        assertTrue(red.y / red.x < gold.y / gold.x && red.z / red.x < gold.z / gold.x,
                "redder lower down: " + red + " vs " + gold);
        assertTrue(red.x < gold.x && red.x > 0.05f, "dimmer, but not black: " + red);
    }

    @Test
    void cloudBasesStayLitAfterSunsetAndHighCloudLast() {
        // 1.6 degrees down: under the low cloud's horizon (1.24 deg dip, +0.29 edge), above the cirrus's.
        assertEquals(0f, at(-1.6f, CloudSunlight.SLAB_BASE_M).length(), 1e-6f, "low cloud has lost it");
        assertTrue(at(-1.6f, CloudSunlight.SLAB_TOP_M).x > 0.05f, "a 4 km top still has it");
        assertTrue(at(-1.6f, CloudSunlight.CIRRUS_M).x > 0.05f, "the cirrus still has it");
        // At sunset the bases are lit in red.
        Vector3f base = at(-0.5f, CloudSunlight.SLAB_BASE_M);
        assertTrue(base.x > 0f && base.x > 3f * base.y, "red base light, got " + base);
        // The sun stays the clouds' key light until the cirrus loses it, 3.05 + 0.29 degrees down.
        assertTrue(CloudSunlight.sunLightsHighestCloud(sunY(-3.0f)));
        assertFalse(CloudSunlight.sunLightsHighestCloud(sunY(-3.5f)));
    }

    @Test
    void theTableSpansTheSlabToTheCirrus() {
        assertEquals(CloudSunlight.SLAB_BASE_M, CloudSunlight.virtualAltitude(CloudType.slabBottom()), 1e-3f);
        assertEquals(CloudSunlight.SLAB_TOP_M, CloudSunlight.virtualAltitude(CloudType.slabTop()), 1e-3f);
        assertEquals(CloudSunlight.CIRRUS_M, CloudSunlight.virtualAltitude(CloudSunlight.CIRRUS_Y), 1e-3f);
        float[] rgb = new float[3 * CloudSunlight.TEXELS];
        CloudSunlight.bake(sunY(-1.6f), CloudSunlight.TAU, 0.85f, rgb);
        assertEquals(0f, rgb[0], 1e-6f, "the bottom texel is the slab's base: dark");
        assertTrue(rgb[rgb.length - 3] > 0f, "the top texel is the cirrus: lit");
        CloudSunlight.bake(sunY(50f), CloudSunlight.TAU, 0.85f, rgb);
        assertEquals(0.85f, rgb[0], 0.03f, "strength scales the light");
    }
}
