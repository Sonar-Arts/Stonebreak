package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes JSON-RPC method calls for the MCP protocol surface.
 *
 * <p>Implemented methods:
 * <ul>
 *   <li>{@code initialize} — handshake, declares server capabilities</li>
 *   <li>{@code tools/list} — returns registered tools with input schemas</li>
 *   <li>{@code tools/call} — invokes a tool by name with arguments</li>
 *   <li>{@code ping} — liveness check</li>
 *   <li>{@code notifications/initialized} — no-op acknowledgment</li>
 * </ul>
 */
public final class McpRequestRouter {

    private static final Logger logger = LoggerFactory.getLogger(McpRequestRouter.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "open-mason";
    private static final String SERVER_VERSION = "1.0";

    private final McpToolRegistry registry;
    private final ObjectMapper mapper;

    public McpRequestRouter(McpToolRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    public McpJsonRpc.Response handle(McpJsonRpc.Request request) {
        if (request == null || !"2.0".equals(request.jsonrpc())) {
            return McpJsonRpc.Response.fail(request != null ? request.id() : null,
                    McpJsonRpc.INVALID_REQUEST, "Invalid JSON-RPC envelope");
        }

        String method = request.method();
        if (method == null) {
            return McpJsonRpc.Response.fail(request.id(),
                    McpJsonRpc.INVALID_REQUEST, "Missing method");
        }

        try {
            return switch (method) {
                case "initialize" -> McpJsonRpc.Response.ok(request.id(), buildInitializeResult());
                case "tools/list" -> McpJsonRpc.Response.ok(request.id(), buildToolsList());
                case "tools/call" -> McpJsonRpc.Response.ok(request.id(), invokeTool(request.params()));
                case "ping" -> McpJsonRpc.Response.ok(request.id(), Map.of());
                case "notifications/initialized" -> null; // notification: no response
                default -> McpJsonRpc.Response.fail(request.id(),
                        McpJsonRpc.METHOD_NOT_FOUND, "Method not found: " + method);
            };
        } catch (IllegalArgumentException e) {
            return McpJsonRpc.Response.fail(request.id(), McpJsonRpc.INVALID_PARAMS, e.getMessage());
        } catch (Exception e) {
            logger.error("MCP method '{}' failed", method, e);
            return McpJsonRpc.Response.fail(request.id(),
                    McpJsonRpc.INTERNAL_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Map<String, Object> buildInitializeResult() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));

        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);
        return result;
    }

    private Map<String, Object> buildToolsList() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (McpTool tool : registry.all()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", tool.name());
            entry.put("description", tool.description());
            entry.put("inputSchema", tool.inputSchema());
            entries.add(entry);
        }
        return Map.of("tools", entries);
    }

    private Map<String, Object> invokeTool(JsonNode params) throws Exception {
        if (params == null || !params.isObject()) {
            throw new IllegalArgumentException("tools/call requires a params object");
        }
        String name = params.path("name").asText(null);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tools/call requires a 'name' string");
        }
        McpTool tool = registry.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }
        JsonNode arguments = params.has("arguments") ? params.get("arguments")
                : mapper.createObjectNode();

        Object result;
        try {
            result = tool.handler().call(arguments);
        } catch (IllegalArgumentException e) {
            return errorContent(e.getMessage());
        } catch (Exception e) {
            logger.warn("Tool '{}' threw", name, e);
            return errorContent(e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        ObjectNode text = mapper.createObjectNode();
        text.put("type", "text");
        text.put("text", mapper.writeValueAsString(result));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", List.of(text));
        response.put("isError", false);
        return response;
    }

    private Map<String, Object> errorContent(String message) {
        ObjectNode text = mapper.createObjectNode();
        text.put("type", "text");
        text.put("text", message);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", List.of(text));
        response.put("isError", true);
        return response;
    }
}
