package com.openmason.main.systems.rendering.model.gmr.parts;

import com.openmason.main.systems.rendering.model.ModelPart;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default implementation of {@link IModelPartManager}.
 *
 * <p>Manages an ordered collection of {@link ModelPartDescriptor} entries,
 * each backed by raw geometry data. When parts are added, removed, transformed,
 * or merged, the manager rebuilds the combined mesh via {@link PartMeshRebuilder}
 * and provides the result to a {@link MeshConsumer} callback for GPU upload.
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li><b>Single Responsibility</b>: Manages part descriptors and coordinates rebuilds.
 *       Does not own GPU state or rendering logic.</li>
 *   <li><b>Open/Closed</b>: New part types can be added via the same interface
 *       without modifying manager internals.</li>
 *   <li><b>Dependency Inversion</b>: Depends on abstractions ({@link MeshConsumer})
 *       rather than concrete renderer classes.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Not thread-safe. All calls must be made from the main/render thread.
 * Listeners are stored in a CopyOnWriteArrayList for safe iteration during notification.
 */
public class ModelPartManager implements IModelPartManager {

    private static final Logger logger = LoggerFactory.getLogger(ModelPartManager.class);

    // Part storage (insertion-ordered)
    private final LinkedHashMap<String, ModelPartDescriptor> parts = new LinkedHashMap<>();

    // Raw geometry backing each part (partId → geometry)
    private final Map<String, PartMeshRebuilder.PartGeometry> partGeometry = new HashMap<>();

    // Selection state
    private final LinkedHashSet<String> selectedPartIds = new LinkedHashSet<>();

    // Services
    private final PartMeshRebuilder rebuilder = new PartMeshRebuilder();

    // Listeners
    private final List<IPartChangeListener> listeners = new CopyOnWriteArrayList<>();

    // Mesh consumer callback (set by GenericModelRenderer or similar)
    private MeshConsumer meshConsumer;

    // Counter for generating unique part names
    private int partNameCounter = 0;

    /**
     * Functional interface for receiving rebuilt mesh data.
     * Decouples the manager from specific renderer implementations.
     */
    @FunctionalInterface
    public interface MeshConsumer {
        /**
         * Called after a rebuild with the combined mesh data.
         *
         * @param result The rebuild result containing combined buffers and ranges
         */
        void accept(PartMeshRebuilder.RebuildResult result);
    }

    /**
     * Set the callback that receives rebuilt mesh data.
     * Must be set before any part operations to enable GPU uploads.
     *
     * @param consumer Mesh consumer callback
     */
    public void setMeshConsumer(MeshConsumer consumer) {
        this.meshConsumer = consumer;
    }

    // ========== Query ==========

    @Override
    public List<ModelPartDescriptor> getAllParts() {
        return List.copyOf(parts.values());
    }

    @Override
    public Optional<ModelPartDescriptor> getPartById(String id) {
        return Optional.ofNullable(parts.get(id));
    }

    @Override
    public Optional<ModelPartDescriptor> getPartByName(String name) {
        return parts.values().stream()
                .filter(p -> p.name().equals(name))
                .findFirst();
    }

