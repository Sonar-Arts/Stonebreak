package com.openmason.ui;

import com.openmason.ui.themes.ThemeManager;
import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Dear ImGui implementation of the Advanced Preferences Dialog.
 * Provides comprehensive customization options with tabbed interface.
 */
public class AdvancedPreferencesImGui {
    
    private static final Logger logger = LoggerFactory.getLogger(AdvancedPreferencesImGui.class);
    
    private final AdvancedUIManager uiManager;
    private final ThemeManager themeManager;
    
    // Modal state
    private final ImBoolean showPreferences = new ImBoolean(false);
    private boolean hasUnsavedChanges = false;
    private int currentTab = 0;
    
    // General Settings
    private final ImBoolean autoSaveLayouts = new ImBoolean(true);
    private final ImInt startupBehavior = new ImInt(2); // "Show empty workspace"
    private final ImInt recentFileCount = new ImInt(10);
    private final ImFloat memoryUsage = new ImFloat(2048.0f);
    
    // Appearance Settings
    private final ImInt currentTheme = new ImInt(0);
    private final ImInt uiDensity = new ImInt(1); // Standard
    private final ImFloat interfaceFontSize = new ImFloat(12.0f);
    
    // Shortcut Settings
    private final ImInt shortcutPreset = new ImInt(0);
    
    // Interface Settings
    private final ImBoolean autoHidePanels = new ImBoolean(false);
    private final ImBoolean snapToEdges = new ImBoolean(true);
    private final ImBoolean rememberLayout = new ImBoolean(true);
    private final ImBoolean showToolbarText = new ImBoolean(false);
    private final ImBoolean largeIcons = new ImBoolean(false);
    private final ImBoolean showMemoryUsage = new ImBoolean(true);
    private final ImBoolean showFPS = new ImBoolean(true);
    private final ImBoolean showProgress = new ImBoolean(true);
    
    // Help Settings
    private final ImBoolean showTooltips = new ImBoolean(true);
    private final ImBoolean autoShowHelp = new ImBoolean(true);
    private final ImBoolean enableTutorials = new ImBoolean(true);
    private final ImBoolean rememberTutorialProgress = new ImBoolean(true);
    private final ImBoolean showTutorialTips = new ImBoolean(false);
    
    // Advanced Settings
    private final ImBoolean enableVerboseLogging = new ImBoolean(false);
    private final ImBoolean showDebugInfo = new ImBoolean(false);
    private final ImString systemInfo = new ImString(2048);
    
    // UI Constants
    private final String[] startupOptions = {
        "Show getting started tutorial",
        "Open last used project", 
        "Show empty workspace",
        "Open specific project..."
    };
    
    private final String[] themeOptions = {
        "Dark Theme",
        "Light Theme", 
        "Custom Theme"
    };
    
    private final String[] densityOptions = {
        "Compact",
        "Standard",
        "Comfortable"
    };
    
    public AdvancedPreferencesImGui() {
        this.uiManager = AdvancedUIManager.getInstance();
        this.themeManager = ThemeManager.getInstance();
        
        // Initialize current settings
        loadCurrentSettings();
    }
    
    private void loadCurrentSettings() {
        try {
            // Load current theme
            ThemeManager.ImGuiTheme current = themeManager.getCurrentTheme();
            for (int i = 0; i < themeOptions.length; i++) {
                if (current != null && themeOptions[i].toLowerCase().contains(current.getName().toLowerCase())) {
                    currentTheme.set(i);
                    break;
                }
            }
            
            // Load current density
            ThemeManager.UIDensity density = themeManager.getCurrentDensity();
            uiDensity.set(density.ordinal());
            
            // Load current shortcut preset (stub implementation)
            shortcutPreset.set(0); // Default preset
            
            // Load system information
            systemInfo.set(uiManager.getSystemStatistics());
            
        } catch (Exception e) {
            logger.error("Error loading current settings", e);
        }
    }
    
    /**
     * Show the preferences modal dialog
     */
    public void show() {
        showPreferences.set(true);
        loadCurrentSettings();
    }
    
