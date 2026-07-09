package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes JSON-RPC method calls for the MCP protocol surface.
 *
 * <p>Implemented methods:
 * <ul>
 *   <li>{@code initialize} — handshake, declares server capabilities</li>
 *   <li>{@code tools/list} — returns registered tools with input schemas</li>
 *   <li>{@code tools/call} — invokes a tool by name with arguments</li>
 *   <li>{@code ping} — liveness check</li>
 *   <li>{@code notifications/initialized} — no-op acknowledgment</li>
 * </ul>
 */
public final class McpRequestRouter {

    private static final Logger logger = LoggerFactory.getLogger(McpRequestRouter.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "open-mason";
    private static final String SERVER_VERSION = "1.0";

    /** Pointer the LLM reads before anything else — kept to two sentences. */
    private static final String INSTRUCTIONS =
            "Call model_summary to orient yourself and describe_api for conventions, "
                    + "the Python om cheatsheet, and workflow recipes. Prefer run_python_script "
                    + "or run_model_ops for multi-step edits (one call, one undo entry, atomic); "
                    + "single-op tools are for inspection and small tweaks.";

    /** Retired tool names → what replaced them, so post-rename calls self-heal. */
    private static final Map<String, String> RETIRED_TOOLS = Map.ofEntries(
            Map.entry("get_model_info", "use model_summary"),
            Map.entry("get_selection", "use model_summary (selection field)"),
            Map.entry("focus_camera_on", "use select_part (identical behavior)"),
            Map.entry("set_part_transform", "merged into part_transform"),
            Map.entry("translate_part", "merged into part_transform (translate field)"),
            Map.entry("rotate_part", "merged into part_transform (rotate field)"),
            Map.entry("scale_part", "merged into part_transform (scale_by field)"),
            Map.entry("list_part_vertices", "merged into part_mesh (include:[\"vertices\"])"),
            Map.entry("list_part_edges", "merged into part_mesh (include:[\"edges\"])"),
            Map.entry("list_part_faces", "merged into part_mesh (include:[\"faces\"])"),
            Map.entry("move_vertex", "merged into part_move (element:\"vertex\")"),
            Map.entry("move_edge", "merged into part_move (element:\"edge\")"),
            Map.entry("move_face", "merged into part_move (element:\"face\")"),
            Map.entry("model_undo", "use undo (domain defaults to model)"),
            Map.entry("model_redo", "use redo (domain defaults to model)"),
            Map.entry("tex_get_pixel", "use tex_get_region with w=h=1"),
            Map.entry("tex_set_pixel", "use tex_set_pixels with one entry"),
            Map.entry("tex_fill_canvas", "merged into tex_fill (omit rect)"),
            Map.entry("tex_clear_canvas", "use tex_fill with color [0,0,0,0]"),
            Map.entry("tex_fill_rect", "merged into tex_fill (rect field)"),
            Map.entry("tex_set_active_layer", "merged into tex_set_layer (active:true)"),
            Map.entry("tex_set_layer_visibility", "merged into tex_set_layer (visible field)"),
            Map.entry("tex_rename_layer", "merged into tex_set_layer (name field)"),
            Map.entry("tex_undo", "use undo with domain:\"texture\""),
            Map.entry("tex_redo", "use redo with domain:\"texture\""),
            Map.entry("tex_draw_rect", "script it: om.canvas.rect(x,y,w,h,color) via run_python_script"),
            Map.entry("tex_draw_line", "script it: om.canvas.line(x0,y0,x1,y1,color) via run_python_script"),
            Map.entry("tex_flood_fill", "script it: om.canvas.flood(x,y,color) via run_python_script"),
            Map.entry("tex_apply_noise", "script it: om.canvas.noise(generator, ...) via run_python_script"),
            Map.entry("tex_add_layer", "script it: om.canvas.add_layer(name) via run_python_script"),
            Map.entry("tex_remove_layer", "script it: om.canvas.remove_layer(i) via run_python_script"),
            Map.entry("tex_set_layer", "script it: om.canvas.set_layer(i, active=/visible=/name=/opacity=) via run_python_script"),
            Map.entry("model_face_get_info", "use model_face_list_textures with face_ids + detail:true"),
            Map.entry("model_face_get_pixel", "use model_face_get_region with w=h=1"),
            Map.entry("model_face_set_pixel", "use model_face_set_pixels with one entry"),
            Map.entry("model_face_clear", "use model_face_fill with color [0,0,0,0]"),
            Map.entry("model_face_fill_rect", "merged into model_face_fill (rect field)"),
            Map.entry("model_face_create_texture", "use model_face_create_textures (works for one face)"),
            Map.entry("model_face_open", "sessions retired — model_face_set_pixels/model_face_fill apply directly; multi-step painting: om.tex via run_python_script"),
            Map.entry("model_face_commit", "sessions retired — edits apply immediately, one undo step per call"),
            Map.entry("model_face_discard", "sessions retired — edits apply immediately; use undo (model domain) to revert"),
            Map.entry("model_face_list_sessions", "sessions retired — there is no session state"),
            Map.entry("model_face_draw_rect", "script it: om.tex.of(part, face).rect(x,y,w,h,color) via run_python_script"),
            Map.entry("model_face_draw_line", "script it: om.tex.of(part, face).line(x0,y0,x1,y1,color) via run_python_script"),
            Map.entry("model_face_flood_fill", "script it: om.tex.of(part, face).flood(x,y,color) via run_python_script"),
            Map.entry("bone_get_skeleton_info", "use model_summary"),
            Map.entry("bone_get_selected", "use model_summary"),
            Map.entry("bone_get_head_world", "use bone_get (includes world head/tail)"),
            Map.entry("bone_get_tail_world", "use bone_get (includes world head/tail)"),
            Map.entry("bone_undo", "use undo with domain:\"bone\""),
            Map.entry("bone_redo", "use redo with domain:\"bone\""),
            Map.entry("attach_get_info", "use model_summary"),
            Map.entry("attach_get_selected", "use model_summary"),
            Map.entry("attach_undo", "use undo with domain:\"attach\""),
            Map.entry("attach_redo", "use redo with domain:\"attach\""),
            Map.entry("anim_get_keyframe", "use anim_list_keyframes"),
            Map.entry("anim_set_clip_name", "merged into anim_set_clip"),
            Map.entry("anim_set_clip_fps", "merged into anim_set_clip"),
            Map.entry("anim_set_clip_duration", "merged into anim_set_clip"),
            Map.entry("anim_set_clip_loop", "merged into anim_set_clip"),
            Map.entry("anim_set_layer_type", "merged into anim_set_layer"),
            Map.entry("anim_set_layer_mask", "merged into anim_set_layer"),
            Map.entry("anim_set_layer_fades", "merged into anim_set_layer"),
            Map.entry("anim_set_layer_priority", "merged into anim_set_layer"),
            Map.entry("anim_play", "use anim_transport with action:\"play\""),
            Map.entry("anim_pause", "use anim_transport with action:\"pause\""),
            Map.entry("anim_stop", "use anim_transport with action:\"stop\""),
            Map.entry("anim_set_playhead", "use anim_transport with action:\"seek\" + time"),
            Map.entry("anim_insert_keyframe_at_playhead", "use anim_insert_keyframe without time"),
            Map.entry("anim_save_as", "use anim_save with file_path"),
            Map.entry("anim_undo", "use undo with domain:\"anim\""),
            Map.entry("anim_redo", "use redo with domain:\"anim\""));

    private final McpToolRegistry registry;
    private final ObjectMapper mapper;

    public McpRequestRouter(McpToolRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    public McpJsonRpc.Response handle(McpJsonRpc.Request request) {
        if (request == null || !"2.0".equals(request.jsonrpc())) {
            return McpJsonRpc.Response.fail(request != null ? request.id() : null,
                    McpJsonRpc.INVALID_REQUEST, "Invalid JSON-RPC envelope");
        }

        String method = request.method();
        if (method == null) {
            return McpJsonRpc.Response.fail(request.id(),
                    McpJsonRpc.INVALID_REQUEST, "Missing method");
        }

        try {
            return switch (method) {
                case "initialize" -> McpJsonRpc.Response.ok(request.id(), buildInitializeResult());
                case "tools/list" -> McpJsonRpc.Response.ok(request.id(), buildToolsList());
                case "tools/call" -> McpJsonRpc.Response.ok(request.id(), invokeTool(request.params()));
                case "ping" -> McpJsonRpc.Response.ok(request.id(), Map.of());
                case "notifications/initialized" -> null; // notification: no response
                default -> McpJsonRpc.Response.fail(request.id(),
                        McpJsonRpc.METHOD_NOT_FOUND, "Method not found: " + method);
            };
        } catch (IllegalArgumentException e) {
            return McpJsonRpc.Response.fail(request.id(), McpJsonRpc.INVALID_PARAMS, e.getMessage());
        } catch (Exception e) {
            logger.error("MCP method '{}' failed", method, e);
            return McpJsonRpc.Response.fail(request.id(),
                    McpJsonRpc.INTERNAL_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Map<String, Object> buildInitializeResult() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));

        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);
        result.put("instructions", INSTRUCTIONS);
        return result;
    }

    private Map<String, Object> buildToolsList() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (McpTool tool : registry.all()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", tool.name());
            entry.put("description", tool.description());
            entry.put("inputSchema", tool.inputSchema());
            entries.add(entry);
        }
        return Map.of("tools", entries);
    }

