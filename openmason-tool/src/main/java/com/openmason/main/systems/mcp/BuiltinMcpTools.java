package com.openmason.main.systems.mcp;

import com.openmason.api.mcp.McpToolContribution;
import com.openmason.main.systems.scripting.mcp.ScriptLibraryService;
import com.openmason.main.systems.scripting.mcp.ScriptLibraryToolDefinitions;
import com.openmason.main.systems.scripting.mcp.ScriptingService;
import com.openmason.main.systems.scripting.mcp.ScriptingToolDefinitions;

import java.util.List;

/**
 * The built-in MCP tool groups, each contributed by the built-in plugin that owns its feature.
 * Ids name the per-group tool-surface snapshots ({@code src/test/resources/mcp/tools/<id>.txt}).
 */
public final class BuiltinMcpTools {

    private BuiltinMcpTools() {
    }

    // ── openmason.core ──────────────────────────────────────────────────────

    public static final McpToolContribution SAVE = new McpToolContribution("core.save",
            c -> new SaveToolDefinitions(McpSharedServices.of(c).writes(), c.mapper()).registerAll(c.registry()));

    public static final McpToolContribution MODEL_FILES = new McpToolContribution("core.model-files",
            c -> new ModelFileToolDefinitions(McpSharedServices.of(c).modelFiles(), c.mapper()).registerAll(c.registry()));

    public static final McpToolContribution MODEL = new McpToolContribution("core.model",
            c -> new OpenMasonToolDefinitions(McpSharedServices.of(c).model(), c.mapper()).registerAll(c.registry()));

    public static final McpToolContribution BONES = new McpToolContribution("core.bones",
            c -> new BoneToolDefinitions(McpSharedServices.of(c).bones(), c.mapper()).registerAll(c.registry()));

    public static final McpToolContribution ATTACHMENTS = new McpToolContribution("core.attachments",
            c -> new AttachmentToolDefinitions(McpSharedServices.of(c).attachments(), c.mapper()).registerAll(c.registry()));

    public static final McpToolContribution VIEWPORT = new McpToolContribution("core.viewport",
            c -> new ViewportToolDefinitions(new ViewportCaptureService(McpSharedServices.of(c).editor()), c.mapper())
                    .registerAll(c.registry()));

    public static final McpToolContribution SCENE = new McpToolContribution("core.scene",
            c -> new SceneToolDefinitions(new SceneEditingService(McpSharedServices.of(c).editor()),
                    McpSharedServices.of(c).writes(), c.mapper()).registerAll(c.registry()));

    public static final McpToolContribution INSPECTION = new McpToolContribution("core.inspection",
            c -> new ModelInspectionToolDefinitions(new ModelInspectionService(McpSharedServices.of(c).editor(),
                    McpSharedServices.of(c).faceTexture(), c.mapper()), c.mapper()).registerAll(c.registry()));

    public static final McpToolContribution KNOWLEDGE = new McpToolContribution("core.knowledge",
            c -> new KnowledgeToolDefinitions(c.mapper(), c.capabilities().libalexRecall(),
                    c.services().require(KnowledgeBase.class)).registerAll(c.registry()));

    public static final McpToolContribution META = new McpToolContribution("core.meta", c -> {
        McpSharedServices s = McpSharedServices.of(c);
        new MetaToolDefinitions(new ModelSummaryService(s.editor()), s.model(), s.texture(), s.bones(),
                s.attachments(), s.animation(), c.mapper(), c.services().require(McpGuide.class))
                .registerAll(c.registry());
    });

    // ── openmason.texture ───────────────────────────────────────────────────

    public static final McpToolContribution TEXTURE = new McpToolContribution("texture.editor", c -> {
        McpSharedServices s = McpSharedServices.of(c);
        new TextureToolDefinitions(s.texture(), new CanvasCaptureService(s.editor()), s.writes(), c.mapper())
                .registerAll(c.registry());
    });

    public static final McpToolContribution FACE_TEXTURES = new McpToolContribution("texture.faces",
            c -> new FaceTextureToolDefinitions(McpSharedServices.of(c).faceTexture(), c.mapper()).registerAll(c.registry()));

    // ── openmason.animation ─────────────────────────────────────────────────

    public static final McpToolContribution ANIMATION = new McpToolContribution("animation",
            c -> new AnimationToolDefinitions(McpSharedServices.of(c).animation(), McpSharedServices.of(c).writes(),
                    c.mapper()).registerAll(c.registry()));

    // ── openmason.ui ────────────────────────────────────────────────────────

    /** UI Editor (#324): documents, inspection, op batches (one undo step each), preview, files. */
    public static final McpToolContribution UI = new McpToolContribution("ui", c -> {
        McpSharedServices s = McpSharedServices.of(c);
        new UiToolDefinitions(UiEditingService.forInterface(s.editor(), s.writes(), c.mapper()), c.mapper())
                .registerAll(c.registry());
    });

    // ── openmason.scripting ─────────────────────────────────────────────────

    public static final McpToolContribution SCRIPTING = new McpToolContribution("scripting.run",
            c -> new ScriptingToolDefinitions(new ScriptingService(McpSharedServices.of(c).editor(), c.mapper()),
                    c.mapper()).registerAll(c.registry()));

    public static final McpToolContribution SCRIPT_LIBRARY = new McpToolContribution("scripting.library",
            c -> new ScriptLibraryToolDefinitions(new ScriptLibraryService(McpSharedServices.of(c).editor()),
                    c.mapper()).registerAll(c.registry()));

    // ── stonebreak (built in until #382 moves it to openmason-plugin-stonebreak) ──

    public static final McpToolContribution ASSET_EXPORT = new McpToolContribution("stonebreak.assets", c -> {
        McpSharedServices s = McpSharedServices.of(c);
        new AssetExportToolDefinitions(new AssetExportService(s.editor(), s.writes(), s.modelFiles(), s.texture()),
                c.mapper()).registerAll(c.registry());
    });

    public static final McpToolContribution ASSET_LENS = new McpToolContribution("stonebreak.lens",
            c -> new AssetLensToolDefinitions(new AssetLensService(McpSharedServices.of(c).editor(), c.mapper()),
                    c.mapper()).registerAll(c.registry()));

    /** Every built-in group, in the order the built-in plugins register them. */
    public static List<McpToolContribution> all() {
        return List.of(SAVE, MODEL_FILES, MODEL, BONES, ATTACHMENTS, VIEWPORT, SCENE, INSPECTION, KNOWLEDGE, META,
                TEXTURE, FACE_TEXTURES, ANIMATION, UI, SCRIPTING, SCRIPT_LIBRARY, ASSET_EXPORT, ASSET_LENS);
    }
}
