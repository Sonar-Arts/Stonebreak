package com.openmason.main.systems.scripting.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.mcp.McpSchema;
import com.openmason.main.systems.mcp.McpTool;
import com.openmason.main.systems.mcp.McpToolRegistry;
import com.openmason.main.systems.scripting.ScriptExecutor.Language;

import static com.openmason.main.systems.mcp.McpArgs.optBool;
import static com.openmason.main.systems.mcp.McpArgs.optInt;
import static com.openmason.main.systems.mcp.McpArgs.reqString;

/**
 * MCP tools for the scripting system — the preferred way to make multi-step
 * model edits: one call, one undo entry, atomic rollback on failure, and a
 * compact summary result instead of per-op echoes.
 */
public final class ScriptingToolDefinitions {

    private final ScriptingService service;
    private final ObjectMapper mapper;

    public ScriptingToolDefinitions(ScriptingService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        registry.register(new McpTool(
                "run_python_script",
                "Run a Python script against the live model using the `om` API — the preferred "
                        + "way to build or edit models in multiple steps (loops + math beat many "
                        + "single-op calls). One undo entry; a failed script rolls back completely. "
                        + "Sandboxed: no files/network/imports beyond Python core + om. "
                        + "Quick reference: om.box(name, size=(x,y,z), at=(x,y,z), rotate=(x,y,z), parent=p); "
                        + "also cylinder/sphere/cone/pyramid/plane/wedge/torus/hemisphere/cross/sprite. "
                        + "p = om.part(name); p.move/rotate/scale/set_position/set_rotation/set_origin; "
                        + "p.duplicate(name, offset=..); om.mirror(p, axis='x'); "
                        + "sel = p.faces(facing='+y') or p.faces([ids]); sel.extrude(d)/inset(a)/scale(f)/"
                        + "delete()/set_material(n)/set_uv(region); p.subdivide_edge(a,b,t); "
                        + "p.set_vertex(i, pos); om.material(name, tint=(r,g,b)); om.summary(). "
                        + "Animation: c = om.anim.clip(name, duration, fps, loop); c.key(part, t, "
                        + "position/rotation/scale/easing — omitted = part's current transform); "
                        + "c.layer(type='overlay', mask=[...]); c.save('x.omanim') (absolute path in "
                        + "live runs; written only on success — load it with anim_load). "
                        + "print(om.help()) for the full cheatsheet. Y-up, degrees, sizes are full "
                        + "extents. Runs on the editor thread — keep scripts under a few seconds.",
                schema()
                        .str("source", "Python source code (import om; ...)")
                        .intg("timeout_sec", "Execution timeout in seconds (default 30, max 300)")
                        .bool("include_trace", "Include the normalized JSON ops trace in the result")
                        .required("source")
                        .build(),
                args -> service.toJson(service.runScript(
                        Language.PYTHON,
                        reqString(args, "source"),
                        optInt(args, "timeout_sec", 30) * 1000L,
                        optBool(args, "include_trace", false)))));

        registry.register(new McpTool(
                "run_model_ops",
                "Run a JSON op batch against the live model: validated in full before anything "
                        + "executes, then applied atomically (one undo entry; failure = full rollback "
                        + "+ failing op_index). Format: {\"version\":1,\"ops\":[{\"op\":\"create_part\","
                        + "\"shape\":\"cube\",\"name\":\"body\",\"size\":[8,4,6],\"position\":[0,4,0]}, ...]}. "
                        + "Face selections: [ids], {\"facing\":\"+y\"}, or {\"ref\":\"x\"} bound by an "
                        + "earlier op's \"as\":\"x\". Ops: create_part, duplicate_part, mirror_part, "
                        + "remove_part, rename_part, set_parent, set_visibility, set_transform, translate, "
                        + "rotate, scale, extrude_faces, inset_faces, scale_faces, delete_faces, "
                        + "subdivide_edge, move_vertices, set_vertex, set_geometry, define_material, "
                        + "set_face_material, set_face_uv, anim_clip, anim_key (omitted pose = part's "
                        + "current transform), anim_layer, anim_save (detached .omanim, written on "
                        + "success). Prefer run_python_script when loops or math would avoid repetition.",
                schema()
                        .str("ops", "The op batch as a JSON string")
                        .bool("include_trace", "Include the normalized ops trace in the result")
                        .required("ops")
                        .build(),
                args -> service.toJson(service.runScript(
                        Language.JSON_OPS,
                        reqString(args, "ops"),
                        ScriptingService.DEFAULT_TIMEOUT_MS,
                        optBool(args, "include_trace", false)))));

        registry.register(new McpTool(
                "validate_model_ops",
                "Validate a JSON op batch without executing anything — checks op names, field "
                        + "types, enum values, and {\"ref\"} bindings. Returns ok or the failing "
                        + "op_index with a teaching error.",
                schema()
                        .str("ops", "The op batch as a JSON string")
                        .required("ops")
                        .build(),
                args -> service.toJson(service.validateOps(reqString(args, "ops")))));
    }

    private McpSchema schema() {
        return McpSchema.of(mapper);
    }
}
