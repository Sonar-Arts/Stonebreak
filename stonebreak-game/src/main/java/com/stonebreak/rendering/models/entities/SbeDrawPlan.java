package com.stonebreak.rendering.models.entities;

import com.openmason.engine.rendering.model.IndexedDrawBatch;
import com.stonebreak.mobs.sbe.SbeFace;
import com.stonebreak.mobs.sbe.SbeModelGeometry;
import com.stonebreak.mobs.sbe.SbePart;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** GL-free draw plan, compiled once per uploaded geometry and reused every frame. */
final class SbeDrawPlan {
    record PartBatches(List<IndexedDrawBatch> textured, List<IndexedDrawBatch> untextured) {}

    private final Map<SbePart, PartBatches> parts = new IdentityHashMap<>();
    private final Set<Integer> materialIds;

    SbeDrawPlan(SbeModelGeometry geometry) {
        Set<Integer> usedMaterials = new HashSet<>();
        for (SbePart part : geometry.parts()) {
            var textured = new IndexedDrawBatch.Builder();
            var untextured = new IndexedDrawBatch.Builder();
            for (SbeFace face : part.faces()) {
                if ((long) face.indexStart() + face.indexCount() > geometry.indices().length) {
                    throw new IllegalArgumentException("Face index range exceeds mesh: " + face.faceId());
                }
                untextured.add(0, face.indexStart(), face.indexCount());
                // Match the existing textured path: an absent texture skips
                // that face, but wireframe/solid-colour still draw its geometry.
                if (face.indexCount() > 0 && geometry.materials().containsKey(face.materialId())) {
                    textured.add(face.materialId(), face.indexStart(), face.indexCount());
                    usedMaterials.add(face.materialId());
                }
            }
            parts.put(part, new PartBatches(textured.build(), untextured.build()));
        }
        materialIds = Set.copyOf(usedMaterials);
    }

    PartBatches forPart(SbePart part) {
        return parts.get(part);
    }

    Set<Integer> materialIds() {
        return materialIds;
    }
}
