package com.openmason.export;

import com.openmason.texture.TextureManager;
import com.stonebreak.textures.CowTextureDefinition;
import com.stonebreak.textures.CowTextureLoader;
import com.openmason.coordinates.AtlasCoordinateSystem;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.embed.swing.SwingFXUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Texture Atlas Export System for Open Mason Phase 8.
 * 
 * Provides comprehensive texture atlas export capabilities:
 * - Export complete texture atlases with coordinate grids
 * - Include coordinate mapping data and metadata
 * - Support multiple formats and resolutions
 * - Generate reference documentation
 * - Professional atlas visualization with annotations
 */
public class TextureAtlasExportSystem {
    
    private static final Logger logger = LoggerFactory.getLogger(TextureAtlasExportSystem.class);
    
    // Atlas export formats
    public enum AtlasFormat {
        PNG_WITH_GRID("png", "PNG with Coordinate Grid", true, true),
        PNG_CLEAN("png", "PNG Clean Atlas", true, false),
        SVG_ANNOTATED("svg", "SVG with Annotations", false, true),
        PDF_REFERENCE("pdf", "PDF Reference Sheet", false, true),
        JSON_DATA("json", "JSON Coordinate Data", false, false);
        
        private final String extension;
        private final String description;
        private final boolean isRaster;
        private final boolean includesAnnotations;
        
        AtlasFormat(String extension, String description, boolean isRaster, boolean includesAnnotations) {
            this.extension = extension;
            this.description = description;
            this.isRaster = isRaster;
            this.includesAnnotations = includesAnnotations;
        }
        
        public String getExtension() { return extension; }
        public String getDescription() { return description; }
        public boolean isRaster() { return isRaster; }
        public boolean includesAnnotations() { return includesAnnotations; }
    }
    
    // Atlas resolution presets
    public enum AtlasResolution {
        STANDARD_256(256, "256×256 - Standard"),
        HIGH_512(512, "512×512 - High Quality"),
        ULTRA_1024(1024, "1024×1024 - Ultra High"),
        PRINT_2048(2048, "2048×2048 - Print Quality"),
        MAXIMUM_4096(4096, "4096×4096 - Maximum Quality");
        
        private final int resolution;
        private final String description;
        
        AtlasResolution(int resolution, String description) {
            this.resolution = resolution;
            this.description = description;
        }
        
        public int getResolution() { return resolution; }
        public String getDescription() { return description; }
        public int getCellSize() { return resolution / 16; } // 16x16 grid
    }
    
    // Export configuration
    public static class AtlasExportConfig {
        private AtlasFormat format = AtlasFormat.PNG_WITH_GRID;
        private AtlasResolution resolution = AtlasResolution.HIGH_512;
        private String outputDirectory = "atlas_exports";
        private boolean includeCoordinateLabels = true;
        private boolean includeGridLines = true;
        private boolean includeColorCoding = true;
        private boolean includeLegend = true;
        private boolean includeMetadata = true;
        private boolean exportAllVariants = true;
        private List<String> specificVariants = new ArrayList<>();
        private Color gridColor = Color.rgb(128, 128, 128, 0.8);
        private Color labelColor = Color.WHITE;
        private Color backgroundColor = Color.TRANSPARENT;
        private String customTitle = null;
        private Map<String, String> customAnnotations = new HashMap<>();
        
        // Getters and setters
        public AtlasFormat getFormat() { return format; }
        public void setFormat(AtlasFormat format) { this.format = format; }
        
        public AtlasResolution getResolution() { return resolution; }
        public void setResolution(AtlasResolution resolution) { this.resolution = resolution; }
        
        public String getOutputDirectory() { return outputDirectory; }
        public void setOutputDirectory(String outputDirectory) { this.outputDirectory = outputDirectory; }
        
        public boolean isIncludeCoordinateLabels() { return includeCoordinateLabels; }
        public void setIncludeCoordinateLabels(boolean includeCoordinateLabels) { 
            this.includeCoordinateLabels = includeCoordinateLabels; 
        }
        
