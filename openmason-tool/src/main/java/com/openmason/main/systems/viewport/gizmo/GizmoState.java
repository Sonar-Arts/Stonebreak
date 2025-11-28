package com.openmason.main.systems.viewport.gizmo;

import com.openmason.main.systems.viewport.gizmo.interaction.AxisConstraint;
import com.openmason.main.systems.viewport.gizmo.interaction.GizmoPart;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * Manages the state of the transform gizmo including current mode, active axis,
 * hover state, and interaction state.
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

  public GizmoState() {
    }

    public Mode getCurrentMode() {
        return currentMode;
    }

    public void setCurrentMode(Mode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("Mode cannot be null");
        }
        this.currentMode = mode;
    }

    public void cycleMode() {
        currentMode = switch (currentMode) {
            case TRANSLATE -> Mode.ROTATE;
            case ROTATE -> Mode.SCALE;
            case SCALE -> Mode.TRANSLATE;
        };
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            reset();
        }
    }

    public GizmoPart getHoveredPart() {
        return hoveredPart;
    }

    public void setHoveredPart(GizmoPart part) {
        this.hoveredPart = part;
        this.hoveredConstraint = (part != null) ? part.getConstraint() : AxisConstraint.NONE;
    }

    public AxisConstraint getActiveConstraint() {
        return activeConstraint;
    }

    public boolean isDragging() {
        return isDragging;
    }

    public void startDrag(GizmoPart part, float mouseX, float mouseY,
                          Vector3f objectPos, Vector3f objectRotation, Vector3f objectScale) {
        if (part == null) {
            throw new IllegalArgumentException("Part cannot be null");
        }

        this.activePart = part;
        this.activeConstraint = part.getConstraint();
        this.isDragging = true;

        dragStartMousePos.set(mouseX, mouseY);
        dragStartObjectPos.set(objectPos);
        dragStartObjectRotation.set(objectRotation);
        dragStartObjectScale.set(objectScale);
    }

    public void endDrag() {
        this.isDragging = false;
        this.activePart = null;
        this.activeConstraint = AxisConstraint.NONE;
    }

    public Vector2f getDragStartMousePos() {
        return new Vector2f(dragStartMousePos);
    }

    public Vector3f getDragStartObjectPos() {
        return new Vector3f(dragStartObjectPos);
    }

    public Vector3f getDragStartObjectRotation() {
        return new Vector3f(dragStartObjectRotation);
    }

    public Vector3f getDragStartObjectScale() {
        return new Vector3f(dragStartObjectScale);
    }

    public boolean isSnapEnabled() {
        return snapEnabled;
    }

    public float getSnapIncrement() {
        return snapIncrement;
    }

    public boolean isUniformScaling() {
        return uniformScaling;
    }

    public void setUniformScaling(boolean uniform) {
        this.uniformScaling = uniform;
    }

    public float getIntensityForConstraint(AxisConstraint constraint) {
        if (constraint == null) {
            return 1.0f;
        }

        if (activeConstraint == constraint && isDragging) {
            return activeIntensity;
        } else if (hoveredConstraint == constraint && !isDragging) {
            return hoverIntensity;
        }

        return 1.0f;
    }

    public void reset() {
        hoveredConstraint = AxisConstraint.NONE;
        activeConstraint = AxisConstraint.NONE;
        hoveredPart = null;
        activePart = null;
        isDragging = false;
    }

}
