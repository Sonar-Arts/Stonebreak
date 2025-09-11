package com.stonebreak.ui.settingsMenu.managers;

import com.stonebreak.config.Settings;
import com.stonebreak.core.GameState;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.ui.components.buttons.Button;
import com.stonebreak.ui.components.buttons.DropdownButton;
import com.stonebreak.ui.components.sliders.Slider;
import com.stonebreak.ui.settingsMenu.config.ButtonSelection;
import com.stonebreak.ui.settingsMenu.config.SettingsConfig;

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
    
    // ===== UI STATE =====
    private int selectedButton = ButtonSelection.RESOLUTION.getIndex();
    private GameState previousState = GameState.MAIN_MENU;
    
    // ===== DROPDOWN STATE =====
    private int selectedResolutionIndex = 0;
    private int selectedArmModelIndex = 0;
    private int selectedCrosshairStyleIndex = 0;
    
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
        initializeButtons();
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
     * Initializes the button components with their properties and actions.
     */
    public void initializeButtons() {
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
    }
    
    /**
     * Sets the callback actions for components after ActionHandler is created.
     */
    public void setCallbacks(Runnable applyAction, Runnable backAction, Runnable resolutionAction, 
                           Runnable armModelAction, Runnable crosshairStyleAction, 
                           java.util.function.Consumer<Float> volumeAction, java.util.function.Consumer<Float> crosshairSizeAction) {
        applyButton.setOnClickAction(applyAction);
        backButton.setOnClickAction(backAction);
        resolutionButton.setOnSelectionChangeAction(resolutionAction);
        armModelButton.setOnSelectionChangeAction(armModelAction);
        crosshairStyleButton.setOnSelectionChangeAction(crosshairStyleAction);
        volumeSlider.setOnValueChangeAction(volumeAction);
        crosshairSizeSlider.setOnValueChangeAction(crosshairSizeAction);
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
     * Updates the selection state of all UI components based on the current selected button.
     */
    public void updateButtonSelectionStates() {
        resolutionButton.setSelected(selectedButton == ButtonSelection.RESOLUTION.getIndex());
        volumeSlider.setSelected(selectedButton == ButtonSelection.VOLUME.getIndex());
        armModelButton.setSelected(selectedButton == ButtonSelection.ARM_MODEL.getIndex());
        crosshairStyleButton.setSelected(selectedButton == ButtonSelection.CROSSHAIR_STYLE.getIndex());
        crosshairSizeSlider.setSelected(selectedButton == ButtonSelection.CROSSHAIR_SIZE.getIndex());
        applyButton.setSelected(selectedButton == ButtonSelection.APPLY.getIndex());
        backButton.setSelected(selectedButton == ButtonSelection.BACK.getIndex());
    }
    
    // ===== GETTERS AND SETTERS =====
    
    public int getSelectedButton() { return selectedButton; }
    public void setSelectedButton(int selectedButton) { this.selectedButton = selectedButton; }
    
    public GameState getPreviousState() { return previousState; }
    public void setPreviousState(GameState previousState) { this.previousState = previousState; }
    
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
}