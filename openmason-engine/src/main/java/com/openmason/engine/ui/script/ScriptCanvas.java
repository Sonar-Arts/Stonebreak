package com.openmason.engine.ui.script;

import com.openmason.engine.cenda.LuaFloatBuffer;
import com.openmason.engine.ui.runtime.UiCanvasCommands;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One {@code Canvas} element's draw commands: the native float buffer its script emits into,
 * plus the strings and textures those commands reference by index (registered once through
 * {@code canvas:str} / {@code canvas:texture}). The painter reads it in place.
 */
final class ScriptCanvas implements UiCanvasCommands {

    final String key;
    final String luaName;
    private final LuaFloatBuffer buffer;
    private final List<String> strings = new ArrayList<>();
    private final Map<String, Integer> stringIds = new HashMap<>();
    private final List<String> textures = new ArrayList<>();
    private final Map<String, Integer> textureIds = new HashMap<>();

    ScriptCanvas(String key, String luaName, LuaFloatBuffer buffer) {
        this.key = key;
        this.luaName = luaName;
        this.buffer = buffer;
    }

    int string(String s) {
        return stringIds.computeIfAbsent(s, k -> {
            strings.add(k);
            return strings.size() - 1;
        });
    }

    int texture(String ref) {
        return textureIds.computeIfAbsent(ref, k -> {
            textures.add(k);
            return textures.size() - 1;
        });
    }

    @Override
    public int size() {
        return buffer.cursor();
    }

    @Override
    public float get(int index) {
        return buffer.get(index);
    }

    @Override
    public String string(int id) {
        return id >= 0 && id < strings.size() ? strings.get(id) : null;
    }

    @Override
    public String texture(int id) {
        return id >= 0 && id < textures.size() ? textures.get(id) : null;
    }
}
