package com.openmason.ui.viewport.gizmo;

import com.openmason.ui.viewport.gizmo.interaction.AxisConstraint;
import com.openmason.ui.viewport.gizmo.interaction.GizmoPart;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * Manages the state of the transform gizmo including current mode, active axis,
 * hover state, and interaction state.
 *
 * <p>This class follows SOLID principles:
 * - Single Responsibility: Only manages gizmo state
 * - Encapsulation: Private fields with controlled access
 * - Thread-safe: Can add synchronization if needed
 */
public class GizmoState {
    /**
     * Transform mode for the gizmo.
     */
    public enum Mode {
        TRANSLATE,  // Move mode (G key)
        ROTATE,     // Rotate mode (R key)
        SCALE       // Scale mode (S key)
    }

    // Core state
    private Mode currentMode = Mode.TRANSLATE;
    private boolean enabled = true;

    // Interaction state
    private AxisConstraint hoveredConstraint = AxisConstraint.NONE;
    private AxisConstraint activeConstraint = AxisConstraint.NONE;
    private GizmoPart hoveredPart = null;
    private GizmoPart activePart = null;
    private boolean isDragging = false;

    // Drag tracking
    private final Vector2f dragStartMousePos = new Vector2f();
    private final Vector3f dragStartObjectPos = new Vector3f();
    private final Vector3f dragStartObjectRotation = new Vector3f();
    private final Vector3f dragStartObjectScale = new Vector3f(1.0f);

    // Snap settings
    private boolean snapEnabled = false;
    private float snapIncrement = 0.5f; // Default snap increment

    // Scale mode settings
    private boolean uniformScaling = true; // Default to uniform scaling

    // Highlight intensity for visual feedback
    private float hoverIntensity = 1.5f;  // Brightness multiplier on hover
    private float activeIntensity = 2.0f; // Brightness multiplier when active

    /**
     * Creates a new GizmoState with default values.
     */
    public GizmoState() {
        // All fields initialized above
    }

    // ========== Mode Management ==========

    /**
     * Gets the current transform mode.
     *
     * @return Current mode (never null)
     */
    public Mode getCurrentMode() {
        return currentMode;
    }

