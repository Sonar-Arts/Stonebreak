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
        s.setPreferredLook("{\"format\":\"omlook\",\"version\":\"1.0\",\"slots\":{\"hat\":\"TOP_HAT\"}}");
        s.setCrosshairStyle("DOT");
        s.setCrosshairSize(24.0f);
        s.setCrosshairThickness(3.0f);
        s.setCrosshairGap(6.0f);
        s.setCrosshairOpacity(0.5f);
        s.setCrosshairColor(0.1f, 0.2f, 0.3f);
        s.setCrosshairOutline(false);
        s.setLeafTransparency(false);
        s.setConnectedShapes(false);
        s.setWaterShaderEnabled(false);
        s.setCloudsEnabled(false);
        s.setGodRaysEnabled(false);
        s.setBloomEnabled(false);
        s.setShadowsEnabled(false);
        s.setPlayerNameTagsEnabled(false);
        s.setPauseSingleplayerWorld(true);
        s.setHideArmWithHud(true);
        s.setLiveMenuBackground(false);
        s.setShadowQuality("HIGH");
        s.setShadowDistance(120);
        s.setSmoothLightingEnabled(false);
        s.setRenderDistance(12);
        s.setLodDistance(6);
        s.setLodEnabled(false);
        s.setLodQuality("ULTRA");
        s.setVsyncEnabled(false);
        s.setMaxFps(144);
        s.setMultiplayerPort(30000);
        s.setLastJoinHost("example.test");
        s.setMultiplayerUsername("Tester");
        s.setUiScale(1.5f);
        s.setReducedMotion(true);
        s.setUiTextScale(1.25f);
        s.setUiBindings(java.util.Map.of("ui.submit", java.util.List.of("key:69+exact", "pad:0")));
        return s;
    }

    private static void assertSameSettings(Settings expected, Settings actual) {
        assertEquals(expected.getWindowWidth(), actual.getWindowWidth());
        assertEquals(expected.getWindowHeight(), actual.getWindowHeight());
        assertEquals(expected.getMasterVolume(), actual.getMasterVolume(), EPS);
        assertEquals(expected.getMusicVolume(), actual.getMusicVolume(), EPS);
        assertEquals(expected.getMusicEnabled(), actual.getMusicEnabled());
        assertEquals(expected.getPreferredLook(), actual.getPreferredLook());
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
        assertEquals(expected.getConnectedShapes(), actual.getConnectedShapes());
        assertEquals(expected.getWaterShaderEnabled(), actual.getWaterShaderEnabled());
        assertEquals(expected.getCloudsEnabled(), actual.getCloudsEnabled());
        assertEquals(expected.getGodRaysEnabled(), actual.getGodRaysEnabled());
        assertEquals(expected.getBloomEnabled(), actual.getBloomEnabled());
        assertEquals(expected.getShadowsEnabled(), actual.getShadowsEnabled());
        assertEquals(expected.getPlayerNameTagsEnabled(), actual.getPlayerNameTagsEnabled());
        assertEquals(expected.getPauseSingleplayerWorld(), actual.getPauseSingleplayerWorld());
        assertEquals(expected.getHideArmWithHud(), actual.getHideArmWithHud());
        assertEquals(expected.getLiveMenuBackground(), actual.getLiveMenuBackground());
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
        assertEquals(expected.isReducedMotion(), actual.isReducedMotion());
        assertEquals(expected.getUiTextScale(), actual.getUiTextScale(), EPS);
        assertEquals(expected.getUiBindings(), actual.getUiBindings());
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
        assertEquals(45, tree.size());
        assertTrue(tree.has("connectedShapes"));
        assertTrue(tree.has("verticalRenderDistance"));
        assertTrue(tree.has("preferredLook"));
        assertTrue(!tree.has("selectedHat") && !tree.has("selectedHair") && !tree.has("selectedAccessory"));
        assertTrue(tree.has("particlesEnabled"));
        assertTrue(tree.has("particleDensity"));
        assertTrue(tree.has("pauseSingleplayerWorld"));
        assertTrue(tree.has("hideArmWithHud"));
        assertTrue(tree.has("liveMenuBackground"));
        assertTrue(tree.has("windowWidth"));
        assertTrue(tree.has("uiScale"));
        assertTrue(tree.has("multiplayerUsername"));
        assertTrue(tree.has("lodQuality"));
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
                  "shadowQuality": "ULTRA"
                }
                """));

        assertEquals(Settings.MAX_MAX_FPS, loaded.getMaxFps());
        assertEquals(2.0f, loaded.getUiScale(), EPS);
        assertEquals(Settings.MIN_SHADOW_DISTANCE, loaded.getShadowDistance());
        assertEquals(0.0f, loaded.getMasterVolume(), EPS);
        assertEquals("MEDIUM", loaded.getShadowQuality());
    }

    @Test
    void nonObjectRootKeepsDefaults() throws Exception {
        Settings defaults = new Settings();
        Settings loaded = new Settings();

        loaded.apply(MAPPER.readTree("[1, 2, 3]"));

        assertEquals(defaults.toJson(), loaded.toJson());
    }

    @Test
    void retiredArmModelKeyIsIgnoredAndNotWrittenBack() throws Exception {
        Settings loaded = new Settings();
        loaded.apply(MAPPER.readTree("""
                { "armModelType": "SLIM", "masterVolume": 0.4 }
                """));

        assertEquals(0.4f, loaded.getMasterVolume(), EPS);
        assertFalse(loaded.toJson().has("armModelType"));
    }

    /** #459: the old cosmetic keys become the preferred look, once; the old keys are never written again. */
    @Test
    void legacyCosmeticsMigrateOnceIntoThePreferredLook() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(com.openmason.engine.cenda.CendaKernels.isAvailable(),
                "the look codec is native");
        Settings loaded = new Settings();
        loaded.apply(MAPPER.readTree(
                "{\"selectedHat\":\"TOP_HAT\",\"selectedHair\":\"MALE_HAIR_2\",\"selectedAccessory\":\"GOLD_EARRING\"}"));
        var look = com.stonebreak.player.appearance.PlayerAppearance.readJson(loaded.getPreferredLook());
        assertEquals("TOP_HAT", look.item("hat"));
        assertEquals("MALE_HAIR_2", look.item("hair"));
        assertEquals("GOLD_EARRING", look.item("accessory"));
        ObjectNode saved = loaded.toJson();
        assertTrue(!saved.has("selectedHat") && !saved.has("selectedHair") && !saved.has("selectedAccessory"));

        // Once: a file that already has a preferred look ignores stale old keys.
        Settings again = new Settings();
        ObjectNode mixed = saved.deepCopy();
        mixed.put("selectedHat", "PIRATE_HAT");
        again.apply(mixed);
        assertEquals(loaded.getPreferredLook(), again.getPreferredLook());
    }

    @Test
    void legacyHairInTheHatSlotMovesToHairUnlessHairWasChosen() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(com.openmason.engine.cenda.CendaKernels.isAvailable(),
                "the look codec is native");
        Settings old = new Settings();
        old.apply(MAPPER.readTree("{\"selectedHat\":\"male_hair_1\"}"));
        var look = com.stonebreak.player.appearance.PlayerAppearance.readJson(old.getPreferredLook());
        assertEquals("NONE", look.item("hat"));
        assertEquals("MALE_HAIR_1", look.item("hair"));

        Settings explicit = new Settings();
        explicit.apply(MAPPER.readTree("{\"selectedHat\":\"MALE_HAIR_1\",\"selectedHair\":\"MALE_HAIR_2\"}"));
        var look2 = com.stonebreak.player.appearance.PlayerAppearance.readJson(explicit.getPreferredLook());
        assertEquals("NONE", look2.item("hat"));
        assertEquals("MALE_HAIR_2", look2.item("hair"));
    }

    @Test
    void noOldKeysNoPreferredLook() throws Exception {
        Settings loaded = new Settings();
        loaded.apply(MAPPER.readTree("{\"masterVolume\": 0.5}"));
        assertEquals(null, loaded.getPreferredLook());
    }

    /** #288: accessibility preferences and remapped UI bindings persist; defaults write no bindings. */
    @Test
    void uiAccessibilitySettingsPersistAndClamp() throws Exception {
        Settings defaults = new Settings();
        assertTrue(!defaults.toJson().has("uiBindings"), "unremapped bindings are not written");
        Settings loaded = new Settings();
        loaded.apply(MAPPER.readTree("""
            {"reducedMotion": true, "uiTextScale": 9, "uiBindings": {"ui.cancel": ["key:66", 5], "x": 3}}
            """));
        assertTrue(loaded.isReducedMotion());
        assertEquals(3.0f, loaded.getUiTextScale(), EPS);
        assertEquals(java.util.Map.of("ui.cancel", java.util.List.of("key:66")), loaded.getUiBindings(),
            "non-text entries and non-array actions are dropped");
        var map = com.openmason.engine.ui.runtime.input.UiActionMap.fromWire(loaded.getUiBindings());
        assertEquals(com.openmason.engine.ui.runtime.input.UiAction.CANCEL, map.actionForKey(66, 0));
    }
}
