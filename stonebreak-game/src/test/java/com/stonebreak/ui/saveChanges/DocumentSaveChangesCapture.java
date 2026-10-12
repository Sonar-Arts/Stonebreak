package com.stonebreak.ui.saveChanges;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.runtime.GameUiDocuments;

import java.util.ArrayList;
import java.util.List;

/**
 * The "Save changes?" prompt's fidelity cases and the candidate side of its gate (Daedalus): the shipped
 * {@code ui/documents/save_changes.sbui} on a {@link DocumentStage}. Variants {@code plain}, {@code hover-yes},
 * {@code hover-no}; rects {@code panel}, {@code yes}, {@code no}. Not a test class.
 */
public final class DocumentSaveChangesCapture implements MigrationGate.Renderer {

    private final SbuiArchive shipped;

    public DocumentSaveChangesCapture(SbuiArchive shipped) {
        this.shipped = shipped;
    }

    public static DocumentSaveChangesCapture shipped() throws java.io.IOException {
        return new DocumentSaveChangesCapture(GameUiDocuments.readScreen(SaveChangesDialog.DOCUMENT_ID));
    }

    /** Every frozen capture: plain at the standard viewports, each button hovered (No also at 1.25). */
    public static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("save-changes", List.of("plain"),
            FidelityCase.STANDARD));
        out.add(new FidelityCase("save-changes", "hover-yes", FidelityCase.STANDARD.get(0)));
        out.add(new FidelityCase("save-changes", "hover-no", FidelityCase.STANDARD.get(0)));
        out.add(new FidelityCase("save-changes", "hover-no", FidelityCase.STANDARD.get(3)));
        return out;
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        FidelityCase.Viewport vp = c.viewport();
        try (DocumentStage stage = new DocumentStage(shipped, vp.width(), vp.height(), vp.uiScale())) {
            if (c.variant().startsWith("hover-")) {
                stage.hover(c.variant().substring("hover-".length()));
            }
            stage.paint();
            return new MigrationGate.Capture(stage.raster.capture(), stage.rects(List.of("panel", "yes", "no")));
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the save-changes document for " + c.id(), e);
        }
    }
}
