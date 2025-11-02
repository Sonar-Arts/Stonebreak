package com.openmason.ui.components.textureCreator.rendering;

import com.openmason.ui.components.textureCreator.TextureCreatorController;
import com.openmason.ui.components.textureCreator.TextureCreatorPreferences;
import com.openmason.ui.components.textureCreator.TextureCreatorState;
import com.openmason.ui.components.textureCreator.TextureCreatorWindowState;
import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.coordinators.ToolCoordinator;
import com.openmason.ui.components.textureCreator.panels.*;
import com.openmason.ui.toolbars.TextureEditorToolbarRenderer;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiDockNodeFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

/**
 * Coordinates rendering of all panels in the Texture Creator.
 * Follows Single Responsibility Principle - only handles panel rendering coordination.
 *
 * @author Open Mason Team
 */
public class PanelRenderingCoordinator {

    private final TextureCreatorState state;
    private final TextureCreatorController controller;
    private final TextureCreatorPreferences preferences;
    private final ToolCoordinator toolCoordinator;
    private final TextureCreatorWindowState windowState;

    private final TextureEditorToolbarRenderer toolbarPanel;
    private final ToolOptionsBar toolOptionsBar;
    private final CanvasPanel canvasPanel;
    private final LayerPanelRenderer layerPanel;
    private final ColorPanel colorPanel;
    private final NoiseFilterPanel noiseFilterPanel;
    private final PreferencesPanel preferencesPanel;
    private final SymmetryPanel symmetryPanel;

    /**
     * Create panel rendering coordinator.
     */
    public PanelRenderingCoordinator(TextureCreatorState state,
                                    TextureCreatorController controller,
                                    TextureCreatorPreferences preferences,
                                    ToolCoordinator toolCoordinator,
                                    TextureCreatorWindowState windowState,
                                    TextureEditorToolbarRenderer toolbarPanel,
                                    ToolOptionsBar toolOptionsBar,
                                    CanvasPanel canvasPanel,
                                    LayerPanelRenderer layerPanel,
                                    ColorPanel colorPanel,
                                    NoiseFilterPanel noiseFilterPanel,
                                    PreferencesPanel preferencesPanel,
                                    SymmetryPanel symmetryPanel) {
        this.state = state;
        this.controller = controller;
        this.preferences = preferences;
        this.toolCoordinator = toolCoordinator;
        this.windowState = windowState;
        this.toolbarPanel = toolbarPanel;
        this.toolOptionsBar = toolOptionsBar;
        this.canvasPanel = canvasPanel;
        this.layerPanel = layerPanel;
        this.colorPanel = colorPanel;
        this.noiseFilterPanel = noiseFilterPanel;
        this.preferencesPanel = preferencesPanel;
        this.symmetryPanel = symmetryPanel;
    }

    /**
     * Render all panels.
     */
    public void renderAllPanels() {
        renderToolsPanel();
        renderCanvasPanel();
        renderLayersPanel();
        renderColorPanel();
    }

    /**
     * Render dockspace container.
     */
    public void renderDockSpace() {
        ImGuiViewport viewport = ImGui.getMainViewport();
        float toolbarHeight = toolOptionsBar.getHeight(state.getCurrentTool());

        ImGui.setNextWindowPos(viewport.getWorkPosX(), viewport.getWorkPosY() + toolbarHeight);
        ImGui.setNextWindowSize(viewport.getWorkSizeX(), viewport.getWorkSizeY() - toolbarHeight);
        ImGui.setNextWindowViewport(viewport.getID());

        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0.0f, 0.0f);

        int flags = ImGuiWindowFlags.NoDocking | ImGuiWindowFlags.NoTitleBar |
                    ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoResize |
                    ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoBringToFrontOnFocus |
                    ImGuiWindowFlags.NoNavFocus;

