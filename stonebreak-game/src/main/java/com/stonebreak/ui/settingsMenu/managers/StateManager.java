package com.stonebreak.ui.settingsMenu.managers;

import com.stonebreak.config.Settings;
import com.stonebreak.core.GameState;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.components.buttons.Button;
import com.stonebreak.ui.components.buttons.CategoryButton;
import com.stonebreak.ui.components.buttons.DropdownButton;
import com.stonebreak.ui.components.sliders.Slider;
import com.stonebreak.ui.settingsMenu.config.CategoryState;
import com.stonebreak.ui.settingsMenu.config.SettingsConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the UI state and components for the settings menu.
 * Handles component lifecycle, state tracking, and initialization.
 */
public class StateManager {
    
    private final Settings settings;
    private final UIRenderer uiRenderer;
    
    // ===== UI COMPONENTS =====
    private Button applyButton;
    private Button backButton;
    private DropdownButton resolutionButton;
    private DropdownButton armModelButton;
    private DropdownButton crosshairStyleButton;
    private Slider volumeSlider;
    private Slider crosshairSizeSlider;
    private Button leafTransparencyButton;
    private Button waterShaderButton;
    
    // ===== CATEGORY COMPONENTS =====
    private List<CategoryButton> categoryButtons;
    
    // ===== UI STATE =====
    private CategoryState selectedCategory = CategoryState.GENERAL;
    private int selectedSettingInCategory = 0; // Index within the category's settings
    private GameState previousState = GameState.MAIN_MENU;
    
    // ===== DROPDOWN STATE =====
    private int selectedResolutionIndex = 0;
    private int selectedArmModelIndex = 0;
    private int selectedCrosshairStyleIndex = 0;
    
    // ===== SCROLL STATE =====
    private Map<CategoryState, ScrollManager> scrollManagers;
    private ScrollManager currentScrollManager;
    
    public StateManager(Settings settings, UIRenderer uiRenderer) {
        this.settings = settings;
        this.uiRenderer = uiRenderer;
        initializeState();
    }
    
    /**
     * Initializes the UI state and components.
     */
    private void initializeState() {
        initializeSettingsState();
        initializeScrollManagers();
        initializeCategoryButtons();
        initializeSettingButtons();
    }
    
    /**
     * Initializes the UI state based on current settings values.
     */
    private void initializeSettingsState() {
        this.selectedResolutionIndex = settings.getCurrentResolutionIndex();
        this.selectedArmModelIndex = SettingsConfig.findArmModelIndex(settings.getArmModelType());
        this.selectedCrosshairStyleIndex = SettingsConfig.findCrosshairStyleIndex(settings.getCrosshairStyle());
    }
    
    /**
     * Initializes scroll managers for each category.
     */
    private void initializeScrollManagers() {
        scrollManagers = new HashMap<>();
        
        // Create a scroll manager for each category
        for (CategoryState category : CategoryState.values()) {
            scrollManagers.put(category, new ScrollManager());
        }
        
        // Set current scroll manager to selected category
        currentScrollManager = scrollManagers.get(selectedCategory);
    }
    
    /**
     * Initializes the category buttons for left-side navigation.
     */
    private void initializeCategoryButtons() {
        categoryButtons = new ArrayList<>();
        
        for (CategoryState category : CategoryState.values()) {
            CategoryButton button = new CategoryButton(
                category, 
                0, 0, // Position will be set during rendering
                SettingsConfig.CATEGORY_BUTTON_WIDTH, 
                SettingsConfig.CATEGORY_BUTTON_HEIGHT,
                null // Action will be set via callbacks
            );
            categoryButtons.add(button);
        }
    }
    
