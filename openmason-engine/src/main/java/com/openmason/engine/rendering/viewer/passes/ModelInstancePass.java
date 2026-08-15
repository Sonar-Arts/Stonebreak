package com.openmason.engine.rendering.viewer.passes;

import com.openmason.engine.rendering.model.GenericModelRenderer;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shaders.ShaderType;
import com.openmason.engine.rendering.viewer.ViewerFrame;
import com.openmason.engine.rendering.viewer.ViewerPass;
import com.openmason.engine.rendering.viewer.ViewerPassOrder;
import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.engine.rendering.viewer.scene.ModelScene;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws every visible instance in a {@link ModelScene}.
 *
 * <p>One {@link GenericModelRenderer} per distinct model, drawn once per instance with
 * that instance's matrix — the renderer's draw call already takes a model matrix and is
 * stateless per call, so placing the same model twenty times costs twenty draws and one
 * upload rather than twenty of each.
 */
public final class ModelInstancePass implements ViewerPass {

    private static final Vector3f UNRENDERED_GRAY = new Vector3f(0.53f, 0.53f, 0.53f);

    /** Untextured fallback tint; white so a bound texture is passed through unmodified. */
    private static final Vector3f DEFAULT_TINT = new Vector3f(1.0f, 1.0f, 1.0f);

    private final ModelScene scene;

    public ModelInstancePass(ModelScene scene) {
        this.scene = java.util.Objects.requireNonNull(scene, "scene");
    }

    @Override
    public int order() {
        return ViewerPassOrder.CONTENT;
    }

    @Override
    public String name() {
        return "model-instances";
    }

    @Override
    public void render(ViewerFrame frame) {
        if (scene.isEmpty()) {
            return;
        }

        ShaderProgram shader = frame.shaders().getShaderProgram(ShaderType.MATRIX);
        shader.use();

        Matrix4f view = frame.context().getCamera().getViewMatrix();
        Matrix4f viewProjection = new Matrix4f(frame.context().getCamera().getProjectionMatrix()).mul(view);

        // REQUIRED: the MATRIX shader derives its flat-shading normal from view-space
        // position derivatives, so an unset uViewMatrix leaves fragPosView at the origin
        // for every vertex. The derivatives are then zero, normalize(cross(0,0)) is NaN,
        // and the lit colour comes out black — with a perfectly valid texture bound.
        shader.setMat4("uViewMatrix", view);

        boolean unrendered = frame.settings().isUnrendered();

        // The shader multiplies by uColor on the untextured path; without a sane default
        // an untextured model would render black for the same reason.
        if (!unrendered) {
            shader.setVec3("uColor", DEFAULT_TINT);
        }

        for (ModelInstance instance : scene.instances()) {
            if (!instance.isVisible()) {
                continue;
            }
            GenericModelRenderer renderer = instance.model().renderer();
            if (renderer == null || !renderer.isInitialized()) {
                continue;
            }

            Matrix4f modelMatrix = instance.modelMatrix();
            shader.setMat4("uMVPMatrix", viewProjection);
            shader.setMat4("uModelMatrix", modelMatrix);

            renderer.setForceUnrendered(unrendered);
            if (unrendered) {
                shader.setVec3("uColor", UNRENDERED_GRAY);
            }
            shader.setInt("uTexture", 0);
            shader.setBool("uUseTexture", !unrendered);

            renderer.render(shader, frame.context(), modelMatrix);
        }
    }
}
