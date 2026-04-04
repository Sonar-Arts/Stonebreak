package com.openmason.main.systems.services;

import com.openmason.main.systems.menus.textureCreator.io.OMTSerializer;
import com.openmason.engine.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.main.systems.rendering.model.factory.BlankModelFactory;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureManager;
import com.openmason.engine.rendering.model.gmr.uv.FaceTextureMapping;
import com.openmason.engine.rendering.model.gmr.uv.MaterialDefinition;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.parts.PartMeshRebuilder;
import com.openmason.engine.rendering.model.gmr.parts.PartTransform;
import com.openmason.main.systems.rendering.model.io.omo.OMODeserializer;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.main.systems.rendering.model.io.omo.OMOSerializer;
import com.openmason.main.systems.rendering.model.miscComponents.OMTTextureLoader;
import com.openmason.main.systems.menus.panes.propertyPane.PropertyPanelImGui;
import com.openmason.main.systems.menus.panes.propertyPane.sections.FaceMaterialSection;
import com.openmason.main.systems.menus.dialogs.FileDialogService;
import com.openmason.main.systems.stateHandling.ModelState;
import com.openmason.main.systems.ViewportController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * Model operation service.
 */
public class ModelOperationService {

    private static final Logger logger = LoggerFactory.getLogger(ModelOperationService.class);

    private final ModelState modelState;
    private final StatusService statusService;
    private final FileDialogService fileDialogService;

    // Editable model support
    private final BlankModelFactory blankModelFactory;
    private final OMOSerializer omoSerializer;
    private final OMODeserializer omoDeserializer;
    private BlockModel currentEditableModel;

    // UI component references
    private ViewportController viewport;
    private PropertyPanelImGui propertiesPanel;

    // Called when a new/different model is loaded to reset dependent editor state
    private Runnable onModelChangedCallback;

    public ModelOperationService(ModelState modelState, StatusService statusService,
                                 FileDialogService fileDialogService) {
        this.modelState = modelState;
        this.statusService = statusService;
        this.fileDialogService = fileDialogService;

        // Initialize .OMO support
        this.blankModelFactory = new BlankModelFactory();
        this.omoSerializer = new OMOSerializer();
        this.omoDeserializer = new OMODeserializer();
        this.currentEditableModel = null;
        this.viewport = null; // Set later via setViewport()
    }

    /**
     * Sets the viewport reference for loading models.
     * Called by MainImGuiInterface after viewport is created.
     *
     * @param viewport the 3D viewport instance
     */
    public void setViewport(ViewportController viewport) {
        this.viewport = viewport;
        logger.debug("Viewport reference set in ModelOperationService");
    }

    /**
     * Sets the properties panel reference for editable models.
     * Called by MainImGuiInterface after properties panel is created.
     *
     * @param propertiesPanel the properties panel instance
     */
    public void setPropertiesPanel(PropertyPanelImGui propertiesPanel) {
        this.propertiesPanel = propertiesPanel;
        logger.debug("Properties panel reference set in ModelOperationService");
    }

    /**
     * Sets a callback invoked whenever the active model changes (new model or
     * open model). Used to reset dependent editor state such as the texture editor.
     */
    public void setOnModelChangedCallback(Runnable callback) {
        this.onModelChangedCallback = callback;
    }

