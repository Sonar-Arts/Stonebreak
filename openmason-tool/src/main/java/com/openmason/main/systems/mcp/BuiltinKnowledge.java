package com.openmason.main.systems.mcp;

import com.openmason.api.knowledge.GuideTopic;
import com.openmason.api.knowledge.KnowledgePack;
import com.openmason.api.knowledge.ResourceText;

import java.util.List;

/**
 * The built-in {@code describe_api} topics ({@code /mcp/guide/<id>.md}) and knowledge packs
 * ({@code /mcp/kb/<id>.md}); each built-in plugin registers the ones about its feature.
 */
public final class BuiltinKnowledge {

    private BuiltinKnowledge() {
    }

    public static GuideTopic topic(String id) {
        return new GuideTopic(id, ResourceText.of(BuiltinKnowledge.class, "/mcp/guide/" + id + ".md"));
    }

    public static KnowledgePack pack(String id) {
        return new KnowledgePack(id, ResourceText.of(BuiltinKnowledge.class, "/mcp/kb/" + id + ".md"));
    }

    /** Every built-in topic, in {@link McpGuide#BUILTIN_TOPICS} order. */
    public static List<GuideTopic> guideTopics() {
        return McpGuide.BUILTIN_TOPICS.stream().map(BuiltinKnowledge::topic).toList();
    }

    /** Every built-in pack, in {@link KnowledgeBase#BUILTIN_PACKS} order. */
    public static List<KnowledgePack> knowledgePacks() {
        return KnowledgeBase.BUILTIN_PACKS.stream().map(BuiltinKnowledge::pack).toList();
    }
}
