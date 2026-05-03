package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A single MCP tool: name, description, JSON-schema input definition, and handler.
 */
public record McpTool(String name, String description, JsonNode inputSchema, Handler handler) {

    @FunctionalInterface
    public interface Handler {
        /**
         * Execute the tool with the JSON-decoded {@code arguments} object.
         *
         * @return any JSON-serializable value (Map/List/record/primitive); the server
         *         wraps it as MCP content. Throw to signal an error to the caller.
         */
        Object call(JsonNode arguments) throws Exception;
    }
}