    /**
     * Render the preferences dialog using Dear ImGui
     */
    public void render() {
        if (!showPreferences.get()) {
            return;
        }
        
        // Center the modal
        ImGui.setNextWindowPos(ImGui.getMainViewport().getCenterX(), ImGui.getMainViewport().getCenterY(), 
                               ImGuiCond.Appearing, 0.5f, 0.5f);
        ImGui.setNextWindowSize(900, 700, ImGuiCond.Appearing);
        
        int windowFlags = ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoCollapse;
        
        if (ImGui.beginPopupModal("OpenMason Preferences", showPreferences, windowFlags)) {
            renderHeader();
            ImGui.separator();
            
            renderTabBar();
            ImGui.separator();
            
            renderFooter();
            
            ImGui.endPopup();
        }
        
        // Handle modal close with unsaved changes
        if (!showPreferences.get() && hasUnsavedChanges) {
            showUnsavedChangesDialog();
        }
    }
    
    private void renderHeader() {
        ImGui.textColored(0.8f, 0.9f, 1.0f, 1.0f, "OpenMason Preferences");
        ImGui.text("Customize OpenMason to match your workflow and preferences.");
    }
    
    private void renderTabBar() {
        if (ImGui.beginTabBar("PreferenceTabs")) {
            
            if (ImGui.beginTabItem("General")) {
                currentTab = 0;
                renderGeneralTab();
                ImGui.endTabItem();
            }
            
            if (ImGui.beginTabItem("Appearance")) {
                currentTab = 1;
                renderAppearanceTab();
                ImGui.endTabItem();
            }
            
            if (ImGui.beginTabItem("Shortcuts")) {
                currentTab = 2;
                renderShortcutsTab();
                ImGui.endTabItem();
            }
            
            if (ImGui.beginTabItem("Interface")) {
                currentTab = 3;
                renderInterfaceTab();
                ImGui.endTabItem();
            }
            
            if (ImGui.beginTabItem("Help")) {
                currentTab = 4;
                renderHelpTab();
                ImGui.endTabItem();
            }
            
            if (ImGui.beginTabItem("Advanced")) {
                currentTab = 5;
                renderAdvancedTab();
                ImGui.endTabItem();
            }
            
            ImGui.endTabBar();
        }
    }
    
    private void renderGeneralTab() {
        if (ImGui.beginChild("GeneralContent", 0, -50)) {
            
            if (ImGui.collapsingHeader("Application Settings", ImGuiTreeNodeFlags.DefaultOpen)) {
                ImGui.indent();
                
                if (ImGui.checkbox("Auto-save layouts and settings", autoSaveLayouts)) {
                    hasUnsavedChanges = true;
                }
                
                ImGui.spacing();
                ImGui.text("Startup Behavior:");
                if (ImGui.combo("##startup", startupBehavior, startupOptions)) {
                    hasUnsavedChanges = true;
                }
                
                ImGui.spacing();
                ImGui.text("Recent Files:");
                if (ImGui.sliderInt("##recent", recentFileCount.getData(), 5, 50)) {
                    hasUnsavedChanges = true;
                }
                ImGui.text("Number of recent files to remember");
                
                ImGui.unindent();
            }
            
            if (ImGui.collapsingHeader("Performance Settings", ImGuiTreeNodeFlags.DefaultOpen)) {
                ImGui.indent();
                
                ImGui.text("Memory Usage:");
                if (ImGui.sliderFloat("##memory", memoryUsage.getData(), 512.0f, 8192.0f, "%.0f MB")) {
                    hasUnsavedChanges = true;
                }
                
                ImGui.unindent();
            }
            
        }
        ImGui.endChild();
    }
    
    private void renderAppearanceTab() {
        if (ImGui.beginChild("AppearanceContent", 0, -50)) {
            
            if (ImGui.collapsingHeader("Theme Settings")) {
                ImGui.indent();
                
                ImGui.text("Current Theme:");
                if (ImGui.combo("##theme", currentTheme, themeOptions)) {
                    hasUnsavedChanges = true;
                }
                
                if (ImGui.button("Customize Theme...")) {
                    uiManager.showThemeCustomization();
                }
                
                ImGui.spacing();
                ImGui.text("UI Density:");
                if (ImGui.combo("##density", uiDensity, densityOptions)) {
                    hasUnsavedChanges = true;
                }
                
                ImGui.unindent();
            }
            
            if (ImGui.collapsingHeader("Font Settings", ImGuiTreeNodeFlags.DefaultOpen)) {
                ImGui.indent();
                
                ImGui.text("Interface Font Size:");
                if (ImGui.sliderFloat("##fontsize", interfaceFontSize.getData(), 8.0f, 18.0f, "%.0f pt")) {
                    hasUnsavedChanges = true;
                }
                
                ImGui.unindent();
            }
            
        }
        ImGui.endChild();
    }
    
