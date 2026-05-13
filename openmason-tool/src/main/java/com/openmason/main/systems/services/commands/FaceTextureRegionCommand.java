package com.openmason.main.systems.services.commands;

import com.openmason.engine.rendering.model.GenericModelRenderer;

/**
 * Undoable command for a rectangular GPU texture region update.
 *
 * <p>Captures the RGBA bytes of a region before and after an MCP-driven
 * per-face texture pixel commit. Redo re-uploads {@code after}; undo
 * re-uploads {@code before}, each via {@code glTexSubImage2D}.
 *
 * <p>Distinct from {@link SnapshotCommand} because {@link MeshSnapshot}
 * does not capture GPU texture pixel content — it only tracks materials
 * and face mappings. This command fills that gap.
 */
public final class FaceTextureRegionCommand implements ModelCommand {

    private final int gpuTextureId;
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final byte[] before;
    private final byte[] after;
    private final String description;
    private final GenericModelRenderer renderer;

    public FaceTextureRegionCommand(GenericModelRenderer renderer,
                                     int gpuTextureId,
                                     int x, int y, int width, int height,
                                     byte[] before, byte[] after,
                                     String description) {
        this.renderer = renderer;
        this.gpuTextureId = gpuTextureId;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.before = before;
        this.after = after;
        this.description = description;
    }

    @Override
    public void execute() {
        renderer.updateTextureRegion(gpuTextureId, x, y, width, height, after);
    }

    @Override
    public void undo() {
        renderer.updateTextureRegion(gpuTextureId, x, y, width, height, before);
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public boolean canMergeWith(ModelCommand other) {
        return false;
    }

    @Override
    public ModelCommand mergeWith(ModelCommand other) {
        throw new UnsupportedOperationException("FaceTextureRegionCommand is not mergeable");
    }
}
