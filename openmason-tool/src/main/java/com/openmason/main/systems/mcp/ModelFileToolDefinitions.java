package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import static com.openmason.main.systems.mcp.McpArgs.optBool;
import static com.openmason.main.systems.mcp.McpArgs.optInt;
import static com.openmason.main.systems.mcp.McpArgs.optString;
import static com.openmason.main.systems.mcp.McpArgs.reqString;

/** {@code model_save} / {@code model_open} / {@code model_new}. */
public final class ModelFileToolDefinitions {

    private final ModelFileService files;
    private final ObjectMapper mapper;

    public ModelFileToolDefinitions(ModelFileService files, ObjectMapper mapper) {
        this.files = files;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        registry.register(new McpTool(
                "model_save",
                "Save the model editor's model as .omo. Without file_path: re-save its own file, "
                        + "or — for a new / imported-asset model — open the in-app Save Sheet. "
                        + "file_path: absolute, project:<rel> or a bare name (see save_targets); "
                        + "risky writes ask the user. Returns a structured outcome, never a protocol "
                        + "error when the user declines.",
                McpSchema.of(mapper)
                        .str("file_path", "Target .omo path")
                        .bool("prompt", "Always ask the user in the in-app Save Sheet")
                        .bool("overwrite", "Acknowledge replacing an existing file")
                        .build(),
                args -> files.save(optString(args, "file_path"), optBool(args, "prompt", false),
                        optBool(args, "overwrite", false))));

        registry.register(new McpTool(
                "model_open",
                "Open a .omo from a writable root (absolute, project:<rel>, game:<rel> or a bare "
                        + "name in the project) into the model editor. Unsaved changes trigger the "
                        + "in-tool approval dialog (the user may save first).",
                McpSchema.of(mapper)
                        .str("file_path", "The .omo to open").required("file_path")
                        .intg("timeout_seconds", "How long to wait for the user (default 60, max 240)")
                        .build(),
                args -> files.open(reqString(args, "file_path"), optInt(args, "timeout_seconds", 0))));

        registry.register(new McpTool(
                "model_new",
                "Start a new blank cube model in the editor. Unsaved changes trigger the in-tool "
                        + "approval dialog.",
                McpSchema.of(mapper)
                        .intg("timeout_seconds", "How long to wait for the user (default 60, max 240)")
                        .build(),
                args -> files.newModel(optInt(args, "timeout_seconds", 0))));
    }
}
