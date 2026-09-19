package com.openmason.main.systems.rendering.model.io.omo;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.parts.PartMeshRebuilder;
import com.openmason.engine.rendering.model.gmr.parts.PartTransform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/** Restores saved part geometry and hierarchy without GL or intermediate mesh rebuilds. */
public final class OmoPartRestorer {
    private static final Logger logger = LoggerFactory.getLogger(OmoPartRestorer.class);

    private OmoPartRestorer() { }

    /** Saved-to-live face IDs also account for skipped corrupt parts and sparse ranges. */
    public record Result(Map<Integer, Integer> faceIds, List<String> skippedParts) { }

    public static Result restore(ModelPartManager partManager, OMOFormat.MeshData meshData,
                                 List<OMOFormat.PartEntry> entries,
                                 List<OMOFormat.BoneEntry> boneEntries) {
        float[] allVertices = meshData.vertices();
        float[] allTexCoords = meshData.texCoords();
        int[] allIndices = meshData.indices();
        int[] allTriToFace = meshData.triangleToFaceId();

        // Hierarchy support (v1.5+): the saved combined buffer has each part's
        // *effective* (parent-chain * local) transform baked into its vertices.
        // Build effective matrices from the saved entries and inverse-apply the effective
        // matrix to recover local-space vertices.
        Map<String, OMOFormat.PartEntry> entryById = new HashMap<>();
        for (OMOFormat.PartEntry e : entries) {
            entryById.put(e.id(), e);
        }
        Map<String, OMOFormat.BoneEntry> bonesById = new HashMap<>();
        if (boneEntries != null) {
            for (OMOFormat.BoneEntry b : boneEntries) {
                if (b != null) bonesById.put(b.id(), b);
            }
        }
        Map<String, org.joml.Matrix4f> effectiveBySavedId = new HashMap<>();
        for (OMOFormat.PartEntry e : entries) {
            effectiveBySavedId.put(e.id(), computeEffectiveMatrix(e, entryById, bonesById, new HashSet<>()));
        }
        List<ModelPartDescriptor> restored = new ArrayList<>();
        Map<String, PartMeshRebuilder.PartGeometry> geometry = new LinkedHashMap<>();

        List<String> skippedParts = new ArrayList<>();
        for (OMOFormat.PartEntry entry : entries) {
            // Validate the saved spans against the saved arrays BEFORE slicing.
            // Files written with stale ranges (pre-2026-07 save bug) would
            // otherwise register garbage parts that poison every later mesh
            // rebuild — skip the corrupt part and keep loading the rest.
            String rangeError = validatePartEntrySpans(entry, allVertices, allIndices);
            if (rangeError != null) {
                logger.error("Skipping part '{}' from .OMO: {}", entry.name(), rangeError);
                skippedParts.add(entry.name());
                continue;
            }

            // Slice vertices for this part
            int vStart = entry.vertexStart() * 3;
            int vLen = entry.vertexCount() * 3;
            float[] partVertices = new float[vLen];
            System.arraycopy(allVertices, vStart, partVertices, 0, vLen);

            // Slice tex coords
            float[] partTexCoords = null;
            if (allTexCoords != null) {
                int tStart = entry.vertexStart() * 2;
                int tLen = entry.vertexCount() * 2;
                partTexCoords = new float[tLen];
                System.arraycopy(allTexCoords, tStart, partTexCoords, 0, tLen);
            }

            // Slice indices and rebase to local vertex indices
            int iLen = entry.indexCount();
            int[] partIndices = new int[iLen];
            System.arraycopy(allIndices, entry.indexStart(), partIndices, 0, iLen);
            for (int i = 0; i < iLen; i++) {
                partIndices[i] -= entry.vertexStart(); // Rebase to part-local
            }

            // Slice triangle-to-face mapping and rebase face IDs
            int triCount = iLen / 3;
            int[] partTriToFace = null;
            if (allTriToFace != null) {
                int triStart = entry.indexStart() / 3;
                partTriToFace = new int[triCount];
                for (int i = 0; i < triCount; i++) {
                    partTriToFace[i] = allTriToFace[triStart + i] - entry.faceStart();
                }
            }

            // The combined mesh bakes the *effective* (parent-chain composed) transform
            // into each part's vertices. Inverse-apply that effective matrix to recover
            // local-space vertices so the manager's rebuild won't double-apply.
            PartTransform savedTransform = new PartTransform(
                    new org.joml.Vector3f(entry.originX(), entry.originY(), entry.originZ()),
                    new org.joml.Vector3f(entry.posX(), entry.posY(), entry.posZ()),
                    new org.joml.Vector3f(entry.rotX(), entry.rotY(), entry.rotZ()),
                    new org.joml.Vector3f(entry.scaleX(), entry.scaleY(), entry.scaleZ())
            );

            org.joml.Matrix4f effective = effectiveBySavedId.getOrDefault(entry.id(), savedTransform.toMatrix());
            if (!isIdentityMatrix(effective)) {
                org.joml.Matrix4f inverseTransform = new org.joml.Matrix4f(effective).invert();
                org.joml.Vector4f v = new org.joml.Vector4f();
                for (int i = 0; i < partVertices.length / 3; i++) {
                    int idx = i * 3;
                    v.set(partVertices[idx], partVertices[idx + 1], partVertices[idx + 2], 1.0f);
                    inverseTransform.transform(v);
                    partVertices[idx] = v.x;
                    partVertices[idx + 1] = v.y;
                    partVertices[idx + 2] = v.z;
                }
            }

            // Keep the saved reservation even when trailing faces have no triangles.
            // Otherwise every following part's face IDs (and textures) would shift.
            PartMeshRebuilder.PartGeometry geo = new PartMeshRebuilder.PartGeometry(
                    partVertices, partTexCoords, partIndices, partTriToFace,
                    entry.vertexCount(), entry.indexCount(), entry.faceCount());
            if (geometry.putIfAbsent(entry.id(), geo) != null) {
                throw new IllegalArgumentException("Duplicate saved part ID: " + entry.id());
            }
            restored.add(new ModelPartDescriptor(entry.id(), entry.name(), savedTransform,
                    null, entry.visible(), entry.locked(), entry.parentId(), entry.boneId()));
        }

        // Register the entire hierarchy before evaluating any transform. No parent-first
        // reorder: insertion order defines face numbers. One rebuild for the whole model.
        partManager.replaceAllParts(restored, geometry);
        Map<Integer, Integer> faceIds = new HashMap<>();
        for (OMOFormat.PartEntry entry : entries) {
            ModelPartDescriptor part = partManager.getPartById(entry.id()).orElse(null);
            if (part == null) continue;
            for (int local = 0; local < entry.faceCount(); local++) {
                faceIds.put(entry.faceStart() + local, part.meshRange().faceStart() + local);
            }
        }
        return new Result(Map.copyOf(faceIds), List.copyOf(skippedParts));
    }
    /**
     * Null when the entry's spans index cleanly into the saved arrays;
     * otherwise a description of the violation. Guards against files whose
     * ranges disagree with their mesh (they would slice garbage).
     */
    private static String validatePartEntrySpans(OMOFormat.PartEntry entry,
                                                 float[] allVertices, int[] allIndices) {
        int vertexTotal = allVertices.length / 3;
        long vertexEnd = (long) entry.vertexStart() + entry.vertexCount();
        if (entry.vertexStart() < 0 || entry.vertexCount() < 0 || vertexEnd > vertexTotal) {
            return "vertex span " + entry.vertexStart() + "+" + entry.vertexCount()
                    + " exceeds the saved mesh (" + vertexTotal + " vertices)";
        }
        long indexEnd = (long) entry.indexStart() + entry.indexCount();
        if (entry.indexStart() < 0 || entry.indexCount() < 0 || indexEnd > allIndices.length) {
            return "index span " + entry.indexStart() + "+" + entry.indexCount()
                    + " exceeds the saved mesh (" + allIndices.length + " indices)";
        }
        for (int i = entry.indexStart(); i < indexEnd; i++) {
            int vertex = allIndices[i];
            if (vertex < entry.vertexStart() || vertex >= vertexEnd) {
                return "index " + vertex + " at position " + i
                        + " escapes the part's vertex span " + entry.vertexStart()
                        + ".." + (vertexEnd - 1) + " (stale ranges from an older save)";
            }
        }
        return null;
    }

