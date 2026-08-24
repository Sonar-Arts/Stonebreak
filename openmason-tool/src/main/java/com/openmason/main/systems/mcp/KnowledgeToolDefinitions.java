package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import static com.openmason.main.systems.mcp.McpArgs.optInt;
import static com.openmason.main.systems.mcp.McpArgs.optString;

/**
 * The {@code knowledge} tool (always registered) and, when libalex is
 * detected, {@code knowledge_search} over the machine's libalex library.
 */
public final class KnowledgeToolDefinitions {

    /** Seam to the libalex client (registered only when available). */
    public interface LibalexRecall {
        String recall(String query, String collection, int limit) throws Exception;
    }

    private final ObjectMapper mapper;
    private final LibalexRecall libalex; // null = not detected

    public KnowledgeToolDefinitions(ObjectMapper mapper, LibalexRecall libalex) {
        this.mapper = mapper;
        this.libalex = libalex;
    }

    public void registerAll(McpToolRegistry registry) {
        registry.register(new McpTool(
                "knowledge",
                "Distilled 3D-art knowledge for this tool: modelling, pixel-art texturing, "
                        + "animation, workflows, pitfalls, formats. No args lists packs; topic "
                        + "returns one; query searches sections. Consult before non-trivial "
                        + "art decisions.",
                McpSchema.of(mapper)
                        .enumStr("topic", "Pack to read in full",
                                KnowledgeBase.PACKS.toArray(new String[0]))
                        .str("query", "Search across all packs instead")
                        .intg("limit", "Max matching sections (default 4, max 8)")
                        .build(),
                args -> {
                    String topic = optString(args, "topic");
                    String query = optString(args, "query");
                    if (topic != null && !topic.isBlank()) {
                        return KnowledgeBase.pack(topic);
                    }
                    if (query != null && !query.isBlank()) {
                        return KnowledgeBase.search(query, optInt(args, "limit", 4));
                    }
                    return KnowledgeBase.index();
                }));

        if (libalex != null) {
            registry.register(new McpTool(
                    "knowledge_search",
                    "Search this machine's libalex library (project corpora: modelling packs, "
                            + "engine code notes, game-dev references) for cited slices.",
                    McpSchema.of(mapper)
                            .str("query", "What to recall")
                            .enumStr("collection", "Corpus to search (omit = sensible default)",
                                    "openmason-models", "3d-models", "game-development",
                                    "stonebreak-code")
                            .intg("limit", "Max results (default 5)")
                            .required("query")
                            .build(),
                    args -> libalex.recall(McpArgs.reqString(args, "query"),
                            optString(args, "collection"), optInt(args, "limit", 5))));
        }
    }
}
