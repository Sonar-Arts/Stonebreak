package com.openmason.api.knowledge;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * A topic of the agent-facing guide served by the {@code describe_api} MCP tool.
 *
 * @param id       topic name agents ask for ({@code texture_editor})
 * @param markdown the topic text, read on demand (cached by the host)
 */
public record GuideTopic(String id, Supplier<String> markdown) {

    public GuideTopic {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(markdown, "markdown");
    }
}