    /**
     * Create new blank cube model.
     * Creates a BlockModel with gray 64x48 cube net texture and displays it in viewport.
     */
    public void newModel() {
        statusService.updateStatus("Creating new blank model...");

        try {
            // Create blank cube model with gray texture
            currentEditableModel = blankModelFactory.createBlankCube();

            // Update state
            modelState.reset();
            modelState.setUnsavedChanges(true); // New models are unsaved
            modelState.setModelSource(ModelState.ModelSource.NEW); // Mark as new model

            // Update statistics (single cube: 1 part, 24 vertices, 12 triangles)
            modelState.updateStatistics(1, 24, 12);

            // Load into viewport if available
            if (viewport != null) {
                viewport.loadModel(currentEditableModel);
                statusService.updateStatus("Blank model created and loaded: " + currentEditableModel.getName());
            } else {
                statusService.updateStatus("Blank model created: " + currentEditableModel.getName() +
                                          " (viewport not available)");
                logger.warn("Viewport not set - model created but not displayed");
            }

            // Update properties panel with new model
            if (propertiesPanel != null) {
                propertiesPanel.setEditableModel(currentEditableModel);
            }

            // Reset dependent editors (texture editor, etc.)
            if (onModelChangedCallback != null) {
                onModelChangedCallback.run();
            }

            logger.info("Created new blank cube model: {}", currentEditableModel.getName());

        } catch (IOException e) {
            logger.error("Failed to create blank model", e);
            statusService.updateStatus("Error creating model: " + e.getMessage());
            currentEditableModel = null;
        }
    }

    /**
     * Open model with file dialog.
     * Delegates to openOMOModel() to avoid code duplication (DRY).
     */
    public void openModel() {
        openOMOModel();
    }

    /**
     * Save current editable model.
     * If model hasn't been saved before, shows save dialog.
     * Otherwise, saves to existing file path.
     */
    public void saveModel() {
        if (currentEditableModel == null) {
            logger.warn("No editable model to save");
            statusService.updateStatus("No model to save");
            return;
        }

        // Check if model has file path (saved before)
        if (currentEditableModel.getFilePath() == null) {
            // First-time save: show save dialog
            saveModelAs();
        } else {
            // Save to existing path
            saveModelToFile(currentEditableModel.getFilePath().toString());
        }
    }

    /**
     * Save current editable model with "Save As" dialog.
     * Always shows save dialog, even if model was saved before.
     */
    public void saveModelAs() {
        if (currentEditableModel == null) {
            logger.warn("No editable model to save as");
            statusService.updateStatus("No model to save");
            return;
        }

        // Show save dialog
        fileDialogService.showSaveOMODialog(this::saveModelToFile);
    }

    /**
     * Internal method to save model to a specific file path.
     * Extracts custom mesh data from viewport (if any) for subdivision support.
     *
     * @param filePath the path to save to
     */
    private void saveModelToFile(String filePath) {
        statusService.updateStatus("Saving model...");

        try {
            // ALWAYS extract mesh data to make .omo files self-contained
            OMOFormat.MeshData meshData = null;
            if (viewport != null) {
                meshData = viewport.extractMeshData();
                if (meshData != null) {
                    logger.info("Saving mesh data: {} vertices, {} triangles, {} faces",
                            meshData.getVertexCount(), meshData.getTriangleCount(),
                            meshData.triangleToFaceId() != null ? "mapped" : "unmapped");
                } else {
                    logger.warn("Failed to extract mesh data - file will not be self-contained");
                }
            }

            // Extract and set face texture data (v1.2+)
            if (viewport != null) {
                GenericModelRenderer renderer = viewport.getModelRenderer();
                if (renderer != null) {
                    FaceTextureManager ftm = renderer.getFaceTextureManager();
                    OMOFormat.FaceTextureData faceTextureData = extractFaceTextureData(ftm);
                    Map<Integer, byte[]> materialPNGs = extractMaterialTexturePNGs(ftm, renderer);
                    if (faceTextureData != null && !faceTextureData.mappings().isEmpty()) {
                        omoSerializer.setFaceTextureData(faceTextureData, materialPNGs);
                        logger.info("Saving face texture data: {} mappings, {} materials",
                                faceTextureData.mappings().size(), faceTextureData.materials().size());
                    }
                }
            }

            // Extract and set part entries (v1.3+)
            if (viewport != null) {
                List<OMOFormat.PartEntry> partEntries = extractPartEntries(viewport);
                if (partEntries != null && !partEntries.isEmpty()) {
                    omoSerializer.setPartEntries(partEntries);
                    logger.info("Saving {} model parts", partEntries.size());
                }
            }

            // Extract and set model-level transform (v1.4+)
            if (viewport != null) {
                com.openmason.main.systems.viewport.state.TransformState ts = viewport.getTransformState();
                OMOFormat.ModelTransform modelTransform = new OMOFormat.ModelTransform(
                        ts.getPositionX(), ts.getPositionY(), ts.getPositionZ(),
                        ts.getRotationX(), ts.getRotationY(), ts.getRotationZ(),
                        ts.getScaleX(), ts.getScaleY(), ts.getScaleZ()
                );
                omoSerializer.setModelTransform(modelTransform);
            }

            // Save with mesh data (required for self-contained .omo files)
            boolean success = omoSerializer.save(currentEditableModel, filePath, meshData);

            if (success) {
                modelState.setUnsavedChanges(false);
                modelState.setCurrentModelPath(currentEditableModel.getName());
                modelState.setCurrentOMOFilePath(filePath);
                modelState.setModelSource(ModelState.ModelSource.OMO_FILE);
                String statusMsg = meshData != null
                        ? "Model saved with mesh data: " + filePath
                        : "Model saved (WARNING: no mesh data): " + filePath;
                statusService.updateStatus(statusMsg);
                logger.info("Saved model to: {} (hasMesh={}, selfContained={})",
                        filePath, meshData != null, meshData != null);
            } else {
                statusService.updateStatus("Failed to save model");
                logger.error("Save operation returned false");
            }

        } catch (Exception e) {
            logger.error("Error saving model", e);
            statusService.updateStatus("Error saving model: " + e.getMessage());
        }
    }

