package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static com.openmason.main.systems.mcp.McpArgs.optFloatBoxed;
import static com.openmason.main.systems.mcp.McpArgs.optString;
import static com.openmason.main.systems.mcp.McpArgs.optStringList;
import static com.openmason.main.systems.mcp.McpArgs.optVec3;
import static com.openmason.main.systems.mcp.McpArgs.reqFloat;
import static com.openmason.main.systems.mcp.McpArgs.reqString;

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
                        + "track count, dirty flag, playback state, playhead, and layer metadata — "
                        + "BASE/OVERLAY type, part-name mask, fade in/out, priority).",
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

        // ---------- Mutate: clip + layer metadata ----------

        registry.register(new McpTool(
                "anim_set_clip",
                "Update clip metadata: any of name, fps (>= 1), duration (seconds, >= 0.05), "
                        + "loop. Omitted fields keep their value; pass at least one.",
                schema()
                        .str("name", "New clip name")
                        .num("fps", "Frames per second")
                        .num("duration", "Duration in seconds")
                        .bool("loop", "true to loop, false for one-shot")
                        .build(),
                args -> {
                    Object last = null;
                    String name = optString(args, "name");
                    if (name != null) last = editor.setClipName(name);
                    Float fps = optFloatBoxed(args, "fps");
                    if (fps != null) last = editor.setClipFps(fps);
                    Float duration = optFloatBoxed(args, "duration");
                    if (duration != null) last = editor.setClipDuration(duration);
                    JsonNode loop = args.get("loop");
                    if (loop != null && loop.isBoolean()) last = editor.setClipLoop(loop.asBoolean());
                    if (last == null) {
                        throw new IllegalArgumentException("pass at least one of name, fps, duration, loop");
                    }
                    return last;
                }));

        registry.register(new McpTool(
                "anim_set_layer",
                "Update the clip's mixing-layer metadata: type (BASE drives the whole model, "
                        + "OVERLAY plays on top owning only its masked parts), mask_parts (part names; "
                        + "[] = all), fade_in/fade_out seconds, priority (higher overlay wins). Omitted "
                        + "fields keep their value; pass at least one.",
                schema()
                        .enumStr("type", "Layer type", "BASE", "OVERLAY")
                        .strArray("mask_parts", "Part names the overlay owns; [] = all parts")
                        .num("fade_in_seconds", "Blend-in duration")
                        .num("fade_out_seconds", "Blend-out duration")
                        .num("priority", "Integer priority (higher wins)")
                        .build(),
                args -> {
                    Object last = null;
                    String type = optString(args, "type");
                    if (type != null) last = editor.setLayerType(type);
                    if (args.has("mask_parts")) last = editor.setLayerMask(optStringList(args, "mask_parts"));
                    Float fadeIn = optFloatBoxed(args, "fade_in_seconds");
                    Float fadeOut = optFloatBoxed(args, "fade_out_seconds");
                    if (fadeIn != null || fadeOut != null) last = editor.setLayerFades(fadeIn, fadeOut);
                    Float priority = optFloatBoxed(args, "priority");
                    if (priority != null) last = editor.setLayerPriority(priority.intValue());
                    if (last == null) {
                        throw new IllegalArgumentException(
                                "pass at least one of type, mask_parts, fade_in_seconds, fade_out_seconds, priority");
                    }
                    return last;
                }));

        // ---------- Mutate: transport ----------

        registry.register(new McpTool(
                "anim_transport",
                "Control playback: action=play (resume from playhead), pause, stop (reset to 0), "
                        + "or seek (move the playhead to 'time' seconds and apply the pose).",
                schema()
                        .enumStr("action", "Transport action", "play", "pause", "stop", "seek")
                        .num("time", "Playhead time in seconds (required for seek)")
                        .required("action")
                        .build(),
                args -> switch (reqString(args, "action")) {
                    case "play" -> editor.play();
                    case "pause" -> editor.pause();
                    case "stop" -> editor.stop();
                    case "seek" -> editor.setPlayhead(reqFloat(args, "time"));
                    default -> throw new IllegalArgumentException(
                            "unknown action — valid: play, pause, stop, seek");
                }));

        registry.register(new McpTool(
                "anim_apply_pose",
                "Re-apply the clip's sampled pose at the current playhead to the model. "
                        + "Useful after part edits invalidate the live preview.",
                schema().build(),
                args -> editor.applyPoseAtPlayhead()));

        // ---------- Mutate: keyframes ----------

        registry.register(new McpTool(
                "anim_insert_keyframe",
                "Insert (or upsert) a keyframe on the part's track. time omitted = the current "
                        + "playhead. Any of position/rotation/scale may be omitted — omitted components "
                        + "come from the part's current local transform. Rotation is Euler degrees. "
                        + "Easing defaults to LINEAR.",
                schema()
                        .str("part_id_or_name", "Part id or name")
                        .num("time", "Keyframe time in seconds (omit for the playhead)")
                        .vec3("position", "Position [x,y,z]")
                        .vec3("rotation", "Euler degrees [x,y,z]")
                        .vec3("scale", "Scale [x,y,z]")
                        .str("easing", "Easing curve: LINEAR, EASE_IN, EASE_OUT, or EASE_IN_OUT (default LINEAR)")
                        .required("part_id_or_name")
                        .build(),
                args -> {
                    String part = reqString(args, "part_id_or_name");
                    Float time = optFloatBoxed(args, "time");
                    if (time == null) {
                        if (args.has("position") || args.has("rotation")
                                || args.has("scale") || args.has("easing")) {
                            throw new IllegalArgumentException(
                                    "pass time when supplying a pose; omitting time captures the "
                                            + "part's current transform at the playhead");
                        }
                        return editor.insertKeyframeAtPlayhead(part).orElse(null);
                    }
                    return editor.insertKeyframe(part, time,
                            optVec3(args, "position"),
                            optVec3(args, "rotation"),
                            optVec3(args, "scale"),
                            optString(args, "easing")).orElse(null);
                }));

        registry.register(new McpTool(
                "anim_edit_keyframe",
                "Edit the keyframe at the given index. Any of time/position/rotation/scale/easing may be "
                        + "omitted to keep its current value.",
                schema()
                        .str("part_id_or_name", "Part id or name")
                        .num("index", "Keyframe index from anim_list_keyframes")
                        .num("time", "New time in seconds (optional)")
                        .vec3("position", "Position [x,y,z]")
                        .vec3("rotation", "Euler degrees [x,y,z]")
                        .vec3("scale", "Scale [x,y,z]")
                        .str("easing", "Easing curve")
                        .required("part_id_or_name", "index")
                        .build(),
                args -> editor.editKeyframe(
                        reqString(args, "part_id_or_name"),
                        (int) reqFloat(args, "index"),
                        optFloatBoxed(args, "time"),
                        optVec3(args, "position"),
                        optVec3(args, "rotation"),
                        optVec3(args, "scale"),
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

        // ---------- File I/O ----------

        registry.register(new McpTool(
                "anim_new_clip",
                "Discard the current clip and start a blank one.",
                schema().build(),
                args -> editor.newClip()));

        registry.register(new McpTool(
                "anim_save",
                "Save the current clip: to file_path when given, else to its existing path "
                        + "(false if the clip has no path yet).",
                schema().str("file_path", "Absolute file path (typically ending in .oma)").build(),
                args -> {
                    String path = optString(args, "file_path");
                    return path != null ? editor.saveAs(path) : editor.save();
                }));

        registry.register(new McpTool(
                "anim_load",
                "Load a clip from the given absolute file path, replacing the current clip.",
                schema().str("file_path", "Absolute file path of a .oma clip")
                        .required("file_path").build(),
                args -> editor.load(reqString(args, "file_path"))));
    }

    // ===================== Schema helpers =====================

    private McpSchema schema() {
        return McpSchema.of(mapper);
    }

    private JsonNode partSchema() {
        return schema().str("part_id_or_name", "Part id or name").required("part_id_or_name").build();
    }
}
