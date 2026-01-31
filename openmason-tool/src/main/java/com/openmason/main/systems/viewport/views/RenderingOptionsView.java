package com.openmason.main.systems.viewport.views;

import com.openmason.main.systems.viewport.ViewportActions;
import com.openmason.main.systems.viewport.ViewportUIState;
import imgui.ImGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders the rendering options window.
 * Follows Single Responsibility Principle - only renders rendering options UI.
 */
public class RenderingOptionsView {

    private static final Logger logger = LoggerFactory.getLogger(RenderingOptionsView.class);

    private final ViewportUIState state;
    private final ViewportActions actions;

    public RenderingOptionsView(ViewportUIState state, ViewportActions actions) {
        this.state = state;
        this.actions = actions;
    }

    /**
     * Render rendering options window.
     */
    public void render() {
        if (ImGui.begin("Rendering Options", state.getShowRenderingOptions())) {

            ImGui.text("Render Mode:");
            if (ImGui.combo("##render_mode_detailed", state.getCurrentRenderModeIndex(), state.getRenderModes())) {
                actions.updateRenderMode();
            }

            ImGui.separator();

            ImGui.text("Quality Settings:");
            ImGui.text("Anti-aliasing: 4x MSAA");
            ImGui.text("Shadow Quality: High");
            ImGui.text("Texture Filtering: Anisotropic 16x");

            ImGui.separator();

            if (ImGui.button("Apply Settings")) {
                actions.applyRenderingSettings();
            }

        }
        ImGui.end();
    }
}
