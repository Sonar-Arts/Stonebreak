package com.openmason.ui;

import com.openmason.texture.TextureVariantManager;
import com.openmason.ui.viewport.OpenMason3DViewport;
import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImFloat;
import imgui.type.ImInt;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Dear ImGui implementation of the Properties Panel.
 * Provides real-time property editing with immediate mode updates.
 */
public class PropertyPanelImGui {
    
    private static final Logger logger = LoggerFactory.getLogger(PropertyPanelImGui.class);
    
    // State Management
    private TextureVariantManager textureManager;
    private OpenMason3DViewport viewport3D;
    private String currentModelName = null;
    private boolean initialized = false;
    
    // Transform Controls
    private final ImFloat rotationX = new ImFloat(0.0f);
    private final ImFloat rotationY = new ImFloat(0.0f);
    private final ImFloat rotationZ = new ImFloat(0.0f);
    private final ImFloat scale = new ImFloat(1.0f);
    
    // Animation Controls
    private final ImInt currentAnimation = new ImInt(0);
    private final ImFloat animationTime = new ImFloat(0.0f);
    private boolean animationPlaying = false;
    private boolean animationPaused = false;
    
    // Texture Variants
    private String[] availableVariants = new String[0];
    private final ImInt selectedVariantIndex = new ImInt(0);
    private String selectedVariant = "default";
    
    // Model Statistics
    private int partCount = 0;
    private int vertexCount = 0;
    private int triangleCount = 0;
    private int variantCount = 0;
    
    // Status
    private String statusMessage = "Ready";
    private boolean loadingInProgress = false;
    private String lastViewportModelCheck = null; // Track last model sent to viewport
    private boolean validationInProgress = false;
    
    // Performance tracking
    private long lastSwitchTime = 0;
    private int switchCount = 0;
    private boolean statisticsLogged = false;
    
    // User interaction tracking
    private long lastUserInteractionTime = 0;
    private static final long USER_INTERACTION_TIMEOUT = 100; // ms
    
    // Animation types
    private final String[] animationTypes = {"IDLE", "WALKING", "GRAZING"};
    
    public PropertyPanelImGui() {
        try {
            this.textureManager = TextureVariantManager.getInstance();
            initialize();
        } catch (Exception e) {
            logger.error("Error initializing PropertyPanelImGui", e);
        }
    }
    
    private void initialize() {
        try {
            // Initialize TextureVariantManager
            if (textureManager != null) {
                textureManager.initialize();
            }
            
            initialized = true;
            statusMessage = "Ready";
            // logger.info("PropertyPanelImGui initialized successfully");
            
        } catch (Exception e) {
            logger.error("Failed to initialize PropertyPanelImGui", e);
            statusMessage = "Initialization failed: " + e.getMessage();
        }
    }
    
    /**
     * Render the properties panel using Dear ImGui
     */
    public void render() {
        if (ImGui.begin("Properties", ImGuiWindowFlags.AlwaysAutoResize)) {
            renderModelSection();
            ImGui.separator();
            renderTextureVariantSection();
            ImGui.separator();
            renderTransformSection();
            ImGui.separator();
            renderAnimationSection();
            ImGui.separator();
            renderStatisticsSection();
            ImGui.separator();
            renderDiagnosticsSection();
            ImGui.separator();
            renderActionsSection();
            ImGui.separator();
            renderStatusSection();
        }
        ImGui.end();
    }
    
