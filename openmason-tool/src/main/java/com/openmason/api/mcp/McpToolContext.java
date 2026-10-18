package com.openmason.api.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.api.ServiceRegistry;
import com.openmason.main.systems.mcp.McpTool;
import com.openmason.main.systems.mcp.McpToolRegistry;
import com.openmason.main.systems.mcp.ToolCapabilities;

/**
 * What an {@link McpToolContributor} gets while the tool registry is built.
 */
public interface McpToolContext {

    /** The registry being built; tool names are unique across every contributor. */
    McpToolRegistry registry();

    /** Shorthand for {@code registry().register(tool)}. */
    default void register(McpTool tool) {
        registry().register(tool);
    }

    /** The JSON mapper the server uses (NON_NULL serialization). */
    ObjectMapper mapper();

    /** Optional capabilities detected at start-up (e.g. libalex). */
    ToolCapabilities capabilities();

    /**
     * The editor services (empty in headless tests), plus the MCP services shared by every
     * contributor of this build (write sandbox, domain editing services), published by the
     * built-in MCP contributors.
     */
    ServiceRegistry services();
}
