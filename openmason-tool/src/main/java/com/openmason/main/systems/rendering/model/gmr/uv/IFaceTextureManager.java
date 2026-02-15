package com.openmason.main.systems.rendering.model.gmr.uv;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Contract for the central coordinator of per-face UV data.
 *
 * <p>Manages the association between face IDs and their texture mappings,
 * and between material IDs and their definitions. Provides operations
 * for UV inheritance during face splits, default mapping assignment,
 * and material-based face grouping for the texture editor.
 *
 * @see FaceTextureMapping
 * @see MaterialDefinition
 */
public interface IFaceTextureManager {

    // ── Face mapping operations ──────────────────────────────────────────

    /**
     * Store a texture mapping for a face, replacing any existing mapping.
     *
     * @param mapping The face texture mapping to store
     */
    void setFaceMapping(FaceTextureMapping mapping);

    /**
     * Retrieve the texture mapping for a face.
     *
     * @param faceId Face identifier
     * @return The mapping, or {@code null} if no mapping exists for this face
     */
    FaceTextureMapping getFaceMapping(int faceId);

    /**
     * Check whether a face has a texture mapping.
     *
     * @param faceId Face identifier
     * @return {@code true} if a mapping exists
     */
    boolean hasFaceMapping(int faceId);

    /**
     * Remove the texture mapping for a face.
     *
     * <p>Called when a face is deleted from the mesh.
     *
     * @param faceId Face identifier
     */
    void removeFaceMapping(int faceId);

    // ── Material operations ──────────────────────────────────────────────

    /**
     * Register a material definition, replacing any existing definition with the same ID.
     *
     * @param material The material definition to register
     */
    void registerMaterial(MaterialDefinition material);

    /**
     * Retrieve a material definition by ID.
     *
     * @param materialId Material identifier
     * @return The definition, or {@code null} if no material exists with this ID
     */
    MaterialDefinition getMaterial(int materialId);

    /**
     * Check whether a material is registered.
     *
     * @param materialId Material identifier
     * @return {@code true} if a material definition exists
     */
    boolean hasMaterial(int materialId);

    /**
     * Remove a material definition.
     *
     * <p>Does not affect face mappings that reference this material —
     * those must be reassigned separately.
     *
     * @param materialId Material identifier
     */
    void removeMaterial(int materialId);

    // ── Face operation integration ───────────────────────────────────────

    /**
     * Assign a default mapping to a newly created face.
     *
     * <p>The mapping covers the full {@code (0,0)→(1,1)} UV region
     * of the specified material with no rotation.
     *
     * @param faceId     Face identifier for the new face
     * @param materialId Material to assign (must be registered)
     */
    void assignDefaultMapping(int faceId, int materialId);

    /**
     * Propagate UV data when a parent face is split into two children.
     *
     * <p>The parent's UV region is divided at parametric position {@code t}
     * along the split axis. Child A inherits the region from 0 to {@code t},
     * child B inherits from {@code t} to 1. The parent's mapping is removed.
     *
     * <p>If the parent has no mapping, both children receive default mappings.
     *
     * @param parentFaceId  Face ID of the parent being split (will be removed)
     * @param childFaceIdA  Face ID of the first child
     * @param childFaceIdB  Face ID of the second child
     * @param t             Parametric split position (0..1) along the split axis
     * @param horizontal    {@code true} if split along U axis, {@code false} for V axis
     */
    void propagateSplitUV(int parentFaceId, int childFaceIdA, int childFaceIdB,
                          float t, boolean horizontal);

    // ── Queries ──────────────────────────────────────────────────────────

    /**
     * Group face IDs by their assigned material.
     *
     * <p>Returns an unmodifiable map from material ID to the list of face IDs
     * assigned to that material. Useful for the texture editor to display
     * faces organized by material zone.
     *
     * @return Map of materialId → list of faceIds (unmodifiable)
     */
    Map<Integer, List<Integer>> getFaceIdsByMaterial();

    /**
     * @return All stored face texture mappings (unmodifiable)
     */
    Collection<FaceTextureMapping> getAllMappings();

    /**
     * @return All registered material definitions (unmodifiable)
     */
    Collection<MaterialDefinition> getAllMaterials();

    /**
     * @return Number of face mappings currently stored
     */
    int getFaceMappingCount();

    /**
     * @return Number of materials currently registered
     */
    int getMaterialCount();

    // ── Lifecycle ────────────────────────────────────────────────────────

    /**
     * Remove all face mappings and non-default materials.
     *
     * <p>The default material ({@link MaterialDefinition#DEFAULT}) is re-registered
     * after clearing.
     */
    void clear();
}
