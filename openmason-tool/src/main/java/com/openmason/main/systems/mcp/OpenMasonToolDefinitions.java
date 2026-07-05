package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.joml.Vector3f;

import static com.openmason.main.systems.mcp.McpArgs.optBool;
import static com.openmason.main.systems.mcp.McpArgs.optFloatArray;
import static com.openmason.main.systems.mcp.McpArgs.optIntArray;
import static com.openmason.main.systems.mcp.McpArgs.optVec3;
import static com.openmason.main.systems.mcp.McpArgs.reqFloat;
import static com.openmason.main.systems.mcp.McpArgs.reqIntArray;
import static com.openmason.main.systems.mcp.McpArgs.reqString;
import static com.openmason.main.systems.mcp.McpArgs.reqVec3;

/**
 * Wires the {@link ModelEditingService} surface as MCP tools.
 *
 * <p>All schemas are JSON Schema draft-07 (the MCP-recommended dialect).
 * Names are snake_case to match common MCP conventions.
 */
public final class OpenMasonToolDefinitions {

    private final ModelEditingService editor;
    private final ObjectMapper mapper;

    public OpenMasonToolDefinitions(ModelEditingService editor, ObjectMapper mapper) {
        this.editor = editor;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        // ---------- Read ----------

        registry.register(new McpTool(
                "list_parts",
                "List parts as compact rows {name, id, verts, tris, visible}. Pass detail=true "
                        + "for full transforms; name_filter narrows by substring.",
                schema()
                        .bool("detail", "Include full transforms and flags per part")
                        .str("name_filter", "Case-insensitive substring filter on part names")
                        .build(),
                args -> editor.listParts(
                        optBool(args, "detail", false),
                        McpArgs.optString(args, "name_filter"))));

        registry.register(new McpTool(
                "get_part",
                "Get a single part by id or name.",
                schema().str("id_or_name", "Part id (UUID) or display name").required("id_or_name").build(),
                args -> editor.getPart(reqString(args, "id_or_name")).orElse(null)));

        registry.register(new McpTool(
                "inspect_part",
                "Deep debug inspection of a single part: descriptor, mesh range, local bounding box, "
                        + "and transform matrix (4x4 row-major, 16 floats). Optional flags include the raw "
                        + "vertex/texCoord/index/face-mapping arrays — leave them off (default) to keep responses small.",
                schema()
                        .str("id_or_name", "Part id or name")
                        .bool("include_vertices", "Include raw interleaved vertex positions (xyz)")
                        .bool("include_tex_coords", "Include raw interleaved texture coords (uv)")
                        .bool("include_indices", "Include raw triangle index buffer")
                        .bool("include_face_mapping", "Include triangle→face id mapping")
                        .required("id_or_name")
                        .build(),
                args -> editor.inspectPart(
                        reqString(args, "id_or_name"),
                        optBool(args, "include_vertices", false),
                        optBool(args, "include_tex_coords", false),
                        optBool(args, "include_indices", false),
                        optBool(args, "include_face_mapping", false)).orElse(null)));

        // ---------- Mutate: parts ----------

        registry.register(new McpTool(
                "create_part",
                "Spawn a new part with a primitive shape (CUBE, PYRAMID, PANE, SPRITE) at the model origin. Auto-creates a blank model if none is loaded.",
                schema()
                        .str("shape", "One of: CUBE, PYRAMID, PANE, SPRITE")
                        .str("name", "Display name for the new part")
                        .vec3("size", "[x,y,z] size (default [1,1,1])")
                        .bool("verbose", "Return the full part view instead of a terse ack")
                        .required("shape", "name")
                        .build(),
                args -> {
                    Vector3f size = optVec3(args, "size");
                    if (size == null) size = new Vector3f(1.0f);
                    return ack(java.util.Optional.ofNullable(
                                    editor.createPart(reqString(args, "shape"), reqString(args, "name"), size)),
                            optBool(args, "verbose", false));
                }));

        registry.register(new McpTool(
                "delete_part",
                "Remove a part from the model.",
                idOrNameSchema(),
                args -> editor.deletePart(reqString(args, "id_or_name"))));

        registry.register(new McpTool(
                "duplicate_part",
                "Duplicate an existing part (geometry + transform). Returns the new part's name.",
                schema()
                        .str("id_or_name", "Part id or name")
                        .bool("verbose", "Return the full part view instead of a terse ack")
                        .required("id_or_name")
                        .build(),
                args -> ack(editor.duplicatePart(reqString(args, "id_or_name")),
                        optBool(args, "verbose", false))));

        registry.register(new McpTool(
                "rename_part",
                "Rename a part.",
                schema()
                        .str("id_or_name", "Part id or current name")
                        .str("new_name", "New display name")
                        .bool("verbose", "Return the full part view instead of a terse ack")
                        .required("id_or_name", "new_name")
                        .build(),
                args -> ack(editor.renamePart(reqString(args, "id_or_name"), reqString(args, "new_name")),
                        optBool(args, "verbose", false))));

        registry.register(new McpTool(
                "part_transform",
                "Transform a part. Absolute fields: origin/position/rotation/scale (omit to keep). "
                        + "Delta fields: translate (added to position), rotate (Euler degree deltas), "
                        + "scale_by (per-axis factors). Rotation is Euler XYZ degrees. Pass at least one field.",
                schema()
                        .str("id_or_name", "Part id or name")
                        .vec3("origin", "Absolute pivot [x,y,z]")
                        .vec3("position", "Absolute translation [x,y,z]")
                        .vec3("rotation", "Absolute Euler degrees [x,y,z]")
                        .vec3("scale", "Absolute scale [x,y,z]")
                        .vec3("translate", "Delta added to position [x,y,z]")
                        .vec3("rotate", "Euler degree deltas [x,y,z]")
                        .vec3("scale_by", "Per-axis scale factors [x,y,z]")
                        .bool("verbose", "Return the full part view instead of a terse ack")
                        .required("id_or_name")
                        .build(),
                args -> partTransform(args)));

        registry.register(new McpTool(
                "set_part_visibility",
                "Show or hide a part.",
                schema()
                        .str("id_or_name", "Part id or name")
                        .bool("visible", "true to show, false to hide")
                        .bool("verbose", "Return the full part view instead of a terse ack")
                        .required("id_or_name", "visible")
                        .build(),
                args -> ack(editor.setPartVisibility(
                        reqString(args, "id_or_name"), McpArgs.reqBool(args, "visible")),
                        optBool(args, "verbose", false))));

        registry.register(new McpTool(
                "select_part",
                "Select a part. Pass additive=true to add to the current selection (default replaces it).",
                schema()
                        .str("id_or_name", "Part id or name")
                        .bool("additive", "Add to existing selection (default false)")
                        .required("id_or_name")
                        .build(),
                args -> editor.selectPart(
                        reqString(args, "id_or_name"),
                        args.has("additive") && args.get("additive").asBoolean(false))));

        // ---------- Mutate: mesh elements ----------

        registry.register(new McpTool(
                "part_mesh",
                "Read a part's topology: vertices as {count, positions} (flat [x,y,z,...], "
                        + "vertex i at positions[3i]), edges as {count, vertexPairs} (flat [a,b,...]), "
                        + "faces as [{faceId, vertices}] (part-local vertex indices). 'include' picks "
                        + "sections (default all three). Indices are stable across positional edits.",
                schema()
                        .str("id_or_name", "Part id or name")
                        .strArray("include", "Sections to return: vertices, edges, faces (default all)")
                        .required("id_or_name")
                        .build(),
                args -> partMesh(args)));

        registry.register(new McpTool(
                "part_move",
                "Translate a mesh element of a part: element=vertex|edge|face with its part-local "
                        + "index (from part_mesh). xyz is a delta; absolute=true (vertex only) treats "
                        + "it as the new position.",
                schema()
                        .str("id_or_name", "Part id or name")
                        .enumStr("element", "What to move", "vertex", "edge", "face")
                        .num("index", "Part-local vertex/edge/face index from part_mesh")
                        .vec3("xyz", "Delta (default) or absolute position [x,y,z]")
                        .bool("absolute", "Treat xyz as the new position (vertex only)")
                        .bool("verbose", "Return the full part view instead of a terse ack")
                        .required("id_or_name", "element", "index", "xyz")
                        .build(),
                args -> partMove(args)));

        registry.register(new McpTool(
                "set_part_geometry",
                "Replace a part's geometry wholesale with caller-supplied vertex/index/face data. "
                        + "Topology, vertex count, and face count may all change; the part's transform is preserved. "
                        + "Positions are part-local. vertices is a flat [x,y,z, x,y,z, ...] array. "
                        + "indices is a flat triangle index array (length divisible by 3). "
                        + "tex_coords (optional) is [u,v, u,v, ...] matching vertex count; defaults to zeros. "
                        + "triangle_to_face_id (optional) gives one face id per triangle; defaults to one face per triangle.",
                schema()
                        .str("id_or_name", "Part id or name")
                        .arr("vertices", "number", "Flat [x,y,z, x,y,z, ...] vertex positions in part-local space")
                        .arr("indices", "number", "Flat triangle index array; length must be divisible by 3")
                        .arr("tex_coords", "number", "Optional flat [u,v, u,v, ...] UVs; length must be 2 × vertex count if supplied")
                        .arr("triangle_to_face_id", "number", "Optional one face id per triangle; length must equal triangle count if supplied")
                        .bool("verbose", "Return the full part view instead of a terse ack")
                        .required("id_or_name", "vertices", "indices")
                        .build(),
                args -> ack(editor.setPartGeometry(
                        reqString(args, "id_or_name"),
                        optFloatArray(args, "vertices"),
                        optIntArray(args, "indices"),
                        optFloatArray(args, "tex_coords"),
                        optIntArray(args, "triangle_to_face_id")),
                        optBool(args, "verbose", false))));

        registry.register(new McpTool(
                "subdivide_edge",
                "Insert a vertex on an edge at parametric position t (exclusive 0..1 from the edge's "
                        + "first vertex). Edge index comes from part_mesh. Returns the new vertex's "
                        + "unique id plus a best-effort part-local vertex index.",
                schema()
                        .str("id_or_name", "Part id or name")
                        .num("edge_index", "Edge index from part_mesh")
                        .num("t", "Parametric position along the edge, 0 < t < 1 (0.5 = midpoint)")
                        .required("id_or_name", "edge_index", "t")
                        .build(),
                args -> editor.subdivideEdge(
                        reqString(args, "id_or_name"),
                        (int) reqFloat(args, "edge_index"),
                        reqFloat(args, "t")).orElse(null)));

        registry.register(new McpTool(
                "scale_faces",
                "Uniformly scale the given faces about the selection's area-weighted centroid. "
                        + "Vertices shared with unselected faces move too (Blender face-scale semantics). "
                        + "Face ids come from part_mesh.",
                schema()
                        .str("id_or_name", "Part id or name")
                        .arr("local_face_ids", "number", "Local face ids from part_mesh")
                        .num("factor", "Uniform scale factor (1 = unchanged)")
                        .bool("verbose", "Return the full part view instead of a terse ack")
                        .required("id_or_name", "local_face_ids", "factor")
                        .build(),
                args -> ack(editor.scaleFaces(
                        reqString(args, "id_or_name"),
                        reqIntArray(args, "local_face_ids"),
                        reqFloat(args, "factor")),
                        optBool(args, "verbose", false))));

        registry.register(new McpTool(
                "inset_faces",
                "Inset each of the given faces individually (per-face, even-thickness border of new "
                        + "quads; each original face id keeps its inner cap). Face ids come from "
                        + "part_mesh. Returns the new border-quad face ids.",
                schema()
                        .str("id_or_name", "Part id or name")
                        .arr("local_face_ids", "number", "Local face ids from part_mesh")
                        .num("amount", "Inset distance in model units (> 0)")
                        .required("id_or_name", "local_face_ids", "amount")
                        .build(),
                args -> editor.insetFaces(
                        reqString(args, "id_or_name"),
                        reqIntArray(args, "local_face_ids"),
                        reqFloat(args, "amount")).orElse(null)));

        registry.register(new McpTool(
                "extrude_faces",
                "Extrude each of the given faces individually along its own normal (per-face; each "
                        + "original face id keeps the moved cap, new side quads are created). Face ids "
                        + "come from part_mesh. Returns the new side-quad face ids.",
                schema()
                        .str("id_or_name", "Part id or name")
                        .arr("local_face_ids", "number", "Local face ids from part_mesh")
                        .num("distance", "Signed extrude distance in model units (negative = inward)")
                        .required("id_or_name", "local_face_ids", "distance")
                        .build(),
                args -> editor.extrudeFaces(
                        reqString(args, "id_or_name"),
                        reqIntArray(args, "local_face_ids"),
                        reqFloat(args, "distance")).orElse(null)));

    }