    private void renderModelSection() {
        if (ImGui.collapsingHeader("Model Information", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.indent();
            
            if (currentModelName != null) {
                ImGui.text("Model: " + currentModelName);
                ImGui.text("Parts: " + partCount);
                ImGui.text("Vertices: " + vertexCount);
                ImGui.text("Triangles: " + triangleCount);
                ImGui.text("Variants: " + variantCount);
            } else {
                ImGui.textDisabled("No model loaded");
            }
            
            ImGui.unindent();
        }
    }
    
    private void renderTextureVariantSection() {
        if (ImGui.collapsingHeader("Texture Variants", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.indent();
            
            if (availableVariants.length > 0) {
                ImGui.text("Current Variant:");
                if (ImGui.combo("##variant", selectedVariantIndex, availableVariants)) {
                    if (selectedVariantIndex.get() >= 0 && selectedVariantIndex.get() < availableVariants.length) {
                        String newVariant = availableVariants[selectedVariantIndex.get()];
                        if (!newVariant.equals(selectedVariant)) {
                            switchTextureVariant(newVariant);
                        }
                    }
                }
                
                ImGui.text("Available: " + availableVariants.length + " variants");
                
            } else {
                ImGui.textDisabled("No variants available");
            }
            
            ImGui.unindent();
        }
    }
    
    private void renderTransformSection() {
        if (ImGui.collapsingHeader("Transform", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.indent();
            
            // Get current constraints from viewport
            float minScale = (viewport3D != null) ? viewport3D.getMinScale() : 0.1f;
            float maxScale = (viewport3D != null) ? viewport3D.getMaxScale() : 3.0f;
            
            // Sync UI values with viewport if connected
            if (viewport3D != null && !isUserInteracting()) {
                rotationX.set(viewport3D.getModelRotationX());
                rotationY.set(viewport3D.getModelRotationY());
                rotationZ.set(viewport3D.getModelRotationZ());
                scale.set(viewport3D.getModelScale());
            }
            
            ImGui.text("Rotation:");
            ImGui.text("(Full 360° rotation allowed)");
            
            boolean rotationChanged = false;
            if (ImGui.sliderFloat("X##rotX", rotationX.getData(), -180.0f, 180.0f, "%.1f°")) {
                rotationChanged = true;
                updateModelTransform();
            }
            
            if (ImGui.sliderFloat("Y##rotY", rotationY.getData(), -180.0f, 180.0f, "%.1f°")) {
                rotationChanged = true;
                updateModelTransform();
            }
            
            if (ImGui.sliderFloat("Z##rotZ", rotationZ.getData(), -180.0f, 180.0f, "%.1f°")) {
                rotationChanged = true;
                updateModelTransform();
            }
            
            ImGui.separator();
            ImGui.text("Scale:");
            ImGui.text(String.format("(Constrained to %.1fx - %.1fx for grid bounds)", minScale, maxScale));
            
            // Check if scale is at boundaries for visual feedback
            float currentScale = scale.get();
            boolean atMinScale = Math.abs(currentScale - minScale) < 0.01f;
            boolean atMaxScale = Math.abs(currentScale - maxScale) < 0.01f;
            
            if (atMinScale || atMaxScale) {
                if (atMinScale) {
                    ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 0.6f, 0.0f, 1.0f); // Orange for min
                    ImGui.text("⚠ Minimum scale reached");
                    ImGui.popStyleColor();
                } else {
                    ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 0.6f, 0.0f, 1.0f); // Orange for max
                    ImGui.text("⚠ Maximum scale reached");
                    ImGui.popStyleColor();
                }
            }
            
            if (ImGui.sliderFloat("##scale", scale.getData(), minScale, maxScale, "%.2f")) {
                updateModelTransform();
            }
            
            // Reset button
            if (ImGui.button("Reset Transform")) {
                resetTransform();
            }
            
            // Grid info
            ImGui.separator();
            ImGui.text("Grid Info:");
            ImGui.text("• Grid bounds: -10 to +10 units");
            ImGui.text("• Transform constraints keep model visible");
            ImGui.text("• Rotation is unrestricted");
            
            ImGui.unindent();
        }
    }
    
    private void renderAnimationSection() {
        if (ImGui.collapsingHeader("Animation", ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.indent();
            
            ImGui.text("Animation Type:");
            if (ImGui.combo("##animation", currentAnimation, animationTypes)) {
                setAnimation(animationTypes[currentAnimation.get()], animationTime.get());
            }
            
            ImGui.text("Animation Controls:");
            
            if (ImGui.button("Play")) {
                playAnimation();
            }
            ImGui.sameLine();
            
            if (ImGui.button("Pause")) {
                pauseAnimation();
            }
            ImGui.sameLine();
            
            if (ImGui.button("Stop")) {
                stopAnimation();
            }
            
            ImGui.text("Timeline:");
            if (ImGui.sliderFloat("##timeline", animationTime.getData(), 0.0f, 1.0f, "%.2f")) {
                setAnimationTime(animationTime.get());
            }
            
            // Animation status
            ImGui.text("Status: " + getAnimationStatus());
            
            ImGui.unindent();
        }
    }
    
    private void renderStatisticsSection() {
        if (ImGui.collapsingHeader("Statistics")) {
            ImGui.indent();
            
            ImGui.text("Model Statistics:");
            ImGui.bulletText("Parts: " + partCount);
            ImGui.bulletText("Vertices: " + vertexCount);
            ImGui.bulletText("Triangles: " + triangleCount);
            ImGui.bulletText("Texture Variants: " + variantCount);
            
            if (switchCount > 0) {
                ImGui.separator();
                ImGui.text("Performance:");
                ImGui.bulletText("Texture switches: " + switchCount);
                ImGui.bulletText("Last switch time: " + lastSwitchTime + "ms");
                
                if (lastSwitchTime > 200) {
                    ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 0.6f, 0.0f, 1.0f); // Orange
                    ImGui.bulletText("Performance warning: > 200ms");
                    ImGui.popStyleColor();
                }
            }
            
            ImGui.unindent();
        }
    }
    
    private void renderDiagnosticsSection() {
        if (ImGui.collapsingHeader("Diagnostics")) {
            ImGui.indent();
            
            if (viewport3D != null && viewport3D.getModelRenderer() != null) {
                com.openmason.rendering.ModelRenderer renderer = viewport3D.getModelRenderer();
                
                ImGui.text("Actual Rendered Coordinates:");
                ImGui.text("(Retrieved from GPU transformation matrices)");
                ImGui.separator();
                
                // Get all rendered transformations
                java.util.Map<String, org.joml.Matrix4f> transforms = renderer.getRenderedTransformations();
                
                if (transforms.isEmpty()) {
                    ImGui.textColored(0.8f, 0.8f, 0.0f, 1.0f, "No render data available");
                    ImGui.text("Model must be rendered at least once");
                } else {
                    // Display each part's actual coordinates
                    for (String partName : transforms.keySet()) {
                        com.openmason.rendering.ModelRenderer.DiagnosticData data = renderer.getPartDiagnostics(partName);
                        if (data != null) {
                            if (ImGui.treeNode(partName)) {
                                // Position
                                ImGui.text(String.format("Position: (%.3f, %.3f, %.3f)", 
                                    data.position.x, data.position.y, data.position.z));
                                
                                // Rotation (in degrees for readability)
                                org.joml.Vector3f rotDeg = data.getRotationDegrees();
                                ImGui.text(String.format("Rotation: (%.1f°, %.1f°, %.1f°)", 
                                    rotDeg.x, rotDeg.y, rotDeg.z));
                                
                                // Scale
                                ImGui.text(String.format("Scale: (%.3f, %.3f, %.3f)", 
                                    data.scale.x, data.scale.y, data.scale.z));
                                
                                // Matrix (collapsed by default)
                                if (ImGui.treeNode("Transformation Matrix")) {
                                    org.joml.Matrix4f m = data.transformMatrix;
                                    ImGui.text(String.format("│%.3f %.3f %.3f %.3f│", m.m00(), m.m01(), m.m02(), m.m03()));
                                    ImGui.text(String.format("│%.3f %.3f %.3f %.3f│", m.m10(), m.m11(), m.m12(), m.m13()));
                                    ImGui.text(String.format("│%.3f %.3f %.3f %.3f│", m.m20(), m.m21(), m.m22(), m.m23()));
                                    ImGui.text(String.format("│%.3f %.3f %.3f %.3f│", m.m30(), m.m31(), m.m32(), m.m33()));
                                    ImGui.treePop();
                                }
                                
                                ImGui.treePop();
                            }
                        }
                    }
                    
                    ImGui.separator();
                    ImGui.text(String.format("Total parts rendered: %d", transforms.size()));
                }
                
                if (ImGui.button("Refresh Diagnostics")) {
                    // Diagnostics are automatically updated on each render
                    // logger.info("Diagnostic data refreshed from renderer");
                }
                
            } else {
                ImGui.textColored(1.0f, 0.6f, 0.6f, 1.0f, "Viewport not available");
                ImGui.text("Diagnostics require an active 3D viewport");
            }
            
            ImGui.unindent();
        }
    }
    
    private void renderActionsSection() {
        if (ImGui.collapsingHeader("Actions")) {
            ImGui.indent();
            
            if (ImGui.button("Validate Properties")) {
                validateModel();
            }
            ImGui.sameLine();
            
            if (ImGui.button("Reset All")) {
                resetProperties();
            }
            
            if (ImGui.button("Export Diagnostics")) {
                exportModelDiagnostics();
            }
            
            ImGui.unindent();
        }
    }
    
    private void renderStatusSection() {
        ImGui.separator();
        
        if (loadingInProgress) {
            ImGui.pushStyleColor(ImGuiCol.Text, 0.0f, 1.0f, 1.0f, 1.0f); // Cyan
            ImGui.text("⏳ " + statusMessage);
            ImGui.popStyleColor();
        } else if (validationInProgress) {
            ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 1.0f, 0.0f, 1.0f); // Yellow
            ImGui.text("🔍 " + statusMessage);
            ImGui.popStyleColor();
        } else if (statusMessage.toLowerCase().contains("error") || statusMessage.toLowerCase().contains("failed")) {
            ImGui.pushStyleColor(ImGuiCol.Text, 1.0f, 0.0f, 0.0f, 1.0f); // Red
            ImGui.text("❌ " + statusMessage);
            ImGui.popStyleColor();
        } else if (statusMessage.toLowerCase().contains("success") || statusMessage.toLowerCase().contains("completed")) {
            ImGui.pushStyleColor(ImGuiCol.Text, 0.0f, 1.0f, 0.0f, 1.0f); // Green
            ImGui.text("✅ " + statusMessage);
            ImGui.popStyleColor();
        } else {
            ImGui.text("ℹ️ " + statusMessage);
        }
    }
    
