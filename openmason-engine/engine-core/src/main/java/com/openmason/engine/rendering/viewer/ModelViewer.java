package com.openmason.engine.rendering.viewer;

import com.openmason.engine.rendering.shaders.ShaderManager;
import com.openmason.engine.rendering.viewer.camera.ViewerCamera;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;

/**
 * A reusable offscreen 3D view: camera, framebuffer, and an ordered list of
 * {@link ViewerPass}es rendered into it.
 *
 * <p>This is the piece that used to be welded into the model editor's viewport — the
 * frame prologue/epilogue (bind, clear, fixed GL state, unbind) plus the pass sequence.
 * Hosts contribute what to draw; the viewer owns when and where.
 *
 * <p>Instances are independent, so several can coexist in one frame (the model editor's
 * viewport and a scene viewport, for example). Each owns its own framebuffer and sets its
 * own viewport rectangle at bind time, so ordering between viewers does not matter.
 *
 * <p><b>Threading:</b> every method touches OpenGL and must run on the GL thread.
 */
public final class ModelViewer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ModelViewer.class);

    private final ShaderManager shaderManager;
    private final boolean ownsShaders;
    private final ViewerSettings settings;

    private final ViewerCamera camera;
    private final ViewerRenderContext context;
    private final ViewerFramebuffer framebuffer = new ViewerFramebuffer();

    private final List<ViewerPass> passes = new ArrayList<>();

    private boolean initialized = false;

    /**
     * @param shaderManager shader programs to hand to passes
     * @param ownsShaders   true if {@link #close()} should also clean up the shader
     *                      manager; false when the host shares one across viewers
     * @param settings      display settings, owned by the host and read each frame
     */
    public ModelViewer(ShaderManager shaderManager, boolean ownsShaders, ViewerSettings settings) {
        this.shaderManager = java.util.Objects.requireNonNull(shaderManager, "shaderManager");
        this.ownsShaders = ownsShaders;
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        this.camera = new ViewerCamera();
        this.context = new ViewerRenderContext(camera);
    }

    // ---------------------------------------------------------------- lifecycle

    /** Create GL resources. Idempotent. */
    public void initialize(int width, int height) {
        if (initialized) {
            return;
        }
        framebuffer.create(width, height);
        settings.setSize(width, height);
        camera.setAspectRatio(height == 0 ? 1.0f : (float) width / height);
        initialized = true;
        logger.debug("ModelViewer initialized at {}x{}", width, height);
    }

    /** Recreate the framebuffer at a new size. No-op if unchanged or not yet initialized. */
    public void resize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        settings.setSize(width, height);
        camera.setAspectRatio((float) width / height);
        if (initialized && (framebuffer.getWidth() != width || framebuffer.getHeight() != height)) {
            framebuffer.create(width, height);
        }
    }

    /** Advance camera interpolation. */
    public void update(float deltaTime) {
        camera.update(deltaTime);
    }

    /**
     * Render one frame: bind, clear, configure state, run every enabled pass in order,
     * unbind.
     *
     * <p>A pass that throws is logged and skipped rather than aborting the frame — one
     * broken overlay should not blank the whole view.
     */
    public void render(float deltaTime) {
        if (!initialized) {
            initialize(settings.getWidth(), settings.getHeight());
        }

        camera.update(deltaTime);
        camera.updateMatrices();
        context.update(settings.getWidth(), settings.getHeight(), settings.isUnrendered());

        framebuffer.bind();

        glClearColor(settings.getClearRed(), settings.getClearGreen(),
                settings.getClearBlue(), settings.getClearAlpha());
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        // Pipeline-level state, set once per frame (passes may override locally).
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        glDepthMask(true);
        glDisable(GL_CULL_FACE);   // models are viewed from both sides
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);

        ViewerFrame frame = new ViewerFrame(context, shaderManager, settings,
                settings.getWidth(), settings.getHeight(), deltaTime);

        for (ViewerPass pass : passes) {
            if (!pass.isEnabled()) {
                continue;
            }
            try {
                pass.render(frame);
            } catch (Exception e) {
                logger.error("Error in viewer pass '{}'", pass.name(), e);
            }
        }

        framebuffer.unbind();
    }

    @Override
    public void close() {
        for (ViewerPass pass : passes) {
            try {
                pass.cleanup();
            } catch (Exception e) {
                logger.error("Error cleaning up viewer pass '{}'", pass.name(), e);
            }
        }
        passes.clear();
        framebuffer.close();
        if (ownsShaders) {
            shaderManager.cleanup();
        }
        initialized = false;
    }

    // ---------------------------------------------------------------- passes

    /**
     * Register a pass. Passes are kept sorted by {@link ViewerPass#order()}; ties keep
     * insertion order, so a host can add several passes at the same tier and rely on the
     * order it added them.
     */
    public void addPass(ViewerPass pass) {
        passes.add(java.util.Objects.requireNonNull(pass, "pass"));
        passes.sort(Comparator.comparingInt(ViewerPass::order));
    }

    public void removePass(ViewerPass pass) {
        passes.remove(pass);
    }

    /** Registered passes, in render order. */
    public List<ViewerPass> passes() {
        return List.copyOf(passes);
    }

    // ---------------------------------------------------------------- accessors

    public ViewerCamera camera() { return camera; }
    public ViewerRenderContext context() { return context; }
    public ViewerSettings settings() { return settings; }
    public ShaderManager shaders() { return shaderManager; }

    /** Texture holding the last rendered frame, for compositing into a UI. */
    public int colorTexture() { return framebuffer.getColorTextureId(); }
    public int framebufferWidth() { return framebuffer.getWidth(); }
    public int framebufferHeight() { return framebuffer.getHeight(); }
    public boolean isInitialized() { return initialized; }
}
