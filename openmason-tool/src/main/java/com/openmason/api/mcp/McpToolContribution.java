package com.openmason.api.mcp;

import java.util.Objects;

/**
 * A group of MCP tools a plugin adds to the editor's MCP server (and the in-tool assistant, which
 * shares the registry). Contributors run each time the tool registry is built, in contribution
 * order, so {@code tools/list} lists a plugin's tools after the tools of the plugins it requires.
 *
 * @param id          unique id of the group (also names its tool-surface snapshot in tests)
 * @param contributor registers the tools
 */
public record McpToolContribution(String id, McpToolContributor contributor) {

    public McpToolContribution {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(contributor, "contributor");
    }
}
