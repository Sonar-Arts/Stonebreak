package com.openmason.engine.rendering.viewer.scene;

import com.openmason.engine.rendering.model.ModelBounds;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A flat list of placed {@link ModelInstance}s.
 *
 * <p>Deliberately flat: v1 scenes have no grouping or parent-child nesting, so an
 * instance's transform is its world transform and nothing has to be composed.
 *
 * <p>Instances deliberately do <b>not</b> hold {@link ModelCache} references. Cache
 * lifetime is owned by whoever acquired the model (the host's scene document, one
 * reference per distinct model), because a container that decremented the handle directly
 * would move the counter without the cache's knowledge — leaving entries that can never
 * be evicted no matter how many instances are removed.
 */
public final class ModelScene {

    private final List<ModelInstance> instances = new ArrayList<>();

    /** Add an instance at the origin. */
    public ModelInstance add(ModelHandle model, String name) {
        ModelInstance instance = new ModelInstance(UUID.randomUUID().toString(), model, name);
        instances.add(instance);
        return instance;
    }

    /** Add an instance with an initial transform. */
    public ModelInstance add(ModelHandle model, String name, Vector3f position) {
        ModelInstance instance = add(model, name);
        instance.transform().setPosition(position.x, position.y, position.z);
        return instance;
    }

    public boolean remove(ModelInstance instance) {
        return instances.remove(instance);
    }

    public void clear() {
        instances.clear();
    }

    /** Live view in insertion order. */
    public List<ModelInstance> instances() {
        return List.copyOf(instances);
    }

    public int size() { return instances.size(); }
    public boolean isEmpty() { return instances.isEmpty(); }

    public ModelInstance byId(String id) {
        for (ModelInstance instance : instances) {
            if (instance.id().equals(id)) {
                return instance;
            }
        }
        return null;
    }

    /** How many instances place the given model. */
    public int instanceCountOf(ModelHandle model) {
        int count = 0;
        for (ModelInstance instance : instances) {
            if (instance.model() == model) {
                count++;
            }
        }
        return count;
    }

    /** Combined world bounds of every visible instance; EMPTY when there are none. */
    public ModelBounds worldBounds() {
        Vector3f min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        boolean any = false;

        for (ModelInstance instance : instances) {
            if (!instance.isVisible()) {
                continue;
            }
            ModelBounds b = instance.worldBounds();
            min.min(b.min());
            max.max(b.max());
            any = true;
        }

        if (!any) {
            return ModelBounds.EMPTY;
        }
        Vector3f center = new Vector3f(min).add(max).mul(0.5f);
        Vector3f size = new Vector3f(max).sub(min);
        return new ModelBounds(min, max, center, size);
    }
}
