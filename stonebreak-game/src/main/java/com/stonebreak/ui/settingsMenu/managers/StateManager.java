package com.stonebreak.ui.settingsMenu.managers;

import com.stonebreak.config.Settings;
import com.stonebreak.core.GameState;
import com.stonebreak.rendering.UI.masonryUI.MButton;
import com.stonebreak.rendering.UI.masonryUI.MCategoryButton;
import com.stonebreak.rendering.UI.masonryUI.MDropdown;
import com.stonebreak.rendering.UI.masonryUI.MScrollMath;
import com.stonebreak.rendering.UI.masonryUI.MSlider;
import com.stonebreak.ui.settingsMenu.config.CategoryState;
import com.stonebreak.ui.settingsMenu.config.SettingsConfig;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the MasonryUI widgets and navigation state for the settings menu.
 * Pure state — no rendering, no GL, no NanoVG. Handlers and renderers read
 * and mutate these fields; the {@link com.stonebreak.ui.settingsMenu.renderers.SkijaSettingsRenderer}
 * positions and draws them.
 */
public final class StateManager {

    private final Settings settings;

    // ─────────────────────────────────────────────── Widgets
    private MButton applyButton;
    private MButton backButton;
    private MDropdown resolutionButton;
    private MDropdown armModelButton;
    private MDropdown crosshairStyleButton;
    private MSlider volumeSlider;
    private MSlider crosshairSizeSlider;
    private MButton leafTransparencyButton;
    private MButton waterShaderButton;
    private MButton cloudsButton;
    private MSlider renderDistanceSlider;
    private MSlider lodDistanceSlider;
    private MButton lodEnabledButton;
    private MButton vsyncButton;
    private MSlider uiScaleSlider;

    private List<MCategoryButton<CategoryState>> categoryButtons;

    // ─────────────────────────────────────────────── Navigation state
    private CategoryState selectedCategory = CategoryState.GENERAL;
    private int selectedSettingInCategory = 0;
    private GameState previousState = GameState.MAIN_MENU;

    private int selectedResolutionIndex = 0;
    private int selectedArmModelIndex = 0;
    private int selectedCrosshairStyleIndex = 0;

    // ─────────────────────────────────────────────── Scroll state
    private final Map<CategoryState, MScrollMath> scrollMathByCategory = new EnumMap<>(CategoryState.class);
    private MScrollMath currentScrollMath;

    public StateManager(Settings settings) {
        this.settings = settings;
        initSettingsState();
        initScrollMaths();
        initCategoryButtons();
        initSettingWidgets();
    }

    // ─────────────────────────────────────────────── Initialization

    private void initSettingsState() {
        selectedResolutionIndex = settings.getCurrentResolutionIndex();
        selectedArmModelIndex = SettingsConfig.findArmModelIndex(settings.getArmModelType());
        selectedCrosshairStyleIndex = SettingsConfig.findCrosshairStyleIndex(settings.getCrosshairStyle());
    }

    private void initScrollMaths() {
        for (CategoryState category : CategoryState.values()) {
            scrollMathByCategory.put(category, newScrollMath());
        }
        currentScrollMath = scrollMathByCategory.get(selectedCategory);
    }

    private static MScrollMath newScrollMath() {
        return new MScrollMath()
                .wheelSensitivity(SettingsConfig.SCROLL_WHEEL_SENSITIVITY)
                .velocityFactor(SettingsConfig.SCROLL_VELOCITY_FACTOR)
                .velocityDecay(SettingsConfig.SCROLL_VELOCITY_DECAY)
                .lerpSpeed(SettingsConfig.SCROLL_LERP_SPEED)
                .padding(SettingsConfig.SCROLL_CONTENT_PADDING);
    }

    private void initCategoryButtons() {
        categoryButtons = new ArrayList<>();
        float cbw = SettingsConfig.getScaledCategoryButtonWidth();
        float cbh = SettingsConfig.getScaledCategoryButtonHeight();
        for (CategoryState category : CategoryState.values()) {
            MCategoryButton<CategoryState> button = new MCategoryButton<>(category, category.getDisplayName());
            button.size(cbw, cbh);
            categoryButtons.add(button);
        }
    }

