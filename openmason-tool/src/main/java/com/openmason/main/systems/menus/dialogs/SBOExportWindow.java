package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbe.AnimationCompatibility;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParser;
import com.openmason.engine.format.sbo.SBOSerializer;
import com.openmason.main.systems.menus.dialogs.validation.NumericIdConflictPopup;
import com.openmason.main.systems.menus.dialogs.validation.NumericIdValidator;
import com.openmason.main.systems.menus.dialogs.validation.TakenIdsPopup;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.stateHandling.ModelState;
import com.openmason.main.systems.themes.core.ThemeManager;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
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
import java.util.function.Consumer;

/**
 * Export "start screen" for Stonebreak Object ({@code .sbo}) files.
 *
 * <p>Collects the minimum an SBO needs to exist — identity, classification,
 * numeric ID and optional states — in the same chrome/tab layout as the
 * {@link SBOEditorWindow}, writes the file into the game's resource tree
 * ({@code sbo/blocks} / {@code sbo/items}) and then hands the new file to the
 * SBO editor so the remaining metadata (recipes, sounds, drops, ...) is
 * authored in place. Every field is reset on {@link #show()}; nothing leaks
 * between exports.
 */
public class SBOExportWindow {

    private static final Logger logger = LoggerFactory.getLogger(SBOExportWindow.class);

    private static final String WINDOW_TITLE = "Export SBO";
    private static final float WINDOW_W = 720.0f;
    private static final float WINDOW_H = 640.0f;

    private static final String[] OBJECT_TYPE_LABELS = {
            "Block", "Item", "Entity", "Decoration", "Particle", "Other"
    };
    private static final String[] TAB_LABELS = { "Metadata", "Game Properties", "States" };

    private final ImBoolean visible;
    private final ModelState modelState;
    private final StatusService statusService;
    private final FileDialogService fileDialogService;
    private final SBOSerializer serializer = new SBOSerializer();
    private final SBOStatesSection statesSection;
    private final NumericIdConflictPopup conflictPopup = new NumericIdConflictPopup();
    private final TakenIdsPopup takenIdsPopup = new TakenIdsPopup();

    /** Same Mortar chrome as the editors, in export mode. */
    private final EditorChrome chrome = new EditorChrome("sbo_export");
    private int selectedTab;

    // Form buffers
    private final ImString objectId = new ImString(256);
    private final ImString objectName = new ImString(256);
    private final ImInt objectTypeIndex = new ImInt(0);
    private final ImString objectPack = new ImString(256);
    private final ImString author = new ImString(256);
    private final ImString description = new ImString(1024);
    /** Required for blocks/items; -1 = no GameProperties block emitted. */
    private final ImInt numericId = new ImInt(-1);
    private int lastSuggestedDomainIndex = -1;

    private String validationMessage = "";
    private boolean centerOnNextFrame = false;

    /** Receives the exported path so the host can open it in the SBO editor. */
    private Consumer<String> onExported = p -> { };

    public SBOExportWindow(ImBoolean visible,
                           ThemeManager themeManager,
                           ModelState modelState,
                           StatusService statusService,
                           FileDialogService fileDialogService) {
        this.visible = visible;
        this.modelState = modelState;
        this.statusService = statusService;
        this.fileDialogService = fileDialogService;
        this.statesSection = new SBOStatesSection(
                /* modelKind */ true,
                callback -> fileDialogService.showOpenOMOInProjectDialog(callback::accept),
                callback -> fileDialogService.showOpenOMADialog(callback::accept),
                modelState::getCurrentOMOFilePath
        );
    }

    /** Wire the post-export handoff (typically {@code sboEditorWindow::openFile}). */
    public void setOnExported(Consumer<String> onExported) {
        this.onExported = onExported != null ? onExported : p -> { };
    }

    /**
     * Shows the window with a clean form, pre-populated from the current model.
     */
    public void show() {
        resetForm();
        prepopulateFromModel();
        suggestNumericIdForType();
        visible.set(true);
        centerOnNextFrame = true;
        logger.debug("SBO export window shown");
    }

    public void hide() {
        visible.set(false);
    }

    public boolean isVisible() {
        return visible.get();
    }

    /** Return every buffer to its initial state — no context survives between exports. */
    private void resetForm() {
        objectId.set("");
        objectName.set("");
        objectTypeIndex.set(0);
        objectPack.set("default");
        author.set("");
        description.set("");
        numericId.set(-1);
        lastSuggestedDomainIndex = -1;
        statesSection.reset();
        validationMessage = "";
        selectedTab = 0;
    }

    // ========================================
    // Rendering
    // ========================================