    // ===================== Merged-tool handlers =====================

    /** part_transform: absolute channels + delta channels, applied in order. */
    private Object partTransform(JsonNode args) {
        String idOrName = reqString(args, "id_or_name");
        Vector3f origin = optVec3(args, "origin");
        Vector3f position = optVec3(args, "position");
        Vector3f rotation = optVec3(args, "rotation");
        Vector3f scale = optVec3(args, "scale");
        Vector3f translate = optVec3(args, "translate");
        Vector3f rotate = optVec3(args, "rotate");
        Vector3f scaleBy = optVec3(args, "scale_by");

        if (position != null && translate != null) {
            throw new IllegalArgumentException("pass either position (absolute) or translate (delta), not both");
        }
        if (rotation != null && rotate != null) {
            throw new IllegalArgumentException("pass either rotation (absolute) or rotate (delta), not both");
        }
        if (scale != null && scaleBy != null) {
            throw new IllegalArgumentException("pass either scale (absolute) or scale_by (factors), not both");
        }

        java.util.Optional<ModelEditingService.PartView> last = java.util.Optional.empty();
        if (origin != null || position != null || rotation != null || scale != null) {
            last = editor.setPartTransform(idOrName, origin, position, rotation, scale);
        }
        if (translate != null) last = editor.translatePart(idOrName, translate);
        if (rotate != null) last = editor.rotatePart(idOrName, rotate);
        if (scaleBy != null) last = editor.scalePart(idOrName, scaleBy);
        if (last.isEmpty()) {
            throw new IllegalArgumentException(
                    "pass at least one of origin, position, rotation, scale, translate, rotate, scale_by");
        }
        return ack(last, optBool(args, "verbose", false));
    }