    /**
     * Compute the effective (parent-chain composed) world matrix for a saved part entry.
     * Walks {@code parentId} upward through {@code entryById} and {@code bonesById} —
     * the unified hierarchy allows parts to be parented to bones and vice-versa, so the
     * walk crosses between the two maps as needed. Cycle-defended via {@code visiting}.
     *
     * <p>The matrix this returns must match the runtime engine's view exactly so the
     * load-time inverse-bake recovers true local vertices. The runtime engine has
     * children inherit from the parent bone's <b>tail</b> frame (see
     * {@link com.openmason.main.systems.skeleton.BoneStore#getTailWorldTransform}),
     * so the bone walk here also returns the tail matrix.
     */
    private static org.joml.Matrix4f computeEffectiveMatrix(OMOFormat.PartEntry entry,
                                                     Map<String, OMOFormat.PartEntry> entryById,
                                                     Map<String, OMOFormat.BoneEntry> bonesById,
                                                     Set<String> visiting) {
        org.joml.Matrix4f local = new PartTransform(
                new org.joml.Vector3f(entry.originX(), entry.originY(), entry.originZ()),
                new org.joml.Vector3f(entry.posX(), entry.posY(), entry.posZ()),
                new org.joml.Vector3f(entry.rotX(), entry.rotY(), entry.rotZ()),
                new org.joml.Vector3f(entry.scaleX(), entry.scaleY(), entry.scaleZ())
        ).toMatrix();

        String parentId = entry.parentId();
        if (parentId == null) return local;
        if (!visiting.add(entry.id())) {
            logger.warn("Cycle in saved hierarchy at {} — treating as root", entry.id());
            return local;
        }
        org.joml.Matrix4f parentMatrix = parentFrameMatrix(parentId, entryById, bonesById, visiting);
        visiting.remove(entry.id());
        return new org.joml.Matrix4f(parentMatrix).mul(local);
    }

