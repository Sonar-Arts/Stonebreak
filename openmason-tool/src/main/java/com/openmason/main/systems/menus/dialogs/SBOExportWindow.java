package com.openmason.main.systems.menus.dialogs;

import com.openmason.api.asset.AssetEditorSection;
import com.openmason.api.asset.AssetSectionHost;
import com.openmason.api.asset.AssetSurface;
import com.openmason.main.systems.io.BuiltinFileTypes;
import com.openmason.main.systems.target.GameTargets;
import com.openmason.main.systems.themes.utils.ThemeColors.Tone;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import com.openmason.engine.format.sbe.AnimationCompatibility;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOSerializer;
import com.openmason.main.systems.services.StatusService;
import com.openmason.main.systems.stateHandling.ModelState;
import com.openmason.main.systems.themes.core.ThemeManager;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiSelectableFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Export "start screen" for Stonebreak Object ({@code .sbo}) files.
 *
 * <p>Collects the minimum an SBO needs to exist — identity, classification and
 * optional states, plus whatever game data the plugins' {@link AssetEditorSection}s
 * for {@link AssetSurface#SBO_EXPORT} add (#431; Stonebreak: the numeric id) — in
 * the same chrome/tab layout as the {@link SBOEditorWindow}, writes the file into
 * the game target's folder for the object type and then hands the new file to
 * the SBO editor so the remaining metadata is authored in place. Every field is
 * reset on {@link #show()}; nothing leaks between exports.
 *
 * <p>Two sources share this one window: the model viewer opens it with
 * {@link #show()} (payload = the current {@code .omo}), the Texture Editor with
 * {@link #showForTexture()} (payload = the current {@code .omt}, written as a
 * texture-only SBO; defaults to an Item, and the model-only object types are
 * locked).
 */
public class SBOExportWindow {

    private static final Logger logger = LoggerFactory.getLogger(SBOExportWindow.class);

    private static final String WINDOW_TITLE = "Export SBO";
    private static final float WINDOW_W = 720.0f;
    private static final float WINDOW_H = 640.0f;

    private static final String[] OBJECT_TYPE_LABELS = {
            "Block", "Item", "Entity", "Decoration", "Particle", "Other"
    };
    private static final String TAB_METADATA = "core.metadata";
    private static final String TAB_STATES = "core.states";
    private static final List<AssetSectionSet.Tab> CORE_TABS = List.of(
            new AssetSectionSet.Tab(TAB_METADATA, "Metadata", 0, null),
            new AssetSectionSet.Tab(TAB_STATES, "States", 200, null));

    /** What the export wraps: the viewport model or the texture editor's canvas. */
    private enum Source { MODEL, TEXTURE }

    private final ImBoolean visible;
    private final ModelState modelState;
    private final StatusService statusService;
    private final FileDialogService fileDialogService;
    private final SBOSerializer serializer = new SBOSerializer();
    private final SBOStatesSection modelStates;
    private final SBOStatesSection textureStates;
    /** Game-data sections contributed by plugins (#431). */
    private final AssetSectionSet<SBOFormat.ExportParameters> sections;
    private List<AssetSectionSet.Tab> tabs = CORE_TABS;

    /** Same Mortar chrome as the editors, in export mode. */
    private final EditorChrome chrome = new EditorChrome("sbo_export");
    private int selectedTab;
    private Source source = Source.MODEL;

    /** Current texture editor {@code .omt} (null when unsaved or not a project file). */
    private Supplier<String> omtPathSupplier = () -> null;

    // Form buffers
    private final ImString objectId = new ImString(256);
    private final ImString objectName = new ImString(256);
    private final ImInt objectTypeIndex = new ImInt(0);
    private final ImString objectPack = new ImString(256);
    private final ImString author = new ImString(256);
    private final ImString description = new ImString(1024);

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
        this.modelStates = new SBOStatesSection(
                /* modelKind */ true,
                callback -> fileDialogService.showOpenOMOInProjectDialog(callback::accept),
                callback -> fileDialogService.showOpenOMADialog(callback::accept),
                modelState::getCurrentOMOFilePath
        );
        this.textureStates = new SBOStatesSection(
                /* modelKind */ false,
                callback -> fileDialogService.showOpenOMTInProjectDialog(callback::accept),
                /* clipPicker */ null, // texture-only SBOs cannot carry animation clips
                () -> omtPathSupplier.get()
        );
        this.sections = new AssetSectionSet<>(AssetSurface.SBO_EXPORT, new AssetSectionHost() {
            @Override
            public AssetSurface<?> surface() {
                return AssetSurface.SBO_EXPORT;
            }

            @Override
            public String objectId() {
                return objectId.get().trim();
            }

            @Override
            public String objectType() {
                return SBOFormat.ObjectType.values()[objectTypeIndex.get()].getId();
            }

            @Override
            public boolean texturePayload() {
                return textureSource();
            }

            @Override
            public void markDirty() {
                // an export form has no saved state
            }
        });
    }

    /** Wire the texture editor's current {@code .omt} (texture-context source). */
    public void setOMTPathSupplier(Supplier<String> supplier) {
        this.omtPathSupplier = supplier != null ? supplier : () -> null;
    }

    /** Wire the post-export handoff (typically {@code sboEditorWindow::openFile}). */
    public void setOnExported(Consumer<String> onExported) {
        this.onExported = onExported != null ? onExported : p -> { };
    }

    /**
     * Shows the window with a clean form, pre-populated from the current model.
     */
    public void show() {
        open(Source.MODEL);
    }

    /**
     * Shows the window in texture context: the current {@code .omt} becomes a
     * texture-only SBO, defaulting to an Item exported into {@code sbo/items}.
     */
    public void showForTexture() {
        open(Source.TEXTURE);
    }

    private void open(Source newSource) {
        source = newSource;
        resetForm();
        if (source == Source.TEXTURE) {
            objectTypeIndex.set(SBOFormat.ObjectType.ITEM.ordinal());
        }
        prepopulateFromSource();
        sections.load(new SBOFormat.ExportParameters());
        tabs = sections.tabs(CORE_TABS);
        visible.set(true);
        centerOnNextFrame = true;
        logger.debug("SBO export window shown ({} source)", source);
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
        modelStates.reset();
        textureStates.reset();
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
                        tabLabels(), selectedTab, this::performExport, this::hide);
                ImGui.dummy(0, 6);
                AssetSectionSet.Tab tab = tabs.get(Math.min(selectedTab, tabs.size() - 1));
                if (tab.section() != null) {
                    sections.renderSection(tab.section());
                } else if (TAB_METADATA.equals(tab.key())) {
                    renderMetadataTab();
                } else if (TAB_STATES.equals(tab.key())) {
                    renderStatesTab();
                }
                if (!validationMessage.isEmpty()) {
                    ImGui.dummy(0, 8);
                    ThemedWidgets.inlineError(validationMessage);
                }
                sections.renderOverlays();
            } catch (Exception e) {
                logger.error("Error rendering SBO export window", e);
                ThemedWidgets.statusText(Tone.ERROR, "Error rendering export window");
            }
        }
        ImGui.end();
    }

    private boolean textureSource() {
        return source == Source.TEXTURE;
    }

    /** The states section matching the payload kind (OMO states vs OMT states). */
    private SBOStatesSection states() {
        return textureSource() ? textureStates : modelStates;
    }

    /** The current source file ({@code .omt} in texture context, else {@code .omo}), or null. */
    private String currentSourcePath() {
        String path = textureSource() ? omtPathSupplier.get() : modelState.getCurrentOMOFilePath();
        return path != null && !path.isBlank() ? path : null;
    }

    private String sourceSuffix() {
        String path = currentSourcePath();
        return path != null ? " - " + Path.of(path).getFileName() : "";
    }

    private String sourceLabel() {
        String path = currentSourcePath();
        if (textureSource()) {
            return path != null ? "Texture: " + Path.of(path).getFileName() : "Texture not saved as .OMT";
        }
        return path != null ? "Model: " + Path.of(path).getFileName() : "Model not saved as .OMO";
    }

    private boolean canExport() {
        return states().isEnabled() || currentSourcePath() != null;
    }

    private void renderMetadataTab() {
        ThemedWidgets.sectionLabel("Identity");
        ImGui.inputTextWithHint("Object ID", "e.g. " + GameTargets.shared().qualify("oak_planks"), objectId);
        ImGui.inputTextWithHint("Object Name", "e.g. Oak Planks", objectName);

        ThemedWidgets.sectionLabel("Classification");
        renderObjectTypeCombo();
        ImGui.textDisabled("Exports into " + describeTargetFolder());
        ImGui.inputTextWithHint("Pack", "e.g. default, expansion_1", objectPack);
        sections.renderMetadataSections();

        ThemedWidgets.sectionLabel("Attribution");
        ImGui.inputTextWithHint("Author", "Creator name or studio", author);
        ImGui.text("Description");
        ImGui.inputTextMultiline("##desc", description, -1, 80);
    }

    /**
     * Object Type combo. In texture context the model-only types (Block,
     * Entity) are listed but locked, since a texture-only payload has no mesh.
     *
     * @return true when the selection changed
     */
    private boolean renderObjectTypeCombo() {
        if (!textureSource()) {
            return ImGui.combo("Object Type", objectTypeIndex, OBJECT_TYPE_LABELS);
        }
        boolean changed = false;
        if (ImGui.beginCombo("Object Type", OBJECT_TYPE_LABELS[objectTypeIndex.get()])) {
            for (int i = 0; i < OBJECT_TYPE_LABELS.length; i++) {
                boolean allowed = textureObjectTypeAllowed(SBOFormat.ObjectType.values()[i]);
                int flags = allowed ? ImGuiSelectableFlags.None : ImGuiSelectableFlags.Disabled;
                if (ImGui.selectable(OBJECT_TYPE_LABELS[i], i == objectTypeIndex.get(), flags)
                        && i != objectTypeIndex.get()) {
                    objectTypeIndex.set(i);
                    changed = true;
                }
                if (!allowed && ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
                    ImGui.setTooltip("Needs a model - export it from the model viewer (Tools > Export SBO...)");
                }
            }
            ImGui.endCombo();
        }
        return changed;
    }

    /**
     * Whether a texture-only SBO may have this object type: blocks and entities need a model
     * (a texture payload has no mesh). Shared with the {@code sbo_export} MCP tool.
     */
    public static boolean textureObjectTypeAllowed(SBOFormat.ObjectType type) {
        return type != SBOFormat.ObjectType.BLOCK && type != SBOFormat.ObjectType.ENTITY;
    }

    private void renderStatesTab() {
        states().render();
    }

    private String describeTargetFolder() {
        GameTargets targets = GameTargets.shared();
        Path dir = targets.folderFor(BuiltinFileTypes.SBO.id(), OBJECT_TYPE_LABELS[objectTypeIndex.get()]);
        if (dir == null) {
            return targets.active().isPresent() ? "the last used folder (game resources not found)"
                    : "the last used folder (the project has no game target)";
        }
        Path root = targets.resourcesRoot();
        return root != null && dir.startsWith(root) ? root.relativize(dir).toString() : dir.toString();
    }

    private String[] tabLabels() {
        return tabs.stream().map(AssetSectionSet.Tab::label).toArray(String[]::new);
    }

    private int tabIndex(String key) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).key().equals(key)) return i;
        }
        return 0;
    }

    /** The tab showing {@code section} (Metadata for metadata-placed sections). */
    private int tabOf(AssetEditorSection<?> section) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).section() == section) return i;
        }
        return tabIndex(TAB_METADATA);
    }

    // ========================================
    // Export Logic
    // ========================================

    private void prepopulateFromSource() {
        String sourcePath = textureSource() ? omtPathSupplier.get() : modelState.getCurrentModelPath();
        if (sourcePath != null && !sourcePath.isBlank()) {
            String fileName = Path.of(sourcePath).getFileName().toString();
            String nameWithoutExt = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;
            objectName.set(nameWithoutExt);
            objectId.set(GameTargets.shared().qualify(nameWithoutExt.toLowerCase().replace(' ', '_')));
        }
    }

    private void performExport() {
        AssetSectionSet.Problem problem = sections.firstError();
        if (problem != null) {
            validationMessage = problem.message();
            selectedTab = tabOf(problem.section());
            return;
        }
        sections.confirmThenWrite(this::performExportConfirmed);
    }

    /** Mirrors the metadata half of {@code ExportParameters.getValidationError()}. */
    private static boolean hasMetadataGaps(SBOFormat.ExportParameters params) {
        return params.getObjectId().isBlank() || params.getObjectName().isBlank()
                || params.getObjectPack().isBlank() || params.getAuthor().isBlank();
    }

    private void performExportConfirmed() {
        SBOFormat.ExportParameters params = buildParameters();

        if (!params.isValid()) {
            validationMessage = params.getValidationError();
            // Identity/attribution errors live on Metadata; everything after them is a States error
            selectedTab = tabIndex(hasMetadataGaps(params) ? TAB_METADATA : TAB_STATES);
            return;
        }
        validationMessage = "";

        // Resolve the payload file (.omo, or .omt in texture context). When
        // states are enabled, the default state's path is the legacy/default
        // asset; otherwise we use the currently open source file.
        String sourcePathStr;
        if (states().isEnabled()) {
            sourcePathStr = params.getStates().stream()
                    .filter(s -> s.name().equals(params.getDefaultStateName()))
                    .map(SBOFormat.StateSpec::sourcePath)
                    .findFirst().orElse("");
        } else {
            sourcePathStr = currentSourcePath();
        }
        if (sourcePathStr == null || sourcePathStr.isBlank()) {
            String kind = textureSource() ? "Texture must be saved as .OMT" : "Model must be saved as .OMO";
            validationMessage = kind + " before exporting to .SBO";
            statusService.updateStatus(textureSource()
                    ? "Export failed: texture not saved as .OMT"
                    : "Export failed: model not saved as .OMO");
            return;
        }

        Path sourcePath = Path.of(sourcePathStr);

        if (!textureSource()) {
            String compatError = validateClipCompatibility(params);
            if (compatError != null) {
                validationMessage = compatError;
                selectedTab = tabIndex(TAB_STATES);
                statusService.updateStatus("Export blocked: animation/model mismatch");
                return;
            }
        }

        String typeLabel = OBJECT_TYPE_LABELS[objectTypeIndex.get()];
        String targetDir = GameTargets.shared().folderPathFor(BuiltinFileTypes.SBO.id(), typeLabel);
        Runnable written = sections::written;
        String fileName = GameTargets.shared().suggestedFileName(objectName.get(), "sbo", "object.sbo");

        fileDialogService.showSaveSBODialog(fileName, targetDir, filePath -> {
            boolean success = textureSource()
                    ? serializer.exportTexture(params, sourcePath, filePath)
                    : serializer.export(params, sourcePath, filePath);
            if (success) {
                statusService.updateStatus("Exported SBO: " + Path.of(filePath).getFileName());
                logger.info("SBO export successful: {}", filePath);
                visible.set(false);
                written.run();
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
        SBOStatesSection states = states();
        if (states.isEnabled()) {
            params.setStatesEnabled(true);
            params.setStates(states.toStateSpecs());
            params.setDefaultStateName(states.getDefaultStateName());
        }
        return sections.write(params);
    }

    /** Release the Mortar chrome region. Must run with a current GL context. */
    public void close() {
        chrome.close();
        modelStates.close();
        textureStates.close();
        sections.close();
    }
}
