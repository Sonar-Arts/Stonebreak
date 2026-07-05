package com.openmason.main.systems.scripting.commands;

import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.parts.PartMeshRebuilder;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Selects part-local faces by world-space facing direction ("+y", "up",
 * "north", ...). A face matches when its area-weighted normal — rotated by the
 * part's effective (parent-chain) world matrix — is within
 * {@link #ANGLE_THRESHOLD_DEG} of the direction.
 */
public final class FaceSelector {

    /** A face's normal must be within this many degrees of the requested direction. */
    public static final float ANGLE_THRESHOLD_DEG = 30.0f;

    private static final Map<String, Vector3f> DIRECTIONS = createDirections();

    private FaceSelector() {
    }

    private static Map<String, Vector3f> createDirections() {
        Map<String, Vector3f> m = new LinkedHashMap<>();
        m.put("+x", new Vector3f(1, 0, 0));
        m.put("-x", new Vector3f(-1, 0, 0));
        m.put("+y", new Vector3f(0, 1, 0));
        m.put("-y", new Vector3f(0, -1, 0));
        m.put("+z", new Vector3f(0, 0, 1));
        m.put("-z", new Vector3f(0, 0, -1));
        m.put("up", new Vector3f(0, 1, 0));
        m.put("down", new Vector3f(0, -1, 0));
        // Stonebreak/Minecraft convention: north = -Z, east = +X
        m.put("north", new Vector3f(0, 0, -1));
        m.put("south", new Vector3f(0, 0, 1));
        m.put("east", new Vector3f(1, 0, 0));
        m.put("west", new Vector3f(-1, 0, 0));
        return m;
    }

    /** Valid direction names, for error messages. */
    public static java.util.Set<String> directionNames() {
        return DIRECTIONS.keySet();
    }

    /**
     * Part-local face ids whose world-space normal is within the threshold of
     * {@code direction}.
     *
     * @throws CommandException for an unknown direction name
     */
    public static int[] facesByDirection(ModelPartManager partManager,
                                         ModelPartDescriptor part,
                                         String direction) {
        Vector3f dir = DIRECTIONS.get(direction == null ? "" : direction.trim().toLowerCase());
        if (dir == null) {
            throw new CommandException(
                    "Unknown facing direction '" + direction + "'",
                    "valid directions: " + String.join(", ", DIRECTIONS.keySet()));
        }

        PartMeshRebuilder.PartGeometry geo = partManager.getPartGeometry(part.id());
        if (geo == null || geo.vertices() == null || geo.indices() == null) {
            throw new CommandException("Part '" + part.name() + "' has no geometry");
        }

        // Rotate local normals into world space via the effective matrix's rotation part.
        Matrix4f world = partManager.getEffectiveWorldMatrix(part.id());
        Matrix3f normalMatrix = new Matrix3f();
        world.normal(normalMatrix);

        Map<Integer, Vector3f> faceNormals = accumulateFaceNormals(geo);

        float cosThreshold = (float) Math.cos(Math.toRadians(ANGLE_THRESHOLD_DEG));
        List<Integer> matched = new ArrayList<>();
        for (Map.Entry<Integer, Vector3f> e : faceNormals.entrySet()) {
            Vector3f n = new Vector3f(e.getValue());
            if (n.lengthSquared() < 1e-12f) continue;
            normalMatrix.transform(n).normalize();
            if (n.dot(dir) >= cosThreshold) {
                matched.add(e.getKey());
            }
        }
        int[] out = new int[matched.size()];
        for (int i = 0; i < out.length; i++) out[i] = matched.get(i);
        return out;
    }

    /** Area-weighted normal per local face id (sum of triangle cross products). */
    private static Map<Integer, Vector3f> accumulateFaceNormals(PartMeshRebuilder.PartGeometry geo) {
        float[] v = geo.vertices();
        int[] idx = geo.indices();
        int[] triFace = geo.triangleToFaceId();
        Map<Integer, Vector3f> normals = new LinkedHashMap<>();
        int triCount = idx.length / 3;
        Vector3f e1 = new Vector3f(), e2 = new Vector3f(), cross = new Vector3f();
        for (int t = 0; t < triCount; t++) {
            int a = idx[t * 3] * 3, b = idx[t * 3 + 1] * 3, c = idx[t * 3 + 2] * 3;
            e1.set(v[b] - v[a], v[b + 1] - v[a + 1], v[b + 2] - v[a + 2]);
            e2.set(v[c] - v[a], v[c + 1] - v[a + 1], v[c + 2] - v[a + 2]);
            e1.cross(e2, cross);
            int faceId = triFace != null ? triFace[t] : t;
            normals.computeIfAbsent(faceId, k -> new Vector3f()).add(cross);
        }
        return normals;
    }
}
