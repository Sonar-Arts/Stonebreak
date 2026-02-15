package com.openmason.main.systems.rendering.model.gmr.uv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Central coordinator for per-face UV data.
 *
 * <p>Manages two maps:
 * <ul>
 *   <li>{@code faceId → FaceTextureMapping} — one entry per mesh face</li>
 *   <li>{@code materialId → MaterialDefinition} — registered materials</li>
 * </ul>
 *
 * <p>Called by processors after topology changes to keep UV data consistent:
 * <ul>
 *   <li><b>Edge insertion (face split)</b> → {@link #propagateSplitUV} — children inherit proportional UV sub-regions</li>
 *   <li><b>Face deletion</b> → {@link #removeFaceMapping} — UV mapping removed</li>
 *   <li><b>Face creation</b> → {@link #assignDefaultMapping} — full region of assigned material</li>
 *   <li><b>Knife tool</b> → combination of subdivision + split propagation</li>
 * </ul>
 *
 * <p>The default material ({@link MaterialDefinition#DEFAULT}, ID 0) is always
 * registered and cannot be removed.
 */
public final class FaceTextureManager implements IFaceTextureManager {

    private static final Logger logger = LoggerFactory.getLogger(FaceTextureManager.class);

    private final Map<Integer, FaceTextureMapping> faceMappings = new HashMap<>();
    private final Map<Integer, MaterialDefinition> materials = new HashMap<>();

    /**
     * Creates a new manager with the default material pre-registered.
     */
    public FaceTextureManager() {
        materials.put(MaterialDefinition.DEFAULT.materialId(), MaterialDefinition.DEFAULT);
    }

    // ── Face mapping operations ──────────────────────────────────────────

    @Override
    public void setFaceMapping(FaceTextureMapping mapping) {
        if (mapping == null) {
            throw new IllegalArgumentException("mapping must not be null");
        }
        faceMappings.put(mapping.faceId(), mapping);
        logger.debug("Set face mapping: face {} → material {}, region {}",
            mapping.faceId(), mapping.materialId(), mapping.uvRegion());
    }

    @Override
    public FaceTextureMapping getFaceMapping(int faceId) {
        return faceMappings.get(faceId);
    }

    @Override
    public boolean hasFaceMapping(int faceId) {
        return faceMappings.containsKey(faceId);
    }

    @Override
    public void removeFaceMapping(int faceId) {
        FaceTextureMapping removed = faceMappings.remove(faceId);
        if (removed != null) {
            logger.debug("Removed face mapping: face {}", faceId);
        } else {
            logger.debug("No face mapping to remove for face {}", faceId);
        }
    }

    // ── Material operations ──────────────────────────────────────────────

    @Override
    public void registerMaterial(MaterialDefinition material) {
        if (material == null) {
            throw new IllegalArgumentException("material must not be null");
        }
        materials.put(material.materialId(), material);
        logger.debug("Registered material: {} (ID {})", material.name(), material.materialId());
    }

    @Override
    public MaterialDefinition getMaterial(int materialId) {
        return materials.get(materialId);
    }

    @Override
    public boolean hasMaterial(int materialId) {
        return materials.containsKey(materialId);
    }

    @Override
    public void removeMaterial(int materialId) {
        if (materialId == MaterialDefinition.DEFAULT.materialId()) {
            logger.warn("Cannot remove the default material (ID {})", materialId);
            return;
        }
        MaterialDefinition removed = materials.remove(materialId);
        if (removed != null) {
            logger.debug("Removed material: {} (ID {})", removed.name(), materialId);
        } else {
            logger.debug("No material to remove for ID {}", materialId);
        }
    }

    // ── Face operation integration ───────────────────────────────────────

    @Override
    public void assignDefaultMapping(int faceId, int materialId) {
        int resolvedMaterialId = materials.containsKey(materialId)
            ? materialId
            : MaterialDefinition.DEFAULT.materialId();

        if (resolvedMaterialId != materialId) {
            logger.warn("Material {} not registered — falling back to default material for face {}",
                materialId, faceId);
        }

        FaceTextureMapping mapping = FaceTextureMapping.defaultMapping(faceId, resolvedMaterialId);
        faceMappings.put(faceId, mapping);
        logger.debug("Assigned default mapping: face {} → material {}", faceId, resolvedMaterialId);
    }

    @Override
    public void propagateSplitUV(int parentFaceId, int childFaceIdA, int childFaceIdB,
                                 float t, boolean horizontal) {
        FaceTextureMapping parentMapping = faceMappings.get(parentFaceId);

        if (parentMapping == null) {
            logger.debug("No mapping for parent face {} — assigning default mappings to children {} and {}",
                parentFaceId, childFaceIdA, childFaceIdB);
            assignDefaultMapping(childFaceIdA, MaterialDefinition.DEFAULT.materialId());
            assignDefaultMapping(childFaceIdB, MaterialDefinition.DEFAULT.materialId());
            return;
        }

        float clampedT = Math.clamp(t, 0.0f, 1.0f);

        FaceTextureMapping childA = FaceTextureMapping.fromParentSplit(
            childFaceIdA, parentMapping, 0.0f, clampedT, horizontal);

        FaceTextureMapping childB = FaceTextureMapping.fromParentSplit(
            childFaceIdB, parentMapping, clampedT, 1.0f, horizontal);

        // Remove parent first to handle childFaceIdA == parentFaceId (edge insertion reuses parent ID)
        faceMappings.remove(parentFaceId);
        faceMappings.put(childFaceIdA, childA);
        faceMappings.put(childFaceIdB, childB);

        logger.debug("Split UV: parent face {} → child A {} [0..{}], child B {} [{}..1] ({})",
            parentFaceId, childFaceIdA, clampedT, childFaceIdB, clampedT,
            horizontal ? "horizontal" : "vertical");
    }

    // ── Queries ──────────────────────────────────────────────────────────

    @Override
    public Map<Integer, List<Integer>> getFaceIdsByMaterial() {
        Map<Integer, List<Integer>> grouped = new HashMap<>();

        for (FaceTextureMapping mapping : faceMappings.values()) {
            grouped.computeIfAbsent(mapping.materialId(), k -> new ArrayList<>())
                   .add(mapping.faceId());
        }

        // Wrap inner lists as unmodifiable
        Map<Integer, List<Integer>> result = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public Collection<FaceTextureMapping> getAllMappings() {
        return Collections.unmodifiableCollection(faceMappings.values());
    }

    @Override
    public Collection<MaterialDefinition> getAllMaterials() {
        return Collections.unmodifiableCollection(materials.values());
    }

    @Override
    public int getFaceMappingCount() {
        return faceMappings.size();
    }

    @Override
    public int getMaterialCount() {
        return materials.size();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Override
    public void clear() {
        faceMappings.clear();
        materials.clear();
        materials.put(MaterialDefinition.DEFAULT.materialId(), MaterialDefinition.DEFAULT);
        logger.debug("Cleared all face mappings and non-default materials");
    }
}