    /**
     * Load recent file by name.
     */
    public void loadRecentFile(String filename) {
        statusService.updateStatus("Loading " + filename + "...");
        modelState.setModelLoaded(true);
        modelState.setCurrentModelPath(filename);
        modelState.setUnsavedChanges(false);
        statusService.updateStatus("Loaded " + filename);
    }

    /**
     * Select model from browser.
     */
    public void selectModel(String modelName, String variant) {
        logger.info("Selected model: {} with variant: {}", modelName, variant);

        modelState.setModelLoaded(true);
        modelState.setCurrentModelPath(modelName);
        modelState.setModelSource(ModelState.ModelSource.BROWSER); // Mark as browser model (read-only)
        modelState.updateStatistics(6, 1248, 624);

        statusService.updateStatus("Model loaded: " + modelName + " (" + variant + " variant)");
    }

    /**
     * Open a .OMO file with file dialog.
     */
    public void openOMOModel() {
        fileDialogService.showOpenOMODialog(this::loadOMOModelFromFile);
    }

    /**
     * Load a .OMO model from a file path (public API for model browser).
     *
     * @param filePath the path to the .OMO file
     */
    public void loadOMOModel(String filePath) {
        loadOMOModelFromFile(filePath);
    }

    /**
     * Load a .OMO model from a specific file path.
     * Loads custom mesh data (if any) for subdivision support.
     *
     * @param filePath the path to load from
     */
    private void loadOMOModelFromFile(String filePath) {
        statusService.updateStatus("Loading .OMO model...");

        try {
            BlockModel loadedModel = omoDeserializer.load(filePath);

            if (loadedModel != null) {
                currentEditableModel = loadedModel;

                // Get mesh data (loaded by deserializer from v2.0 files)
                OMOFormat.MeshData meshData = omoDeserializer.getLastLoadedMeshData();

                // Update state
                modelState.setModelLoaded(true);
                modelState.setCurrentModelPath(loadedModel.getName());
                modelState.setCurrentOMOFilePath(filePath);
                modelState.setUnsavedChanges(false);
                modelState.setModelSource(ModelState.ModelSource.OMO_FILE); // Mark as .OMO file

                // Load into viewport if available
                if (viewport != null) {
                    if (meshData != null && meshData.hasCustomGeometry()) {
                        // Load texture via the content loader
                        viewport.loadModel(currentEditableModel);

                        // Check for multi-part model (v1.3+)
                        List<OMOFormat.PartEntry> partEntries = omoDeserializer.getLastLoadedPartEntries();
                        if (partEntries != null && !partEntries.isEmpty()) {
                            // MULTI-PART: Reconstruct individual parts from combined mesh + part entries
                            restorePartsFromEntries(meshData, partEntries);
                            logger.info("Loaded multi-part .omo: {} parts, {} vertices",
                                    partEntries.size(), meshData.getVertexCount());
                        } else {
                            // SINGLE-PART: Load entire mesh as one root part
                            viewport.loadMeshDataAsPart(meshData, loadedModel.getName());
                            logger.info("Loaded single-part .omo: {} vertices, {} triangles",
                                    meshData.getVertexCount(), meshData.getTriangleCount());
                        }

                        // Restore per-face texture data (v1.2+)
                        OMOFormat.FaceTextureData faceTextureData = omoDeserializer.getLastLoadedFaceTextureData();
                        Map<String, byte[]> materialTextures = omoDeserializer.getLastLoadedMaterialTextures();
                        if (faceTextureData != null && !faceTextureData.mappings().isEmpty()) {
                            restoreFaceTextureData(faceTextureData, materialTextures);
                        }

                        // Update statistics with actual counts
                        int partCount = partEntries != null ? partEntries.size() : 1;
                        modelState.updateStatistics(partCount, meshData.getVertexCount(), meshData.getTriangleCount());
                        statusService.updateStatus("Loaded .OMO model: " + loadedModel.getName());
                    } else {
                        // LEGACY: Old .omo file without mesh data - generate from dimensions
                        logger.warn("Loading legacy .omo file without mesh data - using generation fallback");
                        viewport.loadModel(currentEditableModel);

                        // Legacy cube: 1 part, 24 vertices, 12 triangles
                        modelState.updateStatistics(1, 24, 12);
                        statusService.updateStatus("Loaded legacy .OMO model (generated): " + loadedModel.getName());
                    }
                } else {
                    modelState.updateStatistics(1, 24, 12);
                    statusService.updateStatus("Loaded .OMO model: " + loadedModel.getName() +
                                              " (viewport not available)");
                    logger.warn("Viewport not set - model loaded but not displayed");
                }

                // Restore model-level transform (v1.4+)
                OMOFormat.ModelTransform modelTransform = omoDeserializer.getLastLoadedModelTransform();
                if (modelTransform != null && viewport != null) {
                    com.openmason.main.systems.viewport.state.TransformState ts = viewport.getTransformState();
                    ts.setPosition(modelTransform.posX(), modelTransform.posY(), modelTransform.posZ());
                    ts.setRotation(modelTransform.rotX(), modelTransform.rotY(), modelTransform.rotZ());
                    ts.setScale(modelTransform.scaleX(), modelTransform.scaleY(), modelTransform.scaleZ());
                    logger.debug("Restored model-level transform from .OMO");
                }

                // Update properties panel with loaded model
                if (propertiesPanel != null) {
                    propertiesPanel.setEditableModel(currentEditableModel);
                }

                // Reset dependent editors (texture editor, etc.)
                if (onModelChangedCallback != null) {
                    onModelChangedCallback.run();
                }

                logger.info("Loaded .OMO model from: {} (hasMesh={})", filePath, meshData != null);
            } else {
                statusService.updateStatus("Failed to load .OMO model");
                logger.error("Deserializer returned null");
            }

        } catch (Exception e) {
            logger.error("Error loading .OMO model", e);
            statusService.updateStatus("Error loading model: " + e.getMessage());
        }
    }

