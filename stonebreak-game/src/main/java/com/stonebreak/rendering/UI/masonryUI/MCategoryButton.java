package com.stonebreak.rendering.UI.masonryUI;

/**
 * Button variant that carries a tag object (typically an enum). Identical
 * rendering to {@link MButton}; the tag lets the settings menu route clicks
 * without a parallel index lookup.
 *
 * <p>Type parameter {@code T} is the tag type — e.g. {@code CategoryState}.
 */
public class MCategoryButton<T> extends MButton {

    private final T tag;

    public MCategoryButton(T tag, String text) {
        super(text);
        this.tag = tag;
    }

    public T tag() {
        return tag;
    }

    @Override public MCategoryButton<T> position(float x, float y) { super.position(x, y); return this; }
    @Override public MCategoryButton<T> size(float w, float h) { super.size(w, h); return this; }
    @Override public MCategoryButton<T> bounds(float x, float y, float w, float h) {
        super.bounds(x, y, w, h); return this;
    }
}
