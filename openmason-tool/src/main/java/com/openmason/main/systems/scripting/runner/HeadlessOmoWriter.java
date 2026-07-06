package com.openmason.main.systems.scripting.runner;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.main.systems.rendering.model.factory.BlankModelFactory;
import com.openmason.main.systems.rendering.model.io.omo.OMOSerializer;
import com.openmason.main.systems.rendering.model.io.omo.OmoExportAssembler;
import com.openmason.main.systems.scripting.commands.CommandException;
import com.openmason.main.systems.scripting.doc.ModelDocument;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Writes a {@link ModelDocument} to a self-contained .omo file with no GL
 * context — the GL-free subset of {@code ModelOperationService}'s save flow.
 *
 * <p>{@code OMOSerializer.save} requires the model's texture path to exist on
 * disk; {@link BlankModelFactory#createBlankCube()} supplies a valid temp
 * texture. Material texture PNGs are not written (reading pixels back is a
 * GPU operation); data-level face mappings and material definitions serialize
 * normally and pixels can be authored later in the live tool.
 */
public final class HeadlessOmoWriter {

    private HeadlessOmoWriter() {
    }

    /**
     * @param name display name embedded in the model (null = "Untitled Block")
     * @throws CommandException when the document is empty or the save fails
     */
    public static void write(ModelDocument doc, String filePath, String name) {
        OMOFormat.MeshData meshData = doc.extractMeshData();
        if (meshData == null) {
            throw new CommandException("Nothing to save: the model has no visible geometry",
                    "create at least one visible part before writing");
        }

        BlockModel model;
        try {
            model = new BlankModelFactory().createBlankCube();
        } catch (IOException e) {
            throw new CommandException("Could not create the base texture for saving: "
                    + e.getMessage());
        }
        if (name != null && !name.isBlank()) {
            model.setName(name);
        }

        OMOSerializer serializer = new OMOSerializer();

        OMOFormat.FaceTextureData faceTextureData =
                OmoExportAssembler.extractFaceTextureData(doc.faceTextures());
        if (faceTextureData != null && !faceTextureData.mappings().isEmpty()) {
            serializer.setFaceTextureData(faceTextureData, Map.of());
        }

        List<OMOFormat.PartEntry> partEntries =
                OmoExportAssembler.extractPartEntries(doc.parts(), meshData);
        if (partEntries != null && !partEntries.isEmpty()) {
            serializer.setPartEntries(partEntries);
        }

        if (!serializer.save(model, filePath, meshData)) {
            throw new CommandException("Failed to write " + filePath,
                    "check the directory exists and is writable");
        }
    }
}
