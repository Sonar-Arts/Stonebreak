package com.stonebreak.world.chunk.api.mightyMesh.mmsCore;

/**
 * Mighty Mesh System - Mesh data validation utilities.
 *
 * Provides comprehensive validation for mesh data to ensure correctness
 * and prevent common errors during mesh generation and GPU upload.
 *
 * Design Philosophy:
 * - Fail-fast: Detect errors early in the pipeline
 * - Clear messages: Helpful error messages for debugging
 * - KISS: Simple, focused validation methods
 * - Performance: Validation can be disabled in release builds
 *
 * @since MMS 1.0
 */
public final class MmsMeshValidator {

    // Prevent instantiation
    private MmsMeshValidator() {
        throw new AssertionError("MmsMeshValidator is a static utility class");
    }

    // === Validation Configuration ===

    /** Enable/disable validation checks (can be toggled for release builds) */
    private static boolean validationEnabled = true;

    /**
     * Enables or disables validation checks globally.
     * Disabling can improve performance in release builds.
     *
     * @param enabled true to enable validation, false to disable
     */
    public static void setValidationEnabled(boolean enabled) {
        validationEnabled = enabled;
    }

    /**
     * Checks if validation is currently enabled.
     *
     * @return true if validation is enabled
     */
    public static boolean isValidationEnabled() {
        return validationEnabled;
    }

    // === Array Validation ===

    /**
     * Validates that array sizes are consistent for mesh data.
     *
     * @param vertexPositions Vertex position array
     * @param textureCoordinates Texture coordinate array
     * @param vertexNormals Normal vector array
     * @param waterFlags Water flag array
     * @param alphaFlags Alpha flag array
     * @param indices Index array
     * @param indexCount Number of valid indices
     * @return ValidationResult with status and error message if invalid
     */
    public static ValidationResult validateArraySizes(
            float[] vertexPositions, float[] textureCoordinates, float[] vertexNormals,
            float[] waterFlags, float[] alphaFlags, int[] indices, int indexCount) {

        if (!validationEnabled) {
            return ValidationResult.valid();
        }

        // Check for null arrays
        if (vertexPositions == null) {
            return ValidationResult.invalid("Vertex positions array is null");
        }
        if (textureCoordinates == null) {
            return ValidationResult.invalid("Texture coordinates array is null");
        }
        if (vertexNormals == null) {
            return ValidationResult.invalid("Vertex normals array is null");
        }
        if (waterFlags == null) {
            return ValidationResult.invalid("Water flags array is null");
        }
        if (alphaFlags == null) {
            return ValidationResult.invalid("Alpha flags array is null");
        }
        if (indices == null) {
            return ValidationResult.invalid("Indices array is null");
        }

        // Calculate expected vertex count from positions
        if (vertexPositions.length % MmsBufferLayout.POSITION_SIZE != 0) {
            return ValidationResult.invalid(
                String.format("Vertex positions array length (%d) not divisible by %d",
                    vertexPositions.length, MmsBufferLayout.POSITION_SIZE)
            );
        }

        int vertexCount = vertexPositions.length / MmsBufferLayout.POSITION_SIZE;

        // Validate texture coordinates
        int expectedTexCoordSize = vertexCount * MmsBufferLayout.TEXTURE_SIZE;
        if (textureCoordinates.length != expectedTexCoordSize) {
            return ValidationResult.invalid(
                String.format("Texture coordinates array size mismatch: expected %d, got %d",
                    expectedTexCoordSize, textureCoordinates.length)
            );
        }

        // Validate normals
        int expectedNormalSize = vertexCount * MmsBufferLayout.NORMAL_SIZE;
        if (vertexNormals.length != expectedNormalSize) {
            return ValidationResult.invalid(
                String.format("Vertex normals array size mismatch: expected %d, got %d",
                    expectedNormalSize, vertexNormals.length)
            );
        }

        // Validate water flags
        if (waterFlags.length != vertexCount) {
            return ValidationResult.invalid(
                String.format("Water flags array size mismatch: expected %d, got %d",
                    vertexCount, waterFlags.length)
            );
        }

        // Validate alpha flags
        if (alphaFlags.length != vertexCount) {
            return ValidationResult.invalid(
                String.format("Alpha flags array size mismatch: expected %d, got %d",
                    vertexCount, alphaFlags.length)
            );
        }

        // Validate index count
        if (indexCount < 0) {
            return ValidationResult.invalid("Index count cannot be negative: " + indexCount);
        }
        if (indexCount > indices.length) {
            return ValidationResult.invalid(
                String.format("Index count (%d) exceeds index array size (%d)",
                    indexCount, indices.length)
            );
        }
        if (indexCount % 3 != 0) {
            return ValidationResult.invalid(
                String.format("Index count must be multiple of 3 (triangles): %d", indexCount)
            );
        }

        return ValidationResult.valid();
    }