        ImGui.begin("TextureCreatorDockspace", flags);
        ImGui.popStyleVar(3);
        ImGui.dockSpace(ImGui.getID("TextureCreatorDockSpace"), 0.0f, 0.0f,
            ImGuiDockNodeFlags.PassthruCentralNode);
        ImGui.end();
    }

    /**
     * Render tool options toolbar.
     */
    public void renderToolOptionsBar() {
        toolOptionsBar.render(state.getCurrentTool());
    }

    /**
     * Render tools panel.
     */
    private void renderToolsPanel() {
        if (ImGui.begin("Tools")) {
            toolbarPanel.render();
            toolCoordinator.syncToolState();
        }
        ImGui.end();
    }

    /**
     * Render canvas panel with builder pattern.
     */
    private void renderCanvasPanel() {
        if (ImGui.begin("Canvas")) {
            PixelCanvas composited = controller.getCompositedCanvas();
            PixelCanvas active = controller.getActiveLayerCanvas();
            PixelCanvas background = controller.getBackgroundCanvas();

            if (active != null) active.setSelectionManager(state.getSelectionManager());
            if (composited != null) composited.setSelectionManager(state.getSelectionManager());

            if (composited != null) {
                CanvasRenderContext context = CanvasRenderContext.builder()
                    .compositedCanvas(composited)
                    .activeCanvas(active)
                    .backgroundCanvas(background)
                    .canvasState(controller.getCanvasState())
                    .currentTool(state.getCurrentTool())
                    .currentColor(colorPanel.getCurrentColor())
                    .currentSelection(state.getCurrentSelection())
                    .showGrid(state.getShowGrid().get())
                    .gridOpacity(preferences.getGridOpacity())
                    .cubeNetOverlayOpacity(preferences.getCubeNetOverlayOpacity())
                    .commandHistory(controller.getCommandHistory())
                    .onLayerModified(controller::notifyLayerModified)
                    .onColorPicked(colorPanel::setColor)
                    .onColorUsed(color -> {
                        colorPanel.addColorToHistory(color);
                        preferences.setColorHistory(colorPanel.getColorHistory());
                    })
                    .onSelectionCreated(state::setCurrentSelection)
                    .build();

                canvasPanel.render(context.getCompositedCanvas(), context.getActiveCanvas(),
                    context.getBackgroundCanvas(), context.getCanvasState(), context.getCurrentTool(),
                    context.getCurrentColor(), context.getCurrentSelection(), context.isShowGrid(),
                    context.getGridOpacity(), context.getCubeNetOverlayOpacity(),
                    context.getCommandHistory(), context.getOnLayerModified(),
                    context.getOnColorPicked(), context.getOnColorUsed(),
                    context.getOnSelectionCreated(), state.getSymmetryState());
            } else {
                ImGui.text("No layers");
            }
        }
        ImGui.end();
    }

    /**
     * Render layers panel.
     * Panel is closeable - clicking (x) button will hide it.
     */
    private void renderLayersPanel() {
        if (windowState.getShowLayersPanel().get()) {
            if (ImGui.begin("Layers", windowState.getShowLayersPanel())) {
                layerPanel.render(controller.getLayerManager(), controller.getCommandHistory());
            }
            ImGui.end();
        }
    }

    /**
     * Render color panel.
     * Panel is closeable - clicking (x) button will hide it.
     */
    private void renderColorPanel() {
        if (windowState.getShowColorPanel().get()) {
            if (ImGui.begin("Color", windowState.getShowColorPanel())) {
                colorPanel.render();
                state.setCurrentColor(colorPanel.getCurrentColor());
            }
            ImGui.end();
        }
    }

    /**
     * Render preferences window.
     * Window is closeable - clicking (x) button will hide it.
     */
    public void renderPreferencesWindow() {
        if (windowState.getShowPreferencesWindow().get()) {
            if (ImGui.begin("Preferences", windowState.getShowPreferencesWindow())) {
                preferencesPanel.render(preferences);
            }
            ImGui.end();
        }
    }

    /**
     * Render noise filter window.
     * Window is closeable - clicking (x) button will hide it.
     */
    public void renderNoiseFilterWindow() {
        if (windowState.getShowNoiseFilterWindow().get()) {
            if (ImGui.begin("Noise Filter", windowState.getShowNoiseFilterWindow())) {
                noiseFilterPanel.render(state.getCurrentSelection());
            }
            ImGui.end();
        }
    }

    /**
     * Render symmetry window.
     * Window is closeable - clicking (x) button will hide it.
     */
    public void renderSymmetryWindow() {
        if (windowState.getShowSymmetryWindow().get()) {
            if (ImGui.begin("Symmetry", windowState.getShowSymmetryWindow())) {
                int canvasWidth = state.getCurrentCanvasSize().getWidth();
                int canvasHeight = state.getCurrentCanvasSize().getHeight();
                symmetryPanel.render(state.getSymmetryState(), canvasWidth, canvasHeight);
            }
            ImGui.end();
        }
    }
}
