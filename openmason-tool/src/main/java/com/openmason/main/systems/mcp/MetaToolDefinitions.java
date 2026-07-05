package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import static com.openmason.main.systems.mcp.McpArgs.optString;

/**
 * Cross-domain meta tools: orientation ({@code model_summary}),
 * self-description ({@code describe_api}), and domain-routed {@code undo} /
 * {@code redo}.
 *
 * <p>Honesty note on undo: this merges tool NAMES, not timelines — the five
 * histories (model+face-textures, texture editor, bones, sockets, animation)
 * remain separate; {@code domain} picks which one to step.
 */
public final class MetaToolDefinitions {

    private final ModelSummaryService summary;
    private final ModelEditingService model;
    private final TextureEditingService texture;
    private final BoneEditingService bones;
    private final AttachmentEditingService attachments;
    private final AnimationEditingService animation;
    private final ObjectMapper mapper;

    public MetaToolDefinitions(ModelSummaryService summary,
                               ModelEditingService model,
                               TextureEditingService texture,
                               BoneEditingService bones,
                               AttachmentEditingService attachments,
                               AnimationEditingService animation,
                               ObjectMapper mapper) {
        this.summary = summary;
        this.model = model;
        this.texture = texture;
        this.bones = bones;
        this.attachments = attachments;
        this.animation = animation;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        registry.register(new McpTool(
                "model_summary",
                "One-call orientation digest of the open model: totals, world bbox, per-part "
                        + "rows {name, verts, faces, bbox, parent}, bones, attachment sockets, and "
                        + "the current selection. Call this first instead of separate "
                        + "list/info/selection calls.",
                McpSchema.of(mapper).build(),
                args -> summary.summarize()));

        registry.register(new McpTool(
                "describe_api",
                "Compact modeling guide (markdown). Topics: overview (default — conventions: "
                        + "Y-up, degrees, part origins, id-or-name addressing, undo domains), parts, "
                        + "face_textures, bones, attachments, animation, scripting (the Python om "
                        + "cheatsheet), recipes (workflows like 'build a quadruped mob'). Read "
                        + "overview once per session; other topics on demand.",
                McpSchema.of(mapper)
                        .enumStr("topic", "Guide topic (default overview)",
                                McpGuide.TOPICS.toArray(new String[0]))
                        .build(),
                args -> McpGuide.topic(optString(args, "topic"))));

        registry.register(new McpTool(
                "undo",
                "Undo the most recent mutation in a domain: model (parts/geometry/face textures — "
                        + "the default; a whole script run is one step), texture (texture editor), "
                        + "bone, attach (sockets), anim. The five histories are separate.",
                domainSchema(),
                args -> stepHistory(optString(args, "domain"), true)));

        registry.register(new McpTool(
                "redo",
                "Redo the most recently undone mutation in a domain (see undo).",
                domainSchema(),
                args -> stepHistory(optString(args, "domain"), false)));
    }

    private com.fasterxml.jackson.databind.JsonNode domainSchema() {
        return McpSchema.of(mapper)
                .enumStr("domain", "Which history to step (default model)",
                        "model", "texture", "bone", "attach", "anim")
                .build();
    }

    private Object stepHistory(String domain, boolean undo) {
        String d = domain == null ? "model" : domain.trim().toLowerCase();
        return switch (d) {
            case "model" -> undo ? model.undo() : model.redo();
            case "texture" -> undo ? texture.undo() : texture.redo();
            case "bone" -> undo ? bones.undo() : bones.redo();
            case "attach" -> undo ? attachments.undo() : attachments.redo();
            case "anim" -> undo ? animation.undo() : animation.redo();
            default -> throw McpErrors.invalidEnum("domain", d,
                    java.util.List.of("model", "texture", "bone", "attach", "anim"));
        };
    }
}