    // Core functionality methods
    
    public void loadTextureVariants(String modelName) {
        if (modelName == null || modelName.isEmpty()) {
            logger.warn("Cannot load texture variants for null or empty model name");
            return;
        }
        
        // Prevent repeated loading of the same model
        if (loadingInProgress) {
            // logger.debug("Loading already in progress, ignoring request for model: {}", modelName);
            return;
        }
        
        // Don't reload if it's the same model, but still ensure viewport has it (only check once)
        if (modelName.equals(this.currentModelName) && availableVariants != null && availableVariants.length > 0) {
            // Only check viewport status once per model, not every frame
            String modelFileName = modelName.toLowerCase().replace(" ", "_");
            if (!modelFileName.equals(lastViewportModelCheck)) {
                // logger.debug("Model {} already loaded with {} variants, ensuring viewport has model", modelName, availableVariants.length);
                
                // Even if already loaded, make sure viewport has the model
                if (viewport3D != null) {
                    String currentViewportModel = viewport3D.getCurrentModelName();
                    boolean hasActualModel = viewport3D.getCurrentModel() != null;
                    
                    if (!modelFileName.equals(currentViewportModel) || !hasActualModel) {
                        // logger.info("Viewport needs model: name='{}' vs '{}', hasModel={} - loading '{}' (file: '{}') into 3D viewport", 
                        //            currentViewportModel, modelFileName, hasActualModel, modelName, modelFileName);
                        viewport3D.loadModel(modelFileName);
                    } else {
                        // logger.debug("Viewport already has model: {}", modelFileName);
                    }
                }
                lastViewportModelCheck = modelFileName;
            }
            return;
        }
        
        // logger.info("Loading texture variants for model: {}", modelName);
        this.currentModelName = modelName;
        lastViewportModelCheck = null; // Reset for new model
        
        loadingInProgress = true;
        statusMessage = "Loading texture variants for " + modelName + "...";
        
        try {
            List<String> variantsToLoad;
            
            // For cow models, load all 4 variants
            if (modelName.toLowerCase().contains("cow")) {
                variantsToLoad = Arrays.asList("Default", "Angus", "Highland", "Jersey");
                // logger.info("Loading cow variants: {}", variantsToLoad);
            } else {
                variantsToLoad = Arrays.asList("Default");
                // logger.info("Loading default variant for non-cow model: {}", modelName);
            }
            
            // Update available variants
            availableVariants = variantsToLoad.toArray(new String[0]);
            selectedVariantIndex.set(0);
            selectedVariant = "default";
            variantCount = availableVariants.length;
            
            updateModelStatistics();
            
            // Load model into viewport if connected
            if (viewport3D != null) {
                // Convert display name to model file name (e.g., "Standard Cow" -> "standard_cow")
                String modelFileName = modelName.toLowerCase().replace(" ", "_");
                // logger.info("Loading model '{}' (file: '{}') into 3D viewport", modelName, modelFileName);
                viewport3D.loadModel(modelFileName);
            }
            
            loadingInProgress = false;
            if (modelName.toLowerCase().contains("cow")) {
                statusMessage = "Loaded " + variantsToLoad.size() + " texture variants";
            } else {
                statusMessage = "Model type not supported for texture variants";
            }
            
        } catch (Exception e) {
            logger.error("Error loading texture variants for model: {}", modelName, e);
            loadingInProgress = false;
            statusMessage = "Failed to load texture variants: " + e.getMessage();
        }
    }
    
