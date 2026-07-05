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
     */
    public static List<OMOFormat.PartEntry> extractPartEntries(ModelPartManager partManager) {
        if (partManager == null) {
            return null;
        }
        if (partManager.getPartCount() <= 1 && !hasNonDefaultTransform(partManager.getAllParts())) {
            return null; // Single default-transform part: partless synthesis on load covers it
        }

        List<OMOFormat.PartEntry> entries = new ArrayList<>();
        for (ModelPartDescriptor part : partManager.getAllParts()) {
            PartTransform t = part.transform();
            MeshRange range = part.meshRange();

            entries.add(new OMOFormat.PartEntry(
                    part.id(), part.name(),
                    t.origin().x, t.origin().y, t.origin().z,
                    t.position().x, t.position().y, t.position().z,
                    t.rotation().x, t.rotation().y, t.rotation().z,
                    t.scale().x, t.scale().y, t.scale().z,
                    range != null ? range.vertexStart() : 0,
                    range != null ? range.vertexCount() : 0,
                    range != null ? range.indexStart() : 0,
                    range != null ? range.indexCount() : 0,
                    range != null ? range.faceStart() : 0,
                    range != null ? range.faceCount() : 0,
                    part.visible(), part.locked(),
                    part.parentId()
            ));
        }

        return entries;
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
