package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.mcp.AssetExportService.Kind;

import static com.openmason.main.systems.mcp.McpArgs.optBool;
import static com.openmason.main.systems.mcp.McpArgs.optInt;
import static com.openmason.main.systems.mcp.McpArgs.optString;
import static com.openmason.main.systems.mcp.McpArgs.reqString;

/** {@code sbo_export} / {@code sbe_export} and the {@code sbo_editor_*} / {@code sbe_editor_*} tools. */
public final class AssetExportToolDefinitions {

    private final AssetExportService service;
    private final ObjectMapper mapper;

    public AssetExportToolDefinitions(AssetExportService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        registry.register(new McpTool(
                "sbo_export",
                "Export the model editor's model as a new .sbo. Saves the .omo first if needed. "
                        + "params shape: describe_api topic=saving (objectId/objectName/objectType/"
                        + "gameProperties/states/sounds/drops; missing fields get the export window's "
                        + "defaults). Target defaults to game:sbo/<type>/ and asks the user; the result "
                        + "opens in the SBO editor.",
                exportSchema("Target .sbo path").build(),
                args -> service.exportSbo(params(args), optString(args, "file_path"),
                        optBool(args, "prompt", false), optBool(args, "overwrite", false))));

        registry.register(new McpTool(
                "sbe_export",
                "Export the model editor's model as a new .sbe. Saves the .omo first if needed. "
                        + "params shape: describe_api topic=saving (objectId/objectName/entityType/"
                        + "states/variants/sounds). Target defaults to game:sbe/Mobs/ and asks the user; "
                        + "the result opens in the SBE editor.",
                exportSchema("Target .sbe path").build(),
                args -> service.exportSbe(params(args), optString(args, "file_path"),
                        optBool(args, "prompt", false), optBool(args, "overwrite", false))));

        for (Kind kind : Kind.values()) {
            String k = kind.name().toLowerCase();
            String ext = "." + k;
            registry.register(new McpTool(
                    k + "_editor_open",
                    "Load a " + ext + " into the " + kind + " editor window (visible to the user). asset: "
                            + "objectId from asset_list, or a path inside a root. Unsaved editor changes "
                            + "trigger the approval dialog.",
                    McpSchema.of(mapper)
                            .str("asset", "objectId or " + ext + " path").required("asset")
                            .intg("timeout_seconds", "Approval wait (default 60, max 240)")
                            .build(),
                    args -> service.editorOpen(kind, reqString(args, "asset"), optInt(args, "timeout_seconds", 0))));

            registry.register(new McpTool(
                    k + "_editor_get",
                    "The " + kind + " editor's draft manifest as JSON (metadata, "
                            + (kind == Kind.SBO ? "gameProperties, states, sounds, drops, fuel, recipe counts"
                            : "states, variants, sounds") + "), plus path and dirty flag.",
                    McpSchema.of(mapper).build(),
                    args -> service.editorGet(kind)));

            registry.register(new McpTool(
                    k + "_editor_set",
                    "Patch the " + kind + " editor's draft with a partial object of manifest fields "
                            + "(see describe_api topic=saving). States and embedded bytes are untouched; "
                            + "the window refreshes and turns dirty.",
                    McpSchema.of(mapper)
                            .obj("patch", "Partial manifest fields").required("patch")
                            .build(),
                    args -> service.editorSet(kind, args.get("patch"))));

            registry.register(new McpTool(
                    k + "_editor_save",
                    "Validate and write the " + kind + " editor's draft. Without file_path: its own file "
                            + "(a shipped asset under game resources always asks the user). Returns a "
                            + "structured outcome.",
                    McpSchema.of(mapper)
                            .str("file_path", "Target " + ext + " path (see save_targets)")
                            .bool("prompt", "Always ask the user in the in-app Save Sheet")
                            .bool("overwrite", "Acknowledge replacing an existing file")
                            .build(),
                    args -> service.editorSave(kind, optString(args, "file_path"),
                            optBool(args, "prompt", false), optBool(args, "overwrite", false))));
        }
    }

    private McpSchema exportSchema(String pathDescription) {
        return McpSchema.of(mapper)
                .obj("params", "Export parameters (see description)")
                .str("file_path", pathDescription)
                .bool("prompt", "Always ask the user in the in-app Save Sheet")
                .bool("overwrite", "Acknowledge replacing an existing file");
    }

    private static JsonNode params(JsonNode args) {
        JsonNode p = args.get("params");
        return p == null || p.isNull() ? args : p;
    }
}