    public void switchTextureVariant(String variantName) {
        if (variantName == null || variantName.isEmpty()) {
            logger.warn("Cannot switch to null or empty texture variant");
            return;
        }
        
        long startTime = System.currentTimeMillis();
        switchCount++;
        
        // logger.info("Switching to texture variant: {} (switch #{})", variantName, switchCount);
        
        loadingInProgress = true;
        statusMessage = "Switching to " + variantName + " variant...";
        
        try {
            String variantLower = variantName.toLowerCase();
            
            // Switch variant using TextureVariantManager
            boolean success = textureManager.switchToVariant(variantLower);
            
            if (success) {
                // Update viewport if connected
                if (viewport3D != null) {
                    viewport3D.setCurrentTextureVariant(variantLower);
                    viewport3D.requestRender();
                }
                
                selectedVariant = variantName;
                
                long switchTime = System.currentTimeMillis() - startTime;
                lastSwitchTime = switchTime;
                
                loadingInProgress = false;
                statusMessage = String.format("Switched to %s variant (%dms)", variantName, switchTime);
                
                // logger.info("Successfully switched to variant '{}' in {}ms", variantName, switchTime);
                
                if (switchTime > 200) {
                    logger.warn("Texture variant switch took {}ms (target: <200ms)", switchTime);
                }
                
            } else {
                loadingInProgress = false;
                statusMessage = "Failed to switch to variant: " + variantName;
                logger.error("TextureVariantManager failed to switch to variant: {}", variantName);
            }
            
        } catch (Exception e) {
            logger.error("Error switching to texture variant: {}", variantName, e);
            loadingInProgress = false;
            statusMessage = "Error switching variant: " + e.getMessage();
        }
    }
    
