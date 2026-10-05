package com.openmason.engine.ui.runtime;

import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic.Code;
import com.openmason.engine.ui.runtime.style.ComputedStyle;
import com.openmason.engine.ui.runtime.style.Styleable;
import com.openmason.engine.ui.runtime.widget.PropertyDescriptor;
import com.openmason.engine.ui.runtime.widget.WidgetDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One runtime element: an instance of a definition node, owned by exactly one
 * {@link UiDocumentInstance}. Everything mutable lives here; the {@link UiNode} it was built
 * from is the shared, immutable definition and is never written.
 *
 * <p><b>Property ownership</b> (lowest → highest; #283 contracts, #287):
 * <ul>
 *   <li>props: descriptor default → authored props → instance overrides (inner → outer) →
 *       binding value → local (script) write;</li>
 *   <li>style: sheet cascade → inline → instance overrides → binding → local write →
 *       animation channel;</li>
 *   <li>classes: authored → override add/remove → binding toggles → local add/remove.</li>
 * </ul>
 * A target that has a declarative binding is owned by it: a local write to it is reported
 * ({@link Code#BOUND_PROPERTY_WRITE}) and ignored rather than fighting the binding.
 *
 * <p><b>Identity.</b> {@link #key()} is built from stable node ids only (instance node ids
 * joined by {@code /}, exactly the override target syntax), so renaming or reparenting a
 * node never changes the key that bindings, scripts, clips and editor metadata use.
 */
public final class UiElement implements Styleable {

    /** Built-in pseudo-states. */
    public static final String HOVER = "hover";
    public static final String ACTIVE = "active";
    public static final String FOCUS = "focus";
    public static final String DISABLED = "disabled";
    public static final String CHECKED = "checked";

    private final UiDocumentInstance owner;
    private final String key;
    private final UiNode node;
    private final WidgetDescriptor descriptor;
    private final int componentDepth;
    private final String componentId;
    private UiElement parent;
    private final List<UiElement> children = new ArrayList<>();

    // property layers
    private final Map<String, UiValue> authoredProps;
    private final Map<String, UiValue> overrideProps = new LinkedHashMap<>();
    private final Map<String, UiValue> bindingProps = new HashMap<>();
    private final Map<String, UiValue> localProps = new HashMap<>();

    // class layers
    private final Set<String> baseClasses = new TreeSet<>();
    private final Map<String, Boolean> bindingClasses = new HashMap<>();
    private final Set<String> localAdded = new HashSet<>();
    private final Set<String> localRemoved = new HashSet<>();
    private Set<String> effectiveClasses = Set.of();

    // style layers above the sheet cascade
    private final Map<String, UiValue> overrideStyle = new LinkedHashMap<>();
    private final Map<String, UiValue> bindingStyle = new HashMap<>();
    private final Map<String, UiValue> localStyle = new HashMap<>();
    private final Map<String, UiValue> animationStyle = new HashMap<>();

    private final Set<String> boundTargets = new HashSet<>();
    private final Set<String> states = new HashSet<>();
    private boolean enabled = true;

    // derived
    ComputedStyle computed = ComputedStyle.INITIAL;
    /** Yoga's rect, before scroll offsets and translation. */
    UiRect layoutRect = UiRect.EMPTY;
    /** Where the element paints and is hit: layout rect + ancestor scroll + translation. */
    UiRect rect = UiRect.EMPTY;
    int flexNode = -1;
    /** Authoring scope, so runtime-inserted children build exactly like authored ones. */
    Object scope;
    boolean removed;
    // scroll containers
    float scrollX;
    float scrollY;
    float maxScrollX;
    float maxScrollY;
    boolean styleDirty = true;
    boolean recordDirty = true;
    boolean measureDirty;

    UiElement(UiDocumentInstance owner, String key, UiNode node, WidgetDescriptor descriptor,
              Map<String, UiValue> authoredProps, int componentDepth, String componentId) {
        this.owner = owner;
        this.key = key;
        this.node = node;
        this.descriptor = descriptor;
        this.authoredProps = Collections.unmodifiableMap(new LinkedHashMap<>(authoredProps));
        this.componentDepth = componentDepth;
        this.componentId = componentId;
        this.baseClasses.addAll(node.classes());
        for (UiNode.UiBinding b : node.bindings()) {
            boundTargets.add(b.target());
        }
        recomputeClasses();
    }

    // ── identity and structure ──────────────────────────────────────────────

    /** Stable key: node ids joined through instances ({@code resume/label}). */
    public String key() {
        return key;
    }

    /** The definition node. Shared and immutable. */
    public UiNode node() {
        return node;
    }

    public String id() {
        return node.id();
    }

    public String name() {
        return node.name();
    }

    public String type() {
        return node.type();
    }

    public WidgetDescriptor descriptor() {
        return descriptor;
    }

    public UiDocumentInstance owner() {
        return owner;
    }

    public UiElement parent() {
        return parent;
    }

    public List<UiElement> children() {
        return Collections.unmodifiableList(children);
    }

    /** 0 for nodes of the instantiated document, n inside an n-deep component. */
    public int componentDepth() {
        return componentDepth;
    }

    /** Dependency id of the component this node was authored in, or {@code null} for the document. */
    public String componentId() {
        return componentId;
    }

    void addChild(UiElement child) {
        child.parent = this;
        children.add(child);
    }

    void insertChildAt(int index, UiElement child) {
        child.parent = this;
        children.add(index, child);
    }

    void removeChild(UiElement child) {
        children.remove(child);
        child.parent = null;
    }

    /**
     * Builds {@code definition} as a new child at {@code index} (-1 appends), in this element's
     * authoring scope: its key is the scope's prefix plus the node id, it is validated against
     * its descriptor and may itself instantiate components. The definition this element came
     * from is not changed; the insertion lives on this instance only.
     *
     * @throws IllegalArgumentException when the key is already used or this element is a leaf
     */
    public UiElement insertChild(int index, UiNode definition) {
        return owner.insert(this, index, definition);
    }

    /** Removes this element and its subtree from the running instance. The root cannot be removed. */
    public void remove() {
        owner.remove(this);
    }

    public boolean isRemoved() {
        return removed;
    }

    // ── props ───────────────────────────────────────────────────────────────

    /** Effective property value through every layer; the descriptor default when unset. */
    public UiValue prop(String name) {
        UiValue v = localProps.get(name);
        if (v == null) {
            v = bindingProps.get(name);
        }
        if (v == null) {
            v = overrideProps.get(name);
        }
        if (v == null) {
            v = authoredProps.get(name);
        }
        if (v == null) {
            PropertyDescriptor p = descriptor.property(name);
            v = p == null ? UiValue.NULL : p.defaultValue();
        }
        return v;
    }

    public String text(String name) {
        return prop(name) instanceof UiValue.Str s ? s.value() : "";
    }

    /** Local (script/editor-preview) write. Validated against the descriptor; never touches the definition. */
    public boolean setProp(String name, UiValue value) {
        PropertyDescriptor p = descriptor.property(name);
        if (p == null || p.problem(value) != null) {
            owner.report(UiRuntimeDiagnostic.error(p == null ? Code.UNKNOWN_PROPERTY : Code.PROPERTY_TYPE, key,
                p == null ? type() + " has no property " + name : p.problem(value)));
            return false;
        }
        if (rejectBound("prop:" + name)) {
            return false;
        }
        if (!value.equals(localProps.put(name, value))) {
            propChanged(name);
        }
        return true;
    }

    public void clearProp(String name) {
        if (localProps.remove(name) != null) {
            propChanged(name);
        }
    }

    void putOverrideProps(Map<String, UiValue> props) {
        overrideProps.putAll(props);
    }

    /** Binding layer write (#289 owns live bindings; the builder applies static component params). */
    void setBindingProp(String name, UiValue value) {
        if (!value.equals(bindingProps.put(name, value))) {
            propChanged(name);
        }
    }

    private void propChanged(String name) {
        if (descriptor.measured()) {
            measureDirty = true;
        }
        owner.paintChanged(this);
    }

    // ── classes ─────────────────────────────────────────────────────────────

    public Set<String> classes() {
        return effectiveClasses;
    }

    @Override
    public boolean hasClass(String className) {
        return effectiveClasses.contains(className);
    }

    public void addClass(String className) {
        if (rejectBound("class:" + className)) {
            return;
        }
        localRemoved.remove(className);
        localAdded.add(className);
        classesChanged();
    }

    public void removeClass(String className) {
        if (rejectBound("class:" + className)) {
            return;
        }
        localAdded.remove(className);
        localRemoved.add(className);
        classesChanged();
    }

    public void toggleClass(String className, boolean on) {
        if (on) {
            addClass(className);
        } else {
            removeClass(className);
        }
    }

    void applyOverrideClasses(List<String> add, List<String> remove) {
        baseClasses.removeAll(remove);
        baseClasses.addAll(add);
        recomputeClasses();
    }

    void setBindingClass(String className, boolean on) {
        Boolean old = bindingClasses.put(className, on);
        if (old == null || old != on) {
            classesChanged();
        }
    }

    private void classesChanged() {
        Set<String> before = effectiveClasses;
        recomputeClasses();
        if (!before.equals(effectiveClasses)) {
            owner.invalidateSubtreeStyle(this);
        }
    }

    private void recomputeClasses() {
        Set<String> c = new LinkedHashSet<>(baseClasses);
        bindingClasses.forEach((k, on) -> {
            if (on) {
                c.add(k);
            } else {
                c.remove(k);
            }
        });
        c.addAll(localAdded);
        c.removeAll(localRemoved);
        effectiveClasses = Collections.unmodifiableSet(c);
    }

    // ── style layers ────────────────────────────────────────────────────────

    /** Local inline write (script layer). */
    public void setStyle(String property, UiValue value) {
        if (rejectBound("style:" + property)) {
            return;
        }
        if (!value.equals(localStyle.put(property, value))) {
            owner.invalidateStyle(this);
        }
    }

    public void clearStyle(String property) {
        if (localStyle.remove(property) != null) {
            owner.invalidateStyle(this);
        }
    }

    /** Animation channel (#295): wins over every other layer while set. */
    public void setAnimatedStyle(String property, UiValue value) {
        if (!value.equals(animationStyle.put(property, value))) {
            owner.invalidateStyle(this);
        }
    }

    /** Releases the channel back to the value underneath. */
    public void clearAnimatedStyle(String property) {
        if (animationStyle.remove(property) != null) {
            owner.invalidateStyle(this);
        }
    }

    void putOverrideStyle(Map<String, UiValue> style) {
        overrideStyle.putAll(style);
    }

    void setBindingStyle(String property, UiValue value) {
        if (!value.equals(bindingStyle.put(property, value))) {
            owner.invalidateStyle(this);
        }
    }

    /** Non-sheet layers in increasing precedence, for the cascade. */
    List<Map<String, UiValue>> styleLayers() {
        return List.of(node.style(), overrideStyle, bindingStyle, localStyle, animationStyle);
    }

    public ComputedStyle computedStyle() {
        return computed;
    }

    // ── states ──────────────────────────────────────────────────────────────

    @Override
    public boolean hasState(String state) {
        if (DISABLED.equals(state)) {
            return !isEnabledInHierarchy();
        }
        return states.contains(state);
    }

    /** Sets a built-in or declared custom pseudo-state ({@code hover}, {@code selected}). */
    public void setState(String state, boolean on) {
        if (DISABLED.equals(state)) {
            setEnabled(!on);
            return;
        }
        if (on && !owner.isKnownState(state)) {
            owner.report(UiRuntimeDiagnostic.warning(Code.UNKNOWN_STATE, key,
                ":" + state + " is neither built in nor declared by any style sheet of this document"));
        }
        if (on ? states.add(state) : states.remove(state)) {
            owner.invalidateSubtreeStyle(this);
        }
    }

    /** Disabled elements match {@code :disabled}, as do their descendants (Unity's SetEnabled). */
    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            owner.invalidateSubtreeStyle(this);
        }
    }

    public boolean isEnabledSelf() {
        return enabled;
    }

    public boolean isEnabledInHierarchy() {
        for (UiElement e = this; e != null; e = e.parent) {
            if (!e.enabled) {
                return false;
            }
        }
        return true;
    }

    // ── geometry and visibility ─────────────────────────────────────────────

    /**
     * Device-pixel rect where this element paints and is hit, relative to the viewport origin:
     * the last layout, moved by ancestor scroll offsets and {@code translate-x/y}.
     */
    public UiRect rect() {
        return rect;
    }

    /** Yoga's rect before scrolling and translation. */
    public UiRect layoutRect() {
        return layoutRect;
    }

    // ── scrolling ───────────────────────────────────────────────────────────

    /** A {@code ScrollView}, or any element with {@code overflow: scroll}. */
    public boolean isScrollContainer() {
        return "ScrollView".equals(node.type()) || "scroll".equals(computed.keyword("overflow", "visible"));
    }

    public float scrollX() {
        return scrollX;
    }

    public float scrollY() {
        return scrollY;
    }

    /** Largest offsets the content allows (0 when it fits), from the last layout. */
    public float maxScrollX() {
        return maxScrollX;
    }

    public float maxScrollY() {
        return maxScrollY;
    }

    /** Scrolls to device-pixel offsets, clamped to the content; a no-op on other elements. */
    public void scrollTo(float x, float y) {
        if (!isScrollContainer()) {
            return;
        }
        float nx = canScrollX() ? Math.clamp(x, 0, maxScrollX) : 0;
        float ny = canScrollY() ? Math.clamp(y, 0, maxScrollY) : 0;
        if (nx != scrollX || ny != scrollY) {
            scrollX = nx;
            scrollY = ny;
            owner.visualChanged(this);
        }
    }

    public void scrollBy(float dx, float dy) {
        scrollTo(scrollX + dx, scrollY + dy);
    }

    /** Scrolls the nearest scroll-container ancestor just enough to show this element. */
    public void scrollIntoView() {
        for (UiElement s = parent; s != null; s = s.parent) {
            if (s.isScrollContainer()) {
                UiRect view = s.rect;
                float dx = 0;
                float dy = 0;
                if (rect.x() < view.x()) {
                    dx = rect.x() - view.x();
                } else if (rect.right() > view.right()) {
                    dx = Math.min(rect.right() - view.right(), rect.x() - view.x());
                }
                if (rect.y() < view.y()) {
                    dy = rect.y() - view.y();
                } else if (rect.bottom() > view.bottom()) {
                    dy = Math.min(rect.bottom() - view.bottom(), rect.y() - view.y());
                }
                s.scrollBy(dx, dy);
                owner.applyVisuals();
                return;
            }
        }
    }

    boolean canScrollX() {
        if ("ScrollView".equals(node.type())) {
            return prop("horizontal") instanceof UiValue.Bool b && b.value();
        }
        return true;
    }

    boolean canScrollY() {
        if ("ScrollView".equals(node.type())) {
            return !(prop("vertical") instanceof UiValue.Bool b) || b.value();
        }
        return true;
    }

    /** Clips its descendants: {@code overflow: hidden} or a scroll container. */
    public boolean clipsChildren() {
        return isScrollContainer() || "hidden".equals(computed.keyword("overflow", "visible"));
    }

    /** Explicit {@code -sb-layer}, or {@code null} to stay in the parent's layer. */
    public Integer explicitLayer() {
        UiValue v = computed.get("-sb-layer");
        return v instanceof UiValue.Num n ? (int) Math.round(n.value()) : null;
    }

    /** {@code display: none} on this element or an ancestor. */
    public boolean isCollapsed() {
        for (UiElement e = this; e != null; e = e.parent) {
            if (e.computed.collapsed()) {
                return true;
            }
        }
        return false;
    }

    /** Painted: not collapsed and not {@code visibility: hidden} (inherited). */
    public boolean isVisible() {
        return !isCollapsed() && !computed.hidden();
    }

    /** Receives pointer hits itself: visible and {@code picking-mode} is not {@code ignore}. */
    public boolean isPickable() {
        return isVisible() && !computed.pickingIgnored();
    }

    // ── Styleable ───────────────────────────────────────────────────────────

    @Override
    public String styleType() {
        return com.openmason.engine.format.omui.UiWidgets.isNamespaced(node.type()) ? null : node.type();
    }

    @Override
    public String styleName() {
        return node.name();
    }

    @Override
    public Styleable styleParent() {
        return parent;
    }

    // ── live reload ─────────────────────────────────────────────────────────

    /** Copies this element's instance-level state (local writes, states, scroll) to its rebuilt twin. */
    void transferStateTo(UiElement next) {
        localProps.forEach((k, v) -> {
            if (next.descriptor.property(k) != null && !next.isBound("prop:" + k)) {
                next.localProps.put(k, v);
            }
        });
        next.localAdded.addAll(localAdded);
        next.localRemoved.addAll(localRemoved);
        next.recomputeClasses();
        localStyle.forEach((k, v) -> {
            if (!next.isBound("style:" + k)) {
                next.localStyle.put(k, v);
            }
        });
        next.animationStyle.putAll(animationStyle);
        next.states.addAll(states);
        next.enabled = enabled;
        next.scrollX = scrollX;
        next.scrollY = scrollY;
    }

    // ── ownership ───────────────────────────────────────────────────────────

    /** True when {@code target} ({@code prop:text}) is owned by a declarative binding. */
    public boolean isBound(String target) {
        return boundTargets.contains(target);
    }

    private boolean rejectBound(String target) {
        if (boundTargets.contains(target)) {
            owner.report(UiRuntimeDiagnostic.warning(Code.BOUND_PROPERTY_WRITE, key,
                target + " is owned by a binding; local write ignored"));
            return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return type() + "[" + key + "]";
    }
}