        public boolean isIncludeGridLines() { return includeGridLines; }
        public void setIncludeGridLines(boolean includeGridLines) { this.includeGridLines = includeGridLines; }
        
        public boolean isIncludeColorCoding() { return includeColorCoding; }
        public void setIncludeColorCoding(boolean includeColorCoding) { this.includeColorCoding = includeColorCoding; }
        
        public boolean isIncludeLegend() { return includeLegend; }
        public void setIncludeLegend(boolean includeLegend) { this.includeLegend = includeLegend; }
        
        public boolean isIncludeMetadata() { return includeMetadata; }
        public void setIncludeMetadata(boolean includeMetadata) { this.includeMetadata = includeMetadata; }
        
        public boolean isExportAllVariants() { return exportAllVariants; }
        public void setExportAllVariants(boolean exportAllVariants) { this.exportAllVariants = exportAllVariants; }
        
        public List<String> getSpecificVariants() { return specificVariants; }
        public void setSpecificVariants(List<String> specificVariants) { this.specificVariants = specificVariants; }
        
        public Color getGridColor() { return gridColor; }
        public void setGridColor(Color gridColor) { this.gridColor = gridColor; }
        
        public Color getLabelColor() { return labelColor; }
        public void setLabelColor(Color labelColor) { this.labelColor = labelColor; }
        
        public Color getBackgroundColor() { return backgroundColor; }
        public void setBackgroundColor(Color backgroundColor) { this.backgroundColor = backgroundColor; }
        
        public String getCustomTitle() { return customTitle; }
        public void setCustomTitle(String customTitle) { this.customTitle = customTitle; }
        
        public Map<String, String> getCustomAnnotations() { return customAnnotations; }
        public void setCustomAnnotations(Map<String, String> customAnnotations) { 
            this.customAnnotations = customAnnotations; 
        }
    }
    
    // Atlas export result
    public static class AtlasExportResult {
        private final List<File> exportedFiles;
        private final Map<String, Map<String, Object>> variantData;
        private final long exportTime;
        private final String summaryReport;
        
        public AtlasExportResult(List<File> exportedFiles, Map<String, Map<String, Object>> variantData,
                               long exportTime, String summaryReport) {
            this.exportedFiles = exportedFiles;
            this.variantData = variantData;
            this.exportTime = exportTime;
            this.summaryReport = summaryReport;
        }
        
        public List<File> getExportedFiles() { return exportedFiles; }
        public Map<String, Map<String, Object>> getVariantData() { return variantData; }
        public long getExportTime() { return exportTime; }
        public String getSummaryReport() { return summaryReport; }
    }
    
    // Progress callback interface
    public interface AtlasExportProgressCallback {
        void onProgress(String stage, int progress, String details);
        void onVariantCompleted(String variantName, File outputFile);
        void onComplete(AtlasExportResult result);
        void onError(String stage, Throwable error);
    }
    
