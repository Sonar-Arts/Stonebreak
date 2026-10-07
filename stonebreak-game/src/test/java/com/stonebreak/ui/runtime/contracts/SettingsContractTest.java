package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.stonebreak.config.Settings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code stonebreak:settings} v2 field table (#289 review: full contract, float noise, live semantics). */
class SettingsContractTest {

    @Test
    void everySettingIsReadAndMatchesTheDeclaredType() {
        UiValue.Obj v = SettingsContract.read(Settings.defaults());
        assertNull(SettingsContract.TYPE.problem(v), "defaults satisfy the root type");
        assertEquals(SettingsContract.fields().keySet(), v.fields().keySet());
        assertTrue(v.fields().size() >= 30, "the contract covers the whole settings menu, not 5 fields");
    }

    @Test
    void floatsArePublishedAsTheirShortestDecimal() {
        Settings s = Settings.defaults();
        s.setUiScale(1.1f);
        s.setMasterVolume(0.3f);
        UiValue.Obj v = SettingsContract.read(s);
        assertEquals(UiValue.of(1.1), v.get("uiScale"), "1.1f must not read 1.100000023841858");
        assertEquals(UiValue.of(0.3), v.get("masterVolume"));
        // the value a document stages back is the stored value: nothing to write, nothing dirty
        assertTrue(SettingsContract.write(s, new UiValue.Obj(Map.of("uiScale", UiValue.of(1.1)))).isEmpty());
        assertEquals(1.1f, s.getUiScale());
    }

    @Test
    void writeTouchesOnlyPresentChangedFields() {
        Settings s = Settings.defaults();
        UiValue.Obj whole = SettingsContract.read(s);
        java.util.LinkedHashMap<String, UiValue> draft = new java.util.LinkedHashMap<>(whole.fields());
        draft.put("renderDistance", UiValue.of(12));
        draft.put("crosshairColorG", UiValue.of(0.25));
        List<String> changed = SettingsContract.write(s, new UiValue.Obj(draft));
        assertEquals(List.of("crosshairColorG", "renderDistance"), changed.stream().sorted().toList());
        assertEquals(12, s.getRenderDistance());
        assertEquals(0.25f, s.getCrosshairColorG());
        assertEquals(1f, s.getCrosshairColorR(), "a paired setter keeps the other components");

        // a partial commit (only staged fields) leaves the rest alone
        List<String> partial = SettingsContract.write(s, new UiValue.Obj(Map.of("maxFps", UiValue.of(90),
            "uiScale", UiValue.NULL)));
        assertEquals(List.of("maxFps"), partial);
        assertEquals(90, s.getMaxFps());
    }

    @Test
    void rangesAndChoicesAreRefusedUpFrontNotClamped() {
        assertNotNull(SettingsContract.problem("uiScale", UiValue.of(5)));
        assertNotNull(SettingsContract.problem("masterVolume", UiValue.of(-0.1)));
        assertNotNull(SettingsContract.problem("shadowQuality", UiValue.of("ULTRA")));
        assertNotNull(SettingsContract.problem("renderDistance", UiValue.of("far")), "type mismatch");
        assertNotNull(SettingsContract.problem("noSuchSetting", UiValue.of(1)));
        assertNull(SettingsContract.problem("shadowQuality", UiValue.of("HIGH")));
        assertNull(SettingsContract.problem("uiScale", UiValue.NULL), "absent from a partial commit");
        assertNotNull(SettingsContract.problem(new UiValue.Obj(Map.of("maxFps", UiValue.of(1)))));
    }

    @Test
    void liveFieldsApplyAtOnceAndOthersWaitForApply() {
        Settings s = Settings.defaults();
        List<String> pushed = new ArrayList<>();
        assertNull(SettingsContract.setLive(s, "musicVolume", UiValue.of(0.2), (f, set) -> pushed.add(f)));
        assertEquals(0.2f, s.getMusicVolume());
        assertNull(SettingsContract.setLive(s, "cloudsEnabled", UiValue.FALSE, (f, set) -> pushed.add(f)));
        assertEquals(List.of("musicVolume"), pushed, "read-live fields need no push");

        int before = s.getRenderDistance();
        assertNotNull(SettingsContract.setLive(s, "renderDistance", UiValue.of(4), (f, set) -> pushed.add(f)),
            "world distances are pushed on Apply, as in the legacy menu");
        assertEquals(before, s.getRenderDistance());
        assertNotNull(SettingsContract.setLive(s, "musicVolume", UiValue.of(4), (f, set) -> pushed.add(f)));
        assertTrue(SettingsContract.liveFields().containsAll(List.of("musicVolume", "musicEnabled", "lodEnabled",
            "vsyncEnabled", "maxFps", "leafTransparency", "smoothLightingEnabled")));
    }
}
