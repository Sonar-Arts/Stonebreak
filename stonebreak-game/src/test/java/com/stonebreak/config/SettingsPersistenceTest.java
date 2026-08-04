package com.stonebreak.config;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settings save/load runs off one declarative field table, so these tests guard
 * the properties that table is supposed to give us: every setting survives a
 * round trip, a hand-edited file gets the same clamping the UI does, and a
 * malformed value is ignored instead of corrupting a neighbouring setting.
 */
class SettingsPersistenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final float EPS = 1e-5f;

    /** Every persisted setting moved off its default, so a dropped or mis-wired row shows up. */
    private static Settings fullyPopulated() {
        Settings s = new Settings();
        s.setResolution(1920, 1080);
        s.setMasterVolume(0.25f);
        s.setMusicVolume(0.75f);
        s.setMusicEnabled(false);
        s.setArmModelType("SLIM");
        s.setSelectedHat("TOP_HAT");
        s.setCrosshairStyle("DOT");
        s.setCrosshairSize(24.0f);
        s.setCrosshairThickness(3.0f);
        s.setCrosshairGap(6.0f);
        s.setCrosshairOpacity(0.5f);
        s.setCrosshairColor(0.1f, 0.2f, 0.3f);
        s.setCrosshairOutline(false);
        s.setLeafTransparency(false);
        s.setWaterShaderEnabled(false);
        s.setCloudsEnabled(false);
        s.setGodRaysEnabled(false);
        s.setShadowsEnabled(false);
        s.setPlayerNameTagsEnabled(false);
        s.setShadowQuality("HIGH");
        s.setShadowDistance(120);
        s.setSmoothLightingEnabled(false);
        s.setRenderDistance(12);
        s.setLodDistance(6);
        s.setLodEnabled(false);
        s.setVsyncEnabled(false);
        s.setMaxFps(144);
        s.setMultiplayerPort(30000);
        s.setLastJoinHost("example.test");
        s.setMultiplayerUsername("Tester");
        s.setUiScale(1.5f);
        return s;
    }

    private static void assertSameSettings(Settings expected, Settings actual) {
        assertEquals(expected.getWindowWidth(), actual.getWindowWidth());
        assertEquals(expected.getWindowHeight(), actual.getWindowHeight());
        assertEquals(expected.getMasterVolume(), actual.getMasterVolume(), EPS);
        assertEquals(expected.getMusicVolume(), actual.getMusicVolume(), EPS);
        assertEquals(expected.getMusicEnabled(), actual.getMusicEnabled());
        assertEquals(expected.getArmModelType(), actual.getArmModelType());
        assertEquals(expected.getSelectedHat(), actual.getSelectedHat());
        assertEquals(expected.getCrosshairStyle(), actual.getCrosshairStyle());
        assertEquals(expected.getCrosshairSize(), actual.getCrosshairSize(), EPS);
        assertEquals(expected.getCrosshairThickness(), actual.getCrosshairThickness(), EPS);
        assertEquals(expected.getCrosshairGap(), actual.getCrosshairGap(), EPS);
        assertEquals(expected.getCrosshairOpacity(), actual.getCrosshairOpacity(), EPS);
        assertEquals(expected.getCrosshairColorR(), actual.getCrosshairColorR(), EPS);
        assertEquals(expected.getCrosshairColorG(), actual.getCrosshairColorG(), EPS);
        assertEquals(expected.getCrosshairColorB(), actual.getCrosshairColorB(), EPS);
        assertEquals(expected.getCrosshairOutline(), actual.getCrosshairOutline());
        assertEquals(expected.getLeafTransparency(), actual.getLeafTransparency());
        assertEquals(expected.getWaterShaderEnabled(), actual.getWaterShaderEnabled());
        assertEquals(expected.getCloudsEnabled(), actual.getCloudsEnabled());
        assertEquals(expected.getGodRaysEnabled(), actual.getGodRaysEnabled());
        assertEquals(expected.getShadowsEnabled(), actual.getShadowsEnabled());
        assertEquals(expected.getPlayerNameTagsEnabled(), actual.getPlayerNameTagsEnabled());
        assertEquals(expected.getShadowQuality(), actual.getShadowQuality());
        assertEquals(expected.getShadowDistance(), actual.getShadowDistance());
        assertEquals(expected.getSmoothLightingEnabled(), actual.getSmoothLightingEnabled());
        assertEquals(expected.getRenderDistance(), actual.getRenderDistance());
        assertEquals(expected.getLodDistance(), actual.getLodDistance());
        assertEquals(expected.getLodEnabled(), actual.getLodEnabled());
        assertEquals(expected.isVsyncEnabled(), actual.isVsyncEnabled());
        assertEquals(expected.getMaxFps(), actual.getMaxFps());
        assertEquals(expected.getMultiplayerPort(), actual.getMultiplayerPort());
        assertEquals(expected.getLastJoinHost(), actual.getLastJoinHost());
        assertEquals(expected.getMultiplayerUsername(), actual.getMultiplayerUsername());
        assertEquals(expected.getUiScale(), actual.getUiScale(), EPS);
    }

    @Test
    void everySettingSurvivesARoundTrip() {
        Settings saved = fullyPopulated();

        Settings loaded = new Settings();
        loaded.apply(saved.toJson());

        assertSameSettings(saved, loaded);
    }

    @Test
    void roundTripIsStableAcrossASecondPass() {
        Settings first = new Settings();
        first.apply(fullyPopulated().toJson());

        Settings second = new Settings();
        second.apply(first.toJson());

        assertEquals(first.toJson(), second.toJson());
    }

    /**
     * Each row writes and reads the same key, so the serialised tree must carry one
     * field per persisted setting — a duplicated key would silently collapse two rows.
     */
    @Test
    void serialisedTreeHasOneFieldPerSetting() {
        ObjectNode tree = fullyPopulated().toJson();
        assertEquals(34, tree.size());
        assertTrue(tree.has("windowWidth"));
        assertTrue(tree.has("uiScale"));
        assertTrue(tree.has("multiplayerUsername"));
    }

    @Test
    void absentAndUnknownKeysLeaveDefaultsAlone() throws Exception {
        Settings defaults = new Settings();
        Settings loaded = new Settings();

        loaded.apply(MAPPER.readTree("{\"renderDistance\": 14, \"somethingRemoved\": 7}"));

        assertEquals(14, loaded.getRenderDistance());
        assertEquals(defaults.getMaxFps(), loaded.getMaxFps());
        assertEquals(defaults.getMultiplayerUsername(), loaded.getMultiplayerUsername());
    }

    /**
     * The value is dropped and every other key still applies — the old line-scanning
     * parser could mis-assign a malformed line to whichever setting it matched first.
     */
    @Test
    void wrongTypedValuesAreIgnoredWithoutDisturbingNeighbours() throws Exception {
        Settings defaults = new Settings();
        Settings loaded = new Settings();

        JsonNode root = MAPPER.readTree("""
                {
                  "renderDistance": "not-a-number",
                  "vsyncEnabled": "yes",
                  "multiplayerUsername": 42,
                  "maxFps": 120
                }
                """);
        loaded.apply(root);

        assertEquals(defaults.getRenderDistance(), loaded.getRenderDistance());
        assertEquals(defaults.isVsyncEnabled(), loaded.isVsyncEnabled());
        assertEquals(defaults.getMultiplayerUsername(), loaded.getMultiplayerUsername());
        assertEquals(120, loaded.getMaxFps());
    }

    /** Loading routes through the setters, so a hand-edited file is clamped like the UI. */
    @Test
    void outOfRangeValuesAreClampedOnLoad() throws Exception {
        Settings loaded = new Settings();

        loaded.apply(MAPPER.readTree("""
                {
                  "maxFps": 99999,
                  "uiScale": 12.0,
                  "shadowDistance": 1,
                  "masterVolume": -3.0,
                  "shadowQuality": "ULTRA",
                  "armModelType": "GIGANTIC"
                }
                """));

        assertEquals(Settings.MAX_MAX_FPS, loaded.getMaxFps());
        assertEquals(2.0f, loaded.getUiScale(), EPS);
        assertEquals(Settings.MIN_SHADOW_DISTANCE, loaded.getShadowDistance());
        assertEquals(0.0f, loaded.getMasterVolume(), EPS);
        assertEquals("MEDIUM", loaded.getShadowQuality());
        assertEquals("REGULAR", loaded.getArmModelType());
    }

    @Test
    void nonObjectRootKeepsDefaults() throws Exception {
        Settings defaults = new Settings();
        Settings loaded = new Settings();

        loaded.apply(MAPPER.readTree("[1, 2, 3]"));

        assertEquals(defaults.toJson(), loaded.toJson());
    }

    @Test
    void slimArmsFlagTracksTheArmModelType() {
        Settings loaded = new Settings();
        loaded.apply(fullyPopulated().toJson());

        assertTrue(loaded.isSlimArms());

        loaded.setSlimArms(false);
        assertFalse(loaded.isSlimArms());
        assertEquals("REGULAR", loaded.getArmModelType());
    }
}
