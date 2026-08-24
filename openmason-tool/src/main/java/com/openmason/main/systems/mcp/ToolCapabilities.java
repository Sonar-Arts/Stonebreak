package com.openmason.main.systems.mcp;

/**
 * Optional-capability seams that gate conditional MCP tool registration.
 *
 * <p>The golden snapshot test builds the registry with {@link #none()} so the
 * pinned tool surface stays deterministic on machines without the optional
 * integrations; capability-gated tools get their own targeted tests.
 *
 * @param libalexRecall live libalex bridge, or {@code null} when libalex is
 *                      not detected on this machine (gates {@code knowledge_search})
 */
public record ToolCapabilities(KnowledgeToolDefinitions.LibalexRecall libalexRecall) {

    /** All optional capabilities off — the deterministic baseline surface. */
    public static ToolCapabilities none() {
        return new ToolCapabilities(null);
    }

    public boolean libalexAvailable() {
        return libalexRecall != null;
    }
}
