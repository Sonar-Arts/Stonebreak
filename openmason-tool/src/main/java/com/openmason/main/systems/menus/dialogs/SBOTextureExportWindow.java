package com.openmason.main.systems.menus.dialogs;

import com.openmason.main.systems.menus.windows.WindowTitleBar;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOSerializer;
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
 * Texture-editor variant of the SBO export window.
 *
 * <p>Exports a texture-only Stonebreak Object (.SBO, format 1.2+) wrapping the
 * current OMT file. Used for sprite items and other texture-only assets that
 * have no 3D model — replaces the SBT-as-item flow.
 *
 * <p>Distinct from {@code SBOExportWindow} (model editor side), which exports
 * model-bearing SBOs from an OMO. Both target the SBO format but feed different
 * payload kinds.
 */
public class SBOTextureExportWindow {

    private static final Logger logger = LoggerFactory.getLogger(SBOTextureExportWindow.class);

    private static final String WINDOW_TITLE = "Export SBO (Texture)";
    private static final float MIN_WINDOW_WIDTH = 560.0f;
    private static final float MIN_WINDOW_HEIGHT = 720.0f;
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
    private final SBOSerializer serializer;
    private final WindowTitleBar titleBar;

    private Supplier<String> omtPathSupplier = () -> null;

    private final ImString objectId = new ImString(256);
    private final ImString objectName = new ImString(256);
    private final ImInt objectTypeIndex = new ImInt(0); // default ITEM (first entry)
    private final ImString objectPack = new ImString(256);
    private final ImString author = new ImString(256);
    private final ImString description = new ImString(1024);

    // Game properties (populated into SBOFormat.GameProperties on export).
    // Numeric ID is required for ItemRegistry to register the SBO; the other
    // fields have item-friendly defaults but can be overridden here.
    private final ImInt numericId = new ImInt(0);
    private final ImInt maxStackSize = new ImInt(64);
    private final ImInt categoryIndex = new ImInt(1); // TOOLS
    private final ImInt atlasX = new ImInt(-1);
    private final ImInt atlasY = new ImInt(-1);
    private final ImInt renderLayerIndex = new ImInt(1); // CUTOUT
    private final imgui.type.ImFloat hardness = new imgui.type.ImFloat(0.0f);

    /**
     * Category labels mirror {@code com.stonebreak.items.ItemCategory} enum
     * names. Values stored in GameProperties as the enum name (e.g. "TOOLS").
     */
    private static final String[] CATEGORY_LABELS = {
            "BLOCKS", "TOOLS", "MATERIALS", "FOOD", "DECORATIVE"
    };
    private static final String[] RENDER_LAYER_LABELS = {
            "OPAQUE", "CUTOUT", "TRANSLUCENT"
    };

    /**
     * Object types valid for a texture-only SBO. Indices map directly to
     * SBOFormat.ObjectType so the combo selection round-trips cleanly.
     * BLOCK is excluded — model-bearing blocks go through the model editor's
     * SBO export, not this dialog.
     */
    private static final SBOFormat.ObjectType[] OBJECT_TYPE_VALUES = {
            SBOFormat.ObjectType.ITEM,
            SBOFormat.ObjectType.DECORATION,
            SBOFormat.ObjectType.PARTICLE,
            SBOFormat.ObjectType.OTHER
    };
    private static final String[] OBJECT_TYPE_LABELS = {
            "Item", "Decoration", "Particle", "Other"
    };

    private String validationMessage = "";
    private boolean iniFileSet = false;

    private float formOffsetX = MIN_SIDE_PADDING;

    public SBOTextureExportWindow(ImBoolean visible,
                                  ThemeManager themeManager,
                                  StatusService statusService,
                                  FileDialogService fileDialogService) {
        this.visible = visible;
        this.themeManager = themeManager;
        this.statusService = statusService;
        this.fileDialogService = fileDialogService;
        this.serializer = new SBOSerializer();
        this.titleBar = new WindowTitleBar(WINDOW_TITLE, true, false);
        objectPack.set("default");
    }

    public void setOMTPathSupplier(Supplier<String> supplier) {
        this.omtPathSupplier = supplier != null ? supplier : (() -> null);
    }

    public void show() {
        visible.set(true);
        prepopulateFromTexture();
        validationMessage = "";
        logger.debug("SBO texture export window shown");
    }

    public void hide() { visible.set(false); }
    public boolean isVisible() { return visible.get(); }

