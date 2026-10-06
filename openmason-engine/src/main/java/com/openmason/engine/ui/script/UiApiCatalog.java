package com.openmason.engine.ui.script;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The {@code ui} API surface, version {@link #VERSION} (#292): one table that the LuaLS stubs
 * ({@link UiApiStubs}), the editor's static check ({@link UiScriptChecker}) and the drift test
 * against the prelude all read. Adding an API function means a prelude function and a row here.
 *
 * <p>Owners: {@code ui} (module functions), {@code ui.Element}, {@code ui.Canvas},
 * {@code ui.Handle}, {@code ui.Event}, and {@code module} (lifecycle hooks a code-behind defines).
 */
public final class UiApiCatalog {

    /** The {@code uiApi} a document targets; equals {@code OmuiFormat.UI_API_VERSION}. */
    public static final int VERSION = 1;

    /**
     * @param owner   {@code ui}, {@code ui.Element}, {@code ui.Canvas}, {@code ui.Handle},
     *                {@code ui.Event} or {@code module}
     * @param params  LuaLS parameters, {@code name type} pairs separated by commas ({@code ?} = optional)
     * @param returns LuaLS return type(s), empty for none
     */
    public record Member(String owner, String name, String params, String returns, String doc, boolean field) {

        static Member fn(String owner, String name, String params, String returns, String doc) {
            return new Member(owner, name, params, returns, doc, false);
        }

        static Member field(String owner, String name, String type, String doc) {
            return new Member(owner, name, "", type, doc, true);
        }
    }

    private static final String UI = "ui";
    private static final String EL = "ui.Element";
    private static final String CV = "ui.Canvas";
    private static final String HD = "ui.Handle";
    private static final String EV = "ui.Event";
    private static final String MOD = "module";

    public static final List<Member> MEMBERS = List.of(
        // ── ui ───────────────────────────────────────────────────────────────
        Member.field(UI, "api", "integer", "The ui API version this host implements."),
        Member.field(UI, "document", "string", "Logical id of this script's document."),
        Member.field(UI, "key", "string", "Element key of this script's scope: \"\" for a screen, the instance key for a component."),
        Member.field(UI, "root", EL, "The scope root: the screen's root element, or the component instance."),
        Member.fn(UI, "q", "selector string", EL, "First element in this scope matching a selector (#name, .class, Type, :state); nil when none matches."),
        Member.fn(UI, "qAll", "selector string", EL + "[]", "Every element in this scope matching a selector, in tree order."),
        Member.fn(UI, "get", "path string", EL, "Element by stable node-id path relative to this scope (survives renames); nil when absent."),
        Member.fn(UI, "param", "name string", "any", "A component parameter of this instance (nil in a screen script)."),
        Member.fn(UI, "params", "", "table|nil", "All component parameters of this instance."),
        Member.fn(UI, "read", "path string", "any, \"ready\"|\"loading\"|\"missing\"|\"failed\"", "Reads host data at an absolute path (session.online)."),
        Member.fn(UI, "watch", "path string, fn fun(value: any, state: string)", "{cancel: fun()}", "Calls fn at the next frame whenever the data at path changes (coalesced)."),
        Member.fn(UI, "action", "id string, args table?", HD, "Invokes a host action; ui.await the handle for its result."),
        Member.fn(UI, "request", "id string, args table?", HD, "Invokes a host action without waiting for it."),
        Member.fn(UI, "await", "handle " + HD, "any, string?", "Waits (inside a task) for an action, animation or timer: result, or nil and a message. Cancelled tasks never resume."),
        Member.fn(UI, "async", "fn function, ...any", "", "Starts fn as a task that may ui.await."),
        Member.fn(UI, "sleep", "seconds number", HD, "A timer handle that completes after the given UI time."),
        Member.fn(UI, "converter", "name string, spec {result: string, to: fun(v: any): any, back: (fun(v: any): any)?}", "", "Declares a pure binding converter; result is bool|int|number|string|color|asset|list|object|any (? = nullable)."),
        Member.fn(UI, "tween", "el " + EL + ", props table<string, any>, duration number?, easing string?, opts {delay: number?}?", HD, "Animates style properties through the host sampler (linear, ease-in, ease-out, ease-in-out, step)."),
        Member.fn(UI, "play", "clip string, opts {speed: number?, loop: (string|boolean)?, on_event: fun(name: string)?}?", HD, "Plays a timeline clip of this document."),
        Member.fn(UI, "stop", "handle " + HD, "", "Stops an animation (its values stay held)."),
        Member.fn(UI, "release", "el " + EL + ", property string", "", "Hands an animated style property back to the cascade."),
        Member.fn(UI, "sound", "id string, opts table?", "", "Asks the host to play a sound."),
        Member.fn(UI, "navigate", "target string, args table?", "", "Asks the host to navigate (an error when it cannot)."),
        Member.fn(UI, "close", "", "", "Asks the host to close this screen."),
        Member.fn(UI, "focus", "el " + EL, "boolean", "Moves keyboard focus to an element."),
        Member.fn(UI, "emit", "signal string, args table?", "", "Raises a signal the component declares (component scripts only)."),
        Member.fn(UI, "on", "name string, fn fun(args: table)", "integer", "Handles a custom event of this document, raised by its code-behind or its graphs (a task)."),
        Member.fn(UI, "raise", "name string, args table?", "", "Raises a custom event; its handlers (Lua ui.on and graph 'On Custom Event') run after the current handler."),
        Member.fn(UI, "time", "", "number", "Seconds of UI time since this script was opened."),
        Member.fn(UI, "log", "...any", "", "Writes to the script console (print does the same)."),
        Member.fn(UI, "warn", "...any", "", "Writes a warning to the script console."),
        // ── ui.Element ───────────────────────────────────────────────────────
        Member.field(EL, "key", "string", "Stable element key (node ids joined through instances)."),
        Member.fn(EL, "name", "", "string|nil", "The #name selector handle."),
        Member.fn(EL, "type", "", "string", "Widget type."),
        Member.fn(EL, "id", "", "string", "Node id."),
        Member.fn(EL, "exists", "", "boolean", "False once the element was removed."),
        Member.fn(EL, "parent", "", EL, "Parent inside this scope; nil at the scope root."),
        Member.fn(EL, "children", "", EL + "[]", "Children inside this scope."),
        Member.fn(EL, "q", "selector string", EL, "First matching descendant; nil when none matches."),
        Member.fn(EL, "qAll", "selector string", EL + "[]", "Every matching descendant."),
        Member.fn(EL, "prop", "name string", "any", "Effective widget property."),
        Member.fn(EL, "set", "name string, value any", EL, "Sets a widget property (validated; nil clears)."),
        Member.fn(EL, "clear", "name string", EL, "Clears the script's property value."),
        Member.fn(EL, "text", "", "string", "The text property."),
        Member.fn(EL, "setText", "text any", EL, "Sets the text property."),
        Member.fn(EL, "classes", "", "string[]", "Effective classes."),
        Member.fn(EL, "hasClass", "class string", "boolean", ""),
        Member.fn(EL, "addClass", "class string", EL, ""),
        Member.fn(EL, "removeClass", "class string", EL, ""),
        Member.fn(EL, "toggleClass", "class string, on boolean?", EL, "Flips the class when on is nil."),
        Member.fn(EL, "style", "property string, value any", EL, "Sets an inline style property (nil clears)."),
        Member.fn(EL, "clearStyle", "property string", EL, ""),
        Member.fn(EL, "computed", "property string", "any", "Computed style value."),
        Member.fn(EL, "hasState", "state string", "boolean", ""),
        Member.fn(EL, "setState", "state string, on boolean?", EL, "Sets a built-in or declared custom pseudo-state."),
        Member.fn(EL, "enabled", "", "boolean", ""),
        Member.fn(EL, "setEnabled", "on boolean?", EL, ""),
        Member.fn(EL, "focus", "", "boolean", ""),
        Member.fn(EL, "scrollTo", "x number, y number", EL, ""),
        Member.fn(EL, "rect", "", "number, number, number, number", "x, y, width, height in logical px."),
        Member.fn(EL, "on", "event string, fn fun(ev: " + EV + "|table), phase \"trickle\"?", "integer", "Registers a handler (a task) for a UI event or a component signal."),
        Member.fn(EL, "off", "event string, fn function|integer", EL, "Removes handlers."),
        Member.fn(EL, "canvas", "", CV, "The draw surface of a Canvas element."),
        // ── ui.Canvas ────────────────────────────────────────────────────────
        Member.field(CV, "capacity", "integer", "Buffer size in floats."),
        Member.fn(CV, "clear", "", CV, "Starts a new frame of commands."),
        Member.fn(CV, "rect", "x number, y number, w number, h number, rgb integer?, alpha number?", "", ""),
        Member.fn(CV, "circle", "x number, y number, r number, rgb integer?, alpha number?", "", ""),
        Member.fn(CV, "line", "x0 number, y0 number, x1 number, y1 number, width number?, rgb integer?, alpha number?", "", ""),
        Member.fn(CV, "sprite", "texture string|integer, x number, y number, w number, h number, u0 number?, v0 number?, u1 number?, v1 number?, alpha number?", "", "u/v in texture pixels; omitted = the whole texture."),
        Member.fn(CV, "text", "text string|integer, x number, y number, size number?, rgb integer?, alpha number?", "", "y is the baseline."),
        Member.fn(CV, "number", "value number, x number, y number, size number?, rgb integer?, alpha number?, decimals integer?", "", "Draws a number without building a string."),
        Member.fn(CV, "clip", "x number, y number, w number, h number", "", ""),
        Member.fn(CV, "unclip", "", "", ""),
        Member.fn(CV, "translate", "dx number, dy number", "", ""),
        Member.fn(CV, "resetTransform", "", "", ""),
        Member.fn(CV, "used", "", "integer", "Floats written this frame."),
        Member.fn(CV, "texture", "ref string", "integer", "Registers a texture once."),
        Member.fn(CV, "str", "text string", "integer", "Registers a string once."),
        Member.fn(CV, "size", "", "number, number", "Width and height in logical px."),
        // ── ui.Handle ────────────────────────────────────────────────────────
        Member.field(HD, "token", "integer", ""),
        Member.field(HD, "kind", "\"action\"|\"anim\"|\"timer\"", ""),
        Member.fn(HD, "done", "", "boolean", ""),
        Member.fn(HD, "cancel", "", "", ""),
        // ── ui.Event ─────────────────────────────────────────────────────────
        Member.field(EV, "type", "string", "click, pointer-down, key-down, ..."),
        Member.field(EV, "target", EL, ""),
        Member.field(EV, "current", EL, ""),
        Member.field(EV, "phase", "string", ""),
        Member.fn(EV, "stop", "", "", "Stops propagation."),
        Member.fn(EV, "stopImmediate", "", "", "Stops propagation and the remaining handlers here."),
        Member.fn(EV, "prevent", "", "", "Prevents the default action (consumes the input)."),
        Member.fn(EV, "accept", "", "", "Accepts a drop (drag-over / drag-drop)."),
        // ── module hooks ─────────────────────────────────────────────────────
        Member.fn(MOD, "on_open", "ui ui", "", "After the screen opens (a task: may ui.await)."),
        Member.fn(MOD, "on_close", "ui ui", "", "Before the screen closes (synchronous)."),
        Member.fn(MOD, "update", "dt number", "", "Once per frame."),
        Member.fn(MOD, "on_input", "ev " + EV, "boolean?", "Raw input before element handlers; true consumes it."),
        Member.fn(MOD, "on_reload", "ui ui", "", "After a hot reload (default: on_open runs again)."));

    private UiApiCatalog() {
    }

    public static List<Member> of(String owner) {
        return MEMBERS.stream().filter(m -> m.owner().equals(owner)).toList();
    }

    /** Names of the {@code ui} module's members. */
    public static Set<String> uiNames() {
        return of(UI).stream().map(Member::name).collect(Collectors.toUnmodifiableSet());
    }

    public static Set<String> hookNames() {
        return of(MOD).stream().map(Member::name).collect(Collectors.toUnmodifiableSet());
    }
}