    /**
     * Validates index values are within bounds for the vertex count.
     *
     * @param indices Index array
     * @param indexCount Number of valid indices
     * @param vertexCount Number of vertices
     * @return ValidationResult with status and error message if invalid
     */
    public static ValidationResult validateIndexBounds(int[] indices, int indexCount, int vertexCount) {
        if (!validationEnabled) {
            return ValidationResult.valid();
        }

        if (indices == null) {
            return ValidationResult.invalid("Indices array is null");
        }

        for (int i = 0; i < indexCount; i++) {
            int index = indices[i];
            if (index < 0 || index >= vertexCount) {
                return ValidationResult.invalid(
                    String.format("Index %d out of bounds: %d (vertex count: %d)", i, index, vertexCount)
                );
            }
        }

        return ValidationResult.valid();
    }

    /**
     * Validates that vertex positions are finite (not NaN or Infinity).
     *
     * @param vertexPositions Vertex position array
     * @return ValidationResult with status and error message if invalid
     */
    public static ValidationResult validateFiniteValues(float[] vertexPositions) {
        if (!validationEnabled) {
            return ValidationResult.valid();
        }

        if (vertexPositions == null) {
            return ValidationResult.invalid("Vertex positions array is null");
        }

        for (int i = 0; i < vertexPositions.length; i++) {
            float value = vertexPositions[i];
            if (!Float.isFinite(value)) {
                return ValidationResult.invalid(
                    String.format("Non-finite vertex position at index %d: %f", i, value)
                );
            }
        }

        return ValidationResult.valid();
    }

    /**
     * Validates mesh data is within memory limits.
     *
     * @param vertexCount Number of vertices
     * @param indexCount Number of indices
     * @param maxMemoryBytes Maximum allowed memory in bytes
     * @return ValidationResult with status and error message if invalid
     */
    public static ValidationResult validateMemoryLimits(int vertexCount, int indexCount, long maxMemoryBytes) {
        if (!validationEnabled) {
            return ValidationResult.valid();
        }

        long estimatedMemory = MmsBufferLayout.calculateTotalMeshMemory(vertexCount, indexCount);

        if (estimatedMemory > maxMemoryBytes) {
            return ValidationResult.invalid(
                String.format("Mesh exceeds memory limit: %d bytes (limit: %d bytes)",
                    estimatedMemory, maxMemoryBytes)
            );
        }

        return ValidationResult.valid();
    }

    /**
     * Performs comprehensive validation on MmsMeshData.
     *
     * @param meshData Mesh data to validate
     * @return ValidationResult with status and error message if invalid
     */
    public static ValidationResult validate(MmsMeshData meshData) {
        if (!validationEnabled) {
            return ValidationResult.valid();
        }

        if (meshData == null) {
            return ValidationResult.invalid("Mesh data is null");
        }

        // Empty meshes are valid
        if (meshData.isEmpty()) {
            return ValidationResult.valid();
        }

        // Validate array sizes
        ValidationResult sizeResult = validateArraySizes(
            meshData.getVertexPositions(),
            meshData.getTextureCoordinates(),
            meshData.getVertexNormals(),
            meshData.getWaterHeightFlags(),
            meshData.getAlphaTestFlags(),
            meshData.getIndices(),
            meshData.getIndexCount()
        );

        if (!sizeResult.isValid()) {
            return sizeResult;
        }

        // Validate index bounds
        ValidationResult boundsResult = validateIndexBounds(
            meshData.getIndices(),
            meshData.getIndexCount(),
            meshData.getVertexCount()
        );

        if (!boundsResult.isValid()) {
            return boundsResult;
        }

        // Validate finite values
        ValidationResult finiteResult = validateFiniteValues(meshData.getVertexPositions());

        if (!finiteResult.isValid()) {
            return finiteResult;
        }

        return ValidationResult.valid();
    }

    // === Validation Result ===

    /**
     * Result of a validation check.
     */
    public static final class ValidationResult {
        private final boolean valid;
        private final String errorMessage;

        private ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        /**
         * Creates a valid result.
         *
         * @return Valid validation result
         */
        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        /**
         * Creates an invalid result with error message.
         *
         * @param errorMessage Error message describing why validation failed
         * @return Invalid validation result
         */
        public static ValidationResult invalid(String errorMessage) {
            return new ValidationResult(false, errorMessage);
        }

        /**
         * Checks if validation passed.
         *
         * @return true if valid
         */
        public boolean isValid() {
            return valid;
        }

        /**
         * Gets the error message (null if valid).
         *
         * @return Error message or null
         */
        public String getErrorMessage() {
            return errorMessage;
        }

        /**
         * Throws IllegalArgumentException if validation failed.
         *
         * @throws IllegalArgumentException if validation failed
         */
        public void throwIfInvalid() {
            if (!valid) {
                throw new IllegalArgumentException("Mesh validation failed: " + errorMessage);
            }
        }

        @Override
        public String toString() {
            return valid ? "ValidationResult{valid}" : "ValidationResult{invalid: " + errorMessage + "}";
        }
    }
}
