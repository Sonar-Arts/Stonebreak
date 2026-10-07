package com.openmason.engine.ui.runtime.style;

/**
 * What selector matching needs to know about an element. The runtime element implements it;
 * tests can match against plain stubs.
 */
public interface Styleable {

    /**
     * Widget type for type selectors ({@code Button}). Namespaced host types
     * ({@code stonebreak:CrucibleView}) never match a type selector; they are styled by class.
     */
    String styleType();

    /** The {@code #name} handle, or {@code null}. */
    String styleName();

    boolean hasClass(String className);

    /**
     * Every class the element has, so sheets only test selectors that could match
     * ({@link CompiledSheet#candidates}); null (the default) means "unknown: test every rule".
     */
    default Iterable<String> styleClasses() {
        return null;
    }

    /** Built-in ({@code hover active focus disabled checked}) or declared custom state. */
    boolean hasState(String state);

    /** Parent in the runtime tree (component instances and slot content included), or {@code null}. */
    Styleable styleParent();
}
