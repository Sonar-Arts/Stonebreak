package com.openmason.main.systems.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opening, closing or switching UI documents changes what the .omp records (#293 review): the
 * project turns dirty so exit offers to save it, while restoring the recorded session on open
 * is part of the load, not an edit.
 */
class ProjectUiSessionDirtyTest {

    @TempDir
    Path tempDir;

    private static ProjectService headlessService() {
        return new ProjectService() {
            @Override
            public void restoreState(OMPFormat.Document document,
                                     com.openmason.main.systems.ViewportController viewport,
                                     com.openmason.main.systems.stateHandling.ModelState modelState,
                                     com.openmason.main.systems.stateHandling.UIVisibilityState uiState,
                                     com.openmason.main.systems.services.ModelOperationService modelOperations) {
            }
        };
    }

    @Test
    void uiSessionChangesDirtyTheProjectButRestoringDoesNot() {
        Path file = tempDir.resolve("p.omp");
        OMPFormat.UiEditorReference recorded = new OMPFormat.UiEditorReference("UI", List.of("UI/a.omui"), "UI/a.omui");
        assertTrue(new OMPSerializer().save(new OMPFormat.Document(OMPFormat.FORMAT_VERSION, "P", null, null, null,
            null, null, null, null, null, null, recorded), file.toString()));

        ProjectService service = headlessService();
        AtomicReference<OMPFormat.UiEditorReference> live = new AtomicReference<>();
        service.setUiEditorSessionHooks(live::get, ref -> {
            live.set(ref); // the editor reopens the recorded documents...
            service.uiSessionChanged(); // ...and its listeners fire while it does
        });
        assertTrue(service.openProject(file.toString(), null, null, null, null));
        assertFalse(service.hasUnsavedChanges(), "restoring the recorded session is not an edit");

        service.uiSessionChanged(); // nothing changed
        assertFalse(service.hasUnsavedChanges());

        live.set(new OMPFormat.UiEditorReference("UI", List.of("UI/a.omui", "UI/b.omui"), "UI/b.omui"));
        service.uiSessionChanged();
        assertTrue(service.hasUnsavedChanges(), "a newly opened document must be recorded");
    }
}
