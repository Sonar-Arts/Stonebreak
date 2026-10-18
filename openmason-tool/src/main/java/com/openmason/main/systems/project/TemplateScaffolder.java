package com.openmason.main.systems.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.api.target.GameTarget;
import com.openmason.engine.format.mesh.ParsedMeshData;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.rendering.model.gmr.parts.PartMeshRebuilder;
import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate;
import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate.TemplateModel;
import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate.TemplatePart;
import com.openmason.main.systems.scripting.commands.ModelCommands;
import com.openmason.main.systems.scripting.doc.HeadlessModelDocument;
import com.openmason.main.systems.scripting.runner.HeadlessOmoWriter;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Writes a template's starter models into a new project folder.
 *
 * <p>Each {@link TemplateModel} becomes a {@code <name>.omo} at the project
 * root, built through the GL-free scripting path so no editor session is needed. Existing
 * files are never overwritten — creating a project into a folder that already holds a
 * {@code Log.omo} keeps the user's work. Models copied from a game asset read it through the
 * project's game target ({@link GameTarget#templates()}, #380).
 */
public final class TemplateScaffolder {

    private static final Logger logger = LoggerFactory.getLogger(TemplateScaffolder.class);

    private TemplateScaffolder() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * @param target the new project's game target (where target-sourced models are read), or null
     * @return the path of the template's open-on-create model, or null when the template
     *         names none (or that model could not be written)
     */
    public static Path scaffold(ProjectTemplate template, Path projectRoot, GameTarget target) {
        if (template == null || projectRoot == null) {
            return null;
        }

        Path openPath = null;
        ObjectMapper mapper = new ObjectMapper();
        for (TemplateModel model : template.getModels()) {
            Path out = projectRoot.resolve(model.name() + ".omo");
            if (!Files.exists(out) && !writeModel(model, out, mapper, target)) {
                continue;
            }
            if (model.name().equals(template.getOpenOnCreate())) {
                openPath = out;
            }
        }
        return openPath;
    }

    private static boolean writeModel(TemplateModel model, Path out, ObjectMapper mapper, GameTarget target) {
        try {
            HeadlessModelDocument doc = new HeadlessModelDocument();
            if (model.targetSbo() != null) {
                if (target == null) {
                    throw new IOException("the project has no game target to read " + model.targetSbo() + " from");
                }
                doc.parts().addPartFromGeometry(model.name(), readTargetMesh(target, model.targetSbo()), new Vector3f());
            } else {
                ModelCommands commands = new ModelCommands(doc, mapper);
                for (TemplatePart part : model.parts()) {
                    commands.createPart(part.shape().name(), part.name(),
                            new Vector3f(part.size()), new Vector3f(part.position()), null, null);
                }
            }
            HeadlessOmoWriter.write(doc, out.toString(), model.name());
            return true;
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not write template model {}: {}", out, e.getMessage());
            return false;
        }
    }

    /** The mesh of the model embedded in {@code target}'s {@code .sbo} at {@code resource}; its materials are ignored. */
    private static PartMeshRebuilder.PartGeometry readTargetMesh(GameTarget target, String resource) throws IOException {
        try (InputStream sbo = target.templates().open(resource)) {
            ZipInputStream zip = new ZipInputStream(sbo);
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (SBOFormat.EMBEDDED_OMO_FILENAME.equals(entry.getName())) {
                    ParsedMeshData mesh = new OMOReader().read(zip).meshData();
                    if (mesh == null) {
                        throw new IOException(resource + " has no mesh");
                    }
                    return PartMeshRebuilder.PartGeometry.of(
                            mesh.vertices(), mesh.texCoords(), mesh.indices(), mesh.triangleToFaceId());
                }
            }
            throw new IOException(resource + " has no " + SBOFormat.EMBEDDED_OMO_FILENAME);
        }
    }
}