    private void renderShortcutsTab() {
        if (ImGui.beginChild("ShortcutsContent", 0, -50)) {
            
            if (ImGui.collapsingHeader("Keyboard Shortcuts", ImGuiTreeNodeFlags.DefaultOpen)) {
                ImGui.indent();
                
                ImGui.text("Shortcut Preset:");
                String[] presetNames = {"Default", "Developer", "Custom"};
                // Stub implementation - no shortcut system available
                
                if (ImGui.combo("##preset", shortcutPreset, presetNames)) {
                    hasUnsavedChanges = true;
                }
                
                if (ImGui.button("Edit Shortcuts...")) {
                    uiManager.showShortcutEditor();
                }
                
                ImGui.spacing();
                ImGui.text("Common Shortcuts:");
                
                if (ImGui.beginTable("CommonShortcuts", 2, ImGuiTableFlags.Borders)) {
                    ImGui.tableSetupColumn("Action", ImGuiTableColumnFlags.WidthFixed, 150);
                    ImGui.tableSetupColumn("Shortcut", ImGuiTableColumnFlags.WidthStretch);
                    ImGui.tableHeadersRow();
                    
                    addShortcutTableRow("New Model:", "Ctrl+N");
                    addShortcutTableRow("Open Model:", "Ctrl+O");
                    addShortcutTableRow("Save Model:", "Ctrl+S");
                    addShortcutTableRow("Reset View:", "Home");
                    addShortcutTableRow("Toggle Wireframe:", "Z");
                    
                    ImGui.endTable();
                }
                
                ImGui.unindent();
            }
            
        }
        ImGui.endChild();
    }
    
    private void addShortcutTableRow(String action, String shortcut) {
        ImGui.tableNextRow();
        ImGui.tableSetColumnIndex(0);
        ImGui.text(action);
        ImGui.tableSetColumnIndex(1);
        ImGui.textColored(0.8f, 0.8f, 1.0f, 1.0f, shortcut);
    }
    
    private void renderInterfaceTab() {
        if (ImGui.beginChild("InterfaceContent", 0, -50)) {
            
            if (ImGui.collapsingHeader("Panel Settings", ImGuiTreeNodeFlags.DefaultOpen)) {
                ImGui.indent();
                
                if (ImGui.checkbox("Auto-hide panels when not in use", autoHidePanels)) {
                    hasUnsavedChanges = true;
                }
                
                if (ImGui.checkbox("Snap panels to window edges", snapToEdges)) {
                    hasUnsavedChanges = true;
                }
                
                if (ImGui.checkbox("Remember panel layout between sessions", rememberLayout)) {
                    hasUnsavedChanges = true;
                }
                
                ImGui.unindent();
            }
            
            if (ImGui.collapsingHeader("Toolbar Settings", ImGuiTreeNodeFlags.DefaultOpen)) {
                ImGui.indent();
                
                if (ImGui.checkbox("Show text labels on toolbar buttons", showToolbarText)) {
                    hasUnsavedChanges = true;
                }
                
                if (ImGui.checkbox("Use large toolbar icons", largeIcons)) {
                    hasUnsavedChanges = true;
                }
                
                ImGui.unindent();
            }
            
            if (ImGui.collapsingHeader("Status Bar Settings", ImGuiTreeNodeFlags.DefaultOpen)) {
                ImGui.indent();
                
                if (ImGui.checkbox("Show memory usage", showMemoryUsage)) {
                    hasUnsavedChanges = true;
                }
                
                if (ImGui.checkbox("Show frame rate", showFPS)) {
                    hasUnsavedChanges = true;
                }
                
                if (ImGui.checkbox("Show operation progress", showProgress)) {
                    hasUnsavedChanges = true;
                }
                
                ImGui.unindent();
            }
            
        }
        ImGui.endChild();
    }
    
