package com.openmason.main.systems.menus.dialogs;

import com.openmason.main.systems.menus.windows.WindowTitleBar;
import com.openmason.engine.format.sbt.SBTFormat;
import com.openmason.engine.format.sbt.SBTSerializer;
import com.openmason.main.systems.services.StatusService;
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
import java.util.function.Supplier;

/**
 * Export window for creating Stonebreak Texture (.SBT) files.
 *
 * <p>Provides a form-based UI for entering SBT metadata (Texture ID, Name,
 * Texture Type, Pack, Author, Description) and triggering the export. Styled
 * consistently with the SBO/SBE export windows.
 */
public class SBTExportWindow {

    private static final Logger logger = LoggerFactory.getLogger(SBTExportWindow.class);

    private static final String WINDOW_TITLE = "Export SBT";
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
    private final StatusService statusService;
    private final FileDialogService fileDialogService;
    private final SBTSerializer serializer;
    private final WindowTitleBar titleBar;

    /**
     * Supplier for the current OMT file path. Pulled lazily so the window can
     * be constructed before the texture editor is available.
     */
    private Supplier<String> omtPathSupplier = () -> null;

    private final ImString textureId = new ImString(256);
    private final ImString textureName = new ImString(256);
    private final ImInt textureTypeIndex = new ImInt(0);
    private final ImString texturePack = new ImString(256);
    private final ImString author = new ImString(256);
    private final ImString description = new ImString(1024);

    private static final String[] TEXTURE_TYPE_LABELS = {
            "Block", "Item", "Entity", "UI", "Other"
    };

    private String validationMessage = "";
    private boolean iniFileSet = false;

    private float formOffsetX = MIN_SIDE_PADDING;

    public SBTExportWindow(ImBoolean visible,
                           ThemeManager themeManager,
                           StatusService statusService,
                           FileDialogService fileDialogService) {
        this.visible = visible;
        this.themeManager = themeManager;
        this.statusService = statusService;
        this.fileDialogService = fileDialogService;
        this.serializer = new SBTSerializer();
        this.titleBar = new WindowTitleBar(WINDOW_TITLE, true, false);

        texturePack.set("default");
    }

    /**
     * Wire a supplier that returns the current OMT file path on disk, or null
     * if no OMT is loaded/saved. Pulled at export time.
     */
    public void setOMTPathSupplier(Supplier<String> supplier) {
        this.omtPathSupplier = supplier != null ? supplier : (() -> null);
    }

    public void show() {
        visible.set(true);
        prepopulateFromTexture();
        validationMessage = "";
        logger.debug("SBT export window shown");
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
                logger.error("Error rendering SBT export window", e);
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
        ImGui.beginChild("##SBTContent", windowWidth, contentHeight, false);

        renderFormFields();

        ImGui.endChild();
        ImGui.popStyleVar();

        renderFooter(windowWidth);
    }

    private void renderFormFields() {
        ImGui.setCursorPosX(formOffsetX);
        ImGuiComponents.renderCompactSectionHeader("Texture Identity");
        ImGui.spacing();

        renderLabeledInput("Texture ID", "sbt_tex_id", textureId, "e.g. stonebreak:cow_default");
        renderLabeledInput("Texture Name", "sbt_tex_name", textureName, "e.g. Cow Default");

        ImGui.dummy(0, ROW_SPACING);

        ImGui.setCursorPosX(formOffsetX);
        ImGuiComponents.renderCompactSectionHeader("Texture Classification");
        ImGui.spacing();

        renderLabeledCombo("Texture Type", "sbt_tex_type", textureTypeIndex, TEXTURE_TYPE_LABELS);
        renderLabeledInput("Texture Pack", "sbt_tex_pack", texturePack, "e.g. default, expansion_1");

        ImGui.dummy(0, ROW_SPACING);

        ImGui.setCursorPosX(formOffsetX);
        ImGuiComponents.renderCompactSectionHeader("Attribution");
        ImGui.spacing();

        renderLabeledInput("Author", "sbt_author", author, "Creator name or studio");

        ImGui.dummy(0, 2);

        ImGui.setCursorPosX(formOffsetX);
        ImGui.textDisabled("Description");
        ImGui.dummy(0, 2);
        ImGui.setCursorPosX(formOffsetX);
        ImGui.pushItemWidth(INPUT_WIDTH);
        ImGui.inputTextMultiline("##sbt_desc", description, INPUT_WIDTH, DESCRIPTION_HEIGHT,
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

        renderFooterButton(drawList, "Cancel", "sbt_cancel", cancelX, buttonY,
                buttonWidth, buttonHeight, false, theme, accentBase, () -> visible.set(false));

        renderFooterButton(drawList, "Export", "sbt_export", exportX, buttonY,
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

    private void prepopulateFromTexture() {
        String omtPath = omtPathSupplier.get();
        if (omtPath != null && !omtPath.isBlank()) {
            String fileName = Path.of(omtPath).getFileName().toString();
            String nameWithoutExt = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;
            textureName.set(nameWithoutExt);

            String idCandidate = nameWithoutExt.toLowerCase().replace(' ', '_');
            textureId.set("stonebreak:" + idCandidate);
        }
    }

    private void performExport() {
        SBTFormat.ExportParameters params = buildParameters();

        if (!params.isValid()) {
            validationMessage = params.getValidationError();
            return;
        }
        validationMessage = "";

        String omtPathStr = omtPathSupplier.get();
        if (omtPathStr == null || omtPathStr.isBlank()) {
            validationMessage = "Texture must be saved as .OMT before exporting to .SBT";
            statusService.updateStatus("Export failed: texture not saved as .OMT");
            return;
        }

        Path omtPath = Path.of(omtPathStr);

        fileDialogService.showSaveSBTDialog(filePath -> {
            boolean success = serializer.export(params, omtPath, filePath);
            if (success) {
                statusService.updateStatus("Exported SBT: " + Path.of(filePath).getFileName());
                visible.set(false);
                logger.info("SBT export successful: {}", filePath);
            } else {
                validationMessage = "Export failed. Check logs for details.";
                statusService.updateStatus("SBT export failed");
            }
        });
    }

    private SBTFormat.ExportParameters buildParameters() {
        SBTFormat.ExportParameters params = new SBTFormat.ExportParameters();
        params.setTextureId(textureId.get().trim());
        params.setTextureName(textureName.get().trim());
        params.setTextureType(SBTFormat.TextureType.values()[textureTypeIndex.get()]);
        params.setTexturePack(texturePack.get().trim());
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
