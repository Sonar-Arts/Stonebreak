package com.openmason.main.systems.scripting.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.scripting.commands.CommandException;
import com.openmason.main.systems.scripting.commands.ModelCommands;
import org.graalvm.polyglot.HostAccess;
import org.joml.Vector3f;

/**
 * The host object the Python {@code om} shim calls into. Exposes
 * {@link ModelCommands} through primitives, strings and flat arrays only —
 * every method is {@code @HostAccess.Export}ed explicitly; nothing else on the
 * host side is reachable from guest code.
 *
 * <p>Numeric parameters are {@code double}/{@code double[]} on purpose:
 * Python floats are doubles, and polyglot host interop rejects lossy
 * double→float narrowing (e.g. {@code math.cos(math.pi/2)} cannot convert to
 * a {@code float} parameter — it fails method matching with a cryptic
 * TypeError). Vectors are {@code double[3]} (null = omitted), selections are
 * {@code int[]}, structured query results come back as JSON strings the shim
 * parses with the Python {@code json} module.
 */
public final class OmHostBridge {

    private final ModelCommands commands;
    private final ObjectMapper mapper;

    public OmHostBridge(ModelCommands commands, ObjectMapper mapper) {
        this.commands = commands;
        this.mapper = mapper;
    }

    // ===================== Parts =====================

    @HostAccess.Export
    public String createPart(String shape, String name, double[] size,
                             double[] position, double[] rotation, String parent) {
        return commands.createPart(shape, name, vec(size), vec(position), vec(rotation), parent).name();
    }

    @HostAccess.Export
    public String duplicatePart(String src, String newName, double[] offset) {
        return commands.duplicatePart(src, newName, vec(offset)).name();
    }

    @HostAccess.Export
    public String mirrorPart(String src, String axis, String newName) {
        char ax = axis != null && !axis.isEmpty() ? axis.charAt(0) : '?';
        return commands.mirrorPart(src, ax, newName).name();
    }

    @HostAccess.Export
    public void removePart(String part) {
        commands.removePart(part);
    }

    @HostAccess.Export
    public String renamePart(String part, String newName) {
        return commands.renamePart(part, newName).name();
    }

    @HostAccess.Export
    public void setParent(String part, String parentOrNull) {
        commands.setParent(part, parentOrNull, true);
    }

    @HostAccess.Export
    public void setVisibility(String part, boolean visible) {
        commands.setVisibility(part, visible);
    }

    // ===================== Transforms =====================

    @HostAccess.Export
    public void setTransform(String part, double[] origin, double[] position,
                             double[] rotation, double[] scale) {
        commands.setTransform(part, vec(origin), vec(position), vec(rotation), vec(scale));
    }

    @HostAccess.Export
    public void translate(String part, double[] delta) {
        commands.translate(part, req(delta, "delta"));
    }

    @HostAccess.Export
    public void rotate(String part, double[] eulerDeltaDeg) {
        commands.rotate(part, req(eulerDeltaDeg, "rotation delta"));
    }

    @HostAccess.Export
    public void scalePart(String part, double[] factors) {
        commands.scale(part, req(factors, "scale factors"));
    }

    // ===================== Faces =====================

    @HostAccess.Export
    public int[] facesByDirection(String part, String direction) {
        return commands.selectFacesByDirection(part, direction);
    }

    @HostAccess.Export
    public int faceCount(String part) {
        return commands.info(part).faces();
    }

    @HostAccess.Export
    public int[] extrudeFaces(String part, int[] faces, double offset) {
        return commands.extrudeFaces(part, faces, (float) offset).newLocalFaceIds();
    }

    @HostAccess.Export
    public int[] insetFaces(String part, int[] faces, double amount) {
        return commands.insetFaces(part, faces, (float) amount).newLocalFaceIds();
    }

    @HostAccess.Export
    public void scaleFaces(String part, int[] faces, double factor, double[] pivot) {
        commands.scaleFaces(part, faces, (float) factor, vec(pivot));
    }

