package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
                        + "side is max_size (default 512, range 64-1024); keep it small, vision token "
                        + "cost scales with pixel area. Select a part first (select_part / focus_camera_on) "
                        + "to frame it.",
                captureSchema(),
                args -> capture.capture(optInt(args, "max_size", ViewportCaptureService.DEFAULT_MAX_SIZE))));
    }

    private JsonNode captureSchema() {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        ObjectNode maxSize = mapper.createObjectNode();
        maxSize.put("type", "integer");
        maxSize.put("description", "Longest-side pixel cap, 64-1024 (default 512)");
        props.set("max_size", maxSize);
        root.set("properties", props);
        return root;
    }

    private static int optInt(JsonNode args, String key, int fallback) {
        JsonNode n = args.get(key);
        return (n == null || n.isNull() || !n.isNumber()) ? fallback : n.intValue();
    }
}
