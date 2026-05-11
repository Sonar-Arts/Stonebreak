package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.joml.Vector3f;

/**
 * Wires the {@link AnimationEditingService} surface as MCP tools for the
 * Open Mason animation editor (clip metadata, track/keyframe edits, transport,
 * undo/redo, .oma file I/O).
 *
 * <p>Names are snake_case and prefixed with {@code anim_} so they don't
 * collide with the model- or texture-editing toolsets.
 */
public final class AnimationToolDefinitions {

    private final AnimationEditingService editor;
    private final ObjectMapper mapper;

    public AnimationToolDefinitions(AnimationEditingService editor, ObjectMapper mapper) {
        this.editor = editor;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        // ---------- Read ----------

        registry.register(new McpTool(
                "anim_get_info",
                "Get the current animation clip's metadata (name, fps, duration, loop, modelRef, file path, "
                        + "track count, dirty flag, playback state, playhead).",
                schema().build(),
                args -> editor.getAnimationInfo()));

        registry.register(new McpTool(
                "anim_list_tracks",
                "List every per-part track in the current clip with part id, resolved part name, and keyframe count.",
                schema().build(),
                args -> editor.listTracks()));

        registry.register(new McpTool(
                "anim_list_keyframes",
                "List every keyframe on the given part's track with time, pose, and easing.",
                partSchema(),
                args -> editor.listKeyframes(reqString(args, "part_id_or_name")).orElse(null)));

        registry.register(new McpTool(
                "anim_get_keyframe",
                "Get a single keyframe on the given part's track by index.",
                schema()
                        .str("part_id_or_name", "Part id or name")
                        .num("index", "Keyframe index from anim_list_keyframes")
                        .required("part_id_or_name", "index")
                        .build(),
                args -> editor.getKeyframe(
                        reqString(args, "part_id_or_name"),
                        (int) reqFloat(args, "index")).orElse(null)));

        // ---------- Mutate: clip metadata ----------

        registry.register(new McpTool(
                "anim_set_clip_name",
                "Set the current clip's display name.",
                schema().str("name", "New clip name").required("name").build(),
                args -> editor.setClipName(reqString(args, "name"))));

        registry.register(new McpTool(
                "anim_set_clip_fps",
                "Set the clip's playback fps (>= 1).",
                schema().num("fps", "Frames per second").required("fps").build(),
                args -> editor.setClipFps(reqFloat(args, "fps"))));

        registry.register(new McpTool(
                "anim_set_clip_duration",
                "Set the clip's total duration in seconds (>= 0.05).",
                schema().num("duration", "Duration in seconds").required("duration").build(),
                args -> editor.setClipDuration(reqFloat(args, "duration"))));

        registry.register(new McpTool(
                "anim_set_clip_loop",
                "Set whether the clip loops on playback.",
                schema().bool("loop", "true to loop, false for one-shot").required("loop").build(),
                args -> editor.setClipLoop(args.get("loop").asBoolean())));

        // ---------- Mutate: transport ----------

        registry.register(new McpTool(
                "anim_set_playhead",
                "Move the playhead to a specific time in seconds and apply the resulting pose to the model.",
                schema().num("time", "Time in seconds").required("time").build(),
                args -> editor.setPlayhead(reqFloat(args, "time"))));

        registry.register(new McpTool(
                "anim_play",
                "Begin (or resume) playback from the current playhead position.",
                schema().build(),
                args -> editor.play()));

        registry.register(new McpTool(
                "anim_pause",
                "Pause playback, leaving the playhead in place.",
                schema().build(),
                args -> editor.pause()));

        registry.register(new McpTool(
                "anim_stop",
                "Stop playback and reset the playhead to 0.",
                schema().build(),
                args -> editor.stop()));

        registry.register(new McpTool(
                "anim_apply_pose",
                "Re-apply the clip's sampled pose at the current playhead to the model. "
                        + "Useful after part edits invalidate the live preview.",
                schema().build(),
                args -> editor.applyPoseAtPlayhead()));

        // ---------- Mutate: keyframes ----------

        registry.register(new McpTool(
                "anim_insert_keyframe_at_playhead",
                "Capture the part's current transform as a keyframe at the current playhead time.",
                partSchema(),
                args -> editor.insertKeyframeAtPlayhead(reqString(args, "part_id_or_name")).orElse(null)));

        registry.register(new McpTool(
                "anim_insert_keyframe",
                "Insert (or upsert) a keyframe on the part's track at the given time with the supplied pose. "
                        + "Any of position/rotation/scale may be omitted — omitted axes are taken from the part's "
                        + "current local transform. Rotation is Euler degrees. Easing defaults to LINEAR.",
                schema()
                        .str("part_id_or_name", "Part id or name")
                        .num("time", "Keyframe time in seconds")
                        .num("position_x", "Position X").num("position_y", "Position Y").num("position_z", "Position Z")
                        .num("rotation_x", "Rotation X (degrees)").num("rotation_y", "Rotation Y (degrees)").num("rotation_z", "Rotation Z (degrees)")
                        .num("scale_x", "Scale X").num("scale_y", "Scale Y").num("scale_z", "Scale Z")
                        .str("easing", "Easing curve (LINEAR; reserved for future curves)")
                        .required("part_id_or_name", "time")
                        .build(),
                args -> editor.insertKeyframe(
                        reqString(args, "part_id_or_name"),
                        reqFloat(args, "time"),
                        optVec3(args, "position_x", "position_y", "position_z"),
                        optVec3(args, "rotation_x", "rotation_y", "rotation_z"),
                        optVec3(args, "scale_x", "scale_y", "scale_z"),
                        optString(args, "easing")).orElse(null)));

        registry.register(new McpTool(
                "anim_edit_keyframe",
                "Edit the keyframe at the given index. Any of time/position/rotation/scale/easing may be "
                        + "omitted to keep its current value.",
                schema()
                        .str("part_id_or_name", "Part id or name")
                        .num("index", "Keyframe index from anim_list_keyframes")
                        .num("time", "New time in seconds (optional)")
                        .num("position_x", "Position X").num("position_y", "Position Y").num("position_z", "Position Z")
                        .num("rotation_x", "Rotation X (degrees)").num("rotation_y", "Rotation Y (degrees)").num("rotation_z", "Rotation Z (degrees)")
                        .num("scale_x", "Scale X").num("scale_y", "Scale Y").num("scale_z", "Scale Z")
                        .str("easing", "Easing curve")
                        .required("part_id_or_name", "index")
                        .build(),
                args -> editor.editKeyframe(
                        reqString(args, "part_id_or_name"),
                        (int) reqFloat(args, "index"),
                        optFloatBoxed(args, "time"),
                        optVec3(args, "position_x", "position_y", "position_z"),
                        optVec3(args, "rotation_x", "rotation_y", "rotation_z"),
                        optVec3(args, "scale_x", "scale_y", "scale_z"),
                        optString(args, "easing")).orElse(null)));

        registry.register(new McpTool(
                "anim_delete_keyframe",
                "Delete the keyframe at the given index on the part's track.",
                schema()
                        .str("part_id_or_name", "Part id or name")
                        .num("index", "Keyframe index from anim_list_keyframes")
                        .required("part_id_or_name", "index")
                        .build(),
                args -> editor.deleteKeyframe(
                        reqString(args, "part_id_or_name"),
                        (int) reqFloat(args, "index"))));

        registry.register(new McpTool(
                "anim_delete_track",
                "Remove the entire track (every keyframe) for a given part.",
                partSchema(),
                args -> editor.deleteTrack(reqString(args, "part_id_or_name"))));

        // ---------- Undo / Redo ----------

        registry.register(new McpTool(
                "anim_undo",
                "Undo the most recent animation-editor mutation.",
                schema().build(),
                args -> editor.undo()));

        registry.register(new McpTool(
                "anim_redo",
                "Redo the most recently undone animation-editor mutation.",
                schema().build(),
                args -> editor.redo()));

        // ---------- File I/O ----------

        registry.register(new McpTool(
                "anim_new_clip",
                "Discard the current clip and start a blank one.",
                schema().build(),
                args -> editor.newClip()));

        registry.register(new McpTool(
                "anim_save",
                "Save the current clip to its existing file path. Returns false if the clip has no path yet.",
                schema().build(),
                args -> editor.save()));

        registry.register(new McpTool(
                "anim_save_as",
                "Save the current clip to the given absolute file path.",
                schema().str("file_path", "Absolute file path (typically ending in .oma)")
                        .required("file_path").build(),
                args -> editor.saveAs(reqString(args, "file_path"))));

        registry.register(new McpTool(
                "anim_load",
                "Load a clip from the given absolute file path, replacing the current clip.",
                schema().str("file_path", "Absolute file path of a .oma clip")
                        .required("file_path").build(),
                args -> editor.load(reqString(args, "file_path"))));
    }

