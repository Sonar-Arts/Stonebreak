package com.openmason.engine.ui.script;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.cenda.LuaFloatBuffer;
import com.openmason.engine.cenda.LuaState;
import com.openmason.engine.cenda.LuaValueReader;
import com.openmason.engine.cenda.LuaValueWriter;
import com.openmason.engine.cenda.LuaWatchdog;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionCall;
import com.openmason.engine.ui.data.DataState;
import com.openmason.engine.ui.data.Subscription;
import com.openmason.engine.ui.data.UiScope;
import com.openmason.engine.ui.graph.CompiledGraph;
import com.openmason.engine.ui.graph.DocumentEnvironment;
import com.openmason.engine.ui.graph.GraphCompiler;
import com.openmason.engine.ui.graph.GraphDiagnostic;
import com.openmason.engine.ui.graph.GraphEnvironment;
import com.openmason.engine.ui.graph.GraphInputs;
import com.openmason.engine.ui.graph.SourceMap;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.runtime.anim.UiAnimator;
import com.openmason.engine.ui.runtime.binding.UiConverter;
import com.openmason.engine.ui.runtime.binding.UiConverters;
import com.openmason.engine.ui.runtime.input.EventCallbacks;
import com.openmason.engine.ui.runtime.input.UiEvent;
import com.openmason.engine.ui.runtime.input.UiEventHandler;
import com.openmason.engine.ui.runtime.input.UiEventType;
import com.openmason.engine.ui.runtime.input.UiInputRouter;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.engine.ui.script.UiScriptDiagnostic.Code;
import com.openmason.engine.ui.script.UiScriptDiagnostic.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Lua code-behind of one open screen instance (#292).
 *
 * <p><b>One state per screen, one environment per script.</b> A screen gets its own sandboxed
 * {@link LuaState} (memory cap, watchdog deadline, hard teardown on close). Its code-behind and
 * the module of every component instance inside it run in separate environments of that state,
 * so two instances of one component never share script state. A document without any
 * code-behind creates no state at all ({@link #isScripted()} is false).
 *
 * <p><b>Lifecycle</b> (hosts use {@link UiScripts}, which does this in order):
 * {@link #load} runs the modules' top level (they declare converters there), the binder opens
 * with {@link #converters()}, {@link #attach} hands over the scope and input router,
 * {@link #open} runs {@code on_open}, {@link #update} runs once per frame, {@link #reload}
 * hot-swaps modules after a document or script change, and {@link #close} runs {@code on_close}
 * and releases everything.
 *
 * <p><b>Execution model.</b> Everything runs synchronously on the UI thread at defined points:
 * input dispatch (element handlers, {@code on_input}), {@link #update} (completed actions, data
 * watches, animation completions and timers are delivered, then {@code update(dt)}) and binding
 * evaluation (converters). Event handlers, {@code on_open} and callbacks run as tasks that may
 * {@code ui.await}; a reload, a world change or closing the screen cancels them, and their late
 * results are dropped.
 *
 * <p><b>Failures</b> never escape: an error, deadline, memory or budget violation rolls back the
 * dispatch's local writes (last good presentation), disables the failing handler, hook, watch or
 * converter, and is reported with its {@code chunk:line} ({@link #diagnostics()}, the
 * {@link #console()}, and the instance's diagnostics as {@code SCRIPT_ERROR}).
 */
public final class UiScriptRuntime implements UiDocumentView.Extension {

    private static final Logger LOGGER = LoggerFactory.getLogger(UiScriptRuntime.class);

    // Dispatcher ops; mirrored in prelude.lua.
    static final int OP_LOAD = 1;
    static final int OP_OPEN = 2;
    static final int OP_CLOSE = 3;
    static final int OP_UPDATE = 4;
    static final int OP_INPUT = 5;
    static final int OP_EVENT = 6;
    static final int OP_SETTLE = 7;
    static final int OP_CANCEL_ALL = 8;
    static final int OP_CONVERT = 9;
    static final int OP_WATCH = 10;
    static final int OP_ANIM_EVENT = 11;
    static final int OP_DROP_HANDLER = 12;
    static final int OP_SIGNAL = 13;
    static final int OP_RELOAD = 14;
    static final int OP_GRAPH_LOAD = 15;
    static final int OP_GRAPH_HOOK = 16;

    private static final String PRELUDE = prelude();
    private static final Object WATCHDOG_LOCK = new Object();
    private static LuaWatchdog watchdog;

    private record Settlement(ScriptContext ctx, long token, String status, UiValue value, long generation) {
    }

    private record AnimEvent(ScriptContext ctx, long token, String name, long generation) {
    }

    private record Timer(ScriptContext ctx, long token, double due) {
    }

    private record WatchKey(ScriptContext ctx, int id) {
    }

    private record Pending(ScriptContext ctx, ActionCall call) {
    }

    final UiDocumentInstance ui;
    final UiScriptOptions options;
    final UiScriptServices services;
    final UiAnimator animator;
    final ScriptJournal journal;
    /** Trace hits, watches and breakpoints of debug-compiled graphs; null in release. */
    final GraphDebugger debugger;
    private final UiScriptConsole console = new UiScriptConsole();
    private final List<UiScriptDiagnostic> diagnostics = new ArrayList<>();
    private final List<ScriptContext> contexts = new ArrayList<>();
    private final List<ScriptCanvas> canvases = new ArrayList<>();
    private final ScriptOps ops;
    private LuaState lua;
    private int dispatchRef;
    private int factoryRef;
    private int nextContext = 1;
    private int nextCanvas = 1;

    UiScope scope;
    UiInputRouter router;
    /** Converter (pure) calls in progress: mutating ops are refused. */
    int pure;
    long generation;
    private long nextToken = 1;
    private double time;
    private boolean opened;
    private boolean closed;

    private final Map<Long, Pending> pending = new HashMap<>();
    private final List<Timer> timers = new ArrayList<>();
    private final ArrayDeque<Settlement> settlements = new ArrayDeque<>();
    private final ArrayDeque<AnimEvent> animEvents = new ArrayDeque<>();
    private final Map<WatchKey, DataState> watchQueue = new LinkedHashMap<>();
    /** Instance key → signal listeners registered on that component instance. */
    final Map<String, List<ScriptContext.SignalListener>> signalListeners = new HashMap<>();
    private final Map<ScriptContext.SignalListener, ScriptContext> signalOwners = new HashMap<>();

    private long calls;
    private long callNanos;
    private long lastUpdateNanos;
    private String lastFailure = "";

    private UiScriptRuntime(UiDocumentInstance ui, UiScriptOptions options, UiScriptServices services) {
        this.ui = ui;
        this.options = options == null ? UiScriptOptions.DEFAULTS : options;
        this.services = services == null ? UiScriptServices.NONE : services;
        this.animator = new UiAnimator(ui);
        this.journal = new ScriptJournal(ui);
        this.ops = new ScriptOps(this);
        this.debugger = this.options.graphDebug() ? new GraphDebugger(this) : null;
    }

    /**
     * Loads every code-behind module of {@code ui} (the screen's and its component instances')
     * and every behavior graph (#291), compiled to Lua, and runs their top level. Problems in a
     * module or graph are diagnostics; the screen still opens.
     *
     * @throws com.openmason.engine.cenda.CendaLuaUnavailableException when the document has
     *         code-behind but the Lua host is missing or has another ABI: never silently skipped
     */
    public static UiScriptRuntime load(UiDocumentInstance ui, UiScriptOptions options, UiScriptServices services) {
        UiScriptRuntime rt = new UiScriptRuntime(Objects.requireNonNull(ui, "ui"), options, services);
        List<ScriptContext> found = rt.discover();
        if (found.isEmpty()) {
            return rt;
        }
        int api = ui.document().manifest().uiApi();
        if (api > OmuiFormat.UI_API_VERSION) {
            rt.report(Severity.ERROR, Code.API_VERSION, null, "", "document targets ui API " + api
                + "; this host implements " + OmuiFormat.UI_API_VERSION + ": code-behind not run");
            return rt;
        }
        try {
            CendaLua.require();
        } catch (RuntimeException e) {
            rt.report(Severity.ERROR, Code.UNAVAILABLE, null, "", e.getMessage());
            throw e;
        }
        rt.start(found);
        return rt;
    }

    // ── public surface ──────────────────────────────────────────────────────

    /** True when the document has code-behind and a Lua state runs it. */
    public boolean isScripted() {
        return lua != null;
    }

    public UiDocumentInstance instance() {
        return ui;
    }

    /** Converters the modules declared ({@code ui.converter}), for {@code UiBinder.open}. */
    public UiConverters converters() {
        return name -> {
            for (ScriptContext ctx : contexts) {
                UiConverter c = ctx.converters.get(name);
                if (c != null) {
                    return c;
                }
            }
            return null;
        };
    }

    /**
     * Hands over the document's host scope (data reads and watches, actions) and its input
     * router (focus). Either may be null for a static or headless document.
     */
    public void attach(UiScope scope, UiInputRouter router) {
        this.scope = scope;
        this.router = router;
    }

    /** Runs {@code on_open} of every component module, then the screen's. */
    public void open() {
        if (lua == null || opened || closed) {
            return;
        }
        opened = true;
        for (int i = contexts.size() - 1; i >= 0; i--) {
            openContext(contexts.get(i));
        }
    }

    /**
     * One frame: animations advance, completed actions/timers/animations and data changes are
     * delivered to their tasks and callbacks, then every {@code update(dt)} runs. With nothing
     * pending and number-only updates this allocates no Java memory.
     */
    public void update(double dt) {
        if (closed) {
            return;
        }
        time += dt;
        animator.tick(dt);
        if (lua == null) {
            return;
        }
        long t0 = System.nanoTime();
        tickTimers();
        deliverQueued();
        for (int i = 0; i < contexts.size(); i++) {
            ScriptContext ctx = contexts.get(i);
            if (ctx.hasUpdate && !ctx.closed) {
                lua.args().integer(ctx.id).integer(OP_UPDATE).number(dt);
                dispatch(ctx, "update", true);
            }
        }
        for (int i = 0; i < canvases.size(); i++) {
            ui.canvasChanged(canvases.get(i).key);
        }
        lastUpdateNanos = System.nanoTime() - t0;
    }

    @Override
    public void frame(double dt) {
        update(dt);
    }

    /**
     * Hot reload: re-resolves every module and swaps it in atomically per context. Call after
     * {@code instance.reload(...)} or when a script changed. A module that no longer compiles
     * keeps the previous version running. Environments (and so module globals) survive; element
     * state survives by key through the instance reload. Pending tasks are cancelled.
     */
    public void reload() {
        if (closed) {
            return;
        }
        generation++;
        cancelPending();
        animator.clear();
        if (lua != null) {
            for (ScriptContext ctx : contexts) {
                releaseBindings(ctx);
                lua.args().integer(ctx.id).integer(OP_CANCEL_ALL);
                dispatch(ctx, "reload", false);
            }
        }
        List<ScriptContext> found = discover();
        if (lua == null) {
            if (!found.isEmpty()) {
                CendaLua.require();
                start(found);
                if (opened) {
                    opened = false;
                    open();
                }
            }
            return;
        }
        Map<String, ScriptContext> old = new LinkedHashMap<>();
        for (ScriptContext ctx : contexts) {
            old.put(ctx.scopeKey + "\u0000" + ctx.moduleRef, ctx);
        }
        List<ScriptContext> next = new ArrayList<>();
        for (ScriptContext f : found) {
            ScriptContext kept = old.remove(f.scopeKey + "\u0000" + f.moduleRef);
            if (kept != null) {
                next.add(kept);
                kept.archive = f.archive;
                if (kept.envRef <= 0) {
                    createContext(kept);
                } else {
                    reloadContext(kept, f.archive);
                }
            } else {
                next.add(f);
            }
        }
        for (ScriptContext gone : old.values()) {
            closeContext(gone);
        }
        contexts.clear();
        contexts.addAll(next);
        for (ScriptContext ctx : next) {
            if (!ctx.created && ctx.envRef == 0) {
                createContext(ctx);
                if (opened) {
                    openContext(ctx);
                }
            }
        }
    }

    /** Runs {@code on_close} (screen first), cancels every task and frees the state. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (lua != null) {
            for (ScriptContext ctx : contexts) {
                if (ctx.created && !ctx.closed) {
                    closeHooks(ctx);
                }
            }
        }
        closed = true;
        cancelPending();
        animator.clear();
        for (ScriptContext ctx : contexts) {
            releaseBindings(ctx);
            ctx.closed = true;
        }
        for (ScriptCanvas c : canvases) {
            ui.detachCanvas(c.key);
        }
        canvases.clear();
        if (lua != null) {
            synchronized (WATCHDOG_LOCK) {
                if (watchdog != null) {
                    watchdog.unwatch(lua);
                }
            }
            lua.close();
            lua = null;
        }
    }

    public boolean isClosed() {
        return closed;
    }

    /** Problems so far, oldest first. */
    public List<UiScriptDiagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public UiScriptConsole console() {
        return console;
    }

    public UiAnimator animator() {
        return animator;
    }

    /** The graph debugger when the runtime was opened with {@code graphDebug}, else null. */
    public GraphDebugger graphDebugger() {
        return debugger;
    }

    /** Generated Lua of each loaded graph, keyed {@code graphId} or {@code graphId@instanceKey}. */
    public Map<String, String> graphChunks() {
        Map<String, String> out = new LinkedHashMap<>();
        for (ScriptContext ctx : contexts) {
            for (ScriptContext.GraphModule g : ctx.graphs) {
                if (g.loaded) {
                    out.put(ctx.isScreen() ? g.id : g.id + "@" + ctx.scopeKey, g.lua);
                }
            }
        }
        return out;
    }

    /** Seconds of UI time since load. */
    public double time() {
        return time;
    }

    /** Modules and graphs running, as {@code chunk} or {@code chunk@instanceKey}. */
    public List<String> modules() {
        List<String> out = new ArrayList<>();
        for (ScriptContext c : contexts) {
            String at = c.isScreen() ? "" : "@" + c.scopeKey;
            if (c.loaded && c.chunk != null) {
                out.add(c.chunk + at);
            }
            for (ScriptContext.GraphModule g : c.graphs) {
                if (g.loaded) {
                    out.add(g.chunk + at);
                }
            }
        }
        return out;
    }

    /** Lua heap in use, or 0 without a state. */
    public long memoryUsed() {
        return lua == null ? 0 : lua.memUsed();
    }

    public long memoryPeak() {
        return lua == null ? 0 : lua.memPeak();
    }

    /** Dispatches into Lua so far and their total wall time. */
    public long calls() {
        return calls;
    }

    public long callNanos() {
        return callNanos;
    }

    /** Wall time of the last {@link #update} (deliveries + every {@code update(dt)}). */
    public long lastUpdateNanos() {
        return lastUpdateNanos;
    }

    /** Pending awaited operations (actions, timers, animations) — 0 after close. */
    public int pendingCount() {
        return pending.size() + timers.size() + settlements.size();
    }

    // ── setup ───────────────────────────────────────────────────────────────

    /** A context per document instance with code-behind or graphs: the screen, then component instances. */
    private List<ScriptContext> discover() {
        List<ScriptContext> out = new ArrayList<>();
        OmuiArchive doc = ui.document();
        if (behaves(doc)) {
            out.add(context("", ui.root().key(), doc));
        }
        for (UiElement el : ui.elements()) {
            if (!UiNode.INSTANCE_TYPE.equals(el.type()) || el.node().instance() == null) {
                continue;
            }
            OmuiArchive comp = ui.context().source().component(el.node().instance().component());
            if (comp != null && behaves(comp)) {
                out.add(context(el.key(), el.key(), comp));
            }
        }
        return out;
    }

    private static boolean behaves(OmuiArchive doc) {
        return doc.document().codeBehind() != null || !doc.graphs().isEmpty();
    }

    private ScriptContext context(String scopeKey, String rootKey, OmuiArchive doc) {
        String ref = doc.document().codeBehind();
        return new ScriptContext(nextContext++, scopeKey, rootKey, doc, ref, ref == null ? null : chunkName(ref));
    }

    private static String chunkName(String ref) {
        return ref.indexOf(':') >= 0 ? ref : ref + ".lua";
    }

    private void start(List<ScriptContext> found) {
        lua = CendaLua.newState(options.memoryLimitBytes());
        try {
            lua.setBudget(options.instructionBudget());
            if (lua.run(PRELUDE, "prelude.lua", 0) != LuaState.OK) {
                throw new IllegalStateException("ui prelude failed: " + lua.lastError());
            }
            factoryRef = lua.refFunction(0, "__cenda_ui_factory");
            dispatchRef = lua.refFunction(0, "__cenda_ui_dispatch");
            lua.run("__cenda_ui_factory = nil __cenda_ui_dispatch = nil", "prelude", 0);
            if (factoryRef <= 0 || dispatchRef <= 0) {
                throw new IllegalStateException("ui prelude did not define its entry points");
            }
        } catch (RuntimeException e) {
            lua.close();
            lua = null;
            throw e;
        }
        synchronized (WATCHDOG_LOCK) {
            if (watchdog == null) {
                watchdog = new LuaWatchdog(1000);
            }
            watchdog.watch(lua, options.deadlineMillis());
        }
        for (ScriptContext ctx : found) {
            contexts.add(ctx);
            createContext(ctx);
        }
    }

    private void createContext(ScriptContext ctx) {
        ctx.envRef = lua.newEnv();
        if (ctx.envRef <= 0) {
            report(Severity.ERROR, Code.MEMORY, ctx, "", "cannot create a script environment: " + lua.lastError());
            return;
        }
        lua.registerValues(ctx.envRef, "__h", (in, out) -> ops.invoke(ctx, in, out));
        lua.args().integer(ctx.id).ref(ctx.envRef).value(new UiValue.Obj(Map.of(
            "document", UiValue.of(ctx.documentId()),
            "key", UiValue.of(ctx.scopeKey),
            "root", UiValue.of(ctx.rootKey))));
        if (lua.callValues(factoryRef, 0) != LuaState.OK) {
            report(Severity.ERROR, classify(LuaState.ERR_RUN, lua.lastError(), "load"), ctx, "",
                "script context setup failed: " + lua.lastError());
            return;
        }
        ctx.created = true;
        String[] module = null;
        if (ctx.moduleRef != null) {
            try {
                module = ops.resolveModule(ctx, ctx.moduleRef);
            } catch (ScriptOps.ModuleException e) {
                report(Severity.ERROR, e.code, ctx, "", e.getMessage());
            }
        }
        if (ctx.moduleRef == null || module != null) {
            ctx.source = module == null ? null : module[0];
            LuaValueWriter w = lua.args().integer(ctx.id).integer(OP_LOAD);
            if (module == null) {
                w.nil().nil();
            } else {
                w.string(module[0]).string(module[1]);
            }
            writeCanvases(w, bindCanvases(ctx));
            LuaValueReader r = dispatch(ctx, "load", false);
            if (r != null) {
                ctx.loaded = true;
                hooks(ctx, r.value());
            }
        }
        loadGraphs(ctx, List.of());
    }

    // ── behavior graphs (#291) ──────────────────────────────────────────────

    /**
     * Compiles the context's graphs (or takes a still-valid {@code derived/} cache) and loads
     * each into the context's environment after the code-behind, so a graph sees its module as
     * {@code script} and shares its handlers, tasks and lifetime. A graph that fails its checks
     * is reported per node and not loaded; on reload the previous version keeps running.
     */
    private void loadGraphs(ScriptContext ctx, List<ScriptContext.GraphModule> previous) {
        List<ScriptContext.GraphModule> fresh = compileGraphs(ctx, previous);
        if (!ctx.created) {
            return;
        }
        if (!previous.isEmpty() || !ctx.graphs.isEmpty()) {
            lua.args().integer(ctx.id).integer(OP_GRAPH_LOAD).integer(0).nil();
            dispatch(ctx, "graphs", false);
        }
        ctx.graphs.clear();
        ctx.graphs.addAll(fresh);
        for (ScriptContext.GraphModule g : fresh) {
            if (g.lua == null) {
                continue;
            }
            lua.args().integer(ctx.id).integer(OP_GRAPH_LOAD).integer(g.index).value(new UiValue.Obj(Map.of(
                "id", UiValue.of(g.id), "src", UiValue.of(g.lua), "chunk", UiValue.of(g.chunk),
                "debug", UiValue.of(options.graphDebug()))));
            LuaValueReader r = dispatch(ctx, "graph-load:" + g.index, false);
            if (r != null && r.value() instanceof UiValue.Obj o) {
                g.loaded = true;
                g.hasOpen = o.get("on_open") == UiValue.TRUE;
                g.hasUpdate = o.get("update") == UiValue.TRUE;
                g.hasClose = o.get("on_close") == UiValue.TRUE;
            }
        }
        updateFlags(ctx);
    }

    private List<ScriptContext.GraphModule> compileGraphs(ScriptContext ctx, List<ScriptContext.GraphModule> previous) {
        List<ScriptContext.GraphModule> out = new ArrayList<>();
        if (ctx.archive.graphs().isEmpty()) {
            return out;
        }
        GraphEnvironment env = DocumentEnvironment.of(ctx.archive, ui.context().source());
        int index = 1;
        for (UiGraph graph : ctx.archive.graphs().values()) {
            ScriptContext.GraphModule m = new ScriptContext.GraphModule(graph.id(), CompiledGraph.chunkName(graph.id()),
                index++);
            String code = cachedGraph(ctx, graph);
            if (code == null) {
                CompiledGraph c = GraphCompiler.compile(graph, env, options.graphDebug());
                for (GraphDiagnostic d : c.diagnostics()) {
                    if (d.severity() != GraphDiagnostic.Severity.INFO) {
                        reportGraph(ctx, m.chunk, d);
                    }
                }
                code = c.lua();
                if (code == null) {
                    ScriptContext.GraphModule old = previous.stream().filter(p -> p.id.equals(graph.id()) && p.lua != null)
                        .findFirst().orElse(null);
                    if (old != null) {
                        code = old.lua;
                        report(Severity.WARNING, Code.GRAPH_INVALID, ctx, "", "graph " + graph.id()
                            + " has errors; the previous version keeps running");
                    }
                }
            }
            m.lua = code;
            m.map = code == null ? null : SourceMap.parse(graph.id(), code);
            out.add(m);
        }
        return out;
    }

    /** A {@code derived/} chunk still keyed to this graph and compiler, or null (then the graph compiles). */
    private String cachedGraph(ScriptContext ctx, UiGraph graph) {
        if (options.graphDebug()) {
            return null;
        }
        UiDocumentSource.DerivedLua d = ui.context().source().derivedGraph(ctx.documentId(), graph.id());
        if (d == null) {
            return null;
        }
        boolean keys = GraphCompiler.COMPILER.equals(d.compiler()) && GraphCompiler.VERSION.equals(d.compilerVersion())
            && GraphCompiler.sourceSha256(graph).equals(d.sourceSha256());
        // The chunk also records the Lua signatures and signal contracts it was built against.
        String inputs = GraphInputs.sha256(graph, DocumentEnvironment.of(ctx.archive, ui.context().source()));
        if (keys && inputs.equals(GraphInputs.recorded(d.lua()))) {
            return d.lua();
        }
        report(Severity.INFO, Code.GRAPH_INVALID, ctx, "", "derived Lua of graph " + graph.id() + " was built by "
            + d.compiler() + " " + d.compilerVersion() + (keys ? " against other Lua signatures or signal contracts"
            : " for another source") + "; compiled it again");
        return null;
    }

    private void reportGraph(ScriptContext ctx, String chunk, GraphDiagnostic d) {
        Severity sev = d.isError() ? Severity.ERROR : Severity.WARNING;
        UiScriptDiagnostic diag = new UiScriptDiagnostic(sev, Code.GRAPH_INVALID, chunk, 0, "",
            d.code() + ": " + d.message(), d.location());
        record(ctx, diag, "");
    }

    private void updateFlags(ScriptContext ctx) {
        ctx.hasUpdate = ctx.scriptUpdate || ctx.graphs.stream().anyMatch(g -> g.loaded && g.hasUpdate);
    }

    private void graphHook(ScriptContext ctx, ScriptContext.GraphModule g, String hook) {
        lua.args().integer(ctx.id).integer(OP_GRAPH_HOOK).integer(g.index).string(hook);
        dispatch(ctx, ("on_open".equals(hook) ? "graph-open:" : "graph-close:") + g.index, "on_open".equals(hook));
    }

    /** Resumes a task paused at a graph breakpoint, at the next frame. */
    void settleDebug(ScriptContext ctx, long token) {
        if (!closed && !ctx.closed) {
            settlements.add(new Settlement(ctx, token, "ok", UiValue.NULL, generation));
        }
    }

    /** Binds a native draw buffer for every Canvas this context draws and not yet bound. */
    private List<ScriptCanvas> bindCanvases(ScriptContext ctx) {
        List<ScriptCanvas> fresh = new ArrayList<>();
        for (UiElement el : ui.elements()) {
            if (!"Canvas".equals(el.type()) || !ctx.inScope(el.key()) || owner(el.key()) != ctx
                || ctx.canvases.containsKey(el.key())) {
                continue;
            }
            int capacity = (int) Math.clamp(el.prop("capacity") instanceof UiValue.Num n ? n.value() : 32768, 64,
                1 << 22);
            String name = "__cv" + nextCanvas++;
            LuaFloatBuffer buf = lua.bindBuffer(ctx.envRef, name, capacity);
            ScriptCanvas c = new ScriptCanvas(el.key(), name, buf);
            ctx.canvases.put(el.key(), c);
            canvases.add(c);
            ui.attachCanvas(el.key(), c);
            fresh.add(c);
        }
        return fresh;
    }

    private static void writeCanvases(LuaValueWriter w, List<ScriptCanvas> fresh) {
        w.map(fresh.size());
        for (ScriptCanvas c : fresh) {
            w.string(c.key).string(c.luaName);
        }
        w.end();
    }

    /** The innermost context whose scope holds {@code key}. */
    ScriptContext owner(String key) {
        ScriptContext best = null;
        for (ScriptContext ctx : contexts) {
            if (!ctx.closed && ctx.inScope(key) && (best == null || ctx.scopeKey.length() > best.scopeKey.length())) {
                best = ctx;
            }
        }
        return best;
    }

    private void hooks(ScriptContext ctx, UiValue info) {
        if (!(info instanceof UiValue.Obj o)) {
            return;
        }
        ctx.hasOpen = o.get("on_open") == UiValue.TRUE;
        ctx.hasClose = o.get("on_close") == UiValue.TRUE;
        ctx.scriptUpdate = o.get("update") == UiValue.TRUE;
        ctx.hasInput = o.get("on_input") == UiValue.TRUE;
        updateFlags(ctx);
    }

    private void openContext(ScriptContext ctx) {
        if (ctx.closed || !ctx.created) {
            return;
        }
        if (ctx.loaded) {
            installInputHook(ctx);
            if (ctx.hasOpen) {
                lua.args().integer(ctx.id).integer(OP_OPEN);
                dispatch(ctx, "on_open", true);
            }
        }
        for (ScriptContext.GraphModule g : List.copyOf(ctx.graphs)) {
            if (g.loaded && g.hasOpen && !ctx.closed) {
                graphHook(ctx, g, "on_open");
            }
        }
    }

    private void reloadContext(ScriptContext ctx, OmuiArchive archive) {
        List<ScriptContext.GraphModule> previous = List.copyOf(ctx.graphs);
        reloadScript(ctx);
        loadGraphs(ctx, previous);
        if (opened) {
            for (ScriptContext.GraphModule g : List.copyOf(ctx.graphs)) {
                if (g.loaded && g.hasOpen && !ctx.closed) {
                    graphHook(ctx, g, "on_open");
                }
            }
        }
    }

    private void reloadScript(ScriptContext ctx) {
        if (ctx.moduleRef == null) {
            LuaValueWriter w = lua.args().integer(ctx.id).integer(OP_RELOAD).nil().nil();
            writeCanvases(w, bindCanvases(ctx));
            LuaValueReader r = dispatch(ctx, "load", true);
            if (r != null) {
                ctx.loaded = true;
                hooks(ctx, r.value());
            }
            return;
        }
        String[] module;
        try {
            module = ops.resolveModule(ctx, ctx.moduleRef);
        } catch (ScriptOps.ModuleException e) {
            report(Severity.ERROR, e.code, ctx, "", e.getMessage() + " (the previous version keeps running)");
            if (ctx.source == null) {
                return;
            }
            module = new String[]{ctx.source, ctx.chunk};
        }
        LuaValueWriter w = lua.args().integer(ctx.id).integer(OP_RELOAD).string(module[0]).string(module[1]);
        writeCanvases(w, bindCanvases(ctx));
        LuaValueReader r = dispatch(ctx, "load", true);
        if (r == null && module[0] != null && !module[0].equals(ctx.source) && ctx.source != null) {
            // The new version failed: put the last good one back so its handlers re-attach.
            lua.args().integer(ctx.id).integer(OP_RELOAD).string(ctx.source).string(ctx.chunk).map(0).end();
            r = dispatch(ctx, "load", true);
        } else if (r != null) {
            ctx.source = module[0];
        }
        if (r != null) {
            ctx.loaded = true;
            hooks(ctx, r.value());
            installInputHook(ctx);
        }
    }

    private void closeContext(ScriptContext ctx) {
        if (ctx.created && !ctx.closed) {
            closeHooks(ctx);
        }
        if (debugger != null) {
            debugger.cancel(ctx);
        }
        releaseBindings(ctx);
        ctx.closed = true;
        for (ScriptCanvas c : ctx.canvases.values()) {
            ui.detachCanvas(c.key);
            canvases.remove(c);
        }
        if (ctx.envRef > 0) {
            lua.unref(ctx.envRef);
        }
    }

    /**
     * Graphs' {@code on_close}, then OP_CLOSE: the code-behind's {@code on_close} and the
     * cancellation of every task, so their {@code <close>} handlers run while the state lives.
     */
    private void closeHooks(ScriptContext ctx) {
        for (ScriptContext.GraphModule g : ctx.graphs) {
            if (g.loaded && g.hasClose) {
                graphHook(ctx, g, "on_close");
            }
        }
        lua.args().integer(ctx.id).integer(OP_CLOSE);
        dispatch(ctx, "on_close", false);
    }

    /** Detaches everything a context registered on the document and the host. */
    private void releaseBindings(ScriptContext ctx) {
        for (ScriptContext.Handler h : ctx.handlers.values()) {
            h.element().off(h.type(), h.java(), h.phase());
        }
        ctx.handlers.clear();
        for (Subscription s : ctx.watches.values()) {
            s.close();
        }
        ctx.watches.clear();
        for (ScriptContext.SignalListener l : ctx.signals.values()) {
            List<ScriptContext.SignalListener> list = signalListeners.get(l.instanceKey());
            if (list != null) {
                list.remove(l);
            }
            signalOwners.remove(l);
        }
        ctx.signals.clear();
        if (ctx.inputHook != null) {
            for (UiEventType t : ScriptEvents.INPUT) {
                ctx.inputHookElement.off(t, ctx.inputHook, EventCallbacks.Phase.TRICKLE_DOWN);
            }
            ctx.inputHook = null;
            ctx.inputHookElement = null;
        }
        watchQueue.keySet().removeIf(k -> k.ctx == ctx);
    }

    private void installInputHook(ScriptContext ctx) {
        if (!ctx.hasInput || ctx.inputHook != null) {
            return;
        }
        UiElement root = ui.find(ctx.rootKey);
        if (root == null) {
            return;
        }
        UiEventHandler hook = ev -> onInput(ctx, ev);
        for (UiEventType t : ScriptEvents.INPUT) {
            root.on(t, hook, EventCallbacks.Phase.TRICKLE_DOWN);
        }
        ctx.inputHook = hook;
        ctx.inputHookElement = root;
    }

    // ── dispatch ────────────────────────────────────────────────────────────

    /**
     * Calls the prelude dispatcher with the args already written to {@code lua.args()}.
     *
     * @return the results after the leading {@code ok}, or null when the call failed (the
     *         failure is handled: rolled back, reported, the culprit disabled)
     */
    LuaValueReader dispatch(ScriptContext ctx, Object origin, boolean journaled) {
        if (lua == null) {
            return null;
        }
        long t0 = System.nanoTime();
        if (journaled) {
            journal.begin();
        }
        int status = lua.callValues(dispatchRef, 4);
        calls++;
        callNanos += System.nanoTime() - t0;
        if (status != LuaState.OK) {
            String msg = lua.lastError();
            if (journaled) {
                journal.rollback();
            }
            fail(ctx, status, msg, origin);
            return null;
        }
        LuaValueReader r = lua.results();
        if (!r.bool()) {
            String msg = r.string();
            UiValue o = r.value();
            if (journaled) {
                journal.rollback();
            }
            Object culprit = o instanceof UiValue.Num n ? (Object) (int) n.value()
                : o instanceof UiValue.Str s ? s.value() : origin;
            fail(ctx, LuaState.OK, msg == null ? "script error" : msg, culprit);
            return null;
        }
        if (journaled) {
            journal.commit();
        }
        return r;
    }

    private void fail(ScriptContext ctx, int status, String message, Object origin) {
        lastFailure = message;
        String where = origin instanceof String s ? s : "handler";
        Code code = classify(status, message, where);
        String element = "";
        if (origin instanceof Integer id) {
            ScriptContext.Handler h = ctx.handlers.get(id);
            if (h != null) {
                element = h.key();
            }
        }
        report(Severity.ERROR, code, ctx, element, message);
        disable(ctx, origin, code);
        if (code == Code.MEMORY && lua != null) {
            lua.gcCollect();
        }
    }

    private static Code classify(int status, String message, String origin) {
        return switch (status) {
            case LuaState.ERR_DEADLINE -> Code.DEADLINE;
            case LuaState.ERR_MEM -> Code.MEMORY;
            case LuaState.ERR_BUDGET -> Code.BUDGET;
            case LuaState.ERR_SYNTAX -> Code.SYNTAX;
            default -> {
                String m = message == null ? "" : message;
                if (m.contains("not enough memory")) {
                    yield Code.MEMORY;
                }
                if (m.contains("deadline exceeded")) {
                    yield Code.DEADLINE;
                }
                if (m.contains("instruction budget")) {
                    yield Code.BUDGET;
                }
                yield "load".equals(origin) && !m.contains("stack traceback") ? Code.SYNTAX : Code.RUNTIME;
            }
        };
    }

    /** Switches off whatever failed so it cannot fail every frame. */
    private void disable(ScriptContext ctx, Object origin, Code code) {
        if (closed || ctx.closed) {
            return;
        }
        String what = null;
        if (origin instanceof Integer id) {
            ScriptContext.Handler h = ctx.handlers.remove(id);
            if (h != null) {
                h.element().off(h.type(), h.java(), h.phase());
                what = "the " + ScriptEvents.name(h.type()) + " handler on " + h.key();
            }
            ScriptContext.SignalListener l = ctx.signals.remove(id);
            if (l != null) {
                List<ScriptContext.SignalListener> list = signalListeners.get(l.instanceKey());
                if (list != null) {
                    list.remove(l);
                }
                signalOwners.remove(l);
                what = "the '" + l.signal() + "' signal handler on " + l.instanceKey();
            }
            if (what != null) {
                drop(ctx, id, "handler");
            }
        } else if ("update".equals(origin)) {
            ctx.scriptUpdate = false;
            updateFlags(ctx);
            drop(ctx, 0, "update");
            what = "update(dt)";
        } else if (origin instanceof String s && s.startsWith("graph-update:")) {
            int index = Integer.parseInt(s.substring(13));
            for (ScriptContext.GraphModule g : ctx.graphs) {
                if (g.index == index) {
                    g.hasUpdate = false;
                    what = "the update event of graph " + g.id;
                }
            }
            updateFlags(ctx);
            drop(ctx, index, "graph-update");
        } else if (origin instanceof String s && s.startsWith("custom:")) {
            int id = Integer.parseInt(s.substring(7));
            drop(ctx, id, "handler");
            what = "a custom event handler";
        } else if ("on_input".equals(origin)) {
            ctx.hasInput = false;
            if (ctx.inputHook != null) {
                for (UiEventType t : ScriptEvents.INPUT) {
                    ctx.inputHookElement.off(t, ctx.inputHook, EventCallbacks.Phase.TRICKLE_DOWN);
                }
                ctx.inputHook = null;
            }
            what = "on_input";
        } else if (origin instanceof String s && s.startsWith("watch:")) {
            int id = Integer.parseInt(s.substring(6));
            Subscription sub = ctx.watches.remove(id);
            if (sub != null) {
                sub.close();
            }
            drop(ctx, id, "watch");
            what = "the watch callback";
        } else if (origin instanceof String s && s.startsWith("converter:")) {
            String name = s.substring(10);
            ScriptOps.disableConverter(ctx, name);
            what = "converter " + name;
        }
        if (what != null) {
            report(Severity.WARNING, Code.HANDLER_DISABLED, ctx, "", what + " was disabled after a " + code
                + " error; the screen keeps its last good state");
        }
    }

    private void drop(ScriptContext ctx, int id, String what) {
        if (lua != null) {
            lua.args().integer(ctx.id).integer(OP_DROP_HANDLER).integer(id).string(what);
            lua.callValues(dispatchRef, 1);
        }
    }

    String lastFailure() {
        return lastFailure;
    }

    // ── events and hooks ────────────────────────────────────────────────────

    void onEvent(ScriptContext ctx, int handlerId, UiEvent ev) {
        if (closed || ctx.closed || lua == null) {
            return;
        }
        LuaValueWriter w = lua.args().integer(ctx.id).integer(OP_EVENT).integer(handlerId);
        w.value(ScriptEvents.encode(ev, ui.metrics().scale()));
        LuaValueReader r = dispatch(ctx, handlerId, true);
        if (r != null) {
            ScriptEvents.apply(ev, (int) r.number());
        }
    }

    private void onInput(ScriptContext ctx, UiEvent ev) {
        if (closed || ctx.closed || lua == null || !ctx.hasInput) {
            return;
        }
        LuaValueWriter w = lua.args().integer(ctx.id).integer(OP_INPUT);
        w.value(ScriptEvents.encode(ev, ui.metrics().scale()));
        LuaValueReader r = dispatch(ctx, "on_input", true);
        if (r != null && r.bool()) {
            ev.stopPropagation();
            ev.preventDefault();
        }
    }

    /** Delivers a component signal to every listener on {@code instanceKey}, synchronously. */
    void signal(String instanceKey, String signal, UiValue.Obj args) {
        List<ScriptContext.SignalListener> list = signalListeners.get(instanceKey);
        if (list == null) {
            return;
        }
        for (ScriptContext.SignalListener l : List.copyOf(list)) {
            ScriptContext target = signalOwners.get(l);
            if (target == null || target.closed || !l.signal().equals(signal)) {
                continue;
            }
            lua.args().integer(target.id).integer(OP_SIGNAL).integer(l.id()).value(args);
            dispatch(target, l.id(), true);
        }
    }

    void addSignalListener(ScriptContext ctx, ScriptContext.SignalListener l) {
        ctx.signals.put(l.id(), l);
        signalListeners.computeIfAbsent(l.instanceKey(), k -> new ArrayList<>()).add(l);
        signalOwners.put(l, ctx);
    }

    void removeSignalListener(ScriptContext ctx, int id) {
        ScriptContext.SignalListener l = ctx.signals.remove(id);
        if (l != null) {
            List<ScriptContext.SignalListener> list = signalListeners.get(l.instanceKey());
            if (list != null) {
                list.remove(l);
            }
            signalOwners.remove(l);
        }
    }

    /** A converter call: pure, synchronous, from the binder. */
    UiValue convert(ScriptContext ctx, String name, boolean back, UiValue value) {
        if (closed || lua == null || ctx.closed) {
            throw new IllegalStateException("the code-behind of converter " + name + " is closed");
        }
        pure++;
        try {
            lua.args().integer(ctx.id).integer(OP_CONVERT).string(name).bool(back).value(value);
            LuaValueReader r = dispatch(ctx, "converter:" + name, false);
            if (r == null) {
                throw new IllegalStateException(firstLine(lastFailure));
            }
            return r.value();
        } finally {
            pure--;
        }
    }

    // ── tokens: actions, timers, animations ─────────────────────────────────

    long token() {
        return nextToken++;
    }

    void trackAction(ScriptContext ctx, long token, ActionCall call) {
        long gen = generation;
        pending.put(token, new Pending(ctx, call));
        call.whenSettled(c -> {
            if (pending.remove(token) == null || gen != generation || closed) {
                return; // cancelled with its task, or from before a reload: never delivered as a result
            }
            String status = switch (c.state()) {
                case SUCCEEDED -> "ok";
                case CANCELLED -> "cancelled";
                case REJECTED -> "rejected";
                default -> "failed";
            };
            UiValue value = c.state() == ActionCall.State.SUCCEEDED
                ? (c.result() == null ? UiValue.NULL : c.result())
                : UiValue.of(c.error() == null ? status : String.valueOf(c.error().getMessage()));
            settlements.add(new Settlement(ctx, token, status, value, gen));
        });
    }

    boolean cancelAction(long token) {
        Pending p = pending.get(token);
        if (p != null) {
            p.call.cancel();
            return true;
        }
        return false;
    }

    void addTimer(ScriptContext ctx, long token, double seconds) {
        timers.add(new Timer(ctx, token, time + Math.max(0, seconds)));
    }

    UiAnimator.Listener animListener(ScriptContext ctx) {
        long gen = generation;
        return new UiAnimator.Listener() {
            @Override
            public void finished(long token, boolean stopped) {
                if (!closed && gen == generation) {
                    settlements.add(new Settlement(ctx, token, stopped ? "stopped" : "ok", UiValue.NULL, gen));
                }
            }

            @Override
            public void event(long token, String name) {
                if (!closed && gen == generation) {
                    animEvents.add(new AnimEvent(ctx, token, name, gen));
                }
            }
        };
    }

    void queueWatch(ScriptContext ctx, int id, DataState state) {
        if (!closed && !ctx.closed) {
            watchQueue.put(new WatchKey(ctx, id), state);
        }
    }

    private void tickTimers() {
        for (int i = timers.size() - 1; i >= 0; i--) {
            Timer t = timers.get(i);
            if (time >= t.due) {
                timers.remove(i);
                settlements.add(new Settlement(t.ctx, t.token, "ok", UiValue.NULL, generation));
            }
        }
    }

    private void deliverQueued() {
        // Deliveries may queue more (a task awaiting again, a watch firing an action): a few
        // rounds drain them; anything still queued goes next frame.
        for (int round = 0; round < 8; round++) {
            if (settlements.isEmpty() && animEvents.isEmpty() && watchQueue.isEmpty()) {
                return;
            }
            int n = settlements.size();
            for (int i = 0; i < n; i++) {
                Settlement s = settlements.poll();
                if (s.generation != generation || s.ctx.closed) {
                    continue;
                }
                if ("cancelled".equals(s.status)) {
                    report(Severity.INFO, Code.TASK_CANCELLED, s.ctx, "", "an awaited call was cancelled; its task ends");
                }
                lua.args().integer(s.ctx.id).integer(OP_SETTLE).integer(s.token).string(s.status).value(s.value);
                dispatch(s.ctx, "task", true);
            }
            int m = animEvents.size();
            for (int i = 0; i < m; i++) {
                AnimEvent e = animEvents.poll();
                if (e.generation != generation || e.ctx.closed) {
                    continue;
                }
                lua.args().integer(e.ctx.id).integer(OP_ANIM_EVENT).integer(e.token).string(e.name);
                dispatch(e.ctx, "anim:" + e.token, true);
            }
            if (!watchQueue.isEmpty()) {
                List<Map.Entry<WatchKey, DataState>> batch = new ArrayList<>(watchQueue.entrySet());
                watchQueue.clear();
                for (Map.Entry<WatchKey, DataState> e : batch) {
                    ScriptContext ctx = e.getKey().ctx;
                    if (ctx.closed || !ctx.watches.containsKey(e.getKey().id)) {
                        continue;
                    }
                    LuaValueWriter w = lua.args().integer(ctx.id).integer(OP_WATCH).integer(e.getKey().id);
                    DataState st = e.getValue();
                    w.value(st.valueOrNull()).string(ScriptOps.stateName(st));
                    dispatch(ctx, "watch:" + e.getKey().id, true);
                }
            }
        }
    }

    private void cancelPending() {
        if (debugger != null) {
            debugger.cancel(null);
        }
        for (Pending p : List.copyOf(pending.values())) {
            p.call.cancel();
        }
        pending.clear();
        timers.clear();
        settlements.clear();
        animEvents.clear();
        watchQueue.clear();
    }

    // ── reporting ───────────────────────────────────────────────────────────

    void report(Severity severity, Code code, ScriptContext ctx, String element, String message) {
        String chunk = ctx == null ? "" : ctx.label();
        UiScriptDiagnostic d = UiScriptDiagnostic.fromLua(severity, code, chunk, element, message);
        record(ctx, ctx == null ? d : graphNode(ctx, d), element);
    }

    private static final java.util.regex.Pattern GRAPH_FRAME = java.util.regex.Pattern.compile(
        "([^\\s:'\"]+" + java.util.regex.Pattern.quote(GraphCompiler.CHUNK_SUFFIX) + "):(\\d+)");

    /**
     * Attributes a Lua error to the graph node that emitted the failing line, through the source
     * map: the first compiled-graph frame in the message or its traceback (a Lua function a graph
     * called fails in its own chunk; the graph frame below it names the calling node).
     */
    private UiScriptDiagnostic graphNode(ScriptContext ctx, UiScriptDiagnostic d) {
        if (d.message() == null || ctx.graphs.isEmpty()) {
            return d;
        }
        java.util.regex.Matcher m = GRAPH_FRAME.matcher(d.message());
        while (m.find()) {
            ScriptContext.GraphModule g = ctx.graph(m.group(1));
            if (g != null && g.map != null) {
                SourceMap.Location loc = g.map.at(Integer.parseInt(m.group(2)));
                if (loc != null) {
                    return d.atNode(loc.toString());
                }
            }
        }
        return d;
    }

    private void record(ScriptContext ctx, UiScriptDiagnostic d, String element) {
        Severity severity = d.severity();
        Code code = d.code();
        String message = d.message();
        diagnostics.add(d);
        if (diagnostics.size() > 200) {
            diagnostics.removeFirst();
        }
        UiScriptConsole.Level level = switch (severity) {
            case ERROR -> UiScriptConsole.Level.ERROR;
            case WARNING -> UiScriptConsole.Level.WARN;
            case INFO -> UiScriptConsole.Level.INFO;
        };
        console.add(time, level, ctx == null ? "runtime" : ctx.toString(),
            code + (d.node().isEmpty() ? "" : " at " + d.node()) + ": " + message);
        if (severity == Severity.ERROR) {
            ui.reportDiagnostic(UiRuntimeDiagnostic.error(UiRuntimeDiagnostic.Code.SCRIPT_ERROR, element,
                d.toString()));
            LOGGER.warn("[ui-script] {}", d);
        } else if (severity == Severity.WARNING) {
            ui.reportDiagnostic(UiRuntimeDiagnostic.warning(UiRuntimeDiagnostic.Code.SCRIPT_ERROR, element,
                d.toString()));
            LOGGER.info("[ui-script] {}", d);
        }
    }

    void log(ScriptContext ctx, String level, String message) {
        UiScriptConsole.Level l = "warn".equals(level) ? UiScriptConsole.Level.WARN : UiScriptConsole.Level.INFO;
        console.add(time, l, ctx.toString(), message);
        LOGGER.debug("[ui-script] {}: {}", ctx, message);
    }

    static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static String prelude() {
        try (InputStream in = UiScriptRuntime.class.getResourceAsStream("prelude.lua")) {
            if (in == null) {
                throw new IllegalStateException("ui prelude.lua missing from the engine resources");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the ui prelude", e);
        }
    }

    /** Whether a dependency row is a script (for {@code require} and {@code codeBehind}). */
    static boolean isScript(UiDependency d) {
        return d != null && d.kind() == UiDependency.Kind.SCRIPT;
    }
}
