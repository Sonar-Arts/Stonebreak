# Stonebreak

A 3D voxel-based sandbox game created with Java and LWJGL, inspired by Minecraft.


## Building and Running

### Using the provided scripts (Windows)

1. Run `setup.bat` to download the necessary libraries
2. Run `compile_and_run.bat` to compile and start the game

### Using Maven

If you have Maven installed, you can build with:

1. Clone the repository
2. Build the project with Maven:
   ```
   mvn clean package
   ```
3. Run the game:
   ```
   java -jar target/stonebreak-1.0-SNAPSHOT.jar
   ```

## Requirements

- Java 17 or higher
- OpenGL 3.3 capable graphics card

## Project Structure

- `Main.java`: Entry point and game loop
- `Game.java`: Central game state
- `World.java`: Manages the game world and chunks
- `Chunk.java`: Stores and renders blocks
- `Player.java`: Handles player movement and interaction
- `Camera.java`: Handles the player's view
- `BlockType.java`: Defines all block types
- `NoiseGenerator.java`: Implements Simplex noise for terrain
- `Renderer.java`: Handles rendering of the world
- `ShaderProgram.java`: Manages OpenGL shaders
- `InputHandler.java`: Processes player input
- `Inventory.java`: Manages player's collected blocks

## Future Enhancements

- Save/load world functionality
- More complex biomes
- Simple AI mobs
- Day/night cycle
- Crafting system
- Sound effects and music
