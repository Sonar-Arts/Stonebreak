package com.openmason.main.systems.rendering.model.io.omo;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.rendering.model.gmr.parts.MeshRange;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.parts.PartTransform;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping;
import com.openmason.engine.rendering.model.gmr.uv.MaterialDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Assembles the OMO export payloads (part entries, face-texture metadata) from
 * live model state.
 *
 * <p>GL-free by design: extracted from {@code ModelOperationService}'s save
 * flow so the headless script runner and the live tool share one exporter.
 * Material texture <em>pixels</em> are not handled here — reading them back is
 * a GPU operation and stays with the live save path.
 */
public final class OmoExportAssembler {

    private OmoExportAssembler() {
    }

    /**
     * Extract part entries for serialization (transform + mesh range + flags
     * per part). Returns null when a lone identity-transform part makes
     * entries unnecessary (the partless-synthesis load path covers it).
     *
     * <p>Vertex/index spans are recomputed FROM {@code meshData} — the exact
     * arrays being written — via each part's stable face-id range in
     * {@code triangleToFaceId}. The part manager's {@code MeshRange} indexes
     * the combined soup, but the live save exports the DERIVED render mesh,
     * whose layout can legitimately differ after topology edits (welded
     * degenerate faces are skipped, coincident in-face vertices shrink
     * loops); ranges computed against the saved arrays align by construction,
     * so the load-time slicing can never run out of bounds.
     */
    public static List<OMOFormat.PartEntry> extractPartEntries(ModelPartManager partManager,
                                                               OMOFormat.MeshData meshData) {
        if (partManager == null || meshData == null) {
            return null;
        }
        if (partManager.getPartCount() <= 1 && !hasNonDefaultTransform(partManager.getAllParts())) {
            return null; // Single default-transform part: partless synthesis on load covers it
        }

        List<ModelPartDescriptor> parts = partManager.getAllParts();
        int[][] spans = computeSpans(parts, meshData);

        List<OMOFormat.PartEntry> entries = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            ModelPartDescriptor part = parts.get(i);
            PartTransform t = part.transform();
            MeshRange range = part.meshRange();
            int[] span = spans[i]; // {vertexStart, vertexCount, indexStart, indexCount}

            entries.add(new OMOFormat.PartEntry(
                    part.id(), part.name(),
                    t.origin().x, t.origin().y, t.origin().z,
                    t.position().x, t.position().y, t.position().z,
                    t.rotation().x, t.rotation().y, t.rotation().z,
                    t.scale().x, t.scale().y, t.scale().z,
                    span[0], span[1], span[2], span[3],
                    range != null ? range.faceStart() : 0,
                    range != null ? range.faceCount() : 0,
                    part.visible(), part.locked(),
                    part.parentId()
            ));
        }

