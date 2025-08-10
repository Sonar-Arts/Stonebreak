package com.openmason.ui;

import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import com.openmason.export.HighResolutionScreenshotSystem;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * ImGui-based export dialogs for OpenMason.
 * 
 * Provides Dear ImGui implementations for:
 * - Screenshot configuration and export
 * - Batch export configuration 
 * - Documentation generation settings
 * - Progress tracking with cancellation support
 */
public class ImGuiExportDialog {

    /**
     * Placeholder class to replace LWJGL NativeFileDialog.
     */
    static class NativeFileDialog {
        public static final int NFD_OKAY = 0;
        public static final int NFD_CANCEL = 1;
        public static final int NFD_ERROR = 2;
        
        public static int NFD_PickFolder(String defaultPath, ByteBuffer outPath) {
            logger.warn("File dialog not available - LWJGL NFD dependency not included");
            return NFD_CANCEL;
        }
        
        public static String NFD_GetError() {
            return "LWJGL NFD not available";
        }
    }
    
    private static final Logger logger = LoggerFactory.getLogger(ImGuiExportDialog.class);
    
    /**
     * Screenshot configuration dialog
     */
    public static class ScreenshotDialog implements ImGuiManager.ImGuiModal {
        
        private final ImBoolean visible = new ImBoolean(false);
        private final ImBoolean shouldClose = new ImBoolean(false);
        
        // Configuration state
        private final ImInt resolutionPreset = new ImInt(1); // FULL_HD_1080P
        private final ImInt customWidth = new ImInt(1920);
        private final ImInt customHeight = new ImInt(1080);
        private final ImInt outputFormat = new ImInt(0); // PNG
        private final ImBoolean transparentBackground = new ImBoolean(false);
        private final ImBoolean includeTimestamp = new ImBoolean(true);
        private final ImBoolean includeModelInfo = new ImBoolean(true);
        private final ImBoolean includeCoordinateOverlay = new ImBoolean(false);
        private final ImString filename = new ImString("screenshot", 256);
        private final ImString outputDirectory = new ImString("screenshots", 512);
        
        // Color picker state for background
        private final float[] backgroundColor = {0.2f, 0.2f, 0.2f, 1.0f};
        
        // Quality settings
        private float quality = 0.95f;
        private float renderScale = 1.0f;
        
        // Callback for when dialog is confirmed
        private Consumer<HighResolutionScreenshotSystem.ScreenshotConfig> onConfirm;
        
        public ScreenshotDialog(Consumer<HighResolutionScreenshotSystem.ScreenshotConfig> onConfirm) {
            this.onConfirm = onConfirm;
        }
        
        @Override
        public void render(float deltaTime) {
            if (!visible.get()) return;
            
            ImGui.openPopup("Screenshot Export Configuration");
            
            if (ImGui.beginPopupModal("Screenshot Export Configuration", visible, 
                    ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoResize)) {
                
                renderScreenshotConfiguration();
                
                ImGui.separator();
                
                // Action buttons
                if (ImGui.button("Export Screenshot", 120, 0)) {
                    if (onConfirm != null) {
                        onConfirm.accept(createScreenshotConfig());
                    }
                    shouldClose.set(true);
                }
                
                ImGui.sameLine();
                if (ImGui.button("Cancel", 80, 0)) {
                    shouldClose.set(true);
                }
                
                ImGui.endPopup();
            }
        }
        