    public void initSettingWidgets() {
        float bw  = SettingsConfig.getScaledButtonWidth();
        float bh  = SettingsConfig.getScaledButtonHeight();
        float sw  = SettingsConfig.getScaledSliderWidth();
        float sh  = SettingsConfig.getScaledSliderHeight();
        float dih = SettingsConfig.getScaledDropdownItemHeight();

        applyButton = new MButton("Apply Settings").size(bw, bh);
        backButton = new MButton("Back").size(bw, bh);

        String[] resolutionStrings = toResolutionStrings(Settings.getAvailableResolutions());
        resolutionButton = new MDropdown("Resolution", resolutionStrings).itemHeight(dih);
        resolutionButton.size(bw, bh);
        resolutionButton.setSelectedIndex(selectedResolutionIndex);

        armModelButton = new MDropdown("Arm Model", SettingsConfig.ARM_MODEL_NAMES).itemHeight(dih);
        armModelButton.size(bw, bh);
        armModelButton.setSelectedIndex(selectedArmModelIndex);

        crosshairStyleButton = new MDropdown("Crosshair", SettingsConfig.CROSSHAIR_STYLE_NAMES).itemHeight(dih);
        crosshairStyleButton.size(bw, bh);
        crosshairStyleButton.setSelectedIndex(selectedCrosshairStyleIndex);

        volumeSlider = new MSlider("Master Volume",
                SettingsConfig.MIN_VOLUME, SettingsConfig.MAX_VOLUME, settings.getMasterVolume())
                .trackHeight(sh);
        volumeSlider.size(sw, sh);

        crosshairSizeSlider = new MSlider("Crosshair Size",
                SettingsConfig.MIN_CROSSHAIR_SIZE, SettingsConfig.MAX_CROSSHAIR_SIZE, settings.getCrosshairSize())
                .trackHeight(sh);
        crosshairSizeSlider.size(sw, sh);

        leafTransparencyButton = new MButton(leafTransparencyLabel()).size(bw, bh);
        waterShaderButton = new MButton(waterShaderLabel()).size(bw, bh);
        cloudsButton = new MButton(cloudsLabel()).size(bw, bh);

        renderDistanceSlider = new MSlider(renderDistanceLabel(),
                SettingsConfig.MIN_RENDER_DISTANCE, SettingsConfig.MAX_RENDER_DISTANCE,
                settings.getRenderDistance())
                .trackHeight(sh);
        renderDistanceSlider.size(sw, sh);

        lodDistanceSlider = new MSlider(lodDistanceLabel(),
                SettingsConfig.MIN_LOD_DISTANCE, SettingsConfig.MAX_LOD_DISTANCE,
                settings.getLodDistance())
                .trackHeight(sh);
        lodDistanceSlider.size(sw, sh);

        lodEnabledButton = new MButton(lodEnabledLabel()).size(bw, bh);
        vsyncButton = new MButton(vsyncLabel()).size(bw, bh);

        uiScaleSlider = new MSlider("UI Scale",
                SettingsConfig.MIN_UI_SCALE, SettingsConfig.MAX_UI_SCALE, settings.getUiScale())
                .trackHeight(sh);
        uiScaleSlider.size(sw, sh);
    }

    /**
     * Re-sizes all existing widgets to match the current uiScale.
     * Call this whenever the scale setting changes.
     */
    public void resizeWidgets() {
        float bw  = SettingsConfig.getScaledButtonWidth();
        float bh  = SettingsConfig.getScaledButtonHeight();
        float sw  = SettingsConfig.getScaledSliderWidth();
        float sh  = SettingsConfig.getScaledSliderHeight();
        float dih = SettingsConfig.getScaledDropdownItemHeight();
        float cbw = SettingsConfig.getScaledCategoryButtonWidth();
        float cbh = SettingsConfig.getScaledCategoryButtonHeight();

        applyButton.size(bw, bh);
        backButton.size(bw, bh);
        resolutionButton.size(bw, bh).itemHeight(dih);
        armModelButton.size(bw, bh).itemHeight(dih);
        crosshairStyleButton.size(bw, bh).itemHeight(dih);
        volumeSlider.size(sw, sh);
        crosshairSizeSlider.size(sw, sh);
        leafTransparencyButton.size(bw, bh);
        waterShaderButton.size(bw, bh);
        cloudsButton.size(bw, bh);
        renderDistanceSlider.size(sw, sh);
        lodDistanceSlider.size(sw, sh);
        lodEnabledButton.size(bw, bh);
        vsyncButton.size(bw, bh);
        uiScaleSlider.size(sw, sh);
        for (MCategoryButton<CategoryState> button : categoryButtons) {
            button.size(cbw, cbh);
        }
    }