    public void render() {
        if (!visible.get()) return;

        if (!iniFileSet) {
            ImGui.setNextWindowSize(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT);
            float screenW = ImGui.getMainViewport().getSizeX();
            float screenH = ImGui.getMainViewport().getSizeY();
            ImGui.setNextWindowPos((screenW - MIN_WINDOW_WIDTH) * 0.5f, (screenH - MIN_WINDOW_HEIGHT) * 0.5f);
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
                if (result.minimizeClicked() || result.closeClicked()) visible.set(false);
                renderContent();
            } catch (Exception e) {
                logger.error("Error rendering SBO texture export window", e);
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
        ImGui.beginChild("##SBOTexContent", windowWidth, contentHeight, false);

        renderFormFields();

        ImGui.endChild();
        ImGui.popStyleVar();

        renderFooter(windowWidth);
    }

    private void renderFormFields() {
        ImGui.setCursorPosX(formOffsetX);
        ImGuiComponents.renderCompactSectionHeader("Object Identity");
        ImGui.spacing();

        renderLabeledInput("Object ID", "sbo_tex_obj_id", objectId, "e.g. stonebreak:sword");
        renderLabeledInput("Object Name", "sbo_tex_obj_name", objectName, "e.g. Sword");

        ImGui.dummy(0, ROW_SPACING);

        ImGui.setCursorPosX(formOffsetX);
        ImGuiComponents.renderCompactSectionHeader("Classification");
        ImGui.spacing();

        renderLabeledCombo("Object Type", "sbo_tex_obj_type", objectTypeIndex, OBJECT_TYPE_LABELS);
        renderLabeledInput("Object Pack", "sbo_tex_obj_pack", objectPack, "e.g. default, expansion_1");

        ImGui.dummy(0, ROW_SPACING);

        ImGui.setCursorPosX(formOffsetX);
        ImGuiComponents.renderCompactSectionHeader("Attribution");
        ImGui.spacing();

        renderLabeledInput("Author", "sbo_tex_author", author, "Creator name or studio");

        ImGui.dummy(0, 2);

        ImGui.setCursorPosX(formOffsetX);
        ImGui.textDisabled("Description");
        ImGui.dummy(0, 2);
        ImGui.setCursorPosX(formOffsetX);
        ImGui.pushItemWidth(INPUT_WIDTH);
        ImGui.inputTextMultiline("##sbo_tex_desc", description, INPUT_WIDTH, DESCRIPTION_HEIGHT,
                ImGuiInputTextFlags.AllowTabInput);
        ImGui.popItemWidth();

        ImGui.dummy(0, ROW_SPACING);

        ImGui.setCursorPosX(formOffsetX);
        ImGuiComponents.renderCompactSectionHeader("Game Properties");
        ImGui.spacing();

        renderLabeledIntInput("Numeric ID", "sbo_tex_num_id", numericId,
                "required — unique across items (e.g. 1012)");
        renderLabeledIntInput("Max Stack", "sbo_tex_stack", maxStackSize, "1–64");
        renderLabeledCombo("Category", "sbo_tex_cat", categoryIndex, CATEGORY_LABELS);

        ImGui.dummy(0, ROW_SPACING);

        ImGui.setCursorPosX(formOffsetX);
        ImGui.textDisabled("Advanced (defaults are sensible for sprite items)");
        ImGui.dummy(0, 2);

        renderLabeledIntInput("Atlas X", "sbo_tex_atlasx", atlasX, "-1 = none");
        renderLabeledIntInput("Atlas Y", "sbo_tex_atlasy", atlasY, "-1 = none");
        renderLabeledCombo("Render Layer", "sbo_tex_rl", renderLayerIndex, RENDER_LAYER_LABELS);
        renderLabeledFloatInput("Hardness", "sbo_tex_hard", hardness, "seconds to break");

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

    private void renderLabeledIntInput(String label, String id, ImInt value, String hint) {
        ImGui.setCursorPosX(formOffsetX);
        ImGui.textDisabled(label);
        ImGui.sameLine(formOffsetX + LABEL_WIDTH);
        ImGui.pushItemWidth(INPUT_WIDTH);
        ImGui.inputInt("##" + id, value);
        ImGui.popItemWidth();
        if (hint != null && !hint.isBlank()) {
            ImGui.setCursorPosX(formOffsetX + LABEL_WIDTH);
            ImGui.textDisabled(hint);
        }
        ImGui.dummy(0, ROW_SPACING);
    }

    private void renderLabeledFloatInput(String label, String id, imgui.type.ImFloat value, String hint) {
        ImGui.setCursorPosX(formOffsetX);
        ImGui.textDisabled(label);
        ImGui.sameLine(formOffsetX + LABEL_WIDTH);
        ImGui.pushItemWidth(INPUT_WIDTH);
        ImGui.inputFloat("##" + id, value);
        ImGui.popItemWidth();
        if (hint != null && !hint.isBlank()) {
            ImGui.setCursorPosX(formOffsetX + LABEL_WIDTH);
            ImGui.textDisabled(hint);
        }
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

        drawList.addRectFilled(pos.x, pos.y, pos.x + bannerWidth, pos.y + bannerHeight,
                ImColor.rgba(0.8f, 0.2f, 0.2f, 0.15f), 4.0f);
        drawList.addRectFilled(pos.x, pos.y, pos.x + 3.0f, pos.y + bannerHeight,
                ImColor.rgba(0.9f, 0.3f, 0.3f, 0.8f), 2.0f);

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

        renderFooterButton(drawList, "Cancel", "sbo_tex_cancel", cancelX, buttonY,
                buttonWidth, buttonHeight, false, theme, accentBase, () -> visible.set(false));
        renderFooterButton(drawList, "Export", "sbo_tex_export", exportX, buttonY,
                buttonWidth, buttonHeight, true, theme, accentBase, this::performExport);
    }

    private void renderFooterButton(ImDrawList drawList, String label, String id,
                                    float x, float y, float width, float height,
                                    boolean primary, ThemeDefinition theme,
                                    ImVec4 accentBase, Runnable onClick) {
        ImGui.setCursorScreenPos(x, y);
        ImGui.invisibleButton("##" + id, width, height);
        boolean isHovered = ImGui.isItemHovered();
        if (ImGui.isItemClicked()) onClick.run();

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
            objectName.set(nameWithoutExt);
            String idCandidate = nameWithoutExt.toLowerCase().replace(' ', '_');
            objectId.set("stonebreak:" + idCandidate);
        }
    }

    private void performExport() {
        if (numericId.get() <= 0) {
            validationMessage = "Numeric ID must be > 0 (required for ItemRegistry to load this SBO)";
            return;
        }

        SBOFormat.ExportParameters params = buildParameters();

        if (!params.isValid()) {
            validationMessage = params.getValidationError();
            return;
        }
        validationMessage = "";

        String omtPathStr = omtPathSupplier.get();
        if (omtPathStr == null || omtPathStr.isBlank()) {
            validationMessage = "Texture must be saved as .OMT before exporting to .SBO";
            statusService.updateStatus("Export failed: texture not saved as .OMT");
            return;
        }

        Path omtPath = Path.of(omtPathStr);

        fileDialogService.showSaveSBODialog(filePath -> {
            boolean success = serializer.exportTexture(params, omtPath, filePath);
            if (success) {
                statusService.updateStatus("Exported SBO: " + Path.of(filePath).getFileName());
                visible.set(false);
                logger.info("SBO texture export successful: {}", filePath);
            } else {
                validationMessage = "Export failed. Check logs for details.";
                statusService.updateStatus("SBO texture export failed");
            }
        });
    }

    private SBOFormat.ExportParameters buildParameters() {
        SBOFormat.ExportParameters params = new SBOFormat.ExportParameters();
        params.setObjectId(objectId.get().trim());
        params.setObjectName(objectName.get().trim());
        params.setObjectType(OBJECT_TYPE_VALUES[objectTypeIndex.get()]);
        params.setObjectPack(objectPack.get().trim());
        params.setAuthor(author.get().trim());
        params.setDescription(description.get().trim());
        params.setGameProperties(buildGameProperties());
        return params;
    }

    /**
     * Build a GameProperties block from the form. Defaults match the values
     * the SBT-to-SBO migration emits for sprite items: not solid, breakable,
     * not transparent (CUTOUT layer handles alpha), not placeable.
     */
    private SBOFormat.GameProperties buildGameProperties() {
        return new SBOFormat.GameProperties(
                numericId.get(),
                hardness.get(),
                false,                              // solid
                true,                               // breakable
                atlasX.get(),
                atlasY.get(),
                RENDER_LAYER_LABELS[renderLayerIndex.get()],
                true,                               // transparent
                false,                              // flower
                false,                              // stackable
                Math.max(1, maxStackSize.get()),
                CATEGORY_LABELS[categoryIndex.get()],
                false                               // placeable
        );
    }

    private ImVec4 getAccentColor(ThemeDefinition theme) {
        ImVec4 accent = theme.getColor(ImGuiCol.HeaderActive);
        if (accent == null) accent = theme.getColor(ImGuiCol.ButtonHovered);
        if (accent == null) accent = new ImVec4(0.36f, 0.61f, 0.84f, 1.0f);
        return accent;
    }
}
