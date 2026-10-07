package com.openmason.engine.ui.runtime;

import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;

import java.util.Objects;

/**
 * The binding layer of one {@link UiDocumentInstance}, handed to exactly one binder at a time
 * (#289, {@link UiDocumentInstance#claimBindings}). It writes the binding layer of element
 * properties, styles and classes (below local writes and animation channels), builds template
 * rows and moves elements for collection views, and tells the binder about tree changes it must
 * follow. Nothing else writes the binding layer, so two binders can never fight over a target.
 */
public final class BindingAccess {

    /** Tree changes a binder follows. Called on the UI thread, synchronously. */
    public interface Listener {
        /** A subtree was inserted at run time (a script's {@code insertChild}, not a binder row). */
        default void inserted(UiElement subtreeRoot) {
        }

        /** A subtree is about to be removed; release what it holds. */
        default void removing(UiElement subtreeRoot) {
        }

        /** The tree was rebuilt by {@link UiDocumentInstance#reload}: every element object is new. */
        default void reloaded(UiDocumentInstance.ReloadReport report) {
        }

        /** A scroll container's offset changed. */
        default void scrolled(UiElement container) {
        }

        /** A local write landed on a {@code two-way} or {@code to-source} bound target. */
        default void localWrite(UiElement element, String target, UiValue value) {
        }
    }

    private final UiDocumentInstance owner;
    private Listener listener;
    private boolean released;

    BindingAccess(UiDocumentInstance owner) {
        this.owner = owner;
    }

    public UiDocumentInstance instance() {
        return owner;
    }

    public void listen(Listener l) {
        this.listener = l;
    }

    Listener listener() {
        return released ? null : listener;
    }

    /** Gives the binding layer back; the instance can be claimed again. */
    public void release() {
        released = true;
        owner.releaseBindings(this);
    }

    // ── binding layer ───────────────────────────────────────────────────────

    /**
     * Writes {@code value} to {@code target} ({@code prop:text}, {@code style:opacity},
     * {@code class:on}; a class takes a boolean) on the binding layer. The caller has checked
     * the value against the target's type.
     */
    public void set(UiElement el, String target, UiValue value) {
        check(el);
        String name = name(target);
        switch (kind(target)) {
            case "prop" -> el.setBindingProp(name, value);
            case "style" -> el.setBindingStyle(name, value);
            case "class" -> el.setBindingClass(name, value instanceof UiValue.Bool b && b.value());
            default -> throw new IllegalArgumentException("not a binding target: " + target);
        }
    }

    /** Removes the binding layer's value: the authored, override or default value shows again. */
    public void clear(UiElement el, String target) {
        check(el);
        String name = name(target);
        switch (kind(target)) {
            case "prop" -> el.clearBindingProp(name);
            case "style" -> el.clearBindingStyle(name);
            case "class" -> el.clearBindingClass(name);
            default -> throw new IllegalArgumentException("not a binding target: " + target);
        }
    }

    /** Drops the local (edit-in-progress) value of a two-way target, so the bound value shows. */
    public void clearLocal(UiElement el, String target) {
        check(el);
        String name = name(target);
        switch (kind(target)) {
            case "prop" -> el.clearProp(name);
            case "style" -> el.clearStyle(name);
            case "class" -> el.clearLocalClass(name);
            default -> throw new IllegalArgumentException("not a binding target: " + target);
        }
    }

    // ── collection rows ─────────────────────────────────────────────────────

    /**
     * Builds {@code template} as a child of {@code parent} at {@code index} with element key
     * {@code key}; the template's descendants get keys {@code key/<nodeId>}, like a component
     * instance, so rows built from one template never collide.
     */
    public UiElement insertRow(UiElement parent, int index, UiNode template, String key) {
        check(parent);
        return owner.insertRow(parent, index, template, key);
    }

    /** Moves {@code el} to {@code index} among its siblings, keeping its state and subtree. */
    public void move(UiElement el, int index) {
        check(el);
        owner.move(el, index);
    }

    /** Removes a row or other runtime-built element without notifying the listener. */
    public void removeRow(UiElement el) {
        check(el);
        owner.remove(el, false);
    }

    private void check(UiElement el) {
        if (released) {
            throw new IllegalStateException("binding access was released");
        }
        if (Objects.requireNonNull(el, "element").owner() != owner) {
            throw new IllegalArgumentException(el + " belongs to another instance");
        }
    }

    private static String kind(String target) {
        int colon = target.indexOf(':');
        return colon < 0 ? "" : target.substring(0, colon);
    }

    private static String name(String target) {
        return target.substring(target.indexOf(':') + 1);
    }
}
