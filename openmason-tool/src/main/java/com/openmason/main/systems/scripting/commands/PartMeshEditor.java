package com.openmason.main.systems.scripting.commands;

import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;
import com.openmason.engine.rendering.model.gmr.editable.MeshImporter;
import com.openmason.engine.rendering.model.gmr.editable.RenderMesh;
import com.openmason.engine.rendering.model.gmr.editable.RenderMeshBuilder;
import com.openmason.engine.rendering.model.gmr.parts.PartMeshRebuilder;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Per-part topology editing plumbing: import a part's stored geometry into an
 * {@link EditableMesh}, run ops against it, then flatten back to a
 * {@link PartMeshRebuilder.PartGeometry} with compact (0..n-1) local face ids.
 *
 * <p>Editing per part — rather than against the combined model mesh — keeps
 * the part manager's stored geometry authoritative, so topology edits survive
 * later transform changes and part rebuilds, and mesh ranges stay correct.
 * Face ids in the imported mesh equal the part-local face ids, because the
 * part's {@code triangleToFaceId} is what the importer groups by.
 */
public final class PartMeshEditor {

    private PartMeshEditor() {
    }

    /** An imported part mesh plus the soup-index → editable-vertex-id mapping. */
    public record ImportedPart(EditableMesh mesh, int[] soupToVertexId) {
    }

    /**
     * Flatten result: the new part geometry plus the map from pre-flatten
     * editable face ids to compact part-local face ids.
     */
    public record FlattenResult(PartMeshRebuilder.PartGeometry geometry,
                                Map<Integer, Integer> faceIdToLocal) {
    }

    /** Import a part's stored geometry; face ids in the mesh == part-local face ids. */
    public static ImportedPart importPart(PartMeshRebuilder.PartGeometry geo) {
        if (geo == null || geo.vertices() == null || geo.indices() == null) {
            throw new CommandException("Part has no geometry to edit");
        }
        MeshImporter.ImportResult imported = MeshImporter.importSoup(
                geo.vertices(), geo.texCoords(), geo.indices(), geo.triangleToFaceId());

        int soupCount = geo.vertices().length / 3;
        int[] soupToVertexId = new int[soupCount];
        java.util.Arrays.fill(soupToVertexId, -1);
        int[][] byVertex = imported.vertexIdToSoupIndices();
        for (int vertexId = 0; vertexId < byVertex.length; vertexId++) {
            int[] soups = byVertex[vertexId];
            if (soups == null) continue;
            for (int soup : soups) {
                if (soup >= 0 && soup < soupCount) {
                    soupToVertexId[soup] = vertexId;
                }
            }
        }
        return new ImportedPart(imported.mesh(), soupToVertexId);
    }

    /**
     * Derive part geometry from an edited mesh. Face ids are compacted to
     * 0..n-1 in ascending original-id order (part geometry requires contiguous
     * local face ids for {@code MeshRange} arithmetic); the returned map lets
     * callers translate op results and remap face-material assignments.
     *
     * <p>UV derivation uses an empty texture manager: authored per-corner UVs
     * pass through, faces without them get the default projection. Face-region
     * mappings are applied later, at combined-mesh render derivation — exactly
     * as the live pipeline treats part texCoords as authored input.
     */
    public static FlattenResult flatten(EditableMesh mesh) {
        RenderMesh rm = RenderMeshBuilder.build(mesh, new FaceTextureManager());
        if (rm.cornerCount() == 0 || rm.triangleCount() == 0) {
            throw new CommandException("Edit produced an empty mesh",
                    "the operation removed all faces; undo or rebuild the part");
        }

        TreeSet<Integer> distinctIds = new TreeSet<>();
        for (int faceId : rm.triangleToFaceId()) {
            distinctIds.add(faceId);
        }
        Map<Integer, Integer> faceIdToLocal = new LinkedHashMap<>();
        int next = 0;
        for (int id : distinctIds) {
            faceIdToLocal.put(id, next++);
        }

        int[] localTriToFace = new int[rm.triangleToFaceId().length];
        for (int i = 0; i < localTriToFace.length; i++) {
            localTriToFace[i] = faceIdToLocal.get(rm.triangleToFaceId()[i]);
        }

        PartMeshRebuilder.PartGeometry geometry = PartMeshRebuilder.PartGeometry.of(
                rm.vertices(), rm.texCoords(), rm.indices(), localTriToFace);
        return new FlattenResult(geometry, faceIdToLocal);
    }
}