    /**
     * Initializes the setting button components with their properties and actions.
     */
    public void initializeSettingButtons() {
        // Create action handlers that will be set by the main SettingsMenu
        applyButton = new Button("Apply Settings", 0, 0, SettingsConfig.BUTTON_WIDTH, SettingsConfig.BUTTON_HEIGHT, null);
        backButton = new Button("Back", 0, 0, SettingsConfig.BUTTON_WIDTH, SettingsConfig.BUTTON_HEIGHT, null);
        
        // Initialize dropdown buttons
        int[][] resolutions = Settings.getAvailableResolutions();
        String[] resolutionStrings = createResolutionStrings(resolutions);
        resolutionButton = new DropdownButton("Resolution", 0, 0, SettingsConfig.BUTTON_WIDTH, SettingsConfig.BUTTON_HEIGHT, 
                                            resolutionStrings, SettingsConfig.DROPDOWN_ITEM_HEIGHT, null);
        resolutionButton.setSelectedItemIndex(selectedResolutionIndex);
        
        armModelButton = new DropdownButton("Arm Model", 0, 0, SettingsConfig.BUTTON_WIDTH, SettingsConfig.BUTTON_HEIGHT, 
                                          SettingsConfig.ARM_MODEL_NAMES, SettingsConfig.DROPDOWN_ITEM_HEIGHT, null);
        armModelButton.setSelectedItemIndex(selectedArmModelIndex);
        
        crosshairStyleButton = new DropdownButton("Crosshair", 0, 0, SettingsConfig.BUTTON_WIDTH, SettingsConfig.BUTTON_HEIGHT, 
                                                 SettingsConfig.CROSSHAIR_STYLE_NAMES, SettingsConfig.DROPDOWN_ITEM_HEIGHT, null);
        crosshairStyleButton.setSelectedItemIndex(selectedCrosshairStyleIndex);
        
        // Initialize sliders
        volumeSlider = new Slider("Master Volume", 0, 0, SettingsConfig.SLIDER_WIDTH, SettingsConfig.SLIDER_HEIGHT, 
                                SettingsConfig.MIN_VOLUME, SettingsConfig.MAX_VOLUME, settings.getMasterVolume(), null);
        
        crosshairSizeSlider = new Slider("Crosshair Size", 0, 0, SettingsConfig.SLIDER_WIDTH, SettingsConfig.SLIDER_HEIGHT,
                                       SettingsConfig.MIN_CROSSHAIR_SIZE, SettingsConfig.MAX_CROSSHAIR_SIZE, settings.getCrosshairSize(), null);

        // Initialize leaf transparency button
        String leafTransparencyText = "Leaf Transparency: " + (settings.getLeafTransparency() ? "ON" : "OFF");
        leafTransparencyButton = new Button(leafTransparencyText, 0, 0, SettingsConfig.BUTTON_WIDTH, SettingsConfig.BUTTON_HEIGHT, null);

        // Initialize water shader button
        String waterShaderText = "Water Animation: " + (settings.getWaterShaderEnabled() ? "ON" : "OFF");
        waterShaderButton = new Button(waterShaderText, 0, 0, SettingsConfig.BUTTON_WIDTH, SettingsConfig.BUTTON_HEIGHT, null);
    }
    
    /**
     * Sets the callback actions for components after ActionHandler is created.
     */
    public void setCallbacks(Runnable applyAction, Runnable backAction, Runnable resolutionAction,
                           Runnable armModelAction, Runnable crosshairStyleAction,
                           java.util.function.Consumer<Float> volumeAction, java.util.function.Consumer<Float> crosshairSizeAction,
                           Runnable leafTransparencyAction, Runnable waterShaderAction) {
        applyButton.setOnClickAction(applyAction);
        backButton.setOnClickAction(backAction);
        resolutionButton.setOnSelectionChangeAction(resolutionAction);
        armModelButton.setOnSelectionChangeAction(armModelAction);
        crosshairStyleButton.setOnSelectionChangeAction(crosshairStyleAction);
        volumeSlider.setOnValueChangeAction(volumeAction);
        crosshairSizeSlider.setOnValueChangeAction(crosshairSizeAction);
        leafTransparencyButton.setOnClickAction(leafTransparencyAction);
        waterShaderButton.setOnClickAction(waterShaderAction);
        
        // Set category button callbacks - each will set the selected category
        for (CategoryButton button : categoryButtons) {
            final CategoryState category = button.getCategory();
            button.setOnClickAction(() -> {
                setSelectedCategory(category); // Use the setter method to trigger dropdown closing
            });
        }
    }
    
    /**
     * Creates display strings for resolution options.
     */
    private String[] createResolutionStrings(int[][] resolutions) {
        String[] resolutionStrings = new String[resolutions.length];
        for (int i = 0; i < resolutions.length; i++) {
            resolutionStrings[i] = resolutions[i][0] + "x" + resolutions[i][1];
        }
        return resolutionStrings;
    }
    
    /**
     * Updates the selection state of all UI components based on the current selected category and setting.
     */
    public void updateButtonSelectionStates() {
        // Update category button selection states
        for (CategoryButton button : categoryButtons) {
            button.setSelected(button.getCategory() == selectedCategory);
        }
        
        // Update setting selection states based on current category
        CategoryState.SettingType[] settings = selectedCategory.getSettings();
        CategoryState.SettingType currentSetting;
        
        if (selectedSettingInCategory < settings.length) {
            // Regular setting within the category
            currentSetting = settings[selectedSettingInCategory];
        } else if (selectedSettingInCategory == settings.length) {
            // Apply button
            currentSetting = CategoryState.SettingType.APPLY;
        } else if (selectedSettingInCategory == settings.length + 1) {
            // Back button
            currentSetting = CategoryState.SettingType.BACK;
        } else {
            // Out of bounds - default to apply
            currentSetting = CategoryState.SettingType.APPLY;
        }
            
        // Keep all setting button selections OFF - they should only show hover state, not persistent selection
        // Only category buttons maintain persistent selection state
        resolutionButton.setSelected(false);
        volumeSlider.setSelected(false);
        armModelButton.setSelected(false);
        crosshairStyleButton.setSelected(false);
        crosshairSizeSlider.setSelected(false);
        applyButton.setSelected(false);
        backButton.setSelected(false);
        
        // Note: selectedSettingInCategory is kept for internal state tracking (keyboard navigation, etc.)
        // but it no longer affects visual selection state for setting buttons.
        // Setting buttons should only show blue overlay when hovered, not when selected.
    }
    
    // ===== GETTERS AND SETTERS =====
    
