package com.openmason.engine.rendering.viewer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link ViewerPass} contract that {@link ModelViewer} relies on.
 *
 * <p>Ordering is load-bearing: the grid must be behind the models, and the editor's
 * x-ray overlays must come last. {@code ModelViewer.render()} itself touches OpenGL, so
 * these exercise the ordering and failure-isolation rules through the same
 * {@code addPass}/sort path without needing a GL context.
 */
class ViewerPassOrderingTest {

    /** Minimal pass that appends its name to a shared log when rendered. */
    private static final class RecordingPass implements ViewerPass {
        private final String name;
        private final int order;
        private final List<String> log;
        private boolean enabled = true;
        boolean cleanedUp = false;

        RecordingPass(String name, int order, List<String> log) {
            this.name = name;
            this.order = order;
            this.log = log;
        }

        @Override public int order() { return order; }
        @Override public String name() { return name; }
        @Override public boolean isEnabled() { return enabled; }
        @Override public void render(ViewerFrame frame) { log.add(name); }
        @Override public void cleanup() { cleanedUp = true; }
    }

    /** Renders the registered passes the way ModelViewer does, minus the GL calls. */
    private static void runPasses(ModelViewer viewer, List<String> failures) {
        for (ViewerPass pass : viewer.passes()) {
            if (!pass.isEnabled()) {
                continue;
            }
            try {
                pass.render(null);
            } catch (Exception e) {
                failures.add(pass.name());
            }
        }
    }

    private static ModelViewer newViewer() {
        // No GL work happens until initialize()/render(), so constructing is safe here.
        return new ModelViewer(new com.openmason.engine.rendering.shaders.ShaderManager(),
                false, new ViewerSettings());
    }

    @Test
    @DisplayName("passes run in ascending order regardless of registration order")
    void passesRunInOrder() {
        List<String> log = new ArrayList<>();
        ModelViewer viewer = newViewer();

        viewer.addPass(new RecordingPass("xray", ViewerPassOrder.XRAY_OVERLAY, log));
        viewer.addPass(new RecordingPass("grid", ViewerPassOrder.GRID, log));
        viewer.addPass(new RecordingPass("gizmo", ViewerPassOrder.GIZMO, log));
        viewer.addPass(new RecordingPass("content", ViewerPassOrder.CONTENT, log));

        runPasses(viewer, new ArrayList<>());

        assertEquals(List.of("grid", "content", "gizmo", "xray"), log);
    }

    @Test
    @DisplayName("passes at the same order keep insertion order")
    void tiesKeepInsertionOrder() {
        // The editor registers several overlays at one tier and relies on the sequence
        // it added them in.
        List<String> log = new ArrayList<>();
        ModelViewer viewer = newViewer();

        viewer.addPass(new RecordingPass("first", ViewerPassOrder.CONTENT, log));
        viewer.addPass(new RecordingPass("second", ViewerPassOrder.CONTENT, log));
        viewer.addPass(new RecordingPass("third", ViewerPassOrder.CONTENT, log));

        runPasses(viewer, new ArrayList<>());

        assertEquals(List.of("first", "second", "third"), log);
    }

    @Test
    @DisplayName("a disabled pass is skipped but stays registered")
    void disabledPassIsSkipped() {
        List<String> log = new ArrayList<>();
        ModelViewer viewer = newViewer();

        RecordingPass grid = new RecordingPass("grid", ViewerPassOrder.GRID, log);
        RecordingPass content = new RecordingPass("content", ViewerPassOrder.CONTENT, log);
        grid.enabled = false;
        viewer.addPass(grid);
        viewer.addPass(content);

        runPasses(viewer, new ArrayList<>());

        assertEquals(List.of("content"), log);
        assertEquals(2, viewer.passes().size(), "disabling must not unregister");
    }

    @Test
    @DisplayName("a throwing pass does not abort the passes after it")
    void throwingPassDoesNotAbortFrame() {
        // One broken overlay should cost its own layer, not blank the whole viewport.
        List<String> log = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        ModelViewer viewer = newViewer();

        viewer.addPass(new RecordingPass("grid", ViewerPassOrder.GRID, log));
        viewer.addPass(new ViewerPass() {
            @Override public int order() { return ViewerPassOrder.CONTENT; }
            @Override public String name() { return "boom"; }
            @Override public void render(ViewerFrame frame) { throw new IllegalStateException("boom"); }
        });
        viewer.addPass(new RecordingPass("gizmo", ViewerPassOrder.GIZMO, log));

        runPasses(viewer, failures);

        assertEquals(List.of("grid", "gizmo"), log, "passes after the failure still ran");
        assertEquals(List.of("boom"), failures);
    }

    @Test
    @DisplayName("removePass unregisters")
    void removePassUnregisters() {
        List<String> log = new ArrayList<>();
        ModelViewer viewer = newViewer();

        RecordingPass grid = new RecordingPass("grid", ViewerPassOrder.GRID, log);
        viewer.addPass(grid);
        viewer.addPass(new RecordingPass("content", ViewerPassOrder.CONTENT, log));
        viewer.removePass(grid);

        runPasses(viewer, new ArrayList<>());

        assertEquals(List.of("content"), log);
    }

    @Test
    @DisplayName("the standard tiers are ordered grid < content < overlay < mesh < gizmo < xray")
    void standardTierOrdering() {
        assertTrue(ViewerPassOrder.GRID < ViewerPassOrder.CONTENT);
        assertTrue(ViewerPassOrder.CONTENT < ViewerPassOrder.CONTENT_OVERLAY);
        assertTrue(ViewerPassOrder.CONTENT_OVERLAY < ViewerPassOrder.MESH_OVERLAY);
        assertTrue(ViewerPassOrder.MESH_OVERLAY < ViewerPassOrder.GIZMO);
        assertTrue(ViewerPassOrder.GIZMO < ViewerPassOrder.XRAY_OVERLAY);
    }

    @Test
    @DisplayName("passes() is a defensive copy")
    void passesIsDefensiveCopy() {
        ModelViewer viewer = newViewer();
        viewer.addPass(new RecordingPass("grid", ViewerPassOrder.GRID, new ArrayList<>()));

        List<ViewerPass> snapshot = viewer.passes();
        viewer.addPass(new RecordingPass("content", ViewerPassOrder.CONTENT, new ArrayList<>()));

        assertEquals(1, snapshot.size(), "an earlier snapshot must not see later additions");
        assertEquals(2, viewer.passes().size());
    }
}
