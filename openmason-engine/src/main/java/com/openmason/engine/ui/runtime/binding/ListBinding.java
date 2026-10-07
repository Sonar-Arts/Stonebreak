package com.openmason.engine.ui.runtime.binding;

import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataPath;
import com.openmason.engine.ui.data.DataRoot;
import com.openmason.engine.ui.data.DataState;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.ListChange;
import com.openmason.engine.ui.data.ListDiff;
import com.openmason.engine.ui.data.Subscription;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.runtime.input.UiEventHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A {@code ListView} bound to a collection (#289). Each row is built from the view's template
 * and gets its item as data source, so relative paths in the template ({@code .count}) read
 * the row's item.
 *
 * <ul>
 *   <li><b>Identity.</b> Items are matched by the collection's identity field (a host
 *       collection's schema, else the view's {@code itemKey}); host collections deliver
 *       incremental {@link ListChange}s, anything else is diffed by identity. A row follows
 *       its item through moves and updates, keeping its element state.</li>
 *   <li><b>Selection</b> is retained by identity across any change and cleared when its item
 *       goes; the selected row matches {@code :checked}.</li>
 *   <li><b>Virtualization.</b> With {@code itemHeight > 0} only the visible rows exist. Spacers
 *       keep the scroll extent at {@code items × itemHeight}; scrolling recycles rows by giving
 *       them other items, which rebinds their whole subtree. A recycled row drops its local
 *       (edit-in-progress) values, and focus follows its item ({@link UiBinder#onRecycled}).</li>
 *   <li><b>Grid.</b> With {@code columns > 1} the view wraps its rows into a grid: each row is
 *       {@code 1/columns} of the width and the view lays out as a wrapping row. Virtualization
 *       then works by lines of {@code columns} items, {@code itemHeight} tall.</li>
 *   <li><b>Robustness.</b> A row removed behind the binding's back (a script's
 *       {@code remove}) or a change that cannot be replayed rebuilds the rows from the items
 *       instead of failing.</li>
 * </ul>
 */
public final class ListBinding {

    private static final int UNLAID_WINDOW = 16;

    /** One row element and the item it currently shows. */
    private static final class Row {
        final UiElement el;
        final VarFeed item;
        String id;

        Row(UiElement el, VarFeed item) {
            this.el = el;
            this.item = item;
        }
    }

    private final UiBinder binder;
    private final UiElement view;
    private final UiNode.UiBinding def;
    private final Feed feed;
    private final UiNode template;
    private final List<Row> rows = new ArrayList<>();
    private final List<UiValue> items = new ArrayList<>();
    private final List<Consumer<UiValue>> selectionListeners = new ArrayList<>();
    private Subscription sub = Subscription.NONE;
    private String identity;
    private String selectedId;
    private UiElement topSpacer;
    private UiElement bottomSpacer;
    private boolean gridApplied;
    private int first;
    private int rowSeq;
    private BindingStatus status = BindingStatus.LOADING;

    ListBinding(UiBinder binder, UiElement view, UiNode.UiBinding def, Feed feed) {
        this.binder = binder;
        this.view = view;
        this.def = def;
        this.feed = feed;
        List<UiNode> authored = view.node().children();
        this.template = authored.isEmpty() ? null : authored.getFirst();
        if (authored.size() != 1) {
            binder.report(UiRuntimeDiagnostic.warning(UiRuntimeDiagnostic.Code.LIST_TEMPLATE, view.key(),
                authored.isEmpty() ? "ListView has no row template; items are not shown"
                    : "ListView has " + authored.size() + " children; only the first is the row template"));
        }
    }

    // ── public view ─────────────────────────────────────────────────────────

    public UiElement view() {
        return view;
    }

    public BindingStatus status() {
        return status;
    }

    /** The items, as last delivered. */
    public List<UiValue> items() {
        return List.copyOf(items);
    }

    /** Row elements in order (for a virtualized list, only the visible window). */
    public List<UiElement> rows() {
        return rows.stream().map(r -> r.el).toList();
    }

    /** The item a row element currently shows, or {@code null}. */
    public UiValue itemOf(UiElement row) {
        for (Row r : rows) {
            if (r.el == row) {
                return r.item.current().valueOrNull();
            }
        }
        return null;
    }

    public boolean isVirtualized() {
        return itemHeight() > 0;
    }

    /** Items per line: the {@code columns} property, at least 1. */
    public int columns() {
        return view.prop("columns") instanceof UiValue.Num n && n.value() >= 1 ? (int) n.value() : 1;
    }

    /** Index of the first item a row shows (0 unless virtualized and scrolled). */
    public int firstVisibleIndex() {
        return isVirtualized() ? first : 0;
    }

    public UiValue selectedItem() {
        int i = indexOfId(selectedId);
        return i < 0 ? null : items.get(i);
    }

    /** Selects the item at {@code index} ({@code -1} clears). */
    public void select(int index) {
        String id = index < 0 || index >= items.size() ? null : idOf(index);
        if (!Objects.equals(id, selectedId)) {
            selectedId = id;
            refreshChecked();
            UiValue item = selectedItem();
            selectionListeners.forEach(l -> l.accept(item));
        }
    }

    /** Called with the selected item (or {@code null}) whenever the selection changes. */
    public void onSelectionChanged(Consumer<UiValue> listener) {
        selectionListeners.add(listener);
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    void start() {
        identity = resolveIdentity();
        DataPath host = feed.hostPath();
        if (host != null && host.tail().isSelf()) {
            sub = binder.scope().watchList(host, this::changed);
        } else {
            sub = feed.watch(s -> changed(s, List.of()));
        }
        changed(feed.current(), List.of(ListChange.RESET));
    }

    void stop() {
        sub.close();
        sub = Subscription.NONE;
        for (Row r : List.copyOf(rows)) {
            dropRow(r);
        }
        rows.clear();
        topSpacer = null; // dropped with the view's subtree by the binder
        bottomSpacer = null;
    }

    private String resolveIdentity() {
        DataPath host = feed.hostPath();
        if (host != null) {
            DataRoot root = binder.scope().host().data().root(host.rootName());
            DataType t = root == null ? null : host.typeFrom(root.source().type());
            if (t instanceof DataType.ListOf l && l.identity() != null) {
                return l.identity();
            }
        }
        return view.prop("itemKey") instanceof UiValue.Str s && !s.value().isEmpty() ? s.value() : null;
    }

    private void changed(DataState state, List<ListChange> changes) {
        if (!(state instanceof DataState.Ready r) || !(r.value() instanceof UiValue.Arr arr)) {
            status = switch (state) {
                case DataState.Loading l -> BindingStatus.LOADING;
                case DataState.Failed f -> BindingStatus.of(BindingStatus.State.FAILED, f.message());
                case DataState.Ready r when r.value() instanceof UiValue.Null -> BindingStatus.of(BindingStatus.State.NULL, "");
                case DataState.Ready r -> BindingStatus.of(BindingStatus.State.INVALID, "prop:items needs a list");
                default -> BindingStatus.MISSING;
            };
            replaceAll(List.of());
            return;
        }
        status = BindingStatus.of(BindingStatus.State.ACTIVE, "");
        List<UiValue> next = arr.items();
        boolean incremental = !changes.isEmpty() && changes.stream().noneMatch(c -> c instanceof ListChange.Reset);
        if (incremental) {
            List<UiValue> replayed = ListDiff.apply(items, changes);
            if (!replayed.equals(next)) {
                incremental = false; // the scope's view and ours disagree: fall back to a diff
            }
        }
        if (incremental) {
            applyChanges(changes, next);
        } else {
            replaceAll(next);
        }
    }

    private void replaceAll(List<UiValue> next) {
        applyChanges(ListDiff.diff(items, next, identity), next);
    }

    private void applyChanges(List<ListChange> changes, List<UiValue> next) {
        if (template == null) {
            items.clear();
            items.addAll(next);
            return;
        }
        applyGridLayout();
        if (isVirtualized()) {
            items.clear();
            items.addAll(next);
            retainSelection();
            refreshWindow(true);
            return;
        }
        List<ListChange> plan = anyRowGone() ? List.of(ListChange.RESET) : changes;
        try {
            replay(plan, next);
        } catch (RuntimeException e) {
            binder.report(UiRuntimeDiagnostic.warning(UiRuntimeDiagnostic.Code.LIST_TEMPLATE, view.key(),
                "list rows out of step (" + e.getMessage() + "); rebuilt"));
            replay(List.of(ListChange.RESET), next);
        }
        retainSelection();
        refreshChecked();
    }

    /** True when a script removed one of the rows (or a spacer) behind the binding's back. */
    private boolean anyRowGone() {
        for (Row r : rows) {
            if (r.el.isRemoved()) {
                return true;
            }
        }
        return topSpacer != null && (topSpacer.isRemoved() || bottomSpacer.isRemoved());
    }

    private void replay(List<ListChange> changes, List<UiValue> next) {
        for (ListChange c : changes) {
            switch (c) {
                case ListChange.Inserted ins -> {
                    items.add(ins.index(), ins.item());
                    Row row = newRow(ins.index(), ins.item());
                    rows.add(ins.index(), row);
                    row.id = idOf(ins.index());
                }
                case ListChange.Removed rem -> {
                    items.remove(rem.index());
                    dropRow(rows.remove(rem.index()));
                }
                case ListChange.Updated up -> {
                    items.set(up.index(), up.item());
                    show(rows.get(up.index()), up.index());
                }
                case ListChange.Moved mv -> {
                    items.add(mv.to(), items.remove(mv.from()));
                    Row row = rows.remove(mv.from());
                    rows.add(mv.to(), row);
                    binder.access().move(row.el, mv.to());
                }
                case ListChange.Reset r -> {
                    for (Row row : List.copyOf(rows)) {
                        dropRow(row);
                    }
                    rows.clear();
                    items.clear();
                    for (int i = 0; i < next.size(); i++) {
                        items.add(next.get(i));
                        Row row = newRow(i, next.get(i));
                        rows.add(row);
                        row.id = idOf(i);
                    }
                }
            }
        }
    }

    // ── rows ────────────────────────────────────────────────────────────────

    /** Builds a row at {@code position} among the rows, already showing {@code item}. */
    private Row newRow(int position, UiValue item) {
        String key = view.key() + "/" + (isVirtualized() ? "v" : "r") + rowSeq++;
        VarFeed feed = new VarFeed(DataState.ready(item));
        int at = topSpacer == null ? position : position + 1;
        UiElement el = binder.buildRow(this, view, at, template, key, feed);
        if (isVirtualized()) {
            // The window math assumes every row is exactly itemHeight tall.
            el.setStyle("height", UiValue.of(itemHeight()));
            el.setStyle("flex-shrink", UiValue.of(0));
        }
        if (columns() > 1) {
            el.setStyle("width", UiValue.of(cellWidth()));
            el.setStyle("flex-shrink", UiValue.of(0));
        }
        return new Row(el, feed);
    }

    /**
     * Gives {@code row} the item at {@code index}.
     *
     * @return the identity the row showed before when it now shows another item (it was
     *         recycled), else {@code null}
     */
    private String show(Row row, int index) {
        String before = row.id;
        row.id = idOf(index);
        row.item.set(DataState.ready(items.get(index)));
        if (before != null && !before.equals(row.id)) {
            binder.recycled(row.el);
            return before;
        }
        return null;
    }

    /** Lays the view out as a wrapping row of {@code columns} cells (once, when columns > 1). */
    private void applyGridLayout() {
        if (columns() > 1 && !gridApplied) {
            gridApplied = true;
            view.setStyle("flex-direction", UiValue.of("row"));
            view.setStyle("flex-wrap", UiValue.of("wrap"));
            view.setStyle("align-content", UiValue.of("flex-start"));
        }
    }

    private String cellWidth() {
        return String.format(java.util.Locale.ROOT, "%.6f%%", 100.0 / columns());
    }

    private void dropRow(Row row) {
        binder.dropRow(row.el);
    }

    // ── virtualization ──────────────────────────────────────────────────────

    /** Re-evaluates which items the window shows; cheap when nothing moved. */
    void refreshWindow(boolean force) {
        if (!isVirtualized() || template == null) {
            return;
        }
        if (anyRowGone()) {
            // A script removed a row or spacer: rebuild the window from scratch.
            for (Row r : List.copyOf(rows)) {
                dropRow(r);
            }
            rows.clear();
            dropSpacers();
            force = true;
        }
        int cols = columns();
        float scale = view.owner().metrics().scale();
        float linePx = (float) itemHeight() * scale;
        float viewPx = view.rect().height();
        int n = items.size();
        int lines = (n + cols - 1) / cols;
        int visibleLines = viewPx > 0 ? (int) Math.ceil(viewPx / linePx) + 1 : UNLAID_WINDOW;
        int firstLine = Math.clamp((int) Math.floor(view.scrollY() / linePx), 0, Math.max(0, lines - visibleLines));
        int firstNow = firstLine * cols;
        int slots = Math.min(visibleLines * cols, n - firstNow);
        if (!force && firstNow == first && slots == rows.size()) {
            return;
        }
        first = firstNow;
        ensureSpacers();
        while (rows.size() < slots) {
            rows.add(newRow(rows.size(), items.get(first + rows.size())));
        }
        List<Row> moved = new ArrayList<>();
        List<String> previous = new ArrayList<>();
        for (int i = 0; i < slots; i++) {
            String before = show(rows.get(i), first + i);
            if (before != null) {
                moved.add(rows.get(i));
                previous.add(before);
            }
        }
        List<Row> gone = new ArrayList<>();
        while (rows.size() > slots) {
            gone.add(rows.removeLast());
        }
        // Focus follows its item to the row now showing it, or moves on when it scrolled away.
        for (int i = 0; i < moved.size(); i++) {
            binder.recycledAway(moved.get(i).el, rowShowing(previous.get(i)));
        }
        for (Row r : gone) {
            binder.recycledAway(r.el, rowShowing(r.id));
            dropRow(r);
        }
        double h = itemHeight();
        int shownLines = (slots + cols - 1) / cols;
        topSpacer.setStyle("height", UiValue.of(firstLine * h));
        bottomSpacer.setStyle("height", UiValue.of(Math.max(0, lines - firstLine - shownLines) * h));
        refreshChecked();
    }

    /** The row element now showing the item with {@code id}, or {@code null}. */
    private UiElement rowShowing(String id) {
        if (id == null) {
            return null;
        }
        for (Row r : rows) {
            if (id.equals(r.id)) {
                return r.el;
            }
        }
        return null;
    }

    private void dropSpacers() {
        for (UiElement sp : new UiElement[] {topSpacer, bottomSpacer}) {
            if (sp != null) {
                binder.dropRow(sp);
            }
        }
        topSpacer = null;
        bottomSpacer = null;
    }

    private void ensureSpacers() {
        if (topSpacer != null) {
            return;
        }
        topSpacer = binder.buildSpacer(view, 0, view.key() + "/~top");
        bottomSpacer = binder.buildSpacer(view, -1, view.key() + "/~bottom");
        if (columns() > 1) {
            topSpacer.setStyle("width", UiValue.of("100%")); // a spacer takes a whole line of the grid
            bottomSpacer.setStyle("width", UiValue.of("100%"));
        }
    }

    private double itemHeight() {
        return view.prop("itemHeight") instanceof UiValue.Num n ? n.value() : 0;
    }

    // ── selection ───────────────────────────────────────────────────────────

    private void retainSelection() {
        if (selectedId != null && indexOfId(selectedId) < 0) {
            selectedId = null;
            selectionListeners.forEach(l -> l.accept(null));
        }
    }

    private void refreshChecked() {
        for (Row r : rows) {
            r.el.setState(UiElement.CHECKED, selectedId != null && selectedId.equals(r.id));
        }
    }

    boolean selectable() {
        return !(view.prop("selectionMode") instanceof UiValue.Str s && "none".equals(s.value()));
    }

    UiEventHandler clickHandler(UiElement rowEl) {
        return e -> {
            if (!selectable()) {
                return;
            }
            for (Row r : rows) {
                if (r.el == rowEl) {
                    select(indexOfId(r.id));
                    return;
                }
            }
        };
    }

    private String idOf(int index) {
        return identity == null ? "#" + index : DataType.identityOf(items.get(index), identity);
    }

    private int indexOfId(String id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(idOf(i))) {
                return i;
            }
        }
        return -1;
    }

    static boolean isItemsBinding(UiElement el, UiNode.UiBinding b) {
        return "ListView".equals(el.type()) && "prop:items".equals(b.target());
    }
}
