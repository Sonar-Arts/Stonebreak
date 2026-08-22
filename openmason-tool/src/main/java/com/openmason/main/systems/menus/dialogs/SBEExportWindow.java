package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbe.AnimationCompatibility;
import com.openmason.engine.format.sbe.SBEFormat;
import com.openmason.engine.format.sbe.SBESerializer;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.stateHandling.ModelState;
import com.openmason.main.systems.themes.core.ThemeManager;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Export "start screen" for Stonebreak Entity ({@code .sbe}) files.
 *
 * <p>Mirrors {@link SBOExportWindow}: the same editor chrome and tab layout as
 * the {@link SBEEditorWindow}, a save dialog that opens straight in the game's
 * {@code sbe/} resource tree, a full form reset on every {@link #show()}, and
 * a handoff that opens the freshly written file in the SBE editor.
 */
public class SBEExportWindow {

    private static final Logger logger = LoggerFactory.getLogger(SBEExportWindow.class);

    private static final String WINDOW_TITLE = "Export SBE";
    private static final float WINDOW_W = 720.0f;
    private static final float WINDOW_H = 640.0f;

    private static final String[] ENTITY_TYPE_LABELS = {
            "Mob", "NPC", "Projectile", "Vehicle", "Other"
    };
    private static final String[] TAB_LABELS = { "Metadata", "States", "Variants" };

    private final ImBoolean visible;
    private final ModelState modelState;
    private final StatusService statusService;
    private final FileDialogService fileDialogService;
    private final SBESerializer serializer = new SBESerializer();
    private final SBEObjectIndexPopup objectIndexPopup = new SBEObjectIndexPopup();

    private final EditorChrome chrome = new EditorChrome("sbe_export");
    private int selectedTab;

    // Form buffers
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

    private String validationMessage = "";
    private boolean centerOnNextFrame = false;

    /** Receives the exported path so the host can open it in the SBE editor. */
    private Consumer<String> onExported = p -> { };

    /**
     * One row in the state bindings list. A state may declare an optional
     * model override and an optional animation clip — neither, either, or both.
     */
    private static final class StateBindingRow {
        final ImString state = new ImString(64);
        Path modelOverridePath;
        Path clipPath;
    }

    /**
     * One row in the variant bindings list. A variant may declare an optional
     * OMO override; without one the variant resolves to the base OMO at runtime.
     */
    private static final class VariantBindingRow {
        final ImString variant = new ImString(64);
        Path modelOverridePath;
    }

    public SBEExportWindow(ImBoolean visible,
                           ThemeManager themeManager,
                           ModelState modelState,
                           StatusService statusService,
                           FileDialogService fileDialogService) {
        this.visible = visible;
        this.modelState = modelState;
        this.statusService = statusService;
        this.fileDialogService = fileDialogService;
    }

    /** Wire the post-export handoff (typically {@code sbeEditorWindow::openFile}). */
    public void setOnExported(Consumer<String> onExported) {
        this.onExported = onExported != null ? onExported : p -> { };
    }

    public void show() {
        resetForm();
        prepopulateFromModel();
        visible.set(true);
        centerOnNextFrame = true;
        logger.debug("SBE export window shown");
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
        entityTypeIndex.set(0);
        objectPack.set("default");
        author.set("");
        description.set("");
        stateBindings.clear();
        variantBindings.clear();
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

        String title = WINDOW_TITLE + sourceSuffix() + "###sbe_export";
        if (ImGui.begin(title, visible, ImGuiWindowFlags.NoCollapse)) {
            try {
                selectedTab = chrome.renderExport(canExport(), sourceLabel(), "Export...",
                        TAB_LABELS, selectedTab, this::performExport, this::hide);
                ImGui.dummy(0, 6);
                switch (selectedTab) {
                    case 0 -> renderMetadataTab();
                    case 1 -> renderStatesTab();
                    case 2 -> renderVariantsTab();
                    default -> { }
                }
                if (!validationMessage.isEmpty()) {
                    ImGui.dummy(0, 8);
                    EditorWidgets.inlineError(validationMessage);
                }
                objectIndexPopup.render();
            } catch (Exception e) {
                logger.error("Error rendering SBE export window", e);
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
        String omo = modelState.getCurrentOMOFilePath();
        return omo != null && !omo.isBlank();
    }

    private void renderMetadataTab() {
        EditorWidgets.sectionLabel("Identity");
        ImGui.inputTextWithHint("Object ID", "e.g. stonebreak:cow", objectId);
        ImGui.sameLine();
        if (ImGui.smallButton("Registered IDs...##sbe_show_ids")) {
            objectIndexPopup.open();
        }
        ImGui.inputTextWithHint("Object Name", "e.g. Cow", objectName);

        EditorWidgets.sectionLabel("Classification");
        ImGui.combo("Entity Type", entityTypeIndex, ENTITY_TYPE_LABELS);
        ImGui.textDisabled("Exports into " + describeTargetFolder());
        ImGui.inputTextWithHint("Pack", "e.g. default, expansion_1", objectPack);

        EditorWidgets.sectionLabel("Attribution");
        ImGui.inputTextWithHint("Author", "Creator name or studio", author);
        ImGui.text("Description");
        ImGui.inputTextMultiline("##desc", description, -1, 80);
    }

    private void renderStatesTab() {
        EditorWidgets.sectionLabel("States (Optional)");
        ImGui.textDisabled("Each state may override the model and/or bind an animation clip.");
        ImGui.dummy(0, 4);

        if (stateBindings.isEmpty()) {
            ImGui.textDisabled("No states declared.");
        }
        int removeIndex = -1;
        for (int i = 0; i < stateBindings.size(); i++) {
            StateBindingRow row = stateBindings.get(i);
            ImGui.pushID("sbe_state_row_" + i);

            ImGui.pushItemWidth(160.0f);
            ImGui.inputTextWithHint("##state_name", "e.g. idle", row.state);
            ImGui.popItemWidth();
            ImGui.sameLine();
            if (ImGui.smallButton("Remove")) removeIndex = i;

            renderAssetSlot("Model:", row.modelOverridePath, "(use base OMO)",
                    () -> fileDialogService.showOpenOMOInProjectDialog(p -> {
                        if (p != null && !p.isBlank()) row.modelOverridePath = Path.of(p);
                    }),
                    () -> row.modelOverridePath = null);
            renderAssetSlot("Clip:", row.clipPath, "(no animation)",
                    () -> fileDialogService.showOpenOMADialog(p -> {
                        if (p != null && !p.isBlank()) row.clipPath = Path.of(p);
                    }),
                    () -> row.clipPath = null);

            ImGui.dummy(0, 4);
            ImGui.popID();
        }
        if (removeIndex >= 0) stateBindings.remove(removeIndex);

        ImGui.dummy(0, 4);
        if (ImGui.button("+ Add State##sbe_state_add", 120.0f, 0)) {
            stateBindings.add(new StateBindingRow());
        }
    }

    private void renderVariantsTab() {
        EditorWidgets.sectionLabel("Texture Variants (Optional)");
        ImGui.textDisabled("A variant without a model override resolves to the base OMO at runtime.");
        ImGui.dummy(0, 4);

        if (variantBindings.isEmpty()) {
            ImGui.textDisabled("No variants declared.");
        }
        int removeIndex = -1;
        for (int i = 0; i < variantBindings.size(); i++) {
            VariantBindingRow row = variantBindings.get(i);
            ImGui.pushID("sbe_variant_row_" + i);

            ImGui.pushItemWidth(160.0f);
            ImGui.inputTextWithHint("##variant_name", "e.g. angus", row.variant);
            ImGui.popItemWidth();
            ImGui.sameLine();
            if (ImGui.smallButton("Remove")) removeIndex = i;

            renderAssetSlot("Model:", row.modelOverridePath, "(use base OMO)",
                    () -> fileDialogService.showOpenOMOInProjectDialog(p -> {
                        if (p != null && !p.isBlank()) row.modelOverridePath = Path.of(p);
                    }),
                    () -> row.modelOverridePath = null);

            ImGui.dummy(0, 4);
            ImGui.popID();
        }
        if (removeIndex >= 0) variantBindings.remove(removeIndex);

        ImGui.dummy(0, 4);
        if (ImGui.button("+ Add Variant##sbe_variant_add", 120.0f, 0)) {
            variantBindings.add(new VariantBindingRow());
        }
    }

    /**
     * Labelled asset slot laid out like {@link EditorWidgets#assetSlot} but
     * backed by a path the exporter resolves at write time instead of bytes.
     */
    private void renderAssetSlot(String label, Path current, String emptyHint,
                                 Runnable onPick, Runnable onClear) {
        ImGui.pushID(label);
        ImGui.indent(20.0f);
        ImGui.textDisabled(label);
        ImGui.sameLine(80.0f);
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
            if (ImGui.smallButton("Set...")) onPick.run();
        }
        ImGui.unindent(20.0f);
        ImGui.popID();
    }

    private String describeTargetFolder() {
        String dir = GameResourceDirs.sbeFolderFor(ENTITY_TYPE_LABELS[entityTypeIndex.get()]);
        if (dir == null) return "the last used folder (game resources not found)";
        Path root = GameResourceDirs.resourcesRoot();
        Path p = Path.of(dir);
        return root != null && p.startsWith(root) ? root.relativize(p).toString() : dir;
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
        SBEFormat.ExportParameters params = buildParameters();

        if (!params.isValid()) {
            validationMessage = params.getValidationError();
            selectedTab = 0;
            return;
        }

        String stateError = validateStates();
        if (stateError != null) {
            validationMessage = stateError;
            selectedTab = 1;
            return;
        }
        String variantError = validateVariants();
        if (variantError != null) {
            validationMessage = variantError;
            selectedTab = 2;
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
            selectedTab = 1;
            statusService.updateStatus("SBE export blocked: incompatible animation");
            return;
        }

        String targetDir = GameResourceDirs.sbeFolderFor(ENTITY_TYPE_LABELS[entityTypeIndex.get()]);
        String fileName = GameResourceDirs.suggestedFileName(objectName.get(), "sbe", "entity.sbe");

        fileDialogService.showSaveSBEDialog(fileName, targetDir, filePath -> {
            boolean success = serializer.export(params, omoPath, filePath);
            if (success) {
                statusService.updateStatus("Exported SBE: " + Path.of(filePath).getFileName());
                logger.info("SBE export successful: {}", filePath);
                visible.set(false);
                onExported.accept(filePath);
            } else {
                validationMessage = "Export failed. Check logs for details.";
                statusService.updateStatus("SBE export failed");
            }
        });
    }

    /**
     * Validate each clip's required parts against the relevant model:
     * a state with a model override is checked against its override OMO,
     * otherwise against the base OMO. Returns null when all bindings are
     * compatible. Falls open on read errors — a disk error here should not
     * block the user from exporting.
     */
    private String validateAnimationCompatibility(Path baseOmoPath) {
        if (stateBindings.isEmpty()) return null;

        List<String> baseParts;
        try {
            baseParts = AnimationCompatibility.readOMOPartIds(baseOmoPath);
        } catch (IOException e) {
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
                } catch (IOException e) {
                    logger.warn("Could not read override parts from {}: {}",
                            row.modelOverridePath, e.getMessage());
                    continue;
                }
            }

            List<String> requiredParts;
            try {
                requiredParts = AnimationCompatibility.readOMARequiredParts(row.clipPath);
            } catch (IOException e) {
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
        Set<String> seen = new HashSet<>();
        for (StateBindingRow row : stateBindings) {
            String state = row.state.get().trim();
            if (state.isBlank()) return "State name cannot be blank";
            if (!seen.add(state)) return "Duplicate state: '" + state + "'";
        }
        return null;
    }

    private String validateVariants() {
        Set<String> seen = new HashSet<>();
        for (VariantBindingRow row : variantBindings) {
            String name = row.variant.get().trim();
            if (name.isBlank()) return "Variant name cannot be blank";
            if (!seen.add(name)) return "Duplicate variant: '" + name + "'";
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

    /** Release the Mortar chrome region. Must run with a current GL context. */
    public void close() {
        chrome.close();
    }
}
