package com.openmason.ui.help;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Context help renderer for tooltips and inline help in Dear ImGui.
 * 
 * Provides various types of contextual help:
 * - Hover tooltips for UI elements
 * - Help icons with detailed tooltips
 * - Inline help panels
 * - Quick help overlays
 * - Status-based help hints
 */
public class ContextHelpRenderer {
    
    private static final Logger logger = LoggerFactory.getLogger(ContextHelpRenderer.class);
    
    // Configuration
    private float tooltipWrapWidth = 300.0f;
    private float inlineHelpWrapWidth = 400.0f;
    private float helpIconSize = 16.0f;
    private boolean enableAnimations = true;
    
    // State tracking
    private final Map<String, Long> tooltipTimings = new HashMap<>();
    private final Map<String, Boolean> tooltipStates = new HashMap<>();
    private long tooltipDelayMs = 500; // Delay before showing tooltip
    
    // Visual configuration
    private float helpIconColorR = 0.6f, helpIconColorG = 0.6f, helpIconColorB = 0.6f;
    private float inlineHelpBgR = 0.0f, inlineHelpBgG = 0.3f, inlineHelpBgB = 0.6f, inlineHelpBgA = 0.15f;
    
    /**
     * Show context help tooltip for the last drawn ImGui item.
     * Only shows if the item is hovered.
     * 
     * @param contextId Unique identifier for this help context
     * @param helpText The help text to display
     */
    public void showContextHelp(String contextId, String helpText) {
        if (helpText == null || helpText.trim().isEmpty()) {
            return;
        }
        
        if (ImGui.isItemHovered()) {
            long currentTime = System.currentTimeMillis();
            Long firstHoverTime = tooltipTimings.get(contextId);
            
            if (firstHoverTime == null) {
                tooltipTimings.put(contextId, currentTime);
                return;
            }
            
            // Show tooltip after delay
            if (currentTime - firstHoverTime >= tooltipDelayMs) {
                showTooltip(helpText, tooltipWrapWidth);
                tooltipStates.put(contextId, true);
            }
        } else {
            // Clear timing when not hovering
            tooltipTimings.remove(contextId);
            tooltipStates.remove(contextId);
        }
    }
    
    /**
     * Show context help tooltip immediately without delay.
     * 
     * @param helpText The help text to display
     */
    public void showContextHelpImmediate(String helpText) {
        if (helpText != null && !helpText.trim().isEmpty() && ImGui.isItemHovered()) {
            showTooltip(helpText, tooltipWrapWidth);
        }
    }
    
    /**
     * Show a help icon (?) with tooltip on hover.
     * 
     * @param helpText The help text to display in tooltip
     * @return true if the help icon was clicked
     */
    public boolean helpIcon(String helpText) {
        return helpIcon("(?)", helpText);
    }
    
    /**
     * Show a custom help icon with tooltip on hover.
     * 
     * @param iconText The text/symbol to display as the help icon
     * @param helpText The help text to display in tooltip
     * @return true if the help icon was clicked
     */
    public boolean helpIcon(String iconText, String helpText) {
        ImGui.pushStyleColor(ImGuiCol.Text, helpIconColorR, helpIconColorG, helpIconColorB, 0.8f);
        ImGui.pushStyleColor(ImGuiCol.Button, 0.0f, 0.0f, 0.0f, 0.0f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.3f, 0.3f, 0.3f, 0.3f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0.5f, 0.5f, 0.5f, 0.5f);
        
        boolean clicked = ImGui.smallButton(iconText);
        
        ImGui.popStyleColor(4);
        
        if (ImGui.isItemHovered() && helpText != null && !helpText.trim().isEmpty()) {
            showTooltip(helpText, inlineHelpWrapWidth);
        }
        
        return clicked;
    }
    
    /**
     * Show an inline help panel with title and content.
     * 
     * @param title The title of the help panel
     * @param content The help content to display
     */
    public void showInlineHelp(String title, String content) {
        if ((title == null || title.trim().isEmpty()) && 
            (content == null || content.trim().isEmpty())) {
            return;
        }
        
        ImGui.pushStyleColor(ImGuiCol.ChildBg, inlineHelpBgR, inlineHelpBgG, inlineHelpBgB, inlineHelpBgA);
        ImGui.pushStyleVar(ImGuiStyleVar.ChildRounding, 8.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ChildBorderSize, 1.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 12.0f, 8.0f);
        
        if (ImGui.beginChild("inline_help", 0, 0, true, ImGuiWindowFlags.AlwaysAutoResize)) {
            if (title != null && !title.trim().isEmpty()) {
                ImGui.pushStyleColor(ImGuiCol.Text, 0.4f, 0.7f, 1.0f, 1.0f);
                ImGui.text("💡 " + title);
                ImGui.popStyleColor();
                
                if (content != null && !content.trim().isEmpty()) {
                    ImGui.separator();
                    ImGui.spacing();
                }
            }
            
            if (content != null && !content.trim().isEmpty()) {
                ImGui.pushTextWrapPos(inlineHelpWrapWidth);
                ImGui.textWrapped(content);
                ImGui.popTextWrapPos();
            }
        }
        ImGui.endChild();
        
        ImGui.popStyleVar(3);
        ImGui.popStyleColor();
    }
    
