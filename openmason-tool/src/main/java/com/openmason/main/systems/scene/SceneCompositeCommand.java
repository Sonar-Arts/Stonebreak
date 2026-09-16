package com.openmason.main.systems.scene;

import com.openmason.main.systems.services.commands.ModelCommand;

import java.util.List;

/**
 * Several scene commands recorded as one undo step — a multi-instance delete, a group
 * gizmo drag, a bulk visibility toggle.
 *
 * <p>Executes in order and undoes in reverse, so a composite of lifecycle commands
 * restores scene order exactly.
 */
final class SceneCompositeCommand implements ModelCommand {

    private final List<ModelCommand> parts;
    private final String description;

    SceneCompositeCommand(String description, List<? extends ModelCommand> parts) {
        this.parts = List.copyOf(parts);
        this.description = description;
    }

    List<ModelCommand> parts() {
        return parts;
    }

    @Override
    public void execute() {
        for (ModelCommand part : parts) {
            part.execute();
        }
    }

    @Override
    public void undo() {
        for (int i = parts.size() - 1; i >= 0; i--) {
            parts.get(i).undo();
        }
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public boolean canMergeWith(ModelCommand other) {
        return false;
    }

    @Override
    public ModelCommand mergeWith(ModelCommand other) {
        throw new UnsupportedOperationException("SceneCompositeCommand is not mergeable");
    }
}
