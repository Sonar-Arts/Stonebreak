package com.openmason.main.systems.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Deserializer for .OMP (Open Mason Project) files.
 * Reads JSON using Jackson's tree model (JsonNode) to avoid module reflection issues.
 * Gracefully handles unknown/missing fields and version differences.
 */
public class OMPDeserializer {

    private static final Logger logger = LoggerFactory.getLogger(OMPDeserializer.class);

    private final ObjectMapper objectMapper;

    public OMPDeserializer() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Load an OMP document from the specified file path.
     *
     * @param filePath the file path to load from
     * @return the loaded document, or null if loading failed
     */
    public OMPFormat.Document load(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            logger.error("Cannot load: file path is null or empty");
            return null;
        }

        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            logger.error("OMP file does not exist: {}", filePath);
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(path.toFile());

            if (root == null || root.isEmpty()) {
                logger.error("Failed to parse OMP file: empty content");
                return null;
            }

            String version = text(root, "version", OMPFormat.FORMAT_VERSION);
            if (!version.equals(OMPFormat.FORMAT_VERSION)) {
                logger.warn("OMP file version {} differs from current version {}. Loading anyway.",
                        version, OMPFormat.FORMAT_VERSION);
            }

            OMPFormat.Document document = fromJsonTree(root, version);
            logger.info("OMP project loaded: {} (version {})", filePath, version);
            return document;

        } catch (Exception e) {
            logger.error("Failed to load OMP project: {}", filePath, e);
            return null;
        }
    }

    private OMPFormat.Document fromJsonTree(JsonNode root, String version) {
        return new OMPFormat.Document(
                version,
                text(root, "projectName", "Untitled Project"),
                text(root, "createdAt", null),
                text(root, "lastSavedAt", null),
                parseCameraState(root.get("camera")),
                parseViewportState(root.get("viewport")),
                parseTransformData(root.get("transform")),
                parseModelReference(root.get("model")),
                parseUIState(root.get("ui")),
                parsePartsList(root.get("parts"))
        );
    }

    private OMPFormat.CameraState parseCameraState(JsonNode node) {
        if (node == null) {
            return new OMPFormat.CameraState("ARCBALL", 5.0f, 30.0f, 45.0f, 60.0f);
        }
        return new OMPFormat.CameraState(
                text(node, "mode", "ARCBALL"),
                floatVal(node, "distance", 5.0f),
                floatVal(node, "pitch", 30.0f),
                floatVal(node, "yaw", 45.0f),
                floatVal(node, "fov", 60.0f)
        );
    }

    private OMPFormat.ViewportState parseViewportState(JsonNode node) {
        if (node == null) {
            return new OMPFormat.ViewportState(0, 0, true, true, false, false, true, false, 0.5f);
        }
        return new OMPFormat.ViewportState(
                intVal(node, "viewModeIndex", 0),
                intVal(node, "renderModeIndex", 0),
                boolVal(node, "gridVisible", true),
                boolVal(node, "axesVisible", true),
                boolVal(node, "wireframeMode", false),
                boolVal(node, "showVertices", false),
                boolVal(node, "showGizmo", true),
                boolVal(node, "gridSnappingEnabled", false),
                floatVal(node, "gridSnappingIncrement", 0.5f)
        );
    }

    private OMPFormat.TransformData parseTransformData(JsonNode node) {
        if (node == null) {
            return new OMPFormat.TransformData(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, false);
        }
        return new OMPFormat.TransformData(
                floatVal(node, "positionX", 0.0f),
                floatVal(node, "positionY", 0.0f),
                floatVal(node, "positionZ", 0.0f),
                floatVal(node, "rotationX", 0.0f),
                floatVal(node, "rotationY", 0.0f),
                floatVal(node, "rotationZ", 0.0f),
                floatVal(node, "scaleX", 1.0f),
                floatVal(node, "scaleY", 1.0f),
                floatVal(node, "scaleZ", 1.0f),
                boolVal(node, "gizmoEnabled", false)
        );
    }

    private OMPFormat.ModelReference parseModelReference(JsonNode node) {
        if (node == null) {
            return new OMPFormat.ModelReference("BLOCK_MODEL", null, null, null, null, "NONE", null);
        }
        return new OMPFormat.ModelReference(
                text(node, "renderingMode", "BLOCK_MODEL"),
                text(node, "modelName", null),
                text(node, "textureVariant", null),
                text(node, "selectedBlockType", null),
                text(node, "selectedItemType", null),
                text(node, "modelSource", "NONE"),
                text(node, "modelFilePath", null)
        );
    }

    private OMPFormat.UIState parseUIState(JsonNode node) {
        if (node == null) {
            return new OMPFormat.UIState(true, true, true);
        }
        return new OMPFormat.UIState(
                boolVal(node, "showModelBrowser", true),
                boolVal(node, "showPropertyPanel", true),
                boolVal(node, "showToolbar", true)
        );
    }

    private List<OMPFormat.PartData> parsePartsList(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null; // No parts data (pre-1.1 file)
        }

        List<OMPFormat.PartData> parts = new ArrayList<>();
        for (JsonNode partNode : node) {
            String id = text(partNode, "id", null);
            if (id == null || id.isBlank()) {
                logger.warn("Skipping part with missing ID");
                continue;
            }

            parts.add(new OMPFormat.PartData(
                    id,
                    text(partNode, "name", "Unnamed Part"),
                    floatVal(partNode, "originX", 0.0f),
                    floatVal(partNode, "originY", 0.0f),
                    floatVal(partNode, "originZ", 0.0f),
                    floatVal(partNode, "posX", 0.0f),
                    floatVal(partNode, "posY", 0.0f),
                    floatVal(partNode, "posZ", 0.0f),
                    floatVal(partNode, "rotX", 0.0f),
                    floatVal(partNode, "rotY", 0.0f),
                    floatVal(partNode, "rotZ", 0.0f),
                    floatVal(partNode, "scaleX", 1.0f),
                    floatVal(partNode, "scaleY", 1.0f),
                    floatVal(partNode, "scaleZ", 1.0f),
                    boolVal(partNode, "visible", true),
                    boolVal(partNode, "locked", false)
            ));
        }

        logger.debug("Parsed {} model parts from OMP file", parts.size());
        return parts.isEmpty() ? null : parts;
    }

    // Helper methods for safe JsonNode field access

    private static String text(JsonNode node, String field, String defaultValue) {
        JsonNode child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : defaultValue;
    }

    private static float floatVal(JsonNode node, String field, float defaultValue) {
        JsonNode child = node.get(field);
        return (child != null && child.isNumber()) ? child.floatValue() : defaultValue;
    }

    private static int intVal(JsonNode node, String field, int defaultValue) {
        JsonNode child = node.get(field);
        return (child != null && child.isNumber()) ? child.intValue() : defaultValue;
    }

    private static boolean boolVal(JsonNode node, String field, boolean defaultValue) {
        JsonNode child = node.get(field);
        return (child != null && child.isBoolean()) ? child.booleanValue() : defaultValue;
    }
}