    // ===================== Schema helpers =====================

    private SchemaBuilder schema() {
        return new SchemaBuilder(mapper);
    }

    private JsonNode partSchema() {
        return schema().str("part_id_or_name", "Part id or name").required("part_id_or_name").build();
    }

    private static final class SchemaBuilder {
        private final ObjectMapper mapper;
        private final ObjectNode root;
        private final ObjectNode properties;
        private final ArrayNode required;

        SchemaBuilder(ObjectMapper mapper) {
            this.mapper = mapper;
            this.root = mapper.createObjectNode();
            this.properties = mapper.createObjectNode();
            this.required = mapper.createArrayNode();
            root.put("type", "object");
            root.set("properties", properties);
        }

        SchemaBuilder str(String name, String description) { return prop(name, "string", description); }
        SchemaBuilder num(String name, String description) { return prop(name, "number", description); }
        SchemaBuilder bool(String name, String description) { return prop(name, "boolean", description); }

        SchemaBuilder prop(String name, String type, String description) {
            ObjectNode def = mapper.createObjectNode();
            def.put("type", type);
            def.put("description", description);
            properties.set(name, def);
            return this;
        }

        SchemaBuilder required(String... names) {
            for (String n : names) required.add(n);
            return this;
        }

        JsonNode build() {
            if (!required.isEmpty()) root.set("required", required);
            return root;
        }
    }