    /**
     * Attach the action callbacks after construction. Keeps the state manager
     * free of behavioral logic.
     */
    public void setCallbacks(Runnable applyAction, Runnable backAction, Runnable resolutionAction,
                             Runnable armModelAction, Runnable crosshairStyleAction,
                             java.util.function.Consumer<Float> volumeAction,
                             java.util.function.Consumer<Float> crosshairSizeAction,
                             Runnable leafTransparencyAction, Runnable waterShaderAction,
                             Runnable cloudsAction,
                             java.util.function.Consumer<Float> renderDistanceAction,
                             java.util.function.Consumer<Float> lodDistanceAction,
                             Runnable lodEnabledAction,
                             Runnable vsyncAction,
                             java.util.function.Consumer<Float> uiScaleAction) {
        applyButton.setOnClick(applyAction);
        backButton.setOnClick(backAction);
        resolutionButton.setOnSelectionChanged(resolutionAction);
        armModelButton.setOnSelectionChanged(armModelAction);
        crosshairStyleButton.setOnSelectionChanged(crosshairStyleAction);
        volumeSlider.setOnChange(volumeAction);
        crosshairSizeSlider.setOnChange(crosshairSizeAction);
        leafTransparencyButton.setOnClick(leafTransparencyAction);
        waterShaderButton.setOnClick(waterShaderAction);
        cloudsButton.setOnClick(cloudsAction);
        renderDistanceSlider.setOnChange(renderDistanceAction);
        lodDistanceSlider.setOnChange(lodDistanceAction);
        lodEnabledButton.setOnClick(lodEnabledAction);
        vsyncButton.setOnClick(vsyncAction);
        uiScaleSlider.setOnChange(uiScaleAction);

        for (MCategoryButton<CategoryState> button : categoryButtons) {
            CategoryState category = button.tag();
            button.setOnClick(() -> setSelectedCategory(category));
        }
    }

    private static String[] toResolutionStrings(int[][] resolutions) {
        String[] out = new String[resolutions.length];
        for (int i = 0; i < resolutions.length; i++) {
            out[i] = resolutions[i][0] + "x" + resolutions[i][1];
        }
        return out;
    }

    // ─────────────────────────────────────────────── Category state

    public void updateButtonSelectionStates() {
        for (MCategoryButton<CategoryState> button : categoryButtons) {
            button.setSelected(button.tag() == selectedCategory);
        }
        // Setting buttons are hover-only; no persistent selection on them.
        resolutionButton.setSelected(false);
        armModelButton.setSelected(false);
        crosshairStyleButton.setSelected(false);
        volumeSlider.setSelected(false);
        crosshairSizeSlider.setSelected(false);
        applyButton.setSelected(false);
        backButton.setSelected(false);
        leafTransparencyButton.setSelected(false);
        waterShaderButton.setSelected(false);
        cloudsButton.setSelected(false);
        renderDistanceSlider.setSelected(false);
        lodDistanceSlider.setSelected(false);
        lodEnabledButton.setSelected(false);
        vsyncButton.setSelected(false);
        uiScaleSlider.setSelected(false);
    }

    public void closeAllDropdowns() {
        if (resolutionButton != null) resolutionButton.close();
        if (armModelButton != null) armModelButton.close();
        if (crosshairStyleButton != null) crosshairStyleButton.close();
    }

    public void setSelectedCategory(CategoryState category) {
        this.selectedCategory = category;
        this.selectedSettingInCategory = 0;
        closeAllDropdowns();
        currentScrollMath = scrollMathByCategory.get(category);
        if (currentScrollMath != null) currentScrollMath.scrollTo(0);
    }

    public void navigateToNextCategory() {
        int next = (selectedCategory.getIndex() + 1) % CategoryState.values().length;
        setSelectedCategory(CategoryState.fromIndex(next));
    }

    public void navigateToPreviousCategory() {
        int length = CategoryState.values().length;
        int prev = (selectedCategory.getIndex() - 1 + length) % length;
        setSelectedCategory(CategoryState.fromIndex(prev));
    }

    public void navigateToNextSettingInCategory() {
        CategoryState.SettingType[] s = selectedCategory.getSettings();
        if (s.length > 0) selectedSettingInCategory = (selectedSettingInCategory + 1) % (s.length + 2);
    }

    public void navigateToPreviousSettingInCategory() {
        CategoryState.SettingType[] s = selectedCategory.getSettings();
        if (s.length > 0) {
            int mod = s.length + 2;
            selectedSettingInCategory = (selectedSettingInCategory - 1 + mod) % mod;
        }
    }