    private Map<String, Object> invokeTool(JsonNode params) throws Exception {
        if (params == null || !params.isObject()) {
            throw new IllegalArgumentException("tools/call requires a params object");
        }
        String name = params.path("name").asText(null);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tools/call requires a 'name' string");
        }
        McpTool tool = registry.get(name);
        if (tool == null) {
            String hint = RETIRED_TOOLS.get(name);
            throw new IllegalArgumentException(hint != null
                    ? "Tool '" + name + "' was retired: " + hint
                    : "Unknown tool: " + name);
        }
        JsonNode arguments = params.has("arguments") ? params.get("arguments")
                : mapper.createObjectNode();

        Object result;
        try {
            result = tool.handler().call(arguments);
        } catch (IllegalArgumentException e) {
            return errorContent(e.getMessage());
        } catch (Exception e) {
            logger.warn("Tool '{}' threw", name, e);
            return errorContent(e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        ObjectNode content;
        if (result instanceof McpImageContent img) {
            content = mapper.createObjectNode();
            content.put("type", "image");
            content.put("data", img.base64Data());
            content.put("mimeType", img.mimeType());
        } else if (result instanceof String s) {
            // Raw text (markdown guides etc.) — no JSON quoting/escaping overhead.
            content = mapper.createObjectNode();
            content.put("type", "text");
            content.put("text", s);
        } else {
            content = mapper.createObjectNode();
            content.put("type", "text");
            content.put("text", mapper.writeValueAsString(result));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", List.of(content));
        response.put("isError", false);
        return response;
    }

    private Map<String, Object> errorContent(String message) {
        ObjectNode text = mapper.createObjectNode();
        text.put("type", "text");
        text.put("text", message);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", List.of(text));
        response.put("isError", true);
        return response;
    }
}
