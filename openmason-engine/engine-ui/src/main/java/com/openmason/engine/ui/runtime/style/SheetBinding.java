package com.openmason.engine.ui.runtime.style;

import java.util.Objects;

/**
 * A compiled sheet attached to part of a runtime tree.
 *
 * <p>Precedence between sheets is decided by {@code rank} first, then specificity, then
 * {@code order}, then rule order (#287): theme → component sheets → document sheets. A
 * component's sheets attach at its instance with a rank below the document that
 * instantiates it, so the outer document can restyle a component's internals; nested
 * components rank lower still.
 *
 * @param rank  cascade layer; higher wins regardless of specificity ({@link #THEME_RANK} lowest)
 * @param order position in the owning document's {@code styleSheets} list
 * @param scope the element whose subtree the sheet styles, or {@code null} for the whole tree
 */
public record SheetBinding(CompiledSheet sheet, int rank, int order, Styleable scope) {

    public static final int THEME_RANK = 0;
    /** Rank of the top-level document's sheets; each component nesting level subtracts one. */
    public static final int DOCUMENT_RANK = 1_000;

    public SheetBinding {
        Objects.requireNonNull(sheet, "sheet");
    }

    public static int rankForDepth(int componentDepth) {
        return DOCUMENT_RANK - componentDepth;
    }

    /** True when {@code element} is inside this binding's scope. */
    public boolean applies(Styleable element) {
        if (scope == null) {
            return true;
        }
        for (Styleable e = element; e != null; e = e.styleParent()) {
            if (e == scope) {
                return true;
            }
        }
        return false;
    }
}
