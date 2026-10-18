package com.openmason.engine.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Engine properties are {@code openmason.*}; the legacy {@code stonebreak.*} names still work (#378). */
class EnginePropertiesTest {

    private static final String KEY = "enginepropertiestest.switch";

    @AfterEach
    void clear() {
        System.clearProperty(EngineProperties.PREFIX + KEY);
        System.clearProperty(EngineProperties.LEGACY_PREFIX + KEY);
    }

    @Test
    void unsetFallsBackToTheDefault() {
        assertEquals("dflt", EngineProperties.get(KEY, "dflt"));
        assertFalse(EngineProperties.flag(KEY));
        assertFalse(EngineProperties.isOff(KEY));
    }

    @Test
    void theNewNameIsRead() {
        System.setProperty("openmason." + KEY, "off");
        assertEquals("off", EngineProperties.get(KEY, "on"));
        assertTrue(EngineProperties.isOff(KEY));
    }

    @Test
    void theLegacyNameStillWorks() {
        System.setProperty("stonebreak." + KEY, "true");
        assertTrue(EngineProperties.flag(KEY));
    }

    @Test
    void theNewNameWinsOverTheLegacyOne() {
        System.setProperty("stonebreak." + KEY, "off");
        System.setProperty("openmason." + KEY, "on");
        assertFalse(EngineProperties.isOff(KEY));
    }

    @Test
    void killSwitchesAcceptOffAndFalse() {
        System.setProperty("openmason." + KEY, "FALSE");
        assertTrue(EngineProperties.isOff(KEY));
        System.setProperty("openmason." + KEY, "anything-else");
        assertFalse(EngineProperties.isOff(KEY));
    }

    @Test
    void nameSpellsTheFlagToPass() {
        assertEquals("openmason.sparse", EngineProperties.name("sparse"));
    }
}
