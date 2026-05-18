package com.openmason.main.systems.menus.dialogs;

import com.openmason.main.systems.menus.windows.WindowTitleBar;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParser;
import com.openmason.engine.format.sbo.SBOSerializer;
import com.openmason.main.systems.menus.dialogs.validation.NumericIdConflictPopup;
import com.openmason.main.systems.menus.dialogs.validation.NumericIdValidator;
import com.openmason.main.systems.menus.dialogs.validation.TakenIdsPopup;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Export window for creating Stonebreak Object (.SBO) files.
 *
 * <p>Provides a form-based UI for entering SBO metadata (Object ID, Name,
 * Type, Pack, Author, Description) and triggering the export. Styled
 * consistently with the Preferences window as a floating, themed window.
 */
public class SBOExportWindow {

    private static final Logger logger = LoggerFactory.getLogger(SBOExportWindow.class);

    private static final String WINDOW_TITLE = "Export SBO";
    private static final float MIN_WINDOW_WIDTH = 560.0f;
    private static final float MIN_WINDOW_HEIGHT = 800.0f;
    private static final float FOOTER_HEIGHT = 44.0f;
    private static final float CONTENT_PADDING_Y = 22.0f;
    private static final float LABEL_WIDTH = 100.0f;
    private static final float INPUT_WIDTH = 360.0f;
    private static final float FORM_WIDTH = LABEL_WIDTH + INPUT_WIDTH;
    private static final float DESCRIPTION_HEIGHT = 76.0f;
    private static final float ROW_SPACING = 6.0f;
    private static final float MIN_SIDE_PADDING = 28.0f;

    // Footer button style constants (matching PreferencesWindow)
    private static final float BTN_ROUNDING = 8.0f;

    private final ImBoolean visible;
    private final ThemeManager themeManager;
    private final ModelState modelState;
    private final StatusService statusService;
    private final FileDialogService fileDialogService;
    private final SBOSerializer serializer;
    private final WindowTitleBar titleBar;
    private final SBOStatesSection statesSection;

    // Input field buffers
    private final ImString objectId = new ImString(256);
    private final ImString objectName = new ImString(256);
    private final ImInt objectTypeIndex = new ImInt(0);
    private final ImString objectPack = new ImString(256);
    private final ImString author = new ImString(256);
    private final ImString description = new ImString(1024);

    // Numeric ID for blocks — required for chunk save references. Other object
    // types may leave it at -1 (no GameProperties block emitted).
    private final ImInt numericId = new ImInt(-1);

    private final NumericIdConflictPopup conflictPopup = new NumericIdConflictPopup();
    private final TakenIdsPopup takenIdsPopup = new TakenIdsPopup();

    // Object type labels for the combo box
    private static final String[] OBJECT_TYPE_LABELS = {
            "Block", "Item", "Entity", "Decoration", "Particle", "Other"
    };

    // Validation state
    private String validationMessage = "";
    private boolean iniFileSet = false;

    // Computed each frame — left X offset to center form content
    private float formOffsetX = MIN_SIDE_PADDING;

    /**
     * Creates a new SBO export window.
     *
     * @param visible           visibility state toggle
     * @param themeManager      theme manager for styled rendering
     * @param modelState        current model state
     * @param statusService     status bar service
     * @param fileDialogService file dialog service for save dialogs
     */
    public SBOExportWindow(ImBoolean visible,
                           ThemeManager themeManager,
                           ModelState modelState,
                           StatusService statusService,
                           FileDialogService fileDialogService) {
        this.visible = visible;
        this.themeManager = themeManager;
        this.modelState = modelState;
        this.statusService = statusService;
        this.fileDialogService = fileDialogService;
        this.serializer = new SBOSerializer();
        this.titleBar = new WindowTitleBar(WINDOW_TITLE, true, false);
        this.statesSection = new SBOStatesSection(
                /* modelKind */ true,
                callback -> fileDialogService.showOpenOMODialog(callback::accept),
                modelState::getCurrentOMOFilePath
        );

        // Default pack name
        objectPack.set("default");
    }

    /**
     * Shows the export window and pre-populates fields from current model state.
     */
    public void show() {
        visible.set(true);
        prepopulateFromModel();
        if (numericId.get() < 0) {
            numericId.set(NumericIdValidator.suggestNextFreeId(NumericIdValidator.Domain.BLOCK));
        }
        statesSection.reset();
        validationMessage = "";
        logger.debug("SBO export window shown");
    }

    /**
     * Hides the export window.
     */
    public void hide() {
        visible.set(false);
    }

