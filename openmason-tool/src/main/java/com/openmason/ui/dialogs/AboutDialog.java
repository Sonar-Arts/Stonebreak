package com.openmason.ui.dialogs;

import com.openmason.ui.LogoManager;
import com.openmason.ui.state.UIVisibilityState;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;

/**
 * About dialog window.
 * Follows Single Responsibility Principle - only handles about window rendering.
 */
public class AboutDialog {

    private final UIVisibilityState uiState;
    private final LogoManager logoManager;

    public AboutDialog(UIVisibilityState uiState, LogoManager logoManager) {
        this.uiState = uiState;
        this.logoManager = logoManager;
    }

    /**
     * Render the about window.
     */
    public void render() {
        if (!uiState.getShowAboutWindow().get()) {
            return;
        }

        if (ImGui.begin("About OpenMason", uiState.getShowAboutWindow(),
                ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoCollapse)) {

            // Render large logo at the top
            if (logoManager != null) {
                logoManager.renderAboutLogo();
            }

            // Application title and version
            ImGui.textColored(0.2f, 0.6f, 1.0f, 1.0f, "OpenMason");
            ImGui.sameLine();
            ImGui.text("v0.0.1");

            ImGui.text("Professional 3D Model Development Tool");
            ImGui.spacing();

            // Description
            ImGui.separator();
            ImGui.spacing();
            ImGui.textWrapped("OpenMason is a professional 3D model and texture development tool designed for " +
                    "creating and editing Stonebreak game assets. Built with ImGui and LWJGL for " +
                    "high-performance rendering and intuitive user experience.");
            ImGui.spacing();

            // Features
            ImGui.text("Features:");
            ImGui.bulletText("Real-time 3D model visualization");
            ImGui.bulletText("Texture variant management");
            ImGui.bulletText("Professional camera controls");
            ImGui.bulletText("Transform gizmos and wireframe modes");
            ImGui.bulletText("Direct integration with Stonebreak model system");
            ImGui.spacing();

            // Technical information
            ImGui.separator();
            ImGui.spacing();
            ImGui.text("Built with:");
            ImGui.bulletText("Java 17");
            ImGui.bulletText("LWJGL 3.3.2 (OpenGL, GLFW)");
            ImGui.bulletText("Dear ImGui");
            ImGui.bulletText("JOML Math Library");
            ImGui.spacing();

            // Close button
            ImGui.separator();
            ImGui.spacing();
            float windowWidth = ImGui.getWindowSize().x;
            float buttonWidth = 80.0f;
            ImGui.setCursorPosX((windowWidth - buttonWidth) * 0.5f);

            if (ImGui.button("Close", buttonWidth, 0)) {
                uiState.getShowAboutWindow().set(false);
            }
        }
        ImGui.end();
    }
}
