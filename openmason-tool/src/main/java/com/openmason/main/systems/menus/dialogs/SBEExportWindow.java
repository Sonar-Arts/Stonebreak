package com.openmason.main.systems.menus.dialogs;

import com.openmason.main.systems.menus.windows.WindowTitleBar;
import com.openmason.engine.format.sbe.AnimationCompatibility;
import com.openmason.engine.format.sbe.SBEFormat;
import com.openmason.engine.format.sbe.SBESerializer;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.stateHandling.ModelState;
import com.openmason.main.systems.themes.core.ThemeDefinition;
import com.openmason.main.systems.themes.core.ThemeManager;
import com.openmason.main.systems.themes.utils.ImGuiComponents;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.ImVec4;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Export window for creating Stonebreak Entity (.SBE) files.
 *
 * <p>Provides a form-based UI for entering SBE metadata (Object ID, Name,
 * Entity Type, Pack, Author, Description) and triggering the export. Styled
 * consistently with the SBO export window and the Preferences window.
 */
public class SBEExportWindow {

    private static final Logger logger = LoggerFactory.getLogger(SBEExportWindow.class);

    private static final String WINDOW_TITLE = "Export SBE";
    private static final float MIN_WINDOW_WIDTH = 560.0f;
    private static final float MIN_WINDOW_HEIGHT = 530.0f;
    private static final float FOOTER_HEIGHT = 44.0f;
    private static final float CONTENT_PADDING_Y = 22.0f;
    private static final float LABEL_WIDTH = 100.0f;
    private static final float INPUT_WIDTH = 360.0f;
    private static final float FORM_WIDTH = LABEL_WIDTH + INPUT_WIDTH;
    private static final float DESCRIPTION_HEIGHT = 76.0f;
    private static final float ROW_SPACING = 6.0f;
    private static final float MIN_SIDE_PADDING = 28.0f;
    private static final float BTN_ROUNDING = 8.0f;

    private final ImBoolean visible;
    private final ThemeManager themeManager;
    private final ModelState modelState;
    private final StatusService statusService;
    private final FileDialogService fileDialogService;
    private final SBESerializer serializer;
    private final WindowTitleBar titleBar;

    // Input field buffers
    private final ImString objectId = new ImString(256);
    private final ImString objectName = new ImString(256);
    private final ImInt entityTypeIndex = new ImInt(0);
    private final ImString objectPack = new ImString(256);
    private final ImString author = new ImString(256);
    private final ImString description = new ImString(1024);

    /** State rows the user has staged for embedding. */
    private final List<StateBindingRow> stateBindings = new ArrayList<>();

    /** Variant rows the user has staged for embedding. */
    private final List<VariantBindingRow> variantBindings = new ArrayList<>();

    /**
     * One row in the state bindings list. A state may declare an optional
     * model override and an optional animation clip — neither, either, or both.
     */
    private static final class StateBindingRow {
        final ImString state = new ImString(64);
        Path modelOverridePath;
        Path clipPath;

        StateBindingRow(String initialState) {
            this.state.set(initialState != null ? initialState : "");
        }
    }

    /**
     * One row in the variant bindings list. A variant may declare an optional
     * OMO override; without one the variant resolves to the base OMO at runtime.
     */
    private static final class VariantBindingRow {
        final ImString variant = new ImString(64);
        Path modelOverridePath;

        VariantBindingRow(String initialName) {
            this.variant.set(initialName != null ? initialName : "");
        }
    }

    // Entity type labels for the combo box
    private static final String[] ENTITY_TYPE_LABELS = {
            "Mob", "NPC", "Projectile", "Vehicle", "Other"
    };

    /** Popup listing already-registered SBE object ids. */
    private final SBEObjectIndexPopup objectIndexPopup = new SBEObjectIndexPopup();

    private String validationMessage = "";
    private boolean iniFileSet = false;

    // Computed each frame — left X offset to center form content
    private float formOffsetX = MIN_SIDE_PADDING;

