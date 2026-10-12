package com.openmason.main.systems.menus.textureCreator;

import com.openmason.main.systems.menus.textureCreator.canvas.CanvasChangeListener;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.layers.Layer;
import com.openmason.engine.rendering.model.GenericModelRenderer;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.engine.rendering.model.gmr.uv.MaterialDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real-time texture preview pipeline that pushes canvas edits to the 3D viewport.
 *
 * <p>Listens for pixel changes on the active layer's {@link PixelCanvas}, accumulates
 * a dirty bounding box per frame, and on {@link #flush()} composites visible layers
 * and uploads only the dirty region to the GPU via {@code glTexSubImage2D}.
 *
 * <p>This avoids per-pixel GPU calls by coalescing all changes within a frame into
 * a single partial texture upload.
 *
 * <p>Only the active document is listened to, and only a face document uploads (to its own
 * face's material). The pending dirty region always belongs to the document it was collected
 * on: switching tabs or closing a document flushes that document first, so its pixels never
 * land in another face's texture. Documents closed because the model changed are dropped
 * without a flush — their material ids may already name the new model's textures.
 */
public class TexturePreviewPipeline implements CanvasChangeListener, TextureCreatorController.DocumentListener {

    private static final Logger logger = LoggerFactory.getLogger(TexturePreviewPipeline.class);

    private final TextureCreatorController controller;
    private final GenericModelRenderer modelRenderer;

    // Dirty region accumulator
    private int dirtyMinX;
    private int dirtyMinY;
    private int dirtyMaxX;
    private int dirtyMaxY;
    private boolean hasDirtyRegion;

    // Currently registered canvas (for re-registration on layer switch) and its document
    private PixelCanvas registeredCanvas;
    private TextureDocument registeredDocument;

    private boolean enabled = true;

    /**
     * Create a new texture preview pipeline.
     *
     * @param controller    texture creator controller for composited canvas and face-region state
     * @param modelRenderer 3D model renderer for GPU texture updates
     */
    public TexturePreviewPipeline(TextureCreatorController controller,
                                  GenericModelRenderer modelRenderer) {
        this.controller = controller;
        this.modelRenderer = modelRenderer;
        resetDirtyRegion();
        controller.addDocumentListener(this);

        logger.info("TexturePreviewPipeline initialized");
    }

    // =========================================================================
    // CANVAS CHANGE LISTENER
    // =========================================================================

    @Override
    public void onCanvasChanged(PixelCanvas canvas, int dirtyX, int dirtyY,
                                int dirtyWidth, int dirtyHeight) {
        if (!enabled) {
            return;
        }

        // Expand the accumulated dirty bounding box
        int incomingMaxX = dirtyX + dirtyWidth;
        int incomingMaxY = dirtyY + dirtyHeight;

        if (!hasDirtyRegion) {
            dirtyMinX = dirtyX;
            dirtyMinY = dirtyY;
            dirtyMaxX = incomingMaxX;
            dirtyMaxY = incomingMaxY;
            hasDirtyRegion = true;
        } else {
            dirtyMinX = Math.min(dirtyMinX, dirtyX);
            dirtyMinY = Math.min(dirtyMinY, dirtyY);
            dirtyMaxX = Math.max(dirtyMaxX, incomingMaxX);
            dirtyMaxY = Math.max(dirtyMaxY, incomingMaxY);
        }
    }

    // =========================================================================
    // PER-FRAME FLUSH
    // =========================================================================

    /**
     * Flush pending dirty regions to the GPU. Call once per frame from the render loop.
     *
     * <p>If the active layer has changed since the last flush, re-registers the listener
     * on the correct canvas. Composites all visible layers into a single image and uploads
     * only the dirty sub-region via {@code glTexSubImage2D}.
     */
    public void flush() {
        if (!enabled || modelRenderer == null) {
            return;
        }

        // Upload what was collected on the registered document, then follow the active one
        uploadPending();
        ensureListenerRegistration();
    }

    /** Uploads the pending dirty region from the document it was collected on. */
    private void uploadPending() {
        if (!hasDirtyRegion) {
            return;
        }
        TextureDocument document = registeredDocument;
        if (document == null) {
            resetDirtyRegion();
            return;
        }

        // Resolve the target GPU texture ID
        int targetTextureId = resolveTargetTextureId(document);
        if (targetTextureId <= 0) {
            resetDirtyRegion();
            return;
        }

        // Get the composited canvas (all visible layers merged)
        PixelCanvas composited = document.getLayerManager().compositeLayersToCanvas();
        if (composited == null) {
            resetDirtyRegion();
            return;
        }

        // Clamp dirty region to canvas bounds
        int regionX = Math.max(0, dirtyMinX);
        int regionY = Math.max(0, dirtyMinY);
        int regionW = Math.min(composited.getWidth(), dirtyMaxX) - regionX;
        int regionH = Math.min(composited.getHeight(), dirtyMaxY) - regionY;

        if (regionW <= 0 || regionH <= 0) {
            resetDirtyRegion();
            return;
        }

        // Extract only the dirty sub-region as RGBA bytes
        byte[] rgbaBytes = composited.getPixelsAsRGBABytes(regionX, regionY, regionW, regionH);

        // Upload to GPU
        modelRenderer.updateTextureRegion(targetTextureId, regionX, regionY,
                                          regionW, regionH, rgbaBytes);

        resetDirtyRegion();
    }

    // =========================================================================
    // DOCUMENT EVENTS
    // =========================================================================

    @Override
    public void activated(TextureDocument document) {
        // Finish the previous document's pending edits, then listen to the new one at once so a
        // full-canvas notification right after opening a face is not missed.
        if (enabled && modelRenderer != null) {
            uploadPending();
        } else {
            resetDirtyRegion();
        }
        ensureListenerRegistration();
    }

    @Override
    public void closing(TextureDocument document, TextureCreatorController.CloseReason reason) {
        if (document != registeredDocument) {
            return;
        }
        if (reason == TextureCreatorController.CloseReason.MODEL_CHANGED || !enabled || modelRenderer == null) {
            resetDirtyRegion();
        } else {
            uploadPending();
        }
        unregisterFromCurrentCanvas();
    }

    // =========================================================================
    // LISTENER REGISTRATION
    // =========================================================================

    /**
     * Ensure the change listener is attached to the currently active layer's canvas.
     * Re-registers if the active layer has changed.
     */
    private void ensureListenerRegistration() {
        TextureDocument document = controller.hasOpenDocument() ? controller.getActiveDocument() : null;
        PixelCanvas activeCanvas = document != null ? getActiveLayerCanvas() : null;
        if (activeCanvas == null) {
            unregisterFromCurrentCanvas();
            return;
        }

        if (activeCanvas != registeredCanvas || document != registeredDocument) {
            if (document != registeredDocument) {
                resetDirtyRegion(); // anything left belongs to the old document and was flushed or dropped
            }
            unregisterFromCurrentCanvas();
            activeCanvas.addChangeListener(this);
            registeredCanvas = activeCanvas;
            registeredDocument = document;
        }
    }

    /**
     * Unregister from the currently tracked canvas.
     */
    private void unregisterFromCurrentCanvas() {
        if (registeredCanvas != null) {
            registeredCanvas.removeChangeListener(this);
            registeredCanvas = null;
        }
        registeredDocument = null;
    }

    /**
     * Get the active layer's canvas from the controller.
     */
    private PixelCanvas getActiveLayerCanvas() {
        var layerManager = controller.getLayerManager();
        if (layerManager == null) {
            return null;
        }
        Layer activeLayer = layerManager.getActiveLayer();
        return activeLayer != null ? activeLayer.getCanvas() : null;
    }

    // =========================================================================
    // TEXTURE RESOLUTION
    // =========================================================================

    /**
     * Resolve the GPU texture ID to upload to.
     *
     * <p>Only uploads when face-region editing is active, targeting that face's
     * material texture. Standalone (non-per-face) editing does NOT push changes
     * to the 3D viewport to maintain context separation.
     *
     * @return the target GPU texture ID, or -1 if no upload should occur
     */
    private int resolveTargetTextureId(TextureDocument document) {
        if (!document.isFace()) {
            // Standalone editing — do not push to the 3D model
            return -1;
        }

        int materialId = document.face().materialId();
        if (materialId >= 0) {
            FaceTextureManager ftm = modelRenderer.getFaceTextureManager();
            MaterialDefinition material = ftm.getMaterial(materialId);
            if (material != null && material.textureId() > 0) {
                return material.textureId();
            }
        }

        return -1;
    }

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    /**
     * Enable or disable the pipeline.
     *
     * @param enabled true to enable real-time preview, false to disable
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            resetDirtyRegion();
        }
    }

    /**
     * Check if the pipeline is enabled.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Dispose of the pipeline, unregistering all listeners.
     */
    public void dispose() {
        controller.removeDocumentListener(this);
        unregisterFromCurrentCanvas();
        resetDirtyRegion();
        logger.info("TexturePreviewPipeline disposed");
    }

    private void resetDirtyRegion() {
        dirtyMinX = Integer.MAX_VALUE;
        dirtyMinY = Integer.MAX_VALUE;
        dirtyMaxX = Integer.MIN_VALUE;
        dirtyMaxY = Integer.MIN_VALUE;
        hasDirtyRegion = false;
    }
}
