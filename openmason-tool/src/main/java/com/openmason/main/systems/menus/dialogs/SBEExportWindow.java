package com.openmason.main.systems.menus.dialogs;

import com.openmason.main.systems.menus.windows.WindowTitleBar;
import com.openmason.main.systems.rendering.model.io.sbe.SBEFormat;
import com.openmason.main.systems.rendering.model.io.sbe.SBESerializer;
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

    // Entity type labels for the combo box
    private static final String[] ENTITY_TYPE_LABELS = {
            "Mob", "NPC", "Projectile", "Vehicle", "Other"
    };

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

        if (!validationMessage.isEmpty()) {
            ImGui.dummy(0, ROW_SPACING);
            renderValidationBanner(validationMessage);
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
        validationMessage = "";

        String omoPathStr = modelState.getCurrentOMOFilePath();
        if (omoPathStr == null || omoPathStr.isBlank()) {
            validationMessage = "Model must be saved as .OMO before exporting to .SBE";
            statusService.updateStatus("Export failed: model not saved as .OMO");
            return;
        }

        Path omoPath = Path.of(omoPathStr);

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

    private SBEFormat.ExportParameters buildParameters() {
        SBEFormat.ExportParameters params = new SBEFormat.ExportParameters();
        params.setObjectId(objectId.get().trim());
        params.setObjectName(objectName.get().trim());
        params.setEntityType(SBEFormat.EntityType.values()[entityTypeIndex.get()]);
        params.setObjectPack(objectPack.get().trim());
        params.setAuthor(author.get().trim());
        params.setDescription(description.get().trim());
        return params;
    }

    private ImVec4 getAccentColor(ThemeDefinition theme) {
        ImVec4 accent = theme.getColor(ImGuiCol.HeaderActive);
        if (accent == null) accent = theme.getColor(ImGuiCol.ButtonHovered);
        if (accent == null) accent = new ImVec4(0.36f, 0.61f, 0.84f, 1.0f);
        return accent;
    }
}