    public void render() {
        if (!visible.get()) {
            return;
        }

        ImGui.setNextWindowSize(WINDOW_W, WINDOW_H, ImGuiCond.FirstUseEver);
        if (centerOnNextFrame) {
            // Center on the app's main viewport (absolute screen coords under
            // multi-viewport) — size-only math would land on the primary monitor.
            float screenW = ImGui.getMainViewport().getSizeX();
            float screenH = ImGui.getMainViewport().getSizeY();
            float originX = ImGui.getMainViewport().getPosX();
            float originY = ImGui.getMainViewport().getPosY();
            ImGui.setNextWindowPos(originX + (screenW - WINDOW_W) * 0.5f, originY + (screenH - WINDOW_H) * 0.5f, ImGuiCond.Always);
            centerOnNextFrame = false;
        }

        String title = WINDOW_TITLE + sourceSuffix() + "###sbo_export";
        if (ImGui.begin(title, visible, ImGuiWindowFlags.NoCollapse)) {
            try {
                selectedTab = chrome.renderExport(canExport(), sourceLabel(), "Export...",
                        TAB_LABELS, selectedTab, this::performExport, this::hide);
                ImGui.dummy(0, 6);
                switch (selectedTab) {
                    case 0 -> renderMetadataTab();
                    case 1 -> renderGamePropertiesTab();
                    case 2 -> renderStatesTab();
                    default -> { }
                }
                if (!validationMessage.isEmpty()) {
                    ImGui.dummy(0, 8);
                    EditorWidgets.inlineError(validationMessage);
                }
                conflictPopup.render();
                takenIdsPopup.render();
            } catch (Exception e) {
                logger.error("Error rendering SBO export window", e);
                ImGui.textColored(1.0f, 0.0f, 0.0f, 1.0f, "Error rendering export window");
            }
        }
        ImGui.end();
    }

    private String sourceSuffix() {
        String omo = modelState.getCurrentOMOFilePath();
        return (omo != null && !omo.isBlank()) ? " - " + Path.of(omo).getFileName() : "";
    }

    private String sourceLabel() {
        String omo = modelState.getCurrentOMOFilePath();
        return (omo != null && !omo.isBlank())
                ? "Model: " + Path.of(omo).getFileName()
                : "Model not saved as .OMO";
    }

    private boolean canExport() {
        if (statesSection.isEnabled()) return true;
        String omo = modelState.getCurrentOMOFilePath();
        return omo != null && !omo.isBlank();
    }

    private void renderMetadataTab() {
        EditorWidgets.sectionLabel("Identity");
        ImGui.inputTextWithHint("Object ID", "e.g. stonebreak:oak_planks", objectId);
        ImGui.inputTextWithHint("Object Name", "e.g. Oak Planks", objectName);

        EditorWidgets.sectionLabel("Classification");
        if (ImGui.combo("Object Type", objectTypeIndex, OBJECT_TYPE_LABELS)) {
            suggestNumericIdForType();
        }
        ImGui.textDisabled("Exports into " + describeTargetFolder());
        ImGui.inputTextWithHint("Pack", "e.g. default, expansion_1", objectPack);

        EditorWidgets.sectionLabel("Attribution");
        ImGui.inputTextWithHint("Author", "Creator name or studio", author);
        ImGui.text("Description");
        ImGui.inputTextMultiline("##desc", description, -1, 80);
    }

    private void renderGamePropertiesTab() {
        EditorWidgets.sectionLabel("Identity");
        ImGui.pushItemWidth(140);
        ImGui.inputInt("Numeric ID", numericId);
        ImGui.popItemWidth();
        ImGui.sameLine();
        if (ImGui.smallButton("Taken IDs...##exp_taken")) {
            takenIdsPopup.open(currentDomain());
        }
        ImGui.textDisabled(blockSelected()
                ? "Required for blocks - unique across all blocks (chunk saves reference it)."
                : currentDomain() == NumericIdValidator.Domain.ITEM
                        ? "Unique across all items; -1 skips the gameProperties block."
                        : "Optional for this object type; -1 skips the gameProperties block.");
        renderConflictHint();

        EditorWidgets.sectionLabel("Defaults");
        ImGui.textDisabled(blockSelected()
                ? "Hardness 1.0, solid, breakable, OPAQUE layer, first free atlas tile."
                : "Hardness 1.0, stackable x64, MATERIALS category.");
        ImGui.textDisabled("Everything else (recipes, smelting, sounds, drops) is authored in the");
        ImGui.textDisabled("SBO Editor, which opens automatically after the export.");
    }

