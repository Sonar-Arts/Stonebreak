package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParser;
import com.openmason.engine.format.sbo.SBOSerializer;
import com.openmason.main.systems.services.StatusService;
import imgui.ImGui;
import imgui.flag.ImGuiTabBarFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Standalone editor window for {@code .sbo} files.
 *
 * <p>Loads an existing SBO from disk via {@link SBOParser#parseRaw(Path)},
 * exposes its manifest fields (identity, gameProperties, states, recipes) as
 * editable widgets across a tab bar, and writes the result back via
 * {@link SBOSerializer#exportFromDocument}. The embedded OMO/OMT bytes are
 * passed through unchanged — this editor is metadata-only.
 */
public class SBOEditorWindow {

    private static final Logger logger = LoggerFactory.getLogger(SBOEditorWindow.class);
    private static final String WINDOW_TITLE = "SBO Editor";

    private final ImBoolean visible;
    private final FileDialogService fileDialogService;
    private final StatusService statusService;
    private final SBOParser parser = new SBOParser();
    private final SBOSerializer serializer = new SBOSerializer();
    private final SBORecipeSection recipeSection;

    // Loaded document state
    private Path currentPath;
    private SBOFormat.Document loadedManifest;
    private byte[] loadedDefaultBytes;
    private java.util.Map<String, byte[]> loadedStateBytes;
    private boolean dirty;

    // Form buffers (mirror manifest)
    private final ImString objectId = new ImString(256);
    private final ImString objectName = new ImString(256);
    private final ImString objectPack = new ImString(256);
    private final ImString author = new ImString(256);
    private final ImString description = new ImString(1024);
    private final ImInt objectTypeIndex = new ImInt(0);

    // GameProperties buffers
    private boolean hasGameProperties;
    private final ImInt numericId = new ImInt(-1);
    private final ImFloat hardness = new ImFloat(1.0f);
    private final ImBoolean solid = new ImBoolean(true);
    private final ImBoolean breakable = new ImBoolean(true);
    private final ImInt atlasX = new ImInt(-1);
    private final ImInt atlasY = new ImInt(-1);
    private final ImInt renderLayerIndex = new ImInt(0);
    private final ImBoolean transparent = new ImBoolean(false);
    private final ImBoolean flower = new ImBoolean(false);
    private final ImBoolean stackable = new ImBoolean(false);
    private final ImInt maxStackSize = new ImInt(64);
    private final ImString category = new ImString(64);
    private final ImBoolean placeable = new ImBoolean(true);

    private static final String[] OBJECT_TYPE_LABELS = {
            "block", "item", "entity", "decoration", "particle", "other"
    };
    private static final String[] RENDER_LAYER_LABELS = { "OPAQUE", "CUTOUT", "TRANSLUCENT" };

    public SBOEditorWindow(FileDialogService fileDialogService, StatusService statusService) {
        this.visible = new ImBoolean(false);
        this.fileDialogService = fileDialogService;
        this.statusService = statusService;
        this.recipeSection = new SBORecipeSection(() -> dirty = true);
    }

    /**
     * Open the editor: prompts for an SBO file, loads it on success.
     */
    public void openWithDialog() {
        if (fileDialogService == null) {
            logger.warn("Cannot open SBO editor: FileDialogService unavailable");
            return;
        }
        fileDialogService.showOpenSBODialog(this::loadFile);
    }

    /** Show editor without prompting (for "still has unsaved file" reopen). */
    public void show() {
        visible.set(true);
    }

    public boolean isVisible() {
        return visible.get();
    }

    private void loadFile(String pathStr) {
        try {
            Path path = Path.of(pathStr);
            SBOParser.RawParse raw = parser.parseRaw(path);
            this.currentPath = path;
            this.loadedManifest = raw.manifest();
            this.loadedDefaultBytes = raw.defaultBytes();
            this.loadedStateBytes = raw.stateBytes();
            populateBuffers(raw.manifest());
            this.dirty = false;
            this.visible.set(true);
            if (statusService != null) {
                statusService.updateStatus("Opened SBO: " + path.getFileName());
            }
        } catch (IOException e) {
            logger.error("Failed to load SBO {}", pathStr, e);
            if (statusService != null) statusService.updateStatus("Failed to open SBO");
        }
    }

    private void populateBuffers(SBOFormat.Document doc) {
        objectId.set(doc.objectId());
        objectName.set(doc.objectName());
        objectPack.set(doc.objectPack());
        author.set(doc.author());
        description.set(doc.description() != null ? doc.description() : "");
        objectTypeIndex.set(indexOf(OBJECT_TYPE_LABELS, doc.objectType()));

        SBOFormat.GameProperties gp = doc.gameProperties();
        hasGameProperties = gp != null;
        if (gp != null) {
            numericId.set(gp.numericId());
            hardness.set(gp.hardness());
            solid.set(gp.solid());
            breakable.set(gp.breakable());
            atlasX.set(gp.atlasX());
            atlasY.set(gp.atlasY());
            renderLayerIndex.set(indexOf(RENDER_LAYER_LABELS, gp.renderLayerOrDefault()));
            transparent.set(gp.transparent());
            flower.set(gp.flower());
            stackable.set(gp.stackable());
            maxStackSize.set(gp.maxStackSize());
            category.set(gp.categoryOrDefault());
            placeable.set(gp.placeable());
        }

        recipeSection.setFromRecipeData(doc.recipes());
    }

    public void render() {
        if (!visible.get()) return;

        ImGui.setNextWindowSize(720, 640, imgui.flag.ImGuiCond.FirstUseEver);
        int flags = ImGuiWindowFlags.NoCollapse;
        String title = WINDOW_TITLE
                + (currentPath != null ? " — " + currentPath.getFileName() : "")
                + (dirty ? " *" : "")
                + "###sbo_editor";
        if (ImGui.begin(title, visible, flags)) {
            renderToolbar();
            ImGui.separator();
            if (loadedManifest == null) {
                ImGui.textDisabled("No SBO loaded. Use File > Open... or Tools > SBO Editor.");
            } else {
                renderTabs();
            }
        }
        ImGui.end();
    }

    private void renderToolbar() {
        boolean canSave = loadedManifest != null && dirty;
        if (!canSave) ImGui.beginDisabled();
        if (ImGui.button("Save")) {
            saveInPlace();
        }
        if (!canSave) ImGui.endDisabled();
        ImGui.sameLine();
        if (ImGui.button("Save As...")) {
            saveAs();
        }
        ImGui.sameLine();
        if (ImGui.button("Open Different SBO...")) {
            openWithDialog();
        }
    }

    private void renderTabs() {
        if (ImGui.beginTabBar("##sbo_editor_tabs", ImGuiTabBarFlags.Reorderable)) {
            if (ImGui.beginTabItem("Metadata")) {
                renderMetadataTab();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Game Properties")) {
                renderGamePropertiesTab();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("States")) {
                renderStatesTab();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Recipes")) {
                recipeSection.render();
                ImGui.endTabItem();
            }
            ImGui.endTabBar();
        }
    }

    private void renderMetadataTab() {
        if (ImGui.inputText("Object ID", objectId))    dirty = true;
        if (ImGui.inputText("Object Name", objectName)) dirty = true;
        if (ImGui.combo("Object Type", objectTypeIndex, OBJECT_TYPE_LABELS)) dirty = true;
        if (ImGui.inputText("Pack", objectPack))        dirty = true;
        if (ImGui.inputText("Author", author))          dirty = true;
        ImGui.text("Description");
        if (ImGui.inputTextMultiline("##desc", description, -1, 80)) dirty = true;
    }

    private void renderGamePropertiesTab() {
        if (ImGui.checkbox("Has gameProperties block", new ImBoolean(hasGameProperties))) {
            hasGameProperties = !hasGameProperties;
            dirty = true;
        }
        if (!hasGameProperties) {
            ImGui.textDisabled("This SBO has no gameProperties block (legacy 1.0).");
            return;
        }
        if (ImGui.inputInt("Numeric ID", numericId))         dirty = true;
        if (ImGui.inputFloat("Hardness", hardness))          dirty = true;
        if (ImGui.checkbox("Solid", solid))                  dirty = true;
        if (ImGui.checkbox("Breakable", breakable))          dirty = true;
        if (ImGui.inputInt("Atlas X", atlasX))               dirty = true;
        if (ImGui.inputInt("Atlas Y", atlasY))               dirty = true;
        if (ImGui.combo("Render Layer", renderLayerIndex, RENDER_LAYER_LABELS)) dirty = true;
        if (ImGui.checkbox("Transparent", transparent))      dirty = true;
        if (ImGui.checkbox("Flower", flower))                dirty = true;
        if (ImGui.checkbox("Stackable", stackable))          dirty = true;
        if (ImGui.inputInt("Max Stack Size", maxStackSize))  dirty = true;
        if (ImGui.inputText("Category", category))           dirty = true;
        if (ImGui.checkbox("Placeable", placeable))          dirty = true;
    }

    private void renderStatesTab() {
        // States editing is complex (requires per-state asset paths); this
        // editor preserves states unchanged but does not yet expose adding /
        // removing them. Use the export window for new state authoring.
        if (loadedManifest.hasStates()) {
            ImGui.text("This SBO declares " + loadedManifest.states().size() + " state(s):");
            for (SBOFormat.StateEntry e : loadedManifest.states()) {
                String marker = e.name().equals(loadedManifest.defaultStateName()) ? " (default)" : "";
                ImGui.bulletText(e.name() + marker);
            }
        } else {
            ImGui.textDisabled("No states declared.");
        }
        ImGui.dummy(0, 8);
        ImGui.textDisabled("State editing is read-only here. Re-author via the export flow.");
    }

    private void saveInPlace() {
        if (currentPath == null) {
            saveAs();
            return;
        }
        writeTo(currentPath.toString());
    }

    private void saveAs() {
        if (fileDialogService == null) return;
        fileDialogService.showSaveSBODialog(this::writeTo);
    }

    private void writeTo(String pathStr) {
        if (loadedManifest == null) return;
        SBOFormat.Document edited = buildEditedDocument();
        boolean ok = serializer.exportFromDocument(edited, loadedDefaultBytes, loadedStateBytes, pathStr);
        if (ok) {
            currentPath = Path.of(pathStr);
            loadedManifest = edited;
            dirty = false;
            if (statusService != null) {
                statusService.updateStatus("Saved SBO: " + currentPath.getFileName());
            }
        } else if (statusService != null) {
            statusService.updateStatus("Failed to save SBO");
        }
    }

    private SBOFormat.Document buildEditedDocument() {
        SBOFormat.GameProperties gp = hasGameProperties
                ? new SBOFormat.GameProperties(
                        numericId.get(), hardness.get(), solid.get(), breakable.get(),
                        atlasX.get(), atlasY.get(), RENDER_LAYER_LABELS[renderLayerIndex.get()],
                        transparent.get(), flower.get(), stackable.get(),
                        maxStackSize.get(), category.get().trim(), placeable.get())
                : null;

        return new SBOFormat.Document(
                loadedManifest.version(),
                objectId.get().trim(),
                objectName.get().trim(),
                OBJECT_TYPE_LABELS[objectTypeIndex.get()],
                objectPack.get().trim(),
                loadedManifest.checksum(), // recomputed by serializer
                author.get().trim(),
                description.get().isBlank() ? null : description.get(),
                loadedManifest.createdAt(),
                loadedManifest.omoFilename(),
                loadedManifest.textureFilename(),
                gp,
                loadedManifest.states(),
                loadedManifest.defaultStateName(),
                recipeSection.toRecipeData()
        );
    }

    private static int indexOf(String[] arr, String value) {
        if (value == null) return 0;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equalsIgnoreCase(value)) return i;
        }
        return 0;
    }
}
