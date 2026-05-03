package com.openmason.main.systems.viewport.views;

import com.openmason.main.systems.ViewportController;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.PartShapeFactory;
import com.openmason.engine.rendering.model.gmr.parts.PartTransform;
import com.openmason.main.systems.viewport.ViewportActions;
import com.openmason.main.systems.viewport.ViewportUIState;
import com.openmason.main.systems.viewport.viewportRendering.gizmo.GizmoState;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;
import imgui.type.ImString;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;

/**
 * Renders the tool pane that overlays the left edge of the 3D viewport.
 * Appears instantly when a tool button is toggled. Uses theme-derived
 * colours to match the rest of the Open Mason UI.
 */
public class ToolPaneRenderer {

    private static final Logger logger = LoggerFactory.getLogger(ToolPaneRenderer.class);

    private static final float PANE_WIDTH = 220.0f;
    private static final float ADD_PART_PANE_WIDTH = 280.0f;
    private static final float CLOSE_BTN_SIZE = 20.0f;
    private static final float ICON_HALF = 4.5f;
    private static final float ICON_STROKE = 1.4f;

    private final ViewportUIState state;
    private final ViewportActions actions;
    private final ViewportController viewport;

    // Add Part form state
    private final ImString addPartName = new ImString(64);
    private final ImInt selectedShapeIndex = new ImInt(0);
    private final PartShapeFactory.Shape[] shapes = PartShapeFactory.Shape.values();
    private ViewportUIState.ActiveToolPane previousPane = ViewportUIState.ActiveToolPane.NONE;

    // Part Transform form state
    private final ImFloat posX = new ImFloat(), posY = new ImFloat(), posZ = new ImFloat();
    private final ImFloat rotX = new ImFloat(), rotY = new ImFloat(), rotZ = new ImFloat();
    private final ImFloat sclX = new ImFloat(1), sclY = new ImFloat(1), sclZ = new ImFloat(1);

    public ToolPaneRenderer(ViewportUIState state, ViewportActions actions,
                            ViewportController viewport) {
        this.state = state;
        this.actions = actions;
        this.viewport = viewport;
    }

    /**
     * Render the pane overlaying the left edge of the viewport image.
     * Appears/disappears instantly.
     */
    public void render(float imageX, float imageY, float imageWidth, float imageHeight) {
        ViewportUIState.ActiveToolPane pane = state.getActiveToolPane();
        if (pane == ViewportUIState.ActiveToolPane.NONE) {
            previousPane = ViewportUIState.ActiveToolPane.NONE;
            return;
        }

        // Reset Add Part form when opening the pane fresh
        if (pane == ViewportUIState.ActiveToolPane.ADD_PART && previousPane != ViewportUIState.ActiveToolPane.ADD_PART) {
            addPartName.set("");
            selectedShapeIndex.set(0);
        }
        previousPane = pane;

        float paneWidth = (pane == ViewportUIState.ActiveToolPane.ADD_PART) ? ADD_PART_PANE_WIDTH : PANE_WIDTH;
        ImGui.setNextWindowPos(imageX, imageY);
        ImGui.setNextWindowSize(paneWidth, imageHeight);

        int flags = ImGuiWindowFlags.NoTitleBar
                | ImGuiWindowFlags.NoResize
                | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoCollapse
                | ImGuiWindowFlags.NoSavedSettings
                | ImGuiWindowFlags.NoFocusOnAppearing;

        // Use theme WindowBg with slight transparency
        ImVec4 winBg = ImGui.getStyle().getColor(ImGuiCol.WindowBg);
        ImGui.pushStyleColor(ImGuiCol.WindowBg, winBg.x, winBg.y, winBg.z, 0.96f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 10, 8);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 6, 5);

        if (ImGui.begin("##ToolPane", flags)) {
            renderHeader(pane);
            ImGui.separator();
            ImGui.spacing();

            switch (pane) {
                case CAMERA -> renderCameraPane();
                case RENDERING -> renderRenderingPane();
                case TRANSFORM -> renderTransformPane();
                case ADD_PART -> renderAddPartPane();
                case PART_TRANSFORM -> renderPartTransformPane();
                default -> {}
            }
        }
        ImGui.end();

