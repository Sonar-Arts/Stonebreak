package com.openmason.ui.components.textureCreator.tools.move;

import java.util.Objects;

/**
 * Immutable snapshot of a transformation composed of translation, scaling,
 * and rotation around the selection centre.
 *
 * Translation is expressed in canvas pixels, scaling is relative to the
 * original selection size, and rotation is stored in degrees to keep UI
 * tooling human friendly.
 */
public final class TransformationState {

    private static final double MIN_SCALE_MAGNITUDE = 0.01;

    private final double translateX;
    private final double translateY;
    private final double scaleX;
    private final double scaleY;
    private final double rotationDegrees;

    public static TransformationState identity() {
        return new TransformationState(0.0, 0.0, 1.0, 1.0, 0.0);
    }

    public TransformationState(double translateX,
                               double translateY,
                               double scaleX,
                               double scaleY,
                               double rotationDegrees) {
        this.translateX = translateX;
        this.translateY = translateY;
        this.scaleX = clampScale(scaleX);
        this.scaleY = clampScale(scaleY);
        this.rotationDegrees = normalizeDegrees(rotationDegrees);
    }

    public double translateX() {
        return translateX;
    }

    public double translateY() {
        return translateY;
    }

    public double scaleX() {
        return scaleX;
    }

    public double scaleY() {
        return scaleY;
    }

    public double rotationDegrees() {
        return rotationDegrees;
    }

    public boolean isIdentity() {
        return nearlyZero(translateX)
                && nearlyZero(translateY)
                && nearlyEqual(scaleX, 1.0)
                && nearlyEqual(scaleY, 1.0)
                && nearlyZero(rotationDegrees);
    }

    public TransformationState withTranslation(double newTranslateX, double newTranslateY) {
        return new TransformationState(newTranslateX, newTranslateY, scaleX, scaleY, rotationDegrees);
    }

    public TransformationState withScale(double newScaleX, double newScaleY) {
        return new TransformationState(translateX, translateY, newScaleX, newScaleY, rotationDegrees);
    }

    public TransformationState withRotation(double newRotationDegrees) {
        return new TransformationState(translateX, translateY, scaleX, scaleY, newRotationDegrees);
    }

    public TransformationState addTranslation(double deltaX, double deltaY) {
        return withTranslation(translateX + deltaX, translateY + deltaY);
    }

    public TransformationState addRotation(double deltaDegrees) {
        return withRotation(rotationDegrees + deltaDegrees);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TransformationState)) return false;
        TransformationState that = (TransformationState) o;
        return Double.compare(that.translateX, translateX) == 0
                && Double.compare(that.translateY, translateY) == 0
                && Double.compare(that.scaleX, scaleX) == 0
                && Double.compare(that.scaleY, scaleY) == 0
                && Double.compare(that.rotationDegrees, rotationDegrees) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(translateX, translateY, scaleX, scaleY, rotationDegrees);
    }

    @Override
    public String toString() {
        return "TransformationState{" +
                "translateX=" + translateX +
                ", translateY=" + translateY +
                ", scaleX=" + scaleX +
                ", scaleY=" + scaleY +
                ", rotationDegrees=" + rotationDegrees +
                '}';
    }

    private static double normalizeDegrees(double value) {
        double normalized = value % 360.0;
        if (normalized > 180.0) {
            normalized -= 360.0;
        } else if (normalized < -180.0) {
            normalized += 360.0;
        }
        return normalized;
    }

    private static double clampScale(double value) {
        double magnitude = Math.abs(value);
        if (magnitude < MIN_SCALE_MAGNITUDE) {
            magnitude = MIN_SCALE_MAGNITUDE;
        }
        return Math.copySign(magnitude, value == 0.0 ? 1.0 : value);
    }

    private static boolean nearlyZero(double value) {
        return Math.abs(value) < 1.0e-6;
    }

    private static boolean nearlyEqual(double a, double b) {
        return Math.abs(a - b) < 1.0e-6;
    }
}
