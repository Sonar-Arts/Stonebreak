package com.openmason.ui.textureCreator.tools.move;

/**
 * Immutable snapshot of a transformation composed of translation, scaling,
 * and rotation around the selection centre.
 */
public record TransformationState(double translateX, double translateY, double scaleX, double scaleY,
                                  double rotationDegrees) {

    private static final double MIN_SCALE_MAGNITUDE = 0.01;

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

    public boolean isIdentity() {
        return nearlyZero(translateX)
                && nearlyZero(translateY)
                && nearlyEqual(scaleX)
                && nearlyEqual(scaleY)
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TransformationState that)) return false;
        return Double.compare(that.translateX, translateX) == 0
                && Double.compare(that.translateY, translateY) == 0
                && Double.compare(that.scaleX, scaleX) == 0
                && Double.compare(that.scaleY, scaleY) == 0
                && Double.compare(that.rotationDegrees, rotationDegrees) == 0;
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

    private static boolean nearlyEqual(double a) {
        return Math.abs(a - 1.0) < 1.0e-6;
    }
}
