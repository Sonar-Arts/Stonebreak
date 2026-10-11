package com.openmason.main.systems.scripting.live;

import com.openmason.main.systems.menus.textureCreator.TextureCreatorController;
import com.openmason.main.systems.menus.textureCreator.TextureDocument;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.commands.CommandHistory;
import com.openmason.main.systems.menus.textureCreator.layers.LayerManager;
import com.openmason.main.systems.menus.textureCreator.layers.LayerStackSnapshot;
import com.openmason.main.systems.scripting.doc.CanvasSurface;

/**
 * One open texture document as a script canvas target — the one active when the run started,
 * pinned for the whole run so a tab switch cannot redirect it. Owns the run's
 * layer-stack journal: the before-snapshot is captured lazily on the first
 * mutation, so {@code ScriptingService} can roll a failed run back
 * ({@link #rollback()}) or record the run as ONE undo entry in that
 * document's own history ({@link #history()}).
 *
 * <p>GL/main-thread only, one instance per script run.
 */
public final class LiveCanvasSurface implements CanvasSurface {

    private final TextureCreatorController controller;
    private final TextureDocument document;
    private LayerStackSnapshot before;

    public LiveCanvasSurface(TextureCreatorController controller, TextureDocument document) {
        this.controller = controller;
        this.document = document;
    }

    @Override
    public LayerManager layers() {
        LayerManager lm = document.getLayerManager();
        if (lm == null) {
            throw new IllegalStateException("Texture editor has no layer manager");
        }
        return lm;
    }

    @Override
    public PixelCanvas activeCanvas() {
        var layer = layers().getActiveLayer();
        if (layer == null) {
            return null;
        }
        PixelCanvas canvas = layer.getCanvas();
        canvas.setSelectionManager(document.getSelectionManager()); // the document's own selection bounds the run
        return canvas;
    }

    @Override
    public void beginMutation() {
        if (before == null) {
            before = LayerStackSnapshot.capture(layers());
        }
    }

    @Override
    public void notifyModified() {
        controller.notifyModified(document);
    }

    @Override
    public boolean exportPng(String absolutePath) {
        return controller.exportDocument(document, absolutePath);
    }

    /** Whether the run mutated the canvas (journal captured). */
    public boolean touched() {
        return before != null;
    }

    /** The pre-run layer stack, or null when untouched. */
    public LayerStackSnapshot before() {
        return before;
    }

    /** Failed-run cleanup: restore the pre-run layer stack. */
    public void rollback() {
        if (before != null) {
            before.restore(layers());
            notifyModified();
        }
    }

    /** The document's own undo history (separate from the model's). */
    public CommandHistory history() {
        return document.getCommandHistory();
    }
}
