package com.openmason.main.systems.scene;

import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import com.openmason.main.systems.services.commands.ModelCommand;

import java.util.function.Function;

/**
 * Undoable change of one non-transform instance property: name, visibility or lock.
 *
 * <p>Resolved by id at apply time, like {@link SceneInstanceTransformCommand}, so an entry
 * for an instance that has since been deleted degrades to a no-op — and works again once
 * an undo-delete puts the instance back under the same id.
 */
final class SceneInstancePropertyCommand implements ModelCommand {

    enum Property { NAME, VISIBLE, LOCKED }

    private final String instanceId;
    private final Function<String, ModelInstance> resolver;
    private final Property property;
    private final Object oldValue;
    private final Object newValue;

    SceneInstancePropertyCommand(String instanceId, Function<String, ModelInstance> resolver,
                                 Property property, Object oldValue, Object newValue) {
        this.instanceId = java.util.Objects.requireNonNull(instanceId, "instanceId");
        this.resolver = java.util.Objects.requireNonNull(resolver, "resolver");
        this.property = java.util.Objects.requireNonNull(property, "property");
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    String instanceId() {
        return instanceId;
    }

    @Override
    public void execute() {
        apply(newValue);
    }

    @Override
    public void undo() {
        apply(oldValue);
    }

    private void apply(Object value) {
        ModelInstance instance = resolver.apply(instanceId);
        if (instance == null) {
            return;
        }
        switch (property) {
            case NAME -> instance.setName((String) value);
            case VISIBLE -> instance.setVisible((Boolean) value);
            case LOCKED -> instance.setLocked((Boolean) value);
        }
    }

    @Override
    public String getDescription() {
        return switch (property) {
            case NAME -> "Rename Instance";
            case VISIBLE -> Boolean.TRUE.equals(newValue) ? "Show Instance" : "Hide Instance";
            case LOCKED -> Boolean.TRUE.equals(newValue) ? "Lock Instance" : "Unlock Instance";
        };
    }

    @Override
    public boolean canMergeWith(ModelCommand other) {
        return false;
    }

    @Override
    public ModelCommand mergeWith(ModelCommand other) {
        throw new UnsupportedOperationException("SceneInstancePropertyCommand is not mergeable");
    }
}
