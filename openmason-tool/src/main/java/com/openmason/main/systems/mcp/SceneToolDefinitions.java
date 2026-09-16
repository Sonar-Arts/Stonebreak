package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import static com.openmason.main.systems.mcp.McpArgs.optString;
import static com.openmason.main.systems.mcp.McpArgs.optStringList;
import static com.openmason.main.systems.mcp.McpArgs.optVec3;
import static com.openmason.main.systems.mcp.McpArgs.reqString;

/**
 * Wires the {@link SceneEditingService} surface as MCP tools for the Scene Viewer:
 * inspect the open .omsc scene, place / transform / rename / show / lock / delete /
 * duplicate instances, drive the selection and camera, and new / open / save the scene.
 *
 * <p>Names are snake_case and prefixed with {@code scene_}. Instances are addressed by
 * id or by name (a name must be unique to be accepted).
 */
public final class SceneToolDefinitions {

    private final SceneEditingService scene;
    private final ObjectMapper mapper;

    public SceneToolDefinitions(SceneEditingService scene, ObjectMapper mapper) {
        this.scene = scene;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        // ---------- Read ----------

        registry.register(new McpTool(
                "scene_info",
                "Describe the open scene: name, file path, dirty flag, instance/model counts, orphaned "
                        + "placements (model missing), the current selection, and what undo/redo would do. "
                        + "Call this first before any scene edit.",
                schema().build(),
                args -> scene.getInfo()));

        registry.register(new McpTool(
                "scene_list",
                "List every placed instance with id, name, model, position, rotation (Euler degrees), "
                        + "scale, visible, locked and selected flags. Pass what=\"models\" to list the "
                        + "distinct models the scene references instead (id, source name, path, resolution "
                        + "status, instance count).",
                schema()
                        .enumStr("what", "\"instances\" (default) or \"models\"", "instances", "models")
                        .build(),
                args -> "models".equals(optString(args, "what")) ? scene.listModels() : scene.listInstances()));

        registry.register(new McpTool(
                "scene_get",
                "Get one placed instance by id or name.",
                idOrNameSchema(),
                args -> scene.getInstance(reqString(args, "id_or_name"))));

        // ---------- Mutate ----------

        registry.register(new McpTool(
                "scene_place",
                "Place a .omo model in the scene as a new instance. omo_path is absolute or relative to "
                        + "the project root. name defaults to the file name. position defaults to the origin; "
                        + "rotation is Euler degrees [x,y,z]; scale defaults to [1,1,1]. The new instance "
                        + "becomes the selection. Undoable.",
                schema()
                        .str("omo_path", "Path to the .omo model to place")
                        .str("name", "Instance name (defaults to the model file name)")
                        .vec3("position", "World position [x,y,z]")
                        .vec3("rotation", "Euler degrees [x,y,z]")
                        .vec3("scale", "Scale [x,y,z]")
                        .required("omo_path")
                        .build(),
                args -> scene.place(
                        reqString(args, "omo_path"),
                        optString(args, "name"),
                        optVec3(args, "position"),
                        optVec3(args, "rotation"),
                        optVec3(args, "scale"))));

        registry.register(new McpTool(
                "scene_set_transform",
                "Set an instance's position / rotation (Euler degrees) / scale. Omitted components are "
                        + "left unchanged. Refuses a locked instance. One undo step.",
                schema()
                        .str("id_or_name", "Instance id or unique name")
                        .vec3("position", "World position [x,y,z]")
                        .vec3("rotation", "Euler degrees [x,y,z]")
                        .vec3("scale", "Scale [x,y,z]")
                        .required("id_or_name")
                        .build(),
                args -> scene.setTransform(
                        reqString(args, "id_or_name"),
                        optVec3(args, "position"),
                        optVec3(args, "rotation"),
                        optVec3(args, "scale"))));

        registry.register(new McpTool(
                "scene_set_properties",
                "Rename, show/hide or lock/unlock an instance. Each supplied field is one undo step. "
                        + "A locked instance cannot be picked, dragged or deleted.",
                schema()
                        .str("id_or_name", "Instance id or unique name")
                        .str("name", "New display name")
                        .bool("visible", "Show (true) or hide (false)")
                        .bool("locked", "Lock (true) or unlock (false)")
                        .required("id_or_name")
                        .build(),
                args -> scene.setProperties(
                        reqString(args, "id_or_name"),
                        optString(args, "name"),
                        args.has("visible") && !args.get("visible").isNull() ? args.get("visible").asBoolean() : null,
                        args.has("locked") && !args.get("locked").isNull() ? args.get("locked").asBoolean() : null)));

        registry.register(new McpTool(
                "scene_delete",
                "Delete one or more instances (ids or unique names). Locked instances are skipped. "
                        + "One undo step restores them all.",
                idsSchema("Instance ids or names to delete"),
                args -> scene.delete(optStringList(args, "ids_or_names"))));

        registry.register(new McpTool(
                "scene_duplicate",
                "Duplicate one or more instances, offset by one unit on X and Z so the copies are "
                        + "visibly beside the originals. The copies become the selection. Returns them.",
                idsSchema("Instance ids or names to duplicate"),
                args -> scene.duplicate(optStringList(args, "ids_or_names"))));

        // ---------- Selection / camera ----------

        registry.register(new McpTool(
                "scene_select",
                "Replace the selection with the given instances (the first is the primary the gizmo "
                        + "drives). An empty list clears the selection. Returns the selected ids.",
                idsSchema("Instance ids or names to select; empty clears"),
                args -> scene.select(optStringList(args, "ids_or_names"))));

        registry.register(new McpTool(
                "scene_focus",
                "Frame the scene camera on an instance, or on the whole scene when id_or_name is omitted.",
                schema().str("id_or_name", "Instance id or unique name; omit to frame everything").build(),
                args -> scene.focus(optString(args, "id_or_name"))));

        // ---------- File ----------

        registry.register(new McpTool(
                "scene_new",
                "Start a new, empty, untitled scene (drops the current one — save first if it matters).",
                schema().str("name", "Scene name (default \"Untitled Scene\")").build(),
                args -> scene.newScene(optString(args, "name"))));

        registry.register(new McpTool(
                "scene_open",
                "Open a .omsc scene. path is absolute, project-relative, or a bare file name in the "
                        + "project's Scenes/ folder. Replaces the current scene.",
                schema().str("path", "Scene file to open").required("path").build(),
                args -> scene.open(reqString(args, "path"))));

        registry.register(new McpTool(
                "scene_save",
                "Save the scene. With path: Save As (absolute, project-relative, or a bare name in "
                        + "Scenes/). Without: save to its current file, or — for an untitled scene — into "
                        + "the project's Scenes/ folder under its name.",
                schema().str("path", "Optional Save As target").build(),
                args -> scene.save(optString(args, "path"))));

        registry.register(new McpTool(
                "scene_undo",
                "Undo the last scene edit (placement, transform, rename, visibility, lock, delete, duplicate).",
                schema().build(),
                args -> scene.undo()));

        registry.register(new McpTool(
                "scene_redo",
                "Redo the last undone scene edit.",
                schema().build(),
                args -> scene.redo()));
    }

    private McpSchema schema() {
        return McpSchema.of(mapper);
    }

    private com.fasterxml.jackson.databind.JsonNode idOrNameSchema() {
        return schema()
                .str("id_or_name", "Instance id or unique name")
                .required("id_or_name")
                .build();
    }

    private com.fasterxml.jackson.databind.JsonNode idsSchema(String description) {
        return schema()
                .strArray("ids_or_names", description)
                .required("ids_or_names")
                .build();
    }
}