        private void renderScreenshotConfiguration() {
            // Resolution settings
            ImGui.text("Resolution Settings");
            ImGui.separator();
            
            String[] resolutionOptions = {
                "HD 720p (1280x720)",
                "Full HD 1080p (1920x1080)",
                "QHD 1440p (2560x1440)",
                "4K UHD (3840x2160)",
                "Cinema 4K (4096x2160)",
                "Ultra-wide 1440p (3440x1440)",
                "Custom Resolution"
            };
            
            if (ImGui.combo("Preset", resolutionPreset, resolutionOptions)) {
                // Update custom dimensions when preset changes
                updateCustomDimensions();
            }
            
            // Custom resolution inputs (only show if custom is selected)
            if (resolutionPreset.get() == 6) { // Custom
                ImGui.inputInt("Width", customWidth);
                ImGui.inputInt("Height", customHeight);
            }
            
            ImGui.spacing();
            
            // Output format settings
            ImGui.text("Output Settings");
            ImGui.separator();
            
            String[] formatOptions = {"PNG", "JPEG", "BMP"};
            ImGui.combo("Format", outputFormat, formatOptions);
            
            // Quality setting for JPEG
            if (outputFormat.get() == 1) { // JPEG
                float[] qualityArray = {quality};
                if (ImGui.sliderFloat("Quality", qualityArray, 0.1f, 1.0f, "%.2f")) {
                    quality = qualityArray[0];
                }
            }
            
            ImGui.spacing();
            
            // Advanced settings
            ImGui.text("Advanced Settings");
            ImGui.separator();
            
            float[] renderScaleArray = {renderScale};
            if (ImGui.sliderFloat("Render Scale", renderScaleArray, 0.5f, 4.0f, "%.1fx")) {
                renderScale = renderScaleArray[0];
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Higher values provide better quality but use more memory");
            }
            
            ImGui.checkbox("Transparent Background", transparentBackground);
            
            if (!transparentBackground.get()) {
                ImGui.colorEdit3("Background Color", backgroundColor);
            }
            
            ImGui.spacing();
            
            // File settings
            ImGui.text("File Settings");
            ImGui.separator();
            
            ImGui.inputText("Filename", filename);
            ImGui.inputText("Output Directory", outputDirectory);
            ImGui.sameLine();
            if (ImGui.button("Browse...")) {
                browseForDirectory();
            }
            
            ImGui.spacing();
            
            // Additional options
            ImGui.text("Include Information");
            ImGui.separator();
            
            ImGui.checkbox("Include Timestamp", includeTimestamp);
            ImGui.checkbox("Include Model Info", includeModelInfo);
            ImGui.checkbox("Include Coordinate Overlay", includeCoordinateOverlay);
        }
        
        private void updateCustomDimensions() {
            switch (resolutionPreset.get()) {
                case 0: // HD 720p
                    customWidth.set(1280);
                    customHeight.set(720);
                    break;
                case 1: // Full HD 1080p
                    customWidth.set(1920);
                    customHeight.set(1080);
                    break;
                case 2: // QHD 1440p
                    customWidth.set(2560);
                    customHeight.set(1440);
                    break;
                case 3: // 4K UHD
                    customWidth.set(3840);
                    customHeight.set(2160);
                    break;
                case 4: // Cinema 4K
                    customWidth.set(4096);
                    customHeight.set(2160);
                    break;
                case 5: // Ultra-wide 1440p
                    customWidth.set(3440);
                    customHeight.set(1440);
                    break;
                // Custom (6) doesn't change values
            }
        }
        
        private void browseForDirectory() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer outPath = stack.malloc(1024);
                
