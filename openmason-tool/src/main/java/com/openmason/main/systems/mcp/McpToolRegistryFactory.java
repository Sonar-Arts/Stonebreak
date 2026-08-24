package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.MainImGuiInterface;

/**
 * Single source of truth for the MCP tool surface.
 *
 * <p>Both consumers build the registry here so they can never drift apart:
 * the embedded HTTP MCP server ({@link McpServerBootstrap}) and the in-tool
 * assistant harness, which translates the same {@link McpTool} records into
 * OpenAI function specs and dispatches through the same handlers.
 *
 * <p>Service constructors must only store the {@code mainInterface} reference
 * (it is {@code null} in the snapshot test); handlers hop to the main thread
 * via {@code MainThreadExecutor} when invoked.
 */
public final class McpToolRegistryFactory {

    private McpToolRegistryFactory() {
        // Static factory
    }

    /** Build the full tool registry for the given capability set. */
    public static McpToolRegistry build(MainImGuiInterface mainInterface, ObjectMapper mapper,
                                        ToolCapabilities capabilities) {
        McpToolRegistry registry = new McpToolRegistry();

        ModelEditingService editor = new ModelEditingService(mainInterface);
        new OpenMasonToolDefinitions(editor, mapper).registerAll(registry);

        TextureEditingService textureEditor = new TextureEditingService(mainInterface);
        CanvasCaptureService canvasCapture = new CanvasCaptureService(mainInterface);
        new TextureToolDefinitions(textureEditor, canvasCapture, mapper).registerAll(registry);

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

        AssetLensService assetLens = new AssetLensService(mainInterface, mapper);
        new AssetLensToolDefinitions(assetLens, mapper).registerAll(registry);

        ModelInspectionService modelInspection =
                new ModelInspectionService(mainInterface, faceTextureEditor, mapper);
        new ModelInspectionToolDefinitions(modelInspection, mapper).registerAll(registry);

        com.openmason.main.systems.scripting.mcp.ScriptLibraryService scriptLibrary =
                new com.openmason.main.systems.scripting.mcp.ScriptLibraryService(mainInterface);
        new com.openmason.main.systems.scripting.mcp.ScriptLibraryToolDefinitions(scriptLibrary, mapper)
                .registerAll(registry);

        new KnowledgeToolDefinitions(mapper, capabilities.libalexRecall()).registerAll(registry);

        new MetaToolDefinitions(new ModelSummaryService(mainInterface),
                editor, textureEditor, boneEditor, attachmentEditor, animationEditor, mapper)
                .registerAll(registry);

        return registry;
    }
}
