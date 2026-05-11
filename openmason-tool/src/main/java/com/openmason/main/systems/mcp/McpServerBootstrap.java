package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.MainImGuiInterface;
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

    public void start(MainImGuiInterface mainInterface) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            McpToolRegistry registry = new McpToolRegistry();
            ModelEditingService editor = new ModelEditingService(mainInterface);
            new OpenMasonToolDefinitions(editor, mapper).registerAll(registry);

            TextureEditingService textureEditor = new TextureEditingService(mainInterface);
            new TextureToolDefinitions(textureEditor, mapper).registerAll(registry);

            BoneEditingService boneEditor = new BoneEditingService(mainInterface);
            new BoneToolDefinitions(boneEditor, mapper).registerAll(registry);

            AnimationEditingService animationEditor = new AnimationEditingService(mainInterface);
            new AnimationToolDefinitions(animationEditor, mapper).registerAll(registry);

            McpRequestRouter router = new McpRequestRouter(registry, mapper);
            server = new McpHttpServer(PORT, router, mapper);
            server.start();
            logger.info("MCP server started ({} tools registered)", registry.all().size());
        } catch (IOException e) {
            logger.error("Failed to start MCP server", e);
            server = null;
        }
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
