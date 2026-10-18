package com.openmason.api.viewport;

import com.openmason.engine.rendering.shaders.ShaderProgram;
import org.joml.Matrix4f;

/**
 * The viewport state a {@link ViewportContentProvider} draws with. The matrix shader is bound and
 * its {@code uViewMatrix} set to {@code view * model} before {@link ViewportContentProvider#render}.
 */
public interface ViewportContentFrame {

    /** The flat-shaded matrix shader ({@code uMVPMatrix}, {@code uModelMatrix}, {@code uTexture}, {@code uUseTexture}). */
    ShaderProgram matrixShader();

    /** Column-major view-projection matrix. */
    float[] viewProjection();

    /** The content's model transform (the viewport gizmo's). */
    Matrix4f modelMatrix();

    /** The camera's view matrix. */
    Matrix4f viewMatrix();
}
