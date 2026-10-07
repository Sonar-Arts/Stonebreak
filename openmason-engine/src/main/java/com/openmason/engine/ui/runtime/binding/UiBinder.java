package com.openmason.engine.ui.runtime.binding;

import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionCall;
import com.openmason.engine.ui.data.CallSite;
import com.openmason.engine.ui.data.DataPath;
import com.openmason.engine.ui.data.DataState;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.data.UiProblem;
import com.openmason.engine.ui.data.UiScope;
import com.openmason.engine.ui.runtime.BindingAccess;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic.Code;
import com.openmason.engine.ui.runtime.input.ChangeEvent;
import com.openmason.engine.ui.runtime.input.UiEventHandler;
import com.openmason.engine.ui.runtime.input.UiEventType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Connects a running document to its host (#289): every declarative binding of every element
 * is resolved against the element's inherited data source and kept up to date from
 * notifications of the document's {@link UiScope}.
 *
 * <p><b>Inherited data sources</b> (Unity UI Toolkit): a node's {@code dataSource} applies to
 * its subtree; a relative path ({@code .online}) resolves against the nearest ancestor's source;
 * an absolute one ({@code session.online}) reads the host directly. A component's subtree reads
 * the instance's parameters, which {@code prop:<param>} bindings on the {@code Instance} node
 * keep live; slot content keeps the source of the document that authored it; a list row's
 * source is its item.
 *
 * <p><b>Lifetime.</b> Runtime insertions are bound, removals release what they held, a
 * {@link UiDocumentInstance#reload} renews the scope generation and rebinds everything, and
 * {@link #close()} releases every subscription (and the scope, if this binder opened it).
 */
public final class UiBinder implements AutoCloseable {

    /** What one element holds. */
    private static final class Bound {
        final List<TargetBinding> targets = new ArrayList<>();
        ListBinding list;
        UiEventHandler commitHandler;
        UiEventHandler rowClick;
    }

    private final UiDocumentInstance ui;
    private final UiScope scope;
    private final boolean ownsScope;
    private final UiConverters converters;
    private final BindingAccess access;
    private final Map<UiElement, Bound> bound = new IdentityHashMap<>();
    private final Map<UiElement, Feed> contexts = new IdentityHashMap<>();
    private final Map<UiElement, VarFeed> params = new IdentityHashMap<>();
    private final Map<UiElement, VarFeed> rowItems = new IdentityHashMap<>();
    private final Set<UiElement> spacers = Collections.newSetFromMap(new IdentityHashMap<>());
    private java.util.function.BiConsumer<UiElement, UiElement> recycleListener = (a, b) -> { };
    private boolean closed;

    private UiBinder(UiDocumentInstance ui, UiScope scope, boolean ownsScope, UiConverters converters) {
        this.ui = Objects.requireNonNull(ui, "instance");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.ownsScope = ownsScope;
        this.converters = converters == null ? UiConverters.NONE : converters;
        this.access = ui.claimBindings();
        access.listen(new Listener());
        bindTree(ui.root());
    }

    /**
     * Opens a scope for {@code ui}'s document on {@code host} (its manifest's {@code hostApis}
     * are the capabilities it may call) and binds every element. Scope problems become element
     * diagnostics. Closing the binder closes the scope.
     */
    public static UiBinder open(UiDocumentInstance ui, UiHost host, UiConverters converters) {
        UiManifest m = ui.document().manifest();
        Map<String, Integer> declared = m.hostApis().stream()
            .collect(Collectors.toMap(UiManifest.HostRequirement::id, UiManifest.HostRequirement::version, Math::max));
        UiScope scope = host.openScopeAt(m.documentId(), declared, p -> ui.reportDiagnostic(diagnostic(p)));
        try {
            return new UiBinder(ui, scope, true, converters);
        } catch (RuntimeException e) {
            scope.close();
            throw e;
        }
    }

    /** Binds {@code ui} through an existing scope, which the caller keeps and closes. */
    public static UiBinder attach(UiDocumentInstance ui, UiScope scope, UiConverters converters) {
        return new UiBinder(ui, scope, false, converters);
    }

    public UiDocumentInstance instance() {
        return ui;
    }

    public UiScope scope() {
        return scope;
    }

    UiConverters converters() {
        return converters;
    }

    BindingAccess access() {
        return access;
    }

    void report(UiRuntimeDiagnostic d) {
        ui.reportDiagnostic(d);
    }

    // ── queries ─────────────────────────────────────────────────────────────

    /** Status of the binding of {@code target} on the element with {@code key}, or {@code null}. */
    public BindingStatus status(String key, String target) {
        UiElement el = ui.find(key);
        Bound b = el == null ? null : bound.get(el);
        if (b == null) {
            return null;
        }
        for (TargetBinding t : b.targets) {
            if (t.def.target().equals(target)) {
                return t.status();
            }
        }
        return b.list != null && "prop:items".equals(target) ? b.list.status() : null;
    }

    /** The collection binding of the {@code ListView} with {@code key}, or {@code null}. */
    public ListBinding list(String key) {
        UiElement el = ui.find(key);
        Bound b = el == null ? null : bound.get(el);
        return b == null ? null : b.list;
    }

    /** Elements with live bindings, for inspectors and leak tests. */
    public int boundElementCount() {
        return bound.size();
    }

    /** Listeners the binder holds on component parameters and row items (host watches are the scope's). */
    public int localListenerCount() {
        int n = 0;
        for (VarFeed f : params.values()) {
            n += f.listenerCount();
        }
        for (VarFeed f : rowItems.values()) {
            n += f.listenerCount();
        }
        return n;
    }

    // ── edits ───────────────────────────────────────────────────────────────

    /** Commits every drafted edit of this document (the settings screen's Apply). */
    public List<ActionCall> applyEdits(String elementKey) {
        return scope.edits().applyAll(scope.site(elementKey, CallSite.Origin.BINDING));
    }

    /**
     * Rollback: drops the draft and every edit in progress; bound elements show committed values.
     * A {@code to-source} target has no value of its own to fall back to, so its local edit is
     * dropped and the authored value shows again.
     */
    public void revertEdits() {
        scope.edits().cancel();
        for (Bound b : bound.values()) {
            for (TargetBinding t : b.targets) {
                if (t.mode() == UiNode.BindingMode.TWO_WAY || t.mode() == UiNode.BindingMode.TO_SOURCE) {
                    t.revertLocal();
                }
            }
        }
    }

    /**
     * Re-evaluates virtualized list windows after layout. Cheap when nothing scrolled or
     * resized; call it after {@code update()} like the input router's {@code sync()}.
     */
    public void sync() {
        for (Bound b : List.copyOf(bound.values())) {
            if (b.list != null) {
                b.list.refreshWindow(false);
            }
        }
    }

    // ── binding the tree ────────────────────────────────────────────────────

    private void bindTree(UiElement el) {
        bindElement(el);
        for (UiElement c : List.copyOf(el.children())) {
            if (!rowItems.containsKey(c) && !isSpacer(c)) {
                bindTree(c);
            }
        }
    }

    private void bindElement(UiElement el) {
        List<UiNode.UiBinding> defs = el.node().bindings();
        if (defs.isEmpty()) {
            return;
        }
        Bound b = new Bound();
        bound.put(el, b);
        Feed ctx = contextOf(el);
        for (UiNode.UiBinding def : defs) {
            DataPath path;
            try {
                path = DataPath.parse(def.path());
            } catch (IllegalArgumentException e) {
                report(UiRuntimeDiagnostic.error(Code.UNKNOWN_DATA_SOURCE, el.key(), e.getMessage()));
                continue;
            }
            Feed feed = path.relative() ? ctx.sub(path) : hostFeed(el, path);
            if (ListBinding.isItemsBinding(el, def)) {
                b.list = new ListBinding(this, el, def, feed);
                b.list.start();
                continue;
            }
            TargetBinding t = new TargetBinding(this, el, def, feed, paramSink(el, def));
            b.targets.add(t);
            t.start();
            if (("TextField".equals(el.type()) && "prop:text".equals(def.target()))
                && (def.mode() == UiNode.BindingMode.TWO_WAY || def.mode() == UiNode.BindingMode.TO_SOURCE)) {
                b.commitHandler = e -> {
                    if (e instanceof ChangeEvent ch) {
                        t.writeBack(UiValue.of(ch.value()));
                    }
                };
                el.on(UiEventType.COMMIT, b.commitHandler);
            }
        }
    }

    private Feed hostFeed(UiElement el, DataPath path) {
        if (scope.host().data().root(path.rootName()) == null) {
            report(UiRuntimeDiagnostic.error(Code.UNKNOWN_DATA_SOURCE, el.key(),
                "the host has no data root '" + path.rootName() + "' (" + path + ")"));
        }
        return new HostFeed(scope, path);
    }

    /** A binding of {@code prop:<param>} on an {@code Instance} feeds the component's parameters. */
    private java.util.function.Consumer<UiValue> paramSink(UiElement el, UiNode.UiBinding def) {
        UiValue.Obj declared = el.componentParams();
        if (declared == null || !def.target().startsWith("prop:")) {
            return null;
        }
        String name = def.target().substring(5);
        if (declared.get(name) == null) {
            return v -> { }; // unknown parameter: reported by the builder (UNKNOWN_PARAM)
        }
        VarFeed feed = paramsFeed(el);
        UiValue authored = declared.get(name);
        return v -> {
            UiValue current = feed.current().valueOrNull();
            Map<String, UiValue> next = new LinkedHashMap<>(current instanceof UiValue.Obj o ? o.fields() : Map.of());
            next.put(name, v == null ? authored : v);
            feed.set(DataState.ready(new UiValue.Obj(next)));
        };
    }

    private VarFeed paramsFeed(UiElement instance) {
        return params.computeIfAbsent(instance, i -> new VarFeed(DataState.ready(i.componentParams())));
    }

    /**
     * The data source {@code el} inherits, after its own {@code dataSource}: row item, component
     * parameters, slot content's authoring document, else the parent's.
     */
    private Feed contextOf(UiElement el) {
        Feed cached = contexts.get(el);
        if (cached != null) {
            return cached;
        }
        Feed base;
        UiElement parent = el.parent();
        VarFeed item = rowItems.get(el);
        if (item != null) {
            base = item;
        } else if (parent == null) {
            base = ConstFeed.NONE;
        } else if (parent.componentParams() != null && el.componentDepth() > parent.componentDepth()) {
            base = paramsFeed(parent); // the component root
        } else if (el.componentDepth() < parent.componentDepth()) {
            base = contextOf(authoringAncestor(el)); // slot content
        } else {
            base = contextOf(parent);
        }
        String ds = el.node().dataSource();
        if (ds != null) {
            try {
                DataPath p = DataPath.parse(ds);
                base = p.relative() ? base.sub(p) : hostFeed(el, p);
            } catch (IllegalArgumentException e) {
                report(UiRuntimeDiagnostic.error(Code.UNKNOWN_DATA_SOURCE, el.key(), e.getMessage()));
            }
        }
        contexts.put(el, base);
        return base;
    }

    /** Nearest ancestor authored at {@code el}'s own component depth: the instance whose slot holds it. */
    private static UiElement authoringAncestor(UiElement el) {
        UiElement a = el.parent();
        while (a.parent() != null && a.componentDepth() != el.componentDepth()) {
            a = a.parent();
        }
        return a;
    }

    // ── rows (called by ListBinding) ────────────────────────────────────────

    UiElement buildRow(ListBinding list, UiElement view, int index, UiNode template, String key, VarFeed item) {
        UiElement row = access.insertRow(view, index, template, key);
        rowItems.put(row, item);
        bindTree(row);
        Bound b = bound.computeIfAbsent(row, r -> new Bound());
        b.rowClick = list.clickHandler(row);
        row.on(UiEventType.CLICK, b.rowClick);
        return row;
    }

    UiElement buildSpacer(UiElement view, int index, String key) {
        UiNode spacer = new UiNode("spacer", null, "Box", 1, List.of(), Map.of(),
            Map.of("flex-shrink", UiValue.of(0)), null, List.of(), null, List.of(), Map.of());
        UiElement el = access.insertRow(view, index, spacer, key);
        spacers.add(el);
        return el;
    }

    private boolean isSpacer(UiElement el) {
        return spacers.contains(el);
    }

    /**
     * Called with {@code (row, replacement)} when a virtualized list gives {@code row} another item
     * or drops it while scrolling: {@code replacement} is the row now showing the item {@code row}
     * showed, or {@code null} when that item is no longer realized. A view wires the input
     * router's {@code FocusManager.recycled} here, so focus follows the item.
     */
    public void onRecycled(java.util.function.BiConsumer<UiElement, UiElement> listener) {
        this.recycleListener = Objects.requireNonNull(listener, "listener");
    }

    /** A recycled row starts clean: local (script/edit) values of its subtree belong to its old item. */
    void recycled(UiElement row) {
        clearLocal(row, true);
    }

    private void clearLocal(UiElement el, boolean isRow) {
        for (String target : el.localTargets()) {
            if (isRow && isRowLayout(target)) {
                continue; // the list's own sizing of the row
            }
            access.clearLocal(el, target);
        }
        el.setState(UiElement.INVALID, false);
        for (UiElement c : el.children()) {
            clearLocal(c, false);
        }
    }

    private static boolean isRowLayout(String target) {
        return switch (target) {
            case "style:height", "style:width", "style:flex-shrink" -> true;
            default -> false;
        };
    }

    void recycledAway(UiElement row, UiElement replacement) {
        try {
            recycleListener.accept(row, replacement);
        } catch (RuntimeException e) {
            report(UiRuntimeDiagnostic.warning(Code.ACTION_CALLBACK_FAILED, row.key(), "recycle listener: " + e));
        }
    }

    void dropRow(UiElement row) {
        unbindTree(row);
        if (!row.isRemoved() && !ui.isClosed()) {
            access.removeRow(row);
        }
    }

    // ── unbinding ───────────────────────────────────────────────────────────

    private void unbindTree(UiElement el) {
        for (UiElement c : List.copyOf(el.children())) {
            unbindTree(c);
        }
        Bound b = bound.remove(el);
        if (b != null) {
            for (TargetBinding t : b.targets) {
                t.stop();
            }
            if (b.list != null) {
                b.list.stop();
            }
            if (b.commitHandler != null) {
                el.off(UiEventType.COMMIT, b.commitHandler);
            }
            if (b.rowClick != null) {
                el.off(UiEventType.CLICK, b.rowClick);
            }
        }
        contexts.remove(el);
        params.remove(el);
        rowItems.remove(el);
        spacers.remove(el);
    }

    /** Releases everything without touching elements (they are being replaced or are gone). */
    private void releaseAll() {
        for (Bound b : List.copyOf(bound.values())) {
            b.targets.forEach(TargetBinding::stop);
            if (b.list != null) {
                b.list.stop();
            }
        }
        bound.clear();
        contexts.clear();
        params.clear();
        rowItems.clear();
        spacers.clear();
    }

    private final class Listener implements BindingAccess.Listener {
        @Override
        public void inserted(UiElement subtreeRoot) {
            bindTree(subtreeRoot);
        }

        @Override
        public void removing(UiElement subtreeRoot) {
            unbindTree(subtreeRoot);
        }

        @Override
        public void reloaded(UiDocumentInstance.ReloadReport report) {
            scope.renew(ui.document().manifest().documentId());
            releaseAllQuietly();
            bindTree(ui.root());
        }

        @Override
        public void scrolled(UiElement container) {
            Bound b = bound.get(container);
            if (b != null && b.list != null) {
                b.list.refreshWindow(false);
            }
        }

        @Override
        public void localWrite(UiElement element, String target, UiValue value) {
            Bound b = bound.get(element);
            if (b == null || ("TextField".equals(element.type()) && "prop:text".equals(target))) {
                return; // text fields stage on commit, never per keystroke
            }
            for (TargetBinding t : b.targets) {
                if (t.def.target().equals(target)) {
                    t.writeBack(value);
                }
            }
        }
    }

    private void releaseAllQuietly() {
        try {
            releaseAll();
        } catch (RuntimeException e) {
            report(UiRuntimeDiagnostic.warning(Code.ACTION_CALLBACK_FAILED, "", "releasing bindings: " + e));
        }
    }

    // ── lifetime ────────────────────────────────────────────────────────────

    public boolean isClosed() {
        return closed;
    }

    /** Releases every subscription and the binding layer; closes the scope if this binder opened it. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        releaseAllQuietly();
        access.release();
        if (ownsScope) {
            scope.close();
        }
    }

    // ── problems → diagnostics ──────────────────────────────────────────────

    static UiRuntimeDiagnostic diagnostic(UiProblem p) {
        String key = p.site().elementKey();
        return switch (p.kind()) {
            case ACTION_FAILED -> UiRuntimeDiagnostic.warning(Code.ACTION_FAILED, key, p.message());
            case RESULT_MISMATCH -> UiRuntimeDiagnostic.error(Code.ACTION_RESULT_MISMATCH, key, p.message());
            case CALLBACK_FAILED, CANCEL_HOOK_FAILED ->
                UiRuntimeDiagnostic.error(Code.ACTION_CALLBACK_FAILED, key, p.message());
            case STALE_COMPLETION -> new UiRuntimeDiagnostic(UiRuntimeDiagnostic.Severity.INFO,
                Code.STALE_COMPLETION, key, p.message());
            case INVALID_EDIT -> UiRuntimeDiagnostic.warning(Code.INVALID_EDIT, key, p.message());
        };
    }
}