    /** part_mesh: vertices/edges/faces sections in one response. */
    private Object partMesh(JsonNode args) {
        String idOrName = reqString(args, "id_or_name");
        java.util.List<String> include = McpArgs.optStringList(args, "include");
        boolean all = include.isEmpty();
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (String section : all ? java.util.List.of("vertices", "edges", "faces") : include) {
            switch (section.toLowerCase()) {
                case "vertices" -> out.put("vertices",
                        editor.listPartVertices(idOrName).orElse(null));
                case "edges" -> out.put("edges",
                        editor.listPartEdges(idOrName).orElse(null));
                case "faces" -> out.put("faces",
                        editor.listPartFaces(idOrName).orElse(null));
                default -> throw new IllegalArgumentException(
                        "unknown include section '" + section + "' — valid: vertices, edges, faces");
            }
        }
        return out;
    }

    /** part_move: element-dispatched vertex/edge/face translation. */
    private Object partMove(JsonNode args) {
        String idOrName = reqString(args, "id_or_name");
        String element = reqString(args, "element").toLowerCase();
        int index = (int) reqFloat(args, "index");
        Vector3f xyz = reqVec3(args, "xyz");
        boolean absolute = optBool(args, "absolute", false);
        boolean verbose = optBool(args, "verbose", false);

        return switch (element) {
            case "vertex" -> ack(editor.moveVertex(idOrName, index, xyz, absolute), verbose);
            case "edge" -> {
                requireDelta(absolute, "edge");
                yield ack(editor.moveEdge(idOrName, index, xyz), verbose);
            }
            case "face" -> {
                requireDelta(absolute, "face");
                yield ack(editor.moveFace(idOrName, index, xyz), verbose);
            }
            default -> throw new IllegalArgumentException(
                    "unknown element '" + element + "' — valid: vertex, edge, face");
        };
    }

    private static void requireDelta(boolean absolute, String element) {
        if (absolute) {
            throw new IllegalArgumentException(
                    "absolute=true is only supported for element=vertex, not " + element);
        }
    }

    // ===================== Schema helpers =====================

    private McpSchema schema() {
        return McpSchema.of(mapper);
    }

    private JsonNode idOrNameSchema() {
        return schema().str("id_or_name", "Part id or name").required("id_or_name").build();
    }

    /**
     * Terse mutation ack ({@code {ok, part, verts, tris}}) by default; the
     * full refreshed {@link ModelEditingService.PartView} with
     * {@code verbose:true}. Keeps mutation loops cheap for LLM callers.
     */
    private static Object ack(java.util.Optional<ModelEditingService.PartView> view, boolean verbose) {
        if (verbose) {
            return view.orElse(null);
        }
        return view
                .map(v -> McpAck.ok()
                        .with("part", v.name())
                        .with("verts", v.vertexCount())
                        .with("tris", v.triangleCount()))
                .orElse(null);
    }
}