    // =========================================================================
    // FACE TEXTURE DATA EXTRACTION (save path)
    // =========================================================================

    /**
     * Extract face texture data from FaceTextureManager for serialization.
     *
     * @param ftm the face texture manager
     * @return face texture data, or null if no non-default mappings exist
     */
    private OMOFormat.FaceTextureData extractFaceTextureData(FaceTextureManager ftm) {
        Collection<FaceTextureMapping> allMappings = ftm.getAllMappings();
        Collection<MaterialDefinition> allMaterials = ftm.getAllMaterials();

        // Build mapping entries (skip default-material full-region mappings that are implicit)
        List<OMOFormat.FaceMappingEntry> mappingEntries = new ArrayList<>();
        for (FaceTextureMapping mapping : allMappings) {
            if (mapping.materialId() == MaterialDefinition.DEFAULT.materialId()
                    && mapping.uvRegion().equals(FaceTextureMapping.FULL_REGION)) {
                continue; // Skip implicit default mappings
            }
            mappingEntries.add(new OMOFormat.FaceMappingEntry(
                    mapping.faceId(),
                    mapping.materialId(),
                    mapping.uvRegion().u0(), mapping.uvRegion().v0(),
                    mapping.uvRegion().u1(), mapping.uvRegion().v1(),
                    mapping.uvRotation().degrees()
            ));
        }

        if (mappingEntries.isEmpty()) {
            return null; // No non-default mappings to save
        }

        // Build material entries (exclude default material)
        List<OMOFormat.MaterialEntry> materialEntries = new ArrayList<>();
        for (MaterialDefinition mat : allMaterials) {
            if (mat.materialId() == MaterialDefinition.DEFAULT.materialId()) {
                continue;
            }
            String textureFile = "material_" + mat.materialId() + ".png";
            materialEntries.add(new OMOFormat.MaterialEntry(
                    mat.materialId(),
                    mat.name(),
                    textureFile,
                    mat.renderLayer().name(),
                    mat.properties().emissive(),
                    mat.properties().tintColor()
            ));
        }

        return new OMOFormat.FaceTextureData(mappingEntries, materialEntries);
    }

