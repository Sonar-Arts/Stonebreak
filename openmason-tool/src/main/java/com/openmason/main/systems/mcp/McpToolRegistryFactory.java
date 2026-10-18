package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.api.ServiceRegistry;
import com.openmason.api.mcp.McpToolContext;
import com.openmason.api.mcp.McpToolContribution;
import com.openmason.main.systems.io.AssetWriteService;
import com.openmason.main.systems.io.WriteRoots;
import com.openmason.main.systems.io.WriteSandbox;
import com.openmason.main.systems.menus.preferences.AssistantPreferences;
import com.openmason.main.systems.services.EditorServices;

import java.util.List;

/**
 * Single source of truth for the MCP tool surface: builds the registry from the MCP tool
 * contributions of the active plugins (#379).
 *
 * <p>Both consumers build the registry here so they can never drift apart:
 * the embedded HTTP MCP server ({@link McpServerBootstrap}) and the in-tool
 * assistant harness, which translates the same {@link McpTool} records into
 * OpenAI function specs and dispatches through the same handlers.
 *
 * <p>Before the contributors run, the services every contributor shares are created once and
 * published in the build's {@link McpToolContext#services()}: {@link EditorServices}, the
 * {@link AssetWriteService} (every file write goes through one sandbox + policy + Save Sheet seam)
 * and the domain editing services ({@link McpSharedServices}). Service constructors must only store
 * the {@code EditorServices} reference (it is empty in the snapshot test); handlers hop to the main
 * thread via {@code MainThreadExecutor} when invoked.
 */
public final class McpToolRegistryFactory {

    private McpToolRegistryFactory() {
        // Static factory
    }

    /** The built-in tool surface (the built-in plugins' contributions), for headless tests. */
    public static McpToolRegistry build(EditorServices editorServices, ObjectMapper mapper,
                                        ToolCapabilities capabilities) {
        return build(editorServices, mapper, capabilities, McpSurface.builtin());
    }

    /** Builds the registry from the surface's tool contributions, in order. */
    public static McpToolRegistry build(EditorServices editorServices, ObjectMapper mapper,
                                        ToolCapabilities capabilities, McpSurface surface) {
        McpToolRegistry registry = new McpToolRegistry();
        McpToolContext context = context(editorServices, mapper, capabilities, registry, surface);
        List<McpToolContribution> contributions = surface.tools();
        for (McpToolContribution c : contributions) {
            c.contributor().contribute(context);
        }
        return registry;
    }

    /**
     * A build context over {@code registry} with the shared services published (also used by the
     * per-contributor snapshot test).
     */
    public static McpToolContext context(EditorServices editorServices, ObjectMapper mapper,
                                         ToolCapabilities capabilities, McpToolRegistry registry,
                                         McpSurface surface) {
        EditorServices editor = editorServices != null ? editorServices : EditorServices.empty();
        ServiceRegistry services = new ServiceRegistry();
        services.put(EditorServices.class, editor);
        AssetWriteService writes = new AssetWriteService(editor,
                new WriteSandbox(WriteRoots.forInterface(editor)), AssistantPreferences::writePolicy);
        services.put(AssetWriteService.class, writes);
        services.put(McpSharedServices.class, McpSharedServices.create(editor, writes));
        services.put(McpGuide.class, new McpGuide(surface.guideTopics()));
        services.put(KnowledgeBase.class, new KnowledgeBase(surface.knowledgePacks()));
        ToolCapabilities caps = capabilities != null ? capabilities : ToolCapabilities.none();
        return new McpToolContext() {
            @Override
            public McpToolRegistry registry() {
                return registry;
            }

            @Override
            public ObjectMapper mapper() {
                return mapper;
            }

            @Override
            public ToolCapabilities capabilities() {
                return caps;
            }

            @Override
            public ServiceRegistry services() {
                return services;
            }
        };
    }
}
