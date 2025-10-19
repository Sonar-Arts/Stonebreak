package com.openmason.ui.viewport;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * 3D Transform Gizmo for manipulating object transforms in Open Mason.
 *
 * Features:
 * - Visual X/Y/Z axes (Red/Green/Blue)
 * - Mouse interaction for translation
 * - High-quality rendering with proper depth testing
 * - Safe bounds checking and input validation
 * - KISS principle: focused, single-responsibility design
 *
 * Security measures:
 * - All transform values are clamped to safe ranges
 * - Input validation prevents invalid operations
 * - Graceful error handling for OpenGL failures
 *
 * IMPLEMENTATION NOTE - Technical Debt:
 * The current mouse interaction implementation uses simplified ray-casting logic.
 * This is intentional technical debt for the initial implementation.
 *
 * Simplified areas:
 * - screenToWorldRay(): Uses basic forward vector instead of proper screen-to-world transformation
 * - getAxisFromRay(): Simple proximity-based axis selection instead of proper ray-line intersection
 * - calculateAxisConstrainedPosition(): Basic delta calculation instead of plane-constrained dragging
 *
 * Future improvements (when needed):
 * - Implement proper NDC (Normalized Device Coordinates) to world space ray-casting
 * - Add accurate ray-line intersection testing for axis selection
 * - Implement plane-constrained dragging for smoother gizmo manipulation
 * - Add visual feedback for hover/selection states
 */
public class TransformGizmo {
    
    private static final Logger logger = LoggerFactory.getLogger(TransformGizmo.class);
    
    // Gizmo visual properties
    private static final float AXIS_LENGTH = 2.0f;
    private static final float AXIS_THICKNESS = 0.05f;
    private static final float ARROW_SIZE = 0.3f;
    private static final float SELECTION_TOLERANCE = 0.2f;
    
    // Safety constraints
    private static final float MAX_TRANSLATION = 10.0f;
    private static final float MIN_TRANSLATION = -10.0f;
    
    // OpenGL resources
    private int axisVAO = -1;
    private int axisVBO = -1;
    private int arrowVAO = -1;
    private int arrowVBO = -1;
    private boolean initialized = false;
    
    // Gizmo state
    private final Vector3f position = new Vector3f(0.0f, 0.0f, 0.0f);
    private boolean visible = true;
    private GizmoAxis selectedAxis = GizmoAxis.NONE;
    private boolean isDragging = false;
    
    // Interaction state
    private final Vector3f lastMouseWorldPos = new Vector3f();
    private final Vector3f dragStartPos = new Vector3f();
    private final Vector3f dragStartModelPos = new Vector3f();
    
    // Colors for each axis
    private static final float[] X_AXIS_COLOR = {1.0f, 0.0f, 0.0f}; // Red
    private static final float[] Y_AXIS_COLOR = {0.0f, 1.0f, 0.0f}; // Green
    private static final float[] Z_AXIS_COLOR = {0.0f, 0.0f, 1.0f}; // Blue
    private static final float[] SELECTED_COLOR = {1.0f, 1.0f, 0.0f}; // Yellow
    
    public enum GizmoAxis {
        NONE, X_AXIS, Y_AXIS, Z_AXIS
    }
    
    /**
     * Initialize the gizmo OpenGL resources.
     */
    public void initialize() {
        if (initialized) {
            return;
        }
        
        try {
            createAxisGeometry();
            createArrowGeometry();
            initialized = true;
            logger.debug("Transform gizmo initialized successfully");
        } catch (Exception e) {
            logger.error("Failed to initialize transform gizmo", e);
            cleanup();
            throw new RuntimeException("Transform gizmo initialization failed", e);
        }
    }
    
    /**
     * Create geometry for axis lines.
     */
    private void createAxisGeometry() {
        // Create three axis lines: X, Y, Z
        float[] axisVertices = {
            // X-axis (red)
            0.0f, 0.0f, 0.0f,  AXIS_LENGTH, 0.0f, 0.0f,
            // Y-axis (green)  
            0.0f, 0.0f, 0.0f,  0.0f, AXIS_LENGTH, 0.0f,
            // Z-axis (blue)
            0.0f, 0.0f, 0.0f,  0.0f, 0.0f, AXIS_LENGTH
        };
        
        axisVAO = glGenVertexArrays();
        axisVBO = glGenBuffers();
        
        if (axisVAO == 0 || axisVBO == 0) {
            throw new RuntimeException("Failed to generate axis buffers");
        }
        
        glBindVertexArray(axisVAO);
        
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(axisVertices.length);
        vertexBuffer.put(axisVertices).flip();
        
        glBindBuffer(GL_ARRAY_BUFFER, axisVBO);
        glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_STATIC_DRAW);
        
