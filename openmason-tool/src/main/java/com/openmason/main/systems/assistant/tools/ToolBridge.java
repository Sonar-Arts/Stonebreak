package com.openmason.main.systems.assistant.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.main.systems.mcp.McpImageContent;
import com.openmason.main.systems.mcp.McpTool;
import com.openmason.main.systems.mcp.McpToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bridges the shared {@link McpToolRegistry} to OpenAI function calling: the
 * registry's name/description/inputSchema become function specs verbatim, and
 * dispatch goes through the SAME handlers the MCP server uses (which hop to
 * the main thread internally) — MCP clients and the in-tool assistant can
 * never drift apart.
 */
public final class ToolBridge {

    /** Cap on a single tool result fed back to the model. */
    public static final int RESULT_CHAR_CAP = 24_000;

    /** Dispatch outcome: text for the model + optional image for the UI. */
    public record DispatchResult(String text, McpImageContent image, boolean error) {
    }

    private final McpToolRegistry registry;
    private final ObjectMapper mapper;

    public ToolBridge(McpToolRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    /** OpenAI {@code tools} specs for every registered tool. */
    public List<ObjectNode> openAiToolSpecs() {
        List<ObjectNode> specs = new ArrayList<>();
        for (McpTool tool : registry.all()) {
            ObjectNode spec = mapper.createObjectNode();
            spec.put("type", "function");
            ObjectNode fn = spec.putObject("function");
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            fn.set("parameters", tool.inputSchema());
            specs.add(spec);
        }
        return specs;
    }

    public ToolCategory categorize(String name) {
        return ToolAccessPolicy.categorize(name);
    }

    /**
     * Dispatch one call. Never throws: every failure becomes teaching-error
     * text the model can act on (mirrors {@code McpRequestRouter}'s
     * isError handling).
     */
    public DispatchResult dispatch(String name, String argumentsJson) {
        McpTool tool = registry.get(name);
        if (tool == null) {
            return new DispatchResult("Error: unknown tool '" + name + "'."
                    + suggest(name), null, true);
        }
        JsonNode args;
        try {
            String json = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
            args = mapper.readTree(json);
        } catch (Exception e) {
            return new DispatchResult("Error: tool arguments are not valid JSON ("
                    + e.getMessage() + ")", null, true);
        }
        Object result;
        try {
            result = tool.handler().call(args);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new DispatchResult("Error: " + e.getMessage(), null, true);
        } catch (Exception e) {
            return new DispatchResult("Error: " + e.getClass().getSimpleName() + ": "
                    + e.getMessage(), null, true);
        }
        return marshal(result);
    }

    private DispatchResult marshal(Object result) {
        if (result instanceof McpImageContent image) {
            return new DispatchResult("[image captured — shown to the user in the chat pane]",
                    image, false);
        }
        String text;
        if (result == null) {
            text = "ok";
        } else if (result instanceof String s) {
            text = s;
        } else {
            try {
                text = mapper.writeValueAsString(result);
            } catch (Exception e) {
                text = String.valueOf(result);
            }
        }
        if (text.length() > RESULT_CHAR_CAP) {
            text = text.substring(0, RESULT_CHAR_CAP)
                    + " …[truncated " + (text.length() - RESULT_CHAR_CAP)
                    + " chars — request a smaller region/page]";
        }
        return new DispatchResult(text, null, false);
    }

    private String suggest(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        List<String> close = new ArrayList<>();
        for (McpTool tool : registry.all()) {
            if (tool.name().toLowerCase(Locale.ROOT).contains(lower)
                    || (lower.length() > 4 && lower.contains(tool.name().substring(0,
                            Math.min(4, tool.name().length()))))) {
                close.add(tool.name());
                if (close.size() >= 5) {
                    break;
                }
            }
        }
        return close.isEmpty() ? "" : " Did you mean: " + close + "?";
    }
}
