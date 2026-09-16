package com.openmason.main.systems.menus.animationEditor;

import com.openmason.engine.rendering.model.gmr.parts.ModelPartDescriptor;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.parts.PartShapeFactory;
import com.openmason.main.systems.menus.animationEditor.data.Easing;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import org.joml.Vector3f;

/** Headless model fixtures for animation editor tests (no GL: {@link ModelPartManager} is pure data). */
public final class AnimTestModels {

    private AnimTestModels() {}

    /** A part manager with the named cube parts, all at identity rest pose. */
    public static ModelPartManager model(String... names) {
        ModelPartManager pm = new ModelPartManager();
        for (String name : names) addCube(pm, name);
        return pm;
    }

    public static ModelPartDescriptor addCube(ModelPartManager pm, String name) {
        return pm.addPartFromGeometry(name,
                PartShapeFactory.createGeometry(PartShapeFactory.Shape.CUBE, name, new Vector3f(1, 1, 1)),
                new Vector3f());
    }

    public static String idOf(ModelPartManager pm, String name) {
        return pm.getAllParts().stream()
                .filter(p -> p.name().equals(name))
                .findFirst().orElseThrow().id();
    }

    public static Keyframe kf(float t, float x) {
        return new Keyframe(t, new Vector3f(x, 0, 0), new Vector3f(), new Vector3f(1, 1, 1), Easing.LINEAR);
    }
}
