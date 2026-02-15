package com.openmason.main.systems.services.commands;

import com.openmason.main.systems.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.main.systems.rendering.model.gmr.uv.FaceTextureMapping;

/**
 * Command for per-face texture/material assignment changes.
 *
 * <p>Stores the old and new {@link FaceTextureMapping} for a single face.
 * Not mergeable — each texture assignment is a discrete operation.
 */
public final class FaceTextureCommand implements ModelCommand {

    private final int faceId;
    private final FaceTextureMapping oldMapping; // null if face had no mapping before
    private final FaceTextureMapping newMapping;
    private final FaceTextureManager faceTextureManager;
    private final GenericModelRenderer gmr;

    public FaceTextureCommand(int faceId,
                              FaceTextureMapping oldMapping,
                              FaceTextureMapping newMapping,
                              FaceTextureManager faceTextureManager,
                              GenericModelRenderer gmr) {
        this.faceId = faceId;
        this.oldMapping = oldMapping;
        this.newMapping = newMapping;
        this.faceTextureManager = faceTextureManager;
        this.gmr = gmr;
    }

    @Override
    public void execute() {
        faceTextureManager.setFaceMapping(newMapping);
        gmr.refreshUVs();
    }

    @Override
    public void undo() {
        if (oldMapping == null) {
            faceTextureManager.removeFaceMapping(faceId);
        } else {
            faceTextureManager.setFaceMapping(oldMapping);
        }
        gmr.refreshUVs();
    }

    @Override
    public String getDescription() {
        return "Assign Face Texture";
    }

    @Override
    public boolean canMergeWith(ModelCommand other) {
        return false;
    }

    @Override
    public ModelCommand mergeWith(ModelCommand other) {
        throw new UnsupportedOperationException("FaceTextureCommand is not mergeable");
    }
}
