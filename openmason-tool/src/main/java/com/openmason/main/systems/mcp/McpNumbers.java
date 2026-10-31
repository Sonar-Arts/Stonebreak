package com.openmason.main.systems.mcp;

/** Rounding used by MCP replies so geometry reads stay short and stable across runs. */
public final class McpNumbers {

    private McpNumbers() {
    }

    /** {@code v} rounded to 4 decimals. */
    public static float round4(float v) {
        return Math.round(v * 10000f) / 10000f;
    }

    /** Each component rounded to 3 decimals (null stays null). */
    public static float[] round3(float[] v) {
        if (v == null) {
            return null;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = Math.round(v[i] * 1000f) / 1000f;
        }
        return out;
    }
}
