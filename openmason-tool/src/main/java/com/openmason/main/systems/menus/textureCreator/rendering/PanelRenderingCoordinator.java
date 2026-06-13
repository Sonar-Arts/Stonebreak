package com.openmason.main.systems.menus.textureCreator.rendering;

import com.openmason.main.systems.menus.textureCreator.TextureCreatorController;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorPreferences;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorState;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorWindowState;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.coordinators.ToolCoordinator;
import com.openmason.main.systems.menus.preferences.PreferencesManager;
import com.openmason.main.systems.menus.textureCreator.panels.*;
import com.openmason.main.systems.menus.textureCreator.panels.color.ColorPanelView;
import com.openmason.main.systems.menus.toolbars.TextureEditorToolbarRenderer;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiDockNodeFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

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
    private final ColorPanelView colorPanel;
    private final NoiseFilterPanel noiseFilterPanel;
    private final SymmetryPanel symmetryPanel;

    // Windowed mode flag - when true, skip fullscreen dockspace creation
    private boolean windowedMode = false;

    // Previous-frame visibility, to focus these windows when they (re)open —
    // surfaces them even if they were docked behind another window
    private boolean noiseFilterWasVisible = false;
    private boolean symmetryWasVisible = false;

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
                                    ColorPanelView colorPanel,
                                    NoiseFilterPanel noiseFilterPanel,
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
        this.symmetryPanel = symmetryPanel;
    }

    /**
     * Render all panels.
     */
    public void renderAllPanels() {
        renderCanvasPanel();
        renderLayersPanel();
    }

    // ========================================
    // FIXED LEFT CHROME: Color column | splitter | Tools column
    // ========================================
    // These live OUTSIDE the dock system on purpose: as dock nodes, the
    // fixed-width Tools column couldn't coexist with a resizable Color
    // column (dock splitters either froze both or dumped freed space into
    // the Tools node, detaching the strip and crushing the canvas).

    private static final float TOOLS_COLUMN_WIDTH = 48f;
    private static final float SPLITTER_WIDTH = 6f;
    private static final float COLOR_COLUMN_MIN = 240f;
    private static final float COLOR_COLUMN_MAX = 480f;
    private static final float COLOR_COLUMN_DEFAULT = 320f;

    // Size bounds for the detached "Layers" window so it can't be resized
    // smaller than a usable card or wider/taller than is sensible.
    private static final float LAYERS_WINDOW_MIN_W = 220f;
    private static final float LAYERS_WINDOW_MIN_H = 160f;
    private static final float LAYERS_WINDOW_MAX_W = 400f;
    private static final float LAYERS_WINDOW_DEFAULT_W = 280f;
    private static final float LAYERS_WINDOW_DEFAULT_H = 360f;

    private float colorColumnWidth = -1f; // lazy-loaded from preferences

    /**
     * Render the fixed left columns (Color panel + splitter + tools strip)
     * as chrome children. Caller follows with {@code ImGui.sameLine(0,0)} and
     * the dockspace, which fills the remaining width.
     *
     * @param height pixel height of the column row (content minus status bar)
     */
    public void renderLeftColumns(float height) {
        if (colorColumnWidth < 0) {
            colorColumnWidth = PreferencesManager.getInstance()
                    .getTextureEditorColorColumnWidth(COLOR_COLUMN_DEFAULT);
        }

        if (windowState.getShowColorPanel().get()) {
            ImGui.beginChild("##color_column", colorColumnWidth, height, false,
                    ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
            colorPanel.render();
            state.setCurrentColor(colorPanel.getCurrentColor());
            ImGui.endChild();

            ImGui.sameLine(0, 0);
            renderColorColumnSplitter(height);
            ImGui.sameLine(0, 0);
        }

        ImGui.beginChild("##tools_column", TOOLS_COLUMN_WIDTH, height, false,
                ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
        toolbarPanel.render();
        toolCoordinator.syncToolState();
        ImGui.endChild();

        // 1px divider on the tools column's right edge, separating the fixed
        // chrome from the dockspace. Drawn on the foreground list: the child
        // windows paint their opaque ChildBg above the host window's draw
        // list, which swallows a line drawn there.
        float edgeX = ImGui.getItemRectMaxX() - 0.5f;
        ImGui.getForegroundDrawList().addLine(
                edgeX, ImGui.getItemRectMinY(), edgeX, ImGui.getItemRectMaxY(),
                ImGui.getColorU32(imgui.flag.ImGuiCol.Separator), 1.0f);
    }

    /**
     * Custom drag handle between the Color and Tools columns. Dragging
     * resizes only the Color column; the dockspace (canvas) absorbs the
     * difference naturally since it fills the remaining width.
     */
    private void renderColorColumnSplitter(float height) {
        ImGui.invisibleButton("##color_column_splitter", SPLITTER_WIDTH, height);

        boolean hovered = ImGui.isItemHovered();
        boolean active = ImGui.isItemActive();
        if (hovered || active) {
            ImGui.setMouseCursor(imgui.flag.ImGuiMouseCursor.ResizeEW);
        }
        if (active) {
            float delta = ImGui.getIO().getMouseDelta().x;
            if (delta != 0) {
                colorColumnWidth = Math.max(COLOR_COLUMN_MIN,
                        Math.min(COLOR_COLUMN_MAX, colorColumnWidth + delta));
            }
        }
        if (ImGui.isItemDeactivated()) {
            PreferencesManager.getInstance().setTextureEditorColorColumnWidth(colorColumnWidth);
        }

        // Visible 1px divider centered in the grab area
        float x = ImGui.getItemRectMinX() + SPLITTER_WIDTH / 2f;
        int color = ImGui.getColorU32(active ? imgui.flag.ImGuiCol.SeparatorActive
                : hovered ? imgui.flag.ImGuiCol.SeparatorHovered
                : imgui.flag.ImGuiCol.Separator);
        ImGui.getWindowDrawList().addLine(x, ImGui.getItemRectMinY(), x, ImGui.getItemRectMaxY(), color, 1.0f);
    }

    /**
     * Render dockspace container.
     * Skips fullscreen dockspace creation when in windowed mode.
     */
    public void renderDockSpace() {
        // Skip fullscreen dockspace creation when in windowed mode
        // (the parent window will provide the dockspace)
        if (windowedMode) {
            return;
        }

        ImGuiViewport viewport = ImGui.getMainViewport();
        float toolbarHeight = toolOptionsBar.getHeight();

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
     * Set windowed mode flag.
     * When true, the coordinator will skip creating its own fullscreen dockspace.
     *
     * @param windowedMode true to enable windowed mode, false for fullscreen mode
     */
    public void setWindowedMode(boolean windowedMode) {
        this.windowedMode = windowedMode;
    }

    /**
     * Check if windowed mode is enabled.
     *
     * @return true if in windowed mode
     */
    public boolean isWindowedMode() {
        return windowedMode;
    }

    /**
     * Render tool options toolbar.
     * In windowed mode, uses simplified rendering without absolute positioning.
     */
    public void renderToolOptionsBar() {
        if (windowedMode) {
            toolOptionsBar.renderWindowed(state.getCurrentTool());
        } else {
            toolOptionsBar.render(state.getCurrentTool());
        }
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
            // Clamp the window so it can't be dragged out to an unusable size:
            // a min that always fits a layer card, and a max width (cards never
            // stretch absurdly wide) with the max height capped to the screen.
            float maxHeight = Math.max(LAYERS_WINDOW_MIN_H, ImGui.getMainViewport().getWorkSizeY());
            ImGui.setNextWindowSizeConstraints(
                    LAYERS_WINDOW_MIN_W, LAYERS_WINDOW_MIN_H,
                    LAYERS_WINDOW_MAX_W, maxHeight);
            ImGui.setNextWindowSize(LAYERS_WINDOW_DEFAULT_W, LAYERS_WINDOW_DEFAULT_H,
                    imgui.flag.ImGuiCond.FirstUseEver);

            if (ImGui.begin("Layers", windowState.getShowLayersPanel())) {
                layerPanel.render(controller.getLayerManager(), controller.getCommandHistory());
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
            }
            ImGui.end();
        }
    }

    /**
     * Render noise filter window.
     * Window is closeable - clicking (x) button will hide it.
     */
    public void renderNoiseFilterWindow() {
        boolean visible = windowState.getShowNoiseFilterWindow().get();
        if (visible) {
            if (!noiseFilterWasVisible) {
                ImGui.setNextWindowFocus();
            }
            if (ImGui.begin("Noise Filter", windowState.getShowNoiseFilterWindow())) {
                noiseFilterPanel.render(state.getCurrentSelection());
            }
            ImGui.end();
        }
        noiseFilterWasVisible = visible;
    }

    /**
     * Render symmetry window.
     * Window is closeable - clicking (x) button will hide it.
     */
    public void renderSymmetryWindow() {
        boolean visible = windowState.getShowSymmetryWindow().get();
        if (visible) {
            if (!symmetryWasVisible) {
                ImGui.setNextWindowFocus();
            }
            if (ImGui.begin("Symmetry", windowState.getShowSymmetryWindow())) {
                int canvasWidth = state.getCurrentCanvasSize().getWidth();
                int canvasHeight = state.getCurrentCanvasSize().getHeight();
                symmetryPanel.render(state.getSymmetryState(), canvasWidth, canvasHeight);
            }
            ImGui.end();
        }
        symmetryWasVisible = visible;
    }
}