    public CategoryState getSelectedCategory() { return selectedCategory; }
    public void setSelectedCategory(CategoryState selectedCategory) {
        this.selectedCategory = selectedCategory;
        this.selectedSettingInCategory = 0; // Reset to first setting in category

        // Close all open dropdowns when switching categories
        closeAllDropdowns();

        // Switch to the scroll manager for the new category
        currentScrollManager = scrollManagers.get(selectedCategory);
        if (currentScrollManager != null) {
            currentScrollManager.scrollTo(0); // Reset scroll position when switching categories
        }
    }
    
    public int getSelectedSettingInCategory() { return selectedSettingInCategory; }
    public void setSelectedSettingInCategory(int selectedSettingInCategory) { 
        this.selectedSettingInCategory = selectedSettingInCategory; 
    }
    
    public GameState getPreviousState() { return previousState; }
    public void setPreviousState(GameState previousState) { this.previousState = previousState; }
    
    // Legacy method for backward compatibility
    @Deprecated
    public int getSelectedButton() { 
        return selectedSettingInCategory; 
    }
    
    @Deprecated
    public void setSelectedButton(int selectedButton) { 
        this.selectedSettingInCategory = selectedButton; 
    }
    
    public int getSelectedResolutionIndex() { return selectedResolutionIndex; }
    public void setSelectedResolutionIndex(int selectedResolutionIndex) { this.selectedResolutionIndex = selectedResolutionIndex; }
    
    public int getSelectedArmModelIndex() { return selectedArmModelIndex; }
    public void setSelectedArmModelIndex(int selectedArmModelIndex) { this.selectedArmModelIndex = selectedArmModelIndex; }
    
    public int getSelectedCrosshairStyleIndex() { return selectedCrosshairStyleIndex; }
    public void setSelectedCrosshairStyleIndex(int selectedCrosshairStyleIndex) { this.selectedCrosshairStyleIndex = selectedCrosshairStyleIndex; }
    
    // ===== COMPONENT GETTERS =====
    
    public Button getApplyButton() { return applyButton; }
    public Button getBackButton() { return backButton; }
    public DropdownButton getResolutionButton() { return resolutionButton; }
    public DropdownButton getArmModelButton() { return armModelButton; }
    public DropdownButton getCrosshairStyleButton() { return crosshairStyleButton; }
    public Slider getVolumeSlider() { return volumeSlider; }
    public Slider getCrosshairSizeSlider() { return crosshairSizeSlider; }
    public Button getLeafTransparencyButton() { return leafTransparencyButton; }
    public Button getWaterShaderButton() { return waterShaderButton; }
    public List<CategoryButton> getCategoryButtons() { return categoryButtons; }
    
    // ===== SCROLL MANAGER GETTERS =====
    
    public ScrollManager getCurrentScrollManager() { return currentScrollManager; }
    public ScrollManager getScrollManagerForCategory(CategoryState category) { 
        return scrollManagers.get(category); 
    }
    public UIRenderer getUIRenderer() { return uiRenderer; }
    
    // ===== DROPDOWN MANAGEMENT =====

    /**
     * Closes all open dropdown menus. This is called when switching categories
     * to ensure no dropdown remains open from the previous category.
     */
    private void closeAllDropdowns() {
        if (resolutionButton != null) {
            resolutionButton.closeDropdown();
        }
        if (armModelButton != null) {
            armModelButton.closeDropdown();
        }
        if (crosshairStyleButton != null) {
            crosshairStyleButton.closeDropdown();
        }
    }

    // ===== NAVIGATION METHODS =====

    /**
     * Navigates to the next category in the list.
     */
    public void navigateToNextCategory() {
        int currentIndex = selectedCategory.getIndex();
        int nextIndex = (currentIndex + 1) % CategoryState.values().length;
        selectedCategory = CategoryState.fromIndex(nextIndex);
        selectedSettingInCategory = 0;
        closeAllDropdowns();
    }
    
    /**
     * Navigates to the previous category in the list.
     */
    public void navigateToPreviousCategory() {
        int currentIndex = selectedCategory.getIndex();
        int prevIndex = (currentIndex - 1 + CategoryState.values().length) % CategoryState.values().length;
        selectedCategory = CategoryState.fromIndex(prevIndex);
        selectedSettingInCategory = 0;
        closeAllDropdowns();
    }
    
    /**
     * Navigates to the next setting within the current category.
     */
    public void navigateToNextSettingInCategory() {
        CategoryState.SettingType[] settings = selectedCategory.getSettings();
        if (settings.length > 0) {
            selectedSettingInCategory = (selectedSettingInCategory + 1) % (settings.length + 2); // +2 for Apply/Back
        }
    }
    
    /**
     * Navigates to the previous setting within the current category.
     */
    public void navigateToPreviousSettingInCategory() {
        CategoryState.SettingType[] settings = selectedCategory.getSettings();
        if (settings.length > 0) {
            int maxIndex = settings.length + 1; // +2 for Apply/Back, -1 for 0-based
            selectedSettingInCategory = (selectedSettingInCategory - 1 + maxIndex + 2) % (settings.length + 2);
        }
    }
}