    @Override
    public Optional<ModelPartDescriptor> getPartForVertex(int globalVertexIndex) {
        for (ModelPartDescriptor part : parts.values()) {
            if (part.meshRange() != null && part.meshRange().containsVertex(globalVertexIndex)) {
                return Optional.of(part);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<ModelPartDescriptor> getPartForFace(int faceId) {
        for (ModelPartDescriptor part : parts.values()) {
            if (part.meshRange() != null && part.meshRange().containsFace(faceId)) {
                return Optional.of(part);
            }
        }
        return Optional.empty();
    }

    @Override
    public int getPartCount() {
        return parts.size();
    }

    // ========== CRUD ==========

    @Override
    public ModelPartDescriptor addPart(String name, ModelPart geometry) {
        // Build face mapping from topology hint (for ModelPart-based geometry only)
        int[] triangleToFaceId = buildTriangleToFaceMapping(geometry);
        PartMeshRebuilder.PartGeometry geo = PartMeshRebuilder.PartGeometry.of(
                geometry.vertices(), geometry.texCoords(), geometry.indices(), triangleToFaceId
        );

        Vector3f origin = geometry.origin() != null ? geometry.origin() : new Vector3f(0, 0, 0);
        return addPartFromGeometry(name, geo, origin);
    }

    /**
     * Add a new part from pre-built geometry data.
     * Preserves the exact triangle-to-face mapping — does NOT regenerate it.
     * Use this when loading from .OMO files or any source with an explicit face mapping.
     *
     * @param name     Display name for the part
     * @param geometry Pre-built geometry with original face mapping intact
     * @param origin   Part origin/pivot point
     * @return The newly created part descriptor
     */
    public ModelPartDescriptor addPartFromGeometry(String name, PartMeshRebuilder.PartGeometry geometry,
                                                    Vector3f origin) {
        String id = UUID.randomUUID().toString();
        String partName = (name != null && !name.isBlank()) ? name : generatePartName();

        Vector3f partOrigin = origin != null ? origin : new Vector3f(0, 0, 0);
        PartTransform transform = PartTransform.identity(partOrigin);

        ModelPartDescriptor descriptor = new ModelPartDescriptor(id, partName, transform, null, true, false);

        parts.put(id, descriptor);
        partGeometry.put(id, geometry);

        // Rebuild and notify
        rebuildCombinedMesh();
        notifyPartAdded(parts.get(id)); // Re-fetch to get updated meshRange

        logger.info("Added part '{}' (id={}, {} vertices, {} faces)",
                partName, id, geometry.vertexCount(), geometry.faceCount());

        return parts.get(id);
    }

    @Override
    public Optional<ModelPartDescriptor> duplicatePart(String sourceId) {
        ModelPartDescriptor source = parts.get(sourceId);
        if (source == null) {
            logger.warn("Cannot duplicate: part not found (id={})", sourceId);
            return Optional.empty();
        }

        PartMeshRebuilder.PartGeometry sourceGeo = partGeometry.get(sourceId);
        if (sourceGeo == null) {
            logger.warn("Cannot duplicate: no geometry for part (id={})", sourceId);
            return Optional.empty();
        }

        String newId = UUID.randomUUID().toString();
        String newName = source.name() + " Copy";

        // Deep copy geometry arrays
        float[] verticesCopy = sourceGeo.vertices().clone();
        float[] texCoordsCopy = sourceGeo.texCoords() != null ? sourceGeo.texCoords().clone() : null;
        int[] indicesCopy = sourceGeo.indices() != null ? sourceGeo.indices().clone() : null;
        int[] faceMappingCopy = sourceGeo.triangleToFaceId() != null ? sourceGeo.triangleToFaceId().clone() : null;

        PartMeshRebuilder.PartGeometry newGeo = new PartMeshRebuilder.PartGeometry(
                verticesCopy, texCoordsCopy, indicesCopy, faceMappingCopy,
                sourceGeo.vertexCount(), sourceGeo.indexCount(), sourceGeo.faceCount()
        );

        // Copy transform with a slight offset so the duplicate is visible
        PartTransform newTransform = source.transform().withTranslation(new Vector3f(0.1f, 0, 0));

        ModelPartDescriptor duplicate = new ModelPartDescriptor(
                newId, newName, newTransform, null, source.visible(), false
        );

        parts.put(newId, duplicate);
        partGeometry.put(newId, newGeo);

        rebuildCombinedMesh();
        notifyPartAdded(parts.get(newId));

        logger.info("Duplicated part '{}' -> '{}' (id={})", source.name(), newName, newId);
        return Optional.of(parts.get(newId));
    }

    @Override
    public boolean removePart(String id) {
        ModelPartDescriptor removed = parts.remove(id);
        if (removed == null) {
            logger.warn("Cannot remove: part not found (id={})", id);
            return false;
        }

        partGeometry.remove(id);
        selectedPartIds.remove(id);

        rebuildCombinedMesh();
        notifyPartRemoved(id);

        logger.info("Removed part '{}' (id={})", removed.name(), id);
        return true;
    }

    @Override
    public Optional<ModelPartDescriptor> mergeParts(List<String> partIds, String mergedName) {
        if (partIds == null || partIds.size() < 2) {
            logger.warn("Merge requires at least 2 parts");
            return Optional.empty();
        }

        // Validate all parts exist
        List<ModelPartDescriptor> toMerge = new ArrayList<>();
        List<PartMeshRebuilder.PartGeometry> geometries = new ArrayList<>();
        for (String id : partIds) {
            ModelPartDescriptor part = parts.get(id);
            PartMeshRebuilder.PartGeometry geo = partGeometry.get(id);
            if (part == null || geo == null) {
                logger.warn("Merge aborted: part not found (id={})", id);
                return Optional.empty();
            }
            toMerge.add(part);
            geometries.add(geo);
        }

        // Combine geometry (apply transforms, then merge into flat arrays)
        PartMeshRebuilder.RebuildResult mergedResult = rebuilder.rebuild(toMerge,
                buildGeometryMap(toMerge, geometries));

        // Create merged geometry
        PartMeshRebuilder.PartGeometry mergedGeo = PartMeshRebuilder.PartGeometry.of(
                mergedResult.combinedVertices(),
                mergedResult.combinedTexCoords(),
                mergedResult.combinedIndices(),
                mergedResult.triangleToFaceId()
        );

        // Remove source parts
        for (String id : partIds) {
            parts.remove(id);
            partGeometry.remove(id);
            selectedPartIds.remove(id);
        }

        // Add merged part
        String mergedId = UUID.randomUUID().toString();
        String name = (mergedName != null && !mergedName.isBlank()) ? mergedName : "Merged";

        ModelPartDescriptor mergedDescriptor = new ModelPartDescriptor(
                mergedId, name, PartTransform.identity(), null, true, false
        );

        parts.put(mergedId, mergedDescriptor);
        partGeometry.put(mergedId, mergedGeo);

        rebuildCombinedMesh();
        notifyPartsMerged(partIds, parts.get(mergedId));

        logger.info("Merged {} parts into '{}' (id={})", partIds.size(), name, mergedId);
        return Optional.of(parts.get(mergedId));
    }

    @Override
    public Optional<ModelPartDescriptor> renamePart(String id, String newName) {
        ModelPartDescriptor existing = parts.get(id);
        if (existing == null) {
            return Optional.empty();
        }

        ModelPartDescriptor renamed = existing.withName(newName);
        parts.put(id, renamed);

        logger.debug("Renamed part '{}' -> '{}' (id={})", existing.name(), newName, id);
        return Optional.of(renamed);
    }

    // ========== Selection ==========

    @Override
    public Set<String> getSelectedPartIds() {
        return Collections.unmodifiableSet(selectedPartIds);
    }

    @Override
    public void selectPart(String id) {
        if (parts.containsKey(id) && selectedPartIds.add(id)) {
            notifySelectionChanged();
        }
    }

    @Override
    public void deselectPart(String id) {
        if (selectedPartIds.remove(id)) {
            notifySelectionChanged();
        }
    }

    @Override
    public void togglePartSelection(String id) {
        if (parts.containsKey(id)) {
            if (!selectedPartIds.remove(id)) {
                selectedPartIds.add(id);
            }
            notifySelectionChanged();
        }
    }

    @Override
    public void selectAllParts() {
        selectedPartIds.addAll(parts.keySet());
        notifySelectionChanged();
    }

    @Override
    public void deselectAllParts() {
        if (!selectedPartIds.isEmpty()) {
            selectedPartIds.clear();
            notifySelectionChanged();
        }
    }

    @Override
    public boolean isPartSelected(String id) {
        return selectedPartIds.contains(id);
    }

    // ========== Transforms ==========

    @Override
    public void setPartTransform(String id, PartTransform transform) {
        ModelPartDescriptor existing = parts.get(id);
        if (existing == null || existing.locked()) {
            return;
        }

        // Skip rebuild if transform hasn't actually changed
        if (existing.transform().equals(transform)) {
            return;
        }

        parts.put(id, existing.withTransform(transform));
        rebuildCombinedMesh();
        notifyTransformChanged(id, transform);
    }

    @Override
    public void translatePart(String id, Vector3f delta) {
        ModelPartDescriptor existing = parts.get(id);
        if (existing == null || existing.locked()) {
            return;
        }

        PartTransform newTransform = existing.transform().withTranslation(delta);
        parts.put(id, existing.withTransform(newTransform));
        rebuildCombinedMesh();
        notifyTransformChanged(id, newTransform);
    }

    @Override
    public void rotatePart(String id, Vector3f eulerDelta) {
        ModelPartDescriptor existing = parts.get(id);
        if (existing == null || existing.locked()) {
            return;
        }

        PartTransform newTransform = existing.transform().withRotation(eulerDelta);
        parts.put(id, existing.withTransform(newTransform));
        rebuildCombinedMesh();
        notifyTransformChanged(id, newTransform);
    }

    @Override
    public void scalePart(String id, Vector3f scaleFactors) {
        ModelPartDescriptor existing = parts.get(id);
        if (existing == null || existing.locked()) {
            return;
        }

        PartTransform newTransform = existing.transform().withScale(scaleFactors);
        parts.put(id, existing.withTransform(newTransform));
        rebuildCombinedMesh();
        notifyTransformChanged(id, newTransform);
    }

    // ========== Visibility / Locking ==========

    @Override
    public void setPartVisible(String id, boolean visible) {
        ModelPartDescriptor existing = parts.get(id);
        if (existing != null && existing.visible() != visible) {
            parts.put(id, existing.withVisible(visible));
            rebuildCombinedMesh();
        }
    }

    @Override
    public void setPartLocked(String id, boolean locked) {
        ModelPartDescriptor existing = parts.get(id);
        if (existing != null && existing.locked() != locked) {
            parts.put(id, existing.withLocked(locked));
        }
    }

    // ========== Listeners ==========

    @Override
    public void addPartChangeListener(IPartChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removePartChangeListener(IPartChangeListener listener) {
        listeners.remove(listener);
    }

    // ========== Bulk Operations ==========

    @Override
    public void clear() {
        parts.clear();
        partGeometry.clear();
        selectedPartIds.clear();
        partNameCounter = 0;

        if (meshConsumer != null) {
            meshConsumer.accept(PartMeshRebuilder.RebuildResult.empty());
        }

        notifyPartsRebuilt();
        logger.info("Cleared all parts");
    }

    @Override
    public ModelPartDescriptor importAsSinglePart(ModelPart geometry) {
        clear();
        return addPart("Root", geometry);
    }

    /**
     * Import pre-built geometry as a single root part.
     * Preserves the exact triangle-to-face mapping from the source data.
     * Use this when loading from .OMO files to avoid regenerating the face mapping.
     *
     * @param name     Display name for the root part
     * @param geometry Pre-built geometry with original face mapping
     * @return The root part descriptor
     */
    public ModelPartDescriptor importAsSinglePartFromGeometry(String name, PartMeshRebuilder.PartGeometry geometry) {
        clear();
        return addPartFromGeometry(name, geometry, new Vector3f(0, 0, 0));
    }

    // ========== Internal ==========

    /**
     * Rebuild the combined mesh from all visible parts and push to consumer.
     */
    private void rebuildCombinedMesh() {
        // Filter to visible parts only
        List<ModelPartDescriptor> visibleParts = parts.values().stream()
                .filter(ModelPartDescriptor::visible)
                .toList();

        // Build geometry map for visible parts
        Map<String, PartMeshRebuilder.PartGeometry> geoMap = new HashMap<>();
        for (ModelPartDescriptor part : visibleParts) {
            PartMeshRebuilder.PartGeometry geo = partGeometry.get(part.id());
            if (geo != null) {
                geoMap.put(part.id(), geo);
            }
        }

        // Rebuild
        PartMeshRebuilder.RebuildResult result = rebuilder.rebuild(visibleParts, geoMap);

        // Update mesh ranges on descriptors
        for (var entry : result.partRanges().entrySet()) {
            ModelPartDescriptor existing = parts.get(entry.getKey());
            if (existing != null) {
                parts.put(entry.getKey(), existing.withMeshRange(entry.getValue()));
            }
        }

        // Clear ranges for hidden parts
        for (ModelPartDescriptor part : parts.values()) {
            if (!part.visible() && part.meshRange() != null) {
                parts.put(part.id(), part.withMeshRange(null));
            }
        }

        // Push to consumer
        if (meshConsumer != null) {
            meshConsumer.accept(result);
        }
    }

    /**
     * Build triangle-to-face mapping from a ModelPart's topology hint.
     */
    private int[] buildTriangleToFaceMapping(ModelPart geometry) {
        if (geometry.indices() == null) {
            return null;
        }

        int triangleCount = geometry.indices().length / 3;
        int[] mapping = new int[triangleCount];

        Integer trianglesPerFace = geometry.trianglesPerFace();
        if (trianglesPerFace != null && trianglesPerFace > 0) {
            for (int i = 0; i < triangleCount; i++) {
                mapping[i] = i / trianglesPerFace;
            }
        } else {
            // 1:1 mapping (each triangle is its own face)
            for (int i = 0; i < triangleCount; i++) {
                mapping[i] = i;
            }
        }

        return mapping;
    }

    /**
     * Build a geometry map from parallel lists (for merge operations).
     */
    private Map<String, PartMeshRebuilder.PartGeometry> buildGeometryMap(
            List<ModelPartDescriptor> descriptors, List<PartMeshRebuilder.PartGeometry> geometries) {
        Map<String, PartMeshRebuilder.PartGeometry> map = new HashMap<>();
        for (int i = 0; i < descriptors.size(); i++) {
            map.put(descriptors.get(i).id(), geometries.get(i));
        }
        return map;
    }

    /**
     * Generate a unique part name.
     */
    private String generatePartName() {
        return "Part " + (++partNameCounter);
    }

    // ========== Notification Helpers ==========

    private void notifyPartAdded(ModelPartDescriptor part) {
        for (IPartChangeListener l : listeners) {
            l.onPartAdded(part);
        }
    }

    private void notifyPartRemoved(String partId) {
        for (IPartChangeListener l : listeners) {
            l.onPartRemoved(partId);
        }
    }

    private void notifyTransformChanged(String partId, PartTransform transform) {
        for (IPartChangeListener l : listeners) {
            l.onPartTransformChanged(partId, transform);
        }
    }

    private void notifySelectionChanged() {
        Set<String> snapshot = Collections.unmodifiableSet(new LinkedHashSet<>(selectedPartIds));
        for (IPartChangeListener l : listeners) {
            l.onPartSelectionChanged(snapshot);
        }
    }

    private void notifyPartsMerged(List<String> sourceIds, ModelPartDescriptor merged) {
        for (IPartChangeListener l : listeners) {
            l.onPartsMerged(sourceIds, merged);
        }
    }

    private void notifyPartsRebuilt() {
        for (IPartChangeListener l : listeners) {
            l.onPartsRebuilt();
        }
    }

    // ========== Accessors for Serialization ==========

    /**
     * Get raw geometry for a part (used by serialization).
     *
     * @param partId Part ID
     * @return Raw geometry, or null if not found
     */
    public PartMeshRebuilder.PartGeometry getPartGeometry(String partId) {
        return partGeometry.get(partId);
    }

    /**
     * Get all part geometries (used by serialization).
     *
     * @return Unmodifiable map of partId → geometry
     */
    public Map<String, PartMeshRebuilder.PartGeometry> getAllPartGeometry() {
        return Collections.unmodifiableMap(partGeometry);
    }
}
