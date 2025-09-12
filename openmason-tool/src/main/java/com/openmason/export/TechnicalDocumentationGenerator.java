package com.openmason.export;

import com.openmason.model.ModelManager;
import com.stonebreak.model.ModelDefinition;
import com.openmason.texture.TextureManager;
import com.stonebreak.textures.mobs.CowTextureDefinition;
import com.openmason.coordinates.ModelCoordinateSystem;
import com.openmason.coordinates.AtlasCoordinateSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * Technical Documentation Generator for Open Mason Phase 8.
 * 
 * Generates comprehensive technical documentation including:
 * - Model specifications and metadata
 * - Coordinate mapping documentation
 * - Texture atlas reference sheets
 * - Professional report formatting (HTML, Markdown, PDF)
 * - Cross-referenced documentation with hyperlinks
 */
public class TechnicalDocumentationGenerator {
    
    private static final Logger logger = LoggerFactory.getLogger(TechnicalDocumentationGenerator.class);
    
    // Documentation formats
    public enum DocumentFormat {
        HTML("html", "HTML Document", true, true),
        MARKDOWN("md", "Markdown Document", false, true),
        PLAIN_TEXT("txt", "Plain Text", false, false),
        JSON("json", "JSON Data", false, false),
        CSV("csv", "CSV Data", false, false);
        
        private final String extension;
        private final String description;
        private final boolean supportsFormatting;
        private final boolean supportsLinks;
        
        DocumentFormat(String extension, String description, boolean supportsFormatting, boolean supportsLinks) {
            this.extension = extension;
            this.description = description;
            this.supportsFormatting = supportsFormatting;
            this.supportsLinks = supportsLinks;
        }
        
        public String getExtension() { return extension; }
        public String getDescription() { return description; }
        public boolean supportsFormatting() { return supportsFormatting; }
        public boolean supportsLinks() { return supportsLinks; }
    }
    
    // Documentation configuration
    public static class DocumentationConfig {
        private DocumentFormat format = DocumentFormat.HTML;
        private String outputDirectory = "documentation";
        private String projectName = "Open Mason Model Documentation";
        private String version = "1.0";
        private boolean includeModelSpecs = true;
        private boolean includeTextureSpecs = true;
        private boolean includeCoordinateMaps = true;
        private boolean includeStatistics = true;
        private boolean includeImages = true;
        private boolean includeSourceCode = false;
        private boolean generateIndex = true;
        private boolean useCompactFormat = false;
        private String customCSS = null;
        private Map<String, String> customMetadata = new HashMap<>();
        
        // Getters and setters
        public DocumentFormat getFormat() { return format; }
        public void setFormat(DocumentFormat format) { this.format = format; }
        
        public String getOutputDirectory() { return outputDirectory; }
        public void setOutputDirectory(String outputDirectory) { this.outputDirectory = outputDirectory; }
        
        public String getProjectName() { return projectName; }
        public void setProjectName(String projectName) { this.projectName = projectName; }
        
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        
        public boolean isIncludeModelSpecs() { return includeModelSpecs; }
        public void setIncludeModelSpecs(boolean includeModelSpecs) { this.includeModelSpecs = includeModelSpecs; }
        
        public boolean isIncludeTextureSpecs() { return includeTextureSpecs; }
        public void setIncludeTextureSpecs(boolean includeTextureSpecs) { this.includeTextureSpecs = includeTextureSpecs; }
        
        public boolean isIncludeCoordinateMaps() { return includeCoordinateMaps; }
        public void setIncludeCoordinateMaps(boolean includeCoordinateMaps) { this.includeCoordinateMaps = includeCoordinateMaps; }
        
        public boolean isIncludeStatistics() { return includeStatistics; }
        public void setIncludeStatistics(boolean includeStatistics) { this.includeStatistics = includeStatistics; }
        
        public boolean isIncludeImages() { return includeImages; }
        public void setIncludeImages(boolean includeImages) { this.includeImages = includeImages; }
        
        public boolean isIncludeSourceCode() { return includeSourceCode; }
        public void setIncludeSourceCode(boolean includeSourceCode) { this.includeSourceCode = includeSourceCode; }
        
        public boolean isGenerateIndex() { return generateIndex; }
        public void setGenerateIndex(boolean generateIndex) { this.generateIndex = generateIndex; }
        
        public boolean isUseCompactFormat() { return useCompactFormat; }
        public void setUseCompactFormat(boolean useCompactFormat) { this.useCompactFormat = useCompactFormat; }
        
        public String getCustomCSS() { return customCSS; }
        public void setCustomCSS(String customCSS) { this.customCSS = customCSS; }
        
        public Map<String, String> getCustomMetadata() { return customMetadata; }
        public void setCustomMetadata(Map<String, String> customMetadata) { this.customMetadata = customMetadata; }
    }
    
    // Documentation generation result
    public static class DocumentationResult {
        private final List<File> generatedFiles;
        private final Map<String, Object> statistics;
        private final long generationTime;
        private final String indexFile;
        
        public DocumentationResult(List<File> generatedFiles, Map<String, Object> statistics, 
                                 long generationTime, String indexFile) {
            this.generatedFiles = generatedFiles;
            this.statistics = statistics;
            this.generationTime = generationTime;
            this.indexFile = indexFile;
        }
        
