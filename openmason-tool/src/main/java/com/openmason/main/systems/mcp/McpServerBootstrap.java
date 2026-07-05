package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
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
            // Token efficiency: omit null fields from tool results (gated arrays in
            // inspect_part, unresolved bone world positions, etc.).
            mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
            McpToolRegistry registry = new McpToolRegistry();
            ModelEditingService editor = new ModelEditingService(mainInterface);
            new OpenMasonToolDefinitions(editor, mapper).registerAll(registry);

            TextureEditingService textureEditor = new TextureEditingService(mainInterface);
            new TextureToolDefinitions(textureEditor, mapper).registerAll(registry);

            FaceTextureEditingService faceTextureEditor = new FaceTextureEditingService(mainInterface);
            new FaceTextureToolDefinitions(faceTextureEditor, mapper).registerAll(registry);

            BoneEditingService boneEditor = new BoneEditingService(mainInterface);
            new BoneToolDefinitions(boneEditor, mapper).registerAll(registry);

            AttachmentEditingService attachmentEditor = new AttachmentEditingService(mainInterface);
            new AttachmentToolDefinitions(attachmentEditor, mapper).registerAll(registry);

            AnimationEditingService animationEditor = new AnimationEditingService(mainInterface);
            new AnimationToolDefinitions(animationEditor, mapper).registerAll(registry);

            ViewportCaptureService viewportCapture = new ViewportCaptureService(mainInterface);
            new ViewportToolDefinitions(viewportCapture, mapper).registerAll(registry);

            com.openmason.main.systems.scripting.mcp.ScriptingService scripting =
                    new com.openmason.main.systems.scripting.mcp.ScriptingService(mainInterface, mapper);
            new com.openmason.main.systems.scripting.mcp.ScriptingToolDefinitions(scripting, mapper)
                    .registerAll(registry);

            new MetaToolDefinitions(new ModelSummaryService(mainInterface),
                    editor, textureEditor, boneEditor, attachmentEditor, animationEditor, mapper)
                    .registerAll(registry);

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
