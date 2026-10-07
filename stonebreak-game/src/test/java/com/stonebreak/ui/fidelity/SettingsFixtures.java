package com.stonebreak.ui.fidelity;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MDropdown;
import com.stonebreak.config.Settings;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.ui.runtime.contracts.SettingsContract;
import com.stonebreak.ui.settingsMenu.SettingsMenu;
import com.stonebreak.ui.settingsMenu.config.CategoryState;
import com.stonebreak.ui.settingsMenu.managers.StateManager;

/**
 * Pinned settings screens for the #299 fidelity gates. Every value the screen shows is set
 * ({@link #pin}; {@link #restore} puts the shared {@link Settings} back) and the UI-scale countdown
 * reads a pinned clock. A variant is a category ({@code general} ... {@code audio}) with optional
 * {@code -scrolled} (to the bottom), {@code -open} (the category's first dropdown open),
 * {@code -confirm} (the UI-scale confirmation up, 1.5x pending) and {@code -hover-<part>}. Not a
 * test class.
 */
public final class SettingsFixtures {

    private SettingsFixtures() {
    }

    public static final long PINNED_MS = 1_000_000L;

    /** Sets every value the screen shows; returns what to {@link #restore}. */
    public static UiValue.Obj pin() {
        Settings s = Settings.getInstance();
        UiValue.Obj before = SettingsContract.read(s);
        s.setResolutionByIndex(4);
        s.setMasterVolume(0.8f);
        s.setMusicVolume(0.45f);
        s.setMusicEnabled(true);
        s.setArmModelType("REGULAR");
        s.setCrosshairStyle("SIMPLE_CROSS");
        s.setCrosshairSize(16f);
        s.setPlayerNameTagsEnabled(true);
        s.setLeafTransparency(true);
        s.setWaterShaderEnabled(true);
        s.setCloudsEnabled(true);
        s.setGodRaysEnabled(false);
        s.setShadowsEnabled(true);
        s.setShadowQuality("MEDIUM");
        s.setShadowDistance(96);
        s.setSmoothLightingEnabled(true);
        s.setRenderDistance(10);
        s.setLodEnabled(true);
        s.setLodDistance(24);
        s.setLodQuality("MEDIUM");
        s.setVsyncEnabled(true);
        s.setMaxFps(144);
        return before;
    }

    public static void restore(UiValue.Obj before) {
        SettingsContract.write(Settings.getInstance(), before);
    }

    static CategoryState category(String variant) {
        String base = variant.contains("-") ? variant.substring(0, variant.indexOf('-')) : variant;
        return CategoryState.valueOf(base.toUpperCase(java.util.Locale.ROOT));
    }

    /** A menu (values {@link #pin pinned} first) in the variant's state, laid out for {@code w x h}. */
    public static SettingsMenu menu(SkijaUIBackend backend, String variant, int w, int h) {
        SettingsMenu m = new SettingsMenu(backend);
        StateManager st = m.getStateManager();
        st.setClock(() -> PINNED_MS);
        st.setSelectedCategory(category(variant));
        m.layout(w, h);
        if (variant.contains("-scrolled")) {
            st.getCurrentScrollMath().scrollTo(Float.MAX_VALUE);
        }
        if (variant.contains("-open")) {
            for (CategoryState.SettingType t : st.getSelectedCategory().getSettings()) {
                if (st.widget(t) instanceof MDropdown d) {
                    d.open();
                    break;
                }
            }
        }
        if (variant.contains("-confirm")) {
            st.getUiScaleSlider().setValue(1.5f);
            st.startUiScaleConfirmation(1.0f);
        }
        m.layout(w, h);
        return m;
    }

    static String hover(String variant) {
        int i = variant.indexOf("-hover-");
        return i < 0 ? "" : variant.substring(i + "-hover-".length());
    }
}
