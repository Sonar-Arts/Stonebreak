package com.openmason.main.systems.scripting.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.scripting.commands.CommandException;
import com.openmason.main.systems.scripting.commands.ModelCommands;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The JSON op-batch frontend: {@code {"version":1, "ops":[{"op":...}, ...]}}.
 *
 * <p><b>Validation-first</b>: {@link #validate} checks the whole batch —
 * unknown ops, missing/mistyped fields, bad enum values, undefined
 * {@code {"ref":...}} face selections — with zero document mutation.
 * {@link #execute} then applies ops in order against {@link ModelCommands};
 * a failure at op <i>i</i> throws with that index, and the caller guarantees
 * atomicity (headless: discard the document; live: restore the snapshot).
 *
 * <p>Face selections ({@code faces}) accept three forms: a bare index array,
 * {@code {"facing":"+y"}} (world-space direction), or {@code {"ref":"name"}}
 * — where {@code "as":"name"} on a topology op binds its result faces for
 * later ops (extrude/inset result ids are unknowable in advance).
 */
public final class OpBatchExecutor {

    /** Supported batch schema version. */
    public static final int VERSION = 1;

    private static final Map<String, OpSpec> OPS = buildRegistry();

    private final ObjectMapper mapper;

    public OpBatchExecutor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Parse batch JSON text. Throws {@link OpBatchException} on malformed input. */
    public JsonNode parse(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new OpBatchException(-1, "Batch is not valid JSON: " + e.getMessage(),
                    "expected {\"version\":1, \"ops\":[{\"op\":\"create_part\", ...}]}");
        }
        if (root == null || !root.isObject()) {
            throw new OpBatchException(-1, "Batch must be a JSON object",
                    "expected {\"version\":1, \"ops\":[...]}");
        }
        return root;
    }

    /** Structural validation of the whole batch; zero document mutation. */
    public void validate(JsonNode root) {
        int version = root.path("version").asInt(VERSION);
        if (version != VERSION) {
            throw new OpBatchException(-1, "Unsupported batch version " + version,
                    "this build supports version " + VERSION);
        }
        JsonNode ops = root.get("ops");
        if (ops == null || !ops.isArray() || ops.isEmpty()) {
            throw new OpBatchException(-1, "Batch has no ops",
                    "\"ops\" must be a non-empty array of {\"op\":...} objects");
        }

        Set<String> definedRefs = new HashSet<>();
        for (int i = 0; i < ops.size(); i++) {
            JsonNode op = ops.get(i);
            if (op == null || !op.isObject()) {
                throw new OpBatchException(i, "ops[" + i + "] must be an object", null);
            }
            String name = op.path("op").asText(null);
            if (name == null || name.isBlank()) {
                throw new OpBatchException(i, "ops[" + i + "] is missing \"op\"",
                        "valid ops: " + String.join(", ", OPS.keySet()));
            }
            OpSpec spec = OPS.get(name);
            if (spec == null) {
                throw new OpBatchException(i, "Unknown op '" + name + "'",
                        "valid ops: " + String.join(", ", OPS.keySet()));
            }
            try {
                spec.validate(op, definedRefs);
            } catch (OpBatchException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new OpBatchException(i, e.getMessage(), hintOf(e));
            }
            String as = op.path("as").asText(null);
            if (as != null && !as.isBlank()) {
                definedRefs.add(as);
            }
        }
    }

    /** Apply the (already validated) batch in order. Throws with the failing op index. */
    public void execute(JsonNode root, ModelCommands cmds) {
        JsonNode ops = root.get("ops");
        Map<String, FaceBinding> bindings = new HashMap<>();
        for (int i = 0; i < ops.size(); i++) {
            JsonNode op = ops.get(i);
            String name = op.get("op").asText();
            OpSpec spec = OPS.get(name);
            try {
                int[] resultFaces = spec.execute(cmds, op, bindings);
                String as = op.path("as").asText(null);
                if (as != null && !as.isBlank()) {
                    bindings.put(as, new FaceBinding(op.path("part").asText(null), resultFaces));
                }
            } catch (OpBatchException e) {
                throw e;
            } catch (CommandException e) {
                throw new OpBatchException(i, e.getMessage(), e.hint());
            } catch (RuntimeException e) {
                throw new OpBatchException(i, "op '" + name + "' failed: " + e.getMessage(), null);
            }
        }
    }

    /** A bound face selection: the owning part plus its part-local ids. */
    private record FaceBinding(String part, int[] localFaceIds) {
    }

    // ===================== Op registry =====================

    private interface OpSpec {
        /** Structural validation only — no document access. */
        void validate(JsonNode op, Set<String> definedRefs);

        /** Execute; return result face ids for {@code "as"} binding (null = not bindable). */
        int[] execute(ModelCommands cmds, JsonNode op, Map<String, FaceBinding> bindings);
    }

    private static Map<String, OpSpec> buildRegistry() {
        Map<String, OpSpec> ops = new LinkedHashMap<>();

        ops.put("create_part", spec(
                op -> {
                    reqStr(op, "shape");
                    reqStr(op, "name");
                    optVecCheck(op, "size");
                    optVecCheck(op, "position");
                    optVecCheck(op, "rotation");
                },
                (cmds, op, b) -> {
                    cmds.createPart(reqStr(op, "shape"), reqStr(op, "name"),
                            optVec(op, "size"), optVec(op, "position"), optVec(op, "rotation"),
                            optStr(op, "parent"));
                    return null;
                }));

        ops.put("duplicate_part", spec(
                op -> {
                    reqStr(op, "part");
                    reqStr(op, "name");
                    optVecCheck(op, "offset");
                },
                (cmds, op, b) -> {
                    cmds.duplicatePart(reqStr(op, "part"), reqStr(op, "name"), optVec(op, "offset"));
                    return null;
                }));

        ops.put("mirror_part", spec(
                op -> {
                    reqStr(op, "part");
                    String axis = reqStr(op, "axis");
                    if (axis.length() != 1 || "xyz".indexOf(axis.toLowerCase(Locale.ROOT).charAt(0)) < 0) {
                        throw new IllegalArgumentException("axis must be \"x\", \"y\" or \"z\", got '" + axis + "'");
                    }
                },
                (cmds, op, b) -> {
                    cmds.mirrorPart(reqStr(op, "part"), reqStr(op, "axis").charAt(0), optStr(op, "name"));
                    return null;
                }));

        ops.put("remove_part", spec(
                op -> reqStr(op, "part"),
                (cmds, op, b) -> {
                    cmds.removePart(reqStr(op, "part"));
                    return null;
                }));

        ops.put("rename_part", spec(
                op -> {
                    reqStr(op, "part");
                    reqStr(op, "name");
                },
                (cmds, op, b) -> {
                    cmds.renamePart(reqStr(op, "part"), reqStr(op, "name"));
                    return null;
                }));

        ops.put("set_parent", spec(
                op -> reqStr(op, "part"),
                (cmds, op, b) -> {
                    cmds.setParent(reqStr(op, "part"), optStr(op, "parent"), true);
                    return null;
                }));

        ops.put("set_visibility", spec(
                op -> {
                    reqStr(op, "part");
                    reqBool(op, "visible");
                },
                (cmds, op, b) -> {
                    cmds.setVisibility(reqStr(op, "part"), reqBool(op, "visible"));
                    return null;
                }));

        ops.put("set_transform", spec(
                op -> {
                    reqStr(op, "part");
                    optVecCheck(op, "origin");
                    optVecCheck(op, "position");
                    optVecCheck(op, "rotation");
                    optVecCheck(op, "scale");
                },
                (cmds, op, b) -> {
                    cmds.setTransform(reqStr(op, "part"), optVec(op, "origin"),
                            optVec(op, "position"), optVec(op, "rotation"), optVec(op, "scale"));
                    return null;
                }));

        ops.put("translate", spec(
                op -> {
                    reqStr(op, "part");
                    reqVecCheck(op, "delta");
                },
                (cmds, op, b) -> {
                    cmds.translate(reqStr(op, "part"), reqVec(op, "delta"));
                    return null;
                }));

        ops.put("rotate", spec(
                op -> {
                    reqStr(op, "part");
                    reqVecCheck(op, "delta");
                },
                (cmds, op, b) -> {
                    cmds.rotate(reqStr(op, "part"), reqVec(op, "delta"));
                    return null;
                }));

        ops.put("scale", spec(
                op -> {
                    reqStr(op, "part");
                    reqVecCheck(op, "factors");
                },
                (cmds, op, b) -> {
                    cmds.scale(reqStr(op, "part"), reqVec(op, "factors"));
                    return null;
                }));

        ops.put("extrude_faces", spec(
                op -> {
                    reqStr(op, "part");
                    facesCheck(op);
                    reqNum(op, "offset");
                },
                (cmds, op, b) -> cmds.extrudeFaces(reqStr(op, "part"),
                        resolveFaces(cmds, op, b), (float) reqNum(op, "offset")).newLocalFaceIds()));

        ops.put("inset_faces", spec(
                op -> {
                    reqStr(op, "part");
                    facesCheck(op);
                    reqNum(op, "amount");
                },
                (cmds, op, b) -> cmds.insetFaces(reqStr(op, "part"),
                        resolveFaces(cmds, op, b), (float) reqNum(op, "amount")).newLocalFaceIds()));

        ops.put("scale_faces", spec(
                op -> {
                    reqStr(op, "part");
                    facesCheck(op);
                    reqNum(op, "factor");
                    optVecCheck(op, "pivot");
                },
                (cmds, op, b) -> {
                    cmds.scaleFaces(reqStr(op, "part"), resolveFaces(cmds, op, b),
                            (float) reqNum(op, "factor"), optVec(op, "pivot"));
                    return null;
                }));

        ops.put("delete_faces", spec(
                op -> {
                    reqStr(op, "part");
                    facesCheck(op);
                },
                (cmds, op, b) -> {
                    cmds.deleteFaces(reqStr(op, "part"), resolveFaces(cmds, op, b));
                    return null;
                }));

        ops.put("subdivide_edge", spec(
                op -> {
                    reqStr(op, "part");
                    reqNum(op, "v_a");
                    reqNum(op, "v_b");
                    double t = reqNum(op, "t");
                    if (!(t > 0.0 && t < 1.0)) {
                        throw new IllegalArgumentException("t must be strictly between 0 and 1, got " + t);
                    }
                },
                (cmds, op, b) -> {
                    cmds.subdivideEdge(reqStr(op, "part"),
                            (int) reqNum(op, "v_a"), (int) reqNum(op, "v_b"), (float) reqNum(op, "t"));
                    return null;
                }));

        ops.put("move_vertices", spec(
                op -> {
                    reqStr(op, "part");
                    reqIntArrCheck(op, "vertices");
                    reqVecCheck(op, "xyz");
                },
                (cmds, op, b) -> {
                    cmds.moveVertices(reqStr(op, "part"), reqIntArr(op, "vertices"),
                            reqVec(op, "xyz"), op.path("absolute").asBoolean(false));
                    return null;
                }));

        ops.put("set_vertex", spec(
                op -> {
                    reqStr(op, "part");
                    reqNum(op, "index");
                    reqVecCheck(op, "position");
                },
                (cmds, op, b) -> {
                    cmds.moveVertices(reqStr(op, "part"),
                            new int[]{(int) reqNum(op, "index")}, reqVec(op, "position"), true);
                    return null;
                }));

        ops.put("set_geometry", spec(
                op -> {
                    reqStr(op, "part");
                    reqFloatArrCheck(op, "vertices");
                    reqIntArrCheck(op, "indices");
                },
                (cmds, op, b) -> {
                    cmds.setGeometry(reqStr(op, "part"),
                            reqFloatArr(op, "vertices"), reqIntArr(op, "indices"),
                            optFloatArr(op, "tex_coords"), optIntArr(op, "triangle_to_face_id"));
                    return null;
                }));

        ops.put("define_material", spec(
                op -> {
                    reqStr(op, "name");
                    JsonNode tint = op.get("tint");
                    if (tint != null && (!tint.isArray() || tint.size() != 4)) {
                        throw new IllegalArgumentException("tint must be [r,g,b,a] with values 0..255");
                    }
                },
                (cmds, op, b) -> {
                    cmds.defineMaterial(reqStr(op, "name"), optIntArr(op, "tint"),
                            op.path("emissive").asBoolean(false), optStr(op, "layer"));
                    return null;
                }));

        ops.put("set_face_material", spec(
                op -> {
                    reqStr(op, "part");
                    facesCheck(op);
                    reqStr(op, "material");
                },
                (cmds, op, b) -> {
                    cmds.setFaceMaterial(reqStr(op, "part"), resolveFaces(cmds, op, b),
                            reqStr(op, "material"));
                    return null;
                }));

        ops.put("anim_clip", spec(
                op -> reqStr(op, "name"),
                (cmds, op, b) -> {
                    cmds.anim().createClip(reqStr(op, "name"),
                            optFloat(op, "duration"), optFloat(op, "fps"), optBoolBoxed(op, "loop"));
                    return null;
                }));

        ops.put("anim_key", spec(
                op -> {
                    reqStr(op, "part");
                    reqNum(op, "time");
                    optVecCheck(op, "position");
                    optVecCheck(op, "rotation");
                    optVecCheck(op, "scale");
                },
                (cmds, op, b) -> {
                    cmds.anim().key(optStr(op, "clip"), reqStr(op, "part"),
                            (float) reqNum(op, "time"),
                            optVec(op, "position"), optVec(op, "rotation"), optVec(op, "scale"),
                            optStr(op, "easing"));
                    return null;
                }));

        ops.put("anim_layer", spec(
                op -> {
                    JsonNode mask = op.get("mask");
                    if (mask != null && !mask.isArray()) {
                        throw new IllegalArgumentException("mask must be an array of part names");
                    }
                },
                (cmds, op, b) -> {
                    java.util.List<String> mask = op.has("mask") ? strList(op.get("mask")) : null;
                    Float priority = optFloat(op, "priority");
                    cmds.anim().setLayer(optStr(op, "clip"), optStr(op, "type"), mask,
                            optFloat(op, "fade_in"), optFloat(op, "fade_out"),
                            priority != null ? priority.intValue() : null);
                    return null;
                }));

        ops.put("anim_save", spec(
                op -> reqStr(op, "path"),
                (cmds, op, b) -> {
                    cmds.anim().save(optStr(op, "clip"), reqStr(op, "path"));
                    return null;
                }));

        ops.put("set_face_uv", spec(
                op -> {
                    reqStr(op, "part");
                    facesCheck(op);
                    JsonNode region = op.get("region");
                    if (region == null || !region.isArray() || region.size() != 4) {
                        throw new IllegalArgumentException("region must be [u0,v0,u1,v1] with values 0..1");
                    }
                },
                (cmds, op, b) -> {
                    cmds.setFaceUV(reqStr(op, "part"), resolveFaces(cmds, op, b),
                            reqFloatArr(op, "region"), op.path("rotation").asInt(0));
                    return null;
                }));

        return ops;
    }

    /** Names of all registered ops (for docs / cheatsheets). */
    public static Set<String> opNames() {
        return OPS.keySet();
    }

    private static OpSpec spec(java.util.function.Consumer<JsonNode> check,
                               TriFunction executor) {
        return new OpSpec() {
            @Override
            public void validate(JsonNode op, Set<String> definedRefs) {
                check.accept(op);
                // Static ref check: {"ref": x} must be bound by an earlier "as".
                JsonNode faces = op.get("faces");
                if (faces != null && faces.isObject() && faces.has("ref")) {
                    String ref = faces.get("ref").asText();
                    if (!definedRefs.contains(ref)) {
                        throw new IllegalArgumentException("faces ref '" + ref
                                + "' is not defined by an earlier op's \"as\"");
                    }
                }
            }

            @Override
            public int[] execute(ModelCommands cmds, JsonNode op, Map<String, FaceBinding> bindings) {
                return executor.apply(cmds, op, bindings);
            }
        };
    }

    @FunctionalInterface
    private interface TriFunction {
        int[] apply(ModelCommands cmds, JsonNode op, Map<String, FaceBinding> bindings);
    }

    // ===================== Face selection =====================

    /** Validate the shape of a {@code faces} value (array | {indices}|{facing}|{ref}). */
    private static void facesCheck(JsonNode op) {
        JsonNode faces = op.get("faces");
        if (faces == null || faces.isNull()) {
            throw new IllegalArgumentException("faces is required: an index array, "
                    + "{\"facing\":\"+y\"}, or {\"ref\":\"name\"}");
        }
        if (faces.isArray()) return;
        if (faces.isObject()
                && (faces.has("indices") || faces.has("facing") || faces.has("ref"))) {
            return;
        }
        throw new IllegalArgumentException("faces must be an index array, "
                + "{\"indices\":[...]}, {\"facing\":\"+y\"} or {\"ref\":\"name\"}");
    }

    private static int[] resolveFaces(ModelCommands cmds, JsonNode op,
                                      Map<String, FaceBinding> bindings) {
        JsonNode faces = op.get("faces");
        String part = op.path("part").asText(null);
        if (faces.isArray()) {
            return toIntArray(faces, "faces");
        }
        if (faces.has("indices")) {
            return toIntArray(faces.get("indices"), "faces.indices");
        }
        if (faces.has("facing")) {
            int[] ids = cmds.selectFacesByDirection(part, faces.get("facing").asText());
            if (ids.length == 0) {
                throw new CommandException("No faces of '" + part + "' face "
                        + faces.get("facing").asText(),
                        "faces match when their normal is within "
                                + (int) com.openmason.main.systems.scripting.commands.FaceSelector.ANGLE_THRESHOLD_DEG
                                + "° of the direction");
            }
            return ids;
        }
        String ref = faces.get("ref").asText();
        FaceBinding binding = bindings.get(ref);
        if (binding == null) {
            throw new CommandException("faces ref '" + ref + "' is not bound",
                    "bind it with \"as\":\"" + ref + "\" on an earlier extrude/inset op");
        }
        if (binding.part() != null && part != null && !binding.part().equals(part)) {
            throw new CommandException("faces ref '" + ref + "' belongs to part '"
                    + binding.part() + "', not '" + part + "'");
        }
        if (binding.localFaceIds() == null || binding.localFaceIds().length == 0) {
            throw new CommandException("faces ref '" + ref + "' is empty");
        }
        return binding.localFaceIds();
    }

    // ===================== Field parsing =====================

    private static String reqStr(JsonNode op, String key) {
        JsonNode n = op.get(key);
        if (n == null || !n.isTextual() || n.asText().isBlank()) {
            throw new IllegalArgumentException("missing required string field \"" + key + "\"");
        }
        return n.asText();
    }

    private static String optStr(JsonNode op, String key) {
        JsonNode n = op.get(key);
        return n != null && n.isTextual() && !n.asText().isBlank() ? n.asText() : null;
    }

    private static Float optFloat(JsonNode op, String key) {
        JsonNode n = op.get(key);
        return n != null && n.isNumber() ? n.floatValue() : null;
    }

    private static Boolean optBoolBoxed(JsonNode op, String key) {
        JsonNode n = op.get(key);
        return n != null && n.isBoolean() ? n.asBoolean() : null;
    }

    private static java.util.List<String> strList(JsonNode arr) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (JsonNode item : arr) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                out.add(item.asText());
            }
        }
        return out;
    }

    private static double reqNum(JsonNode op, String key) {
        JsonNode n = op.get(key);
        if (n == null || !n.isNumber()) {
            throw new IllegalArgumentException("missing required number field \"" + key + "\"");
        }
        return n.doubleValue();
    }

    private static boolean reqBool(JsonNode op, String key) {
        JsonNode n = op.get(key);
        if (n == null || !n.isBoolean()) {
            throw new IllegalArgumentException("missing required boolean field \"" + key + "\"");
        }
        return n.asBoolean();
    }

    private static void reqVecCheck(JsonNode op, String key) {
        JsonNode n = op.get(key);
        if (n == null) {
            throw new IllegalArgumentException("missing required [x,y,z] field \"" + key + "\"");
        }
        vecCheck(n, key);
    }

    private static void optVecCheck(JsonNode op, String key) {
        JsonNode n = op.get(key);
        if (n != null && !n.isNull()) {
            vecCheck(n, key);
        }
    }

    private static void vecCheck(JsonNode n, String key) {
        if (!n.isArray() || n.size() != 3
                || !n.get(0).isNumber() || !n.get(1).isNumber() || !n.get(2).isNumber()) {
            throw new IllegalArgumentException("\"" + key + "\" must be a [x,y,z] number array");
        }
    }

    private static Vector3f reqVec(JsonNode op, String key) {
        JsonNode n = op.get(key);
        return new Vector3f(n.get(0).floatValue(), n.get(1).floatValue(), n.get(2).floatValue());
    }

    private static Vector3f optVec(JsonNode op, String key) {
        JsonNode n = op.get(key);
        if (n == null || n.isNull()) return null;
        return new Vector3f(n.get(0).floatValue(), n.get(1).floatValue(), n.get(2).floatValue());
    }

    private static void reqIntArrCheck(JsonNode op, String key) {
        JsonNode n = op.get(key);
        if (n == null || !n.isArray() || n.isEmpty()) {
            throw new IllegalArgumentException("missing required int array field \"" + key + "\"");
        }
    }

    private static void reqFloatArrCheck(JsonNode op, String key) {
        JsonNode n = op.get(key);
        if (n == null || !n.isArray() || n.isEmpty()) {
            throw new IllegalArgumentException("missing required number array field \"" + key + "\"");
        }
    }

    private static int[] reqIntArr(JsonNode op, String key) {
        return toIntArray(op.get(key), key);
    }

    private static int[] optIntArr(JsonNode op, String key) {
        JsonNode n = op.get(key);
        return n == null || n.isNull() ? null : toIntArray(n, key);
    }

    private static float[] reqFloatArr(JsonNode op, String key) {
        return toFloatArray(op.get(key), key);
    }

    private static float[] optFloatArr(JsonNode op, String key) {
        JsonNode n = op.get(key);
        return n == null || n.isNull() ? null : toFloatArray(n, key);
    }

    private static int[] toIntArray(JsonNode arr, String what) {
        if (arr == null || !arr.isArray()) {
            throw new IllegalArgumentException("\"" + what + "\" must be an array of integers");
        }
        int[] out = new int[arr.size()];
        for (int i = 0; i < out.length; i++) {
            JsonNode item = arr.get(i);
            if (item == null || !item.isNumber()) {
                throw new IllegalArgumentException(what + "[" + i + "] is not a number");
            }
            out[i] = item.intValue();
        }
        return out;
    }

    private static float[] toFloatArray(JsonNode arr, String what) {
        if (arr == null || !arr.isArray()) {
            throw new IllegalArgumentException("\"" + what + "\" must be an array of numbers");
        }
        float[] out = new float[arr.size()];
        for (int i = 0; i < out.length; i++) {
            JsonNode item = arr.get(i);
            if (item == null || !item.isNumber()) {
                throw new IllegalArgumentException(what + "[" + i + "] is not a number");
            }
            out[i] = item.floatValue();
        }
        return out;
    }

    private static String hintOf(RuntimeException e) {
        return e instanceof CommandException ce ? ce.hint() : null;
    }
}