        ImGui.popStyleVar(2);
        ImGui.popStyleColor();
    }

    // ========== Header ==========

    private void renderHeader(ViewportUIState.ActiveToolPane pane) {
        String title = switch (pane) {
            case CAMERA -> "Camera";
            case RENDERING -> "Rendering";
            case TRANSFORM -> "Transform";
            case ADD_PART -> "Add Part";
            case PART_TRANSFORM -> {
                var supplier = state.getSelectedPartSupplier();
                ModelPartDescriptor sel = supplier != null ? supplier.get() : null;
                yield sel != null ? sel.name() : "Part Transform";
            }
            case NONE -> "";
        };

        ImGui.textUnformatted(title);

        // Close button — draw-list X icon matching WindowTitleBar style
        ImGui.sameLine(ImGui.getContentRegionAvailX() - CLOSE_BTN_SIZE + 4);

        ImGui.pushStyleColor(ImGuiCol.Button, 0, 0, 0, 0);
        ImVec4 hoverBg = ImGui.getStyle().getColor(ImGuiCol.Text);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, hoverBg.x, hoverBg.y, hoverBg.z, 0.12f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, hoverBg.x, hoverBg.y, hoverBg.z, 0.20f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 0);

        // Position for the icon
        float btnScreenX = ImGui.getCursorScreenPosX();
        float btnScreenY = ImGui.getCursorScreenPosY();

        if (ImGui.button("##closePane", CLOSE_BTN_SIZE, CLOSE_BTN_SIZE)) {
            state.closeToolPane();
        }

        // Draw X icon
        ImVec4 textCol = ImGui.getStyle().getColor(ImGuiCol.Text);
        int iconColor = ImGui.isItemHovered()
                ? ImGui.getColorU32(1.0f, 0.4f, 0.4f, 1.0f)
                : ImGui.getColorU32(textCol.x, textCol.y, textCol.z, 0.6f);
        float cx = btnScreenX + CLOSE_BTN_SIZE * 0.5f;
        float cy = btnScreenY + CLOSE_BTN_SIZE * 0.5f;
        ImGui.getWindowDrawList().addLine(
                cx - ICON_HALF, cy - ICON_HALF,
                cx + ICON_HALF, cy + ICON_HALF,
                iconColor, ICON_STROKE);
        ImGui.getWindowDrawList().addLine(
                cx + ICON_HALF, cy - ICON_HALF,
                cx - ICON_HALF, cy + ICON_HALF,
                iconColor, ICON_STROKE);

        ImGui.popStyleVar();
        ImGui.popStyleColor(3);
    }

    // ========== Camera ==========

    private void renderCameraPane() {
        label("Position");

        labeledSlider("Distance", "##cam_dist", state.getCameraDistance().getData(),
                1.0f, 20.0f, "%.1f", actions::updateCameraDistance);

        labeledSlider("Pitch", "##cam_pitch", state.getCameraPitch().getData(),
                -89.0f, 89.0f, "%.1f\u00B0", actions::updateCameraPitch);

        labeledSlider("Yaw", "##cam_yaw", state.getCameraYaw().getData(),
                -180.0f, 180.0f, "%.1f\u00B0", actions::updateCameraYaw);

        ImGui.spacing();
        label("Field of View");

        labeledSlider("FOV", "##cam_fov", state.getCameraFOV().getData(),
                30.0f, 120.0f, "%.0f\u00B0", actions::updateCameraFOV);

        ImGui.spacing();
        ImGui.spacing();

        if (ImGui.button("Reset Camera", -1, 0)) {
            actions.resetCamera();
        }
    }

    // ========== Rendering ==========

    private void renderRenderingPane() {
        label("Render Mode");

        ImGui.setNextItemWidth(-1);
        if (ImGui.combo("##render_mode", state.getCurrentRenderModeIndex(), state.getRenderModes())) {
            actions.updateRenderMode();
        }
    }

    // ========== Transform ==========

    private void renderTransformPane() {
        boolean gizmoEnabled = viewport.isGizmoEnabled();
        com.openmason.main.systems.viewport.viewportRendering.gizmo.GizmoDisplayMode displayMode =
                viewport.getGizmoState().getDisplayMode();
        boolean isAutoMode = displayMode == com.openmason.main.systems.viewport.viewportRendering.gizmo.GizmoDisplayMode.AUTO_SHOW_ON_SELECT;

        if (isAutoMode) {
            // In auto mode, show disabled checkbox with "(Auto)" label
            ImGui.beginDisabled();
            ImGui.checkbox("Show Gizmo (Auto)", new ImBoolean(gizmoEnabled));
            ImGui.endDisabled();
        } else {
            if (ImGui.checkbox("Show Gizmo", new ImBoolean(gizmoEnabled))) {
                viewport.setGizmoEnabled(!gizmoEnabled);
            }
        }

        ImGui.spacing();
        label("Mode");

        GizmoState.Mode currentMode = viewport.getGizmoMode();

        float btnW = (ImGui.getContentRegionAvailX() - ImGui.getStyle().getItemSpacingX() * 2) / 3.0f;
        modeButton("Translate", "G", GizmoState.Mode.TRANSLATE, currentMode, btnW);
        ImGui.sameLine();
        modeButton("Rotate", "R", GizmoState.Mode.ROTATE, currentMode, btnW);
        ImGui.sameLine();
        modeButton("Scale", "S", GizmoState.Mode.SCALE, currentMode, btnW);

        ImGui.spacing();

        if (currentMode == GizmoState.Mode.SCALE) {
            boolean uniform = viewport.getGizmoUniformScaling();
            if (ImGui.checkbox("Uniform Scaling", uniform)) {
                actions.toggleUniformScaling();
            }
        }

        ImGui.spacing();

        ImGui.pushStyleColor(ImGuiCol.Text, 0.50f, 0.50f, 0.50f, 1.0f);
        String hint = switch (currentMode) {
            case TRANSLATE -> "Drag arrows to move along an axis.";
            case ROTATE -> "Drag rings to rotate around an axis.";
            case SCALE -> viewport.getGizmoUniformScaling()
                    ? "Drag any handle to scale uniformly."
                    : "Drag handles to scale per axis.";
        };
        ImGui.textWrapped(hint);
        ImGui.popStyleColor();
    }

    // ========== Part Transform ==========

    private void renderPartTransformPane() {
        var supplier = state.getSelectedPartSupplier();
        ModelPartDescriptor part = supplier != null ? supplier.get() : null;
        if (part == null) {
            ImGui.textDisabled("No part selected");
            return;
        }

        if (part.locked()) {
            ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 0.6f, 0.0f, 0.9f);
            ImGui.textUnformatted("Locked");
            ImGui.popStyleColor();
            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();
        }

        PartTransform t = part.transform();

        // Sync ImFloat values
        posX.set(t.position().x); posY.set(t.position().y); posZ.set(t.position().z);
        rotX.set(t.rotation().x); rotY.set(t.rotation().y); rotZ.set(t.rotation().z);
        sclX.set(t.scale().x);   sclY.set(t.scale().y);   sclZ.set(t.scale().z);

        boolean changed = false;

        changed |= renderTransformGroup("Position", "pos", posX, posY, posZ, 0.01f, "%.3f");
        ImGui.spacing();
        ImGui.spacing();
        changed |= renderTransformGroup("Rotation", "rot", rotX, rotY, rotZ, 0.5f, "%.1f");
        ImGui.spacing();
        ImGui.spacing();
        changed |= renderTransformGroup("Scale", "scl", sclX, sclY, sclZ, 0.01f, "%.3f");

        ImGui.spacing();
        ImGui.spacing();
        ImGui.spacing();

        // Reset button
        ImVec4 dimCol = ImGui.getStyle().getColor(ImGuiCol.TextDisabled);
        ImGui.pushStyleColor(ImGuiCol.Button, dimCol.x, dimCol.y, dimCol.z, 0.15f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, dimCol.x, dimCol.y, dimCol.z, 0.30f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, dimCol.x, dimCol.y, dimCol.z, 0.45f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 3.0f);
        if (ImGui.button("Reset", -1, 0)) {
            posX.set(0); posY.set(0); posZ.set(0);
            rotX.set(0); rotY.set(0); rotZ.set(0);
            sclX.set(1); sclY.set(1); sclZ.set(1);
            changed = true;
        }
        ImGui.popStyleVar();
        ImGui.popStyleColor(3);

        // Apply changes
        if (changed && !part.locked()) {
            BiConsumer<String, PartTransform> applier = state.getApplyPartTransform();
            if (applier != null) {
                PartTransform updated = new PartTransform(
                        new Vector3f(t.origin()),
                        new Vector3f(posX.get(), posY.get(), posZ.get()),
                        new Vector3f(rotX.get(), rotY.get(), rotZ.get()),
                        new Vector3f(sclX.get(), sclY.get(), sclZ.get())
                );
                applier.accept(part.id(), updated);
            }
            Runnable invalidator = state.getPartTransformInvalidator();
            if (invalidator != null) {
                invalidator.run();
            }
        }
    }

    /**
     * Render a labeled transform group (label + 3 axis rows stacked vertically).
     * Each axis gets its own row with colored label pill + full-width drag float.
     */
    private boolean renderTransformGroup(String groupLabel, String id,
                                          ImFloat x, ImFloat y, ImFloat z,
                                          float speed, String format) {
        boolean changed = false;
        label(groupLabel);

        changed |= renderAxisField("X", id + "x", x, speed, format, 0.85f, 0.25f, 0.25f);
        changed |= renderAxisField("Y", id + "y", y, speed, format, 0.25f, 0.72f, 0.25f);
        changed |= renderAxisField("Z", id + "z", z, speed, format, 0.25f, 0.45f, 0.90f);

        return changed;
    }

    /**
     * Render a single axis field: colored label pill + drag float filling remaining width.
     */
    private boolean renderAxisField(String axisLabel, String id, ImFloat value,
                                     float speed, String format,
                                     float colorR, float colorG, float colorB) {
        boolean changed = false;
        float pillWidth = 18.0f;
        float pillHeight = ImGui.getFrameHeight();
        float spacing = 4.0f;
        float fieldWidth = ImGui.getContentRegionAvailX() - pillWidth - spacing;

        ImDrawList drawList = ImGui.getWindowDrawList();
        ImVec2 cursor = ImGui.getCursorScreenPos();

        // Draw colored pill background
        int pillColor = ImGui.colorConvertFloat4ToU32(colorR, colorG, colorB, 0.25f);
        int textColor = ImGui.colorConvertFloat4ToU32(colorR, colorG, colorB, 1.0f);
        drawList.addRectFilled(cursor.x, cursor.y,
                cursor.x + pillWidth, cursor.y + pillHeight, pillColor, 3.0f);

        // Center axis letter in pill
        ImVec2 textSize = new ImVec2();
        ImGui.calcTextSize(textSize, axisLabel);
        float textX = cursor.x + (pillWidth - textSize.x) * 0.5f;
        float textY = cursor.y + (pillHeight - textSize.y) * 0.5f;
        drawList.addText(textX, textY, textColor, axisLabel);

        // Advance past pill
        ImGui.dummy(pillWidth, pillHeight);
        ImGui.sameLine(0, spacing);

        // Drag float
        ImGui.pushItemWidth(fieldWidth);
        if (ImGui.dragFloat("##pt_" + id, value.getData(), speed, 0, 0, format)) {
            changed = true;
        }
        ImGui.popItemWidth();

        return changed;
    }

    // ========== Add Part ==========

    private void renderAddPartPane() {
        ImDrawList drawList = ImGui.getWindowDrawList();
        float cardWidth = ImGui.getContentRegionAvailX();

        // Shape selection
        for (int i = 0; i < shapes.length; i++) {
            PartShapeFactory.Shape shape = shapes[i];
            boolean isSelected = (selectedShapeIndex.get() == i);

            ImGui.pushID(i);

            ImVec2 cardPos = ImGui.getCursorScreenPos();
            float iconAreaSize = 40.0f;
            float cardPad = 10.0f;
            float textX = cardPos.x + iconAreaSize + cardPad + 6;
            float textAreaWidth = cardWidth - iconAreaSize - cardPad - 12;
            float rounding = 5.0f;

            // Measure wrapped description height to size card dynamically
            ImVec2 descSize = new ImVec2();
            ImGui.calcTextSize(descSize, shape.getDescription(), false, textAreaWidth);
            float cardHeight = Math.max(iconAreaSize + cardPad * 2, cardPad + 16 + descSize.y + cardPad + 4);

            // Card background
            ImVec4 accent = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
            if (isSelected) {
                int bgColor = ImGui.colorConvertFloat4ToU32(accent.x, accent.y, accent.z, 0.20f);
                int borderColor = ImGui.colorConvertFloat4ToU32(accent.x, accent.y, accent.z, 0.70f);
                drawList.addRectFilled(cardPos.x, cardPos.y,
                        cardPos.x + cardWidth, cardPos.y + cardHeight, bgColor, rounding);
                drawList.addRect(cardPos.x, cardPos.y,
                        cardPos.x + cardWidth, cardPos.y + cardHeight, borderColor, rounding, 0, 1.2f);
                // Left accent bar
                drawList.addRectFilled(cardPos.x, cardPos.y + 4,
                        cardPos.x + 3, cardPos.y + cardHeight - 4,
                        ImGui.colorConvertFloat4ToU32(accent.x, accent.y, accent.z, 0.90f), 1.0f);
            } else {
                ImVec4 frameBg = ImGui.getStyle().getColor(ImGuiCol.FrameBg);
                int bgColor = ImGui.colorConvertFloat4ToU32(frameBg.x, frameBg.y, frameBg.z, 0.30f);
                drawList.addRectFilled(cardPos.x, cardPos.y,
                        cardPos.x + cardWidth, cardPos.y + cardHeight, bgColor, rounding);
            }

            // Click detection
            if (ImGui.invisibleButton("##shape_card", cardWidth, cardHeight)) {
                selectedShapeIndex.set(i);
            }

            // Hover
            if (ImGui.isItemHovered() && !isSelected) {
                ImVec4 hoverBg = ImGui.getStyle().getColor(ImGuiCol.HeaderHovered);
                drawList.addRectFilled(cardPos.x, cardPos.y,
                        cardPos.x + cardWidth, cardPos.y + cardHeight,
                        ImGui.colorConvertFloat4ToU32(hoverBg.x, hoverBg.y, hoverBg.z, 0.12f), rounding);
            }

            // Shape icon
            float iconCx = cardPos.x + cardPad + iconAreaSize * 0.5f;
            float iconCy = cardPos.y + cardHeight * 0.5f;
            int iconColor = isSelected
                    ? ImGui.colorConvertFloat4ToU32(accent.x, accent.y, accent.z, 0.90f)
                    : ImGui.colorConvertFloat4ToU32(
                            ImGui.getStyle().getColor(ImGuiCol.Text).x,
                            ImGui.getStyle().getColor(ImGuiCol.Text).y,
                            ImGui.getStyle().getColor(ImGuiCol.Text).z, 0.50f);
            drawShapeIcon(drawList, shape, iconCx, iconCy, 14.0f, iconColor);

            // Shape name
            ImVec4 textCol = ImGui.getStyle().getColor(ImGuiCol.Text);
            float nameAlpha = isSelected ? 1.0f : 0.85f;
            int nameColor = ImGui.colorConvertFloat4ToU32(textCol.x, textCol.y, textCol.z, nameAlpha);
            drawList.addText(textX, cardPos.y + cardPad, nameColor, shape.getDisplayName());

            // Description (wrapped manually via ImGui calcTextSize)
            ImVec4 dimCol = ImGui.getStyle().getColor(ImGuiCol.TextDisabled);
            int descColor = ImGui.colorConvertFloat4ToU32(dimCol.x, dimCol.y, dimCol.z, dimCol.w);
            drawWrappedText(drawList, textX, cardPos.y + cardPad + 17, textAreaWidth,
                    shape.getDescription(), descColor);

            ImGui.popID();

            ImGui.dummy(0, 3);
        }

        ImGui.spacing();
        ImGui.spacing();

        // Name input
        label("Name (optional)");
        ImGui.setNextItemWidth(-1);
        ImGui.inputText("##add_part_name", addPartName);
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Leave blank to use shape name");
        }

        ImGui.spacing();
        ImGui.spacing();

        // Add button
        ImVec4 accent = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
        ImGui.pushStyleColor(ImGuiCol.Button, accent.x, accent.y, accent.z, 0.65f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, accent.x, accent.y, accent.z, 0.85f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, accent.x, accent.y, accent.z, 1.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 4.0f);

        if (ImGui.button("Add Part", -1, 30)) {
            PartShapeFactory.Shape shape = shapes[selectedShapeIndex.get()];
            String name = addPartName.get().trim();
            if (name.isEmpty()) {
                name = shape.getDisplayName();
            }

            BiConsumer<String, String> callback = state.getAddPartCallback();
            if (callback != null) {
                callback.accept(shape.name(), name);
            }

            state.closeToolPane();
        }

        ImGui.popStyleVar();
        ImGui.popStyleColor(3);
    }

    /**
     * Draw a simple wireframe icon representing a shape.
     */
    private void drawShapeIcon(ImDrawList dl, PartShapeFactory.Shape shape, float cx, float cy,
                               float size, int color) {
        float s = size;
        float thick = 1.4f;

        switch (shape) {
            case CUBE -> {
                // Isometric cube wireframe
                float dx = s * 0.85f, dy = s * 0.5f, dz = s * 0.6f;
                // Front face
                float fx0 = cx - dx * 0.5f, fy0 = cy + dy * 0.3f;
                float fx1 = cx + dx * 0.5f, fy1 = cy + dy * 0.3f;
                float fx2 = cx + dx * 0.5f, fy2 = cy - dz + dy * 0.3f;
                float fx3 = cx - dx * 0.5f, fy3 = cy - dz + dy * 0.3f;
                dl.addLine(fx0, fy0, fx1, fy1, color, thick);
                dl.addLine(fx1, fy1, fx2, fy2, color, thick);
                dl.addLine(fx2, fy2, fx3, fy3, color, thick);
                dl.addLine(fx3, fy3, fx0, fy0, color, thick);
                // Top face
                float off = dy * 0.6f;
                dl.addLine(fx3, fy3, fx3 + off * 0.7f, fy3 - off, color, thick);
                dl.addLine(fx2, fy2, fx2 + off * 0.7f, fy2 - off, color, thick);
                dl.addLine(fx3 + off * 0.7f, fy3 - off, fx2 + off * 0.7f, fy2 - off, color, thick);
                // Right face
                dl.addLine(fx1, fy1, fx1 + off * 0.7f, fy1 - off, color, thick);
                dl.addLine(fx1 + off * 0.7f, fy1 - off, fx2 + off * 0.7f, fy2 - off, color, thick);
            }
            case PYRAMID -> {
                // Pyramid: base quad + apex
                float bw = s * 0.7f, bh = s * 0.35f;
                float baseY = cy + s * 0.4f;
                float apexY = cy - s * 0.55f;
                // Base (diamond shape for perspective)
                dl.addLine(cx, baseY - bh, cx + bw, baseY, color, thick);
                dl.addLine(cx + bw, baseY, cx, baseY + bh, color, thick);
                dl.addLine(cx, baseY + bh, cx - bw, baseY, color, thick);
                dl.addLine(cx - bw, baseY, cx, baseY - bh, color, thick);
                // Edges to apex
                dl.addLine(cx, baseY - bh, cx, apexY, color, thick);
                dl.addLine(cx + bw, baseY, cx, apexY, color, thick);
                dl.addLine(cx, baseY + bh, cx, apexY, color, thick);
                dl.addLine(cx - bw, baseY, cx, apexY, color, thick);
            }
            case PANE -> {
                // Flat plane (angled rectangle)
                float pw = s * 0.9f, ph = s * 0.6f;
                float skew = s * 0.25f;
                dl.addLine(cx - pw * 0.5f + skew, cy - ph * 0.5f,
                        cx + pw * 0.5f + skew, cy - ph * 0.5f, color, thick);
                dl.addLine(cx + pw * 0.5f + skew, cy - ph * 0.5f,
                        cx + pw * 0.5f - skew, cy + ph * 0.5f, color, thick);
                dl.addLine(cx + pw * 0.5f - skew, cy + ph * 0.5f,
                        cx - pw * 0.5f - skew, cy + ph * 0.5f, color, thick);
                dl.addLine(cx - pw * 0.5f - skew, cy + ph * 0.5f,
                        cx - pw * 0.5f + skew, cy - ph * 0.5f, color, thick);
                // Diagonal line to suggest flat surface
                dl.addLine(cx - pw * 0.5f + skew, cy - ph * 0.5f,
                        cx + pw * 0.5f - skew, cy + ph * 0.5f, color, thick * 0.6f);
            }
            case SPRITE -> {
                // Billboard: upright rectangle with a cross through it
                float sw = s * 0.55f, sh = s * 0.75f;
                dl.addRect(cx - sw, cy - sh * 0.5f, cx + sw, cy + sh * 0.5f, color, 0, 0, thick);
                // Cross to indicate single-sided / billboard
                dl.addLine(cx - sw, cy - sh * 0.5f, cx + sw, cy + sh * 0.5f, color, thick * 0.5f);
                dl.addLine(cx + sw, cy - sh * 0.5f, cx - sw, cy + sh * 0.5f, color, thick * 0.5f);
            }
            case CYLINDER -> {
                float rw = s * 0.55f, rh = s * 0.18f;
                float topY = cy - s * 0.5f, botY = cy + s * 0.5f;
                drawEllipse(dl, cx, topY, rw, rh, color, thick);
                drawEllipseArc(dl, cx, botY, rw, rh, 0, (float) Math.PI, color, thick);
                dl.addLine(cx - rw, topY, cx - rw, botY, color, thick);
                dl.addLine(cx + rw, topY, cx + rw, botY, color, thick);
            }
            case CONE -> {
                float rw = s * 0.6f, rh = s * 0.18f;
                float baseY = cy + s * 0.45f;
                float apexY = cy - s * 0.55f;
                dl.addLine(cx - rw, baseY, cx, apexY, color, thick);
                dl.addLine(cx + rw, baseY, cx, apexY, color, thick);
                drawEllipseArc(dl, cx, baseY, rw, rh, 0, (float) Math.PI, color, thick);
                drawEllipseArc(dl, cx, baseY, rw, rh, (float) Math.PI, (float) (2 * Math.PI), color, (thick * 0.5f));
            }
            case SPHERE -> {
                float r = s * 0.6f;
                dl.addCircle(cx, cy, r, color, 24, thick);
                drawEllipse(dl, cx, cy, r, r * 0.35f, color, thick * 0.6f);
                drawEllipse(dl, cx, cy, r * 0.35f, r, color, thick * 0.6f);
            }
            case HEMISPHERE -> {
                float r = s * 0.6f, rh = s * 0.18f;
                float baseY = cy + s * 0.25f;
                drawEllipseArc(dl, cx, baseY, r, r, (float) Math.PI, (float) (2 * Math.PI), color, thick);
                drawEllipse(dl, cx, baseY, r, rh, color, thick);
            }
            case WEDGE -> {
                float w = s * 0.75f, h = s * 0.7f, dz = s * 0.25f;
                float fx0 = cx - w * 0.5f, fy0 = cy + h * 0.5f;
                float fx1 = cx + w * 0.5f, fy1 = cy + h * 0.5f;
                float fx2 = cx - w * 0.5f, fy2 = cy - h * 0.5f;
                // Front triangle
                dl.addLine(fx0, fy0, fx1, fy1, color, thick);
                dl.addLine(fx0, fy0, fx2, fy2, color, thick);
                dl.addLine(fx2, fy2, fx1, fy1, color, thick);
                // Depth offset (back triangle's visible edges)
                dl.addLine(fx0, fy0, fx0 + dz, fy0 - dz, color, thick);
                dl.addLine(fx1, fy1, fx1 + dz, fy1 - dz, color, thick);
                dl.addLine(fx0 + dz, fy0 - dz, fx1 + dz, fy1 - dz, color, thick);
            }
            case TORUS -> {
                float ow = s * 0.7f, oh = s * 0.28f;
                float iw = s * 0.28f, ih = s * 0.11f;
                drawEllipse(dl, cx, cy, ow, oh, color, thick);
                drawEllipse(dl, cx, cy, iw, ih, color, thick);
            }
            case CROSS -> {
                float w = s * 0.7f, h = s * 0.7f;
                dl.addLine(cx - w, cy - h, cx + w, cy + h, color, thick);
                dl.addLine(cx + w, cy - h, cx - w, cy + h, color, thick);
                // Thin perpendicular caps to suggest two panes
                dl.addLine(cx - w, cy - h, cx - w + 4, cy - h, color, thick * 0.6f);
                dl.addLine(cx + w, cy + h, cx + w - 4, cy + h, color, thick * 0.6f);
                dl.addLine(cx + w, cy - h, cx + w - 4, cy - h, color, thick * 0.6f);
                dl.addLine(cx - w, cy + h, cx - w + 4, cy + h, color, thick * 0.6f);
            }
        }
    }

    /** Draw a full ellipse outline by polyline approximation. */
    private void drawEllipse(ImDrawList dl, float cx, float cy, float rx, float ry,
                              int color, float thick) {
        drawEllipseArc(dl, cx, cy, rx, ry, 0, (float) (2 * Math.PI), color, thick);
    }

    /** Draw an elliptical arc from {@code start} to {@code end} radians. */
    private void drawEllipseArc(ImDrawList dl, float cx, float cy, float rx, float ry,
                                 float start, float end, int color, float thick) {
        int segments = 24;
        float step = (end - start) / segments;
        float px = cx + rx * (float) Math.cos(start);
        float py = cy + ry * (float) Math.sin(start);
        for (int i = 1; i <= segments; i++) {
            float a = start + step * i;
            float nx = cx + rx * (float) Math.cos(a);
            float ny = cy + ry * (float) Math.sin(a);
            dl.addLine(px, py, nx, ny, color, thick);
            px = nx;
            py = ny;
        }
    }

    /**
     * Draw text with manual word wrapping using the draw list.
     */
    private void drawWrappedText(ImDrawList dl, float x, float y, float wrapWidth,
                                  String text, int color) {
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        float lineY = y;
        float lineHeight = ImGui.getTextLineHeight();

        for (String word : words) {
            String testLine = line.isEmpty() ? word : line + " " + word;
            ImVec2 testSize = new ImVec2();
            ImGui.calcTextSize(testSize, testLine);

            if (testSize.x > wrapWidth && !line.isEmpty()) {
                dl.addText(x, lineY, color, line.toString());
                lineY += lineHeight + 1;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(testLine);
            }
        }
        if (!line.isEmpty()) {
            dl.addText(x, lineY, color, line.toString());
        }
    }

    // ========== Helpers ==========

    /** Dimmed section label using theme TextDisabled color. */
    private void label(String text) {
        ImVec4 col = ImGui.getStyle().getColor(ImGuiCol.TextDisabled);
        ImGui.pushStyleColor(ImGuiCol.Text, col.x, col.y, col.z, col.w);
        ImGui.textUnformatted(text);
        ImGui.popStyleColor();
        ImGui.spacing();
    }

    /** Label + full-width slider. */
    private void labeledSlider(String label, String id, float[] data,
                               float min, float max, String fmt, Runnable onChange) {
        ImGui.textUnformatted(label);
        ImGui.setNextItemWidth(-1);
        if (ImGui.sliderFloat(id, data, min, max, fmt)) {
            onChange.run();
        }
        ImGui.spacing();
    }

    /** Transform mode toggle button with theme-aware active highlight. */
    private void modeButton(String label, String shortcut, GizmoState.Mode mode,
                            GizmoState.Mode current, float width) {
        boolean active = (current == mode);
        if (active) {
            ImVec4 accent = ImGui.getStyle().getColor(ImGuiCol.HeaderActive);
            ImGui.pushStyleColor(ImGuiCol.Button, accent.x, accent.y, accent.z, 0.80f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, accent.x, accent.y, accent.z, 1.0f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, accent.x, accent.y, accent.z, 0.65f);
        }
        if (ImGui.button(label, width, 0)) {
            actions.setGizmoMode(mode);
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(label + " (" + shortcut + ")");
        }
        if (active) {
            ImGui.popStyleColor(3);
        }
    }

}
