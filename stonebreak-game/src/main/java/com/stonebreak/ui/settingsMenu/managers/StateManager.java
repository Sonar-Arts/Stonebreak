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
        for (CategoryState category : CategoryState.values()) {
            MCategoryButton<CategoryState> button = new MCategoryButton<>(category, category.getDisplayName());
            button.size(SettingsConfig.CATEGORY_BUTTON_WIDTH, SettingsConfig.CATEGORY_BUTTON_HEIGHT);
            categoryButtons.add(button);
        }
    }

    public void initSettingWidgets() {
        applyButton = new MButton("Apply Settings").size(SettingsConfig.BUTTON_WIDTH, SettingsConfig.BUTTON_HEIGHT);
        backButton = new MButton("Back").size(SettingsConfig.BUTTON_WIDTH, SettingsConfig.BUTTON_HEIGHT);

        String[] resolutionStrings = toResolutionStrings(Settings.getAvailableResolutions());
        resolutionButton = new MDropdown("Resolution", resolutionStrings)
                .itemHeight(SettingsConfig.DROPDOWN_ITEM_HEIGHT);
        resolutionButton.size(SettingsConfig.BUTTON_WIDTH, SettingsConfig.BUTTON_HEIGHT);
        resolutionButton.setSelectedIndex(selectedResolutionIndex);

        armModelButton = new MDropdown("Arm Model", SettingsConfig.ARM_MODEL_NAMES)
                .itemHeight(SettingsConfig.DROPDOWN_ITEM_HEIGHT);
        armModelButton.size(SettingsConfig.BUTTON_WIDTH, SettingsConfig.BUTTON_HEIGHT);
        armModelButton.setSelectedIndex(selectedArmModelIndex);

        crosshairStyleButton = new MDropdown("Crosshair", SettingsConfig.CROSSHAIR_STYLE_NAMES)
                .itemHeight(SettingsConfig.DROPDOWN_ITEM_HEIGHT);
        crosshairStyleButton.size(SettingsConfig.BUTTON_WIDTH, SettingsConfig.BUTTON_HEIGHT);
        crosshairStyleButton.setSelectedIndex(selectedCrosshairStyleIndex);

        volumeSlider = new MSlider("Master Volume",
                SettingsConfig.MIN_VOLUME, SettingsConfig.MAX_VOLUME, settings.getMasterVolume())
                .trackHeight(SettingsConfig.SLIDER_HEIGHT);
        volumeSlider.size(SettingsConfig.SLIDER_WIDTH, SettingsConfig.SLIDER_HEIGHT);

        crosshairSizeSlider = new MSlider("Crosshair Size",
                SettingsConfig.MIN_CROSSHAIR_SIZE, SettingsConfig.MAX_CROSSHAIR_SIZE, settings.getCrosshairSize())
                .trackHeight(SettingsConfig.SLIDER_HEIGHT);
        crosshairSizeSlider.size(SettingsConfig.SLIDER_WIDTH, SettingsConfig.SLIDER_HEIGHT);

        leafTransparencyButton = new MButton(leafTransparencyLabel())
                .size(SettingsConfig.BUTTON_WIDTH, SettingsConfig.BUTTON_HEIGHT);
        waterShaderButton = new MButton(waterShaderLabel())
                .size(SettingsConfig.BUTTON_WIDTH, SettingsConfig.BUTTON_HEIGHT);
    }

    /**
     * Attach the action callbacks after construction. Keeps the state manager
     * free of behavioral logic.
     */
    public void setCallbacks(Runnable applyAction, Runnable backAction, Runnable resolutionAction,
                             Runnable armModelAction, Runnable crosshairStyleAction,
                             java.util.function.Consumer<Float> volumeAction,
                             java.util.function.Consumer<Float> crosshairSizeAction,
                             Runnable leafTransparencyAction, Runnable waterShaderAction) {
        applyButton.setOnClick(applyAction);
        backButton.setOnClick(backAction);
        resolutionButton.setOnSelectionChanged(resolutionAction);
        armModelButton.setOnSelectionChanged(armModelAction);
        crosshairStyleButton.setOnSelectionChanged(crosshairStyleAction);
        volumeSlider.setOnChange(volumeAction);
        crosshairSizeSlider.setOnChange(crosshairSizeAction);
        leafTransparencyButton.setOnClick(leafTransparencyAction);
        waterShaderButton.setOnClick(waterShaderAction);

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
    }

    private String leafTransparencyLabel() {
        return "Leaf Transparency: " + (settings.getLeafTransparency() ? "ON" : "OFF");
    }

    private String waterShaderLabel() {
        return "Water Animation: " + (settings.getWaterShaderEnabled() ? "ON" : "OFF");
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
    public List<MCategoryButton<CategoryState>> getCategoryButtons() { return categoryButtons; }

    public MScrollMath getCurrentScrollMath() { return currentScrollMath; }
    public MScrollMath getScrollMathForCategory(CategoryState category) { return scrollMathByCategory.get(category); }
}
