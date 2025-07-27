# Stonebreak

A 3D voxel-based sandbox game created with Java, inspired by Minecraft.

## Project Structure

This is a multi-module Maven project with two main components:

### Stonebreak Game
The main 3D voxel-based sandbox game built with LWJGL and OpenGL.

**Key Features:**
- 3D voxel world generation with chunk-based loading
- Entity system with cow mobs featuring 4 texture variants
- Advanced crafting and inventory system
- Professional audio system with OpenAL
- Memory-optimized performance with G1GC

### OpenMason Tool
A JavaFX-based model and texture editing tool for content creation.

**Key Features:**
- Canvas-based 3D viewport with real-time model visualization
- Professional arc-ball camera system for model inspection
- Real-time texture variant switching and preview
- JSON-based model and texture definition system
- Performance monitoring and optimization

## Technology Stack

- **Language**: Java 17
- **Game Engine**: LWJGL 3.3.2 (OpenGL, GLFW, OpenAL)
- **Tool UI**: JavaFX with Canvas-based 3D rendering
- **Math**: JOML 1.10.5
- **Build**: Maven with multi-module structure
- **Architecture**: Entity-Component with professional resource management

## Build & Run

### Prerequisites
- Java 17 or higher
- Maven 3.6+
- Modern graphics drivers (for optimal Canvas 3D rendering)

### Building the Project
```bash
# Build entire project
mvn clean compile

# Package both modules
mvn clean package

# Run Stonebreak game
cd stonebreak-game
mvn exec:java

# Run OpenMason tool
cd openmason-tool
mvn javafx:run
```

### IDE Setup (IntelliJ IDEA)
1. Import as Maven project
2. Set Project SDK to Java 17
3. For Stonebreak Game:
   - Main class: `com.stonebreak.core.Main`
   - Recommended JVM options: `-XX:+UseG1GC -XX:MaxGCPauseMillis=50 -Xms2g -Xmx4g`
4. For OpenMason Tool:
   - Main class: `com.openmason.OpenMasonApplication`
   - No special JVM arguments required (Canvas-based rendering)

## Canvas-Based 3D Rendering

OpenMason features an innovative Canvas-based 3D rendering system that provides professional model visualization within JavaFX without requiring complex native dependencies.

**Canvas 3D Features:**
- Real-time 3D-to-2D projection using camera matrices
- Professional arc-ball camera navigation
- Wireframe and solid rendering modes
- Depth-based lighting simulation
- Hardware-accelerated Canvas drawing with smooth anti-aliasing
- No native dependencies or complex setup required

This approach offers excellent performance while maintaining simplicity and cross-platform compatibility.

## Documentation

- `CLAUDE.md` - Comprehensive project documentation and architecture
- `docs/` - Additional implementation guides and specifications
- `stonebreak-game/` - Game-specific documentation and resources
- `openmason-tool/` - Tool documentation and user guides

## License

See `LICENSE` file for details.



