package com.openmason.main.systems.menus.textureCreator;

import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping.UVRegion;
import com.openmason.main.systems.menus.textureCreator.canvas.CanvasShapeMask;
import com.openmason.main.systems.menus.textureCreator.canvas.CanvasState;
import com.openmason.main.systems.menus.textureCreator.commands.CommandHistory;
import com.openmason.main.systems.menus.textureCreator.layers.LayerManager;
import com.openmason.main.systems.menus.textureCreator.selection.SelectionManager;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One texture open in the Texture Editor: its layers, its own undo history, view (zoom/pan),
 * selection, file and dirty state, and — for a model face being edited — the face it is bound
 * to. The editor shows one document at a time (the controller's active one); tools, colours,
 * palettes and preferences are shared by all of them.
 *
 * <p>Main/GL thread only, like the rest of the editor.
 */
public final class TextureDocument {

    /** The face of the model a face document paints, and the mask it paints through. */
    public static final class FaceBinding {
        private final int faceId;
        private final int materialId;
        private final UVRegion uvRegion;
        private final CanvasShapeMask mask;
        /** The canvas mask before the face mask was composed onto it (restored on unbind). */
        private final CanvasShapeMask baseMask;

        FaceBinding(int faceId, int materialId, UVRegion uvRegion, CanvasShapeMask mask,
                    CanvasShapeMask baseMask) {
            this.faceId = faceId;
            this.materialId = materialId;
            this.uvRegion = uvRegion;
            this.mask = mask;
            this.baseMask = baseMask;
        }

        /** The model face, or -1 when the caller did not say which face it was. */
        public int faceId() {
            return faceId;
        }

        public int materialId() {
            return materialId;
        }

        public UVRegion uvRegion() {
            return uvRegion;
        }

        public CanvasShapeMask mask() {
            return mask;
        }

        CanvasShapeMask baseMask() {
            return baseMask;
        }
    }

    private final String id;
    private final String untitledName;
    private LayerManager layerManager;
    private final CommandHistory commandHistory = new CommandHistory();
    private final CanvasState canvasState = new CanvasState();
    private final SelectionManager selectionManager = new SelectionManager();
    private TextureCreatorState.CanvasSize canvasSize;
    private String filePath;
    private boolean projectFile;
    private boolean unsavedChanges;
    private FaceBinding face;

    /**
     * @param id           stable id for the session (MCP and the tab strip address documents by it)
     * @param untitledName title shown while the document has no file
     * @param layers       the document's layer stack
     */
    TextureDocument(String id, String untitledName, LayerManager layers) {
        this.id = Objects.requireNonNull(id, "id");
        this.untitledName = Objects.requireNonNull(untitledName, "untitledName");
        setLayerManager(layers);
    }

    public String id() {
        return id;
    }

    /** Tab title: "Face 12", the file name, or the untitled name. */
    public String title() {
        if (face != null) {
            return face.faceId >= 0 ? "Face " + face.faceId : "Face (material " + face.materialId + ")";
        }
        if (filePath != null && !filePath.isBlank()) {
            Path name = Path.of(filePath).getFileName();
            return name != null ? name.toString() : filePath;
        }
        return untitledName;
    }

    public LayerManager getLayerManager() {
        return layerManager;
    }

    /** Replaces the whole layer stack (new canvas, import, face pixels); the canvas size follows it. */
    void setLayerManager(LayerManager layers) {
        this.layerManager = Objects.requireNonNull(layers, "layers");
        this.canvasSize = new TextureCreatorState.CanvasSize(layers.getCanvasWidth(), layers.getCanvasHeight());
    }

    public CommandHistory getCommandHistory() {
        return commandHistory;
    }

    public CanvasState getCanvasState() {
        return canvasState;
    }

    public SelectionManager getSelectionManager() {
        return selectionManager;
    }

    public TextureCreatorState.CanvasSize getCanvasSize() {
        return canvasSize;
    }

    void setCanvasSize(TextureCreatorState.CanvasSize size) {
        this.canvasSize = Objects.requireNonNull(size, "size");
    }

    public String getFilePath() {
        return filePath;
    }

    void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    /** True when the document was opened from or saved as a .omt project. */
    public boolean isProjectFile() {
        return projectFile;
    }

    void setProjectFile(boolean projectFile) {
        this.projectFile = projectFile;
    }

    /** The .omt this document is saved to, or null (untitled, PNG import, face). */
    public String projectPath() {
        return projectFile && filePath != null && !filePath.isBlank() ? filePath : null;
    }

    public boolean hasUnsavedChanges() {
        return unsavedChanges;
    }

    void setUnsavedChanges(boolean unsavedChanges) {
        this.unsavedChanges = unsavedChanges;
    }

    /**
     * Whether closing this document loses work: a standalone document with unsaved edits. A face
     * document never asks — its edits already live on the model's texture and closing it saves
     * the model.
     */
    public boolean needsSavePrompt() {
        return face == null && unsavedChanges;
    }

    public boolean isFace() {
        return face != null;
    }

    /** The bound face, or null for a standalone texture. */
    public FaceBinding face() {
        return face;
    }

    void setFace(FaceBinding face) {
        this.face = face;
    }

    /** Whether this document paints {@code faceId} (or, with no face id known, {@code materialId}). */
    public boolean isBoundTo(int faceId, int materialId) {
        if (face == null) {
            return false;
        }
        return faceId >= 0 && face.faceId >= 0 ? face.faceId == faceId : face.materialId == materialId;
    }

    @Override
    public String toString() {
        return "TextureDocument[" + id + " '" + title() + "']";
    }
}