    /**
     * Export texture atlases asynchronously.
     * 
     * @param config Export configuration
     * @param progressCallback Optional progress callback
     * @return CompletableFuture that resolves to AtlasExportResult
     */
    public CompletableFuture<AtlasExportResult> exportAtlasesAsync(AtlasExportConfig config,
                                                                 AtlasExportProgressCallback progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            List<File> exportedFiles = new ArrayList<>();
            Map<String, Map<String, Object>> variantData = new HashMap<>();
            
            try {
                if (progressCallback != null) {
                    progressCallback.onProgress("Initialization", 0, "Starting atlas export");
                }
                
                // Create output directory
                Path outputDir = Paths.get(config.getOutputDirectory());
                Files.createDirectories(outputDir);
                
                // Get variants to export
                List<String> variantsToExport = config.isExportAllVariants() ? 
                    TextureManager.getAvailableVariants() : config.getSpecificVariants();
                
                if (progressCallback != null) {
                    progressCallback.onProgress("Data Collection", 10, 
                        "Collecting data for " + variantsToExport.size() + " variants");
                }
                
                // Export each variant
                for (int i = 0; i < variantsToExport.size(); i++) {
                    String variant = variantsToExport.get(i);
                    
                    if (progressCallback != null) {
                        int progress = 10 + (i * 80 / variantsToExport.size());
                        progressCallback.onProgress("Export", progress, "Exporting variant: " + variant);
                    }
                    
                    try {
                        File outputFile = exportSingleVariant(config, outputDir, variant);
                        exportedFiles.add(outputFile);
                        
                        // Collect variant data
                        Map<String, Object> data = collectVariantData(variant);
                        variantData.put(variant, data);
                        
                        if (progressCallback != null) {
                            progressCallback.onVariantCompleted(variant, outputFile);
                        }
                        
                    } catch (Exception e) {
                        logger.error("Failed to export variant: {}", variant, e);
                        // Continue with other variants
                    }
                }
                
                // Generate summary report
                if (progressCallback != null) {
                    progressCallback.onProgress("Summary", 95, "Generating summary report");
                }
                
                String summaryReport = generateSummaryReport(config, variantData, exportedFiles);
                File summaryFile = new File(outputDir.toFile(), "export_summary.txt");
                Files.write(summaryFile.toPath(), summaryReport.getBytes());
                exportedFiles.add(summaryFile);
                
                long exportTime = System.currentTimeMillis() - startTime;
                AtlasExportResult result = new AtlasExportResult(exportedFiles, variantData, exportTime, summaryReport);
                
                if (progressCallback != null) {
                    progressCallback.onComplete(result);
                }
                
                logger.info("Atlas export completed: {} variants, {} files, {} ms", 
                    variantsToExport.size(), exportedFiles.size(), exportTime);
                
                return result;
                
            } catch (Exception e) {
                logger.error("Atlas export failed", e);
                if (progressCallback != null) {
                    progressCallback.onError("Export", e);
                }
                throw new RuntimeException("Atlas export failed", e);
            }
        });
    }
    
    /**
     * Export atlases synchronously.
     */
    public AtlasExportResult exportAtlases(AtlasExportConfig config) {
        try {
            return exportAtlasesAsync(config, null).get();
        } catch (Exception e) {
            throw new RuntimeException("Synchronous atlas export failed", e);
        }
    }
    
    /**
     * Export a single texture variant atlas.
     */
    private File exportSingleVariant(AtlasExportConfig config, Path outputDir, String variantName) throws IOException {
        String filename = generateFilename(config, variantName);
        File outputFile = new File(outputDir.toFile(), filename);
        
        switch (config.getFormat()) {
            case PNG_WITH_GRID:
            case PNG_CLEAN:
                exportPngAtlas(config, outputFile, variantName);
                break;
            case SVG_ANNOTATED:
                exportSvgAtlas(config, outputFile, variantName);
                break;
            case PDF_REFERENCE:
                exportPdfAtlas(config, outputFile, variantName);
                break;
            case JSON_DATA:
                exportJsonData(config, outputFile, variantName);
                break;
            default:
                throw new IllegalArgumentException("Unsupported format: " + config.getFormat());
        }
        
        logger.debug("Exported atlas for variant '{}': {}", variantName, outputFile.getAbsolutePath());
        return outputFile;
    }
    
    /**
     * Export PNG atlas with optional grid and annotations.
     */
    private void exportPngAtlas(AtlasExportConfig config, File outputFile, String variantName) throws IOException {
        int resolution = config.getResolution().getResolution();
        int cellSize = config.getResolution().getCellSize();
        
        // Create canvas for rendering
        Canvas canvas = new Canvas(resolution, resolution);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        
        // Clear with background color
        gc.setFill(config.getBackgroundColor());
        gc.fillRect(0, 0, resolution, resolution);
        
        // Get texture variant info
        TextureManager.TextureVariantInfo variantInfo = TextureManager.getVariantInfo(variantName);
        if (variantInfo == null) {
            throw new IOException("Variant not found: " + variantName);
        }
        
        // Draw texture cells
        drawTextureCells(gc, config, variantInfo, cellSize);
        
        // Draw grid if requested
        if (config.isIncludeGridLines() && config.getFormat() == AtlasFormat.PNG_WITH_GRID) {
            drawGrid(gc, config, resolution, cellSize);
        }
        
        // Draw coordinate labels if requested
        if (config.isIncludeCoordinateLabels() && config.getFormat() == AtlasFormat.PNG_WITH_GRID) {
            drawCoordinateLabels(gc, config, resolution, cellSize);
        }
        
        // Draw legend if requested
        if (config.isIncludeLegend() && config.getFormat() == AtlasFormat.PNG_WITH_GRID) {
            drawLegend(gc, config, variantInfo, resolution);
        }
        
        // Export as PNG
        WritableImage image = canvas.snapshot(null, null);
        BufferedImage bufferedImage = SwingFXUtils.fromFXImage(image, null);
        ImageIO.write(bufferedImage, "png", outputFile);
    }
    
    /**
     * Draw texture cells in the atlas.
     */
    private void drawTextureCells(GraphicsContext gc, AtlasExportConfig config, 
                                TextureManager.TextureVariantInfo variantInfo, int cellSize) {
        CowTextureDefinition.CowVariant variant = variantInfo.getVariantDefinition();
        if (variant == null || variant.getFaceMappings() == null) {
            return;
        }
        
        // Color coding for different face types
        Map<String, Color> faceTypeColors = createFaceTypeColorMap(config);
        
        for (Map.Entry<String, CowTextureDefinition.AtlasCoordinate> entry : 
             variant.getFaceMappings().entrySet()) {
            
            String faceName = entry.getKey();
            CowTextureDefinition.AtlasCoordinate coord = entry.getValue();
            
            int x = coord.getAtlasX() * cellSize;
            int y = coord.getAtlasY() * cellSize;
            
            // Draw cell background with face type color
            if (config.isIncludeColorCoding()) {
                Color cellColor = getFaceTypeColor(faceName, faceTypeColors);
                gc.setFill(cellColor);
                gc.fillRect(x, y, cellSize, cellSize);
            }
            
            // Draw face name label
            if (config.isIncludeCoordinateLabels()) {
                gc.setFill(config.getLabelColor());
                gc.setFont(Font.font("Arial", FontWeight.BOLD, Math.max(8, cellSize / 8)));
                
                // Abbreviated face name for small cells
                String displayName = abbreviateFaceName(faceName, cellSize);
                
                // Center text in cell
                double textWidth = displayName.length() * gc.getFont().getSize() * 0.6;
                double textX = x + (cellSize - textWidth) / 2;
                double textY = y + cellSize / 2 + gc.getFont().getSize() / 3;
                
                gc.fillText(displayName, textX, textY);
            }
        }
    }
    
    /**
     * Draw coordinate grid.
     */
    private void drawGrid(GraphicsContext gc, AtlasExportConfig config, int resolution, int cellSize) {
        gc.setStroke(config.getGridColor());
        gc.setLineWidth(1.0);
        
        // Vertical lines
        for (int x = 0; x <= resolution; x += cellSize) {
            gc.strokeLine(x, 0, x, resolution);
        }
        
        // Horizontal lines
        for (int y = 0; y <= resolution; y += cellSize) {
            gc.strokeLine(0, y, resolution, y);
        }
    }
    
    /**
     * Draw coordinate labels on grid.
     */
    private void drawCoordinateLabels(GraphicsContext gc, AtlasExportConfig config, int resolution, int cellSize) {
        gc.setFill(config.getLabelColor());
        gc.setFont(Font.font("Arial", FontWeight.NORMAL, Math.max(10, cellSize / 10)));
        
        // X-axis labels (top)
        for (int x = 0; x < 16; x++) {
            String label = String.valueOf(x);
            double labelX = x * cellSize + cellSize / 2 - 4;
            gc.fillText(label, labelX, 12);
        }
        
        // Y-axis labels (left)
        for (int y = 0; y < 16; y++) {
            String label = String.valueOf(y);
            double labelY = y * cellSize + cellSize / 2 + 4;
            gc.fillText(label, 4, labelY);
        }
    }
    
    /**
     * Draw legend explaining the atlas.
     */
    private void drawLegend(GraphicsContext gc, AtlasExportConfig config, 
                          TextureManager.TextureVariantInfo variantInfo, int resolution) {
        int legendX = resolution - 200;
        int legendY = 20;
        
        // Legend background
        gc.setFill(Color.rgb(0, 0, 0, 0.7));
        gc.fillRect(legendX - 10, legendY - 10, 190, 150);
        
        // Legend title
        gc.setFill(Color.WHITE);
        gc.setFont(Font.font("Arial", FontWeight.BOLD, 12));
        gc.fillText("Texture Atlas Legend", legendX, legendY + 10);
        
        // Variant info
        gc.setFont(Font.font("Arial", FontWeight.NORMAL, 10));
        gc.fillText("Variant: " + variantInfo.getDisplayName(), legendX, legendY + 30);
        gc.fillText("Faces: " + variantInfo.getFaceMappingCount(), legendX, legendY + 45);
        
        // Color coding legend if enabled
        if (config.isIncludeColorCoding()) {
            gc.fillText("Color Coding:", legendX, legendY + 65);
            
            Map<String, Color> faceTypeColors = createFaceTypeColorMap(config);
            int colorY = legendY + 80;
            
            for (Map.Entry<String, Color> entry : faceTypeColors.entrySet()) {
                gc.setFill(entry.getValue());
                gc.fillRect(legendX, colorY, 15, 10);
                
                gc.setFill(Color.WHITE);
                gc.fillText(entry.getKey(), legendX + 20, colorY + 8);
                colorY += 15;
            }
        }
    }
    
    /**
     * Export SVG atlas with vector annotations.
     */
    private void exportSvgAtlas(AtlasExportConfig config, File outputFile, String variantName) throws IOException {
        int resolution = config.getResolution().getResolution();
        int cellSize = config.getResolution().getCellSize();
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            // SVG header
            writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            writer.println("<svg width=\"" + resolution + "\" height=\"" + resolution + 
                          "\" xmlns=\"http://www.w3.org/2000/svg\">");
            
            // Title
            String title = config.getCustomTitle() != null ? config.getCustomTitle() : 
                          "Texture Atlas: " + variantName;
            writer.println("  <title>" + escapeXml(title) + "</title>");
            
            // Background
            if (config.getBackgroundColor() != Color.TRANSPARENT) {
                writer.println("  <rect width=\"100%\" height=\"100%\" fill=\"" + 
                              colorToHex(config.getBackgroundColor()) + "\"/>");
            }
            
            // Get variant info
            TextureManager.TextureVariantInfo variantInfo = TextureManager.getVariantInfo(variantName);
            if (variantInfo != null && variantInfo.getVariantDefinition().getFaceMappings() != null) {
                
                // Draw texture cells
                Map<String, Color> faceTypeColors = createFaceTypeColorMap(config);
                
                for (Map.Entry<String, CowTextureDefinition.AtlasCoordinate> entry : 
                     variantInfo.getVariantDefinition().getFaceMappings().entrySet()) {
                    
                    String faceName = entry.getKey();
                    CowTextureDefinition.AtlasCoordinate coord = entry.getValue();
                    
                    int x = coord.getAtlasX() * cellSize;
                    int y = coord.getAtlasY() * cellSize;
                    
                    // Cell background
                    if (config.isIncludeColorCoding()) {
                        Color cellColor = getFaceTypeColor(faceName, faceTypeColors);
                        writer.println("  <rect x=\"" + x + "\" y=\"" + y + 
                                      "\" width=\"" + cellSize + "\" height=\"" + cellSize + 
                                      "\" fill=\"" + colorToHex(cellColor) + "\"/>");
                    }
                    
                    // Face label
                    if (config.isIncludeCoordinateLabels()) {
                        String displayName = abbreviateFaceName(faceName, cellSize);
                        int textX = x + cellSize / 2;
                        int textY = y + cellSize / 2;
                        
                        writer.println("  <text x=\"" + textX + "\" y=\"" + textY + 
                                      "\" text-anchor=\"middle\" dominant-baseline=\"middle\" " +
                                      "font-family=\"Arial\" font-size=\"" + Math.max(8, cellSize / 8) + 
                                      "\" fill=\"" + colorToHex(config.getLabelColor()) + "\">" +
                                      escapeXml(displayName) + "</text>");
                    }
                }
            }
            
            // Grid lines
            if (config.isIncludeGridLines()) {
                String gridColor = colorToHex(config.getGridColor());
                
                for (int x = 0; x <= resolution; x += cellSize) {
                    writer.println("  <line x1=\"" + x + "\" y1=\"0\" x2=\"" + x + 
                                  "\" y2=\"" + resolution + "\" stroke=\"" + gridColor + "\"/>");
                }
                
                for (int y = 0; y <= resolution; y += cellSize) {
                    writer.println("  <line x1=\"0\" y1=\"" + y + "\" x2=\"" + resolution + 
                                  "\" y2=\"" + y + "\" stroke=\"" + gridColor + "\"/>");
                }
            }
            
            writer.println("</svg>");
        }
    }
    
    /**
     * Export PDF reference sheet.
     */
    private void exportPdfAtlas(AtlasExportConfig config, File outputFile, String variantName) throws IOException {
        // For PDF generation, we would typically use a library like iText or PDFBox
        // For now, we'll create a simple text-based reference
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile.getPath().replace(".pdf", ".txt")))) {
            writer.println("TEXTURE ATLAS REFERENCE SHEET");
            writer.println("=============================");
            writer.println();
            writer.println("Variant: " + variantName);
            writer.println("Generated: " + LocalDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME));
            writer.println("Resolution: " + config.getResolution().getDescription());
            writer.println();
            
            TextureManager.TextureVariantInfo variantInfo = TextureManager.getVariantInfo(variantName);
            if (variantInfo != null && variantInfo.getVariantDefinition().getFaceMappings() != null) {
                writer.println("FACE MAPPINGS:");
                writer.println("--------------");
                
                for (Map.Entry<String, CowTextureDefinition.AtlasCoordinate> entry : 
                     variantInfo.getVariantDefinition().getFaceMappings().entrySet()) {
                    
                    String faceName = entry.getKey();
                    CowTextureDefinition.AtlasCoordinate coord = entry.getValue();
                    
                    writer.printf("%-20s -> Atlas(%2d, %2d) -> UV(%.3f, %.3f, %.3f, %.3f)%n",
                        faceName, coord.getAtlasX(), coord.getAtlasY(),
                        coord.getAtlasX() / 16.0f, coord.getAtlasY() / 16.0f,
                        (coord.getAtlasX() + 1) / 16.0f, (coord.getAtlasY() + 1) / 16.0f);
                }
            }
        }
    }
    
    /**
     * Export JSON coordinate data.
     */
    private void exportJsonData(AtlasExportConfig config, File outputFile, String variantName) throws IOException {
        Map<String, Object> atlasData = new HashMap<>();
        
        // Metadata
        atlasData.put("variant", variantName);
        atlasData.put("exportDate", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        atlasData.put("resolution", config.getResolution().getResolution());
        atlasData.put("cellSize", config.getResolution().getCellSize());
        
        // Variant data
        Map<String, Object> variantData = collectVariantData(variantName);
        atlasData.put("variantData", variantData);
        
        // Face mappings
        TextureManager.TextureVariantInfo variantInfo = TextureManager.getVariantInfo(variantName);
        if (variantInfo != null && variantInfo.getVariantDefinition().getFaceMappings() != null) {
            
            Map<String, Map<String, Object>> faceMappings = new HashMap<>();
            
            for (Map.Entry<String, CowTextureDefinition.AtlasCoordinate> entry : 
                 variantInfo.getVariantDefinition().getFaceMappings().entrySet()) {
                
                String faceName = entry.getKey();
                CowTextureDefinition.AtlasCoordinate coord = entry.getValue();
                
                Map<String, Object> faceData = new HashMap<>();
                faceData.put("atlasX", coord.getAtlasX());
                faceData.put("atlasY", coord.getAtlasY());
                
                float[] uvCoords = TextureManager.getNormalizedUVCoordinates(variantName, faceName);
                faceData.put("uvMin", Arrays.asList(uvCoords[0], uvCoords[1]));
                faceData.put("uvMax", Arrays.asList(uvCoords[2], uvCoords[3]));
                faceData.put("uvArray", Arrays.asList(uvCoords[0], uvCoords[1], uvCoords[2], uvCoords[3]));
                
                faceMappings.put(faceName, faceData);
            }
            
            atlasData.put("faceMappings", faceMappings);
        }
        
        // Custom annotations
        if (!config.getCustomAnnotations().isEmpty()) {
            atlasData.put("customAnnotations", config.getCustomAnnotations());
        }
        
        // Write JSON
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            writeJsonObject(writer, atlasData, 0);
        }
    }
    
    /**
     * Collect data for a texture variant.
     */
    private Map<String, Object> collectVariantData(String variantName) {
        Map<String, Object> data = new HashMap<>();
        
        try {
            TextureManager.TextureVariantInfo info = TextureManager.getVariantInfo(variantName);
            if (info != null) {
                data.put("displayName", info.getDisplayName());
                data.put("faceMappingCount", info.getFaceMappingCount());
                data.put("drawingInstructionCount", info.getDrawingInstructionCount());
                data.put("baseColors", info.getBaseColors());
                
                // Validation status
                data.put("isValid", TextureManager.validateVariant(variantName));
                data.put("coordinatesValid", TextureManager.validateCoordinates(variantName));
                
                // Face names
                data.put("faceNames", TextureManager.getFaceNames(variantName));
            }
        } catch (Exception e) {
            data.put("error", e.getMessage());
            logger.error("Error collecting variant data for: {}", variantName, e);
        }
        
        return data;
    }
    
    /**
     * Generate filename for atlas export.
     */
    private String generateFilename(AtlasExportConfig config, String variantName) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String resolution = config.getResolution().getResolution() + "x" + config.getResolution().getResolution();
        
        StringBuilder filename = new StringBuilder();
        filename.append("atlas_").append(variantName).append("_").append(resolution);
        
        switch (config.getFormat()) {
            case PNG_WITH_GRID:
                filename.append("_grid");
                break;
            case PNG_CLEAN:
                filename.append("_clean");
                break;
            case SVG_ANNOTATED:
                filename.append("_annotated");
                break;
            case PDF_REFERENCE:
                filename.append("_reference");
                break;
            case JSON_DATA:
                filename.append("_data");
                break;
        }
        
        filename.append("_").append(timestamp);
        filename.append(".").append(config.getFormat().getExtension());
        
        return filename.toString();
    }
    
    /**
     * Generate summary report for the export.
     */
    private String generateSummaryReport(AtlasExportConfig config, 
                                       Map<String, Map<String, Object>> variantData,
                                       List<File> exportedFiles) {
        StringBuilder report = new StringBuilder();
        
        report.append("TEXTURE ATLAS EXPORT SUMMARY\n");
        report.append("============================\n\n");
        
        report.append("Export Configuration:\n");
        report.append("- Format: ").append(config.getFormat().getDescription()).append("\n");
        report.append("- Resolution: ").append(config.getResolution().getDescription()).append("\n");
        report.append("- Output Directory: ").append(config.getOutputDirectory()).append("\n");
        report.append("- Include Grid: ").append(config.isIncludeGridLines()).append("\n");
        report.append("- Include Labels: ").append(config.isIncludeCoordinateLabels()).append("\n");
        report.append("- Include Legend: ").append(config.isIncludeLegend()).append("\n");
        report.append("\n");
        
        report.append("Export Results:\n");
        report.append("- Variants Processed: ").append(variantData.size()).append("\n");
        report.append("- Files Generated: ").append(exportedFiles.size()).append("\n");
        report.append("- Total Size: ").append(formatFileSize(
            exportedFiles.stream().mapToLong(File::length).sum())).append("\n");
        report.append("\n");
        
        report.append("Variant Details:\n");
        for (Map.Entry<String, Map<String, Object>> entry : variantData.entrySet()) {
            String variant = entry.getKey();
            Map<String, Object> data = entry.getValue();
            
            report.append("- ").append(variant).append(":\n");
            report.append("  - Display Name: ").append(data.get("displayName")).append("\n");
            report.append("  - Face Count: ").append(data.get("faceMappingCount")).append("\n");
            report.append("  - Valid: ").append(data.get("isValid")).append("\n");
            report.append("\n");
        }
        
        report.append("Generated Files:\n");
        for (File file : exportedFiles) {
            report.append("- ").append(file.getName()).append(" (")
                  .append(formatFileSize(file.length())).append(")\n");
        }
        
        return report.toString();
    }
    
    // Utility methods
    
    private Map<String, Color> createFaceTypeColorMap(AtlasExportConfig config) {
        Map<String, Color> colorMap = new HashMap<>();
        colorMap.put("head", Color.rgb(255, 200, 200, 0.7));
        colorMap.put("body", Color.rgb(200, 255, 200, 0.7));
        colorMap.put("leg", Color.rgb(200, 200, 255, 0.7));
        colorMap.put("horn", Color.rgb(255, 255, 200, 0.7));
        colorMap.put("udder", Color.rgb(255, 200, 255, 0.7));
        colorMap.put("tail", Color.rgb(200, 255, 255, 0.7));
        colorMap.put("default", Color.rgb(220, 220, 220, 0.7));
        return colorMap;
    }
    
    private Color getFaceTypeColor(String faceName, Map<String, Color> colorMap) {
        String faceType = faceName.toLowerCase();
        
        for (String type : colorMap.keySet()) {
            if (faceType.contains(type)) {
                return colorMap.get(type);
            }
        }
        
        return colorMap.get("default");
    }
    
    private String abbreviateFaceName(String faceName, int cellSize) {
        if (cellSize < 32) {
            // Very small cells - use initials
            return faceName.substring(0, Math.min(2, faceName.length())).toUpperCase();
        } else if (cellSize < 64) {
            // Medium cells - use abbreviated name
            return faceName.length() > 8 ? faceName.substring(0, 8) + "..." : faceName;
        } else {
            // Large cells - use full name
            return faceName;
        }
    }
    
    private String colorToHex(Color color) {
        int r = (int) Math.round(color.getRed() * 255);
        int g = (int) Math.round(color.getGreen() * 255);
        int b = (int) Math.round(color.getBlue() * 255);
        return String.format("#%02X%02X%02X", r, g, b);
    }
    
    private String escapeXml(String text) {
        return text.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&apos;");
    }
    
    private void writeJsonObject(PrintWriter writer, Map<String, Object> obj, int indent) {
        String indentStr = "  ".repeat(indent);
        writer.println(indentStr + "{");
        
        int i = 0;
        for (Map.Entry<String, Object> entry : obj.entrySet()) {
            writer.print(indentStr + "  \"" + entry.getKey() + "\": ");
            writeJsonValue(writer, entry.getValue(), indent + 1);
            if (i < obj.size() - 1) {
                writer.print(",");
            }
            writer.println();
            i++;
        }
        
        writer.print(indentStr + "}");
    }
    
    @SuppressWarnings("unchecked")
    private void writeJsonValue(PrintWriter writer, Object value, int indent) {
        if (value == null) {
            writer.print("null");
        } else if (value instanceof String) {
            writer.print("\"" + value.toString().replace("\"", "\\\"") + "\"");
        } else if (value instanceof Number || value instanceof Boolean) {
            writer.print(value.toString());
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            writer.print("[");
            for (int i = 0; i < list.size(); i++) {
                writeJsonValue(writer, list.get(i), indent);
                if (i < list.size() - 1) {
                    writer.print(", ");
                }
            }
            writer.print("]");
        } else if (value instanceof Map) {
            writer.println();
            writeJsonObject(writer, (Map<String, Object>) value, indent);
        } else {
            writer.print("\"" + value.toString() + "\"");
        }
    }
    
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}