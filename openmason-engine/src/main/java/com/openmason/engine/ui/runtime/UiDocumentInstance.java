package com.openmason.engine.ui.runtime;

import com.openmason.engine.cenda.CendaFlex;
import com.openmason.engine.cenda.FlexLayoutTree;
import com.openmason.engine.cenda.FlexMeasure;
import com.openmason.engine.cenda.FlexRecord;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiSelectors;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.anim.UiAnimator;
import com.openmason.engine.ui.runtime.anim.UiClocks;
import com.openmason.engine.ui.runtime.anim.UiStateMachines;
import com.openmason.engine.ui.runtime.input.UiTransform;
import com.openmason.engine.ui.runtime.layout.FlexStyleMapper;
import com.openmason.engine.ui.runtime.layout.HitTester;
import com.openmason.engine.ui.runtime.layout.LayoutChecks;
import com.openmason.engine.ui.runtime.layout.PaintOrder;
import com.openmason.engine.ui.runtime.style.ComputedStyle;
import com.openmason.engine.ui.runtime.style.Selector;
import com.openmason.engine.ui.runtime.style.SelectorMatcher;
import com.openmason.engine.ui.runtime.style.SelectorParser;
import com.openmason.engine.ui.runtime.style.SelectorUse;
import com.openmason.engine.ui.runtime.style.SheetBinding;
import com.openmason.engine.ui.runtime.style.StyleResolver;
import com.openmason.engine.ui.runtime.style.StyleValues;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One running instance of a UI document (#287): the runtime element tree, its style
 * resolution and its retained Yoga layout. A definition ({@link OmuiArchive}) can back any
 * number of instances; each owns its own hover/focus/class/local state and never writes the
 * definition.
 *
 * <p>Per frame the host calls {@link #update()}: dirty styles resolve (parents before
 * children, propagating only when inherited values change), changed layout records are pushed
 * in one batch, and Yoga relayouts only what its dirty flags reach. Scroll offsets and
 * translations then place every element; that geometry drives both painting and
 * {@link #hitTest}. {@link #consumeDirtyRegion()} reports the device-pixel area whose pixels
 * may have changed.
 *
 * <p>The tree can change while it runs: {@link UiElement#insertChild} and
 * {@link UiElement#remove} edit it in place (lists, runtime-built content), and
 * {@link #reload} swaps in a new revision of the document or its components while keeping
 * each surviving element's instance state.
 *
 * <p>Single-threaded: the UI thread owns an instance. {@link #close()} frees the native tree.
 */
public final class UiDocumentInstance implements AutoCloseable {

    /**
     * Counters from the most recent {@link #update()}, for invalidation tests and diagnostics.
     *
     * @param nanos wall time of the whole update (styles, layout, placement); the runtime budget
     *              monitor (#296) judges the ones with {@code laidOut} against the layout budget
     */
    public record UpdateStats(int stylesResolved, int recordsPushed, int remeasured, int rectsChanged,
                              boolean laidOut, long nanos) {
        static final UpdateStats NONE = new UpdateStats(0, 0, 0, 0, false, 0);
    }

    /** Told about every {@link #update()} as it finishes (budget monitoring, #296). */
    @FunctionalInterface
    public interface UpdateObserver {
        void updated(UiDocumentInstance ui, UpdateStats stats);
    }

    /**
     * Outcome of a {@link #reload}.
     *
     * @param kept    keys present before and after; their instance state carried over
     * @param dropped keys that no longer exist; their instance state is gone
     * @param added   keys new in this revision
     */
    public record ReloadReport(Set<String> kept, Set<String> dropped, Set<String> added) {
    }

    /**
     * Where parts were authored: the screen ({@code instanceKey} empty) or one component instance.
     * Clip tracks and state machines of {@code archive} address elements through {@link #keyOf}.
     */
    public record AuthoringScope(String instanceKey, OmuiArchive archive) {
        public AuthoringScope {
            Objects.requireNonNull(instanceKey, "instanceKey");
            Objects.requireNonNull(archive, "archive");
        }

        /** Element key of node-id path {@code path} inside this scope. */
        public String keyOf(String path) {
            return instanceKey.isEmpty() ? path : path.isEmpty() ? instanceKey : instanceKey + "/" + path;
        }
    }

    /** Root class while the player asks for reduced motion, so sheets can author alternates (#295). */
    public static final String REDUCED_MOTION_CLASS = "sb-reduced-motion";

    /** Properties {@link PaintOrder} reads: only their changes rebuild it. */
    private static final Set<String> PAINT_ORDER_PROPERTIES = Set.of("display", "-sb-layer", "-sb-anchor");

    private final UiRuntimeContext context;
    private final Set<UiRuntimeDiagnostic> diagnostics = new LinkedHashSet<>();
    private final List<UiElement> elements = new ArrayList<>();
    private final Map<String, UiElement> byKey = new HashMap<>();
    private final Map<String, UiCanvasCommands> canvases = new HashMap<>();
    private final List<SheetBinding> sheets = new ArrayList<>();
    private final Set<String> customStates = new HashSet<>();
    private final List<AuthoringScope> scopes = new ArrayList<>();
    private final UiClocks clocks = new UiClocks();
    private final UiAnimator animator;
    private final UiStateMachines machines;
    private boolean scopesChanged = true;
    /** Which classes/states the sheets test and where; rebuilt when sheets attach or detach. */
    private SelectorUse selectorUse;
    /** Layout findings ({@link LayoutChecks}) currently reported, by element key: replaced on each check. */
    private final Map<String, List<UiRuntimeDiagnostic>> layoutFindings = new HashMap<>();
    private boolean closed;
    private boolean subpixelAnimation;
    /** True while {@link #place} walks a subtree whose translation animates (sub-pixel mode). */
    private boolean placingFree;
    private OmuiArchive document;
    private UiElement root;
    private BindingAccess bindings;

    private UiMetrics metrics = UiMetrics.of(0, 0, 1);
    private UiPreferences preferences = UiPreferences.DEFAULTS;
    private int textRevision;
    private FlexLayoutTree flex;
    private UiElement[] byFlexNode = new UiElement[16];
    private boolean orderDirty;
    private boolean structureChanged;
    private boolean layoutForced = true;
    private boolean anyStyleDirty = true;
    private boolean visualDirty = true;
    /** The host pointer in device pixels (NaN outside), for {@code -sb-anchor: pointer} elements. */
    private float pointerX = Float.NaN;
    private float pointerY = Float.NaN;
    /** Pointer-anchored elements found by the last placement; pointer moves re-place only when > 0. */
    private int pointerAnchors;
    private PaintOrder paintOrder;
    private UiRect dirtyRegion = UiRect.EMPTY;
    private UiRect animatedRegion = UiRect.EMPTY;
    private double nextAnimationChange = Double.POSITIVE_INFINITY;
    private UpdateStats lastStats = UpdateStats.NONE;
    private final List<UpdateObserver> updateObservers = new ArrayList<>(1);
    private float[] records = new float[FlexRecord.STRIDE * 16];
    private int[] nodeIds = new int[16];
    private float[] rects = new float[64];
    private final float[] measureOut = new float[2];
    private final FlexMeasure flexMeasure = new FlexMeasure() {
        @Override
        public void measure(int id, float w, int wMode, float h, int hMode, float[] out) {
            UiElement el = id < byFlexNode.length ? byFlexNode[id] : null;
            measureOut[0] = 0;
            measureOut[1] = 0;
            if (el != null) {
                context.measurer().measure(el, w, wMode, h, hMode, metrics.scale(), measureOut);
            }
            out[0] = measureOut[0];
            out[1] = measureOut[1];
        }

        @Override
        public float baseline(int id, float w, float h) {
            UiElement el = id < byFlexNode.length ? byFlexNode[id] : null;
            return el == null ? h : context.measurer().baseline(el, w, h, metrics.scale());
        }
    };

    private UiDocumentInstance(OmuiArchive document, UiRuntimeContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.textRevision = context.localizer().revision();
        this.animator = new UiAnimator(this, clocks);
        this.machines = new UiStateMachines(this, animator);
        install(Objects.requireNonNull(document, "document"));
    }

    /** Builds a new instance. Composition problems are diagnostics, never exceptions. */
    public static UiDocumentInstance instantiate(OmuiArchive document, UiRuntimeContext context) {
        return new UiDocumentInstance(document, context);
    }

    private void install(OmuiArchive doc) {
        UiTreeBuilder builder = new UiTreeBuilder(this, context);
        UiElement newRoot = builder.build(doc);
        document = doc;
        root = newRoot;
        elements.clear();
        elements.addAll(builder.elements());
        byKey.clear();
        byKey.putAll(builder.byKey());
        sheets.clear();
        sheets.addAll(builder.sheets());
        selectorUse = null;
        layoutFindings.clear();
        customStates.clear();
        for (SheetBinding b : sheets) {
            customStates.addAll(b.sheet().source().customStates());
        }
        scopes.clear();
        scopes.addAll(builder.scopes());
        scopesChanged = true;
        root.setHostClass(REDUCED_MOTION_CLASS, preferences.reducedMotion());
        orderDirty = true;
        anyStyleDirty = true;
        visualDirty = true;
        layoutForced = true;
    }

    // ── tree access ─────────────────────────────────────────────────────────

    public OmuiArchive document() {
        return document;
    }

    public UiRuntimeContext context() {
        return context;
    }

    public UiElement root() {
        return root;
    }

    /** How many elements the tree has (no list wrapper: the budget monitor reads it per relayout). */
    public int elementCount() {
        return preOrder().size();
    }

    /** Elements in pre-order. */
    public List<UiElement> elements() {
        return Collections.unmodifiableList(preOrder());
    }

    private List<UiElement> preOrder() {
        if (orderDirty) {
            elements.clear();
            collect(root, elements);
            orderDirty = false;
        }
        return elements;
    }

    private static void collect(UiElement el, List<UiElement> out) {
        out.add(el);
        for (UiElement c : el.children()) {
            collect(c, out);
        }
    }

    /** By stable key ({@code resume/label}). Survives renames, reparenting and {@link #reload}. */
    public UiElement find(String key) {
        return byKey.get(key);
    }

    /** First element in pre-order matching {@code selector} ({@code #resume}, {@code .menu-button}), or null. */
    public UiElement q(String selector) {
        List<UiElement> all = qAll(selector);
        return all.isEmpty() ? null : all.getFirst();
    }

    public List<UiElement> qAll(String selector) {
        List<Selector> list = SelectorParser.parseList(selector, customStates);
        List<UiElement> out = new ArrayList<>();
        for (UiElement e : elements()) {
            for (Selector s : list) {
                if (SelectorMatcher.matches(s, e)) {
                    out.add(e);
                    break;
                }
            }
        }
        return out;
    }

    /** True for built-in pseudo-states and custom states declared by any attached sheet. */
    public boolean isKnownState(String state) {
        return UiSelectors.BUILT_IN_STATES.contains(state) || customStates.contains(state);
    }

    // ── canvases (#292) ─────────────────────────────────────────────────────

    /** Gives the {@code Canvas} element with {@code key} its draw commands (the script runtime does). */
    public void attachCanvas(String key, UiCanvasCommands commands) {
        canvases.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(commands, "commands"));
        UiElement el = find(key);
        if (el != null) {
            paintChanged(el);
        }
    }

    public void detachCanvas(String key) {
        canvases.remove(key);
    }

    /** The canvas with {@code key} drew a new frame: its rect joins the dirty region. */
    public void canvasChanged(String key) {
        UiElement el = find(key);
        if (el != null) {
            paintChanged(el);
        }
    }

    /** The draw commands of the canvas with {@code key}, or null when nothing draws it. */
    public UiCanvasCommands canvas(String key) {
        return canvases.get(key);
    }

    /** Everything reported so far, deduplicated, in first-seen order. */
    public List<UiRuntimeDiagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    void report(UiRuntimeDiagnostic d) {
        diagnostics.add(d);
    }

    /** Records a finding from a collaborator (input routing, text resolution); deduplicated like the rest. */
    public void reportDiagnostic(UiRuntimeDiagnostic d) {
        diagnostics.add(Objects.requireNonNull(d, "diagnostic"));
    }

    // ── bindings (#289) ─────────────────────────────────────────────────────

    /**
     * Hands the binding layer to one binder. Until it is {@link BindingAccess#release released}
     * no other binder can claim it.
     *
     * @throws IllegalStateException when another binder holds it
     */
    public BindingAccess claimBindings() {
        if (bindings != null) {
            throw new IllegalStateException("bindings of " + document.manifest().documentId() + " are already claimed");
        }
        bindings = new BindingAccess(this);
        return bindings;
    }

    void releaseBindings(BindingAccess a) {
        if (bindings == a) {
            bindings = null;
        }
    }

    private BindingAccess.Listener bindingListener() {
        return bindings == null ? null : bindings.listener();
    }

    /** A local write on a bound target: forwarded to the binder when the binding reads it back. */
    void boundWrite(UiElement el, String target, com.openmason.engine.format.omui.UiValue value) {
        UiNode.BindingMode mode = el.bindingMode(target);
        BindingAccess.Listener l = bindingListener();
        if (l != null && (mode == UiNode.BindingMode.TWO_WAY || mode == UiNode.BindingMode.TO_SOURCE)) {
            l.localWrite(el, target, value);
        }
    }

    // ── structural edits ────────────────────────────────────────────────────

    UiElement insert(UiElement parent, int index, UiNode definition) {
        UiElement child = insertBuilt(parent, index, definition, null);
        BindingAccess.Listener l = bindingListener();
        if (l != null) {
            l.inserted(child);
        }
        return child;
    }

    UiElement insertRow(UiElement parent, int index, UiNode template, String key) {
        return insertBuilt(parent, index, template, key);
    }

    private UiElement insertBuilt(UiElement parent, int index, UiNode definition, String key) {
        requireLive(parent);
        if (!parent.descriptor().acceptsChildren()) {
            throw new IllegalArgumentException(parent.type() + " [" + parent.key() + "] cannot have children");
        }
        UiTreeBuilder builder = new UiTreeBuilder(this, context, byKey);
        UiElement child = key == null ? builder.buildChild(parent, definition) : builder.buildRow(parent, definition, key);
        if (builder.duplicates() > 0) {
            throw new IllegalArgumentException("element key " + child.key() + " is already in use");
        }
        int at = index < 0 || index > parent.children().size() ? parent.children().size() : index;
        parent.insertChildAt(at, child);
        byKey.putAll(builder.byKey());
        if (sheets.addAll(builder.sheets())) {
            selectorUse = null;
        }
        scopes.addAll(builder.scopes());
        scopesChanged |= !builder.scopes().isEmpty();
        for (SheetBinding b : builder.sheets()) {
            customStates.addAll(b.sheet().source().customStates());
        }
        if (flex != null) {
            createFlexNodes(child);
            flex.insert(parent.flexNode, child.flexNode, at);
        }
        orderDirty = true;
        anyStyleDirty = true;
        visualDirty = true;
        structureChanged = true;
        return child;
    }

    void remove(UiElement el) {
        remove(el, true);
    }

    void remove(UiElement el, boolean notify) {
        requireLive(el);
        if (el == root) {
            throw new IllegalArgumentException("the root element cannot be removed");
        }
        BindingAccess.Listener l = bindingListener();
        if (notify && l != null) {
            l.removing(el);
        }
        UiElement parent = el.parent();
        markRemoved(el);
        parent.removeChild(el);
        orderDirty = true;
        visualDirty = true;
        layoutForced = true;
        structureChanged = true;
    }

    void move(UiElement el, int index) {
        requireLive(el);
        UiElement parent = el.parent();
        if (parent == null) {
            throw new IllegalArgumentException("the root element cannot be moved");
        }
        int from = parent.children().indexOf(el);
        int to = Math.clamp(index, 0, parent.children().size() - 1);
        if (from == to) {
            return;
        }
        parent.removeChild(el);
        parent.insertChildAt(to, el);
        if (flex != null) {
            flex.detach(el.flexNode);
            flex.insert(parent.flexNode, el.flexNode, to);
        }
        orderDirty = true;
        visualDirty = true;
        layoutForced = true;
        structureChanged = true;
        paintOrder = null;
    }

    private void markRemoved(UiElement el) {
        for (UiElement c : el.children()) {
            markRemoved(c);
        }
        dirtyRegion = dirtyRegion.union(el.bounds);
        if (byKey.remove(el.key(), el)) {
            animator.forget(el.key()); // its channels die with it; a reused key starts clean
        }
        dropLayoutFindings(el.key());
        if (sheets.removeIf(b -> b.scope() == el)) {
            selectorUse = null;
        }
        if (scopes.removeIf(s -> s.instanceKey().equals(el.key()))) {
            scopesChanged = true;
        }
        if (flex != null && el.flexNode >= 0) {
            flex.freeNode(el.flexNode);
            byFlexNode[el.flexNode] = null;
            el.flexNode = -1;
        }
        el.removed = true;
    }

    private static void requireLive(UiElement el) {
        if (el.isRemoved()) {
            throw new IllegalStateException(el + " was removed");
        }
    }

    // ── live reload ─────────────────────────────────────────────────────────

    /**
     * Rebuilds against a new revision of the document (or, with the same document, against
     * whatever the {@link UiDocumentSource} now returns for its components and sheets). Every
     * element whose key survives keeps its instance state (local props, classes and styles,
     * pseudo-states, enabled, scroll); authored values, explicit overrides and component
     * sources come from the new revision. Diagnostics restart with the new build. Element
     * objects are replaced: hold keys, not elements, across a reload.
     */
    public ReloadReport reload(OmuiArchive newDocument) {
        Objects.requireNonNull(newDocument, "document");
        Map<String, UiElement> old = new HashMap<>(byKey);
        if (flex != null) {
            flex.close();
            flex = null;
        }
        diagnostics.clear();
        install(newDocument);
        Set<String> kept = new LinkedHashSet<>();
        Set<String> added = new LinkedHashSet<>();
        for (UiElement next : elements()) {
            UiElement prev = old.remove(next.key());
            if (prev == null) {
                added.add(next.key());
            } else {
                prev.transferStateTo(next);
                prev.removed = true;
                kept.add(next.key());
            }
        }
        old.values().forEach(e -> e.removed = true);
        old.keySet().forEach(animator::forget); // dropped keys take their animation channels with them
        dirtyRegion = new UiRect(0, 0, metrics.viewportWidth(), metrics.viewportHeight());
        ReloadReport report = new ReloadReport(kept, new LinkedHashSet<>(old.keySet()), added);
        BindingAccess.Listener l = bindingListener();
        if (l != null) {
            l.reloaded(report);
        }
        return report;
    }

    /** {@link #reload(OmuiArchive)} with the current document: picks up changed components and sheets. */
    public ReloadReport reload() {
        return reload(document);
    }

    // ── metrics and update ──────────────────────────────────────────────────

    public UiMetrics metrics() {
        return metrics;
    }

    /** Viewport, UI scale or DPI changed. A scale change re-pushes every layout record. */
    public void setMetrics(UiMetrics m) {
        Objects.requireNonNull(m, "metrics");
        if (m.equals(metrics)) {
            return;
        }
        if (m.scale() != metrics.scale()) {
            for (UiElement e : elements()) {
                e.recordDirty = true;
                if (e.descriptor().measured()) {
                    e.measureDirty = true;
                }
            }
            visualDirty = true; // translations are logical pixels
        }
        metrics = m;
        layoutForced = true;
    }

    /** The player's accessibility preferences this instance honours (#288). */
    public UiPreferences preferences() {
        return preferences;
    }

    /**
     * A text-scale change re-measures every measured element. Reduced motion affects input and
     * animations started from now on, and toggles {@link #REDUCED_MOTION_CLASS} on the root.
     */
    public void setPreferences(UiPreferences p) {
        Objects.requireNonNull(p, "preferences");
        if (p.textScale() != preferences.textScale()) {
            remeasureAll();
        }
        preferences = p;
        root.setHostClass(REDUCED_MOTION_CLASS, p.reducedMotion());
    }

    private void remeasureAll() {
        for (UiElement e : elements()) {
            if (e.descriptor().measured()) {
                e.measureDirty = true;
            }
        }
        layoutForced = true;
        dirtyRegion = new UiRect(0, 0, metrics.viewportWidth(), metrics.viewportHeight());
    }

    /** Resolves dirty styles, lays out, then places elements. Requires the native flex library. */
    public UpdateStats update() {
        if (closed) {
            return UpdateStats.NONE; // never rebuild a native tree for a closed instance
        }
        long start = System.nanoTime();
        int rev = context.localizer().revision();
        if (rev != textRevision) {
            textRevision = rev; // locale, catalog or pseudo-localization changed: every text may differ
            remeasureAll();
        }
        int styles = resolveStyles();
        boolean laidOut = layout();
        applyVisuals();
        lastStats = new UpdateStats(styles, lastPushed, lastRemeasured, lastRectsChanged, laidOut,
            System.nanoTime() - start);
        for (int i = 0; i < updateObservers.size(); i++) {
            updateObservers.get(i).updated(this, lastStats);
        }
        return lastStats;
    }

    public UpdateStats lastUpdate() {
        return lastStats;
    }

    /**
     * True when {@link #update()} has work: dirty styles, layout records or measurements,
     * placement, or a forced layout. Lets a host skip a second update in a frame when the input
     * and binding reconciliation after the first one changed nothing.
     */
    public boolean needsUpdate() {
        if (anyStyleDirty || visualDirty || layoutForced || structureChanged || flex == null) {
            return true;
        }
        for (UiElement el : elements()) {
            if (el.styleDirty || el.overlayDirty || el.recordDirty || el.measureDirty) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where the host's pointer is, in device pixels ({@code NaN} when it is outside the frame).
     * {@code -sb-anchor: pointer} elements follow it; hosts set it once per frame before
     * {@link #update()} ({@code UiDocumentView.layout} does).
     */
    public void setPointer(float x, float y) {
        if (Float.compare(x, pointerX) == 0 && Float.compare(y, pointerY) == 0) {
            return;
        }
        pointerX = x;
        pointerY = y;
        if (pointerAnchors > 0) {
            visualDirty = true;
        }
    }

    /** True while the pointer is over the frame (pointer-anchored elements paint only then). */
    public boolean pointerInside() {
        return !Float.isNaN(pointerX) && !Float.isNaN(pointerY);
    }

    public void addUpdateObserver(UpdateObserver o) {
        updateObservers.add(Objects.requireNonNull(o, "observer"));
    }

    public void removeUpdateObserver(UpdateObserver o) {
        updateObservers.remove(o);
    }

    /**
     * Resolves dirty styles only (no native layout). Returns how many elements were resolved.
     * Safe to call without the Cenda library, e.g. for style-only tooling.
     */
    public int resolveStyles() {
        if (!anyStyleDirty) {
            return 0;
        }
        anyStyleDirty = false;
        int resolved = 0;
        for (UiElement el : elements()) {
            if (!el.styleDirty && !el.overlayDirty && !el.inheritDirty) {
                continue;
            }
            resolved++;
            if (el.styleDirty || el.inheritDirty) {
                ComputedStyle parentStyle = el.parent() == null ? ComputedStyle.INITIAL : el.parent().computed;
                if (el.styleDirty) {
                    el.own = StyleResolver.computeOwn(el, sheets, el.baseLayers(), parentStyle, el.key(),
                        this::report);
                }
                // Only the parent's inherited values changed: re-inherit, no selector matching.
                ComputedStyle nextBase = el.own.inheriting(parentStyle);
                el.styleDirty = false;
                el.inheritDirty = false;
                ComputedStyle prevBase = el.base;
                el.base = nextBase;
                if (el.baseResolved && !nextBase.equals(prevBase)) {
                    animator.baseChanged(el, prevBase, nextBase); // style transitions (#295)
                }
                el.baseResolved = true;
            }
            el.overlayDirty = false;
            ComputedStyle next = el.base.overlay(el.animationStyle(), el.key(), this::report);
            ComputedStyle prev = el.computed;
            if (next.equals(prev)) {
                continue;
            }
            el.computed = next;
            Set<String> changed = next.changedProperties(prev);
            // Children resolve var() against these customs: they need the full cascade.
            boolean customsChanged = !next.customs().equals(prev.customs());
            boolean inheritedChanged = false;
            boolean orderChanged = false;
            boolean subtreePaint = false;
            for (String p : changed) {
                if (StyleValues.LAYOUT.contains(p)) {
                    el.recordDirty = true;
                }
                if (StyleValues.MEASURE.contains(p) && el.descriptor().measured()) {
                    el.measureDirty = true;
                }
                if (StyleValues.VISUAL.contains(p)) {
                    visualDirty = true;
                }
                subtreePaint |= StyleValues.SUBTREE_PAINT.contains(p);
                inheritedChanged |= StyleValues.INHERITED.contains(p);
                orderChanged |= PAINT_ORDER_PROPERTIES.contains(p);
            }
            if (customsChanged) {
                for (UiElement c : el.children()) {
                    c.styleDirty = true;
                }
            } else if (inheritedChanged) {
                for (UiElement c : el.children()) {
                    c.inheritDirty = true;
                }
            }
            if (orderChanged) {
                paintOrder = null;
            }
            if (subtreePaint) {
                subtreeChanged(el);
            } else {
                paintChanged(el);
            }
        }
        return resolved;
    }

    /** Opacity and visibility fade or hide a whole subtree, which may paint outside its root. */
    private void subtreeChanged(UiElement el) {
        dirtyRegion = dirtyRegion.union(el.bounds);
        for (UiElement c : el.children()) {
            subtreeChanged(c);
        }
    }

    /** Counters of the last {@link #layout()}, folded into one {@link UpdateStats} per update. */
    private int lastPushed;
    private int lastRemeasured;
    private int lastRectsChanged;

    /** Pushes dirty records and runs Yoga when anything changed; true when it ran. */
    private boolean layout() {
        boolean created = ensureFlexTree();
        int pushed = 0;
        int remeasured = 0;
        for (UiElement el : elements()) {
            if (el.recordDirty) {
                el.recordDirty = false;
                ensureRecordCapacity(pushed + 1);
                FlexStyleMapper.write(el.computed, metrics.scale(), el.descriptor().measured() ? el.flexNode : -1,
                    el.isScrollWidget(), records, pushed * FlexRecord.STRIDE);
                nodeIds[pushed++] = el.flexNode;
            }
        }
        if (pushed > 0 || created) {
            recheckLayout(); // a parent's record decides whether its children's percentages are cyclic
        }
        flex.setStyles(nodeIds, records, pushed);
        for (UiElement el : elements()) {
            if (el.measureDirty) {
                el.measureDirty = false;
                flex.markDirty(el.flexNode);
                remeasured++;
            }
        }
        lastPushed = pushed;
        lastRemeasured = remeasured;
        lastRectsChanged = 0;
        if (pushed == 0 && remeasured == 0 && !layoutForced && !created) {
            return false;
        }
        layoutForced = false;
        int changed = flex.layout(root.flexNode, metrics.viewportWidth(), metrics.viewportHeight(), flexMeasure);
        // A structural edit can change a scroll extent without moving any surviving rect.
        int rectsChanged = changed > 0 || created || structureChanged ? readRects() : 0;
        structureChanged = false;
        lastRectsChanged = rectsChanged;
        return true;
    }

    /**
     * Re-runs {@link LayoutChecks} on every element, replacing each one's previous findings, so a
     * fixed conflict stops being reported and a parent change that makes a child's percentage
     * cyclic (or no longer cyclic) is seen even though the child's own record did not change.
     */
    private void recheckLayout() {
        List<UiRuntimeDiagnostic> found = new ArrayList<>(2);
        for (UiElement el : elements()) {
            dropLayoutFindings(el.key());
            LayoutChecks.check(el, found::add);
            if (!found.isEmpty()) {
                layoutFindings.put(el.key(), List.copyOf(found));
                diagnostics.addAll(found);
                found.clear();
            }
        }
    }

    private void dropLayoutFindings(String key) {
        List<UiRuntimeDiagnostic> old = layoutFindings.remove(key);
        if (old != null) {
            old.forEach(diagnostics::remove);
        }
    }

    private boolean ensureFlexTree() {
        if (flex != null) {
            return false;
        }
        if (closed) {
            throw new IllegalStateException("UI document instance is closed");
        }
        CendaFlex.require();
        flex = CendaFlex.newTree(context.pixelGrid());
        createFlexNodes(root);
        return true;
    }

    private void createFlexNodes(UiElement el) {
        el.flexNode = flex.newNode();
        if (byFlexNode.length <= el.flexNode) {
            byFlexNode = Arrays.copyOf(byFlexNode, Math.max(byFlexNode.length * 2, el.flexNode + 1));
        }
        byFlexNode[el.flexNode] = el;
        el.recordDirty = true;
        if (el.descriptor().measured()) {
            el.measureDirty = false; // a fresh leaf is measured by its first layout anyway
        }
        for (UiElement c : el.children()) {
            createFlexNodes(c);
            flex.insert(el.flexNode, c.flexNode, -1);
        }
    }

    /** Reads every layout rect and updates scroll extents. */
    private int readRects() {
        List<UiElement> all = elements();
        int n = all.size();
        if (nodeIds.length < n) {
            nodeIds = new int[n];
        }
        if (rects.length < n * 4) {
            rects = new float[n * 4];
        }
        for (int i = 0; i < n; i++) {
            nodeIds[i] = all.get(i).flexNode;
        }
        flex.read(root.flexNode, nodeIds, n, rects);
        int moved = 0;
        for (int i = 0; i < n; i++) {
            UiElement el = all.get(i);
            UiRect next = new UiRect(rects[i * 4], rects[i * 4 + 1], rects[i * 4 + 2], rects[i * 4 + 3]);
            if (!next.equals(el.layoutRect)) {
                el.layoutRect = next;
                moved++;
            }
        }
        for (UiElement el : all) {
            if (el.isScrollContainer()) {
                updateScrollExtent(el);
            }
        }
        visualDirty = true;
        return moved;
    }

    private void updateScrollExtent(UiElement s) {
        UiRect box = s.layoutRect;
        float scale = metrics.scale();
        float right = 0;
        float bottom = 0;
        for (UiElement c : s.children()) {
            if (c.computed.collapsed()) {
                continue;
            }
            float marginR = Math.max(0, lengthPx(c.computed, "margin-right", scale));
            float marginB = Math.max(0, lengthPx(c.computed, "margin-bottom", scale));
            right = Math.max(right, c.layoutRect.right() + marginR - box.x());
            bottom = Math.max(bottom, c.layoutRect.bottom() + marginB - box.y());
        }
        right += lengthPx(s.computed, "padding-right", scale) + lengthPx(s.computed, "border-right-width", scale);
        bottom += lengthPx(s.computed, "padding-bottom", scale) + lengthPx(s.computed, "border-bottom-width", scale);
        s.maxScrollX = s.canScrollX() ? Math.max(0, right - box.width()) : 0;
        s.maxScrollY = s.canScrollY() ? Math.max(0, bottom - box.height()) : 0;
        s.scrollX = Math.clamp(s.scrollX, 0, s.maxScrollX);
        s.scrollY = Math.clamp(s.scrollY, 0, s.maxScrollY);
    }

    private static float lengthPx(ComputedStyle style, String property, float scale) {
        StyleValues.Length l = style.length(property);
        return l.kind() == StyleValues.Length.Kind.POINTS ? l.value() * scale : 0;
    }

    /**
     * Places every element: layout rect plus ancestor scroll offsets plus {@code translate-x/y}
     * (logical pixels, inherited by the subtree like a CSS transform), then its own
     * {@code scale}/{@code rotate} about the rect centre (#295), composed with its ancestors' for
     * the paint bounds. Cheap: no Yoga.
     */
    void applyVisuals() {
        if (!visualDirty) {
            return;
        }
        visualDirty = false;
        pointerAnchors = 0;
        place(root, 0, 0, null, null);
        paintOrder = null;
    }

    /**
     * @param clip  the ancestors' clip in device pixels; dirty contributions outside it cannot change pixels
     * @param outer the ancestors' composed transform, or null for none
     */
    private void place(UiElement el, float dx, float dy, UiRect clip, UiTransform outer) {
        Integer layer = el.explicitLayer();
        if (layer != null && layer != inheritedLayer(el)) {
            clip = null; // overlays escape ancestor clips (an element restating its parent's layer is not lifted)
        }
        float scale = metrics.scale();
        UiRect l = el.layoutRect;
        boolean anchored = el.isPointerAnchored();
        if (anchored) {
            // The cursor layer (C2): at the pointer plus left/top, free of ancestor scroll,
            // translation, transforms and clips; children move with it.
            pointerAnchors++;
            clip = null;
            outer = null;
            float px = pointerInside() ? pointerX : l.x();
            float py = pointerInside() ? pointerY : l.y();
            dx = px + lengthPx(el.computed, "left", scale) - l.x();
            dy = py + lengthPx(el.computed, "top", scale) - l.y();
        }
        // Offsets snap to the same device grid Yoga rounds layout to, so translated and
        // scrolled edges stay on whole pixels and paint exactly where hits land. With sub-pixel
        // animation on, a subtree whose translation is animating moves in fractional steps
        // instead (paint and hits still share the same rects).
        boolean wasFree = placingFree;
        placingFree |= subpixelAnimation && el.animatesTranslation();
        float tx = placeSnap(dx + (float) el.computed.number("translate-x", 0) * scale);
        float ty = placeSnap(dy + (float) el.computed.number("translate-y", 0) * scale);
        UiRect next = tx == 0 && ty == 0 ? l : new UiRect(l.x() + tx, l.y() + ty, l.width(), l.height());
        el.rect = next;
        el.transform = ownTransform(el, next);
        UiTransform total = outer == null ? el.transform : el.transform == null ? outer : outer.then(el.transform);
        UiRect b = total == null ? next : bounds(total, next);
        if (!b.equals(el.bounds)) {
            dirtyRegion = dirtyRegion.union(clipTo(el.bounds, clip)).union(clipTo(b, clip));
            el.bounds = b;
        }
        UiRect childClip = el.clipsChildren() ? (clip == null ? b : intersect(clip, b)) : clip;
        float cx = tx;
        float cy = ty;
        if (el.isScrollContainer()) {
            cx = placeSnap(cx - el.scrollX);
            cy = placeSnap(cy - el.scrollY);
        }
        for (UiElement c : el.children()) {
            place(c, cx, cy, childClip, total);
        }
        placingFree = wasFree;
    }

    private float placeSnap(float v) {
        return placingFree ? v : snap(v);
    }

    /**
     * Sub-pixel placement of animated translations (off by default: static geometry and the
     * fidelity baselines stay on whole device pixels). When on, an element whose
     * {@code translate-x/y} is driven by an animation channel, and its subtree, are placed at
     * fractional offsets so slow slides glide instead of stepping a pixel at a time.
     */
    public void setSubpixelAnimation(boolean on) {
        if (subpixelAnimation != on) {
            subpixelAnimation = on;
            visualDirty = true;
        }
    }

    public boolean subpixelAnimation() {
        return subpixelAnimation;
    }

    /** The layer {@code el} would paint in without its own {@code -sb-layer}: its nearest layered ancestor's. */
    private static int inheritedLayer(UiElement el) {
        for (UiElement p = el.parent(); p != null; p = p.parent()) {
            if (p.isPointerAnchored()) {
                return PaintOrder.CURSOR_LAYER;
            }
            Integer l = p.explicitLayer();
            if (l != null) {
                return l;
            }
        }
        return 0;
    }

    /**
     * {@code scale}/{@code rotate} about the transform origin in {@code r}, or null when neither
     * applies. The origin is {@code transform-origin-x/y} (px or % of the rect), else the pivot of
     * the sprite the element shows, else the centre.
     */
    UiTransform ownTransform(UiElement el, UiRect r) {
        ComputedStyle s = el.computed;
        float sc = (float) s.number("scale", 1);
        float rot = (float) s.number("rotate", 0);
        if (sc == 1f && rot == 0f) {
            return null;
        }
        double[] pivot = spritePivot(el);
        float cx = r.x() + origin(s.length("transform-origin-x"), r.width(), pivot == null ? 0.5 : pivot[0]);
        float cy = r.y() + origin(s.length("transform-origin-y"), r.height(), pivot == null ? 0.5 : pivot[1]);
        return UiTransform.translate(cx, cy).then(UiTransform.rotate(rot)).then(UiTransform.scale(sc, sc))
            .then(UiTransform.translate(-cx, -cy));
    }

    private float origin(StyleValues.Length l, float size, double fallback) {
        return switch (l.kind()) {
            case POINTS -> l.value() * metrics.scale();
            case PERCENT -> size * l.value() / 100f;
            default -> (float) (size * fallback);
        };
    }

    /** Pivot of the sprite an Image's {@code source} or any element's {@code background-image} names, or null. */
    private double[] spritePivot(UiElement el) {
        UiValue v = "Image".equals(el.type()) ? el.prop("source") : null;
        if (!(v instanceof UiValue.Str)) {
            v = el.computed.get("background-image");
        }
        return v instanceof UiValue.Str ref ? context.source().spritePivot(ref.value()) : null;
    }

    /** Axis-aligned bounds of {@code r} under {@code t}. */
    static UiRect bounds(UiTransform t, UiRect r) {
        float[] xs = {r.x(), r.right(), r.right(), r.x()};
        float[] ys = {r.y(), r.y(), r.bottom(), r.bottom()};
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            float x = t.applyX(xs[i], ys[i]);
            float y = t.applyY(xs[i], ys[i]);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        // Whole pixels, outward, ignoring float noise (cos 90° is not exactly 0).
        float x0 = (float) Math.floor(minX + 1e-3f);
        float y0 = (float) Math.floor(minY + 1e-3f);
        return new UiRect(x0, y0, (float) Math.ceil(maxX - 1e-3f) - x0, (float) Math.ceil(maxY - 1e-3f) - y0);
    }

    private static UiRect clipTo(UiRect r, UiRect clip) {
        return clip == null ? r : intersect(r, clip);
    }

    private static UiRect intersect(UiRect a, UiRect b) {
        float x = Math.max(a.x(), b.x());
        float y = Math.max(a.y(), b.y());
        float w = Math.min(a.right(), b.right()) - x;
        float h = Math.min(a.bottom(), b.bottom()) - y;
        return w <= 0 || h <= 0 ? UiRect.EMPTY : new UiRect(x, y, w, h);
    }

    private float snap(float v) {
        float grid = context.pixelGrid();
        return grid > 0 ? Math.round(v * grid) / grid : v;
    }

    private void ensureRecordCapacity(int count) {
        if (records.length < count * FlexRecord.STRIDE) {
            records = Arrays.copyOf(records, Math.max(records.length * 2, count * FlexRecord.STRIDE));
        }
        if (nodeIds.length < count) {
            nodeIds = Arrays.copyOf(nodeIds, Math.max(nodeIds.length * 2, count));
        }
    }

    // ── geometry queries ────────────────────────────────────────────────────

    /** Paint and hit order (layers, overlays) of the current tree; shared by painters and hit tests. */
    public PaintOrder paintOrder() {
        resolveStyles();
        applyVisuals();
        if (paintOrder == null) {
            paintOrder = PaintOrder.of(root);
        }
        return paintOrder;
    }

    /** Topmost pickable element at a device-pixel point, from the last layout. */
    public UiElement hitTest(float x, float y) {
        return HitTester.pick(paintOrder(), x, y);
    }

    /**
     * The cascade behind {@code el}'s computed style: matched rules in precedence order and the
     * winning declaration of each property (#293 editor; same matching as styling).
     */
    public com.openmason.engine.ui.runtime.style.StyleTrace styleTrace(UiElement el) {
        return StyleResolver.trace(el, sheets, el.styleLayers());
    }

    // ── time and animation (#294 sprites, #295 animation) ───────────────────

    /** Seconds of UI time this document has run; hosts advance it with {@code UiDocumentView.frame(dt)}. */
    public double clock() {
        return clocks.now(UiClocks.UI);
    }

    /** The document's time sources: UI, game and host-defined external clocks (#295). */
    public UiClocks clocks() {
        return clocks;
    }

    /** The one sampler of transitions, clips, tweens and state machines (#295). */
    public UiAnimator animator() {
        return animator;
    }

    /** The UI state machines of the screen and its component instances (#295). */
    public UiStateMachines stateMachines() {
        if (scopesChanged) {
            scopesChanged = false;
            machines.sync(scopes);
        }
        return machines;
    }

    /** The screen's and each component instance's authoring scope, in build order. */
    public List<AuthoringScope> authoringScopes() {
        return Collections.unmodifiableList(scopes);
    }

    /**
     * Advances the UI clock by {@code dt}, then runs state machines and samples every animation
     * at the clocks' new readings (hosts advance {@link UiClocks#GAME} and external clocks before).
     * When an animated sprite painted last frame is due to change frame, its area becomes dirty,
     * so hosts that repaint on {@link #consumeDirtyRegion} redraw exactly on frame boundaries.
     */
    public void advanceClock(double dt) {
        clocks.advance(UiClocks.UI, dt);
        stateMachines().poll();
        animator.sample();
        if (clock() >= nextAnimationChange) {
            dirtyRegion = dirtyRegion.union(animatedRegion);
            nextAnimationChange = Double.POSITIVE_INFINITY;
        }
    }

    /** Called by the painter before a frame: forgets last frame's animated areas. */
    public void beginAnimationFrame() {
        animatedRegion = UiRect.EMPTY;
        nextAnimationChange = Double.POSITIVE_INFINITY;
    }

    /** Called by the painter: {@code area} shows an animation whose next change is at UI time {@code at}. */
    public void noteAnimation(UiRect area, double at) {
        if (at == Double.POSITIVE_INFINITY) {
            return;
        }
        animatedRegion = animatedRegion.union(area);
        nextAnimationChange = Math.min(nextAnimationChange, at);
    }

    /** True when something will change on its own: an animated sprite, a transition, clip or tween. */
    public boolean animating() {
        return nextAnimationChange != Double.POSITIVE_INFINITY || animator.animating();
    }

    /** Device-pixel area changed since the last call (moves, restyles, content edits, scrolling). */
    public UiRect consumeDirtyRegion() {
        UiRect r = dirtyRegion;
        dirtyRegion = UiRect.EMPTY;
        return r;
    }

    // ── invalidation (called by elements) ───────────────────────────────────

    void invalidateStyle(UiElement el) {
        el.styleDirty = true;
        anyStyleDirty = true;
    }

    /** Only the animation channel changed: overlay it again without the cascade. */
    void invalidateOverlay(UiElement el) {
        el.overlayDirty = true;
        anyStyleDirty = true;
    }

    /** Classes and states can change what descendant selectors match. */
    void invalidateSubtreeStyle(UiElement el) {
        markSubtree(el);
        anyStyleDirty = true;
    }

    /** {@code el}'s classes changed: restyles what a selector testing one of them can reach. */
    void classesChanged(UiElement el, Set<String> before, Set<String> after) {
        SelectorUse use = selectorUse();
        SelectorUse.Reach reach = SelectorUse.Reach.NONE;
        for (String c : before) {
            if (!after.contains(c)) {
                reach = SelectorUse.max(reach, use.classReach(c));
            }
        }
        for (String c : after) {
            if (!before.contains(c)) {
                reach = SelectorUse.max(reach, use.classReach(c));
            }
        }
        invalidate(el, reach);
    }

    /**
     * {@code el}'s pseudo-state changed. {@code :disabled} is inherited (descendants match it too),
     * so it restyles the subtree whenever any selector tests it.
     */
    void stateChanged(UiElement el, String state) {
        SelectorUse use = selectorUse();
        if (UiElement.DISABLED.equals(state)) {
            invalidate(el, use.usesState(state) ? SelectorUse.Reach.SUBTREE : SelectorUse.Reach.NONE);
        } else {
            invalidate(el, use.stateReach(state));
        }
    }

    private void invalidate(UiElement el, SelectorUse.Reach reach) {
        switch (reach) {
            case SUBTREE -> invalidateSubtreeStyle(el);
            case SELF -> invalidateStyle(el);
            case NONE -> {
            }
        }
    }

    private SelectorUse selectorUse() {
        if (selectorUse == null) {
            selectorUse = SelectorUse.of(sheets);
        }
        return selectorUse;
    }

    private static void markSubtree(UiElement el) {
        el.styleDirty = true;
        for (UiElement c : el.children()) {
            markSubtree(c);
        }
    }

    void paintChanged(UiElement el) {
        dirtyRegion = dirtyRegion.union(el.bounds);
    }

    /** A scroll offset changed: re-place the subtree without Yoga. */
    void visualChanged(UiElement el) {
        visualDirty = true;
        dirtyRegion = dirtyRegion.union(el.rect);
        BindingAccess.Listener l = bindingListener();
        if (l != null) {
            l.scrolled(el);
        }
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    /** True once {@link #close()} ran, whether or not the instance ever laid out. */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Stops animations and frees the native layout tree. Idempotent; {@link #update()} is a no-op
     * afterwards (it never creates a new tree). The tree also has a cleaner as a safety net for
     * instances dropped without closing.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        machines.clear();
        animator.clear();
        if (flex != null) {
            flex.close();
        }
    }
}