    /**
     * Creates a new SBE export window.
     */
    public SBEExportWindow(ImBoolean visible,
                           ThemeManager themeManager,
                           ModelState modelState,
                           StatusService statusService,
                           FileDialogService fileDialogService) {
        this.visible = visible;
        this.themeManager = themeManager;
        this.modelState = modelState;
        this.statusService = statusService;
        this.fileDialogService = fileDialogService;
        this.serializer = new SBESerializer();
        this.titleBar = new WindowTitleBar(WINDOW_TITLE, true, false);

        objectPack.set("default");
    }

    public void show() {
        visible.set(true);
        prepopulateFromModel();
        validationMessage = "";
        logger.debug("SBE export window shown");
    }

    public void hide() {
        visible.set(false);
    }

    public boolean isVisible() {
        return visible.get();
    }

    public void render() {
        if (!visible.get()) {
            return;
        }

        if (!iniFileSet) {
            ImGui.setNextWindowSize(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT);

            float screenW = ImGui.getMainViewport().getSizeX();
            float screenH = ImGui.getMainViewport().getSizeY();
            ImGui.setNextWindowPos(
                    (screenW - MIN_WINDOW_WIDTH) * 0.5f,
                    (screenH - MIN_WINDOW_HEIGHT) * 0.5f
            );
            iniFileSet = true;
        }

        ImGui.setNextWindowSizeConstraints(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT, Float.MAX_VALUE, Float.MAX_VALUE);

        int windowFlags = ImGuiWindowFlags.NoBringToFrontOnFocus
                | ImGuiWindowFlags.NoDocking
                | ImGuiWindowFlags.NoTitleBar
                | ImGuiWindowFlags.NoCollapse
                | ImGuiWindowFlags.NoScrollbar;

        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0.0f, 0.0f);