        public List<File> getGeneratedFiles() { return generatedFiles; }
        public Map<String, Object> getStatistics() { return statistics; }
        public long getGenerationTime() { return generationTime; }
        public String getIndexFile() { return indexFile; }
    }
    
    // Progress callback interface
    public interface DocumentationProgressCallback {
        void onProgress(String stage, int progress, String details);
        void onFileGenerated(String fileName, long fileSize);
        void onComplete(DocumentationResult result);
        void onError(String stage, Throwable error);
    }
    
    /**
     * Generate comprehensive technical documentation.
     * 
     * @param config Documentation configuration
     * @param progressCallback Optional progress callback
     * @return CompletableFuture that resolves to DocumentationResult
     */
    public CompletableFuture<DocumentationResult> generateDocumentationAsync(DocumentationConfig config,
                                                                           DocumentationProgressCallback progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            List<File> generatedFiles = new ArrayList<>();
            Map<String, Object> statistics = new HashMap<>();
            
            try {
                if (progressCallback != null) {
                    progressCallback.onProgress("Initialization", 0, "Starting documentation generation");
                }
                
                // Create output directory
                Path outputDir = Paths.get(config.getOutputDirectory());
                Files.createDirectories(outputDir);
                
                // Initialize documentation builder
                DocumentBuilder builder = createDocumentBuilder(config);
                
                if (progressCallback != null) {
                    progressCallback.onProgress("Data Collection", 10, "Collecting model and texture data");
                }
                
                // Collect all data
                List<String> modelNames = ModelManager.getAvailableModels();
                List<String> textureVariants = TextureManager.getAvailableVariants();
                
                statistics.put("modelCount", modelNames.size());
                statistics.put("textureVariantCount", textureVariants.size());
                
                // Generate index page if requested
                String indexFile = null;
                if (config.isGenerateIndex()) {
                    if (progressCallback != null) {
                        progressCallback.onProgress("Index Generation", 15, "Generating index page");
                    }
                    indexFile = generateIndexPage(builder, config, outputDir, modelNames, textureVariants);
                    generatedFiles.add(new File(outputDir.toFile(), indexFile));
                }
                
                // Generate model documentation
                if (config.isIncludeModelSpecs()) {
                    if (progressCallback != null) {
                        progressCallback.onProgress("Model Documentation", 25, "Generating model specifications");
                    }
                    List<File> modelFiles = generateModelDocumentation(builder, config, outputDir, 
                        modelNames, progressCallback);
                    generatedFiles.addAll(modelFiles);
                }
                
                // Generate texture documentation
                if (config.isIncludeTextureSpecs()) {
                    if (progressCallback != null) {
                        progressCallback.onProgress("Texture Documentation", 50, "Generating texture specifications");
                    }
                    List<File> textureFiles = generateTextureDocumentation(builder, config, outputDir, 
                        textureVariants, progressCallback);
                    generatedFiles.addAll(textureFiles);
                }
                
                // Generate coordinate mapping documentation
                if (config.isIncludeCoordinateMaps()) {
                    if (progressCallback != null) {
                        progressCallback.onProgress("Coordinate Documentation", 75, "Generating coordinate mappings");
                    }
                    List<File> coordFiles = generateCoordinateDocumentation(builder, config, outputDir, 
                        textureVariants, progressCallback);
                    generatedFiles.addAll(coordFiles);
                }
                
                // Generate statistics summary
                if (config.isIncludeStatistics()) {
                    if (progressCallback != null) {
                        progressCallback.onProgress("Statistics", 90, "Generating statistics summary");
                    }
                    File statsFile = generateStatisticsPage(builder, config, outputDir, statistics);
                    generatedFiles.add(statsFile);
                }
                
                // Copy CSS and assets if HTML format
                if (config.getFormat() == DocumentFormat.HTML) {
                    copyStaticAssets(config, outputDir);
                }
                
                long generationTime = System.currentTimeMillis() - startTime;
                statistics.put("generationTime", generationTime);
                statistics.put("fileCount", generatedFiles.size());
                statistics.put("totalSize", generatedFiles.stream().mapToLong(File::length).sum());
                
                DocumentationResult result = new DocumentationResult(generatedFiles, statistics, 
                    generationTime, indexFile);
                
                if (progressCallback != null) {
                    progressCallback.onComplete(result);
                }
                
                logger.info("Documentation generation completed: {} files generated in {} ms", 
                    generatedFiles.size(), generationTime);
                
                return result;
                
            } catch (Exception e) {
                logger.error("Documentation generation failed", e);
                if (progressCallback != null) {
                    progressCallback.onError("Generation", e);
                }
                throw new RuntimeException("Documentation generation failed", e);
            }
        });
    }
    
    /**
     * Generate documentation synchronously.
     */
    public DocumentationResult generateDocumentation(DocumentationConfig config) {
        try {
            return generateDocumentationAsync(config, null).get();
        } catch (Exception e) {
            throw new RuntimeException("Synchronous documentation generation failed", e);
        }
    }
    
    /**
     * Create appropriate document builder for the format.
     */
    private DocumentBuilder createDocumentBuilder(DocumentationConfig config) {
        switch (config.getFormat()) {
            case HTML:
                return new HtmlDocumentBuilder(config);
            case MARKDOWN:
                return new MarkdownDocumentBuilder(config);
            case PLAIN_TEXT:
                return new PlainTextDocumentBuilder(config);
            case JSON:
                return new JsonDocumentBuilder(config);
            case CSV:
                return new CsvDocumentBuilder(config);
            default:
                throw new IllegalArgumentException("Unsupported format: " + config.getFormat());
        }
    }
    
    /**
     * Generate index page with navigation.
     */
    private String generateIndexPage(DocumentBuilder builder, DocumentationConfig config, 
                                   Path outputDir, List<String> modelNames, 
                                   List<String> textureVariants) throws IOException {
        String fileName = "index." + config.getFormat().getExtension();
        File indexFile = new File(outputDir.toFile(), fileName);
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(indexFile))) {
            builder.writeDocumentHeader(writer, "Documentation Index", 
                "Generated on " + LocalDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME));
            
            builder.writeHeading(writer, 1, config.getProjectName() + " Documentation");
            builder.writeParagraph(writer, "Version: " + config.getVersion());
            builder.writeParagraph(writer, "Generated: " + 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            
            // Table of contents
            builder.writeHeading(writer, 2, "Table of Contents");
            builder.writeListStart(writer);
            
            if (config.isIncludeModelSpecs()) {
                builder.writeListItem(writer, builder.createLink("models.html", "Model Specifications"));
            }
            if (config.isIncludeTextureSpecs()) {
                builder.writeListItem(writer, builder.createLink("textures.html", "Texture Specifications"));
            }
            if (config.isIncludeCoordinateMaps()) {
                builder.writeListItem(writer, builder.createLink("coordinates.html", "Coordinate Mappings"));
            }
            if (config.isIncludeStatistics()) {
                builder.writeListItem(writer, builder.createLink("statistics.html", "Statistics Summary"));
            }
            
            builder.writeListEnd(writer);
            
            // Quick stats
            builder.writeHeading(writer, 2, "Overview");
            builder.writeParagraph(writer, "Models: " + modelNames.size());
            builder.writeParagraph(writer, "Texture Variants: " + textureVariants.size());
            
            // Custom metadata
            if (!config.getCustomMetadata().isEmpty()) {
                builder.writeHeading(writer, 2, "Project Information");
                for (Map.Entry<String, String> entry : config.getCustomMetadata().entrySet()) {
                    builder.writeParagraph(writer, entry.getKey() + ": " + entry.getValue());
                }
            }
            
            builder.writeDocumentFooter(writer);
        }
        
        return fileName;
    }
    
    /**
     * Generate model documentation files.
     */
    private List<File> generateModelDocumentation(DocumentBuilder builder, DocumentationConfig config,
                                                Path outputDir, List<String> modelNames,
                                                DocumentationProgressCallback progressCallback) throws IOException {
        List<File> files = new ArrayList<>();
        
        // Generate main models page
        String fileName = "models." + config.getFormat().getExtension();
        File modelsFile = new File(outputDir.toFile(), fileName);
        files.add(modelsFile);
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(modelsFile))) {
            builder.writeDocumentHeader(writer, "Model Specifications", null);
            builder.writeHeading(writer, 1, "Model Specifications");
            
            for (int i = 0; i < modelNames.size(); i++) {
                String modelName = modelNames.get(i);
                
                if (progressCallback != null) {
                    int progress = 25 + (i * 25 / modelNames.size());
                    progressCallback.onProgress("Model Documentation", progress, 
                        "Processing model: " + modelName);
                }
                
                generateModelSection(builder, writer, modelName, config);
                
                // Generate separate detailed file for each model
                if (!config.isUseCompactFormat()) {
                    File detailFile = generateDetailedModelFile(builder, config, outputDir, modelName);
                    files.add(detailFile);
                }
            }
            
            builder.writeDocumentFooter(writer);
        }
        
        return files;
    }
    
    /**
     * Generate a detailed model documentation section.
     */
    private void generateModelSection(DocumentBuilder builder, PrintWriter writer, 
                                    String modelName, DocumentationConfig config) {
        try {
            builder.writeHeading(writer, 2, "Model: " + modelName);
            
            // Get model definition - ModelLoader now throws exceptions instead of returning null
            ModelDefinition.CowModelDefinition modelDef = 
                com.stonebreak.model.ModelLoader.getCowModel(modelName);
            
            // Basic information
            builder.writeParagraph(writer, "Model Name: " + modelName);
            builder.writeParagraph(writer, "Model Type: " + (modelDef.getDisplayName() != null ? modelDef.getDisplayName() : modelDef.getModelName()));
            
            // Parts count
            ModelDefinition.ModelParts parts = modelDef.getParts();
            int partCount = 0;
            if (parts != null) {
                if (parts.getBody() != null) partCount++;
                if (parts.getHead() != null) partCount++;
                if (parts.getLegs() != null) partCount += parts.getLegs().size();
                if (parts.getHorns() != null) partCount += parts.getHorns().size();
                if (parts.getUdder() != null) partCount++;
                if (parts.getTail() != null) partCount++;
            }
            builder.writeParagraph(writer, "Part Count: " + partCount);
            
            // Parts details
            if (parts != null && !config.isUseCompactFormat()) {
                builder.writeHeading(writer, 3, "Model Parts");
                builder.writeTableStart(writer, new String[]{"Part", "Position", "Size", "Texture"});
                
                if (parts.getBody() != null) {
                    writePartRow(builder, writer, "Body", parts.getBody());
                }
                if (parts.getHead() != null) {
                    writePartRow(builder, writer, "Head", parts.getHead());
                }
                if (parts.getLegs() != null) {
                    for (int i = 0; i < parts.getLegs().size(); i++) {
                        writePartRow(builder, writer, "Leg " + (i + 1), parts.getLegs().get(i));
                    }
                }
                if (parts.getHorns() != null) {
                    for (int i = 0; i < parts.getHorns().size(); i++) {
                        writePartRow(builder, writer, "Horn " + (i + 1), parts.getHorns().get(i));
                    }
                }
                if (parts.getUdder() != null) {
                    writePartRow(builder, writer, "Udder", parts.getUdder());
                }
                if (parts.getTail() != null) {
                    writePartRow(builder, writer, "Tail", parts.getTail());
                }
                
                builder.writeTableEnd(writer);
            }
            
        } catch (Exception e) {
            builder.writeParagraph(writer, "Error loading model: " + e.getMessage());
            logger.error("Error documenting model: {}", modelName, e);
        }
    }
    
    /**
     * Write a model part row to the table.
     */
    private void writePartRow(DocumentBuilder builder, PrintWriter writer, String partName, 
                            ModelDefinition.ModelPart part) {
        String position = String.format("(%.1f, %.1f, %.1f)", 
            part.getPosition().getX(), part.getPosition().getY(), part.getPosition().getZ());
        String size = String.format("%.1f × %.1f × %.1f", 
            part.getSize().getX(), part.getSize().getY(), part.getSize().getZ());
        String texture = part.getTexture() != null ? part.getTexture() : "default";
        
        builder.writeTableRow(writer, new String[]{partName, position, size, texture});
    }
    
    /**
     * Generate detailed model file.
     */
    private File generateDetailedModelFile(DocumentBuilder builder, DocumentationConfig config,
                                         Path outputDir, String modelName) throws IOException {
        String fileName = "model_" + modelName.replaceAll("[^a-zA-Z0-9]", "_") + 
                         "." + config.getFormat().getExtension();
        File modelFile = new File(outputDir.toFile(), fileName);
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(modelFile))) {
            builder.writeDocumentHeader(writer, "Model: " + modelName, null);
            generateModelSection(builder, writer, modelName, config);
            builder.writeDocumentFooter(writer);
        }
        
        return modelFile;
    }
    
    /**
     * Generate texture documentation files.
     */
    private List<File> generateTextureDocumentation(DocumentBuilder builder, DocumentationConfig config,
                                                  Path outputDir, List<String> textureVariants,
                                                  DocumentationProgressCallback progressCallback) throws IOException {
        List<File> files = new ArrayList<>();
        
        String fileName = "textures." + config.getFormat().getExtension();
        File texturesFile = new File(outputDir.toFile(), fileName);
        files.add(texturesFile);
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(texturesFile))) {
            builder.writeDocumentHeader(writer, "Texture Specifications", null);
            builder.writeHeading(writer, 1, "Texture Specifications");
            
            for (int i = 0; i < textureVariants.size(); i++) {
                String variant = textureVariants.get(i);
                
                if (progressCallback != null) {
                    int progress = 50 + (i * 25 / textureVariants.size());
                    progressCallback.onProgress("Texture Documentation", progress, 
                        "Processing texture: " + variant);
                }
                
                generateTextureSection(builder, writer, variant, config);
            }
            
            builder.writeDocumentFooter(writer);
        }
        
        return files;
    }
    
    /**
     * Generate texture variant section.
     */
    private void generateTextureSection(DocumentBuilder builder, PrintWriter writer, 
                                      String variantName, DocumentationConfig config) {
        try {
            builder.writeHeading(writer, 2, "Texture Variant: " + variantName);
            
            TextureManager.TextureVariantInfo info = TextureManager.getVariantInfo(variantName);
            if (info != null) {
                builder.writeParagraph(writer, "Display Name: " + info.getDisplayName());
                builder.writeParagraph(writer, "Face Mappings: " + info.getFaceMappingCount());
                builder.writeParagraph(writer, "Drawing Instructions: " + info.getDrawingInstructionCount());
                
                // Base colors
                Map<String, String> colors = info.getBaseColors();
                if (!colors.isEmpty()) {
                    builder.writeHeading(writer, 3, "Base Colors");
                    builder.writeTableStart(writer, new String[]{"Color Type", "Value"});
                    for (Map.Entry<String, String> entry : colors.entrySet()) {
                        builder.writeTableRow(writer, new String[]{entry.getKey(), entry.getValue()});
                    }
                    builder.writeTableEnd(writer);
                }
                
                // Face mappings (if not compact)
                if (!config.isUseCompactFormat()) {
                    CowTextureDefinition.CowVariant variant = info.getVariantDefinition();
                    if (variant != null && variant.getFaceMappings() != null) {
                        builder.writeHeading(writer, 3, "Face Mappings");
                        builder.writeTableStart(writer, new String[]{"Face", "Atlas X", "Atlas Y"});
                        
                        for (Map.Entry<String, CowTextureDefinition.AtlasCoordinate> entry : 
                             variant.getFaceMappings().entrySet()) {
                            CowTextureDefinition.AtlasCoordinate coord = entry.getValue();
                            builder.writeTableRow(writer, new String[]{
                                entry.getKey(),
                                String.valueOf(coord.getAtlasX()),
                                String.valueOf(coord.getAtlasY())
                            });
                        }
                        
                        builder.writeTableEnd(writer);
                    }
                }
                
            } else {
                builder.writeParagraph(writer, "Texture variant information not available.");
            }
            
        } catch (Exception e) {
            builder.writeParagraph(writer, "Error loading texture variant: " + e.getMessage());
            logger.error("Error documenting texture variant: {}", variantName, e);
        }
    }
    
    /**
     * Generate coordinate mapping documentation.
     */
    private List<File> generateCoordinateDocumentation(DocumentBuilder builder, DocumentationConfig config,
                                                     Path outputDir, List<String> textureVariants,
                                                     DocumentationProgressCallback progressCallback) throws IOException {
        List<File> files = new ArrayList<>();
        
        String fileName = "coordinates." + config.getFormat().getExtension();
        File coordFile = new File(outputDir.toFile(), fileName);
        files.add(coordFile);
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(coordFile))) {
            builder.writeDocumentHeader(writer, "Coordinate Mappings", null);
            builder.writeHeading(writer, 1, "Coordinate System Documentation");
            
            // Atlas coordinate system
            builder.writeHeading(writer, 2, "Atlas Coordinate System");
            builder.writeParagraph(writer, "The texture atlas uses a 16×16 grid system for UV mapping.");
            builder.writeParagraph(writer, "Each texture face occupies one cell in the atlas grid.");
            
            // Model coordinate system
            builder.writeHeading(writer, 2, "Model Coordinate System");
            builder.writeParagraph(writer, "Models use a 3D coordinate system with the following conventions:");
            builder.writeListStart(writer);
            builder.writeListItem(writer, "X-axis: Left (-) to Right (+)");
            builder.writeListItem(writer, "Y-axis: Bottom (-) to Top (+)");
            builder.writeListItem(writer, "Z-axis: Back (-) to Front (+)");
            builder.writeListEnd(writer);
            
            // Coordinate reference for each variant
            for (String variant : textureVariants) {
                generateVariantCoordinateSection(builder, writer, variant, config);
            }
            
            builder.writeDocumentFooter(writer);
        }
        
        return files;
    }
    
    /**
     * Generate coordinate section for a specific variant.
     */
    private void generateVariantCoordinateSection(DocumentBuilder builder, PrintWriter writer,
                                                String variantName, DocumentationConfig config) {
        builder.writeHeading(writer, 3, "Coordinates for " + variantName);
        
        try {
            TextureManager.TextureVariantInfo info = TextureManager.getVariantInfo(variantName);
            if (info != null && info.getVariantDefinition().getFaceMappings() != null) {
                
                builder.writeTableStart(writer, new String[]{
                    "Face", "Atlas Coord", "UV Min", "UV Max", "Normalized UV"
                });
                
                for (Map.Entry<String, CowTextureDefinition.AtlasCoordinate> entry : 
                     info.getVariantDefinition().getFaceMappings().entrySet()) {
                    
                    String faceName = entry.getKey();
                    CowTextureDefinition.AtlasCoordinate coord = entry.getValue();
                    
                    // Calculate UV coordinates
                    float[] uvCoords = TextureManager.getNormalizedUVCoordinates(variantName, faceName);
                    
                    String atlasCoord = String.format("(%d, %d)", coord.getAtlasX(), coord.getAtlasY());
                    String uvMin = String.format("(%.3f, %.3f)", uvCoords[0], uvCoords[1]);
                    String uvMax = String.format("(%.3f, %.3f)", uvCoords[2], uvCoords[3]);
                    String normalizedUV = String.format("[%.3f, %.3f, %.3f, %.3f]", 
                        uvCoords[0], uvCoords[1], uvCoords[2], uvCoords[3]);
                    
                    builder.writeTableRow(writer, new String[]{
                        faceName, atlasCoord, uvMin, uvMax, normalizedUV
                    });
                }
                
                builder.writeTableEnd(writer);
            }
            
        } catch (Exception e) {
            builder.writeParagraph(writer, "Error generating coordinates for " + variantName + ": " + e.getMessage());
            logger.error("Error generating coordinates for variant: {}", variantName, e);
        }
    }
    
    /**
     * Generate statistics summary page.
     */
    private File generateStatisticsPage(DocumentBuilder builder, DocumentationConfig config,
                                      Path outputDir, Map<String, Object> statistics) throws IOException {
        String fileName = "statistics." + config.getFormat().getExtension();
        File statsFile = new File(outputDir.toFile(), fileName);
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(statsFile))) {
            builder.writeDocumentHeader(writer, "Statistics Summary", null);
            builder.writeHeading(writer, 1, "Project Statistics");
            
            // Basic statistics
            builder.writeHeading(writer, 2, "Overview");
            builder.writeParagraph(writer, "Models: " + statistics.get("modelCount"));
            builder.writeParagraph(writer, "Texture Variants: " + statistics.get("textureVariantCount"));
            builder.writeParagraph(writer, "Generated Files: " + statistics.get("fileCount"));
            builder.writeParagraph(writer, "Total Size: " + formatFileSize((Long) statistics.get("totalSize")));
            builder.writeParagraph(writer, "Generation Time: " + statistics.get("generationTime") + " ms");
            
            // System information
            builder.writeHeading(writer, 2, "System Information");
            builder.writeParagraph(writer, "Java Version: " + System.getProperty("java.version"));
            builder.writeParagraph(writer, "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
            builder.writeParagraph(writer, "Architecture: " + System.getProperty("os.arch"));
            
            Runtime runtime = Runtime.getRuntime();
            builder.writeParagraph(writer, "Available Memory: " + formatFileSize(runtime.maxMemory()));
            builder.writeParagraph(writer, "Used Memory: " + formatFileSize(runtime.totalMemory() - runtime.freeMemory()));
            
            builder.writeDocumentFooter(writer);
        }
        
        return statsFile;
    }
    
    /**
     * Copy static assets for HTML documentation.
     */
    private void copyStaticAssets(DocumentationConfig config, Path outputDir) throws IOException {
        if (config.getCustomCSS() != null) {
            // Copy custom CSS
            Path cssFile = Paths.get(config.getCustomCSS());
            if (Files.exists(cssFile)) {
                Files.copy(cssFile, outputDir.resolve("styles.css"));
            }
        } else {
            // Create default CSS
            createDefaultCSS(outputDir);
        }
    }
    
    /**
     * Create default CSS for HTML documentation.
     */
    private void createDefaultCSS(Path outputDir) throws IOException {
        File cssFile = new File(outputDir.toFile(), "styles.css");
        try (PrintWriter writer = new PrintWriter(new FileWriter(cssFile))) {
            writer.println("/* Open Mason Documentation Styles */");
            writer.println("body { font-family: Arial, sans-serif; margin: 20px; line-height: 1.6; }");
            writer.println("h1 { color: #2c3e50; border-bottom: 2px solid #3498db; }");
            writer.println("h2 { color: #34495e; margin-top: 30px; }");
            writer.println("h3 { color: #7f8c8d; }");
            writer.println("table { border-collapse: collapse; width: 100%; margin: 20px 0; }");
            writer.println("th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }");
            writer.println("th { background-color: #f2f2f2; font-weight: bold; }");
            writer.println("tr:nth-child(even) { background-color: #f9f9f9; }");
            writer.println("code { background-color: #f8f8f8; padding: 2px 4px; border-radius: 3px; }");
            writer.println("pre { background-color: #f8f8f8; padding: 10px; border-radius: 5px; overflow-x: auto; }");
            writer.println("a { color: #3498db; text-decoration: none; }");
            writer.println("a:hover { text-decoration: underline; }");
            writer.println(".header { background-color: #ecf0f1; padding: 20px; margin-bottom: 20px; border-radius: 5px; }");
            writer.println(".footer { margin-top: 40px; padding-top: 20px; border-top: 1px solid #ddd; color: #7f8c8d; }");
        }
    }
    
    /**
     * Format file size in human-readable format.
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
    
    // Abstract document builder interface
    private abstract static class DocumentBuilder {
        protected final DocumentationConfig config;
        
        public DocumentBuilder(DocumentationConfig config) {
            this.config = config;
        }
        
        public abstract void writeDocumentHeader(PrintWriter writer, String title, String description);
        public abstract void writeDocumentFooter(PrintWriter writer);
        public abstract void writeHeading(PrintWriter writer, int level, String text);
        public abstract void writeParagraph(PrintWriter writer, String text);
        public abstract void writeListStart(PrintWriter writer);
        public abstract void writeListEnd(PrintWriter writer);
        public abstract void writeListItem(PrintWriter writer, String text);
        public abstract void writeTableStart(PrintWriter writer, String[] headers);
        public abstract void writeTableEnd(PrintWriter writer);
        public abstract void writeTableRow(PrintWriter writer, String[] cells);
        public abstract String createLink(String url, String text);
    }
    
    // HTML document builder
    private static class HtmlDocumentBuilder extends DocumentBuilder {
        public HtmlDocumentBuilder(DocumentationConfig config) {
            super(config);
        }
        
        @Override
        public void writeDocumentHeader(PrintWriter writer, String title, String description) {
            writer.println("<!DOCTYPE html>");
            writer.println("<html lang=\"en\">");
            writer.println("<head>");
            writer.println("    <meta charset=\"UTF-8\">");
            writer.println("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
            writer.println("    <title>" + escapeHtml(title) + "</title>");
            writer.println("    <link rel=\"stylesheet\" href=\"styles.css\">");
            writer.println("</head>");
            writer.println("<body>");
            if (description != null) {
                writer.println("    <div class=\"header\">");
                writer.println("        <p>" + escapeHtml(description) + "</p>");
                writer.println("    </div>");
            }
        }
        
        @Override
        public void writeDocumentFooter(PrintWriter writer) {
            writer.println("    <div class=\"footer\">");
            writer.println("        <p>Generated by Open Mason Documentation System</p>");
            writer.println("    </div>");
            writer.println("</body>");
            writer.println("</html>");
        }
        
        @Override
        public void writeHeading(PrintWriter writer, int level, String text) {
            writer.println("    <h" + level + ">" + escapeHtml(text) + "</h" + level + ">");
        }
        
        @Override
        public void writeParagraph(PrintWriter writer, String text) {
            writer.println("    <p>" + escapeHtml(text) + "</p>");
        }
        
        @Override
        public void writeListStart(PrintWriter writer) {
            writer.println("    <ul>");
        }
        
        @Override
        public void writeListEnd(PrintWriter writer) {
            writer.println("    </ul>");
        }
        
        @Override
        public void writeListItem(PrintWriter writer, String text) {
            writer.println("        <li>" + text + "</li>");
        }
        
        @Override
        public void writeTableStart(PrintWriter writer, String[] headers) {
            writer.println("    <table>");
            writer.println("        <thead>");
            writer.println("            <tr>");
            for (String header : headers) {
                writer.println("                <th>" + escapeHtml(header) + "</th>");
            }
            writer.println("            </tr>");
            writer.println("        </thead>");
            writer.println("        <tbody>");
        }
        
        @Override
        public void writeTableEnd(PrintWriter writer) {
            writer.println("        </tbody>");
            writer.println("    </table>");
        }
        
        @Override
        public void writeTableRow(PrintWriter writer, String[] cells) {
            writer.println("            <tr>");
            for (String cell : cells) {
                writer.println("                <td>" + escapeHtml(cell) + "</td>");
            }
            writer.println("            </tr>");
        }
        
        @Override
        public String createLink(String url, String text) {
            return "<a href=\"" + escapeHtml(url) + "\">" + escapeHtml(text) + "</a>";
        }
        
        private String escapeHtml(String text) {
            return text.replace("&", "&amp;")
                      .replace("<", "&lt;")
                      .replace(">", "&gt;")
                      .replace("\"", "&quot;")
                      .replace("'", "&#39;");
        }
    }
    
    // Markdown document builder
    private static class MarkdownDocumentBuilder extends DocumentBuilder {
        public MarkdownDocumentBuilder(DocumentationConfig config) {
            super(config);
        }
        
        @Override
        public void writeDocumentHeader(PrintWriter writer, String title, String description) {
            writer.println("# " + title);
            writer.println();
            if (description != null) {
                writer.println("*" + description + "*");
                writer.println();
            }
        }
        
        @Override
        public void writeDocumentFooter(PrintWriter writer) {
            writer.println();
            writer.println("---");
            writer.println("*Generated by Open Mason Documentation System*");
        }
        
        @Override
        public void writeHeading(PrintWriter writer, int level, String text) {
            writer.println("#".repeat(level) + " " + text);
            writer.println();
        }
        
        @Override
        public void writeParagraph(PrintWriter writer, String text) {
            writer.println(text);
            writer.println();
        }
        
        @Override
        public void writeListStart(PrintWriter writer) {
            // No explicit start needed for Markdown lists
        }
        
        @Override
        public void writeListEnd(PrintWriter writer) {
            writer.println();
        }
        
        @Override
        public void writeListItem(PrintWriter writer, String text) {
            writer.println("- " + text);
        }
        
        @Override
        public void writeTableStart(PrintWriter writer, String[] headers) {
            writer.println("| " + String.join(" | ", headers) + " |");
            writer.println("|" + " --- |".repeat(headers.length));
        }
        
        @Override
        public void writeTableEnd(PrintWriter writer) {
            writer.println();
        }
        
        @Override
        public void writeTableRow(PrintWriter writer, String[] cells) {
            writer.println("| " + String.join(" | ", cells) + " |");
        }
        
        @Override
        public String createLink(String url, String text) {
            return "[" + text + "](" + url + ")";
        }
    }
    
    // Plain text document builder
    private static class PlainTextDocumentBuilder extends DocumentBuilder {
        public PlainTextDocumentBuilder(DocumentationConfig config) {
            super(config);
        }
        
        @Override
        public void writeDocumentHeader(PrintWriter writer, String title, String description) {
            writer.println(title.toUpperCase());
            writer.println("=".repeat(title.length()));
            writer.println();
            if (description != null) {
                writer.println(description);
                writer.println();
            }
        }
        
        @Override
        public void writeDocumentFooter(PrintWriter writer) {
            writer.println();
            writer.println("Generated by Open Mason Documentation System");
        }
        
        @Override
        public void writeHeading(PrintWriter writer, int level, String text) {
            writer.println();
            writer.println(text);
            String underline = level == 1 ? "=" : level == 2 ? "-" : "~";
            writer.println(underline.repeat(text.length()));
            writer.println();
        }
        
        @Override
        public void writeParagraph(PrintWriter writer, String text) {
            writer.println(text);
            writer.println();
        }
        
        @Override
        public void writeListStart(PrintWriter writer) {
            // No explicit start needed
        }
        
        @Override
        public void writeListEnd(PrintWriter writer) {
            writer.println();
        }
        
        @Override
        public void writeListItem(PrintWriter writer, String text) {
            writer.println("  * " + text);
        }
        
        @Override
        public void writeTableStart(PrintWriter writer, String[] headers) {
            writer.println(String.join(" | ", headers));
            writer.println("-".repeat(String.join(" | ", headers).length()));
        }
        
        @Override
        public void writeTableEnd(PrintWriter writer) {
            writer.println();
        }
        
        @Override
        public void writeTableRow(PrintWriter writer, String[] cells) {
            writer.println(String.join(" | ", cells));
        }
        
        @Override
        public String createLink(String url, String text) {
            return text + " (" + url + ")";
        }
    }
    
    // JSON document builder
    private static class JsonDocumentBuilder extends DocumentBuilder {
        public JsonDocumentBuilder(DocumentationConfig config) {
            super(config);
        }
        
        @Override
        public void writeDocumentHeader(PrintWriter writer, String title, String description) {
            writer.println("{");
            writer.println("  \"title\": \"" + escapeJson(title) + "\",");
            if (description != null) {
                writer.println("  \"description\": \"" + escapeJson(description) + "\",");
            }
            writer.println("  \"content\": [");
        }
        
        @Override
        public void writeDocumentFooter(PrintWriter writer) {
            writer.println("  ]");
            writer.println("}");
        }
        
        @Override
        public void writeHeading(PrintWriter writer, int level, String text) {
            writer.println("    {");
            writer.println("      \"type\": \"heading\",");
            writer.println("      \"level\": " + level + ",");
            writer.println("      \"text\": \"" + escapeJson(text) + "\"");
            writer.println("    },");
        }
        
        @Override
        public void writeParagraph(PrintWriter writer, String text) {
            writer.println("    {");
            writer.println("      \"type\": \"paragraph\",");
            writer.println("      \"text\": \"" + escapeJson(text) + "\"");
            writer.println("    },");
        }
        
        @Override
        public void writeListStart(PrintWriter writer) {
            writer.println("    {");
            writer.println("      \"type\": \"list\",");
            writer.println("      \"items\": [");
        }
        
        @Override
        public void writeListEnd(PrintWriter writer) {
            writer.println("      ]");
            writer.println("    },");
        }
        
        @Override
        public void writeListItem(PrintWriter writer, String text) {
            writer.println("        \"" + escapeJson(text) + "\",");
        }
        
        @Override
        public void writeTableStart(PrintWriter writer, String[] headers) {
            writer.println("    {");
            writer.println("      \"type\": \"table\",");
            writer.println("      \"headers\": [");
            for (int i = 0; i < headers.length; i++) {
                writer.print("        \"" + escapeJson(headers[i]) + "\"");
                if (i < headers.length - 1) writer.print(",");
                writer.println();
            }
            writer.println("      ],");
            writer.println("      \"rows\": [");
        }
        
        @Override
        public void writeTableEnd(PrintWriter writer) {
            writer.println("      ]");
            writer.println("    },");
        }
        
        @Override
        public void writeTableRow(PrintWriter writer, String[] cells) {
            writer.println("        [");
            for (int i = 0; i < cells.length; i++) {
                writer.print("          \"" + escapeJson(cells[i]) + "\"");
                if (i < cells.length - 1) writer.print(",");
                writer.println();
            }
            writer.println("        ],");
        }
        
        @Override
        public String createLink(String url, String text) {
            return text + " (" + url + ")";
        }
        
        private String escapeJson(String text) {
            return text.replace("\\", "\\\\")
                      .replace("\"", "\\\"")
                      .replace("\n", "\\n")
                      .replace("\r", "\\r")
                      .replace("\t", "\\t");
        }
    }
    
    // CSV document builder
    private static class CsvDocumentBuilder extends DocumentBuilder {
        public CsvDocumentBuilder(DocumentationConfig config) {
            super(config);
        }
        
        @Override
        public void writeDocumentHeader(PrintWriter writer, String title, String description) {
            writer.println("# " + title);
            if (description != null) {
                writer.println("# " + description);
            }
            writer.println();
        }
        
        @Override
        public void writeDocumentFooter(PrintWriter writer) {
            writer.println();
            writer.println("# Generated by Open Mason Documentation System");
        }
        
        @Override
        public void writeHeading(PrintWriter writer, int level, String text) {
            writer.println("# " + text);
        }
        
        @Override
        public void writeParagraph(PrintWriter writer, String text) {
            writer.println("# " + text);
        }
        
        @Override
        public void writeListStart(PrintWriter writer) {
            // Lists not supported in CSV format
        }
        
        @Override
        public void writeListEnd(PrintWriter writer) {
            // Lists not supported in CSV format
        }
        
        @Override
        public void writeListItem(PrintWriter writer, String text) {
            writer.println("# - " + text);
        }
        
        @Override
        public void writeTableStart(PrintWriter writer, String[] headers) {
            writer.println(String.join(",", Arrays.stream(headers)
                .map(this::escapeCsv)
                .toArray(String[]::new)));
        }
        
        @Override
        public void writeTableEnd(PrintWriter writer) {
            writer.println();
        }
        
        @Override
        public void writeTableRow(PrintWriter writer, String[] cells) {
            writer.println(String.join(",", Arrays.stream(cells)
                .map(this::escapeCsv)
                .toArray(String[]::new)));
        }
        
        @Override
        public String createLink(String url, String text) {
            return text + " " + url;
        }
        
        private String escapeCsv(String text) {
            if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
                return "\"" + text.replace("\"", "\"\"") + "\"";
            }
            return text;
        }
    }
}