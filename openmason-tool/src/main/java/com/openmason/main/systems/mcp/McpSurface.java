package com.openmason.main.systems.mcp;

import com.openmason.api.knowledge.GuideTopic;
import com.openmason.api.knowledge.KnowledgePack;
import com.openmason.api.mcp.McpToolContribution;

import java.util.List;

/**
 * What the MCP server exposes, as contributed by the active plugins: tool groups, the
 * {@code describe_api} guide topics and the {@code knowledge} packs.
 */
public record McpSurface(List<McpToolContribution> tools, List<GuideTopic> guideTopics,
                         List<KnowledgePack> knowledgePacks) {

    public McpSurface {
        tools = List.copyOf(tools);
        guideTopics = List.copyOf(guideTopics);
        knowledgePacks = List.copyOf(knowledgePacks);
    }

    /** The built-in plugins' surface (headless tests, the server without a plugin host). */
    public static McpSurface builtin() {
        return new McpSurface(BuiltinMcpTools.all(), BuiltinKnowledge.guideTopics(), BuiltinKnowledge.knowledgePacks());
    }
}
