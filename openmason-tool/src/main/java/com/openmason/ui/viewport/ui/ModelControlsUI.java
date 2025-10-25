package com.openmason.ui.viewport.ui;

import com.openmason.model.StonebreakModel;
import com.openmason.rendering.ModelRenderer;
import imgui.ImGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Renders model controls window (model loading, texture variants).
 * Follows Single Responsibility Principle - only handles model controls UI.
 */
public class ModelControlsUI {

    private static final Logger logger = LoggerFactory.getLogger(ModelControlsUI.class);

    private static final String[] TEXTURE_VARIANTS = {"default", "angus", "highland", "jersey"};

    private String currentModelName;
    private String currentTextureVariant = "default";
    private StonebreakModel currentModel;
    private CompletableFuture<Void> currentModelLoadingFuture;
    private ModelRenderer modelRenderer;

    /**
     * Update UI state references.
     */
    public void updateState(String currentModelName, String currentTextureVariant,
                           StonebreakModel currentModel, CompletableFuture<Void> loadingFuture,
                           ModelRenderer modelRenderer) {
        this.currentModelName = currentModelName;
        this.currentTextureVariant = currentTextureVariant;
        this.currentModel = currentModel;
        this.currentModelLoadingFuture = loadingFuture;
        this.modelRenderer = modelRenderer;
    }

    /**
     * Render model controls window.
     * Returns result with user actions.
     */
    public ModelControlsResult render() {
        ModelControlsResult result = new ModelControlsResult();

        ImGui.begin("Model Controls");

        // Model loading section
        ImGui.text("Model Loading:");
        ImGui.separator();

        if (currentModel != null) {
            renderModelLoadedSection(result);
        } else {
            renderNoModelSection(result);
        }

        // Model renderer status
        if (modelRenderer != null) {
            renderRendererStatus();
        }

        ImGui.end();

        return result;
    }

    /**
     * Render section when model is loaded.
     */
    private void renderModelLoadedSection(ModelControlsResult result) {
        ImGui.textColored(0.0f, 1.0f, 0.0f, 1.0f, "Model Loaded: " + currentModelName);
        ImGui.text("Variant: " + currentTextureVariant);
        ImGui.text("Parts: " + currentModel.getBodyParts().size());

        // Texture variant controls
        ImGui.separator();
        ImGui.text("Texture Variants:");

        for (String variant : TEXTURE_VARIANTS) {
            if (ImGui.radioButton(variant, currentTextureVariant.equals(variant))) {
                if (!currentTextureVariant.equals(variant)) {
                    result.newTextureVariant = variant;
                    logger.debug("User selected texture variant: {}", variant);
                }
            }
        }

        if (ImGui.button("Unload Model")) {
            result.unloadModel = true;
        }
    }

    /**
     * Render section when no model is loaded.
     */
    private void renderNoModelSection(ModelControlsResult result) {
        if (currentModelLoadingFuture != null && !currentModelLoadingFuture.isDone()) {
            ImGui.textColored(1.0f, 1.0f, 0.0f, 1.0f, "Loading model...");
            ImGui.text("Model: " + currentModelName);
        } else {
            ImGui.text("No model loaded");
        }

        ImGui.separator();
        ImGui.text("Available Models:");

        // Model loading buttons
        if (ImGui.button("Load Cow Model")) {
            result.loadModelName = "standard_cow";
        }
    }

    /**
     * Render model renderer status section.
     */
    private void renderRendererStatus() {
        ImGui.separator();
        ImGui.text("Renderer Status:");
        ModelRenderer.RenderingStatistics stats = modelRenderer.getStatistics();
        ImGui.text("Initialized: " + stats.initialized);
        ImGui.text("Model Parts: " + stats.modelPartCount);
        ImGui.text("Render Calls: " + stats.totalRenderCalls);
        ImGui.text("Last Render: " + (stats.lastRenderTime > 0 ? (System.currentTimeMillis() - stats.lastRenderTime) + "ms ago" : "Never"));
    }

    /**
     * Result object for render() return value.
     */
    public static class ModelControlsResult {
        public String loadModelName = null;      // Model to load
        public boolean unloadModel = false;      // Unload current model
        public String newTextureVariant = null;  // New texture variant selected

        public boolean hasAction() {
            return loadModelName != null || unloadModel || newTextureVariant != null;
        }
    }
}