    /**
     * Compute the bone's <b>head</b> world matrix. The head is at
     * {@code parent_tail × T(origin) × T(pos) × R(rot)}. Used as the base for the
     * bone's own tail and (transitively) for any walk that needs its frame.
     */
    private static org.joml.Matrix4f computeBoneHeadMatrix(OMOFormat.BoneEntry bone,
                                                     Map<String, OMOFormat.PartEntry> entryById,
                                                     Map<String, OMOFormat.BoneEntry> bonesById,
                                                     Set<String> visiting) {
        org.joml.Matrix4f local = new org.joml.Matrix4f()
                .translate(bone.originX(), bone.originY(), bone.originZ())
                .translate(bone.posX(), bone.posY(), bone.posZ())
                .rotateXYZ(
                        (float) Math.toRadians(bone.rotX()),
                        (float) Math.toRadians(bone.rotY()),
                        (float) Math.toRadians(bone.rotZ())
                );

        String parentId = bone.parentBoneId();
        if (parentId == null) return local;
        if (!visiting.add(bone.id())) {
            logger.warn("Cycle in saved hierarchy at bone {} — treating as root", bone.id());
            return local;
        }
        org.joml.Matrix4f parentMatrix = parentFrameMatrix(parentId, entryById, bonesById, visiting);
        visiting.remove(bone.id());
        return new org.joml.Matrix4f(parentMatrix).mul(local);
    }

    /**
     * Compute the bone's <b>tail</b> world matrix — head matrix translated by the
     * bone-local-space endpoint. This is the frame children of this bone inherit
     * from, matching {@code BoneStore.getTailWorldTransform} at runtime.
     */
    private static org.joml.Matrix4f computeBoneTailMatrix(OMOFormat.BoneEntry bone,
                                                     Map<String, OMOFormat.PartEntry> entryById,
                                                     Map<String, OMOFormat.BoneEntry> bonesById,
                                                     Set<String> visiting) {
        org.joml.Matrix4f head = computeBoneHeadMatrix(bone, entryById, bonesById, visiting);
        return new org.joml.Matrix4f(head)
                .translate(bone.endpointX(), bone.endpointY(), bone.endpointZ());
    }

    /**
     * Resolve the frame that a node with parent {@code parentId} inherits from —
     * part parent → part's effective matrix; bone parent → bone's tail matrix.
     */
    private static org.joml.Matrix4f parentFrameMatrix(String parentId,
                                                 Map<String, OMOFormat.PartEntry> entryById,
                                                 Map<String, OMOFormat.BoneEntry> bonesById,
                                                 Set<String> visiting) {
        if (entryById.containsKey(parentId)) {
            return computeEffectiveMatrix(entryById.get(parentId), entryById, bonesById, visiting);
        }
        if (bonesById != null && bonesById.containsKey(parentId)) {
            return computeBoneTailMatrix(bonesById.get(parentId), entryById, bonesById, visiting);
        }
        return new org.joml.Matrix4f();
    }

    private static boolean isIdentityMatrix(org.joml.Matrix4f m) {
        return m != null
                && m.m00() == 1 && m.m11() == 1 && m.m22() == 1 && m.m33() == 1
                && m.m01() == 0 && m.m02() == 0 && m.m03() == 0
                && m.m10() == 0 && m.m12() == 0 && m.m13() == 0
                && m.m20() == 0 && m.m21() == 0 && m.m23() == 0
                && m.m30() == 0 && m.m31() == 0 && m.m32() == 0;
    }

}
