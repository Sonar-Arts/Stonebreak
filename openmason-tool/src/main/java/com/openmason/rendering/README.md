# Canvas-Based 3D Rendering System for OpenMason

## Overview

The Canvas-Based 3D Rendering System provides a comprehensive, high-performance solution for 3D model visualization within JavaFX using innovative Canvas-based rendering techniques. This system integrates seamlessly with the existing StonebreakModel architecture while providing professional-grade 3D visualization without requiring complex native dependencies.

## Key Features

- **Canvas-Based 3D Projection**: Real-time 3D-to-2D projection using camera matrices for realistic depth perception
- **Professional Arc-Ball Camera**: Industry-standard camera navigation with smooth interpolation
- **Advanced Model Visualization**: Wireframe and solid rendering modes with depth-based lighting simulation
- **Real-time Texture Switching**: Instant texture variant updates with visual feedback
- **Memory Efficient**: Lightweight Canvas-based approach with automatic resource management
- **Cross-Platform Compatibility**: No native dependencies or complex setup requirements
- **Performance Optimized**: Hardware-accelerated Canvas drawing with adaptive quality settings

## Architecture

### Core Components

1. **OpenMason3DViewport** - Main Canvas-based 3D viewport extending JavaFX StackPane
2. **ArcBallCamera** - Professional camera system with 3D navigation and presets
3. **Canvas3DProjection** - 3D-to-2D projection system using camera matrices
4. **ModelRenderer** - High-level rendering interface for Canvas-based model display
5. **PerformanceOptimizer** - Adaptive quality and performance monitoring system
6. **BufferManager** - Resource tracking and memory monitoring (legacy OpenGL support)
7. **TextureManager** - Texture variant management and Canvas integration
8. **CanvasRenderingEngine** - Core Canvas drawing and optimization utilities

### Canvas Rendering Lifecycle

```
Initialize Canvas → Setup Camera → Project 3D to 2D → Render on Canvas → Update Display
```

All Canvas operations are handled through JavaFX's hardware-accelerated GraphicsContext with automatic resource management.

## Usage Examples

### Basic Canvas 3D Viewport Setup

```java
// Create Canvas-based 3D viewport
OpenMason3DViewport viewport = new OpenMason3DViewport();

// Set up in JavaFX scene
StackPane root = new StackPane();
root.getChildren().add(viewport);

Scene scene = new Scene(root, 800, 600);
stage.setScene(scene);

// Load and display a model
StonebreakModel cowModel = ModelManager.loadModel("cow");
viewport.setCurrentModel(cowModel);

// Set texture variant
viewport.setCurrentTextureVariant("angus");

// Configure camera for optimal viewing
ArcBallCamera camera = viewport.getCamera();
camera.applyPreset(ArcBallCamera.CameraPreset.ISOMETRIC);

// Enable wireframe mode
viewport.setWireframeMode(true);

// Resources automatically managed by JavaFX
```

### Advanced Camera Controls

```java
// Get camera from viewport
ArcBallCamera camera = viewport.getCamera();

// Apply camera presets
camera.applyPreset(ArcBallCamera.CameraPreset.FRONT);      // Front view
camera.applyPreset(ArcBallCamera.CameraPreset.ISOMETRIC);  // Isometric view
camera.applyPreset(ArcBallCamera.CameraPreset.TOP);        // Top-down view

// Manual camera control
camera.setOrientation(45.0f, 20.0f);  // Azimuth, elevation in degrees
camera.setDistance(5.0f);              // Distance from target
camera.setTarget(new Vector3f(0, 0, 0)); // Look-at point

// Smooth camera movements
camera.rotate(deltaX, deltaY);         // Mouse drag rotation
camera.zoom(scrollDelta);              // Mouse wheel zoom
camera.pan(deltaX, deltaY);            // Mouse pan

// Reset to default position
camera.reset();

// Fit camera to model bounds
camera.frameObject(minBounds, maxBounds);
```

### Performance Monitoring

```java
// Get performance statistics from viewport
PerformanceOptimizer.PerformanceStatistics stats = viewport.getPerformanceStatistics();
System.out.println("FPS: " + stats.getCurrentFPS());
System.out.println("Frame time: " + stats.getAverageFrameTime() + "ms");

// Enable performance overlay for debugging
viewport.setPerformanceOverlayEnabled(true);

// Configure adaptive quality
viewport.setAdaptiveQualityEnabled(true);

// Manual quality settings
viewport.setRenderScale(0.8f);  // Reduce render scale for better performance

// Get rendering statistics
OpenMason3DViewport.RenderingStatistics renderStats = viewport.getStatistics();
System.out.println("Total frames: " + renderStats.getFrameCount());
System.out.println("Errors: " + renderStats.getErrorCount());
System.out.println("Initialized: " + renderStats.isInitialized());
```

## Integration with Existing Systems

### StonebreakModel Integration

The buffer system seamlessly integrates with existing model definitions:

```java
// Convert model parts to buffer data
for (StonebreakModel.BodyPart bodyPart : model.getBodyParts()) {
    StonebreakModelDefinition.ModelPart modelPart = bodyPart.getModelPart();
    
    // Generate buffer data
    float[] vertices = modelPart.getVertices();
    int[] indices = modelPart.getIndices();
    
    // Create VAO
    VertexArray vao = VertexArray.fromModelPart(
        vertices, indices, textureDefinition, bodyPart.getName(), "VAO_" + bodyPart.getName()
    );
}
```

### Texture Atlas Integration

Texture coordinates are automatically generated from the existing atlas system:

