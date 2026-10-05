package com.openmason.engine.ui.runtime.binding;

import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.CallSite;
import com.openmason.engine.ui.data.DataPath;
import com.openmason.engine.ui.data.DataState;
import com.openmason.engine.ui.data.EditSession;
import com.openmason.engine.ui.data.Subscription;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic.Code;
import com.openmason.engine.ui.runtime.widget.PropertyDescriptor;

import java.util.function.Consumer;

/**
 * One declarative binding on one element (#289): source → converter → type check → the
 * element's binding layer, and for {@code two-way}/{@code to-source} the way back into the
 * scope's edit draft.
 */
final class TargetBinding {

    private final UiBinder binder;
    final UiElement el;
    final UiNode.UiBinding def;
    private final Feed feed;
    private final Consumer<UiValue> sink;
    private UiConverter converter;
    private Subscription sub = Subscription.NONE;
    private BindingStatus status = BindingStatus.LOADING;
    private boolean applied;

    /**
     * @param sink where values go instead of the element (component parameters); null = the
     *             element's binding layer
     */
    TargetBinding(UiBinder binder, UiElement el, UiNode.UiBinding def, Feed feed, Consumer<UiValue> sink) {
        this.binder = binder;
        this.el = el;
        this.def = def;
        this.feed = feed;
        this.sink = sink;
    }

    BindingStatus status() {
        return status;
    }

    UiNode.BindingMode mode() {
        return def.mode();
    }

    void start() {
        if (def.converter() != null) {
            converter = binder.converters().find(def.converter());
            if (converter == null) {
                fail(Code.MISSING_CONVERTER, false, "converter '" + def.converter() + "' is not defined by the code-behind");
                return;
            }
        }
        if (sink == null && !targetExists()) {
            return;
        }
        if (def.mode() == UiNode.BindingMode.TO_SOURCE) {
            status = BindingStatus.of(BindingStatus.State.WRITE_ONLY, "");
            return;
        }
        apply(feed.current());
        if (def.mode() != UiNode.BindingMode.ONCE || !applied) {
            sub = feed.watch(this::apply);
        }
    }

    void stop() {
        sub.close();
        sub = Subscription.NONE;
    }

    private boolean targetExists() {
        if (!def.target().startsWith("prop:")) {
            return true;
        }
        String name = def.target().substring(5);
        if (el.descriptor().property(name) == null) {
            fail(Code.UNKNOWN_PROPERTY, true, el.type() + " has no property " + name);
            return false;
        }
        return true;
    }

    private void apply(DataState state) {
        if (def.mode() == UiNode.BindingMode.ONCE && applied) {
            return;
        }
        switch (state) {
            case DataState.Ready r when !(r.value() instanceof UiValue.Null) -> applyValue(r.value());
            case DataState.Ready r -> clearWith(BindingStatus.of(BindingStatus.State.NULL, ""));
            case DataState.Loading l -> clearWith(BindingStatus.LOADING);
            case DataState.Missing m -> clearWith(BindingStatus.MISSING);
            case DataState.Failed f -> {
                clearWith(BindingStatus.of(BindingStatus.State.FAILED, f.message()));
                binder.report(UiRuntimeDiagnostic.warning(Code.BINDING_SOURCE_FAILED, el.key(),
                    def.target() + " <- " + def.path() + ": " + f.message()));
            }
        }
    }

    private void applyValue(UiValue raw) {
        UiValue v = raw;
        if (converter != null) {
            try {
                v = converter.to().apply(raw);
            } catch (RuntimeException e) {
                fail(Code.CONVERTER_FAILED, true, "converter '" + def.converter() + "' failed: " + e);
                return;
            }
            String problem = v == null ? "returned nothing" : converter.result().problem(v);
            if (problem != null) {
                fail(Code.CONVERTER_FAILED, true, "converter '" + def.converter() + "' result: " + problem);
                return;
            }
        }
        String problem = targetProblem(v);
        if (problem != null) {
            fail(Code.BINDING_TYPE, true, def.target() + " <- " + def.path() + ": " + problem);
            return;
        }
        if (sink != null) {
            sink.accept(v);
        } else {
            binder.access().set(el, def.target(), v);
        }
        status = BindingStatus.of(BindingStatus.State.ACTIVE, "");
        applied = true;
        if (def.mode() == UiNode.BindingMode.ONCE) {
            stop();
        }
    }

    private String targetProblem(UiValue v) {
        String target = def.target();
        if (sink != null) {
            return null; // component parameters are checked by their declared types
        }
        if (target.startsWith("class:")) {
            return v instanceof UiValue.Bool ? null : "a class binding needs a bool, got " + v.typeName();
        }
        if (target.startsWith("prop:")) {
            PropertyDescriptor p = el.descriptor().property(target.substring(5));
            return p == null ? "no such property" : p.problem(v);
        }
        return null; // style values are validated by the cascade (STYLE_VALUE)
    }

    private void clearWith(BindingStatus s) {
        status = s;
        if (sink != null) {
            sink.accept(null);
        } else {
            binder.access().clear(el, def.target());
        }
    }

    private void fail(Code code, boolean clear, String message) {
        status = BindingStatus.of(BindingStatus.State.INVALID, message);
        if (clear) {
            if (sink != null) {
                sink.accept(null);
            } else {
                binder.access().clear(el, def.target());
            }
        }
        binder.report(UiRuntimeDiagnostic.error(code, el.key(), message));
    }

    // ── the way back (two-way, to-source) ───────────────────────────────────

    /** Stages a local edit of the target into the scope's draft. */
    void writeBack(UiValue edited) {
        DataPath path = feed.hostPath();
        if (path == null) {
            binder.report(UiRuntimeDiagnostic.error(Code.BINDING_NOT_WRITABLE, el.key(),
                def.target() + " -> " + def.path() + ": only host data can be edited"));
            return;
        }
        UiValue v = edited;
        if (converter != null) {
            if (converter.back() == null) {
                binder.report(UiRuntimeDiagnostic.error(Code.BINDING_NOT_WRITABLE, el.key(),
                    "converter '" + def.converter() + "' has no way back for " + def.mode().wire()));
                return;
            }
            v = converter.back().apply(edited);
        }
        CallSite site = binder.scope().site(el.key(), CallSite.Origin.BINDING);
        EditSession.Result r = binder.scope().edits().stage(path, v, site);
        if (r.accepted()) {
            el.setState(UiElement.INVALID, false);
            if (def.mode() == UiNode.BindingMode.TWO_WAY) {
                binder.access().clearLocal(el, def.target()); // the draft now shows through the binding
            }
        } else {
            el.setState(UiElement.INVALID, true); // the user's input stays visible to be fixed
        }
    }

    /** Rollback: the local edit goes, the committed (or drafted) value shows again. */
    void revertLocal() {
        binder.access().clearLocal(el, def.target());
        el.setState(UiElement.INVALID, false);
    }
}