    /**
     * Sets the current transform mode.
     *
     * @param mode The new mode (must not be null)
     * @throws IllegalArgumentException if mode is null
     */
    public void setCurrentMode(Mode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("Mode cannot be null");
        }
        this.currentMode = mode;
    }

    /**
     * Cycles to the next transform mode (Translate → Rotate → Scale → Translate).
     */
    public void cycleMode() {
        currentMode = switch (currentMode) {
            case TRANSLATE -> Mode.ROTATE;
            case ROTATE -> Mode.SCALE;
            case SCALE -> Mode.TRANSLATE;
        };
    }

    // ========== Enable/Disable ==========

    /**
     * Checks if the gizmo is enabled.
     *
     * @return true if enabled, false otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether the gizmo is enabled.
     *
     * @param enabled true to enable, false to disable
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            // Clear interaction state when disabling
            reset();
        }
    }

    // ========== Hover State ==========

    /**
     * Gets the currently hovered constraint.
     *
     * @return Hovered constraint (never null)
     */
    public AxisConstraint getHoveredConstraint() {
        return hoveredConstraint;
    }

    /**
     * Sets the currently hovered constraint.
     *
     * @param constraint The constraint being hovered (must not be null)
     * @throws IllegalArgumentException if constraint is null
     */
    public void setHoveredConstraint(AxisConstraint constraint) {
        if (constraint == null) {
            throw new IllegalArgumentException("Constraint cannot be null");
        }
        this.hoveredConstraint = constraint;
    }

    /**
     * Gets the currently hovered gizmo part.
     *
     * @return Hovered part or null if none
     */
    public GizmoPart getHoveredPart() {
        return hoveredPart;
    }

    /**
     * Sets the currently hovered gizmo part.
     *
     * @param part The part being hovered (can be null)
     */
    public void setHoveredPart(GizmoPart part) {
        this.hoveredPart = part;
        this.hoveredConstraint = (part != null) ? part.getConstraint() : AxisConstraint.NONE;
    }

    // ========== Active/Drag State ==========

    /**
     * Gets the currently active constraint (being dragged).
     *
     * @return Active constraint (never null)
     */
    public AxisConstraint getActiveConstraint() {
        return activeConstraint;
    }

    /**
     * Gets the currently active gizmo part (being dragged).
     *
     * @return Active part or null if none
     */
    public GizmoPart getActivePart() {
        return activePart;
    }

    /**
     * Checks if a drag operation is in progress.
     *
     * @return true if dragging, false otherwise
     */
    public boolean isDragging() {
        return isDragging;
    }

    /**
     * Starts a drag operation with the given part.
     *
     * @param part The gizmo part being dragged (must not be null)
     * @param mouseX Mouse X position at drag start
     * @param mouseY Mouse Y position at drag start
     * @param objectPos Current object position
     * @param objectRotation Current object rotation
     * @param objectScale Current object scale
     * @throws IllegalArgumentException if part is null
     */
    public void startDrag(GizmoPart part, float mouseX, float mouseY,
                          Vector3f objectPos, Vector3f objectRotation, Vector3f objectScale) {
        if (part == null) {
            throw new IllegalArgumentException("Part cannot be null");
        }

        this.activePart = part;
        this.activeConstraint = part.getConstraint();
        this.isDragging = true;

        // Record start state
        dragStartMousePos.set(mouseX, mouseY);
        dragStartObjectPos.set(objectPos);
        dragStartObjectRotation.set(objectRotation);
        dragStartObjectScale.set(objectScale);
    }

    /**
     * Ends the current drag operation.
     */
    public void endDrag() {
        this.isDragging = false;
        this.activePart = null;
        this.activeConstraint = AxisConstraint.NONE;
    }

    /**
     * Gets the mouse position at drag start.
     *
     * @return Start mouse position (never null)
     */
    public Vector2f getDragStartMousePos() {
        return new Vector2f(dragStartMousePos); // Defensive copy
    }

    /**
     * Gets the object position at drag start.
     *
     * @return Start object position (never null)
     */
    public Vector3f getDragStartObjectPos() {
        return new Vector3f(dragStartObjectPos); // Defensive copy
    }

    /**
     * Gets the object rotation at drag start.
     *
     * @return Start object rotation (never null)
     */
    public Vector3f getDragStartObjectRotation() {
        return new Vector3f(dragStartObjectRotation); // Defensive copy
    }

    /**
     * Gets the object scale at drag start.
     *
     * @return Start object scale (never null)
     */
    public Vector3f getDragStartObjectScale() {
        return new Vector3f(dragStartObjectScale); // Defensive copy
    }

    // ========== Snap Settings ==========

    /**
     * Checks if snapping is enabled.
     *
     * @return true if snap enabled, false otherwise
     */
    public boolean isSnapEnabled() {
        return snapEnabled;
    }

    /**
     * Sets whether snapping is enabled.
     *
     * @param enabled true to enable snap, false to disable
     */
    public void setSnapEnabled(boolean enabled) {
        this.snapEnabled = enabled;
    }

    /**
     * Gets the snap increment value.
     *
     * @return Snap increment (always positive)
     */
    public float getSnapIncrement() {
        return snapIncrement;
    }

    /**
     * Sets the snap increment value.
     *
     * @param increment The snap increment (must be positive)
     * @throws IllegalArgumentException if increment is not positive
     */
    public void setSnapIncrement(float increment) {
        if (increment <= 0.0f) {
            throw new IllegalArgumentException("Snap increment must be positive");
        }
        this.snapIncrement = increment;
    }

    // ========== Visual Feedback ==========

    /**
     * Gets the hover intensity multiplier.
     *
     * @return Hover intensity (always >= 1.0)
     */
    public float getHoverIntensity() {
        return hoverIntensity;
    }

    /**
     * Gets the active intensity multiplier.
     *
     * @return Active intensity (always >= 1.0)
     */
    public float getActiveIntensity() {
        return activeIntensity;
    }

    // ========== Scale Mode Settings ==========

    /**
     * Checks if uniform scaling is enabled.
     * When enabled, all axes scale together. When disabled, each axis scales independently.
     *
     * @return true if uniform scaling is enabled, false otherwise
     */
    public boolean isUniformScaling() {
        return uniformScaling;
    }

    /**
     * Sets whether uniform scaling is enabled.
     *
     * @param uniform true for uniform scaling, false for per-axis scaling
     */
    public void setUniformScaling(boolean uniform) {
        this.uniformScaling = uniform;
    }

    /**
     * Gets the intensity multiplier for a given constraint.
     *
     * @param constraint The constraint to check
     * @return Intensity multiplier (1.0 = normal, >1.0 = highlighted)
     */
    public float getIntensityForConstraint(AxisConstraint constraint) {
        if (constraint == null) {
            return 1.0f;
        }

        if (activeConstraint == constraint && isDragging) {
            return activeIntensity;
        } else if (hoveredConstraint == constraint && !isDragging) {
            return hoverIntensity;
        }

        return 1.0f; // Normal intensity
    }

    // ========== Utility ==========

    /**
     * Resets all interaction state (hover, active, drag).
     * Does not reset mode or snap settings.
     */
    public void reset() {
        hoveredConstraint = AxisConstraint.NONE;
        activeConstraint = AxisConstraint.NONE;
        hoveredPart = null;
        activePart = null;
        isDragging = false;
    }

    /**
     * Resets everything to default state.
     */
    public void resetAll() {
        reset();
        currentMode = Mode.TRANSLATE;
        enabled = true;
        snapEnabled = false;
        snapIncrement = 0.5f;
        uniformScaling = true;
    }
}