    /**
     * Read GPU textures for each non-default material and encode as PNGs.
     *
     * @param ftm      the face texture manager
     * @param renderer the model renderer (for GPU texture readback)
     * @return map of materialId → PNG bytes
     */
    private Map<Integer, byte[]> extractMaterialTexturePNGs(FaceTextureManager ftm,
                                                             GenericModelRenderer renderer) {
        Map<Integer, byte[]> result = new HashMap<>();

        for (MaterialDefinition mat : ftm.getAllMaterials()) {
            if (mat.materialId() == MaterialDefinition.DEFAULT.materialId()) {
                continue;
            }
            if (mat.textureId() <= 0) {
                continue;
            }

            int[] dims = renderer.getTextureDimensions(mat.textureId());
            if (dims == null) {
                logger.warn("Could not read dimensions for material {} texture {}", mat.materialId(), mat.textureId());
                continue;
            }

            byte[] pixels = renderer.readTexturePixels(mat.textureId());
            if (pixels == null) {
                logger.warn("Could not read pixels for material {} texture {}", mat.materialId(), mat.textureId());
                continue;
            }

            byte[] png = OMTSerializer.encodeRGBAToPNG(pixels, dims[0], dims[1]);
            if (png != null) {
                result.put(mat.materialId(), png);
            }
        }

        return result;
    }

    // =========================================================================
    // PART RESTORATION (load path)
    // =========================================================================