                int result = NativeFileDialog.NFD_PickFolder(null, outPath);
                if (result == NativeFileDialog.NFD_OKAY) {
                    String selectedPath = MemoryUtil.memUTF8(outPath);
                    outputDirectory.set(selectedPath);
                    logger.debug("Selected output directory: {}", selectedPath);
                } else if (result == NativeFileDialog.NFD_ERROR) {
                    logger.error("Error opening directory dialog: {}", NativeFileDialog.NFD_GetError());
                }
            }
        }
        
        private HighResolutionScreenshotSystem.ScreenshotConfig createScreenshotConfig() {
            HighResolutionScreenshotSystem.ScreenshotConfig config = 
                new HighResolutionScreenshotSystem.ScreenshotConfig();
            
            // Set resolution preset
            HighResolutionScreenshotSystem.ResolutionPreset[] presets = {
                HighResolutionScreenshotSystem.ResolutionPreset.HD_720P,
                HighResolutionScreenshotSystem.ResolutionPreset.FULL_HD_1080P,
                HighResolutionScreenshotSystem.ResolutionPreset.QHD_1440P,
                HighResolutionScreenshotSystem.ResolutionPreset.UHD_4K,
                HighResolutionScreenshotSystem.ResolutionPreset.CINEMA_4K,
                HighResolutionScreenshotSystem.ResolutionPreset.ULTRA_WIDE_1440P,
                HighResolutionScreenshotSystem.ResolutionPreset.CUSTOM
            };
            config.setResolutionPreset(presets[resolutionPreset.get()]);
            
            // Set custom dimensions
            config.setCustomWidth(customWidth.get());
            config.setCustomHeight(customHeight.get());
            
            // Set output format
            HighResolutionScreenshotSystem.OutputFormat[] formats = {
                HighResolutionScreenshotSystem.OutputFormat.PNG,
                HighResolutionScreenshotSystem.OutputFormat.JPG,
                HighResolutionScreenshotSystem.OutputFormat.BMP
            };
            config.setFormat(formats[outputFormat.get()]);
            
            // Set other options
            config.setQuality(quality);
            config.setRenderScale(renderScale);
            config.setTransparentBackground(transparentBackground.get());
            config.getBackgroundColor().set(backgroundColor[0], backgroundColor[1], backgroundColor[2]);
            config.setFilename(filename.get());
            config.setOutputDirectory(outputDirectory.get());
            config.setIncludeTimestamp(includeTimestamp.get());
            config.setIncludeModelInfo(includeModelInfo.get());
            config.setIncludeCoordinateOverlay(includeCoordinateOverlay.get());
            
            return config;
        }
        
        @Override
        public String getName() {
            return "Screenshot Dialog";
        }
        
        @Override
        public boolean isVisible() {
            return visible.get();
        }
        
        @Override
        public void setVisible(boolean visible) {
            this.visible.set(visible);
            if (!visible) {
                shouldClose.set(false);
            }
        }
        
        @Override
        public boolean shouldClose() {
            return shouldClose.get();
        }
        
        @Override
        public void onClose() {
            visible.set(false);
            shouldClose.set(false);
        }
    }
    
    /**
     * Progress dialog for export operations
     */
    public static class ProgressDialog implements ImGuiManager.ImGuiModal {
        
        private final ImBoolean visible = new ImBoolean(false);
        private final ImBoolean shouldClose = new ImBoolean(false);
        
        private String title = "Export Progress";
        private String status = "Starting export...";
        private float progress = 0.0f;
        private String details = "";
        private boolean cancellable = true;
        private boolean cancelled = false;
        
        private Runnable onCancel;
        
        public ProgressDialog(String title, Runnable onCancel) {
            this.title = title;
            this.onCancel = onCancel;
        }
        
        @Override
        public void render(float deltaTime) {
            if (!visible.get()) return;
            
            ImGui.openPopup(title);
            
            if (ImGui.beginPopupModal(title, visible, 
                    ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove)) {
                
                // Status text
                ImGui.text(status);
                
                // Progress bar
                ImGui.progressBar(progress, 400, 0, String.format("%.1f%%", progress * 100));
                
                // Details text
                if (!details.isEmpty()) {
                    ImGui.spacing();
                    ImGui.textWrapped(details);
                }
                
                ImGui.spacing();
                
                // Cancel button
                if (cancellable && ImGui.button("Cancel", 100, 0)) {
                    cancelled = true;
                    if (onCancel != null) {
                        onCancel.run();
                    }
                    shouldClose.set(true);
                }
                
                ImGui.endPopup();
            }
        }
        
        public void updateProgress(float progress, String status, String details) {
            this.progress = Math.max(0.0f, Math.min(1.0f, progress));
            this.status = status != null ? status : this.status;
            this.details = details != null ? details : "";
        }
        
        public void complete(String message) {
            this.progress = 1.0f;
            this.status = message != null ? message : "Export completed!";
            this.details = "";
            this.cancellable = false;
            
            // Auto-close after a brief delay
            new Thread(() -> {
                try {
                    Thread.sleep(1500);
                    shouldClose.set(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
        
        public void error(String message, String details) {
            this.status = message != null ? message : "Export failed!";
            this.details = details != null ? details : "";
            this.cancellable = false;
        }
        
        public boolean wasCancelled() {
            return cancelled;
        }
        
        @Override
        public String getName() {
            return "Progress Dialog";
        }
        
        @Override
        public boolean isVisible() {
            return visible.get();
        }
        
        @Override
        public void setVisible(boolean visible) {
            this.visible.set(visible);
            if (!visible) {
                shouldClose.set(false);
                cancelled = false;
            }
        }
        
        @Override
        public boolean shouldClose() {
            return shouldClose.get();
        }
        
        @Override
        public void onClose() {
            visible.set(false);
            shouldClose.set(false);
        }
    }
    
    /**
     * Simple information dialog
     */
    public static class InfoDialog implements ImGuiManager.ImGuiModal {
        
        private final ImBoolean visible = new ImBoolean(false);
        private final ImBoolean shouldClose = new ImBoolean(false);
        
        private String title = "Information";
        private String message = "";
        
        public InfoDialog(String title, String message) {
            this.title = title;
            this.message = message;
        }
        
        @Override
        public void render(float deltaTime) {
            if (!visible.get()) return;
            
            ImGui.openPopup(title);
            
            if (ImGui.beginPopupModal(title, visible, ImGuiWindowFlags.AlwaysAutoResize)) {
                
                ImGui.textWrapped(message);
                
                ImGui.spacing();
                
                if (ImGui.button("OK", 80, 0)) {
                    shouldClose.set(true);
                }
                
                ImGui.endPopup();
            }
        }
        
        @Override
        public String getName() {
            return "Info Dialog";
        }
        
        @Override
        public boolean isVisible() {
            return visible.get();
        }
        
        @Override
        public void setVisible(boolean visible) {
            this.visible.set(visible);
        }
        
        @Override
        public boolean shouldClose() {
            return shouldClose.get();
        }
        
        @Override
        public void onClose() {
            visible.set(false);
            shouldClose.set(false);
        }
    }
    
    /**
     * Confirmation dialog
     */
    public static class ConfirmDialog implements ImGuiManager.ImGuiModal {
        
        private final ImBoolean visible = new ImBoolean(false);
        private final ImBoolean shouldClose = new ImBoolean(false);
        
        private String title = "Confirm";
        private String message = "";
        private boolean confirmed = false;
        
        private Consumer<Boolean> onResult;
        
        public ConfirmDialog(String title, String message, Consumer<Boolean> onResult) {
            this.title = title;
            this.message = message;
            this.onResult = onResult;
        }
        
        @Override
        public void render(float deltaTime) {
            if (!visible.get()) return;
            
            ImGui.openPopup(title);
            
            if (ImGui.beginPopupModal(title, visible, ImGuiWindowFlags.AlwaysAutoResize)) {
                
                ImGui.textWrapped(message);
                
                ImGui.spacing();
                
                if (ImGui.button("OK", 80, 0)) {
                    confirmed = true;
                    if (onResult != null) {
                        onResult.accept(true);
                    }
                    shouldClose.set(true);
                }
                
                ImGui.sameLine();
                if (ImGui.button("Cancel", 80, 0)) {
                    confirmed = false;
                    if (onResult != null) {
                        onResult.accept(false);
                    }
                    shouldClose.set(true);
                }
                
                ImGui.endPopup();
            }
        }
        
        public boolean wasConfirmed() {
            return confirmed;
        }
        
        @Override
        public String getName() {
            return "Confirm Dialog";
        }
        
        @Override
        public boolean isVisible() {
            return visible.get();
        }
        
        @Override
        public void setVisible(boolean visible) {
            this.visible.set(visible);
        }
        
        @Override
        public boolean shouldClose() {
            return shouldClose.get();
        }
        
        @Override
        public void onClose() {
            visible.set(false);
            shouldClose.set(false);
            confirmed = false;
        }
    }
}