    /**
     * Checks if the export window is visible.
     */
    public boolean isVisible() {
        return visible.get();
    }

    /**
     * Renders the SBO export window.
     */
    public void render() {
        if (!visible.get()) {
            return;
        }

        // Set initial size and position (first time only)
        if (!iniFileSet) {
            ImGui.setNextWindowSize(MIN_WINDOW_WIDTH, MIN_WINDOW_HEIGHT);

            // Center on screen
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
                conflictPopup.render();
                takenIdsPopup.render();
            } catch (Exception e) {
                logger.error("Error rendering SBO export window", e);
                ImGui.textColored(1.0f, 0.0f, 0.0f, 1.0f, "Error rendering export window");
            }
        }
        ImGui.end();
        ImGui.popStyleVar();
    }

    /**
     * Renders the form content area and footer buttons.
     */
    private void renderContent() {
        float windowWidth = ImGui.getContentRegionAvailX();
        float windowHeight = ImGui.getContentRegionAvailY();
        float contentHeight = windowHeight - FOOTER_HEIGHT;

        // Compute centering offset for the form block
        formOffsetX = Math.max(MIN_SIDE_PADDING, (windowWidth - FORM_WIDTH) * 0.5f);

        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 8.0f, CONTENT_PADDING_Y);
        ImGui.beginChild("##SBOContent", windowWidth, contentHeight, false);

        renderFormFields();

        ImGui.endChild();
        ImGui.popStyleVar();

        // Footer
        renderFooter(windowWidth);
    }

    // ========================================
    // Form Field Rendering
    // ========================================

    /**
     * Renders all form sections with polished layout.
     */
    private void renderFormFields() {
        // Section: Object Identity
        ImGui.setCursorPosX(formOffsetX);
        ImGuiComponents.renderCompactSectionHeader("Object Identity");
        ImGui.spacing();

        renderLabeledInput("Object ID", "sbo_obj_id", objectId, "e.g. stonebreak:oak_planks");
        renderLabeledInput("Object Name", "sbo_obj_name", objectName, "e.g. Oak Planks");

        ImGui.dummy(0, ROW_SPACING);

        // Section: Classification
        ImGui.setCursorPosX(formOffsetX);
        ImGuiComponents.renderCompactSectionHeader("Classification");
        ImGui.spacing();

        renderLabeledCombo("Object Type", "sbo_obj_type", objectTypeIndex, OBJECT_TYPE_LABELS);
        renderLabeledInput("Object Pack", "sbo_obj_pack", objectPack, "e.g. default, expansion_1");

        ImGui.dummy(0, ROW_SPACING);

        // Section: Attribution
        ImGui.setCursorPosX(formOffsetX);
        ImGuiComponents.renderCompactSectionHeader("Attribution");
        ImGui.spacing();

        renderLabeledInput("Author", "sbo_author", author, "Creator name or studio");

        ImGui.dummy(0, 2);

        // Description — label left-aligned, multiline below at fixed width
        ImGui.setCursorPosX(formOffsetX);
        ImGui.textDisabled("Description");
        ImGui.dummy(0, 2);
        ImGui.setCursorPosX(formOffsetX);
        ImGui.pushItemWidth(INPUT_WIDTH);
        ImGui.inputTextMultiline("##sbo_desc", description, INPUT_WIDTH, DESCRIPTION_HEIGHT,
                ImGuiInputTextFlags.AllowTabInput);
        ImGui.popItemWidth();

        ImGui.dummy(0, ROW_SPACING);

        // Section: Game Properties (numericId for blocks; other types may skip)
        ImGui.setCursorPosX(formOffsetX);
        ImGuiComponents.renderCompactSectionHeader("Game Properties");
        ImGui.spacing();

        renderLabeledIntInputWithButton("Numeric ID", "sbo_num_id", numericId,
                blockSelected()
                        ? "required for blocks - unique across all blocks"
                        : "optional for non-blocks (-1 to skip GameProperties)",
                "Taken IDs...##exp_taken",
                () -> takenIdsPopup.open(currentDomain()));
        renderConflictHint();

        ImGui.dummy(0, ROW_SPACING);
        statesSection.render(formOffsetX, LABEL_WIDTH, INPUT_WIDTH, ROW_SPACING);

        // Validation message
        if (!validationMessage.isEmpty()) {
            ImGui.dummy(0, ROW_SPACING);
            renderValidationBanner(validationMessage);
        }
    }

    /**
     * Renders a label + input field pair with fixed-width input.
     */
    private void renderLabeledInput(String label, String id, ImString buffer, String hint) {
        ImGui.setCursorPosX(formOffsetX);
        ImGui.textDisabled(label);
        ImGui.sameLine(formOffsetX + LABEL_WIDTH);
        ImGui.pushItemWidth(INPUT_WIDTH);
        ImGui.inputTextWithHint("##" + id, hint, buffer);
        ImGui.popItemWidth();

        ImGui.dummy(0, ROW_SPACING);
    }

    /**
     * Renders a label + combo box pair with fixed-width combo.
     */
    private void renderLabeledIntInputWithButton(String label, String id, ImInt value, String hint,
                                                 String buttonLabel, Runnable onClick) {
        ImGui.setCursorPosX(formOffsetX);
        ImGui.textDisabled(label);
        ImGui.sameLine(formOffsetX + LABEL_WIDTH);
        ImGui.pushItemWidth(INPUT_WIDTH - 110.0f);
        ImGui.inputInt("##" + id, value);
        ImGui.popItemWidth();
        ImGui.sameLine();
        if (ImGui.button(buttonLabel, 100.0f, 0)) {
            onClick.run();
        }
        if (hint != null && !hint.isBlank()) {
            ImGui.setCursorPosX(formOffsetX + LABEL_WIDTH);
            ImGui.textDisabled(hint);
        }
        ImGui.dummy(0, ROW_SPACING);
    }

    private void renderConflictHint() {
        NumericIdValidator.Domain domain = currentDomain();
        if (domain == NumericIdValidator.Domain.NONE) return;
        NumericIdValidator.Result result = NumericIdValidator.validate(
                domain, numericId.get(), objectId.get().trim());
        if (result instanceof NumericIdValidator.Result.Conflict c) {
            ImGui.setCursorPosX(formOffsetX + LABEL_WIDTH);
            ImGui.textColored(1.0f, 0.55f, 0.45f, 1.0f,
                    "ID " + c.numericId() + " taken by " + c.existingObjectId());
        }
    }

    private NumericIdValidator.Domain currentDomain() {
        return NumericIdValidator.domainFor(OBJECT_TYPE_LABELS[objectTypeIndex.get()]);
    }

    private boolean blockSelected() {
        return currentDomain() == NumericIdValidator.Domain.BLOCK;
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

    /**
     * Renders a validation error banner with accent-tinted background.
     */
    private void renderValidationBanner(String message) {
        ImDrawList drawList = ImGui.getWindowDrawList();
        ImVec2 pos = ImGui.getCursorScreenPos();
        float bannerWidth = LABEL_WIDTH + INPUT_WIDTH;

        float padding = 8.0f;
        ImVec2 textSize = ImGui.calcTextSize(message);
        float bannerHeight = textSize.y + padding * 2;

        // Red-tinted background
        drawList.addRectFilled(
                pos.x, pos.y,
                pos.x + bannerWidth, pos.y + bannerHeight,
                ImColor.rgba(0.8f, 0.2f, 0.2f, 0.15f), 4.0f
        );
        // Left accent bar
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

    /**
     * Renders the footer with Cancel and Export buttons.
     */
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

        // Gradient separator line at top of footer
        float sepCenter = (footerLeft + footerRight) * 0.5f;
        int sepBright = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.25f);
        int sepFade = ImColor.rgba(accentBase.x, accentBase.y, accentBase.z, 0.0f);

        drawList.addRectFilledMultiColor(footerLeft, footerTop, sepCenter, footerTop + 1.0f,
                sepFade, sepBright, sepBright, sepFade);
        drawList.addRectFilledMultiColor(sepCenter, footerTop, footerRight, footerTop + 1.0f,
                sepBright, sepFade, sepFade, sepBright);

        // Center buttons vertically within footer
        float buttonY = footerTop + (FOOTER_HEIGHT - buttonHeight) * 0.5f;
        float totalButtonsWidth = buttonWidth * 2 + buttonSpacing;
        float cancelX = footerRight - rightMargin - totalButtonsWidth;
        float exportX = cancelX + buttonWidth + buttonSpacing;

        // Cancel button
        renderFooterButton(drawList, "Cancel", "sbo_cancel", cancelX, buttonY,
                buttonWidth, buttonHeight, false, theme, accentBase, () -> visible.set(false));

        // Export button (primary)
        renderFooterButton(drawList, "Export", "sbo_export", exportX, buttonY,
                buttonWidth, buttonHeight, true, theme, accentBase, this::performExport);
    }

    /**
     * Renders a footer button at an absolute screen position.
     * Matches the PreferencesWindow footer button style exactly.
     */
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

        // Centered label
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

    /**
     * Pre-populates form fields from the current model state.
     */
    private void prepopulateFromModel() {
        String modelPath = modelState.getCurrentModelPath();
        if (modelPath != null && !modelPath.isBlank()) {
            // Derive object name from the model file name
            String fileName = Path.of(modelPath).getFileName().toString();
            String nameWithoutExt = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;
            objectName.set(nameWithoutExt);

            // Derive a default object ID from the name
            String idCandidate = nameWithoutExt.toLowerCase().replace(' ', '_');
            objectId.set("stonebreak:" + idCandidate);
        }
    }

    /**
     * Validates fields and performs the SBO export via file dialog.
     */
    private void performExport() {
        if (blockSelected() && numericId.get() <= 0) {
            validationMessage = "Numeric ID must be > 0 for blocks (required for chunk save references)";
            return;
        }

        NumericIdValidator.Result vr = NumericIdValidator.validate(
                currentDomain(), numericId.get(), objectId.get().trim());
        if (vr instanceof NumericIdValidator.Result.Conflict c) {
            conflictPopup.open(c, this::performExportConfirmed);
            return;
        }
        performExportConfirmed();
    }

    private void performExportConfirmed() {
        SBOFormat.ExportParameters params = buildParameters();

        if (!params.isValid()) {
            validationMessage = params.getValidationError();
            return;
        }
        validationMessage = "";

        // Resolve the OMO file path. When states are enabled, the default
        // state's path is the legacy/default asset; otherwise we use the
        // currently loaded model's OMO path.
        String omoPathStr;
        if (statesSection.isEnabled()) {
            omoPathStr = params.getStates().stream()
                    .filter(s -> s.name().equals(params.getDefaultStateName()))
                    .map(SBOFormat.StateSpec::sourcePath)
                    .findFirst().orElse("");
        } else {
            omoPathStr = modelState.getCurrentOMOFilePath();
        }
        if (omoPathStr == null || omoPathStr.isBlank()) {
            validationMessage = "Model must be saved as .OMO before exporting to .SBO";
            statusService.updateStatus("Export failed: model not saved as .OMO");
            return;
        }

        Path omoPath = Path.of(omoPathStr);

        // Show save dialog for the .SBO file
        fileDialogService.showSaveSBODialog(filePath -> {
            boolean success = serializer.export(params, omoPath, filePath);
            if (success) {
                statusService.updateStatus("Exported SBO: " + Path.of(filePath).getFileName());
                visible.set(false);
                logger.info("SBO export successful: {}", filePath);
            } else {
                validationMessage = "Export failed. Check logs for details.";
                statusService.updateStatus("SBO export failed");
            }
        });
    }

    /**
     * Builds export parameters from the current form field values.
     */
    private SBOFormat.ExportParameters buildParameters() {
        SBOFormat.ExportParameters params = new SBOFormat.ExportParameters();
        params.setObjectId(objectId.get().trim());
        params.setObjectName(objectName.get().trim());
        params.setObjectType(SBOFormat.ObjectType.values()[objectTypeIndex.get()]);
        params.setObjectPack(objectPack.get().trim());
        params.setAuthor(author.get().trim());
        params.setDescription(description.get().trim());
        if (statesSection.isEnabled()) {
            params.setStatesEnabled(true);
            params.setStates(statesSection.toStateSpecs());
            params.setDefaultStateName(statesSection.getDefaultStateName());
        }
        if (numericId.get() >= 0) {
            params.setGameProperties(buildDefaultGameProperties());
        }
        return params;
    }

    /**
     * Minimal {@code GameProperties} populated from the form's Numeric ID and
     * sensible defaults for the selected object type. The full set of
     * properties (atlas coords, hardness, render layer, etc.) is authored
     * later via the SBO Editor.
     */
    private SBOFormat.GameProperties buildDefaultGameProperties() {
        boolean isBlock = blockSelected();
        int[] slot = isBlock ? findFreeAtlasSlot() : new int[]{-1, -1};
        return new SBOFormat.GameProperties(
                numericId.get(),
                /* hardness    */ 1.0f,
                /* solid       */ isBlock,
                /* breakable   */ true,
                /* atlasX      */ slot[0],
                /* atlasY      */ slot[1],
                /* renderLayer */ "OPAQUE",
                /* transparent */ false,
                /* flower      */ false,
                /* stackable   */ true,
                /* maxStack    */ 64,
                /* category    */ isBlock ? "BLOCKS" : "MATERIALS",
                /* placeable   */ isBlock
        );
    }

    /**
     * Return the first free {@code [tileX, tileY]} on the texture atlas in
     * row-major order. Occupancy is computed fresh on every call as the
     * union of:
     * <ul>
     *   <li>baked entries in {@code texture atlas/atlas_metadata.json}</li>
     *   <li>declared {@code atlasX/atlasY} (≥ 0) in every {@code .sbo} file
     *       currently in {@code sbo/blocks/} on disk</li>
     * </ul>
     *
     * <p>Because the SBO scan reads the live filesystem (via
     * {@link SBOObjectIndex#discover}), back-to-back exports automatically
     * see prior writes, and deleted SBOs free their slot. No in-memory
     * reservation needed.
     *
     * <p>Returns {@code [-1, -1]} when the atlas is fully occupied or the
     * metadata is unreachable — callers should treat that as "no slot" and
     * rely on the game's runtime dynamic allocator.
     */
    private int[] findFreeAtlasSlot() {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("texture atlas/atlas_metadata.json")) {
            if (in == null) {
                logger.debug("atlas_metadata.json not on classpath — defaulting atlasX/Y to -1");
                return new int[]{-1, -1};
            }
            JsonNode root = new ObjectMapper().readTree(in);
            int tileSize = Math.max(1, root.path("textureSize").asInt(16));
            JsonNode size = root.path("atlasSize");
            int cols = Math.max(1, size.path("width").asInt(256) / tileSize);
            int rows = Math.max(1, size.path("height").asInt(256) / tileSize);

            Set<Long> occupied = new HashSet<>();
            markBakedTiles(root.path("textures"), tileSize, cols, rows, occupied);
            markSboDeclaredTiles(cols, rows, occupied);

            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (!occupied.contains(((long) r << 32) | (c & 0xFFFFFFFFL))) {
                        return new int[]{c, r};
                    }
                }
            }
            logger.warn("Atlas is fully occupied — defaulting atlasX/Y to -1");
        } catch (IOException e) {
            logger.warn("Failed to scan atlas_metadata.json for free slot — defaulting atlasX/Y to -1", e);
        }
        return new int[]{-1, -1};
    }

    /**
     * Mark every tile covered by a baked {@code atlas_metadata.json} entry.
     * Off-grid pixel rects are rounded out to all tiles they touch.
     */
    private static void markBakedTiles(JsonNode textures, int tileSize, int cols, int rows, Set<Long> occupied) {
        Iterator<JsonNode> it = textures.elements();
        while (it.hasNext()) {
            JsonNode entry = it.next();
            int x = entry.path("x").asInt(-1);
            int y = entry.path("y").asInt(-1);
            if (x < 0 || y < 0) continue;
            int w = Math.max(1, entry.path("width").asInt(tileSize));
            int h = Math.max(1, entry.path("height").asInt(tileSize));
            int c0 = x / tileSize;
            int r0 = y / tileSize;
            int c1 = (x + w - 1) / tileSize;
            int r1 = (y + h - 1) / tileSize;
            for (int r = r0; r <= r1 && r < rows; r++) {
                for (int c = c0; c <= c1 && c < cols; c++) {
                    occupied.add(((long) r << 32) | (c & 0xFFFFFFFFL));
                }
            }
        }
    }

    /**
     * Mark the tile declared by each {@code .sbo} in {@code sbo/blocks/} whose
     * {@code gameProperties.atlasX/atlasY} are both ≥ 0. Unreadable files are
     * skipped — best-effort, since one bad file shouldn't block exporting.
     */
    private static void markSboDeclaredTiles(int cols, int rows, Set<Long> occupied) {
        SBOParser parser = new SBOParser();
        for (Path path : SBOObjectIndex.discover("sbo/blocks")) {
            try {
                SBOParser.RawParse raw = parser.parseRaw(path);
                SBOFormat.GameProperties gp = raw.manifest().gameProperties();
                if (gp == null) continue;
                int c = gp.atlasX();
                int r = gp.atlasY();
                if (c < 0 || r < 0 || c >= cols || r >= rows) continue;
                occupied.add(((long) r << 32) | (c & 0xFFFFFFFFL));
            } catch (IOException ignored) {
                // best-effort; skip unreadable SBOs
            }
        }
    }

    /**
     * Get the accent color from the theme with fallback.
     */
    private ImVec4 getAccentColor(ThemeDefinition theme) {
        ImVec4 accent = theme.getColor(ImGuiCol.HeaderActive);
        if (accent == null) accent = theme.getColor(ImGuiCol.ButtonHovered);
        if (accent == null) accent = new ImVec4(0.36f, 0.61f, 0.84f, 1.0f);
        return accent;
    }
}
