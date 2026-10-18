package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.services.EditorServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * One-shot bootstrap for the embedded MCP server.
 *
 * <p>Constructs the editing service, registers all tools, starts an HTTP server
 * bound to {@code 127.0.0.1:7878}.
 */
public final class McpServerBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(McpServerBootstrap.class);

    private static final int PORT = 7878;

    private McpHttpServer server;
    private McpToolRegistry registry;
    private ObjectMapper mapper;

    public void start(EditorServices editorServices) {
        start(editorServices, ToolCapabilities.none(), McpSurface.builtin());
    }

    /**
     * Builds the registry from {@code surface} (the active plugins' MCP tool groups, guide topics
     * and knowledge packs) and starts the server.
     */
    public void start(EditorServices editorServices, ToolCapabilities capabilities, McpSurface surface) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            // Token efficiency: omit null fields from tool results (gated arrays in
            // inspect_part, unresolved bone world positions, etc.).
            mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
            McpToolRegistry registry =
                    McpToolRegistryFactory.build(editorServices, mapper, capabilities, surface);
            this.registry = registry;
            this.mapper = mapper;

            McpRequestRouter router = new McpRequestRouter(registry, mapper);
            server = new McpHttpServer(PORT, router, mapper);
            server.start();
            logger.info("MCP server started ({} tools registered)", registry.all().size());
        } catch (IOException e) {
            logger.error("Failed to start MCP server", e);
            server = null;
        }
    }

    /**
     * The live tool registry, or {@code null} before {@link #start} succeeds.
     * Shared with the in-tool assistant harness so both surfaces stay identical.
     */
    public McpToolRegistry registry() {
        return registry;
    }

    /** The shared JSON mapper (NON_NULL serialization), or {@code null} before start. */
    public ObjectMapper mapper() {
        return mapper;
    }

    public void stop() {
        if (server != null) {
            try {
                server.stop();
            } catch (Throwable t) {
                logger.warn("Error stopping MCP server", t);
            } finally {
                server = null;
            }
        }
    }
}