        if (ImGui.begin(WINDOW_TITLE, visible, windowFlags)) {
            try {
                WindowTitleBar.Result result = titleBar.render();
                if (result.minimizeClicked() || result.closeClicked()) {
                    visible.set(false);
                }
                renderContent();
                objectIndexPopup.render();
            } catch (Exception e) {
                logger.error("Error rendering SBE export window", e);
                ImGui.textColored(1.0f, 0.0f, 0.0f, 1.0f, "Error rendering export window");
            }
        }
        ImGui.end();
        ImGui.popStyleVar();
    }

    private void renderContent() {
        float windowWidth = ImGui.getContentRegionAvailX();
        float windowHeight = ImGui.getContentRegionAvailY();
        float contentHeight = windowHeight - FOOTER_HEIGHT;

        formOffsetX = Math.max(MIN_SIDE_PADDING, (windowWidth - FORM_WIDTH) * 0.5f);

        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 8.0f, CONTENT_PADDING_Y);
        ImGui.beginChild("##SBEContent", windowWidth, contentHeight, false);

        renderFormFields();

        ImGui.endChild();
        ImGui.popStyleVar();

        renderFooter(windowWidth);
    }

    // ========================================
    // Form Field Rendering
    // ========================================

    private void renderFormFields() {
        // Section: Entity Identity
        ImGui.setCursorPosX(formOffsetX);
        ImGuiComponents.renderCompactSectionHeader("Entity Identity");
        ImGui.spacing();

        renderLabeledInput("Object ID", "sbe_obj_id", objectId, "e.g. stonebreak:cow");
        ImGui.setCursorPosX(formOffsetX + LABEL_WIDTH);
        if (ImGui.smallButton("Registered IDs...##sbe_show_ids")) {
            objectIndexPopup.open();
        }
        ImGui.dummy(0, ROW_SPACING);
        renderLabeledInput("Object Name", "sbe_obj_name", objectName, "e.g. Cow");

        ImGui.dummy(0, ROW_SPACING);

        // Section: Entity Classification
        ImGui.setCursorPosX(formOffsetX);
        ImGuiComponents.renderCompactSectionHeader("Entity Classification");
        ImGui.spacing();

        renderLabeledCombo("Entity Type", "sbe_ent_type", entityTypeIndex, ENTITY_TYPE_LABELS);
        renderLabeledInput("Object Pack", "sbe_obj_pack", objectPack, "e.g. default, expansion_1");

        ImGui.dummy(0, ROW_SPACING);

        // Section: Attribution
        ImGui.setCursorPosX(formOffsetX);
        ImGuiComponents.renderCompactSectionHeader("Attribution");
        ImGui.spacing();

        renderLabeledInput("Author", "sbe_author", author, "Creator name or studio");

        ImGui.dummy(0, 2);

        ImGui.setCursorPosX(formOffsetX);
        ImGui.textDisabled("Description");
        ImGui.dummy(0, 2);
        ImGui.setCursorPosX(formOffsetX);
        ImGui.pushItemWidth(INPUT_WIDTH);
        ImGui.inputTextMultiline("##sbe_desc", description, INPUT_WIDTH, DESCRIPTION_HEIGHT,
                ImGuiInputTextFlags.AllowTabInput);
        ImGui.popItemWidth();

        ImGui.dummy(0, ROW_SPACING);

        // Section: States (Optional)
        ImGui.setCursorPosX(formOffsetX);
        ImGuiComponents.renderCompactSectionHeader("States (Optional)");
        ImGui.spacing();

        renderStateList();

        ImGui.dummy(0, ROW_SPACING);

        // Section: Variants (Optional)
        ImGui.setCursorPosX(formOffsetX);
        ImGuiComponents.renderCompactSectionHeader("Texture Variants (Optional)");
        ImGui.spacing();

        renderVariantList();

        if (!validationMessage.isEmpty()) {
            ImGui.dummy(0, ROW_SPACING);
            renderValidationBanner(validationMessage);
        }
    }

    private void renderStateList() {
        float fullWidth = LABEL_WIDTH + INPUT_WIDTH;
        float stateInputWidth = 160.0f;
        float removeBtnWidth = 22.0f;
        float indent = 20.0f;
        float slotLabelWidth = 50.0f;
        float slotButtonWidth = 70.0f;

        if (stateBindings.isEmpty()) {
            ImGui.setCursorPosX(formOffsetX);
            ImGui.textDisabled("No states declared.");
            ImGui.dummy(0, ROW_SPACING);
        } else {
            int removeIndex = -1;
            for (int i = 0; i < stateBindings.size(); i++) {
                StateBindingRow row = stateBindings.get(i);
                ImGui.pushID("sbe_state_row_" + i);

                ImGui.setCursorPosX(formOffsetX);
                ImGui.pushItemWidth(stateInputWidth);
                ImGui.inputTextWithHint("##state_name", "e.g. idle", row.state);
                ImGui.popItemWidth();

                ImGui.sameLine(formOffsetX + fullWidth - removeBtnWidth);
                if (ImGui.button("x##remove", removeBtnWidth, 0)) {
                    removeIndex = i;
                }

                // Slot 1: model override
                ImGui.setCursorPosX(formOffsetX + indent);
                ImGui.textDisabled("Model:");
                ImGui.sameLine(formOffsetX + indent + slotLabelWidth);
                renderAssetSlot(
                        row.modelOverridePath,
                        "(use base OMO)",
                        slotButtonWidth,
                        () -> fileDialogService.showOpenOMODialog(p -> {
                            if (p != null && !p.isBlank()) row.modelOverridePath = Path.of(p);
                        }),
                        () -> row.modelOverridePath = null
                );

                // Slot 2: animation clip
                ImGui.setCursorPosX(formOffsetX + indent);
                ImGui.textDisabled("Clip:");
                ImGui.sameLine(formOffsetX + indent + slotLabelWidth);
                renderAssetSlot(
                        row.clipPath,
                        "(no animation)",
                        slotButtonWidth,
                        () -> fileDialogService.showOpenOMADialog(p -> {
                            if (p != null && !p.isBlank()) row.clipPath = Path.of(p);
                        }),
                        () -> row.clipPath = null
                );

                ImGui.dummy(0, 4);
                ImGui.popID();
            }
            if (removeIndex >= 0) {
                stateBindings.remove(removeIndex);
            }
        }

        ImGui.setCursorPosX(formOffsetX);
        if (ImGui.button("+ Add State##sbe_state_add", 120.0f, 0)) {
            stateBindings.add(new StateBindingRow(""));
        }

        ImGui.dummy(0, ROW_SPACING);
    }

    private void renderVariantList() {
        float fullWidth = LABEL_WIDTH + INPUT_WIDTH;
        float variantInputWidth = 160.0f;
        float removeBtnWidth = 22.0f;
        float indent = 20.0f;
        float slotLabelWidth = 50.0f;
        float slotButtonWidth = 70.0f;

        if (variantBindings.isEmpty()) {
            ImGui.setCursorPosX(formOffsetX);
            ImGui.textDisabled("No variants declared.");
            ImGui.dummy(0, ROW_SPACING);
        } else {
            int removeIndex = -1;
            for (int i = 0; i < variantBindings.size(); i++) {
                VariantBindingRow row = variantBindings.get(i);
                ImGui.pushID("sbe_variant_row_" + i);

                ImGui.setCursorPosX(formOffsetX);
                ImGui.pushItemWidth(variantInputWidth);
                ImGui.inputTextWithHint("##variant_name", "e.g. angus", row.variant);
                ImGui.popItemWidth();

                ImGui.sameLine(formOffsetX + fullWidth - removeBtnWidth);
                if (ImGui.button("x##remove_variant", removeBtnWidth, 0)) {
                    removeIndex = i;
                }

                ImGui.setCursorPosX(formOffsetX + indent);
                ImGui.textDisabled("Model:");
                ImGui.sameLine(formOffsetX + indent + slotLabelWidth);
                renderAssetSlot(
                        row.modelOverridePath,
                        "(use base OMO)",
                        slotButtonWidth,
                        () -> fileDialogService.showOpenOMODialog(p -> {
                            if (p != null && !p.isBlank()) row.modelOverridePath = Path.of(p);
                        }),
                        () -> row.modelOverridePath = null
                );

                ImGui.dummy(0, 4);
                ImGui.popID();
            }
            if (removeIndex >= 0) {
                variantBindings.remove(removeIndex);
            }
        }

        ImGui.setCursorPosX(formOffsetX);
        if (ImGui.button("+ Add Variant##sbe_variant_add", 120.0f, 0)) {
            variantBindings.add(new VariantBindingRow(""));
        }

        ImGui.dummy(0, ROW_SPACING);
    }

    private void renderAssetSlot(Path current, String emptyHint, float buttonWidth,
                                  Runnable onPick, Runnable onClear) {
        if (current != null) {
            ImGui.text(current.getFileName().toString());
            if (ImGui.isItemHovered()) ImGui.setTooltip(current.toString());
            ImGui.sameLine();
            if (ImGui.smallButton("Replace...")) onPick.run();
            ImGui.sameLine();
            if (ImGui.smallButton("Clear")) onClear.run();
        } else {
            ImGui.textDisabled(emptyHint);
            ImGui.sameLine();
            if (ImGui.button("Set...", buttonWidth, 0)) onPick.run();
        }
    }

    private void renderLabeledInput(String label, String id, ImString buffer, String hint) {
        ImGui.setCursorPosX(formOffsetX);
        ImGui.textDisabled(label);
        ImGui.sameLine(formOffsetX + LABEL_WIDTH);
        ImGui.pushItemWidth(INPUT_WIDTH);
        ImGui.inputTextWithHint("##" + id, hint, buffer);
        ImGui.popItemWidth();

        ImGui.dummy(0, ROW_SPACING);
    }

    private void renderLabeledCombo(String label, String id, ImInt selected, String[] items) {
        ImGui.setCursorPosX(formOffsetX);
        ImGui.textDisabled(label);
        ImGui.sameLine(formOffsetX + LABEL_WIDTH);
        ImGui.pushItemWidth(INPUT_WIDTH);
        ImGui.combo("##" + id, selected, items);
        ImGui.popItemWidth();

        ImGui.dummy(0, ROW_SPACING);
    }

    private void renderValidationBanner(String message) {
        ImDrawList drawList = ImGui.getWindowDrawList();
        ImVec2 pos = ImGui.getCursorScreenPos();
        float bannerWidth = LABEL_WIDTH + INPUT_WIDTH;

        float padding = 8.0f;
        ImVec2 textSize = ImGui.calcTextSize(message);
        float bannerHeight = textSize.y + padding * 2;

        drawList.addRectFilled(
                pos.x, pos.y,
                pos.x + bannerWidth, pos.y + bannerHeight,
                ImColor.rgba(0.8f, 0.2f, 0.2f, 0.15f), 4.0f
        );
        drawList.addRectFilled(
                pos.x, pos.y,
                pos.x + 3.0f, pos.y + bannerHeight,
                ImColor.rgba(0.9f, 0.3f, 0.3f, 0.8f), 2.0f
        );

        ImGui.setCursorScreenPos(pos.x + padding + 3.0f, pos.y + padding);
        ImGui.textColored(1.0f, 0.45f, 0.4f, 1.0f, message);
        ImGui.setCursorScreenPos(pos.x, pos.y + bannerHeight);
    }

    // ========================================
    // Footer
    // ========================================

    private void renderFooter(float windowWidth) {
        float buttonWidth = 90.0f;
        float buttonHeight = 26.0f;
        float buttonSpacing = 10.0f;
        float rightMargin = 18.0f;

        ImDrawList drawList = ImGui.getWindowDrawList();
        ImVec2 winPos = ImGui.getWindowPos();
        float winHeight = ImGui.getWindowHeight();
        ThemeDefinition theme = themeManager.getCurrentTheme();
        ImVec4 accentBase = getAccentColor(theme);

        float footerTop = winPos.y + winHeight - FOOTER_HEIGHT;
        float footerRight = winPos.x + windowWidth;
        float footerLeft = winPos.x;

        float sepCenter = (footerLeft + footerRight) * 0.5f;
        int sepBright = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.25f);
        int sepFade = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.0f);

        drawList.addRectFilledMultiColor(footerLeft, footerTop, sepCenter, footerTop + 1.0f,
                sepFade, sepBright, sepBright, sepFade);
        drawList.addRectFilledMultiColor(sepCenter, footerTop, footerRight, footerTop + 1.0f,
                sepBright, sepFade, sepFade, sepBright);

        float buttonY = footerTop + (FOOTER_HEIGHT - buttonHeight) * 0.5f;
        float totalButtonsWidth = buttonWidth * 2 + buttonSpacing;
        float cancelX = footerRight - rightMargin - totalButtonsWidth;
        float exportX = cancelX + buttonWidth + buttonSpacing;

        renderFooterButton(drawList, "Cancel", "sbe_cancel", cancelX, buttonY,
                buttonWidth, buttonHeight, false, theme, accentBase, () -> visible.set(false));

        renderFooterButton(drawList, "Export", "sbe_export", exportX, buttonY,
                buttonWidth, buttonHeight, true, theme, accentBase, this::performExport);
    }

    private void renderFooterButton(ImDrawList drawList, String label, String id,
                                     float x, float y, float width, float height,
                                     boolean primary, ThemeDefinition theme,
                                     ImVec4 accentBase, Runnable onClick) {
        ImGui.setCursorScreenPos(x, y);
        ImGui.invisibleButton("##" + id, width, height);
        boolean isHovered = ImGui.isItemHovered();
        if (ImGui.isItemClicked()) {
            onClick.run();
        }

        float x2 = x + width;
        float y2 = y + height;

        if (primary) {
            float bgAlpha = isHovered ? 0.30f : 0.18f;
            drawList.addRectFilled(x, y, x2, y2,
                    ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, bgAlpha), BTN_ROUNDING);
            float borderAlpha = isHovered ? 0.7f : 0.5f;
            drawList.addRect(x, y, x2, y2,
                    ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, borderAlpha), BTN_ROUNDING, 0, 1.0f);
        } else {
            if (isHovered) {
                ImVec4 hoverBase = theme.getColor(ImGuiCol.HeaderHovered);
                int hoverColor = hoverBase != null
                        ? ImColor.rgba(hoverBase.x, hoverBase.y, hoverBase.z, 0.15f)
                        : ImColor.rgba(1.0f, 1.0f, 1.0f, 0.08f);
                drawList.addRectFilled(x, y, x2, y2, hoverColor, BTN_ROUNDING);
                drawList.addRect(x, y, x2, y2,
                        ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.5f), BTN_ROUNDING, 0, 1.0f);
            } else {
                ImVec4 frameBg = theme.getColor(ImGuiCol.FrameBg);
                int normalBg = frameBg != null
                        ? ImColor.rgba(frameBg.x, frameBg.y, frameBg.z, 0.25f)
                        : ImColor.rgba(1.0f, 1.0f, 1.0f, 0.03f);
                drawList.addRectFilled(x, y, x2, y2, normalBg, BTN_ROUNDING);
                ImVec4 borderBase = theme.getColor(ImGuiCol.Border);
                int normalBorder = borderBase != null
                        ? ImColor.rgba(borderBase.x, borderBase.y, borderBase.z, 0.6f)
                        : ImColor.rgba(0.5f, 0.5f, 0.5f, 0.3f);
                drawList.addRect(x, y, x2, y2, normalBorder, BTN_ROUNDING, 0, 1.0f);
            }
        }

        ImVec2 textSize = ImGui.calcTextSize(label);
        float textX = x + (width - textSize.x) * 0.5f;
        float textY = y + (height - textSize.y) * 0.5f;
        ImVec4 textBase = theme.getColor(ImGuiCol.Text);
        float tr = textBase != null ? textBase.x : 0.88f;
        float tg = textBase != null ? textBase.y : 0.89f;
        float tb = textBase != null ? textBase.z : 0.91f;
        drawList.addText(textX, textY, ImColor.rgba(tr, tg, tb, isHovered ? 1.0f : 0.9f), label);
    }

    // ========================================
    // Export Logic
    // ========================================

    private void prepopulateFromModel() {
        String modelPath = modelState.getCurrentModelPath();
        if (modelPath != null && !modelPath.isBlank()) {
            String fileName = Path.of(modelPath).getFileName().toString();
            String nameWithoutExt = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;
            objectName.set(nameWithoutExt);

            String idCandidate = nameWithoutExt.toLowerCase().replace(' ', '_');
            objectId.set("stonebreak:" + idCandidate);
        }
    }

    private void performExport() {
        SBEFormat.ExportParameters params = buildParameters();

        if (!params.isValid()) {
            validationMessage = params.getValidationError();
            return;
        }

        String stateError = validateStates();
        if (stateError != null) {
            validationMessage = stateError;
            return;
        }
        String variantError = validateVariants();
        if (variantError != null) {
            validationMessage = variantError;
            return;
        }
        validationMessage = "";

        String omoPathStr = modelState.getCurrentOMOFilePath();
        if (omoPathStr == null || omoPathStr.isBlank()) {
            validationMessage = "Model must be saved as .OMO before exporting to .SBE";
            statusService.updateStatus("Export failed: model not saved as .OMO");
            return;
        }

        Path omoPath = Path.of(omoPathStr);

        String compatError = validateAnimationCompatibility(omoPath);
        if (compatError != null) {
            validationMessage = compatError;
            statusService.updateStatus("SBE export blocked: incompatible animation");
            return;
        }

        fileDialogService.showSaveSBEDialog(filePath -> {
            boolean success = serializer.export(params, omoPath, filePath);
            if (success) {
                statusService.updateStatus("Exported SBE: " + Path.of(filePath).getFileName());
                visible.set(false);
                logger.info("SBE export successful: {}", filePath);
            } else {
                validationMessage = "Export failed. Check logs for details.";
                statusService.updateStatus("SBE export failed");
            }
        });
    }

    /**
     * Cross-check each staged clip's {@code requiredParts} against the OMO
     * being exported. Returns a UI-ready error message, or null when every
     * clip is compatible. Falls open (allows the export) if either side's
     * part list cannot be read — a hard disk error here should not block
     * the user from exporting; the per-clip read is best-effort.
     */
    /**
     * Validate each clip's required parts against the relevant model:
     * a state with a model override is checked against its override OMO,
     * otherwise against the base OMO. Returns null when all bindings are
     * compatible. Falls open on read errors.
     */
    private String validateAnimationCompatibility(Path baseOmoPath) {
        if (stateBindings.isEmpty()) return null;

        List<String> baseParts;
        try {
            baseParts = AnimationCompatibility.readOMOPartIds(baseOmoPath);
        } catch (java.io.IOException e) {
            logger.warn("Could not read model parts from {}: {}", baseOmoPath, e.getMessage());
            return null;
        }
        if (baseParts.isEmpty()) return null;

        for (StateBindingRow row : stateBindings) {
            if (row.clipPath == null) continue;

            List<String> targetParts = baseParts;
            if (row.modelOverridePath != null) {
                try {
                    List<String> overrideParts = AnimationCompatibility.readOMOPartIds(row.modelOverridePath);
                    if (!overrideParts.isEmpty()) targetParts = overrideParts;
                } catch (java.io.IOException e) {
                    logger.warn("Could not read override parts from {}: {}",
                            row.modelOverridePath, e.getMessage());
                    continue;
                }
            }

            List<String> requiredParts;
            try {
                requiredParts = AnimationCompatibility.readOMARequiredParts(row.clipPath);
            } catch (java.io.IOException e) {
                logger.warn("Could not read required parts from {}: {}", row.clipPath, e.getMessage());
                continue;
            }

            AnimationCompatibility.Result result =
                    AnimationCompatibility.check(requiredParts, targetParts);
            if (!result.isCompatible()) {
                return "State '" + row.state.get().trim()
                        + "' clip references parts missing from its model: "
                        + result.describeMissing();
            }
        }
        return null;
    }

    private String validateStates() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (StateBindingRow row : stateBindings) {
            String state = row.state.get().trim();
            if (state.isBlank()) {
                return "State name cannot be blank";
            }
            if (!seen.add(state)) {
                return "Duplicate state: '" + state + "'";
            }
        }
        return null;
    }

    private String validateVariants() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (VariantBindingRow row : variantBindings) {
            String name = row.variant.get().trim();
            if (name.isBlank()) {
                return "Variant name cannot be blank";
            }
            if (!seen.add(name)) {
                return "Duplicate variant: '" + name + "'";
            }
        }
        return null;
    }

    private SBEFormat.ExportParameters buildParameters() {
        SBEFormat.ExportParameters params = new SBEFormat.ExportParameters();
        params.setObjectId(objectId.get().trim());
        params.setObjectName(objectName.get().trim());
        params.setEntityType(SBEFormat.EntityType.values()[entityTypeIndex.get()]);
        params.setObjectPack(objectPack.get().trim());
        params.setAuthor(author.get().trim());
        params.setDescription(description.get().trim());
        for (StateBindingRow row : stateBindings) {
            params.addState(row.state.get().trim(), row.modelOverridePath, row.clipPath);
        }
        for (VariantBindingRow row : variantBindings) {
            params.addVariant(row.variant.get().trim(), row.modelOverridePath);
        }
        return params;
    }

    private ImVec4 getAccentColor(ThemeDefinition theme) {
        ImVec4 accent = theme.getColor(ImGuiCol.HeaderActive);
        if (accent == null) accent = theme.getColor(ImGuiCol.ButtonHovered);
        if (accent == null) accent = new ImVec4(0.36f, 0.61f, 0.84f, 1.0f);
        return accent;
    }
}