    private void renderHelpTab() {
        if (ImGui.beginChild("HelpContent", 0, -50)) {
            
            if (ImGui.collapsingHeader("Help System Settings", ImGuiTreeNodeFlags.DefaultOpen)) {
                ImGui.indent();
                
                if (ImGui.checkbox("Show context-sensitive tooltips", showTooltips)) {
                    hasUnsavedChanges = true;
                }
                
                if (ImGui.checkbox("Auto-show help for new features", autoShowHelp)) {
                    hasUnsavedChanges = true;
                }
                
                if (ImGui.checkbox("Enable interactive tutorials", enableTutorials)) {
                    hasUnsavedChanges = true;
                }
                
                ImGui.unindent();
            }
            
            if (ImGui.collapsingHeader("Tutorial Settings", ImGuiTreeNodeFlags.DefaultOpen)) {
                ImGui.indent();
                
                if (ImGui.checkbox("Remember tutorial progress", rememberTutorialProgress)) {
                    hasUnsavedChanges = true;
                }
                
                if (ImGui.checkbox("Show tutorial tips during normal use", showTutorialTips)) {
                    hasUnsavedChanges = true;
                }
                
                ImGui.unindent();
            }
            
            if (ImGui.collapsingHeader("Help Actions", ImGuiTreeNodeFlags.DefaultOpen)) {
                ImGui.indent();
                
                if (ImGui.button("Open Help Browser")) {
                    uiManager.showHelp();
                }
                
                ImGui.sameLine();
                if (ImGui.button("Start Getting Started Tutorial")) {
                    uiManager.startGettingStartedTutorial();
                }
                
                ImGui.unindent();
            }
            
        }
        ImGui.endChild();
    }
    
    private void renderAdvancedTab() {
        if (ImGui.beginChild("AdvancedContent", 0, -50)) {
            
            if (ImGui.collapsingHeader("System Information", ImGuiTreeNodeFlags.DefaultOpen)) {
                ImGui.indent();
                
                ImGui.inputTextMultiline("##systeminfo", systemInfo, 400, 150, ImGuiInputTextFlags.ReadOnly);
                
                if (ImGui.button("Refresh")) {
                    systemInfo.set(uiManager.getSystemStatistics());
                }
                
                ImGui.unindent();
            }
            
            if (ImGui.collapsingHeader("Debug Settings", ImGuiTreeNodeFlags.DefaultOpen)) {
                ImGui.indent();
                
                if (ImGui.checkbox("Enable verbose logging", enableVerboseLogging)) {
                    hasUnsavedChanges = true;
                }
                
                if (ImGui.checkbox("Show debug information in UI", showDebugInfo)) {
                    hasUnsavedChanges = true;
                }
                
                ImGui.unindent();
            }
            
            if (ImGui.collapsingHeader("Reset Settings", ImGuiTreeNodeFlags.DefaultOpen)) {
                ImGui.indent();
                
                ImGui.textColored(1.0f, 0.6f, 0.0f, 1.0f, "Warning: These actions cannot be undone!");
                
                if (ImGui.button("Reset All Preferences")) {
                    showResetConfirmation("Reset All Preferences", 
                                        "This will reset all preferences to their default values.");
                }
                
                if (ImGui.button("Reset Interface Layout")) {
                    showResetConfirmation("Reset Interface Layout",
                                        "This will reset the interface layout to default.");
                }
                
                if (ImGui.button("Reset Keyboard Shortcuts")) {
                    showResetConfirmation("Reset Keyboard Shortcuts",
                                        "This will reset all keyboard shortcuts to defaults.");
                }
                
                ImGui.unindent();
            }
            
        }
        ImGui.endChild();
    }
    
    private void renderFooter() {
        ImGui.separator();
        
        // Status text
        if (hasUnsavedChanges) {
            ImGui.textColored(1.0f, 0.8f, 0.0f, 1.0f, "You have unsaved changes");
        } else {
            ImGui.text("All changes saved");
        }
        
        ImGui.sameLine(ImGui.getWindowWidth() - 300);
        
        // Footer buttons
        if (ImGui.button("Reset to Defaults")) {
            showResetConfirmation("Reset to Defaults", 
                                "Reset all preferences to their default values?");
        }
        
        ImGui.sameLine();
        if (ImGui.button("Cancel")) {
            if (hasUnsavedChanges) {
                showUnsavedChangesDialog();
            } else {
                showPreferences.set(false);
            }
        }
        
        ImGui.sameLine();
        if (ImGui.button("Apply")) {
            applyChanges();
        }
        
        ImGui.sameLine();
        if (ImGui.button("OK")) {
            applyAndClose();
        }
    }
    
