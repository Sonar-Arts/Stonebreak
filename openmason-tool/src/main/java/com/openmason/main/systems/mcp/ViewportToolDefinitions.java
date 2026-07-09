package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static com.openmason.main.systems.mcp.McpArgs.optInt;

/**
 * Registers MCP tools for the 3D viewport itself (as opposed to the model
 * loaded in it), exposing {@link ViewportCaptureService}.
 */
public final class ViewportToolDefinitions {

    private final ViewportCaptureService capture;
    private final ObjectMapper mapper;

    public ViewportToolDefinitions(ViewportCaptureService capture, ObjectMapper mapper) {
        this.capture = capture;
        this.mapper = mapper;
    }

    public void registerAll(McpToolRegistry registry) {
        registry.register(new McpTool(
                "viewport_capture",
                "Capture the 3D viewport's last rendered frame (current camera view) as a PNG image — "
                        + "use it to visually verify the model between edits. Downscaled so the longest "
                        + "side is max_size (default 1024, range 64-2048); the source resolution is "
                        + "capped by the on-screen viewport panel size. Select a part first "
                        + "(select_part) to frame it.",
                captureSchema(),
                args -> capture.capture(optInt(args, "max_size", ViewportCaptureService.DEFAULT_MAX_SIZE))));
    }

    private JsonNode captureSchema() {
        return McpSchema.of(mapper)
                .intg("max_size", "Longest-side pixel cap, 64-2048 (default 1024)")
                .build();
    }
}