```java
TextureCoordinateBuffer texCoordBuf = new TextureCoordinateBuffer("CowTexCoords");

// Update for specific texture variant
texCoordBuf.updateForTextureVariant(textureDefinition, "head", "angus");
```

## Performance Considerations

### Memory Management

- Buffers track their memory usage for monitoring
- Automatic cleanup prevents memory leaks
- Buffer reuse minimizes GPU memory allocation overhead
- Real-time texture switching avoids buffer recreation

### Rendering Optimization

- VAOs cache all buffer bindings for fast rendering
- Index buffers enable efficient triangle rendering
- Texture coordinate updates are optimized for variant switching
- Validation can be disabled in production for maximum performance

### Threading

- BufferManager uses thread-safe collections
- Buffer operations are designed to be called from the main OpenGL thread
- Statistics and monitoring are thread-safe

## Error Handling and Validation

### OpenGL Error Detection

```java
// Validate OpenGL context
List<String> contextIssues = OpenGLValidator.validateOpenGLContext();
if (!contextIssues.isEmpty()) {
    System.err.println("OpenGL context issues: " + contextIssues);
}

// Validate rendering state
List<String> renderingIssues = OpenGLValidator.validateRenderingState();
```

### Buffer Validation

```java
// Validate individual buffers
List<String> bufferIssues = OpenGLValidator.validateBuffer(vertexBuffer);

// Validate complete VAO
VertexArray.ValidationResult vaoResult = vao.validate();
if (!vaoResult.isValid()) {
    System.err.println("VAO validation errors: " + vaoResult.getErrors());
}
```

### Resource Leak Detection

```java
BufferManager bufferManager = BufferManager.getInstance();

// Check for resource leaks
List<String> leakIssues = bufferManager.validateResources();
if (!leakIssues.isEmpty()) {
    System.err.println("Potential resource leaks: " + leakIssues);
}
```

## Configuration

### BufferManager Settings

```java
BufferManager bufferManager = BufferManager.getInstance();

// Enable/disable features
bufferManager.setMemoryTrackingEnabled(true);
bufferManager.setLeakDetectionEnabled(true);

// Set thresholds
bufferManager.setMemoryWarningThreshold(100 * 1024 * 1024); // 100MB warning threshold
bufferManager.setMaxHistoryEntries(1000); // History size for statistics
```

## Canvas-Based 3D Advantages

The Canvas-based 3D rendering approach offers several key advantages:

1. **No Native Dependencies**: Pure JavaFX implementation without complex setup
2. **Cross-Platform Compatibility**: Works consistently across all JavaFX-supported platforms
3. **Hardware Acceleration**: Leverages JavaFX's built-in Canvas hardware acceleration
4. **Simplified Deployment**: No additional libraries or native components required
5. **Professional Quality**: Provides excellent visual quality with depth simulation and lighting

## File Structure

```
com.openmason/
├── ui/viewport/
│   └── OpenMason3DViewport.java           # Main Canvas-based 3D viewport
├── camera/
│   └── ArcBallCamera.java                 # Professional camera system
├── rendering/
│   ├── BufferManager.java                 # Resource tracking (legacy OpenGL support)
│   ├── ModelRenderer.java                 # High-level rendering interface
│   ├── PerformanceOptimizer.java          # Performance monitoring and optimization
│   └── OpenGLValidator.java               # Validation and error handling
├── model/
│   ├── ModelManager.java                  # Model loading and management
│   └── StonebreakModel.java               # Model definition and data
└── texture/
    └── TextureManager.java                # Texture variant management
```

## Best Practices

1. **Initialize viewport early** in JavaFX Application.start() method
2. **Use camera presets** for consistent model viewing angles
3. **Enable performance monitoring** during development to optimize frame rates
4. **Bind viewport properties** to UI controls for responsive interaction
5. **Handle model loading asynchronously** to avoid blocking the UI thread
6. **Call viewport.dispose()** during application shutdown for clean resource cleanup

## Dependencies

- JavaFX 17+ (UI framework and Canvas rendering)
- JOML 1.10.5+ (3D math library for matrices and vectors)
- Jackson (JSON processing for model definitions)
- Existing StonebreakModel and texture systems
- No additional native libraries required

## Future Enhancements

- Multiple model support for side-by-side comparison
- Advanced lighting models and shadow simulation
- Animation support for animated models
- Multi-viewport layouts (quad view, side-by-side)
- Model measurement and analysis tools
- Export capabilities for rendered views

## Troubleshooting

### Common Issues

1. **Viewport not rendering**: Check that viewport is properly added to JavaFX scene graph
2. **Camera controls not working**: Ensure viewport has focus and camera controls are enabled
3. **Model not visible**: Verify model is loaded and camera is positioned correctly
4. **Performance issues**: Enable performance overlay to identify bottlenecks
5. **Texture variants not switching**: Check texture variant names match definition files

### Debug Information

Enable detailed logging by setting system properties:
```
-Dopenmason.viewport.debug=true
-Dopenmason.performance.debug=true
-Djavafx.animation.fullspeed=true
```

This will enable detailed Canvas rendering debug output and performance monitoring.

### Performance Tuning

For optimal Canvas 3D performance:
```
// Enable performance overlay to monitor frame rates
viewport.setPerformanceOverlayEnabled(true);

// Use adaptive quality for automatic optimization
viewport.setAdaptiveQualityEnabled(true);

// Manually tune render scale if needed
viewport.setRenderScale(0.8f);  // Reduce for better performance
```