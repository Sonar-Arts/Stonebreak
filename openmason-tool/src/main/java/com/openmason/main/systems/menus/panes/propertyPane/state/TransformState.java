package com.openmason.main.systems.menus.panes.propertyPane.state;

import com.openmason.main.systems.menus.panes.propertyPane.interfaces.ITransformState;
import com.openmason.main.systems.menus.panes.propertyPane.utils.TransformValidator;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transform state management implementation.
 * Encapsulates all transform-related state and logic following SRP and KISS principles.
 */
public class TransformState implements ITransformState {

    private static final Logger logger = LoggerFactory.getLogger(TransformState.class);
    private static final long USER_INTERACTION_TIMEOUT = 100; // ms

    // Position
    private final ImFloat positionX = new ImFloat(0.0f);
    private final ImFloat positionY = new ImFloat(0.0f);
    private final ImFloat positionZ = new ImFloat(0.0f);

    // Rotation
    private final ImFloat rotationX = new ImFloat(0.0f);
    private final ImFloat rotationY = new ImFloat(0.0f);
    private final ImFloat rotationZ = new ImFloat(0.0f);

    // Scale
    private final ImFloat scaleX = new ImFloat(1.0f);
    private final ImFloat scaleY = new ImFloat(1.0f);
    private final ImFloat scaleZ = new ImFloat(1.0f);

    // Uniform scaling mode
    private final ImBoolean uniformScaleMode = new ImBoolean(true);

    // User interaction tracking
    private long lastUserInteractionTime = 0;

    // Scale ratio tracking for uniform scaling
    private float scaleRatioXY = 1.0f; // Y/X ratio
    private float scaleRatioXZ = 1.0f; // Z/X ratio

    @Override
    public ImFloat getPositionX() {
        return positionX;
    }

    @Override
    public ImFloat getPositionY() {
        return positionY;
    }

    @Override
    public ImFloat getPositionZ() {
        return positionZ;
    }

    @Override
    public ImFloat getRotationX() {
        return rotationX;
    }

    @Override
    public ImFloat getRotationY() {
        return rotationY;
    }

    @Override
    public ImFloat getRotationZ() {
        return rotationZ;
    }

    @Override
    public ImFloat getScaleX() {
        return scaleX;
    }

    @Override
    public ImFloat getScaleY() {
        return scaleY;
    }

    @Override
    public ImFloat getScaleZ() {
        return scaleZ;
    }

    @Override
    public ImBoolean getUniformScaleMode() {
        return uniformScaleMode;
    }

    @Override
    public void setUniformScaleMode(boolean uniform) {
        uniformScaleMode.set(uniform);

        // When enabling uniform mode, capture current scale ratios
        if (uniform) {
            captureScaleRatios();
        }
    }

    @Override
    public void reset() {
        positionX.set(0.0f);
        positionY.set(0.0f);
        positionZ.set(0.0f);

        rotationX.set(0.0f);
        rotationY.set(0.0f);
        rotationZ.set(0.0f);

        scaleX.set(1.0f);
        scaleY.set(1.0f);
        scaleZ.set(1.0f);

        uniformScaleMode.set(true);

        // Reset scale ratios
        scaleRatioXY = 1.0f;
        scaleRatioXZ = 1.0f;

        logger.debug("Transform state reset to defaults");
    }

    @Override
    public void applyUniformScale(int axis, float newValue, float minScale, float maxScale) {
        // Simplified uniform scaling algorithm (KISS principle)
        // When one axis changes, scale others proportionally using captured ratios

        float clampedValue = TransformValidator.clamp(newValue, minScale, maxScale);

        switch (axis) {
            case 0: // X changed
                scaleX.set(clampedValue);
                scaleY.set(TransformValidator.clamp(clampedValue * scaleRatioXY, minScale, maxScale));
                scaleZ.set(TransformValidator.clamp(clampedValue * scaleRatioXZ, minScale, maxScale));
                break;
            case 1: // Y changed
                scaleY.set(clampedValue);
                if (scaleRatioXY != 0.0f) {
                    float newX = clampedValue / scaleRatioXY;
                    scaleX.set(TransformValidator.clamp(newX, minScale, maxScale));
                    scaleZ.set(TransformValidator.clamp(newX * scaleRatioXZ, minScale, maxScale));
                }
                break;
            case 2: // Z changed
                scaleZ.set(clampedValue);
                if (scaleRatioXZ != 0.0f) {
                    float newX = clampedValue / scaleRatioXZ;
                    scaleX.set(TransformValidator.clamp(newX, minScale, maxScale));
                    scaleY.set(TransformValidator.clamp(newX * scaleRatioXY, minScale, maxScale));
                }
                break;
        }
    }

    @Override
    public void syncFrom(float posX, float posY, float posZ,
                         float rotX, float rotY, float rotZ,
                         float sclX, float sclY, float sclZ,
                         boolean uniformMode) {
        positionX.set(posX);
        positionY.set(posY);
        positionZ.set(posZ);

        rotationX.set(rotX);
        rotationY.set(rotY);
        rotationZ.set(rotZ);

        scaleX.set(sclX);
        scaleY.set(sclY);
        scaleZ.set(sclZ);

        uniformScaleMode.set(uniformMode);

        // Capture scale ratios when syncing
        captureScaleRatios();
    }

    @Override
    public boolean isUserInteracting() {
        return (System.currentTimeMillis() - lastUserInteractionTime) < USER_INTERACTION_TIMEOUT;
    }

    @Override
    public void markUserInteraction() {
        lastUserInteractionTime = System.currentTimeMillis();
    }

    @Override
    public void ensureSafeDefaults() {
        TransformValidator.ensureSafeTransform(
            positionX, positionY, positionZ,
            rotationX, rotationY, rotationZ,
            scaleX, scaleY, scaleZ
        );
    }

    /**
     * Capture current scale ratios for uniform scaling.
     * Called when entering uniform mode or syncing from viewport.
     */
    private void captureScaleRatios() {
        float x = scaleX.get();
        if (x > 0.001f) {
            scaleRatioXY = scaleY.get() / x;
            scaleRatioXZ = scaleZ.get() / x;
        } else {
            scaleRatioXY = 1.0f;
            scaleRatioXZ = 1.0f;
        }
    }
}
