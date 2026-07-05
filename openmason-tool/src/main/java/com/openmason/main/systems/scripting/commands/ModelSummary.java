package com.openmason.main.systems.scripting.commands;

import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.parts.PartMeshRebuilder;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Token-efficient whole-model digest returned to script callers: totals,
 * world-space bounding box, and one compact row per part.
 *
 * <p>Bounding boxes serialize as {@code [[minX,minY,minZ],[maxX,maxY,maxZ]]}.
 */
public record ModelSummary(
        Totals totals,
        float[][] bbox,
        List<PartRow> parts) {

    public record Totals(int parts, int vertices, int triangles, int faces, int materials) {
    }

    public record PartRow(String name, int verts, int faces, float[][] bbox, String parent) {
    }

    public static ModelSummary from(ModelPartManager pm, int materialCount) {
        List<PartRow> rows = new ArrayList<>();
        int totalVerts = 0, totalTris = 0, totalFaces = 0;
        Vector3f modelMin = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f modelMax = new Vector3f(Float.NEGATIVE_INFINITY);

        for (ModelPartDescriptor part : pm.getAllParts()) {
            PartMeshRebuilder.PartGeometry geo = pm.getPartGeometry(part.id());
            int verts = geo != null ? geo.vertexCount() : 0;
            int tris = geo != null ? geo.indexCount() / 3 : 0;
            int faces = geo != null ? geo.faceCount() : 0;
            totalVerts += verts;
            totalTris += tris;
            totalFaces += faces;

            float[][] worldBox = worldBounds(pm, part, geo);
            if (worldBox != null) {
                modelMin.min(new Vector3f(worldBox[0][0], worldBox[0][1], worldBox[0][2]));
                modelMax.max(new Vector3f(worldBox[1][0], worldBox[1][1], worldBox[1][2]));
            }

            String parentName = part.parentId() != null
                    ? pm.getPartById(part.parentId()).map(ModelPartDescriptor::name).orElse(null)
                    : null;
            rows.add(new PartRow(part.name(), verts, faces, worldBox, parentName));
        }

        float[][] modelBox = rows.isEmpty() || modelMin.x == Float.POSITIVE_INFINITY
                ? null
                : new float[][]{
                        {modelMin.x, modelMin.y, modelMin.z},
                        {modelMax.x, modelMax.y, modelMax.z}};

        return new ModelSummary(
                new Totals(rows.size(), totalVerts, totalTris, totalFaces, materialCount),
                modelBox, rows);
    }

    /** World-space AABB of a part: local AABB corners through the effective matrix. */
    private static float[][] worldBounds(ModelPartManager pm, ModelPartDescriptor part,
                                         PartMeshRebuilder.PartGeometry geo) {
        if (geo == null || geo.vertices() == null || geo.vertices().length < 3) {
            return null;
        }
        float[] v = geo.vertices();
        Vector3f lmin = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f lmax = new Vector3f(Float.NEGATIVE_INFINITY);
        for (int i = 0; i + 2 < v.length; i += 3) {
            lmin.min(new Vector3f(v[i], v[i + 1], v[i + 2]));
            lmax.max(new Vector3f(v[i], v[i + 1], v[i + 2]));
        }
        Matrix4f world = pm.getEffectiveWorldMatrix(part.id());
        Vector3f wmin = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f wmax = new Vector3f(Float.NEGATIVE_INFINITY);
        Vector3f corner = new Vector3f();
        for (int i = 0; i < 8; i++) {
            corner.set(
                    (i & 1) == 0 ? lmin.x : lmax.x,
                    (i & 2) == 0 ? lmin.y : lmax.y,
                    (i & 4) == 0 ? lmin.z : lmax.z);
            world.transformPosition(corner);
            wmin.min(corner);
            wmax.max(corner);
        }
        return new float[][]{{wmin.x, wmin.y, wmin.z}, {wmax.x, wmax.y, wmax.z}};
    }
}
