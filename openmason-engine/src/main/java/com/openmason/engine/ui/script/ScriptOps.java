package com.openmason.engine.ui.script;

import com.openmason.engine.cenda.LuaValueReader;
import com.openmason.engine.cenda.LuaValueWriter;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;
import com.openmason.engine.ui.data.ActionCall;
import com.openmason.engine.ui.data.CallSite;
import com.openmason.engine.ui.data.DataPath;
import com.openmason.engine.ui.data.DataState;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.anim.UiAnimator;
import com.openmason.engine.ui.runtime.anim.UiClocks;
import com.openmason.engine.ui.runtime.binding.UiConverter;
import com.openmason.engine.ui.runtime.input.EventCallbacks;
import com.openmason.engine.ui.runtime.input.InputDevice;
import com.openmason.engine.ui.runtime.input.UiEventHandler;
import com.openmason.engine.ui.runtime.input.UiEventType;
import com.openmason.engine.ui.runtime.widget.PropertyDescriptor;
import com.openmason.engine.ui.script.UiScriptDiagnostic.Code;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The host side of the {@code ui} API (#292): every {@code h(op, ...)} call a script's prelude
 * makes. Ops only reach what a document may reach: its own elements (a component script only its
 * own subtree), the data and actions its scope offers, the animation sampler and the host's
 * {@link UiScriptServices}. Nothing here hands Lua a Java object, a GL handle, a file or a game
 * object; values cross as plain data.
 *
 * <p>Results are {@code true, values...}; a refused or failed op is {@code false, message}, which
 * the prelude raises at the script's line.
 */
final class ScriptOps {

    /** A module that cannot be resolved; carries the diagnostic code. */
    static final class ModuleException extends RuntimeException {
        final Code code;

        ModuleException(Code code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Ops a converter (pure, read-only) may use. */
    private static final Set<String> PURE = Set.of("info", "parent", "children", "q", "qAll", "get", "prop",
        "classes", "hasClass", "hasState", "enabled", "computed", "rect", "param", "read", "log", "module",
        "trace", "traceValue");

    private final UiScriptRuntime rt;

    ScriptOps(UiScriptRuntime rt) {
        this.rt = rt;
    }

    int invoke(ScriptContext ctx, LuaValueReader in, LuaValueWriter out) {
        String op = in.string();
        try {
            // Read every argument before doing anything: an op may call back into the state.
            int n = in.remaining();
            UiValue[] a = new UiValue[n];
            for (int i = 0; i < n; i++) {
                a[i] = in.value();
            }
            if (op == null) {
                throw new IllegalArgumentException("missing op");
            }
            if (rt.pure > 0 && !PURE.contains(op)) {
                throw new IllegalStateException("converters are pure: ui." + op + " is not allowed here");
            }
            if (ctx.closed && !"log".equals(op)) {
                throw new IllegalStateException("this script's screen is closed");
            }
            out.bool(true);
            run(ctx, op, a, out);
        } catch (RuntimeException e) {
            out.reset().bool(false).string(e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return out.count();
    }

    private void run(ScriptContext ctx, String op, UiValue[] a, LuaValueWriter out) {
        switch (op) {
            // ── elements ────────────────────────────────────────────────────
            case "q" -> out.value(keyOrNil(query(ctx, a, true)));
            case "qAll" -> {
                List<UiElement> found = query(ctx, a, false);
                out.array(found.size());
                for (UiElement e : found) {
                    out.string(e.key());
                }
                out.end();
            }
            case "get" -> {
                String key = ctx.keyOf(str(a, 0, "path"));
                UiElement el = rt.ui.find(key);
                out.value(el != null && ctx.inScope(key) ? UiValue.of(key) : UiValue.NULL);
            }
            case "info" -> {
                String key = str(a, 0, "element");
                UiElement el = rt.ui.find(key);
                String what = str(a, 1, "field");
                if ("exists".equals(what)) {
                    out.bool(el != null && !el.isRemoved());
                    return;
                }
                el = element(ctx, key);
                switch (what) {
                    case "name" -> out.value(el.name() == null ? UiValue.NULL : UiValue.of(el.name()));
                    case "type" -> out.string(el.type());
                    case "id" -> out.string(el.id());
                    default -> throw new IllegalArgumentException("unknown element field " + what);
                }
            }
            case "parent" -> {
                UiElement p = element(ctx, str(a, 0, "element")).parent();
                out.value(p != null && ctx.inScope(p.key()) ? UiValue.of(p.key()) : UiValue.NULL);
            }
            case "children" -> {
                List<UiElement> kids = new ArrayList<>();
                for (UiElement c : element(ctx, str(a, 0, "element")).children()) {
                    if (ctx.inScope(c.key())) {
                        kids.add(c);
                    }
                }
                out.array(kids.size());
                for (UiElement c : kids) {
                    out.string(c.key());
                }
                out.end();
            }
            case "prop" -> out.value(element(ctx, str(a, 0, "element")).prop(str(a, 1, "property")));
            case "setProp" -> setProp(ctx, a);
            case "clearProp" -> {
                UiElement el = element(ctx, str(a, 0, "element"));
                rt.journal.prop(el, str(a, 1, "property"));
                el.clearProp(str(a, 1, "property"));
            }
            case "classes" -> {
                Set<String> classes = element(ctx, str(a, 0, "element")).classes();
                out.array(classes.size());
                for (String c : classes) {
                    out.string(c);
                }
                out.end();
            }
            case "hasClass" -> out.bool(element(ctx, str(a, 0, "element")).hasClass(str(a, 1, "class")));
            case "addClass", "removeClass", "toggleClass" -> classOp(ctx, op, a);
            case "setStyle" -> {
                UiElement el = element(ctx, str(a, 0, "element"));
                String p = str(a, 1, "property");
                ownership(el, "style:" + p);
                rt.journal.style(el, p);
                if (arg(a, 2) instanceof UiValue.Null) {
                    el.clearStyle(p);
                } else {
                    el.setStyle(p, arg(a, 2));
                }
            }
            case "clearStyle" -> {
                UiElement el = element(ctx, str(a, 0, "element"));
                rt.journal.style(el, str(a, 1, "property"));
                el.clearStyle(str(a, 1, "property"));
            }
            case "computed" -> {
                UiValue v = element(ctx, str(a, 0, "element")).computedStyle().get(str(a, 1, "property"));
                out.value(v == null ? UiValue.NULL : v);
            }
            case "hasState" -> out.bool(element(ctx, str(a, 0, "element")).hasState(str(a, 1, "state")));
            case "setState" -> {
                UiElement el = element(ctx, str(a, 0, "element"));
                String s = str(a, 1, "state");
                if (UiElement.DISABLED.equals(s)) {
                    rt.journal.enabled(el);
                } else {
                    rt.journal.state(el, s);
                }
                el.setState(s, bool(a, 2));
            }
            case "enabled" -> out.bool(element(ctx, str(a, 0, "element")).isEnabledInHierarchy());
            case "setEnabled" -> {
                UiElement el = element(ctx, str(a, 0, "element"));
                rt.journal.enabled(el);
                el.setEnabled(bool(a, 1));
            }
            case "focus" -> {
                UiElement el = element(ctx, str(a, 0, "element"));
                out.bool(rt.router != null && rt.router.focus().focus(el, InputDevice.PROGRAM));
            }
            case "scrollTo" -> element(ctx, str(a, 0, "element")).scrollTo((float) num(a, 1), (float) num(a, 2));
            case "rect" -> {
                UiRect r = element(ctx, str(a, 0, "element")).rect();
                float s = rt.ui.metrics().scale();
                s = s <= 0 ? 1 : s;
                out.number(r.x() / s).number(r.y() / s).number(r.width() / s).number(r.height() / s);
            }
            case "param" -> out.value(param(ctx, arg(a, 0) instanceof UiValue.Str s ? s.value() : null));

            // ── events and signals ──────────────────────────────────────────
            case "on" -> on(ctx, a);
            case "off" -> off(ctx, a);
            case "emit" -> emit(ctx, a);

            // ── data and actions (#289) ─────────────────────────────────────
            case "read" -> {
                DataState st = scope().read(DataPath.parse(str(a, 0, "path")));
                out.value(st.valueOrNull()).string(stateName(st));
            }
            case "watch" -> {
                int id = (int) num(a, 1);
                DataPath path = DataPath.parse(str(a, 0, "path"));
                if (ctx.watches.size() >= UiScriptRuntime.MAX_WATCHES) {
                    throw new IllegalStateException("this script holds " + UiScriptRuntime.MAX_WATCHES
                        + " watches; cancel some before watching more");
                }
                var sub = scope().watch(path, st -> rt.queueWatch(ctx, id, st));
                ctx.watches.put(id, sub);
                rt.journal.callback(() -> {
                    sub.close();
                    ctx.watches.remove(id);
                });
            }
            case "unwatch" -> {
                var sub = ctx.watches.remove((int) num(a, 0));
                if (sub != null) {
                    sub.close();
                }
            }
            case "action" -> {
                UiValue args = arg(a, 1);
                UiValue.Obj obj = args instanceof UiValue.Obj o ? o
                    : args instanceof UiValue.Arr arr && arr.items().isEmpty() ? UiValue.Obj.EMPTY : null;
                if (obj == null) {
                    throw new IllegalArgumentException("action arguments must be a table of named fields");
                }
                rt.admitHandle(ctx);
                ActionCall call = scope().invoke(str(a, 0, "action"), obj,
                    site(ctx, arg(a, 2) instanceof UiValue.Str trace ? trace.value() : null));
                long token = rt.token();
                rt.trackAction(ctx, token, call);
                out.integer(token);
            }
            case "cancelAction" -> out.bool(rt.cancelAction(ctx, (long) num(a, 0)));
            case "sleep" -> {
                rt.admitHandle(ctx);
                long token = rt.token();
                rt.addTimer(ctx, token, num(a, 0));
                rt.journal.callback(() -> rt.removeTimer(ctx, token));
                out.integer(token);
            }
            case "converter" -> converter(ctx, a);

            // ── animation ───────────────────────────────────────────────────
            case "tween", "play" -> {
                rt.admitHandle(ctx);
                long token = "tween".equals(op) ? tween(ctx, a) : play(ctx, a);
                rt.trackAnim(ctx, token);
                // A failed handler's animation must not re-claim the values its rollback restores.
                rt.journal.callback(() -> rt.animator.stop(token, UiAnimator.StopMode.RELEASE));
                out.integer(token);
            }
            case "stopAnim" -> {
                long token = (long) num(a, 0);
                out.bool(rt.ownsAnim(ctx, token) && rt.animator.stop(token, stopMode(arg(a, 1))));
            }
            case "stopClip" -> {
                boolean any = false;
                for (long t : rt.animator.clipTokens(ctx, str(a, 0, "clip"))) {
                    any |= rt.animator.stop(t, stopMode(arg(a, 1)));
                }
                out.bool(any);
            }
            case "seekAnim", "speedAnim" -> {
                boolean any = false;
                double v = num(a, 2);
                for (long t : animTokens(ctx, a)) {
                    any |= "seekAnim".equals(op) ? rt.animator.seek(t, v) : rt.animator.setSpeed(t, v);
                }
                out.bool(any);
            }
            case "release" -> {
                String key = str(a, 0, "element");
                element(ctx, key);
                rt.animator.release(key, str(a, 1, "property"));
            }
            case "machineSet" -> {
                rt.admitHandle(ctx);
                long token = rt.ui.stateMachines().set(ctx.isScreen() ? "" : ctx.scopeKey, str(a, 0, "machine"),
                    str(a, 1, "state"), rt.animListener(ctx));
                if (token == 0) { // reached at once: a handle that settles next frame
                    token = rt.token();
                    rt.addTimer(ctx, token, 0);
                } else {
                    rt.trackAnim(ctx, token);
                }
                out.integer(token);
            }
            case "machineState" -> {
                String st = rt.ui.stateMachines().state(ctx.isScreen() ? "" : ctx.scopeKey, str(a, 0, "machine"));
                out.value(st == null ? UiValue.NULL : UiValue.of(st));
            }
            case "clock" -> out.number(rt.ui.clocks().now(arg(a, 0) instanceof UiValue.Str c ? c.value() : "ui"));

            // ── host services ───────────────────────────────────────────────
            case "sound" -> rt.services.playSound(str(a, 0, "sound"), obj(a, 1));
            case "navigate" -> {
                if (!rt.services.navigate(str(a, 0, "target"), obj(a, 1))) {
                    throw new IllegalStateException("this host cannot navigate to " + str(a, 0, "target"));
                }
            }
            case "close" -> rt.services.requestClose();
            case "log" -> rt.log(ctx, str(a, 0, "level"), arg(a, 1) instanceof UiValue.Str s ? s.value() : "");

            // ── custom events and graph debugging (#291) ────────────────────
            case "raise" -> str(a, 0, "event"); // the Lua side queues it; this only enforces purity
            case "trace" -> {
                Long token = rt.debugger == null ? null
                    : rt.debugger.onHit(ctx, str(a, 0, "graph"), str(a, 1, "node"), bool(a, 2));
                if (token == null) {
                    out.nil();
                } else {
                    out.integer(token);
                }
            }
            case "traceValue" -> {
                if (rt.debugger != null) {
                    rt.debugger.onValue(str(a, 0, "graph"), str(a, 1, "node"), str(a, 2, "port"), arg(a, 3));
                }
            }

            // ── modules and canvases ────────────────────────────────────────
            case "module" -> {
                String[] m = resolveModule(ctx, str(a, 0, "module"));
                out.string(m[0]).string(m[1]);
            }
            case "canvasTexture" -> out.integer(canvas(ctx, a).texture(str(a, 1, "texture")));
            case "canvasString" -> out.integer(canvas(ctx, a).string(str(a, 1, "string")));
            default -> throw new IllegalArgumentException("unknown ui op " + op);
        }
    }

    // ── elements ────────────────────────────────────────────────────────────

    private UiElement element(ScriptContext ctx, String key) {
        UiElement el = rt.ui.find(key);
        if (el == null || el.isRemoved()) {
            throw new IllegalArgumentException("no element " + key);
        }
        if (!ctx.inScope(key)) {
            throw new IllegalArgumentException("element " + key + " is outside this component's scope");
        }
        return el;
    }

    /** Elements matching a selector inside the scope (or under the given element), in pre-order. */
    private List<UiElement> query(ScriptContext ctx, UiValue[] a, boolean first) {
        UiElement base = arg(a, 0) instanceof UiValue.Str s ? element(ctx, s.value()) : rt.ui.find(ctx.rootKey);
        String selector = str(a, 1, "selector");
        List<UiElement> out = new ArrayList<>();
        for (UiElement e : rt.ui.qAll(selector)) {
            if (ctx.inScope(e.key()) && within(e, base)) {
                out.add(e);
                if (first) {
                    break;
                }
            }
        }
        return out;
    }

    private static boolean within(UiElement e, UiElement base) {
        if (base == null) {
            return true;
        }
        for (UiElement p = e; p != null; p = p.parent()) {
            if (p == base) {
                return true;
            }
        }
        return false;
    }

    private static UiValue keyOrNil(List<UiElement> found) {
        return found.isEmpty() ? UiValue.NULL : UiValue.of(found.getFirst().key());
    }

    private void setProp(ScriptContext ctx, UiValue[] a) {
        UiElement el = element(ctx, str(a, 0, "element"));
        String name = str(a, 1, "property");
        UiValue value = arg(a, 2);
        PropertyDescriptor p = el.descriptor().property(name);
        if (p == null) {
            throw new IllegalArgumentException(el.type() + " has no property " + name);
        }
        if (value instanceof UiValue.Null) {
            rt.journal.prop(el, name);
            el.clearProp(name);
            return;
        }
        String problem = p.problem(value);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        ownership(el, "prop:" + name);
        rt.journal.prop(el, name);
        el.setProp(name, value);
    }

    private void classOp(ScriptContext ctx, String op, UiValue[] a) {
        UiElement el = element(ctx, str(a, 0, "element"));
        String c = str(a, 1, "class");
        ownership(el, "class:" + c);
        rt.journal.cls(el, c);
        switch (op) {
            case "addClass" -> el.addClass(c);
            case "removeClass" -> el.removeClass(c);
            default -> el.toggleClass(c, arg(a, 2) instanceof UiValue.Str ? !el.hasClass(c) : bool(a, 2));
        }
    }

    /** A target owned by a to-target/once binding refuses local writes (#289): say so to the script. */
    private static void ownership(UiElement el, String target) {
        if (el.isBound(target)) {
            UiNode.BindingMode mode = el.bindingMode(target);
            if (mode == UiNode.BindingMode.TO_TARGET || mode == UiNode.BindingMode.ONCE) {
                throw new IllegalStateException(target + " on " + el.key() + " is owned by its " + mode.wire()
                    + " binding; local writes are refused");
            }
        }
    }

    private UiValue param(ScriptContext ctx, String name) {
        UiElement inst = rt.ui.find(ctx.rootKey);
        UiValue.Obj params = inst == null ? null : inst.componentParams();
        if (params == null) {
            return UiValue.NULL;
        }
        if (name == null) {
            return params;
        }
        UiValue v = params.get(name);
        return v == null ? UiValue.NULL : v;
    }

    // ── events and signals ──────────────────────────────────────────────────

    private void on(ScriptContext ctx, UiValue[] a) {
        String key = str(a, 0, "element");
        UiElement el = element(ctx, key);
        String event = str(a, 1, "event");
        int id = (int) num(a, 2);
        if (ctx.handlers.size() + ctx.signals.size() >= UiScriptRuntime.MAX_HANDLERS) {
            throw new IllegalStateException("this script registered " + UiScriptRuntime.MAX_HANDLERS
                + " handlers; remove some (el:off) before adding more");
        }
        UiEventType type = ScriptEvents.type(event);
        if (type == null) {
            if (signals(el).contains(event)) {
                rt.addSignalListener(ctx, new ScriptContext.SignalListener(id, key, event));
                rt.journal.callback(() -> rt.removeSignalListener(ctx, id));
                return;
            }
            throw new IllegalArgumentException("unknown event '" + event + "'"
                + (UiNode.INSTANCE_TYPE.equals(el.type()) ? " (and not a signal of this component)" : "")
                + "; events: " + ScriptEvents.names());
        }
        EventCallbacks.Phase phase = bool(a, 3) ? EventCallbacks.Phase.TRICKLE_DOWN : EventCallbacks.Phase.BUBBLE_UP;
        UiEventHandler java = ev -> rt.onEvent(ctx, id, ev);
        el.on(type, java, phase);
        ctx.handlers.put(id, new ScriptContext.Handler(id, key, type, phase, el, java));
        rt.journal.callback(() -> {
            ScriptContext.Handler h = ctx.handlers.remove(id);
            if (h != null) {
                h.element().off(h.type(), h.java(), h.phase());
            }
        });
    }

    private void off(ScriptContext ctx, UiValue[] a) {
        int id = (int) num(a, 2);
        ScriptContext.Handler h = ctx.handlers.remove(id);
        if (h != null) {
            h.element().off(h.type(), h.java(), h.phase());
        } else {
            rt.removeSignalListener(ctx, id);
        }
    }

    /** Signals the component instance {@code el} declares, or none. */
    private List<String> signals(UiElement el) {
        UiDocument.ComponentDef def = contract(el);
        return def == null ? List.of() : def.events().stream().map(UiDocument.EventDef::name).toList();
    }

    private UiDocument.ComponentDef contract(UiElement el) {
        if (!UiNode.INSTANCE_TYPE.equals(el.type()) || el.node().instance() == null) {
            return null;
        }
        OmuiArchive comp = rt.ui.context().source().component(el.node().instance().component());
        return comp == null ? null : comp.document().component();
    }

    private void emit(ScriptContext ctx, UiValue[] a) {
        if (ctx.isScreen()) {
            throw new IllegalStateException("ui.emit raises a component's signal; a screen script has none");
        }
        String signal = str(a, 0, "signal");
        UiValue.Obj args = obj(a, 1);
        UiDocument.ComponentDef def = ctx.archive.document().component();
        UiDocument.EventDef event = def == null ? null : def.events().stream()
            .filter(e -> e.name().equals(signal)).findFirst().orElse(null);
        if (event == null) {
            throw new IllegalArgumentException("component " + ctx.documentId() + " declares no signal '" + signal + "'");
        }
        for (UiDocument.Param p : event.args()) {
            UiValue v = args.get(p.name());
            String problem = DataType.of(p.type()).orNull().problem(v == null ? UiValue.NULL : v, p.name());
            if (problem != null) {
                throw new IllegalArgumentException("signal " + signal + ": " + problem);
            }
        }
        for (String k : args.fields().keySet()) {
            if (event.args().stream().noneMatch(p -> p.name().equals(k))) {
                throw new IllegalArgumentException("signal " + signal + " has no argument " + k);
            }
        }
        rt.signal(ctx.rootKey, signal, args);
    }

    // ── converters ──────────────────────────────────────────────────────────

    private void converter(ScriptContext ctx, UiValue[] a) {
        String name = str(a, 0, "name");
        DataType result = dataType(str(a, 1, "result"));
        boolean hasBack = bool(a, 2);
        UiConverter c = new UiConverter(result, v -> convert(ctx, name, false, v),
            hasBack ? v -> convert(ctx, name, true, v) : null);
        ctx.converters.put(name, c);
        ctx.disabledConverters.remove(name);
    }

    private UiValue convert(ScriptContext ctx, String name, boolean back, UiValue v) {
        if (ctx.disabledConverters.contains(name)) {
            throw new IllegalStateException("converter " + name + " is disabled after an error");
        }
        return rt.convert(ctx, name, back, v);
    }

    static void disableConverter(ScriptContext ctx, String name) {
        ctx.disabledConverters.add(name);
    }

    static DataType dataType(String name) {
        if (name == null || name.isEmpty() || "any".equals(name)) {
            return DataType.ANY;
        }
        boolean nullable = name.endsWith("?");
        String base = nullable ? name.substring(0, name.length() - 1) : name;
        ValueType t;
        try {
            t = ValueType.fromWire(base);
        } catch (RuntimeException e) {
            t = null;
        }
        if (t == null) {
            throw new IllegalArgumentException("unknown converter result type '" + name
                + "' (bool, int, number, string, color, asset, list, object or any; ? makes it nullable)");
        }
        DataType d = DataType.of(t);
        return nullable ? d.orNull() : d;
    }

    // ── animation ───────────────────────────────────────────────────────────

    private long tween(ScriptContext ctx, UiValue[] a) {
        String key = str(a, 0, "element");
        element(ctx, key);
        if (!(arg(a, 1) instanceof UiValue.Obj props) || props.isEmpty()) {
            throw new IllegalArgumentException("tween(el, props, ...): props must name style properties");
        }
        String name = arg(a, 3) instanceof UiValue.Str s ? s.value() : "linear";
        com.openmason.engine.format.omui.UiBezier bezier = com.openmason.engine.format.omui.UiBezier.parse(name);
        if (bezier != null && bezier.problem() != null) {
            throw new IllegalArgumentException(bezier.problem());
        }
        UiEasing easing = bezier != null ? UiEasing.LINEAR : easing(name);
        UiValue.Obj opts = obj(a, 4);
        double delay = opts.get("delay") instanceof UiValue.Num d ? d.value() : 0;
        Map<String, UiValue> from = opts.get("from") instanceof UiValue.Obj f ? f.fields() : Map.of();
        UiAnimator.TweenOptions o = new UiAnimator.TweenOptions(delay, clock(opts), fill(opts), from, bezier);
        return rt.animator.tween(ctx, key, props.fields(), num(a, 2), easing, o, rt.animListener(ctx));
    }

    private long play(ScriptContext ctx, UiValue[] a) {
        UiValue.Obj opts = obj(a, 1);
        UiAnimationClip clip = clip(ctx, str(a, 0, "clip"));
        if (rt.ui.preferences().reducedMotion() && opts.get("reduced") instanceof UiValue.Str alt) {
            clip = clip(ctx, alt.value()); // the declared reduced-motion alternate
        }
        double speed = opts.get("speed") instanceof UiValue.Num s ? s.value() : 1;
        UiAnimationClip.LoopMode loop = null;
        if (opts.get("loop") instanceof UiValue.Str l) {
            loop = switch (l.value()) {
                case "once" -> UiAnimationClip.LoopMode.ONCE;
                case "loop" -> UiAnimationClip.LoopMode.LOOP;
                case "ping-pong" -> UiAnimationClip.LoopMode.PING_PONG;
                default -> throw new IllegalArgumentException("loop must be once, loop or ping-pong");
            };
        } else if (opts.get("loop") instanceof UiValue.Bool b) {
            loop = b.value() ? UiAnimationClip.LoopMode.LOOP : UiAnimationClip.LoopMode.ONCE;
        }
        double blend = opts.get("blend") instanceof UiValue.Num b ? b.value() : 0;
        double at = opts.get("at") instanceof UiValue.Num t ? t.value() : 0;
        boolean restart = !(opts.get("restart") instanceof UiValue.Bool r) || r.value();
        UiAnimator.PlayOptions o = new UiAnimator.PlayOptions(clock(opts), speed, loop, blend, fill(opts), at, restart);
        return rt.animator.play(ctx, clip, ctx::keyOf, o, rt.animListener(ctx));
    }

    private static UiAnimationClip clip(ScriptContext ctx, String id) {
        UiAnimationClip clip = ctx.archive.animations().get(id);
        if (clip == null) {
            throw new IllegalArgumentException("no animation clip '" + id + "' in " + ctx.documentId()
                + " (clips: " + ctx.archive.animations().keySet() + ")");
        }
        return clip;
    }

    /** {@code opts.clock}: "ui" (default), "game" or a clock the host defined. */
    private String clock(UiValue.Obj opts) {
        String c = opts.get("clock") instanceof UiValue.Str s ? s.value() : UiClocks.UI;
        if (!rt.ui.clocks().has(c)) {
            throw new IllegalArgumentException("no clock '" + c + "' (clocks: " + rt.ui.clocks().names() + ")");
        }
        return c;
    }

    private static UiAnimator.Fill fill(UiValue.Obj opts) {
        if (!(opts.get("fill") instanceof UiValue.Str f)) {
            return UiAnimator.Fill.HOLD;
        }
        return switch (f.value()) {
            case "hold" -> UiAnimator.Fill.HOLD;
            case "release" -> UiAnimator.Fill.RELEASE;
            default -> throw new IllegalArgumentException("fill must be hold or release");
        };
    }

    private static UiAnimator.StopMode stopMode(UiValue v) {
        if (!(v instanceof UiValue.Str m)) {
            return UiAnimator.StopMode.HOLD;
        }
        return switch (m.value()) {
            case "hold" -> UiAnimator.StopMode.HOLD;
            case "end" -> UiAnimator.StopMode.END;
            case "release" -> UiAnimator.StopMode.RELEASE;
            default -> throw new IllegalArgumentException("stop mode must be hold, end or release");
        };
    }

    /** {@code (token, nil, x)} names one handle, {@code (nil, clipId, x)} this context's playbacks of a clip. */
    private List<Long> animTokens(ScriptContext ctx, UiValue[] a) {
        if (arg(a, 0) instanceof UiValue.Num t) {
            long token = (long) t.value();
            return rt.ownsAnim(ctx, token) ? List.of(token) : List.of();
        }
        return rt.animator.clipTokens(ctx, str(a, 1, "clip"));
    }

    static UiEasing easing(String name) {
        for (UiEasing e : UiEasing.values()) {
            if (e.wire().equals(name)) {
                return e;
            }
        }
        throw new IllegalArgumentException("unknown easing '" + name
            + "' (linear, ease-in, ease-out, ease-in-out, step or cubic-bezier(x1, y1, x2, y2))");
    }

    // ── modules ─────────────────────────────────────────────────────────────

    /**
     * Source and chunk name of a module: an in-archive part ({@code scripts/<id>.lua}) of the
     * context's document, or a dependency of kind {@code script} it declares (#285 resolution, so
     * it follows relocation and portable export). Nothing else is reachable.
     */
    String[] resolveModule(ScriptContext ctx, String name) {
        String source;
        String chunk;
        if (name.indexOf(':') < 0) {
            source = ctx.archive.scripts().get(name);
            if (source == null) {
                throw new ModuleException(Code.MODULE_NOT_FOUND, "no script scripts/" + name + ".lua in "
                    + ctx.documentId());
            }
            chunk = name + ".lua";
        } else {
            UiDependency dep = ctx.archive.dependencies().find(name);
            if (!UiScriptRuntime.isScript(dep)) {
                throw new ModuleException(Code.UNDECLARED_MODULE, "module " + name
                    + " is not a declared script dependency of " + ctx.documentId());
            }
            source = rt.ui.context().source().script(name);
            if (source == null) {
                throw new ModuleException(Code.MODULE_NOT_FOUND, "script dependency " + name + " of "
                    + ctx.documentId() + " cannot be resolved");
            }
            chunk = name;
        }
        if (!source.isEmpty() && source.charAt(0) == '\u001b') {
            throw new ModuleException(Code.BINARY_SCRIPT, "module " + name + " is a binary chunk; only Lua source runs");
        }
        return new String[]{source, chunk};
    }

    private ScriptCanvas canvas(ScriptContext ctx, UiValue[] a) {
        ScriptCanvas c = ctx.canvases.get(str(a, 0, "canvas"));
        if (c == null) {
            throw new IllegalArgumentException("element " + str(a, 0, "canvas") + " is not a Canvas of this script");
        }
        return c;
    }

    /**
     * Where an action call came from (#289): the graph node when a compiled graph frame is on the
     * Lua stack ({@code trace}, sent by the prelude only for documents with graphs), else the
     * element whose handler is running, else the script's scope root.
     */
    private CallSite site(ScriptContext ctx, String trace) {
        String node = rt.graphNodeAt(ctx, trace);
        if (node != null) {
            return scope().site(node, CallSite.Origin.GRAPH);
        }
        return scope().site(rt.currentElement != null ? rt.currentElement : ctx.rootKey, CallSite.Origin.SCRIPT);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private com.openmason.engine.ui.data.UiScope scope() {
        if (rt.scope == null) {
            throw new IllegalStateException("this document is not bound to a host: no data or actions");
        }
        return rt.scope;
    }

    static String stateName(DataState st) {
        return switch (st) {
            case DataState.Ready r -> "ready";
            case DataState.Failed f -> "failed";
            default -> st == DataState.LOADING ? "loading" : "missing";
        };
    }

    private static UiValue arg(UiValue[] a, int i) {
        return i < a.length ? a[i] : UiValue.NULL;
    }

    private static String str(UiValue[] a, int i, String what) {
        if (arg(a, i) instanceof UiValue.Str s) {
            return s.value();
        }
        throw new IllegalArgumentException(what + " must be a string, got " + arg(a, i).typeName());
    }

    private static double num(UiValue[] a, int i) {
        if (arg(a, i) instanceof UiValue.Num n) {
            return n.value();
        }
        throw new IllegalArgumentException("expected a number, got " + arg(a, i).typeName());
    }

    private static boolean bool(UiValue[] a, int i) {
        return arg(a, i) instanceof UiValue.Bool b && b.value();
    }

    private static UiValue.Obj obj(UiValue[] a, int i) {
        UiValue v = arg(a, i);
        if (v instanceof UiValue.Obj o) {
            return o;
        }
        if (v instanceof UiValue.Null || v instanceof UiValue.Arr arr && arr.items().isEmpty()) {
            return UiValue.Obj.EMPTY;
        }
        throw new IllegalArgumentException("expected a table of named fields, got " + v.typeName());
    }

    static Map<String, UiValue> fields(UiValue v) {
        return v instanceof UiValue.Obj o ? o.fields() : Map.of();
    }
}