    private void renderStatesTab() {
        statesSection.render(ImGui.getCursorPosX(), 100.0f, 360.0f, 6.0f);
    }

    private void renderConflictHint() {
        NumericIdValidator.Domain domain = currentDomain();
        if (domain == NumericIdValidator.Domain.NONE) return;
        NumericIdValidator.Result result = NumericIdValidator.validate(
                domain, numericId.get(), objectId.get().trim());
        if (result instanceof NumericIdValidator.Result.Conflict c) {
            ImGui.textColored(1.0f, 0.55f, 0.45f, 1.0f,
                    "ID " + c.numericId() + " taken by " + c.existingObjectId());
        }
    }

    private String describeTargetFolder() {
        String dir = GameResourceDirs.sboFolderFor(OBJECT_TYPE_LABELS[objectTypeIndex.get()]);
        if (dir == null) return "the last used folder (game resources not found)";
        Path root = GameResourceDirs.resourcesRoot();
        Path p = Path.of(dir);
        return root != null && p.startsWith(root) ? root.relativize(p).toString() : dir;
    }

    private NumericIdValidator.Domain currentDomain() {
        return NumericIdValidator.domainFor(OBJECT_TYPE_LABELS[objectTypeIndex.get()]);
    }

    private boolean blockSelected() {
        return currentDomain() == NumericIdValidator.Domain.BLOCK;
    }

    /**
     * Re-suggest the numeric ID whenever the type's ID domain changes, so a
     * block gets the next free block ID and an item the next free item ID.
     * Engine sentinels without an SBO (water = 8) are excluded by the validator.
     */
    private void suggestNumericIdForType() {
        NumericIdValidator.Domain domain = currentDomain();
        int domainIndex = domain.ordinal();
        if (domainIndex == lastSuggestedDomainIndex) return;
        lastSuggestedDomainIndex = domainIndex;
        numericId.set(NumericIdValidator.suggestNextFreeId(domain));
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
            objectId.set("stonebreak:" + nameWithoutExt.toLowerCase().replace(' ', '_'));
        }
    }

    private void performExport() {
        if (blockSelected() && numericId.get() <= 0) {
            validationMessage = "Numeric ID must be > 0 for blocks (required for chunk save references)";
            selectedTab = 1;
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

        String compatError = validateClipCompatibility(params);
        if (compatError != null) {
            validationMessage = compatError;
            statusService.updateStatus("Export blocked: animation/model mismatch");
            return;
        }

        String typeLabel = OBJECT_TYPE_LABELS[objectTypeIndex.get()];
        String targetDir = GameResourceDirs.sboFolderFor(typeLabel);
        String fileName = GameResourceDirs.suggestedFileName(objectName.get(), "sbo", "object.sbo");

        fileDialogService.showSaveSBODialog(fileName, targetDir, filePath -> {
            boolean success = serializer.export(params, omoPath, filePath);
            if (success) {
                statusService.updateStatus("Exported SBO: " + Path.of(filePath).getFileName());
                logger.info("SBO export successful: {}", filePath);
                visible.set(false);
                onExported.accept(filePath);
            } else {
                validationMessage = "Export failed. Check logs for details.";
                statusService.updateStatus("SBO export failed");
            }
        });
    }

    /**
     * Validate each state clip's required parts against that state's own OMO
     * (unlike SBE, every SBO state carries a full model, so each clip is
     * checked against exactly the model it will animate). Returns null when
     * compatible. Falls open on read errors, mirroring the SBE export window.
     */
    private String validateClipCompatibility(SBOFormat.ExportParameters params) {
        for (SBOFormat.StateSpec spec : params.getStates()) {
            if (!spec.hasClip()) continue;

            java.util.List<String> requiredParts;
            java.util.List<String> availableParts;
            try {
                requiredParts = AnimationCompatibility.readOMARequiredParts(Path.of(spec.clipSourcePath()));
                availableParts = AnimationCompatibility.readOMOPartIds(Path.of(spec.sourcePath()));
            } catch (IOException e) {
                logger.warn("Could not check clip compatibility for state '{}': {}",
                        spec.name(), e.getMessage());
                continue;
            }
            if (availableParts.isEmpty()) continue;

            AnimationCompatibility.Result result =
                    AnimationCompatibility.check(requiredParts, availableParts);
            if (!result.isCompatible()) {
                return "State '" + spec.name()
                        + "' clip references parts missing from its model: "
                        + result.describeMissing();
            }
        }
        return null;
    }

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

    /** Release the Mortar chrome region. Must run with a current GL context. */
    public void close() {
        chrome.close();
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
}
