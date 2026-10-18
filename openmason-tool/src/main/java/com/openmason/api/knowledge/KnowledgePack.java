package com.openmason.api.knowledge;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * A pack of the built-in knowledge base served by the {@code knowledge} MCP tool: markdown whose
 * first line is the pack's one-line summary and whose {@code ##} sections are searchable.
 *
 * @param id       pack name ({@code texturing_pixel_art})
 * @param markdown the pack text, read on demand (cached by the host)
 */
public record KnowledgePack(String id, Supplier<String> markdown) {

    public KnowledgePack {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(markdown, "markdown");
    }
}
