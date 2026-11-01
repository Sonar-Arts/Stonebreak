package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.ui.graph.SourceMap;
import com.openmason.engine.ui.data.Subscription;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.binding.UiConverter;
import com.openmason.engine.ui.runtime.input.EventCallbacks;
import com.openmason.engine.ui.runtime.input.UiEventHandler;
import com.openmason.engine.ui.runtime.input.UiEventType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One Lua environment inside a screen's state (#292): the screen's code-behind, or the module of
 * one component instance, plus the compiled behavior graphs of that document (#291), which run
 * in the same environment. Instance state is isolated per environment; lifecycle, handlers,
 * watches and converters are tracked here so they can be released one context at a time.
 */
final class ScriptContext {

    /** One compiled graph of this context's document; {@code index} is its slot in Lua (1-based). */
    static final class GraphModule {
        final String id;
        final String chunk;
        final int index;
        String lua;
        SourceMap map;
        boolean loaded;
        boolean hasOpen;
        boolean hasUpdate;
        boolean hasClose;

        GraphModule(String id, String chunk, int index) {
            this.id = id;
            this.chunk = chunk;
            this.index = index;
        }
    }

    /** A UI event handler registered by {@code el:on}. */
    record Handler(int id, String key, UiEventType type, EventCallbacks.Phase phase, UiElement element,
                   UiEventHandler java) {
    }

    /** A component signal listener ({@code instance:on("pressed", fn)}). */
    record SignalListener(int id, String instanceKey, String signal) {
    }

    final int id;
    /** Element key of the scope root: "" for the screen's root element, else the Instance element. */
    final String scopeKey;
    final String rootKey;
    /** The document this code-behind belongs to (the screen, or the component archive). */
    OmuiArchive archive;
    /** The code-behind module ref, or null for a document with graphs only. */
    final String moduleRef;
    final String chunk;
    final List<GraphModule> graphs = new ArrayList<>();

    int envRef;
    /** The Lua side of the context exists (the factory ran): graphs can load even if the code-behind failed. */
    boolean created;
    /** The code-behind module (or its absence) loaded. */
    boolean loaded;
    boolean closed;
    boolean hasOpen;
    boolean hasUpdate;
    boolean hasInput;
    boolean hasClose;
    /** {@code update(dt)} of the code-behind itself (graphs track their own). */
    boolean scriptUpdate;
    String source;

    final Map<Integer, Handler> handlers = new HashMap<>();
    final Map<Integer, SignalListener> signals = new HashMap<>();
    final Map<Integer, Subscription> watches = new HashMap<>();
    final Map<String, UiConverter> converters = new LinkedHashMap<>();
    final java.util.Set<String> disabledConverters = new java.util.HashSet<>();
    final Map<String, ScriptCanvas> canvases = new LinkedHashMap<>();
    /**
     * Awaitable handles this context started and that have not settled (actions, timers,
     * animations, state-machine moves): the per-context cap counts these, and only their owner
     * may cancel them.
     */
    final java.util.Set<Long> live = new java.util.HashSet<>();
    /** Animation tokens this context started (bounded, oldest forgotten): stop/seek/speed ownership. */
    final java.util.Set<Long> anims = java.util.Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > UiScriptRuntime.MAX_HANDLES;
        }
    });
    UiEventHandler inputHook;
    UiElement inputHookElement;

    ScriptContext(int id, String scopeKey, String rootKey, OmuiArchive archive, String moduleRef, String chunk) {
        this.id = id;
        this.scopeKey = scopeKey;
        this.rootKey = rootKey;
        this.archive = archive;
        this.moduleRef = moduleRef;
        this.chunk = chunk;
    }

    /** True for the screen's own code-behind (sees the whole tree). */
    boolean isScreen() {
        return scopeKey.isEmpty();
    }

    /** Keys this context may touch: everything for the screen, its own subtree for a component. */
    boolean inScope(String key) {
        return isScreen() || key.equals(scopeKey) || key.startsWith(scopeKey + "/");
    }

    /** Key of a node id path relative to this scope ({@code ui.get}). */
    String keyOf(String path) {
        return isScreen() ? path : path.isEmpty() ? scopeKey : scopeKey + "/" + path;
    }

    String documentId() {
        return archive.manifest().documentId();
    }

    GraphModule graph(String chunkName) {
        for (GraphModule g : graphs) {
            if (g.chunk.equals(chunkName)) {
                return g;
            }
        }
        return null;
    }

    /** The code-behind chunk, else the first graph's: how the console and diagnostics name this context. */
    String label() {
        if (chunk != null) {
            return chunk;
        }
        return graphs.isEmpty() ? documentId() : graphs.getFirst().chunk;
    }

    @Override
    public String toString() {
        return isScreen() ? label() : label() + "@" + scopeKey;
    }
}