    // ===================== Arg parsing =====================

    private static String reqString(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull() || !n.isTextual() || n.asText().isBlank()) {
            throw new IllegalArgumentException("Missing required string argument: " + key);
        }
        return n.asText();
    }

    private static String optString(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || n.isNull() || !n.isTextual()) return null;
        String s = n.asText();
        return s.isBlank() ? null : s;
    }

    private static float reqFloat(JsonNode args, String key) {
        JsonNode n = args.get(key);
        if (n == null || !n.isNumber()) {
            throw new IllegalArgumentException("Missing required numeric argument: " + key);
        }
        return n.floatValue();
    }

    private static float optFloat(JsonNode args, String key, float fallback) {
        JsonNode n = args.get(key);
        return (n == null || n.isNull() || !n.isNumber()) ? fallback : n.floatValue();
    }

    private static Float optFloatBoxed(JsonNode args, String key) {
        JsonNode n = args.get(key);
        return (n == null || n.isNull() || !n.isNumber()) ? null : n.floatValue();
    }

    private static Vector3f optVec3(JsonNode args, String xKey, String yKey, String zKey) {
        boolean any = args.has(xKey) || args.has(yKey) || args.has(zKey);
        if (!any) return null;
        return new Vector3f(
                optFloat(args, xKey, 0f),
                optFloat(args, yKey, 0f),
                optFloat(args, zKey, 0f));
    }
}
