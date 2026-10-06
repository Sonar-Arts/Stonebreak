package com.openmason.engine.ui.runtime;

import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic.Code;
import com.openmason.engine.ui.runtime.input.EventCallbacks;
import com.openmason.engine.ui.runtime.input.UiEventHandler;
import com.openmason.engine.ui.runtime.input.UiEventType;
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
 * A target with a {@code to-target} or {@code once} binding is owned by it: a local write to it
 * is reported ({@link Code#BOUND_PROPERTY_WRITE}) and ignored rather than fighting the binding.
 * On a {@code two-way} or {@code to-source} target the local layer holds the user's edit in
 * progress above the binding's base value; the binder stages it into the scope's draft and
 * clears it once the draft or the committed value shows through the binding again (#289).
 * Animation channels sit above both and never write the source.
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
    /** Focus that came from the keyboard or a controller, which is indicated (#288). */
    public static final String FOCUS_VISIBLE = "focus-visible";
    /** A text field whose value fails its {@code pattern} (#288). */
    public static final String INVALID = "invalid";

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
    /** Animation channel of props (#295): {@code prop:} clip tracks and tweens. */
    private final Map<String, UiValue> animationProps = new HashMap<>();

    // class layers
    private final Set<String> baseClasses = new TreeSet<>();
    private final Map<String, Boolean> bindingClasses = new HashMap<>();
    private final Set<String> localAdded = new HashSet<>();
    private final Set<String> localRemoved = new HashSet<>();
    /** Classes the runtime itself sets ({@code sb-reduced-motion} on the root, #295). */
    private final Set<String> hostClasses = new HashSet<>();
    private Set<String> effectiveClasses = Set.of();

    // style layers above the sheet cascade
    private final Map<String, UiValue> overrideStyle = new LinkedHashMap<>();
    private final Map<String, UiValue> bindingStyle = new HashMap<>();
    private final Map<String, UiValue> localStyle = new HashMap<>();
    private final Map<String, UiValue> animationStyle = new HashMap<>();

    /** Binding target → mode, from the definition; decides who owns a target (#289). */
    private final Map<String, UiNode.BindingMode> boundModes = new HashMap<>();
    /** An {@code Instance} element's merged component parameters (#287); null elsewhere. */
    UiValue.Obj componentParams;
    private final Set<String> states = new HashSet<>();
    private boolean enabled = true;
    private EventCallbacks callbacks;

    // derived
    /** What the element shows: {@link #base} overlaid with the animation channel (#295). */
    ComputedStyle computed = ComputedStyle.INITIAL;
    /** The cascade without animation; style transitions ease its changes. */
    ComputedStyle base = ComputedStyle.INITIAL;
    boolean baseResolved;
    /** Own {@code scale}/{@code rotate} about the rect centre, or null for none (#295). */
    com.openmason.engine.ui.runtime.input.UiTransform transform;
    /** Device-pixel area this element paints, after every ancestor transform. */
    UiRect bounds = UiRect.EMPTY;
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
    /** Only the animation channel changed: re-overlay without running the cascade. */
    boolean overlayDirty;
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
            boundModes.put(b.target(), b.mode());
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

    /** Effective property value through every layer (animation on top); the descriptor default when unset. */
    public UiValue prop(String name) {
        UiValue v = animationProps.get(name);
        if (v == null) {
            v = localProps.get(name);
        }
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
            owner.boundWrite(this, "prop:" + name, value);
        }
        return true;
    }

    public void clearProp(String name) {
        if (localProps.remove(name) != null) {
            propChanged(name);
        }
    }

    /** The local (script) layer's own value of {@code name}, or null when it leaves the property alone. */
    public UiValue localProp(String name) {
        return localProps.get(name);
    }

    /** The animation channel's value of prop {@code name}, or null when no animation drives it (#295). */
    public UiValue animatedProp(String name) {
        return animationProps.get(name);
    }

    /** Animation channel of a prop (#295): wins over every other layer while set; never written back. */
    public void setAnimatedProp(String name, UiValue value) {
        if (!value.equals(animationProps.put(name, value))) {
            propChanged(name);
        }
    }

    public void clearAnimatedProp(String name) {
        if (animationProps.remove(name) != null) {
            propChanged(name);
        }
    }

    void putOverrideProps(Map<String, UiValue> props) {
        overrideProps.putAll(props);
    }

    /** Binding layer write (the builder applies static component params; the binder the rest, #289). */
    void setBindingProp(String name, UiValue value) {
        if (!value.equals(bindingProps.put(name, value))) {
            propChanged(name);
        }
    }

    void clearBindingProp(String name) {
        if (bindingProps.remove(name) != null) {
            propChanged(name);
        }
    }

    /** An {@code Instance} element's component parameters (defaults merged with the instance's), or null. */
    public UiValue.Obj componentParams() {
        return componentParams;
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
        boolean changed = localAdded.add(className);
        classesChanged();
        if (changed) {
            owner.boundWrite(this, "class:" + className, UiValue.TRUE);
        }
    }

    public void removeClass(String className) {
        if (rejectBound("class:" + className)) {
            return;
        }
        localAdded.remove(className);
        boolean changed = localRemoved.add(className);
        classesChanged();
        if (changed) {
            owner.boundWrite(this, "class:" + className, UiValue.FALSE);
        }
    }

    /** TRUE when the local layer adds {@code className}, FALSE when it removes it, null when neither. */
    public Boolean localClass(String className) {
        return localAdded.contains(className) ? Boolean.TRUE : localRemoved.contains(className) ? Boolean.FALSE : null;
    }

    /** Drops a local add/remove of {@code className}: the binding or authored value shows again. */
    public void clearLocalClass(String className) {
        if (localAdded.remove(className) | localRemoved.remove(className)) {
            classesChanged();
        }
    }

    public void toggleClass(String className, boolean on) {
        if (on) {
            addClass(className);
        } else {
            removeClass(className);
        }
    }

    /** Runtime-owned class ({@code sb-reduced-motion}); above every authored and local layer. */
    void setHostClass(String className, boolean on) {
        if (on ? hostClasses.add(className) : hostClasses.remove(className)) {
            classesChanged();
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

    void clearBindingClass(String className) {
        if (bindingClasses.remove(className) != null) {
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
        c.addAll(hostClasses);
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
            owner.boundWrite(this, "style:" + property, value);
        }
    }

    public void clearStyle(String property) {
        if (localStyle.remove(property) != null) {
            owner.invalidateStyle(this);
        }
    }

    /** The local (script) layer's own value of {@code property}, or null when unset. */
    public UiValue localStyle(String property) {
        return localStyle.get(property);
    }

    /** The animation channel's value of {@code property}, or null when no animation drives it. */
    public UiValue animatedStyle(String property) {
        return animationStyle.get(property);
    }

    /**
     * Animation channel (#295): wins over every other layer while set. Only re-overlays the
     * resolved cascade, so an animated property costs no selector matching per frame.
     */
    public void setAnimatedStyle(String property, UiValue value) {
        if (!value.equals(animationStyle.put(property, value))) {
            owner.invalidateOverlay(this);
        }
    }

    /** Releases the channel back to the value underneath. */
    public void clearAnimatedStyle(String property) {
        if (animationStyle.remove(property) != null) {
            owner.invalidateOverlay(this);
        }
    }

    /** The animation channel's values; read-only. */
    Map<String, UiValue> animationStyle() {
        return animationStyle;
    }

    void putOverrideStyle(Map<String, UiValue> style) {
        overrideStyle.putAll(style);
    }

    void setBindingStyle(String property, UiValue value) {
        if (!value.equals(bindingStyle.put(property, value))) {
            owner.invalidateStyle(this);
        }
    }

    void clearBindingStyle(String property) {
        if (bindingStyle.remove(property) != null) {
            owner.invalidateStyle(this);
        }
    }

    /** Non-sheet layers in increasing precedence, animation included (style traces). */
    List<Map<String, UiValue>> styleLayers() {
        return List.of(node.style(), overrideStyle, bindingStyle, localStyle, animationStyle);
    }

    /** The cascade's non-sheet layers without animation, which overlays the result instead. */
    List<Map<String, UiValue>> baseLayers() {
        return List.of(node.style(), overrideStyle, bindingStyle, localStyle);
    }

    /** What the element shows: the cascade with transitions and animations on top. */
    public ComputedStyle computedStyle() {
        return computed;
    }

    /**
     * The cascade alone (sheets, inline, overrides, binding, local), before transitions and
     * animations (#295). An animation that is released lands here.
     */
    public ComputedStyle baseStyle() {
        return base;
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

    // ── event handlers (#288) ───────────────────────────────────────────────

    /** Registers a bubble-up (and at-target) handler. Lua handlers (#292) use the same call. */
    public void on(UiEventType type, UiEventHandler handler) {
        on(type, handler, EventCallbacks.Phase.BUBBLE_UP);
    }

    public void on(UiEventType type, UiEventHandler handler, EventCallbacks.Phase phase) {
        if (callbacks == null) {
            callbacks = new EventCallbacks();
        }
        callbacks.register(type, handler, phase);
    }

    public void off(UiEventType type, UiEventHandler handler) {
        off(type, handler, EventCallbacks.Phase.BUBBLE_UP);
    }

    public void off(UiEventType type, UiEventHandler handler, EventCallbacks.Phase phase) {
        if (callbacks != null) {
            callbacks.unregister(type, handler, phase);
        }
    }

    /** Registered handlers, or null when none were ever registered. */
    public EventCallbacks callbacks() {
        return callbacks;
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

    /**
     * This element's own {@code scale}/{@code rotate} about its rect centre in device pixels
     * (#295), applied to it and its subtree; identity when it has none. Ancestors' transforms
     * compose on top ({@code UiCoordinates.elementTransform}).
     */
    public com.openmason.engine.ui.runtime.input.UiTransform localTransform() {
        return transform == null ? com.openmason.engine.ui.runtime.input.UiTransform.IDENTITY : transform;
    }

    /** True when {@code scale} or {@code rotate} applies to this element. */
    public boolean isTransformed() {
        return transform != null;
    }

    /** Device-pixel area this element paints after every transform (its rect when untransformed). */
    public UiRect paintBounds() {
        return bounds;
    }

    // ── scrolling ───────────────────────────────────────────────────────────

    /** A {@code ScrollView} or {@code ListView}, or any element with {@code overflow: scroll}. */
    public boolean isScrollContainer() {
        return isScrollWidget() || "scroll".equals(computed.keyword("overflow", "visible"));
    }

    /** A widget that always scrolls ({@code ScrollView}, {@code ListView}). */
    public boolean isScrollWidget() {
        return "ScrollView".equals(node.type()) || "ListView".equals(node.type());
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
        if ("ListView".equals(node.type())) {
            return false;
        }
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

    /** Copies this element's instance-level state (local writes, states, scroll, event handlers) to its rebuilt twin. */
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
        animationProps.forEach((k, v) -> {
            if (next.descriptor.property(k) != null) {
                next.animationProps.put(k, v);
            }
        });
        next.states.addAll(states);
        next.enabled = enabled;
        next.scrollX = scrollX;
        next.scrollY = scrollY;
        if (callbacks != null) {
            next.callbacks = callbacks.copy();
        }
    }

    // ── ownership ───────────────────────────────────────────────────────────

    /** True when {@code target} ({@code prop:text}) has a declarative binding. */
    public boolean isBound(String target) {
        return boundModes.containsKey(target);
    }

    /** The binding mode of {@code target}, or null when unbound. */
    public UiNode.BindingMode bindingMode(String target) {
        return boundModes.get(target);
    }

    /**
     * Local writes to a target bound {@code to-target} or {@code once} fight the binding and are
     * refused; on a {@code two-way} or {@code to-source} target a local write is the user's (or a
     * script's) edit, which the binder stages into the scope's draft (#289).
     */
    private boolean rejectBound(String target) {
        UiNode.BindingMode mode = boundModes.get(target);
        if (mode == UiNode.BindingMode.TO_TARGET || mode == UiNode.BindingMode.ONCE) {
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
