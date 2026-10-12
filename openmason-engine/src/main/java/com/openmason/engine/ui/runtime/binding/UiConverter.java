package com.openmason.engine.ui.runtime.binding;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataType;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * A pure code-behind function a binding names as its {@code converter} (#289): it maps the
 * source value to the target value and declares its result type, which the binder checks.
 * Lua code-behind (#292) and compiled graphs (#291) supply these through {@link UiConverters};
 * Java hosts and tests may too. A converter must not have side effects: it runs whenever the
 * source changes, in no promised order.
 *
 * @param result type of what {@link #to} returns
 * @param to     source → target
 * @param back   target → source for {@code two-way}/{@code to-source} bindings, or {@code null}
 *               when the converter is one-way
 */
public record UiConverter(DataType result, UnaryOperator<UiValue> to, UnaryOperator<UiValue> back) {

    public UiConverter {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(to, "to");
    }

    public static UiConverter of(DataType result, UnaryOperator<UiValue> to) {
        return new UiConverter(result, to, null);
    }
}