    private void showUnsavedChangesDialog() {
        // This would open another modal dialog for unsaved changes
        // For now, just apply changes
        applyChanges();
        showPreferences.set(false);
    }
    
    private void showResetConfirmation(String title, String message) {
        // This would show a confirmation dialog
        // For now, just perform the reset
        resetToDefaults();
    }
    
    private void resetToDefaults() {
        // Reset all settings to defaults
        autoSaveLayouts.set(true);
        startupBehavior.set(2);
        recentFileCount.set(10);
        memoryUsage.set(2048.0f);
        
        currentTheme.set(0);
        uiDensity.set(1);
        interfaceFontSize.set(12.0f);
        
        shortcutPreset.set(0);
        
        autoHidePanels.set(false);
        snapToEdges.set(true);
        rememberLayout.set(true);
        showToolbarText.set(false);
        largeIcons.set(false);
        showMemoryUsage.set(true);
        showFPS.set(true);
        showProgress.set(true);
        
        showTooltips.set(true);
        autoShowHelp.set(true);
        enableTutorials.set(true);
        rememberTutorialProgress.set(true);
        showTutorialTips.set(false);
        
        enableVerboseLogging.set(false);
        showDebugInfo.set(false);
        
        hasUnsavedChanges = true;
        
        logger.info("Reset all preferences to defaults");
    }
    
    private void applyChanges() {
        try {
            // Apply theme changes
            String selectedTheme = themeOptions[currentTheme.get()];
            if (selectedTheme.toLowerCase().contains("dark")) {
                themeManager.applyTheme("dark");
            } else if (selectedTheme.toLowerCase().contains("light")) {
                themeManager.applyTheme("light");
            }
            
            // Apply UI density
            ThemeManager.UIDensity[] densities = ThemeManager.UIDensity.values();
            if (uiDensity.get() >= 0 && uiDensity.get() < densities.length) {
                themeManager.setUIDensity(densities[uiDensity.get()]);
            }
            
            // Apply shortcut preset
            // Apply shortcut preset (stub implementation)
            if (shortcutPreset.get() >= 0 && shortcutPreset.get() < 3) {
                logger.info("Shortcut preset {} applied (stub)", shortcutPreset.get());
            }
            
            hasUnsavedChanges = false;
            logger.info("Applied preference changes");
            
        } catch (Exception e) {
            logger.error("Error applying preference changes", e);
        }
    }
    
    private void applyAndClose() {
        applyChanges();
        showPreferences.set(false);
    }
    
    // Public API methods
    
    public boolean isShowing() {
        return showPreferences.get();
    }
    
    public void hide() {
        showPreferences.set(false);
    }
    
    public boolean hasUnsavedChanges() {
        return hasUnsavedChanges;
    }
    
    public int getCurrentTab() {
        return currentTab;
    }
    
    public Map<String, Object> getPreferenceValues() {
        Map<String, Object> values = new HashMap<>();
        
        // General settings
        values.put("autoSaveLayouts", autoSaveLayouts.get());
        values.put("startupBehavior", startupBehavior.get());
        values.put("recentFileCount", recentFileCount.get());
        values.put("memoryUsage", memoryUsage.get());
        
        // Appearance settings
        values.put("currentTheme", currentTheme.get());
        values.put("uiDensity", uiDensity.get());
        values.put("interfaceFontSize", interfaceFontSize.get());
        
        // Interface settings
        values.put("autoHidePanels", autoHidePanels.get());
        values.put("snapToEdges", snapToEdges.get());
        values.put("rememberLayout", rememberLayout.get());
        values.put("showToolbarText", showToolbarText.get());
        values.put("largeIcons", largeIcons.get());
        values.put("showMemoryUsage", showMemoryUsage.get());
        values.put("showFPS", showFPS.get());
        values.put("showProgress", showProgress.get());
        
        // Help settings
        values.put("showTooltips", showTooltips.get());
        values.put("autoShowHelp", autoShowHelp.get());
        values.put("enableTutorials", enableTutorials.get());
        
        // Advanced settings
        values.put("enableVerboseLogging", enableVerboseLogging.get());
        values.put("showDebugInfo", showDebugInfo.get());
        
        return values;
    }
}