        return entries;
    }

    /**
     * Per-part {vertexStart, vertexCount, indexStart, indexCount} in the
     * saved mesh's own layout. Triangles carry global face ids and each part
     * owns the contiguous id range {@code [faceStart, faceStart+faceCount)};
     * a part with no triangles in the mesh (hidden) gets a zero span.
     */
    private static int[][] computeSpans(List<ModelPartDescriptor> parts, OMOFormat.MeshData meshData) {
        int[][] spans = new int[parts.size()][4];
        int[] triToFace = meshData.triangleToFaceId();
        int[] indices = meshData.indices();
        if (triToFace == null || indices == null) {
            // No face mapping — fall back to the part manager's soup ranges.
            for (int i = 0; i < parts.size(); i++) {
                MeshRange r = parts.get(i).meshRange();
                spans[i] = r == null ? new int[4] : new int[]{
                        r.vertexStart(), r.vertexCount(), r.indexStart(), r.indexCount()};
            }
            return spans;
        }

        int triangleCount = Math.min(triToFace.length, indices.length / 3);
        for (int i = 0; i < parts.size(); i++) {
            MeshRange r = parts.get(i).meshRange();
            if (r == null || r.faceCount() == 0) {
                continue; // zero span
            }
            int faceStart = r.faceStart();
            int faceEnd = faceStart + r.faceCount();
            int triFirst = -1, triLast = -1;
            int minVertex = Integer.MAX_VALUE, maxVertex = -1;
            for (int tri = 0; tri < triangleCount; tri++) {
                int faceId = triToFace[tri];
                if (faceId < faceStart || faceId >= faceEnd) continue;
                if (triFirst < 0) triFirst = tri;
                triLast = tri;
                for (int c = 0; c < 3; c++) {
                    int vertex = indices[tri * 3 + c];
                    if (vertex < minVertex) minVertex = vertex;
                    if (vertex > maxVertex) maxVertex = vertex;
                }
            }
            if (triFirst < 0) {
                continue; // hidden part: reserves its face ids, contributes no geometry
            }
            spans[i][0] = minVertex;
            spans[i][1] = maxVertex - minVertex + 1;
            spans[i][2] = triFirst * 3;
            spans[i][3] = (triLast - triFirst + 1) * 3;
        }
        return spans;
    }

    /**
     * True if any part has a non-identity origin/position/rotation/scale or a
     * parent. A lone part with an identity transform is covered by the
     * partless-synthesis load path, so its entry can be safely omitted.
     */
    public static boolean hasNonDefaultTransform(Iterable<ModelPartDescriptor> parts) {
        for (ModelPartDescriptor part : parts) {
            PartTransform t = part.transform();
            if (part.parentId() != null) return true;
            if (!t.origin().equals(0f, 0f, 0f)) return true;
            if (!t.position().equals(0f, 0f, 0f)) return true;
            if (!t.rotation().equals(0f, 0f, 0f)) return true;
            if (!t.scale().equals(1f, 1f, 1f)) return true;
        }
        return false;
    }

    /**
     * Extract face texture metadata (mappings + materials) for serialization.
     * Material texture file names follow the {@code material_<id>.png}
     * convention the serializer and loader share.
     *
     * @return face texture data, or null if no non-default mappings exist
     */
    public static OMOFormat.FaceTextureData extractFaceTextureData(FaceTextureManager ftm) {
        Collection<FaceTextureMapping> allMappings = ftm.getAllMappings();
        Collection<MaterialDefinition> allMaterials = ftm.getAllMaterials();

        // Build mapping entries (skip default-material full-region mappings that are implicit)
        List<OMOFormat.FaceMappingEntry> mappingEntries = new ArrayList<>();
        for (FaceTextureMapping mapping : allMappings) {
            if (mapping.materialId() == MaterialDefinition.DEFAULT.materialId()
                    && mapping.uvRegion().equals(FaceTextureMapping.FULL_REGION)) {
                continue; // Skip implicit default mappings
            }
            mappingEntries.add(new OMOFormat.FaceMappingEntry(
                    mapping.faceId(),
                    mapping.materialId(),
                    mapping.uvRegion().u0(), mapping.uvRegion().v0(),
                    mapping.uvRegion().u1(), mapping.uvRegion().v1(),
                    mapping.uvRotation().degrees(),
                    mapping.autoResize()
            ));
        }

        if (mappingEntries.isEmpty()) {
            return null; // No non-default mappings to save
        }

        // Build material entries (exclude default material)
        List<OMOFormat.MaterialEntry> materialEntries = new ArrayList<>();
        for (MaterialDefinition mat : allMaterials) {
            if (mat.materialId() == MaterialDefinition.DEFAULT.materialId()) {
                continue;
            }
            String textureFile = "material_" + mat.materialId() + ".png";
            materialEntries.add(new OMOFormat.MaterialEntry(
                    mat.materialId(),
                    mat.name(),
                    textureFile,
                    mat.renderLayer().name(),
                    mat.properties().emissive(),
                    mat.properties().tintColor()
            ));
        }

        return new OMOFormat.FaceTextureData(mappingEntries, materialEntries);
    }
}