    /**
     * Reconstruct individual parts from combined mesh data and part entries.
     * Slices the combined vertex/index/face arrays by each part's mesh range
     * and registers them in the ModelPartManager.
     */
    private void restorePartsFromEntries(OMOFormat.MeshData meshData, List<OMOFormat.PartEntry> entries) {
        if (viewport == null) {
            return;
        }

        ModelPartManager partManager = viewport.getPartManager();
        partManager.clear();

        float[] allVertices = meshData.vertices();
        float[] allTexCoords = meshData.texCoords();
        int[] allIndices = meshData.indices();
        int[] allTriToFace = meshData.triangleToFaceId();

        for (OMOFormat.PartEntry entry : entries) {
            // Slice vertices for this part
            int vStart = entry.vertexStart() * 3;
            int vLen = entry.vertexCount() * 3;
            float[] partVertices = new float[vLen];
            System.arraycopy(allVertices, vStart, partVertices, 0, vLen);

            // Slice tex coords
            float[] partTexCoords = null;
            if (allTexCoords != null) {
                int tStart = entry.vertexStart() * 2;
                int tLen = entry.vertexCount() * 2;
                partTexCoords = new float[tLen];
                System.arraycopy(allTexCoords, tStart, partTexCoords, 0, tLen);
            }

            // Slice indices and rebase to local vertex indices
            int iLen = entry.indexCount();
            int[] partIndices = new int[iLen];
            System.arraycopy(allIndices, entry.indexStart(), partIndices, 0, iLen);
            for (int i = 0; i < iLen; i++) {
                partIndices[i] -= entry.vertexStart(); // Rebase to part-local
            }

            // Slice triangle-to-face mapping and rebase face IDs
            int triCount = iLen / 3;
            int[] partTriToFace = null;
            if (allTriToFace != null) {
                int triStart = entry.indexStart() / 3;
                partTriToFace = new int[triCount];
                for (int i = 0; i < triCount; i++) {
                    partTriToFace[i] = allTriToFace[triStart + i] - entry.faceStart();
                }
            }

            // The combined mesh bakes part transforms into vertex positions
            // (see PartMeshRebuilder.rebuild). Un-transform sliced vertices back
            // to local space so that addPartFromGeometry + setPartTransform doesn't
            // double-apply the transform during rebuildCombinedMesh.
            PartTransform savedTransform = new PartTransform(
                    new org.joml.Vector3f(entry.originX(), entry.originY(), entry.originZ()),
                    new org.joml.Vector3f(entry.posX(), entry.posY(), entry.posZ()),
                    new org.joml.Vector3f(entry.rotX(), entry.rotY(), entry.rotZ()),
                    new org.joml.Vector3f(entry.scaleX(), entry.scaleY(), entry.scaleZ())
            );

            if (!savedTransform.isIdentity()) {
                org.joml.Matrix4f inverseTransform = savedTransform.toMatrix().invert();
                org.joml.Vector4f v = new org.joml.Vector4f();
                for (int i = 0; i < partVertices.length / 3; i++) {
                    int idx = i * 3;
                    v.set(partVertices[idx], partVertices[idx + 1], partVertices[idx + 2], 1.0f);
                    inverseTransform.transform(v);
                    partVertices[idx] = v.x;
                    partVertices[idx + 1] = v.y;
                    partVertices[idx + 2] = v.z;
                }
            }

            // Build part geometry (now in local space)
            PartMeshRebuilder.PartGeometry geo = PartMeshRebuilder.PartGeometry.of(
                    partVertices, partTexCoords, partIndices, partTriToFace
            );

            // Add part with local-space geometry
            org.joml.Vector3f origin = new org.joml.Vector3f(entry.originX(), entry.originY(), entry.originZ());
            ModelPartDescriptor part = partManager.addPartFromGeometry(entry.name(), geo, origin);

            // Restore transform (position, rotation, scale) — rebuildCombinedMesh
            // will re-apply this to the local-space vertices
            if (part != null) {
                partManager.setPartTransform(part.id(), savedTransform);
                partManager.setPartVisible(part.id(), entry.visible());
                partManager.setPartLocked(part.id(), entry.locked());
            }
        }

        logger.info("Restored {} parts from .OMO file", entries.size());
    }

    // =========================================================================
    // PART ENTRY EXTRACTION (save path)
    // =========================================================================

    /**
     * Extract part entries from the ModelPartManager for serialization.
     * Each entry includes the part's transform, mesh range, and per-part geometry.
     */
    private List<OMOFormat.PartEntry> extractPartEntries(ViewportController viewport) {
        ModelPartManager partManager = viewport.getPartManager();
        if (partManager == null || partManager.getPartCount() <= 1) {
            return null; // Single-part models don't need explicit part entries
        }

        List<OMOFormat.PartEntry> entries = new ArrayList<>();
        for (ModelPartDescriptor part : partManager.getAllParts()) {
            PartTransform t = part.transform();
            com.openmason.engine.rendering.model.gmr.parts.MeshRange range = part.meshRange();

            entries.add(new OMOFormat.PartEntry(
                    part.id(), part.name(),
                    t.origin().x, t.origin().y, t.origin().z,
                    t.position().x, t.position().y, t.position().z,
                    t.rotation().x, t.rotation().y, t.rotation().z,
                    t.scale().x, t.scale().y, t.scale().z,
                    range != null ? range.vertexStart() : 0,
                    range != null ? range.vertexCount() : 0,
                    range != null ? range.indexStart() : 0,
                    range != null ? range.indexCount() : 0,
                    range != null ? range.faceStart() : 0,
                    range != null ? range.faceCount() : 0,
                    part.visible(), part.locked()
            ));
        }

        return entries;
    }

