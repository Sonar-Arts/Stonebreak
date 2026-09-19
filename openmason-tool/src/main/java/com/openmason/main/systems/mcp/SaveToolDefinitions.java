package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.io.AssetWriteService;

/**
 * Cross-cutting save/prompt tools: where an agent may write, and whether a
 * dialog is currently waiting on the human.
 */
public final class SaveToolDefinitions {

    private final AssetWriteService writes;
    private final ObjectMapper mapper;

    public SaveToolDefinitions(AssetWriteService writes, ObjectMapper mapper) {
        this.writes = writes;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        registry.register(new McpTool(
                "save_targets",
                "Where agent writes may land: the writable roots (project / game resources / "
                        + "exports) with their sub-folders, the path grammar, per-kind defaults "
                        + "(omo, omt, omanim, omsc, png, sbo, sbe) and the current write policy. "
                        + "Read this before passing a file_path to any save/export tool.",
                McpSchema.of(mapper).build(),
                args -> writes.targets()));

        registry.register(new McpTool(
                "ui_prompt_status",
                "Whether an in-app dialog (Save Sheet or approval) is waiting on the user right "
                        + "now, with its title and seconds remaining — explain the wait instead of "
                        + "retrying blindly.",
                McpSchema.of(mapper).build(),
                args -> writes.promptStatus()));
    }
}
