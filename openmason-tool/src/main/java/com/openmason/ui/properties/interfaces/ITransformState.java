package com.openmason.ui.properties.interfaces;

import imgui.type.ImBoolean;
import imgui.type.ImFloat;

/**
 * Interface for transform state management following SOLID principles.
 * Provides abstraction for position, rotation, and scale transformations.
 */
public interface ITransformState {

    // Position accessors
    ImFloat getPositionX();
    ImFloat getPositionY();
    ImFloat getPositionZ();

    // Rotation accessors
    ImFloat getRotationX();
    ImFloat getRotationY();
    ImFloat getRotationZ();

    // Scale accessors
    ImFloat getScaleX();
    ImFloat getScaleY();
    ImFloat getScaleZ();

    // Uniform scaling mode
    ImBoolean getUniformScaleMode();
    void setUniformScaleMode(boolean uniform);

    /**
     * Reset all transform values to defaults.
     */
    void reset();

    /**
     * Apply uniform scaling to all axes based on a scale factor from one axis.
     *
     * @param axis The axis that changed (0=X, 1=Y, 2=Z)
     * @param newValue The new value for that axis
     * @param minScale Minimum allowed scale
     * @param maxScale Maximum allowed scale
     */
    void applyUniformScale(int axis, float newValue, float minScale, float maxScale);

    /**
     * Sync transform state from an external source (e.g., viewport).
     *
     * @param posX Position X
     * @param posY Position Y
     * @param posZ Position Z
     * @param rotX Rotation X
     * @param rotY Rotation Y
     * @param rotZ Rotation Z
     * @param sclX Scale X
     * @param sclY Scale Y
     * @param sclZ Scale Z
     * @param uniformMode Uniform scaling mode
     */
    void syncFrom(float posX, float posY, float posZ,
                  float rotX, float rotY, float rotZ,
                  float sclX, float sclY, float sclZ,
                  boolean uniformMode);

    /**
     * Check if the user is currently interacting with transform controls.
     *
     * @return true if user is actively modifying transforms
     */
    boolean isUserInteracting();

    /**
     * Mark that the user has interacted with the transform controls.
     */
    void markUserInteraction();

    /**
     * Validate and ensure all transform values are within safe bounds.
     */
    void ensureSafeDefaults();
}