    // =========================================================================
    // FACE TEXTURE DATA RESTORATION (load path)
    // =========================================================================

    /**
     * Restore per-face texture data from a loaded OMO file.
     * Uploads material textures to GPU, registers materials, and restores face mappings.
     *
     * @param faceTextureData  the loaded face texture metadata
     * @param materialTextures map of ZIP entry names to PNG bytes
     */
    private void restoreFaceTextureData(OMOFormat.FaceTextureData faceTextureData,
                                         Map<String, byte[]> materialTextures) {
        if (viewport == null) {
            return;
        }

        GenericModelRenderer renderer = viewport.getModelRenderer();
        if (renderer == null) {
            return;
        }

        FaceTextureManager ftm = renderer.getFaceTextureManager();
        OMTTextureLoader textureLoader = new OMTTextureLoader();

        // Upload material textures and register materials
        for (OMOFormat.MaterialEntry matEntry : faceTextureData.materials()) {
            int gpuTextureId = 0;

            // Load the material's PNG from the ZIP entries
            if (materialTextures != null) {
                byte[] pngBytes = materialTextures.get(matEntry.textureFile());
                if (pngBytes != null) {
                    gpuTextureId = textureLoader.loadPNGAsTexture(pngBytes);
                }
            }

            if (gpuTextureId <= 0) {
                logger.warn("Failed to load texture for material {} ({})", matEntry.materialId(), matEntry.textureFile());
                continue;
            }

            MaterialDefinition.RenderLayer renderLayer;
            try {
                renderLayer = MaterialDefinition.RenderLayer.valueOf(matEntry.renderLayer());
            } catch (IllegalArgumentException e) {
                renderLayer = MaterialDefinition.RenderLayer.OPAQUE;
            }

            MaterialDefinition material = new MaterialDefinition(
                    matEntry.materialId(),
                    matEntry.name(),
                    gpuTextureId,
                    renderLayer,
                    new MaterialDefinition.MaterialProperties(matEntry.emissive(), matEntry.tintColor())
            );
            ftm.registerMaterial(material);
            logger.debug("Restored material {} '{}' (textureId={})", matEntry.materialId(), matEntry.name(), gpuTextureId);
        }

        // Restore face mappings
        for (OMOFormat.FaceMappingEntry mapEntry : faceTextureData.mappings()) {
            FaceTextureMapping.UVRegion uvRegion = new FaceTextureMapping.UVRegion(
                    mapEntry.u0(), mapEntry.v0(), mapEntry.u1(), mapEntry.v1());
            FaceTextureMapping.UVRotation uvRotation = FaceTextureMapping.UVRotation.fromDegrees(
                    mapEntry.uvRotationDegrees());
            FaceTextureMapping mapping = new FaceTextureMapping(
                    mapEntry.faceId(), mapEntry.materialId(), uvRegion, uvRotation);
            ftm.setFaceMapping(mapping);
        }

        // Synchronize the material ID counter so new user-created materials don't collide
        int maxLoadedId = faceTextureData.materials().stream()
                .mapToInt(OMOFormat.MaterialEntry::materialId)
                .max()
                .orElse(0);
        FaceMaterialSection.syncNextMaterialId(maxLoadedId);

        // Refresh rendering state
        renderer.markDrawBatchesDirty();
        renderer.refreshUVs();

        logger.info("Restored face texture data: {} mappings, {} materials",
                faceTextureData.mappings().size(), faceTextureData.materials().size());
    }
}
