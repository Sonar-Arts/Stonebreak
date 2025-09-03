package com.stonebreak.ui;

import com.stonebreak.rendering.UI.UIRenderer;
import org.joml.Vector3f;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.mobs.cow.Cow;
import com.stonebreak.mobs.cow.CowAI;
import java.util.List;

public class DebugOverlay {
    private boolean visible = false;

    public DebugOverlay() {
    }

    public boolean isVisible() {
        return visible;
    }

    public void toggleVisibility() {
        visible = !visible;
    }

    public String getDebugText() {
        if (!visible) {
            return "";
        }

        Player player = Game.getPlayer();
        World world = Game.getWorld();

        if (player == null || world == null) {
            return "Player or World not available";
        }

        Vector3f pos = player.getPosition();
        int x = (int) Math.floor(pos.x);
        int y = (int) Math.floor(pos.y);
        int z = (int) Math.floor(pos.z);

        // Get chunk coordinates
        int chunkX = x >> 4; // Divide by 16
        int chunkZ = z >> 4; // Divide by 16

        // Get memory information
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / (1024 * 1024);
        long totalMemory = runtime.totalMemory() / (1024 * 1024);
        long freeMemory = runtime.freeMemory() / (1024 * 1024);
        long usedMemory = totalMemory - freeMemory;

        // Get FPS
        float fps = 1.0f / Game.getDeltaTime();

        // Get world information
        int loadedChunks = world.getLoadedChunkCount();
        
        // Get block at player position
        BlockType blockBelow = world.getBlockAt(x, y - 1, z);
        String blockBelowName = blockBelow != null ? blockBelow.name() : "Unknown";

        // Get continentalness value at player position
        float continentalness = world.getContinentalnessAt(x, z);

        // Calculate facing direction from camera's front vector
        Vector3f front = player.getCamera().getFront();
        String facing = getCardinalDirection(front);

        // Build debug text
        StringBuilder debug = new StringBuilder();
        debug.append("Stonebreak Debug (F3)\n");
        debug.append("─────────────────────\n");
        debug.append(String.format("XYZ: %d / %d / %d\n", x, y, z));
        debug.append(String.format("Chunk: %d %d in %d %d\n", x & 15, z & 15, chunkX, chunkZ));
        debug.append(String.format("Facing: %s\n", facing));
        debug.append(String.format("Block Below: %s\n", blockBelowName));
        debug.append(String.format("Continentalness: %.3f\n", continentalness));
        debug.append("\n");
        debug.append(String.format("FPS: %.0f\n", fps));
        debug.append(String.format("Memory: %d/%d MB\n", usedMemory, maxMemory));
        debug.append(String.format("Chunks: %d loaded\n", loadedChunks));
        debug.append(String.format("Pending Mesh: %d\n", world.getPendingMeshBuildCount()));
        debug.append(String.format("Pending GL: %d\n", world.getPendingGLUploadCount()));
        debug.append("\n");
        debug.append("Path Visualization: ON\n");

        return debug.toString();
    }

    /**
     * Renders the debug overlay using the provided renderer.
     */
    public void render(UIRenderer uiRenderer) {
        if (!visible) {
            return;
        }

        String debugText = getDebugText();
        if (debugText.isEmpty()) {
            return;
        }

        // Get window dimensions from Game instance to calculate right side position
        int windowWidth = Game.getWindowWidth();
        
        String[] lines = debugText.split("\n");
        float rightMargin = 10; // Distance from right edge
        float y = 10; // Start position
        float lineHeight = 18;

        for (String line : lines) {
            if (line.contains("Stonebreak Debug")) {
                // Calculate text width and position from right side
                float textWidth = uiRenderer.getTextWidth(line, 20, "sans-bold");
                float x = windowWidth - rightMargin - textWidth;
                // Render title in yellow
                uiRenderer.drawText(line, x, y, "sans-bold", 20, 1.0f, 1.0f, 0.0f, 1.0f);
            } else {
                // Calculate text width and position from right side
                float textWidth = uiRenderer.getTextWidth(line, 16, "sans");
                float x = windowWidth - rightMargin - textWidth;
                // Render regular text
                uiRenderer.drawText(line, x, y, "sans", 16, 1.0f, 1.0f, 1.0f, 1.0f);
            }
            y += lineHeight;
        }
    }
    
    /**
     * Renders debug wireframes for entities (called after UI rendering).
     */
    public void renderWireframes(Renderer renderer) {
        if (!visible) {
            return;
        }
        
        EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) {
            return;
        }
        
        // Get all cow entities
        List<Entity> cowEntities = entityManager.getEntitiesByType(EntityType.COW);
        
        
        // Render green wireframe bounding boxes for each cow
        for (Entity cow : cowEntities) {
            if (cow.isAlive()) {
                renderEntityBoundingBox(cow, renderer);
                
                // Render path wireframe if entity is a Cow
                if (cow instanceof Cow) {
                    renderCowPathWireframe((Cow) cow, renderer);
                }
            }
        }
    }
    
    /**
     * Renders a green wireframe bounding box for an entity.
     */
    private void renderEntityBoundingBox(Entity entity, Renderer renderer) {
        Entity.BoundingBox boundingBox = entity.getBoundingBox();
        
        // Use green color for cow bounding boxes
        Vector3f green = new Vector3f(0.0f, 1.0f, 0.0f);
        
        // Use the renderer's modern wireframe rendering method
        renderer.renderWireframeBoundingBox(boundingBox, green);
    }
    
    /**
     * Renders a blue wireframe path for a cow's AI pathfinding.
     */
    private void renderCowPathWireframe(Cow cow, Renderer renderer) {
        CowAI cowAI = cow.getAI();
        if (cowAI == null) {
            return;
        }
        
        List<Vector3f> pathPoints = cowAI.getPathPoints();
        if (pathPoints == null || pathPoints.size() < 2) {
            return;
        }
        
        // Use blue color for path visualization
        Vector3f blue = new Vector3f(0.0f, 0.5f, 1.0f);
        
        // Use the renderer's path wireframe rendering method
        renderer.renderWireframePath(pathPoints, blue);
    }

    private String getCardinalDirection(Vector3f front) {
        // Calculate yaw from front vector
        // In OpenGL, -Z is forward, so we need to use atan2(-front.z, front.x)
        float yaw = (float) Math.toDegrees(Math.atan2(-front.z, front.x));
        
        // Normalize yaw to 0-360 degrees
        float normalizedYaw = ((yaw % 360) + 360) % 360;
        
        // Adjust for Minecraft coordinate system where:
        // North = -Z, South = +Z, East = +X, West = -X
        // Using 8 directions with 45-degree segments
        if (normalizedYaw >= 337.5 || normalizedYaw < 22.5) {
            return "East";
        } else if (normalizedYaw >= 22.5 && normalizedYaw < 67.5) {
            return "Northeast";
        } else if (normalizedYaw >= 67.5 && normalizedYaw < 112.5) {
            return "North";
        } else if (normalizedYaw >= 112.5 && normalizedYaw < 157.5) {
            return "Northwest";
        } else if (normalizedYaw >= 157.5 && normalizedYaw < 202.5) {
            return "West";
        } else if (normalizedYaw >= 202.5 && normalizedYaw < 247.5) {
            return "Southwest";
        } else if (normalizedYaw >= 247.5 && normalizedYaw < 292.5) {
            return "South";
        } else {
            return "Southeast";
        }
    }
}