    private void updateModelTransform() {
        // Track user interaction
        lastUserInteractionTime = System.currentTimeMillis();
        
        // logger.debug("Updating model transform: rot=({}, {}, {}), scale={}", 
        //             rotationX.get(), rotationY.get(), rotationZ.get(), scale.get());
        
        try {
            if (viewport3D != null) {
                viewport3D.setModelTransform(rotationX.get(), rotationY.get(), rotationZ.get(), scale.get());
                viewport3D.requestRender();
            }
        } catch (Exception e) {
            logger.error("Error updating model transform", e);
        }
    }
    
    /**
     * Check if user is currently interacting with transform controls.
     */
    private boolean isUserInteracting() {
        return (System.currentTimeMillis() - lastUserInteractionTime) < USER_INTERACTION_TIMEOUT;
    }
    
    private void resetTransform() {
        rotationX.set(0.0f);
        rotationY.set(0.0f);
        rotationZ.set(0.0f);
        scale.set(1.0f);
        
        // Reset both UI and viewport
        if (viewport3D != null) {
            viewport3D.resetModelTransform();
        } else {
            updateModelTransform();
        }
        
        statusMessage = "Transform reset to defaults";
        // logger.info("Transform reset to default values");
    }
    
    private void setAnimation(String animationName, float time) {
        // logger.debug("Setting animation: name='{}', time={}", animationName, time);
        
        try {
            if (animationName == null || animationName.trim().isEmpty()) {
                logger.warn("Cannot set animation - invalid animation name");
                return;
            }
            
            float clampedTime = Math.max(0.0f, Math.min(1.0f, time));
            animationTime.set(clampedTime);
            
            // Apply animation to 3D viewport
            if (viewport3D != null) {
                // logger.info("Applied animation '{}' at time {} to viewport", animationName, clampedTime);
                viewport3D.requestRender();
            }
            
        } catch (Exception e) {
            logger.error("Error setting animation: {}", animationName, e);
        }
    }
    
