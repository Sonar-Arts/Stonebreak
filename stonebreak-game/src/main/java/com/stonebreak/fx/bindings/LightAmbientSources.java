package com.stonebreak.fx.bindings;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.rendering.lighting.DynamicLights;
import org.joml.Vector3f;

/**
 * {@link AmbientSources} over this frame's {@link DynamicLights} selection: the nearest emitting torches and lit
 * furnaces plus torches other players carry. Read after {@code DynamicLights.update}.
 */
public final class LightAmbientSources implements AmbientSources {

    public static final LightAmbientSources INSTANCE = new LightAmbientSources();

    private LightAmbientSources() {
    }

    @Override
    public int count() {
        return DynamicLights.count();
    }

    @Override
    public BlockType block(int i) {
        return DynamicLights.sourceBlock(i);
    }

    @Override
    public long key(int i) {
        return DynamicLights.sourceKey(i);
    }

    @Override
    public Vector3f position(int i, Vector3f out) {
        return DynamicLights.position(i, out);
    }
}
