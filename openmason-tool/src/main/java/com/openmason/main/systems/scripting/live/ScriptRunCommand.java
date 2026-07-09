package com.openmason.main.systems.scripting.live;

import com.openmason.engine.rendering.model.GenericModelRenderer;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.main.systems.services.commands.MeshSnapshot;
import com.openmason.main.systems.services.commands.ModelCommand;
import com.openmason.main.systems.services.commands.RendererSynchronizer;

import java.util.List;

/**
 * Undo entry for a whole script run: restores BOTH the part manager (part
 * list, transforms, per-part geometry) and the combined mesh (topology, face
 * mappings, materials) — a script is one atomic step in the shared model
 * history.
 *
 * <p>Pixel deltas cover what the mesh snapshot can't: texel content of
 * pre-existing face textures the script painted. Textures the script CREATED
 * carry no delta and intentionally stay alive on the GPU across undo — the
 * mesh snapshot governs whether their material is mapped (same policy as the
 * MCP face-texture create path).
 *
 * <p>Restore order matters: parts first (their rebuild pushes a fresh
 * combined mesh through the pipeline), then the mesh snapshot (authoritative
 * for face mappings/materials), then pixel re-uploads, then renderer sync.
 */
public final class ScriptRunCommand implements ModelCommand {

    private final String description;
    private final PartManagerSnapshot partsBefore;
    private final MeshSnapshot meshBefore;
    private final PartManagerSnapshot partsAfter;
    private final MeshSnapshot meshAfter;
    private final List<TexturePixelDelta> pixelDeltas;
    private final ModelPartManager partManager;
    private final GenericModelRenderer gmr;
    private final RendererSynchronizer synchronizer;

    public ScriptRunCommand(String description,
                            PartManagerSnapshot partsBefore, MeshSnapshot meshBefore,
                            PartManagerSnapshot partsAfter, MeshSnapshot meshAfter,
                            List<TexturePixelDelta> pixelDeltas,
                            ModelPartManager partManager,
                            GenericModelRenderer gmr,
                            RendererSynchronizer synchronizer) {
        this.description = description;
        this.partsBefore = partsBefore;
        this.meshBefore = meshBefore;
        this.partsAfter = partsAfter;
        this.meshAfter = meshAfter;
        this.pixelDeltas = pixelDeltas != null ? pixelDeltas : List.of();
        this.partManager = partManager;
        this.gmr = gmr;
        this.synchronizer = synchronizer;
    }

    @Override
    public void execute() {
        restore(partsAfter, meshAfter, false);
    }

    @Override
    public void undo() {
        restore(partsBefore, meshBefore, true);
    }

    private void restore(PartManagerSnapshot parts, MeshSnapshot mesh, boolean toBefore) {
        parts.restore(partManager);
        mesh.restore(gmr);
        for (TexturePixelDelta delta : pixelDeltas) {
            gmr.updateTextureRegion(delta.gpuTextureId(), 0, 0,
                    delta.width(), delta.height(),
                    toBefore ? delta.before() : delta.after());
        }
        synchronizer.synchronize();
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
        throw new UnsupportedOperationException("Script runs are discrete undo steps");
    }
}
