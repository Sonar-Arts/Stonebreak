package com.openmason.main.systems.scripting.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.mcp.McpSchema;
import com.openmason.main.systems.mcp.McpTool;
import com.openmason.main.systems.mcp.McpToolRegistry;

import static com.openmason.main.systems.mcp.McpArgs.optBool;
import static com.openmason.main.systems.mcp.McpArgs.optString;
import static com.openmason.main.systems.mcp.McpArgs.reqString;

/** Registrations for the script library + Scripting window tools. */
public final class ScriptLibraryToolDefinitions {

    private final ScriptLibraryService service;
    private final ObjectMapper mapper;

    public ScriptLibraryToolDefinitions(ScriptLibraryService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        registry.register(new McpTool(
                "script_list",
                "List saved scripts (~/.openmason/scripts/) and the bundled read-only examples.",
                McpSchema.of(mapper).build(),
                args -> service.list()));

        registry.register(new McpTool(
                "script_read",
                "Read a saved script or bundled example by name.",
                nameSchema().build(),
                args -> service.read(reqString(args, "name"))));

        registry.register(new McpTool(
                "script_save",
                "Save a python/JSON-ops script into the user library for reuse "
                        + "(.py forced when no extension; run it with run_python_script/run_model_ops).",
                nameSchema()
                        .str("source", "Script source text")
                        .bool("overwrite", "Replace an existing script (default false)")
                        .required("name", "source")
                        .build(),
                args -> service.save(reqString(args, "name"), reqString(args, "source"),
                        optBool(args, "overwrite", false))));

        registry.register(new McpTool(
                "script_delete",
                "Delete a user script (bundled examples are read-only).",
                nameSchema().build(),
                args -> service.delete(reqString(args, "name"))));

        registry.register(new McpTool(
                "scripting_open_window",
                "Open the Scripting window in the tool UI, optionally pre-loading a saved script.",
                McpSchema.of(mapper)
                        .str("script", "Script name to load into the editor (optional)")
                        .build(),
                args -> service.openWindow(optString(args, "script"))));
    }

    private McpSchema nameSchema() {
        return McpSchema.of(mapper)
                .str("name", "Script file name, e.g. 'my_edit.py' or 'ops.json'")
                .required("name");
    }
}