    /**
     * Show a warning help panel.
     * 
     * @param title The warning title
     * @param content The warning content
     */
    public void showWarningHelp(String title, String content) {
        ImGui.pushStyleColor(ImGuiCol.ChildBg, 0.6f, 0.3f, 0.0f, 0.15f);
        ImGui.pushStyleVar(ImGuiStyleVar.ChildRounding, 8.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ChildBorderSize, 1.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 12.0f, 8.0f);
        
        if (ImGui.beginChild("warning_help", 0, 0, true, ImGuiWindowFlags.AlwaysAutoResize)) {
            if (title != null && !title.trim().isEmpty()) {
                ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 0.6f, 0.0f, 1.0f);
                ImGui.text("⚠️ " + title);
                ImGui.popStyleColor();
                
                if (content != null && !content.trim().isEmpty()) {
                    ImGui.separator();
                    ImGui.spacing();
                }
            }
            
            if (content != null && !content.trim().isEmpty()) {
                ImGui.pushTextWrapPos(inlineHelpWrapWidth);
                ImGui.textWrapped(content);
                ImGui.popTextWrapPos();
            }
        }
        ImGui.endChild();
        
        ImGui.popStyleVar(3);
        ImGui.popStyleColor();
    }
    
    /**
     * Show an error help panel.
     * 
     * @param title The error title
     * @param content The error content
     */
    public void showErrorHelp(String title, String content) {
        ImGui.pushStyleColor(ImGuiCol.ChildBg, 0.6f, 0.0f, 0.0f, 0.15f);
        ImGui.pushStyleVar(ImGuiStyleVar.ChildRounding, 8.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ChildBorderSize, 1.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 12.0f, 8.0f);
        
        if (ImGui.beginChild("error_help", 0, 0, true, ImGuiWindowFlags.AlwaysAutoResize)) {
            if (title != null && !title.trim().isEmpty()) {
                ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 0.3f, 0.3f, 1.0f);
                ImGui.text("❌ " + title);
                ImGui.popStyleColor();
                
                if (content != null && !content.trim().isEmpty()) {
                    ImGui.separator();
                    ImGui.spacing();
                }
            }
            
            if (content != null && !content.trim().isEmpty()) {
                ImGui.pushTextWrapPos(inlineHelpWrapWidth);
                ImGui.textWrapped(content);
                ImGui.popTextWrapPos();
            }
        }
        ImGui.endChild();
        
        ImGui.popStyleVar(3);
        ImGui.popStyleColor();
    }
    
    /**
     * Show a success help panel.
     * 
     * @param title The success title
     * @param content The success content
     */
    public void showSuccessHelp(String title, String content) {
        ImGui.pushStyleColor(ImGuiCol.ChildBg, 0.0f, 0.6f, 0.0f, 0.15f);
        ImGui.pushStyleVar(ImGuiStyleVar.ChildRounding, 8.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ChildBorderSize, 1.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 12.0f, 8.0f);
        
        if (ImGui.beginChild("success_help", 0, 0, true, ImGuiWindowFlags.AlwaysAutoResize)) {
            if (title != null && !title.trim().isEmpty()) {
                ImGui.pushStyleColor(ImGuiCol.Text, 0.3f, 1.0f, 0.3f, 1.0f);
                ImGui.text("✅ " + title);
                ImGui.popStyleColor();
                
                if (content != null && !content.trim().isEmpty()) {
                    ImGui.separator();
                    ImGui.spacing();
                }
            }
            
            if (content != null && !content.trim().isEmpty()) {
                ImGui.pushTextWrapPos(inlineHelpWrapWidth);
                ImGui.textWrapped(content);
                ImGui.popTextWrapPos();
            }
        }
        ImGui.endChild();
        
        ImGui.popStyleVar(3);
        ImGui.popStyleColor();
    }
    
    /**
     * Show a quick help overlay at a specific position.
     * 
     * @param x X position for the overlay
     * @param y Y position for the overlay
     * @param helpText The help text to display
     */
    public void showQuickHelpOverlay(float x, float y, String helpText) {
        if (helpText == null || helpText.trim().isEmpty()) {
            return;
        }
        
        ImGui.setNextWindowPos(x, y);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 8.0f, 6.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 6.0f);
        ImGui.pushStyleColor(ImGuiCol.WindowBg, 0.1f, 0.1f, 0.1f, 0.9f);
        
        int flags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | 
                   ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoScrollbar |
                   ImGuiWindowFlags.NoScrollWithMouse | ImGuiWindowFlags.AlwaysAutoResize;
        
        if (ImGui.begin("QuickHelp", flags)) {
            ImGui.pushTextWrapPos(250.0f);
            ImGui.textUnformatted(helpText);
            ImGui.popTextWrapPos();
        }
        ImGui.end();
        
        ImGui.popStyleColor();
        ImGui.popStyleVar(2);
    }
    
    /**
     * Show a status-based help hint.
     * 
     * @param status The current status
     * @param helpText The help text for this status
     */
    public void showStatusHelp(String status, String helpText) {
        if (status == null || helpText == null) {
            return;
        }
        
        ImGui.pushStyleColor(ImGuiCol.Text, 0.7f, 0.7f, 0.7f, 1.0f);
        ImGui.text("Status: " + status);
        ImGui.popStyleColor();
        
        if (ImGui.isItemHovered()) {
            showTooltip(helpText, tooltipWrapWidth);
        }
    }
    
    /**
     * Show a keyboard shortcut help.
     * 
     * @param description Description of the action
     * @param shortcut The keyboard shortcut
     */
    public void showShortcutHelp(String description, String shortcut) {
        if (description == null || shortcut == null) {
            return;
        }
        
        ImGui.text(description);
        ImGui.sameLine();
        
        ImGui.pushStyleColor(ImGuiCol.ChildBg, 0.2f, 0.2f, 0.2f, 1.0f);
        ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 1.0f, 1.0f, 1.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ChildRounding, 3.0f);
        
        float shortcutWidth = ImGui.calcTextSize(shortcut).x + 8.0f;
        if (ImGui.beginChild("shortcut", shortcutWidth, ImGui.getTextLineHeight() + 4.0f, true)) {
            ImGui.text(shortcut);
        }
        ImGui.endChild();
        
        ImGui.popStyleVar();
        ImGui.popStyleColor(2);
    }
    
    /**
     * Internal method to show a tooltip with proper styling.
     */
    private void showTooltip(String text, float wrapWidth) {
        ImGui.beginTooltip();
        ImGui.pushTextWrapPos(wrapWidth);
        ImGui.textUnformatted(text);
        ImGui.popTextWrapPos();
        ImGui.endTooltip();
    }
    
    // Configuration methods
    
    public void setTooltipWrapWidth(float width) {
        this.tooltipWrapWidth = Math.max(100.0f, width);
    }
    
    public void setInlineHelpWrapWidth(float width) {
        this.inlineHelpWrapWidth = Math.max(100.0f, width);
    }
    
    public void setTooltipDelay(long delayMs) {
        this.tooltipDelayMs = Math.max(0, delayMs);
    }
    
    public void setHelpIconColor(float r, float g, float b) {
        this.helpIconColorR = Math.max(0.0f, Math.min(1.0f, r));
        this.helpIconColorG = Math.max(0.0f, Math.min(1.0f, g));
        this.helpIconColorB = Math.max(0.0f, Math.min(1.0f, b));
    }
    
    public void setInlineHelpBackgroundColor(float r, float g, float b, float a) {
        this.inlineHelpBgR = Math.max(0.0f, Math.min(1.0f, r));
        this.inlineHelpBgG = Math.max(0.0f, Math.min(1.0f, g));
        this.inlineHelpBgB = Math.max(0.0f, Math.min(1.0f, b));
        this.inlineHelpBgA = Math.max(0.0f, Math.min(1.0f, a));
    }
    
    public void setAnimationsEnabled(boolean enabled) {
        this.enableAnimations = enabled;
    }
    
    /**
     * Clear all tooltip timing state.
     * Useful when switching contexts or clearing the UI.
     */
    public void clearTooltipState() {
        tooltipTimings.clear();
        tooltipStates.clear();
    }
    
    /**
     * Get tooltip timing information for debugging.
     */
    public Map<String, Long> getTooltipTimings() {
        return new HashMap<>(tooltipTimings);
    }
    
    /**
     * Check if a specific tooltip is currently active.
     */
    public boolean isTooltipActive(String contextId) {
        return tooltipStates.getOrDefault(contextId, false);
    }
    
    /**
     * Get configuration information for debugging.
     */
    public String getConfigInfo() {
        return String.format("ContextHelpRenderer - Tooltip wrap: %.0f, Inline wrap: %.0f, Delay: %dms, Animations: %s",
                           tooltipWrapWidth, inlineHelpWrapWidth, tooltipDelayMs, enableAnimations);
    }
}