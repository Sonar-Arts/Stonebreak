package com.openmason.engine.ui.runtime;

import com.openmason.engine.cenda.CendaFlex;
import com.openmason.engine.cenda.FlexLayoutTree;
import com.openmason.engine.cenda.FlexMeasure;
import com.openmason.engine.cenda.FlexRecord;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiSelectors;
import com.openmason.engine.ui.runtime.layout.FlexStyleMapper;
import com.openmason.engine.ui.runtime.layout.HitTester;
import com.openmason.engine.ui.runtime.layout.LayoutChecks;
import com.openmason.engine.ui.runtime.layout.PaintOrder;
import com.openmason.engine.ui.runtime.style.ComputedStyle;
import com.openmason.engine.ui.runtime.style.Selector;
import com.openmason.engine.ui.runtime.style.SelectorMatcher;
import com.openmason.engine.ui.runtime.style.SelectorParser;
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

    /** Counters from the most recent {@link #update()}, for invalidation tests and diagnostics. */
    public record UpdateStats(int stylesResolved, int recordsPushed, int remeasured, int rectsChanged,
                              boolean laidOut) {
        static final UpdateStats NONE = new UpdateStats(0, 0, 0, 0, false);
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

    private final UiRuntimeContext context;
    private final Set<UiRuntimeDiagnostic> diagnostics = new LinkedHashSet<>();
    private final List<UiElement> elements = new ArrayList<>();
    private final Map<String, UiElement> byKey = new HashMap<>();
    private final List<SheetBinding> sheets = new ArrayList<>();
    private final Set<String> customStates = new HashSet<>();
    private OmuiArchive document;
    private UiElement root;

    private UiMetrics metrics = UiMetrics.of(0, 0, 1);
    private FlexLayoutTree flex;
    private UiElement[] byFlexNode = new UiElement[16];
    private boolean orderDirty;
    private boolean structureChanged;
    private boolean layoutForced = true;
    private boolean anyStyleDirty = true;
    private boolean visualDirty = true;
    private PaintOrder paintOrder;
    private UiRect dirtyRegion = UiRect.EMPTY;
    private UpdateStats lastStats = UpdateStats.NONE;
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
        customStates.clear();
        for (SheetBinding b : sheets) {
            customStates.addAll(b.sheet().source().customStates());
        }
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

    /** Elements in pre-order. */
    public List<UiElement> elements() {
        if (orderDirty) {
            elements.clear();
            collect(root, elements);
            orderDirty = false;
        }
        return Collections.unmodifiableList(elements);
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

    /** Everything reported so far, deduplicated, in first-seen order. */
    public List<UiRuntimeDiagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    void report(UiRuntimeDiagnostic d) {
        diagnostics.add(d);
    }

    // ── structural edits ────────────────────────────────────────────────────

    UiElement insert(UiElement parent, int index, UiNode definition) {
        requireLive(parent);
        if (!parent.descriptor().acceptsChildren()) {
            throw new IllegalArgumentException(parent.type() + " [" + parent.key() + "] cannot have children");
        }
        UiTreeBuilder builder = new UiTreeBuilder(this, context, byKey);
        int before = diagnostics.size();
        UiElement child = builder.buildChild(parent, definition);
        if (diagnostics.stream().skip(before).anyMatch(d -> d.code() == UiRuntimeDiagnostic.Code.DUPLICATE_ELEMENT_KEY)) {
            throw new IllegalArgumentException("element key " + child.key() + " is already in use");
        }
        int at = index < 0 || index > parent.children().size() ? parent.children().size() : index;
        parent.insertChildAt(at, child);
        byKey.putAll(builder.byKey());
        sheets.addAll(builder.sheets());
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
        requireLive(el);
        if (el == root) {
            throw new IllegalArgumentException("the root element cannot be removed");
        }
        UiElement parent = el.parent();
        markRemoved(el);
        parent.removeChild(el);
        orderDirty = true;
        visualDirty = true;
        layoutForced = true;
        structureChanged = true;
    }

    private void markRemoved(UiElement el) {
        for (UiElement c : el.children()) {
            markRemoved(c);
        }
        dirtyRegion = dirtyRegion.union(el.rect);
        byKey.remove(el.key(), el);
        sheets.removeIf(b -> b.scope() == el);
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
        dirtyRegion = new UiRect(0, 0, metrics.viewportWidth(), metrics.viewportHeight());
        return new ReloadReport(kept, new LinkedHashSet<>(old.keySet()), added);
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

    /** Resolves dirty styles, lays out, then places elements. Requires the native flex library. */
    public UpdateStats update() {
        int styles = resolveStyles();
        lastStats = layout(styles);
        applyVisuals();
        return lastStats;
    }

    public UpdateStats lastUpdate() {
        return lastStats;
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
            if (!el.styleDirty) {
                continue;
            }
            el.styleDirty = false;
            resolved++;
            ComputedStyle parentStyle = el.parent() == null ? ComputedStyle.INITIAL : el.parent().computed;
            ComputedStyle next = StyleResolver.compute(el, sheets, el.styleLayers(), parentStyle, el.key(), this::report);
            ComputedStyle prev = el.computed;
            if (next.equals(prev)) {
                continue;
            }
            el.computed = next;
            Set<String> changed = next.changedProperties(prev);
            boolean inheritedChanged = !next.customs().equals(prev.customs());
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
                inheritedChanged |= StyleValues.INHERITED.contains(p);
            }
            if (inheritedChanged) {
                for (UiElement c : el.children()) {
                    c.styleDirty = true;
                }
            }
            paintOrder = null;
            paintChanged(el);
        }
        return resolved;
    }

    private UpdateStats layout(int stylesResolved) {
        boolean created = ensureFlexTree();
        int pushed = 0;
        int remeasured = 0;
        for (UiElement el : elements()) {
            if (el.recordDirty) {
                el.recordDirty = false;
                ensureRecordCapacity(pushed + 1);
                FlexStyleMapper.write(el.computed, metrics.scale(), el.descriptor().measured() ? el.flexNode : -1,
                    "ScrollView".equals(el.type()), records, pushed * FlexRecord.STRIDE);
                nodeIds[pushed++] = el.flexNode;
                LayoutChecks.check(el, this::report);
            }
        }
        flex.setStyles(nodeIds, records, pushed);
        for (UiElement el : elements()) {
            if (el.measureDirty) {
                el.measureDirty = false;
                flex.markDirty(el.flexNode);
                remeasured++;
            }
        }
        if (pushed == 0 && remeasured == 0 && !layoutForced && !created) {
            return new UpdateStats(stylesResolved, 0, 0, 0, false);
        }
        layoutForced = false;
        int changed = flex.layout(root.flexNode, metrics.viewportWidth(), metrics.viewportHeight(), flexMeasure);
        // A structural edit can change a scroll extent without moving any surviving rect.
        int rectsChanged = changed > 0 || created || structureChanged ? readRects() : 0;
        structureChanged = false;
        return new UpdateStats(stylesResolved, pushed, remeasured, rectsChanged, true);
    }

    private boolean ensureFlexTree() {
        if (flex != null) {
            return false;
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
     * (logical pixels, inherited by the subtree like a CSS transform). Cheap: no Yoga.
     */
    void applyVisuals() {
        if (!visualDirty) {
            return;
        }
        visualDirty = false;
        place(root, 0, 0, null);
        paintOrder = null;
    }

    /** @param clip the ancestors' clip; dirty contributions outside it cannot change pixels */
    private void place(UiElement el, float dx, float dy, UiRect clip) {
        if (el.explicitLayer() != null) {
            clip = null; // overlays escape ancestor clips
        }
        float scale = metrics.scale();
        // Offsets snap to the same device grid Yoga rounds layout to, so translated and
        // scrolled edges stay on whole pixels and paint exactly where hits land.
        float tx = snap(dx + (float) el.computed.number("translate-x", 0) * scale);
        float ty = snap(dy + (float) el.computed.number("translate-y", 0) * scale);
        UiRect l = el.layoutRect;
        UiRect next = tx == 0 && ty == 0 ? l : new UiRect(l.x() + tx, l.y() + ty, l.width(), l.height());
        if (!next.equals(el.rect)) {
            dirtyRegion = dirtyRegion.union(clipTo(el.rect, clip)).union(clipTo(next, clip));
            el.rect = next;
        }
        UiRect childClip = el.clipsChildren() ? (clip == null ? next : intersect(clip, next)) : clip;
        float cx = tx;
        float cy = ty;
        if (el.isScrollContainer()) {
            cx = snap(cx - el.scrollX);
            cy = snap(cy - el.scrollY);
        }
        for (UiElement c : el.children()) {
            place(c, cx, cy, childClip);
        }
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

    /** Classes and states can change what descendant selectors match. */
    void invalidateSubtreeStyle(UiElement el) {
        markSubtree(el);
        anyStyleDirty = true;
    }

    private static void markSubtree(UiElement el) {
        el.styleDirty = true;
        for (UiElement c : el.children()) {
            markSubtree(c);
        }
    }

    void paintChanged(UiElement el) {
        dirtyRegion = dirtyRegion.union(el.rect);
    }

    /** A scroll offset changed: re-place the subtree without Yoga. */
    void visualChanged(UiElement el) {
        visualDirty = true;
        dirtyRegion = dirtyRegion.union(el.rect);
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    public boolean isClosed() {
        return flex != null && flex.isClosed();
    }

    @Override
    public void close() {
        if (flex != null) {
            flex.close();
        }
    }
}