    // ─────────────────────────────────────────────── Label refresh

    public void refreshLabels() {
        resolutionButton.setText("Resolution: " + settings.getCurrentResolutionString());
        armModelButton.setText("Arm Model: " + SettingsConfig.ARM_MODEL_NAMES[selectedArmModelIndex]);
        crosshairStyleButton.setText("Crosshair: " + SettingsConfig.CROSSHAIR_STYLE_NAMES[selectedCrosshairStyleIndex]);
        leafTransparencyButton.setText(leafTransparencyLabel());
        waterShaderButton.setText(waterShaderLabel());
        cloudsButton.setText(cloudsLabel());
        renderDistanceSlider.setLabel(renderDistanceLabel());
        lodDistanceSlider.setLabel(lodDistanceLabel());
        lodEnabledButton.setText(lodEnabledLabel());
        vsyncButton.setText(vsyncLabel());
        uiScaleSlider.setLabel(uiScaleLabel());
    }

    private String leafTransparencyLabel() {
        return "Leaf Transparency: " + (settings.getLeafTransparency() ? "ON" : "OFF");
    }

    private String waterShaderLabel() {
        return "Water Animation: " + (settings.getWaterShaderEnabled() ? "ON" : "OFF");
    }

    private String cloudsLabel() {
        return "Clouds: " + (settings.getCloudsEnabled() ? "ON" : "OFF");
    }

    private String renderDistanceLabel() {
        return "Render Distance: " + settings.getRenderDistance() + " chunks";
    }

    private String lodDistanceLabel() {
        return "LOD Distance: " + settings.getLodDistance() + " chunks";
    }

    private String lodEnabledLabel() {
        return "Distant Terrain LOD: " + (settings.getLodEnabled() ? "ON" : "OFF");
    }

    private String vsyncLabel() {
        return "VSync: " + (settings.isVsyncEnabled() ? "ON" : "OFF");
    }

    private String uiScaleLabel() {
        return "UI Scale: " + String.format("%.1f", settings.getUiScale()) + "x";
    }

    // ─────────────────────────────────────────────── Getters / setters

    public CategoryState getSelectedCategory() { return selectedCategory; }
    public int getSelectedSettingInCategory() { return selectedSettingInCategory; }
    public void setSelectedSettingInCategory(int i) { this.selectedSettingInCategory = i; }

    public GameState getPreviousState() { return previousState; }
    public void setPreviousState(GameState state) { this.previousState = state; }

    public int getSelectedButton() { return selectedSettingInCategory; }

    public int getSelectedResolutionIndex() { return selectedResolutionIndex; }
    public void setSelectedResolutionIndex(int i) { this.selectedResolutionIndex = i; }

    public int getSelectedArmModelIndex() { return selectedArmModelIndex; }
    public void setSelectedArmModelIndex(int i) { this.selectedArmModelIndex = i; }

    public int getSelectedCrosshairStyleIndex() { return selectedCrosshairStyleIndex; }
    public void setSelectedCrosshairStyleIndex(int i) { this.selectedCrosshairStyleIndex = i; }

    public MButton getApplyButton() { return applyButton; }
    public MButton getBackButton() { return backButton; }
    public MDropdown getResolutionButton() { return resolutionButton; }
    public MDropdown getArmModelButton() { return armModelButton; }
    public MDropdown getCrosshairStyleButton() { return crosshairStyleButton; }
    public MSlider getVolumeSlider() { return volumeSlider; }
    public MSlider getCrosshairSizeSlider() { return crosshairSizeSlider; }
    public MButton getLeafTransparencyButton() { return leafTransparencyButton; }
    public MButton getWaterShaderButton() { return waterShaderButton; }
    public MButton getCloudsButton() { return cloudsButton; }
    public MSlider getRenderDistanceSlider() { return renderDistanceSlider; }
    public MSlider getLodDistanceSlider() { return lodDistanceSlider; }
    public MButton getLodEnabledButton() { return lodEnabledButton; }
    public MButton getVsyncButton() { return vsyncButton; }
    public MSlider getUiScaleSlider() { return uiScaleSlider; }
    public List<MCategoryButton<CategoryState>> getCategoryButtons() { return categoryButtons; }

    public MScrollMath getCurrentScrollMath() { return currentScrollMath; }
    public MScrollMath getScrollMathForCategory(CategoryState category) { return scrollMathByCategory.get(category); }
}
