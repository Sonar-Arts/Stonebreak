package com.openmason.main.systems.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Serializer for .OMP (Open Mason Project) files.
 * Writes session state as formatted JSON using atomic write (temp file + move).
 * Uses Jackson's tree model (ObjectNode) to avoid module reflection issues.
 */
public class OMPSerializer {

    private static final Logger logger = LoggerFactory.getLogger(OMPSerializer.class);

    private final ObjectMapper objectMapper;

    public OMPSerializer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Save an OMP document to the specified file path.
     * Uses atomic write: writes to a temp file first, then moves to the target.
     *
     * @param document the OMP document to save
     * @param filePath the target file path
     * @return true if saved successfully, false otherwise
     */
    public boolean save(OMPFormat.Document document, String filePath) {
        if (document == null || filePath == null || filePath.isBlank()) {
            logger.error("Cannot save: document or file path is null/empty");
            return false;
        }

        String resolvedPath = OMPFormat.ensureExtension(filePath);
        Path targetPath = Path.of(resolvedPath);

        try {
            // Ensure parent directory exists
            Path parentDir = targetPath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            // Build JSON tree from document
            ObjectNode root = toJsonTree(document);

            // Atomic write: temp file + move
            Path tempFile = Files.createTempFile(
                    parentDir != null ? parentDir : Path.of("."),
                    "omp_", ".tmp"
            );

            try {
                objectMapper.writeValue(tempFile.toFile(), root);
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                logger.info("OMP project saved: {}", resolvedPath);
                return true;
            } catch (IOException e) {
                Files.deleteIfExists(tempFile);
                throw e;
            }

        } catch (Exception e) {
            logger.error("Failed to save OMP project: {}", resolvedPath, e);
            return false;
        }
    }

    private ObjectNode toJsonTree(OMPFormat.Document doc) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", doc.version());
        root.put("projectName", doc.projectName());
        if (doc.createdAt() != null) root.put("createdAt", doc.createdAt());
        if (doc.lastSavedAt() != null) root.put("lastSavedAt", doc.lastSavedAt());

        if (doc.camera() != null) {
            ObjectNode camera = objectMapper.createObjectNode();
            camera.put("mode", doc.camera().mode());
            camera.put("distance", doc.camera().distance());
            camera.put("pitch", doc.camera().pitch());
            camera.put("yaw", doc.camera().yaw());
            camera.put("fov", doc.camera().fov());
            root.set("camera", camera);
        }

        if (doc.viewport() != null) {
            ObjectNode viewport = objectMapper.createObjectNode();
            viewport.put("viewModeIndex", doc.viewport().viewModeIndex());
            viewport.put("renderModeIndex", doc.viewport().renderModeIndex());
            viewport.put("gridVisible", doc.viewport().gridVisible());
            viewport.put("axesVisible", doc.viewport().axesVisible());
            viewport.put("unrenderedMode", doc.viewport().unrenderedMode());
            viewport.put("showVertices", doc.viewport().showVertices());
            viewport.put("showGizmo", doc.viewport().showGizmo());
            viewport.put("gridSnappingEnabled", doc.viewport().gridSnappingEnabled());
            viewport.put("gridSnappingIncrement", doc.viewport().gridSnappingIncrement());
            root.set("viewport", viewport);
        }

        if (doc.transform() != null) {
            ObjectNode transform = objectMapper.createObjectNode();
            transform.put("positionX", doc.transform().positionX());
            transform.put("positionY", doc.transform().positionY());
            transform.put("positionZ", doc.transform().positionZ());
            transform.put("rotationX", doc.transform().rotationX());
            transform.put("rotationY", doc.transform().rotationY());
            transform.put("rotationZ", doc.transform().rotationZ());
            transform.put("scaleX", doc.transform().scaleX());
            transform.put("scaleY", doc.transform().scaleY());
            transform.put("scaleZ", doc.transform().scaleZ());
            transform.put("gizmoEnabled", doc.transform().gizmoEnabled());
            root.set("transform", transform);
        }

        if (doc.model() != null) {
            ObjectNode model = objectMapper.createObjectNode();
            if (doc.model().renderingMode() != null) model.put("renderingMode", doc.model().renderingMode());
            if (doc.model().modelName() != null) model.put("modelName", doc.model().modelName());
            if (doc.model().textureVariant() != null) model.put("textureVariant", doc.model().textureVariant());
            if (doc.model().selectedBlockType() != null) model.put("selectedBlockType", doc.model().selectedBlockType());
            if (doc.model().selectedItemType() != null) model.put("selectedItemType", doc.model().selectedItemType());
            if (doc.model().modelSource() != null) model.put("modelSource", doc.model().modelSource());
            if (doc.model().modelFilePath() != null) model.put("modelFilePath", doc.model().modelFilePath());
            root.set("model", model);
        }

        if (doc.ui() != null) {
            ObjectNode ui = objectMapper.createObjectNode();
            ui.put("showModelBrowser", doc.ui().showModelBrowser());
            ui.put("showPropertyPanel", doc.ui().showPropertyPanel());
            ui.put("showToolbar", doc.ui().showToolbar());
            root.set("ui", ui);
        }

        if (doc.parts() != null && !doc.parts().isEmpty()) {
            ArrayNode partsArray = objectMapper.createArrayNode();
            for (OMPFormat.PartData part : doc.parts()) {
                ObjectNode partNode = objectMapper.createObjectNode();
                partNode.put("id", part.id());
                partNode.put("name", part.name());
                partNode.put("originX", part.originX());
                partNode.put("originY", part.originY());
                partNode.put("originZ", part.originZ());
                partNode.put("posX", part.posX());
                partNode.put("posY", part.posY());
                partNode.put("posZ", part.posZ());
                partNode.put("rotX", part.rotX());
                partNode.put("rotY", part.rotY());
                partNode.put("rotZ", part.rotZ());
                partNode.put("scaleX", part.scaleX());
                partNode.put("scaleY", part.scaleY());
                partNode.put("scaleZ", part.scaleZ());
                partNode.put("visible", part.visible());
                partNode.put("locked", part.locked());
                partsArray.add(partNode);
            }
            root.set("parts", partsArray);
        }

        return root;
    }
}