        // Position attribute
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
        
        glBindVertexArray(0);
    }
    
    /**
     * Create geometry for arrow heads.
     */
    private void createArrowGeometry() {
        // Simple pyramid arrow heads at the end of each axis
        float[] arrowVertices = {
            // X-axis arrow (at end of X axis)
            AXIS_LENGTH, 0.0f, 0.0f,  // Tip
            AXIS_LENGTH - ARROW_SIZE, ARROW_SIZE * 0.5f, 0.0f,  // Base
            AXIS_LENGTH - ARROW_SIZE, -ARROW_SIZE * 0.5f, 0.0f,
            
            AXIS_LENGTH, 0.0f, 0.0f,  // Tip
            AXIS_LENGTH - ARROW_SIZE, 0.0f, ARROW_SIZE * 0.5f,  // Base
            AXIS_LENGTH - ARROW_SIZE, 0.0f, -ARROW_SIZE * 0.5f,
            
            // Y-axis arrow (at end of Y axis)
            0.0f, AXIS_LENGTH, 0.0f,  // Tip
            ARROW_SIZE * 0.5f, AXIS_LENGTH - ARROW_SIZE, 0.0f,  // Base
            -ARROW_SIZE * 0.5f, AXIS_LENGTH - ARROW_SIZE, 0.0f,
            
            0.0f, AXIS_LENGTH, 0.0f,  // Tip
            0.0f, AXIS_LENGTH - ARROW_SIZE, ARROW_SIZE * 0.5f,  // Base
            0.0f, AXIS_LENGTH - ARROW_SIZE, -ARROW_SIZE * 0.5f,
            
            // Z-axis arrow (at end of Z axis)
            0.0f, 0.0f, AXIS_LENGTH,  // Tip
            ARROW_SIZE * 0.5f, 0.0f, AXIS_LENGTH - ARROW_SIZE,  // Base
            -ARROW_SIZE * 0.5f, 0.0f, AXIS_LENGTH - ARROW_SIZE,
            
            0.0f, 0.0f, AXIS_LENGTH,  // Tip
            0.0f, ARROW_SIZE * 0.5f, AXIS_LENGTH - ARROW_SIZE,  // Base
            0.0f, -ARROW_SIZE * 0.5f, AXIS_LENGTH - ARROW_SIZE
        };
        
        arrowVAO = glGenVertexArrays();
        arrowVBO = glGenBuffers();
        
        if (arrowVAO == 0 || arrowVBO == 0) {
            throw new RuntimeException("Failed to generate arrow buffers");
        }
        
        glBindVertexArray(arrowVAO);
        
        FloatBuffer arrowBuffer = BufferUtils.createFloatBuffer(arrowVertices.length);
        arrowBuffer.put(arrowVertices).flip();
        
        glBindBuffer(GL_ARRAY_BUFFER, arrowVBO);
        glBufferData(GL_ARRAY_BUFFER, arrowBuffer, GL_STATIC_DRAW);
        
        // Position attribute
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
        
        glBindVertexArray(0);
    }
    
    /**
     * Render the gizmo using provided shader and matrices.
     */
    public void render(int shaderProgram, int mvpLocation, int colorLocation, 
                      Matrix4f viewProjectionMatrix, Matrix4f modelMatrix) {
        if (!initialized || !visible) {
            return;
        }
        
        try {
            // Enable line width for better visibility
            glLineWidth(3.0f);
            
            // Calculate final MVP matrix
            Matrix4f mvpMatrix = new Matrix4f();
            Matrix4f gizmoModelMatrix = new Matrix4f(modelMatrix);
            gizmoModelMatrix.translate(position);
            
            viewProjectionMatrix.mul(gizmoModelMatrix, mvpMatrix);
            
            // Upload MVP matrix
            FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);
            mvpMatrix.get(matrixBuffer);
            glUniformMatrix4fv(mvpLocation, false, matrixBuffer);
            
            // Render axis lines
            glBindVertexArray(axisVAO);
            
            // X-axis (red or yellow if selected)
            float[] xColor = (selectedAxis == GizmoAxis.X_AXIS) ? SELECTED_COLOR : X_AXIS_COLOR;
            glUniform3f(colorLocation, xColor[0], xColor[1], xColor[2]);
            glDrawArrays(GL_LINES, 0, 2);
            
            // Y-axis (green or yellow if selected)
            float[] yColor = (selectedAxis == GizmoAxis.Y_AXIS) ? SELECTED_COLOR : Y_AXIS_COLOR;
            glUniform3f(colorLocation, yColor[0], yColor[1], yColor[2]);
            glDrawArrays(GL_LINES, 2, 2);
            
            // Z-axis (blue or yellow if selected)
            float[] zColor = (selectedAxis == GizmoAxis.Z_AXIS) ? SELECTED_COLOR : Z_AXIS_COLOR;
            glUniform3f(colorLocation, zColor[0], zColor[1], zColor[2]);
            glDrawArrays(GL_LINES, 4, 2);
            
            // Render arrow heads
            glBindVertexArray(arrowVAO);
            
            // X-axis arrows
            glUniform3f(colorLocation, xColor[0], xColor[1], xColor[2]);
            glDrawArrays(GL_TRIANGLES, 0, 6);
            
            // Y-axis arrows
            glUniform3f(colorLocation, yColor[0], yColor[1], yColor[2]);
            glDrawArrays(GL_TRIANGLES, 6, 6);
            
            // Z-axis arrows
            glUniform3f(colorLocation, zColor[0], zColor[1], zColor[2]);
            glDrawArrays(GL_TRIANGLES, 12, 6);
            
            glBindVertexArray(0);
            glLineWidth(1.0f); // Restore default line width
            
        } catch (Exception e) {
            logger.error("Error rendering transform gizmo", e);
        }
    }
    
    /**
     * Handle mouse press for gizmo interaction.
     * Returns true if gizmo handled the input.
     */
    public boolean handleMousePress(float mouseX, float mouseY, Camera camera) {
        if (!initialized || !visible) {
            return false;
        }
        
        try {
            // Perform ray-casting to determine which axis was clicked
            Vector3f rayDir = screenToWorldRay(mouseX, mouseY, camera);
            GizmoAxis clickedAxis = getAxisFromRay(camera.getPosition(), rayDir);
            
            if (clickedAxis != GizmoAxis.NONE) {
                selectedAxis = clickedAxis;
                isDragging = true;
                
                // Store initial positions
                dragStartPos.set(camera.getPosition());
                dragStartModelPos.set(position);
                
                logger.debug("Gizmo axis selected: {}", clickedAxis);
                return true;
            }
            
        } catch (Exception e) {
            logger.error("Error handling gizmo mouse press", e);
        }
        
        return false;
    }
    
    /**
     * Handle mouse drag for gizmo interaction.
     * Returns translation delta or null if no valid drag.
     */
    public Vector3f handleMouseDrag(float mouseX, float mouseY, Camera camera) {
        if (!isDragging || selectedAxis == GizmoAxis.NONE) {
            return null;
        }
        
        try {
            Vector3f rayDir = screenToWorldRay(mouseX, mouseY, camera);
            Vector3f newPos = calculateAxisConstrainedPosition(rayDir, camera.getPosition());
            
            // Calculate delta from start position
            Vector3f delta = new Vector3f(newPos).sub(dragStartModelPos);
            
            // Apply safety constraints
            delta = clampTranslationDelta(delta);
            
            // Update position
            position.set(dragStartModelPos).add(delta);
            
            return new Vector3f(delta);
            
        } catch (Exception e) {
            logger.error("Error handling gizmo mouse drag", e);
            return null;
        }
    }
    
    /**
     * Handle mouse release to end gizmo interaction.
     */
    public void handleMouseRelease() {
        isDragging = false;
        selectedAxis = GizmoAxis.NONE;
        logger.debug("Gizmo interaction ended");
    }
    
    /**
     * Convert screen coordinates to world ray direction.
     *
     * SIMPLIFIED IMPLEMENTATION: Currently returns camera forward vector.
     * TODO: Implement proper NDC to world space transformation:
     *       1. Convert screen coords to NDC [-1, 1]
     *       2. Create ray in clip space
     *       3. Transform through inverse projection and view matrices
     */
    private Vector3f screenToWorldRay(float mouseX, float mouseY, Camera camera) {
        Vector3f forward = new Vector3f();
        camera.getViewMatrix().positiveZ(forward).negate();
        return forward.normalize();
    }
    
    /**
     * Determine which axis (if any) intersects with the given ray.
     *
     * SIMPLIFIED IMPLEMENTATION: Uses Manhattan distance approximation.
     * TODO: Implement proper ray-line intersection testing:
     *       1. Calculate closest point on each axis line to the ray
     *       2. Use perpendicular distance for accurate hit testing
     *       3. Add selection priority for overlapping axes
     */
    private GizmoAxis getAxisFromRay(Vector3f rayOrigin, Vector3f rayDir) {
        Vector3f gizmoPos = new Vector3f(position);
        Vector3f toGizmo = new Vector3f(gizmoPos).sub(rayOrigin);
        
        // Check distance to each axis (simplified)
        float distToX = Math.abs(toGizmo.y) + Math.abs(toGizmo.z);
        float distToY = Math.abs(toGizmo.x) + Math.abs(toGizmo.z);
        float distToZ = Math.abs(toGizmo.x) + Math.abs(toGizmo.y);
        
        float minDist = Math.min(Math.min(distToX, distToY), distToZ);
        
        if (minDist > SELECTION_TOLERANCE) {
            return GizmoAxis.NONE;
        }
        
        if (distToX == minDist) return GizmoAxis.X_AXIS;
        if (distToY == minDist) return GizmoAxis.Y_AXIS;
        return GizmoAxis.Z_AXIS;
    }
    
    /**
     * Calculate new position constrained to the selected axis.
     *
     * SIMPLIFIED IMPLEMENTATION: Uses basic ray direction scaling.
     * TODO: Implement proper plane-constrained dragging:
     *       1. Create plane perpendicular to camera at gizmo position
     *       2. Intersect mouse ray with this plane
     *       3. Project intersection point onto selected axis
     *       4. Calculate delta from drag start position
     */
    private Vector3f calculateAxisConstrainedPosition(Vector3f rayDir, Vector3f cameraPos) {
        Vector3f newPos = new Vector3f(position);

        // Simple delta calculation based on ray direction
        float movementScale = 0.1f; // Adjust sensitivity
        
        switch (selectedAxis) {
            case X_AXIS:
                newPos.x += rayDir.x * movementScale;
                break;
            case Y_AXIS:
                newPos.y += rayDir.y * movementScale;
                break;
            case Z_AXIS:
                newPos.z += rayDir.z * movementScale;
                break;
        }
        
        return newPos;
    }
    
    /**
     * Clamp translation delta to safe bounds.
     */
    private Vector3f clampTranslationDelta(Vector3f delta) {
        Vector3f clamped = new Vector3f(delta);
        
        // Ensure the final position stays within bounds
        Vector3f finalPos = new Vector3f(dragStartModelPos).add(delta);
        
        finalPos.x = Math.max(MIN_TRANSLATION, Math.min(MAX_TRANSLATION, finalPos.x));
        finalPos.y = Math.max(MIN_TRANSLATION, Math.min(MAX_TRANSLATION, finalPos.y));
        finalPos.z = Math.max(MIN_TRANSLATION, Math.min(MAX_TRANSLATION, finalPos.z));
        
        return finalPos.sub(dragStartModelPos);
    }
    
    /**
     * Set the gizmo position.
     */
    public void setPosition(float x, float y, float z) {
        // Apply safety constraints
        x = Math.max(MIN_TRANSLATION, Math.min(MAX_TRANSLATION, x));
        y = Math.max(MIN_TRANSLATION, Math.min(MAX_TRANSLATION, y));
        z = Math.max(MIN_TRANSLATION, Math.min(MAX_TRANSLATION, z));
        
        position.set(x, y, z);
    }
    
    /**
     * Get the current gizmo position.
     */
    public Vector3f getPosition() {
        return new Vector3f(position);
    }
    
    /**
     * Set gizmo visibility.
     */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }
    
    /**
     * Check if gizmo is visible.
     */
    public boolean isVisible() {
        return visible;
    }
    
    /**
     * Check if currently dragging.
     */
    public boolean isDragging() {
        return isDragging;
    }
    
    /**
     * Get the currently selected axis.
     */
    public GizmoAxis getSelectedAxis() {
        return selectedAxis;
    }
    
    /**
     * Clean up OpenGL resources.
     */
    public void cleanup() {
        if (axisVBO != -1) {
            glDeleteBuffers(axisVBO);
            axisVBO = -1;
        }
        
        if (axisVAO != -1) {
            glDeleteVertexArrays(axisVAO);
            axisVAO = -1;
        }
        
        if (arrowVBO != -1) {
            glDeleteBuffers(arrowVBO);
            arrowVBO = -1;
        }
        
        if (arrowVAO != -1) {
            glDeleteVertexArrays(arrowVAO);
            arrowVAO = -1;
        }
        
        initialized = false;
        logger.debug("Transform gizmo resources cleaned up");
    }
    
    /**
     * Reset gizmo to origin.
     */
    public void reset() {
        position.set(0.0f, 0.0f, 0.0f);
        selectedAxis = GizmoAxis.NONE;
        isDragging = false;
    }
}