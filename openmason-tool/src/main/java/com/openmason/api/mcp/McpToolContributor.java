package com.openmason.api.mcp;

/**
 * Registers a group of MCP tools. Must only register (and build its services): the editor may be
 * absent (headless tool-surface tests pass empty services), so handlers resolve what they need at
 * call time and hop to the UI thread themselves.
 */
@FunctionalInterface
public interface McpToolContributor {

    void contribute(McpToolContext context);
}