    @HostAccess.Export
    public void deleteFaces(String part, int[] faces) {
        commands.deleteFaces(part, faces);
    }

    // ===================== Vertices / geometry =====================

    @HostAccess.Export
    public int subdivideEdge(String part, int vA, int vB, double t) {
        return commands.subdivideEdge(part, vA, vB, (float) t);
    }

    @HostAccess.Export
    public void moveVertices(String part, int[] vertices, double[] xyz, boolean absolute) {
        commands.moveVertices(part, vertices, req(xyz, "xyz"), absolute);
    }

    @HostAccess.Export
    public float[] vertex(String part, int localIndex) {
        return commands.vertex(part, localIndex);
    }

    @HostAccess.Export
    public int vertexCount(String part) {
        return commands.info(part).verts();
    }

    @HostAccess.Export
    public void setGeometry(String part, double[] vertices, int[] indices,
                            double[] texCoords, int[] triangleToFaceId) {
        commands.setGeometry(part, toFloat(vertices), indices, toFloat(texCoords), triangleToFaceId);
    }

    // ===================== Materials =====================

    @HostAccess.Export
    public int defineMaterial(String name, int[] tint, boolean emissive, String layer) {
        return commands.defineMaterial(name, tint, emissive, layer);
    }

    @HostAccess.Export
    public void setFaceMaterial(String part, int[] faces, String material) {
        commands.setFaceMaterial(part, faces, material);
    }

    @HostAccess.Export
    public void setFaceUV(String part, int[] faces, double[] region, int rotationDeg) {
        commands.setFaceUV(part, faces, toFloat(region), rotationDeg);
    }

    // ===================== Animation (detached .omanim clips) =====================

    @HostAccess.Export
    public String animClip(String name, Double duration, Double fps, Boolean loop) {
        return commands.anim().createClip(name,
                duration != null ? duration.floatValue() : null,
                fps != null ? fps.floatValue() : null,
                loop);
    }

    @HostAccess.Export
    public void animKey(String clip, String part, double time,
                        double[] position, double[] rotation, double[] scale, String easing) {
        commands.anim().key(clip, part, (float) time, vec(position), vec(rotation), vec(scale), easing);
    }

    @HostAccess.Export
    public void animLayer(String clip, String type, String[] mask,
                          Double fadeIn, Double fadeOut, Integer priority) {
        commands.anim().setLayer(clip, type,
                mask != null ? java.util.List.of(mask) : null,
                fadeIn != null ? fadeIn.floatValue() : null,
                fadeOut != null ? fadeOut.floatValue() : null,
                priority);
    }

    @HostAccess.Export
    public void animSave(String clip, String path) {
        commands.anim().save(clip, path);
    }

    @HostAccess.Export
    public String animInfoJson(String clip) {
        return toJson(commands.anim().info(clip));
    }

    // ===================== Queries =====================

    @HostAccess.Export
    public String[] partNames() {
        return commands.partNames().toArray(new String[0]);
    }

    @HostAccess.Export
    public boolean partExists(String name) {
        return commands.partNames().contains(name);
    }

    @HostAccess.Export
    public String infoJson(String part) {
        return toJson(commands.info(part));
    }

    @HostAccess.Export
    public String summaryJson() {
        return toJson(commands.summary());
    }

    // ===================== Helpers =====================

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize result: " + e.getMessage());
        }
    }

    private static Vector3f vec(double[] v) {
        if (v == null) return null;
        if (v.length != 3) {
            throw new CommandException(
                    "vector needs exactly 3 components, got " + v.length, "pass (x, y, z)");
        }
        return new Vector3f((float) v[0], (float) v[1], (float) v[2]);
    }

    private static Vector3f req(double[] v, String what) {
        Vector3f out = vec(v);
        if (out == null) {
            throw new CommandException(what + " is required");
        }
        return out;
    }

    private static float[] toFloat(double[] v) {
        if (v == null) return null;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) v[i];
        return out;
    }
}
