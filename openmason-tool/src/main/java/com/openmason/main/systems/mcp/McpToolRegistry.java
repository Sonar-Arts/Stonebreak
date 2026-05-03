package com.openmason.main.systems.mcp;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry of MCP tools, keyed by tool name. Insertion-ordered for stable listings.
 */
public final class McpToolRegistry {

    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    public void register(McpTool tool) {
        if (tools.putIfAbsent(tool.name(), tool) != null) {
            throw new IllegalStateException("Duplicate MCP tool name: " + tool.name());
        }
    }

    public Collection<McpTool> all() {
        return tools.values();
    }

    public McpTool get(String name) {
        return tools.get(name);
    }
}
