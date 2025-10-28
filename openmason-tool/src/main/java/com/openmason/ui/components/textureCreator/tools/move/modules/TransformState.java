package com.openmason.ui.components.textureCreator.tools.move.modules;

import java.awt.Point;

/**
 * Immutable data class representing the current transformation state.
 * Follows the Builder pattern for easy updates.
 */
public class TransformState {
    private final int translateX;
    private final int translateY;
    private final double scaleX;
    private final double scaleY;
    private final double rotationDegrees;
    private final Point pivot;

    private TransformState(Builder builder) {
        this.translateX = builder.translateX;
        this.translateY = builder.translateY;
        this.scaleX = builder.scaleX;
        this.scaleY = builder.scaleY;
        this.rotationDegrees = builder.rotationDegrees;
        this.pivot = builder.pivot != null ? new Point(builder.pivot) : new Point(0, 0);
    }

    public static TransformState identity() {
        return new Builder().build();
    }

    public static TransformState translation(int dx, int dy) {
        return new Builder().translate(dx, dy).build();
    }

    public int getTranslateX() {
        return translateX;
    }

    public int getTranslateY() {
        return translateY;
    }

    public double getScaleX() {
        return scaleX;
    }

    public double getScaleY() {
        return scaleY;
    }

    public double getRotationDegrees() {
        return rotationDegrees;
    }

    public Point getPivot() {
        return new Point(pivot);
    }

    /**
     * Creates a new Builder initialized with this transform's values.
     */
    public Builder toBuilder() {
        return new Builder()
                .translate(translateX, translateY)
                .scale(scaleX, scaleY)
                .rotate(rotationDegrees)
                .pivot(pivot);
    }

    /**
     * Checks if this transform is the identity transform (no changes).
     */
    public boolean isIdentity() {
        return translateX == 0 && translateY == 0 &&
               scaleX == 1.0 && scaleY == 1.0 &&
               rotationDegrees == 0.0;
    }

    public static class Builder {
        private int translateX = 0;
        private int translateY = 0;
        private double scaleX = 1.0;
        private double scaleY = 1.0;
        private double rotationDegrees = 0.0;
        private Point pivot = new Point(0, 0);

        public Builder translate(int dx, int dy) {
            this.translateX = dx;
            this.translateY = dy;
            return this;
        }

        public Builder scale(double sx, double sy) {
            this.scaleX = sx;
            this.scaleY = sy;
            return this;
        }

        public Builder uniformScale(double scale) {
            this.scaleX = scale;
            this.scaleY = scale;
            return this;
        }

        public Builder rotate(double degrees) {
            this.rotationDegrees = degrees;
            return this;
        }

        public Builder pivot(Point pivot) {
            this.pivot = new Point(pivot);
            return this;
        }

        public Builder pivot(int x, int y) {
            this.pivot = new Point(x, y);
            return this;
        }

        public TransformState build() {
            return new TransformState(this);
        }
    }

    @Override
    public String toString() {
        return String.format("TransformState[translate=(%d, %d), scale=(%.2f, %.2f), rotation=%.1f°, pivot=%s]",
                translateX, translateY, scaleX, scaleY, rotationDegrees, pivot);
    }
}