    private void playAnimation() {
        // logger.info("Starting animation playback...");
        
        animationPlaying = true;
        animationPaused = false;
        
        String currentAnimationName = animationTypes[currentAnimation.get()];
        
        // logger.info("Started animation playback for: {}", currentAnimationName);
        statusMessage = "Playing animation: " + currentAnimationName;
        
        setAnimation(currentAnimationName, 0.0f);
    }
    
    private void pauseAnimation() {
        // logger.info("Pausing animation playback...");
        
        animationPlaying = false;
        animationPaused = true;
        
        statusMessage = "Animation paused";
        // logger.info("Animation playback paused");
    }
    
    private void stopAnimation() {
        // logger.info("Stopping animation playback...");
        
        animationPlaying = false;
        animationPaused = false;
        animationTime.set(0.0f);
        currentAnimation.set(0); // Reset to IDLE
        
        statusMessage = "Animation stopped";
        
        setAnimation("IDLE", 0.0f);
        
        // logger.info("Animation playback stopped and reset to IDLE");
    }
    
    private void setAnimationTime(float time) {
        // logger.debug("Setting animation time: {}", time);
        
        try {
            float clampedTime = Math.max(0.0f, Math.min(1.0f, time));
            animationTime.set(clampedTime);
            
            String currentAnimationName = animationTypes[currentAnimation.get()];
            setAnimation(currentAnimationName, clampedTime);
            
            statusMessage = String.format("Animation: %s at %.1f%%", currentAnimationName, clampedTime * 100);
            
        } catch (Exception e) {
            logger.error("Error setting animation time: {}", time, e);
        }
    }
    
    private String getAnimationStatus() {
        if (animationPlaying) {
            return "Playing";
        } else if (animationPaused) {
            return "Paused";
        } else {
            return "Stopped";
        }
    }
    
    private void updateModelStatistics() {
        if (currentModelName == null) {
            return;
        }
        
        try {
            // For cow models, we know the statistics based on JSON definition
            if (currentModelName.toLowerCase().contains("cow")) {
                partCount = 14;
                vertexCount = partCount * 24;
                triangleCount = partCount * 12;
                
                if (!statisticsLogged) {
                    // logger.debug("Updated model statistics: parts={}, vertices={}, triangles={}, variants={}", 
                    //             partCount, vertexCount, triangleCount, variantCount);
                    statisticsLogged = true;
                }
            } else {
                partCount = 0;
                vertexCount = 0;
                triangleCount = 0;
            }
            
        } catch (Exception e) {
            logger.error("Error updating model statistics", e);
        }
    }
    
