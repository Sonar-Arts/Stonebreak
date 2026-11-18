package com.stonebreak.ui.terrainMapper.visualization;

/**
 * A Java noise-stack visualizer read at the native generator's shape-space position (block x
 * {@link NativeStandardColumns#shapeScale}), so its field lines up with the selected terrain scale. Everything
 * but {@link #sample} is the wrapped visualizer's. A factor of 1 (1:4) is the visualizer itself.
 */
final class ShapeScaledVisualizer implements NoiseVisualizer {

    private final NoiseVisualizer inner;
    private final double scale;

    private ShapeScaledVisualizer(NoiseVisualizer inner, double scale) {
        this.inner = inner;
        this.scale = scale;
    }

    static NoiseVisualizer of(NoiseVisualizer inner, double scale) {
        return scale == 1.0 ? inner : new ShapeScaledVisualizer(inner, scale);
    }

    @Override public String displayName() { return inner.displayName(); }

    @Override
    public float sample(int worldX, int worldZ) {
        return inner.sample((int) Math.floor(worldX * scale), (int) Math.floor(worldZ * scale));
    }

    @Override public PreviewChannel channel() { return inner.channel(); }
    @Override public float normalize(float raw) { return inner.normalize(raw); }
    @Override public int colorFor(float normalized) { return inner.colorFor(normalized); }
    @Override public String formatValue(float raw) { return inner.formatValue(raw); }

    @Override
    public void postProcess(float[] raw, int[] pixels, int width, int height, float blocksPerSample) {
        inner.postProcess(raw, pixels, width, height, blocksPerSample);
    }
}
