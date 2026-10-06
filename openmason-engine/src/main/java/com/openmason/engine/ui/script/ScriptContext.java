package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.ui.data.Subscription;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.binding.UiConverter;
import com.openmason.engine.ui.runtime.input.EventCallbacks;
import com.openmason.engine.ui.runtime.input.UiEventHandler;
import com.openmason.engine.ui.runtime.input.UiEventType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One Lua environment inside a screen's state (#292): the screen's code-behind, or the module of
 * one component instance. Instance state is isolated per environment; lifecycle, handlers,
 * watches and converters are tracked here so they can be released one context at a time.
 */
final class ScriptContext {

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
    final String moduleRef;
    final String chunk;

    int envRef;
    boolean loaded;
    boolean closed;
    boolean hasOpen;
    boolean hasUpdate;
    boolean hasInput;
    boolean hasClose;
    String source;

    final Map<Integer, Handler> handlers = new HashMap<>();
    final Map<Integer, SignalListener> signals = new HashMap<>();
    final Map<Integer, Subscription> watches = new HashMap<>();
    final Map<String, UiConverter> converters = new LinkedHashMap<>();
    final java.util.Set<String> disabledConverters = new java.util.HashSet<>();
    final Map<String, ScriptCanvas> canvases = new LinkedHashMap<>();
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

    @Override
    public String toString() {
        return isScreen() ? chunk : chunk + "@" + scopeKey;
    }
}