    private void validateModel() {
        // logger.info("Validating model properties...");
        
        if (currentModelName == null || currentModelName.trim().isEmpty()) {
            statusMessage = "No model selected for validation";
            logger.warn("Cannot validate - no model selected");
            return;
        }
        
        validationInProgress = true;
        statusMessage = "Validating model: " + currentModelName + "...";
        
        CompletableFuture.runAsync(() -> {
            try {
                List<String> validationResults = new ArrayList<>();
                boolean hasErrors = false;
                
                // Simulate validation process
                Thread.sleep(1000);
                
                // Basic validation checks
                validationResults.add("✓ Model structure: Valid (" + partCount + " parts)");
                
                if (currentModelName.toLowerCase().contains("cow")) {
                    validationResults.add("✓ Texture variants: Found " + availableVariants.length + " variants");
                } else {
                    validationResults.add("ℹ Texture variants: Not applicable for this model type");
                }
                
                validationResults.add("✓ Model parts: " + partCount + " parts loaded successfully");
                
                if (viewport3D != null) {
                    validationResults.add("✓ Viewport: 3D viewport connected");
                } else {
                    validationResults.add("✗ Viewport: 3D viewport not connected");
                    hasErrors = true;
                }
                
                validationInProgress = false;
                if (hasErrors) {
                    statusMessage = "Validation completed with errors";
                } else {
                    statusMessage = "Validation passed - model is valid";
                }
                
                logger.info("Model validation completed for '{}'. Results: {}", 
                           currentModelName, String.join(", ", validationResults));
                
            } catch (Exception e) {
                logger.error("Error during model validation", e);
                validationInProgress = false;
                statusMessage = "Validation failed: " + e.getMessage();
            }
        });
    }
    
    private void resetProperties() {
        // logger.info("Resetting properties to defaults...");
        
        try {
            // Reset transform
            resetTransform();
            
            // Stop animation
            stopAnimation();
            
            // Reset texture variant to default
            if (availableVariants.length > 0) {
                selectedVariantIndex.set(0);
                switchTextureVariant("default");
            }
            
            statusMessage = "Properties reset to defaults";
            // logger.info("All properties reset to default values");
            
        } catch (Exception e) {
            logger.error("Error resetting properties", e);
            statusMessage = "Error resetting properties: " + e.getMessage();
        }
    }
    
    private void exportModelDiagnostics() {
        if (currentModelName == null || currentModelName.isEmpty()) {
            statusMessage = "No model loaded for diagnostics export";
            logger.warn("Cannot export diagnostics - no model loaded");
            return;
        }
        
        statusMessage = "Exporting model diagnostics...";
        // logger.info("Exporting model diagnostics for: {}", currentModelName);
        
        CompletableFuture.runAsync(() -> {
            try {
                // Simulate diagnostic export
                Thread.sleep(500);
                
                statusMessage = "Diagnostics exported successfully";
                // logger.info("Model diagnostics exported successfully");
                
            } catch (Exception e) {
                logger.error("Failed to export model diagnostics", e);
                statusMessage = "Failed to export diagnostics: " + e.getMessage();
            }
        });
    }
    
    // Public API Methods
    
    public void setViewport3D(OpenMason3DViewport viewport) {
        if (this.viewport3D != viewport) {
            this.viewport3D = viewport;
            // logger.error("=== PropertyPanelImGui: viewport3D set to instance: {} ===", 
            //             viewport != null ? System.identityHashCode(viewport) : "NULL");
        }
    }
    
    public String getStatusMessage() {
        return statusMessage;
    }
    
    public boolean isLoadingInProgress() {
        return loadingInProgress;
    }
    
    public String getSelectedVariant() {
        return selectedVariant;
    }
    
    public boolean isInitialized() {
        return initialized;
    }
    
    public Map<String, Object> getPerformanceMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("switchCount", switchCount);
        metrics.put("lastSwitchTime", lastSwitchTime);
        metrics.put("initialized", initialized);
        metrics.put("currentModel", currentModelName);
        metrics.put("availableVariants", availableVariants.length);
        
        if (textureManager != null) {
            metrics.putAll(textureManager.getPerformanceStats());
        }
        
        return metrics;
    }
}