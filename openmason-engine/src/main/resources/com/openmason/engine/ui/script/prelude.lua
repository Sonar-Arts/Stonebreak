-- Cenda UI prelude: the Lua side of the `ui` API (#292, uiApi 1).
--
-- Runs once per lua_State, in the shared globals, before any environment exists. It leaves two
-- globals, __cenda_ui_factory and __cenda_ui_dispatch, which the host takes as registry refs and
-- then removes, so no script can reach them (or the traceback helper captured below). Everything
-- a script sees lives in its own environment: `ui`, `require`, `print` and the curated stdlib.
--
-- Host calls go through h(op, ...), which returns true, results... or false, message. Every API
-- function that forwards an error raises it at the script's line (level 3: call -> API -> script).

local traceback = __cenda_traceback
__cenda_traceback = nil

local type, error, select, setmetatable, rawget, rawset, pairs, tostring, xpcall, load =
    type, error, select, setmetatable, rawget, rawset, pairs, tostring, xpcall, load
local co_create, co_resume, co_status, co_yield, co_close, co_running =
    coroutine.create, coroutine.resume, coroutine.status, coroutine.yield, coroutine.close, coroutine.running
local concat, tremove = table.concat, table.remove

-- Ops the host sends to __cenda_ui_dispatch(ctx, op, ...); mirrored in UiScriptRuntime.Op.
local OP_LOAD, OP_OPEN, OP_CLOSE, OP_UPDATE, OP_INPUT, OP_EVENT, OP_SETTLE, OP_CANCEL_ALL,
      OP_CONVERT, OP_WATCH, OP_ANIM_EVENT, OP_DROP_HANDLER, OP_SIGNAL, OP_RELOAD, OP_GRAPH_LOAD, OP_GRAPH_HOOK =
      1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16

-- Custom events one dispatch may deliver (ui.raise); more is a raise loop and fails the dispatch.
local MAX_RAISED = 256

local AWAIT = {} -- yielded by ui.await; any other yield from a task is a script error

local contexts = {}

local function on_error(e)
    return traceback(co_running(), tostring(e))
end

-- ───────────────────────────── elements ─────────────────────────────

-- Every shared metatable hides itself (__metatable): environments share these method tables, so
-- a script that could reach one could change another instance's behaviour.
local Element = {}
Element.__index = Element
Element.__name = "ui.Element"
Element.__metatable = "ui.Element"
Element.__tostring = function(e) return "ui.Element(" .. e.key .. ")" end

local function element(ctx, key)
    if key == nil then
        return nil
    end
    local e = ctx.els[key]
    if e == nil then
        e = setmetatable({ key = key, __ctx = ctx }, Element)
        ctx.els[key] = e
    end
    return e
end

local function elements(ctx, keys)
    local out = {}
    for i = 1, #keys do
        out[i] = element(ctx, keys[i])
    end
    return out
end

local function key_of(v, level)
    if type(v) == "table" and rawget(v, "__ctx") ~= nil then
        return v.key
    end
    if type(v) == "string" then
        return v
    end
    error("expected a ui.Element", level + 1)
end

function Element:name() return (self.__ctx.call("info", self.key, "name")) end
function Element:type() return (self.__ctx.call("info", self.key, "type")) end
function Element:id() return (self.__ctx.call("info", self.key, "id")) end
function Element:exists() return (self.__ctx.call("info", self.key, "exists")) end
function Element:parent() return element(self.__ctx, (self.__ctx.call("parent", self.key))) end
function Element:children() return elements(self.__ctx, (self.__ctx.call("children", self.key))) end
function Element:q(selector) return element(self.__ctx, (self.__ctx.call("q", self.key, selector))) end
function Element:qAll(selector) return elements(self.__ctx, (self.__ctx.call("qAll", self.key, selector))) end

function Element:prop(name) return (self.__ctx.call("prop", self.key, name)) end
function Element:set(name, value) self.__ctx.call("setProp", self.key, name, value) return self end
function Element:clear(name) self.__ctx.call("clearProp", self.key, name) return self end
function Element:text() return (self.__ctx.call("prop", self.key, "text")) end
function Element:setText(s) self.__ctx.call("setProp", self.key, "text", tostring(s)) return self end

function Element:classes() return (self.__ctx.call("classes", self.key)) end
function Element:hasClass(c) return (self.__ctx.call("hasClass", self.key, c)) end
function Element:addClass(c) self.__ctx.call("addClass", self.key, c) return self end
function Element:removeClass(c) self.__ctx.call("removeClass", self.key, c) return self end
function Element:toggleClass(c, on)
    self.__ctx.call("toggleClass", self.key, c, on == nil and "flip" or on and true or false)
    return self
end

function Element:style(property, value) self.__ctx.call("setStyle", self.key, property, value) return self end
function Element:clearStyle(property) self.__ctx.call("clearStyle", self.key, property) return self end
function Element:computed(property) return (self.__ctx.call("computed", self.key, property)) end

function Element:hasState(s) return (self.__ctx.call("hasState", self.key, s)) end
function Element:setState(s, on) self.__ctx.call("setState", self.key, s, on ~= false) return self end
function Element:enabled() return (self.__ctx.call("enabled", self.key)) end
function Element:setEnabled(on) self.__ctx.call("setEnabled", self.key, on ~= false) return self end
function Element:focus() return (self.__ctx.call("focus", self.key)) end
function Element:scrollTo(x, y) self.__ctx.call("scrollTo", self.key, x, y) return self end

function Element:rect()
    local x, y, w, h = self.__ctx.call4("rect", self.key)
    return x, y, w, h
end

-- el:on(event, fn [, "trickle"]): event is a UI event ("click", "pointer-down", "key-down", ...)
-- or, on a component instance, one of the component's declared signals.
function Element:on(event, fn, phase)
    if type(fn) ~= "function" then
        error("on(event, fn): fn must be a function", 2)
    end
    local ctx = self.__ctx
    local id = ctx.next_id + 1
    ctx.next_id = id
    ctx.call("on", self.key, event, id, phase == "trickle")
    ctx.handlers[id] = fn
    local list = ctx.by_fn[fn]
    if list == nil then
        list = {}
        ctx.by_fn[fn] = list
    end
    list[#list + 1] = { key = self.key, event = event, id = id }
    return id
end

-- el:off(event, fn | id)
function Element:off(event, fn)
    local ctx = self.__ctx
    if type(fn) == "number" then
        if ctx.handlers[fn] ~= nil then
            ctx.call("off", self.key, event, fn)
            ctx.handlers[fn] = nil
        end
        return self
    end
    local list = ctx.by_fn[fn]
    if list == nil then
        return self
    end
    for i = #list, 1, -1 do
        local r = list[i]
        if r.key == self.key and r.event == event then
            ctx.call("off", r.key, r.event, r.id)
            ctx.handlers[r.id] = nil
            tremove(list, i)
        end
    end
    return self
end

-- ───────────────────────────── canvas ─────────────────────────────
--
-- Draw commands go straight into a native float buffer the painter reads: no host crossing per
-- command. Coordinates are logical px from the canvas's top-left; colours are 0xRRGGBB plus an
-- alpha in [0, 1]. Command layout is mirrored in engine ui.runtime.paint.CanvasPainter.

local Canvas = {}
Canvas.__index = Canvas
Canvas.__name = "ui.Canvas"
Canvas.__metatable = "ui.Canvas"

local C_RECT, C_CIRCLE, C_LINE, C_SPRITE, C_TEXT, C_NUMBER, C_CLIP, C_UNCLIP, C_TRANSLATE, C_RESET =
      2, 3, 4, 5, 6, 7, 8, 9, 10, 11

local function new_canvas(ctx, key, buf)
    return setmetatable({ key = key, ctx = ctx, emit = buf.emit, reset = buf.reset, cursor = buf.cursor,
        capacity = buf.len(), strings = {}, textures = {} }, Canvas)
end

-- Text and textures are registered once and referenced by number; a string argument is
-- registered on first use and cached, so steady-state drawing never crosses to the host.
local function string_id(c, s)
    if type(s) == "number" then
        return s
    end
    local id = c.strings[s]
    if id == nil then
        id = c.ctx.call("canvasString", c.key, tostring(s))
        c.strings[s] = id
    end
    return id
end

local function texture_id(c, t)
    if type(t) == "number" then
        return t
    end
    local id = c.textures[t]
    if id == nil then
        id = c.ctx.call("canvasTexture", c.key, t)
        c.textures[t] = id
    end
    return id
end

function Canvas:clear() self.reset() return self end
function Canvas:rect(x, y, w, h, rgb, a) self.emit(C_RECT, x, y, w, h, rgb or 0xffffff, a or 1) end
function Canvas:circle(x, y, r, rgb, a) self.emit(C_CIRCLE, x, y, r, rgb or 0xffffff, a or 1) end
function Canvas:line(x0, y0, x1, y1, width, rgb, a)
    self.emit(C_LINE, x0, y0, x1, y1, width or 1, rgb or 0xffffff, a or 1)
end
-- sprite(texture, x, y, w, h [, u0, v0, u1, v1 [, alpha]]): texture is an asset ref or the id
-- canvas:texture returned; u/v in texture pixels; omitted = the whole texture
function Canvas:sprite(tex, x, y, w, h, u0, v0, u1, v1, a)
    self.emit(C_SPRITE, texture_id(self, tex), x, y, w, h, u0 or 0, v0 or 0, u1 or -1, v1 or -1, a or 1)
end
-- text(s, x, y [, size [, rgb [, a]]]): y is the baseline
function Canvas:text(s, x, y, size, rgb, a)
    self.emit(C_TEXT, string_id(self, s), x, y, size or 16, rgb or 0xffffff, a or 1)
end
function Canvas:number(value, x, y, size, rgb, a, decimals)
    self.emit(C_NUMBER, value, x, y, size or 16, rgb or 0xffffff, a or 1, decimals or 0)
end
function Canvas:clip(x, y, w, h) self.emit(C_CLIP, x, y, w, h) end
function Canvas:unclip() self.emit(C_UNCLIP) end
function Canvas:translate(dx, dy) self.emit(C_TRANSLATE, dx, dy) end
function Canvas:resetTransform() self.emit(C_RESET) end
function Canvas:used() return self.cursor() end
function Canvas:texture(ref) return texture_id(self, ref) end
function Canvas:str(s) return string_id(self, s) end
function Canvas:size()
    local _, _, w, h = self.ctx.call4("rect", self.key)
    return w, h
end

function Element:canvas()
    local c = self.__ctx.canvases[self.key]
    if c == nil then
        error("element " .. self.key .. " is not a Canvas in this script's scope", 2)
    end
    return c
end

-- ───────────────────────────── tasks ─────────────────────────────

-- Runs fn as a task: a coroutine that may ui.await. Returns ok, message, origin.
local function step(ctx, co, ...)
    local ok, a, b = co_resume(co, ...)
    if not ok then
        local origin = ctx.tasks[co]
        ctx.tasks[co] = nil
        return false, traceback(co, tostring(a)), origin
    end
    if co_status(co) == "dead" then
        ctx.tasks[co] = nil
        return true
    end
    if a ~= AWAIT then
        local origin = ctx.tasks[co]
        ctx.tasks[co] = nil
        co_close(co)
        return false, "a UI task may only yield through ui.await (use coroutine.wrap for your own coroutines)", origin
    end
    ctx.waiting[b] = co
    return true
end

local function spawn(ctx, origin, fn, ...)
    local co = co_create(fn)
    ctx.tasks[co] = origin
    return step(ctx, co, ...)
end

local function cancel_all(ctx)
    for co in pairs(ctx.tasks) do
        co_close(co)
    end
    ctx.tasks = {}
    ctx.waiting = {}
end

-- Awaitable handle: an action call, an animation or a timer.
local Handle = {}
Handle.__index = Handle
Handle.__name = "ui.Handle"
Handle.__metatable = "ui.Handle"
local is_handle = setmetatable({}, { __mode = "k" }) -- unforgeable identity (metatables are hidden)

local function new_handle(ctx, token, kind)
    local hd = setmetatable({ token = token, kind = kind, ctx = ctx }, Handle)
    ctx.handles[token] = hd
    is_handle[hd] = true
    return hd
end

function Handle:done() return self.status ~= nil end
function Handle:cancel()
    if self.status == nil and self.kind ~= "timer" then
        self.ctx.call(self.kind == "action" and "cancelAction" or "stopAnim", self.token)
    end
end

-- ───────────────────────────── the factory ─────────────────────────────

local function make_ui(ctx, env, info)
    local h = ctx.h

    local function call(op, ...)
        local ok, a, b = h(op, ...)
        if not ok then
            error(a, 3)
        end
        return a, b
    end
    local function call4(op, ...)
        local ok, a, b, c, d = h(op, ...)
        if not ok then
            error(a, 3)
        end
        return a, b, c, d
    end
    ctx.call = call
    ctx.call4 = call4

    local ui = { api = 1, document = info.document, key = info.key }

    function ui.q(selector) return element(ctx, (call("q", nil, selector))) end
    function ui.qAll(selector) return elements(ctx, (call("qAll", nil, selector))) end
    -- ui.get("panel/resume"): by stable node-id path relative to this script's scope.
    function ui.get(path) return element(ctx, (call("get", path))) end
    ui.root = element(ctx, info.root)

    function ui.param(name) return (call("param", name)) end
    function ui.params() return (call("param", nil)) end

    -- Data (#289): absolute host paths, e.g. "session.online". Returns value, state.
    function ui.read(path)
        local v, s = call("read", path)
        return v, s
    end
    function ui.watch(path, fn)
        if type(fn) ~= "function" then
            error("watch(path, fn): fn must be a function", 2)
        end
        local id = ctx.next_id + 1
        ctx.next_id = id
        call("watch", path, id)
        ctx.watchers[id] = fn
        return { cancel = function() if ctx.watchers[id] then ctx.watchers[id] = nil h("unwatch", id) end end }
    end

    -- Actions (#289): ui.action returns an awaitable handle; ui.request fires and forgets.
    function ui.action(id, args) return new_handle(ctx, (call("action", id, args or {})), "action") end
    function ui.request(id, args) return new_handle(ctx, (call("action", id, args or {})), "action") end

    -- local result, err = ui.await(handle): inside a task only (event handlers, on_open, watch and
    -- animation callbacks, ui.async). Closing, reloading or leaving the world cancels the task.
    function ui.await(hd)
        if not is_handle[hd] then
            error("ui.await expects a handle from ui.action, ui.request, ui.tween, ui.play or ui.sleep", 2)
        end
        local status, value = hd.status, hd.value
        if status == nil then
            local co, main = co_running()
            if main or ctx.tasks[co] == nil then
                error("ui.await needs a task: an event handler, on_open, a watch callback or ui.async(fn)", 2)
            end
            status, value = co_yield(AWAIT, hd.token)
        end
        if status == "ok" then
            return value
        end
        return nil, value
    end

    function ui.async(fn, ...)
        if type(fn) ~= "function" then
            error("ui.async(fn): fn must be a function", 2)
        end
        local ok, msg, origin = spawn(ctx, "async", fn, ...)
        if not ok then
            ctx.failed[#ctx.failed + 1] = { msg, origin }
        end
    end

    function ui.sleep(seconds) return new_handle(ctx, (call("sleep", seconds)), "timer") end

    -- Converters: ui.converter("display_if", { result = "string", to = function(v) ... end [, back = fn] })
    function ui.converter(name, spec)
        if type(spec) ~= "table" or type(spec.to) ~= "function" then
            error("converter(name, { result = type, to = fn [, back = fn] })", 2)
        end
        call("converter", name, spec.result or "any", spec.back ~= nil)
        ctx.converters[name] = spec
    end

    -- Animation through the host sampler (#295): never waits on scripts.
    -- opts: delay, clock ("ui" | "game" | a host clock), fill ("hold" | "release"), from = { prop = value }
    function ui.tween(el, props, duration, easing, opts)
        return new_handle(ctx, (call("tween", key_of(el, 1), props, duration or 0.25, easing or "linear", opts or {})),
            "anim")
    end
    -- opts: speed, loop, on_event, clock, blend (s), fill, at (s), restart (false keeps a running one), reduced (clip)
    function ui.play(clip, opts)
        opts = opts or {}
        local hd = new_handle(ctx, (call("play", clip, { speed = opts.speed, loop = opts.loop, clock = opts.clock,
            blend = opts.blend, fill = opts.fill, at = opts.at, restart = opts.restart, reduced = opts.reduced })),
            "anim")
        if type(opts.on_event) == "function" then
            ctx.anim_events[hd.token] = opts.on_event
        end
        return hd
    end
    -- ui.stop(handle | clipId [, "hold" | "end" | "release"])
    function ui.stop(target, how)
        if is_handle[target] then
            if target.status == nil then
                if target.kind == "anim" then
                    call("stopAnim", target.token, how)
                else
                    target:cancel()
                end
            end
        elseif type(target) == "string" then
            return (call("stopClip", target, how))
        end
    end
    local function anim_target(target, n)
        if is_handle[target] then
            return target.token, nil
        elseif type(target) == "string" then
            return nil, target
        end
        error("expected an animation handle or a clip id", n + 1)
    end
    function ui.seek(target, seconds)
        local token, clip = anim_target(target, 2)
        return (call("seekAnim", token, clip, seconds))
    end
    function ui.speed(target, rate)
        local token, clip = anim_target(target, 2)
        return (call("speedAnim", token, clip, rate))
    end
    function ui.release(el, property) call("release", key_of(el, 1), property) end
    -- UI state machines of this scope (animations/<id>.states.json)
    function ui.setState(machine, state) return new_handle(ctx, (call("machineSet", machine, state)), "anim") end
    function ui.machineState(machine) return (call("machineState", machine)) end
    function ui.clock(name) return (call("clock", name)) end

    function ui.sound(id, opts) call("sound", id, opts or {}) end
    function ui.navigate(target, args) call("navigate", target, args or {}) end
    function ui.close() call("close") end
    function ui.focus(el) return (call("focus", key_of(el, 1))) end
    -- Component scripts raise their declared signals on the instance.
    function ui.emit(signal, args) call("emit", signal, args or {}) end

    -- Custom events (#291): between this document's code-behind and its graphs. Handlers are
    -- tasks; a raise is queued and delivered after the current handler, in raise order.
    function ui.on(name, fn)
        if type(name) ~= "string" or type(fn) ~= "function" then
            error("ui.on(name, fn): name must be a string and fn a function", 2)
        end
        local id = ctx.next_id + 1
        ctx.next_id = id
        ctx.handlers[id] = fn
        local list = ctx.custom[name]
        if list == nil then
            list = {}
            ctx.custom[name] = list
        end
        list[#list + 1] = id
        return id
    end
    function ui.raise(name, args)
        if type(name) ~= "string" then
            error("ui.raise(name, args): name must be a string", 2)
        end
        call("raise", name)
        local q = ctx.raised
        q[#q + 1] = { name, args or {} }
    end

    function ui.time() return ctx.time end

    local function joined(...)
        local n = select("#", ...)
        local parts = {}
        for i = 1, n do
            parts[i] = tostring((select(i, ...)))
        end
        return concat(parts, " ")
    end
    function ui.log(...) h("log", "info", joined(...)) end
    function ui.warn(...) h("log", "warn", joined(...)) end

    rawset(env, "ui", ui)
    rawset(env, "print", ui.log)

    -- require: declared script dependencies and in-archive scripts only; no filesystem search.
    rawset(env, "require", function(name)
        if type(name) ~= "string" then
            error("require(name): name must be a string", 2)
        end
        local cached = ctx.loaded[name]
        if cached ~= nil then
            return cached
        end
        if ctx.loading[name] then
            error("require cycle through " .. name, 2)
        end
        local src, chunk = call("module", name)
        local fn, err = load(src, "=" .. chunk, "t", env)
        if fn == nil then
            error(err, 2)
        end
        ctx.loading[name] = true
        local ok, result = xpcall(fn, on_error, name)
        ctx.loading[name] = nil
        if not ok then
            error(result, 2)
        end
        if result == nil then
            result = true
        end
        ctx.loaded[name] = result
        return result
    end)
    return ui
end

-- __cenda_ui_factory(id, env, info): builds context `id` over environment `env`, which holds the
-- host function as __h (taken and removed here). info = {document, key, root}.
function __cenda_ui_factory(id, env, info)
    local h = rawget(env, "__h")
    rawset(env, "__h", nil)
    local ctx = {
        id = id, env = env, h = h, els = {}, handlers = {}, by_fn = setmetatable({}, { __mode = "k" }),
        next_id = 0, tasks = {}, waiting = {}, handles = setmetatable({}, { __mode = "v" }), watchers = {},
        converters = {}, loaded = {}, loading = {}, canvases = {}, anim_events = {}, failed = {}, time = 0,
        root_key = info.root, custom = {}, raised = {}, graphs = {}, graph_updates = {},
    }
    ctx.ui = make_ui(ctx, env, info)
    contexts[id] = ctx
end

local function hook(ctx, name)
    local m = ctx.module
    if type(m) == "table" then
        local f = rawget(m, name)
        if type(f) == "function" then
            return f
        end
    end
    local g = rawget(ctx.env, name)
    if type(g) == "function" then
        return g
    end
    return nil
end

local function hooks(ctx)
    return {
        on_open = hook(ctx, "on_open") ~= nil, on_close = hook(ctx, "on_close") ~= nil,
        update = hook(ctx, "update") ~= nil, on_input = hook(ctx, "on_input") ~= nil,
        on_reload = hook(ctx, "on_reload") ~= nil,
    }
end

-- A task that failed while a dispatch was running (ui.async inside a handler) is reported after it.
local function flush_failed(ctx, ok, msg, origin)
    if ok and #ctx.failed > 0 then
        local f = tremove(ctx.failed, 1)
        return false, f[1], f[2]
    end
    return ok, msg, origin
end

local function bind_canvases(ctx, canvases)
    for key, name in pairs(canvases or {}) do
        local buf = rawget(ctx.env, name)
        rawset(ctx.env, name, nil)
        if buf ~= nil then
            ctx.canvases[key] = new_canvas(ctx, key, buf)
        end
    end
end

local function run_module(ctx, src, chunk)
    if src == nil then
        ctx.module = nil -- graphs only (#291): no code-behind
        return true, hooks(ctx)
    end
    local fn, err = load(src, "=" .. chunk, "t", ctx.env)
    if fn == nil then
        return false, err, "load"
    end
    local ok, m = xpcall(fn, on_error)
    if not ok then
        return false, m, "load"
    end
    ctx.module = m
    return true, hooks(ctx)
end

-- Events arrive as plain tables; handlers stop or prevent them through these methods, and the
-- flags go back to the host's dispatcher when the handler returns (or first awaits).
local EventMethods = {
    stop = function(e) e.flags = e.flags | 1 end,
    stopImmediate = function(e) e.flags = e.flags | 3 end,
    prevent = function(e) e.flags = e.flags | 4 end,
    accept = function(e) e.flags = e.flags | 8 end, -- accept a drop (drag-over / drag-drop)
}
local Event = { __index = EventMethods, __name = "ui.Event", __metatable = "ui.Event" }

local function event_object(ctx, ev)
    ev.target = element(ctx, ev.target)
    ev.current = element(ctx, ev.current)
    ev.flags = 0
    return setmetatable(ev, Event)
end

local dispatch = {}

dispatch[OP_LOAD] = function(ctx, src, chunk, canvases)
    bind_canvases(ctx, canvases)
    return run_module(ctx, src, chunk)
end

dispatch[OP_RELOAD] = function(ctx, src, chunk, canvases)
    local fn, err
    if src ~= nil then
        fn, err = load(src, "=" .. chunk, "t", ctx.env)
        if fn == nil then
            return false, err, "load" -- the running module stays
        end
    end
    bind_canvases(ctx, canvases)
    cancel_all(ctx)
    ctx.handlers = {}
    ctx.by_fn = setmetatable({}, { __mode = "k" })
    ctx.watchers = {}
    ctx.anim_events = {}
    ctx.loaded = {}
    ctx.els = {}
    ctx.custom = {}
    ctx.raised = {}
    ctx.ui.root = element(ctx, ctx.root_key)
    if fn == nil then
        ctx.module = nil
        return true, hooks(ctx)
    end
    local ok, m = xpcall(fn, on_error)
    if not ok then
        return false, m, "load"
    end
    ctx.module = m
    local again = hook(ctx, "on_reload") or hook(ctx, "on_open")
    if again ~= nil then
        local ok2, msg, origin = spawn(ctx, "on_open", again, ctx.ui)
        if not ok2 then
            return false, msg, origin
        end
    end
    return true, hooks(ctx)
end

dispatch[OP_OPEN] = function(ctx)
    local f = hook(ctx, "on_open")
    if f == nil then
        return true
    end
    return flush_failed(ctx, spawn(ctx, "on_open", f, ctx.ui))
end

dispatch[OP_CLOSE] = function(ctx)
    local f = hook(ctx, "on_close")
    local ok, msg = true, nil
    if f ~= nil then
        ok, msg = xpcall(f, on_error, ctx.ui)
    end
    cancel_all(ctx)
    contexts[ctx.id] = nil
    if not ok then
        return false, msg, "on_close"
    end
    return true
end

dispatch[OP_UPDATE] = function(ctx, dt)
    ctx.time = ctx.time + dt
    local f = ctx.update_fn
    if f ~= nil then
        local ok, msg = xpcall(f, on_error, dt)
        if not ok then
            return false, msg, "update"
        end
    end
    local gu = ctx.graph_updates
    for i = 1, #gu do
        local ok, msg = xpcall(gu[i].fn, on_error, dt)
        if not ok then
            return false, msg, "graph-update:" .. gu[i].index
        end
    end
    return flush_failed(ctx, true)
end

dispatch[OP_INPUT] = function(ctx, ev)
    local f = hook(ctx, "on_input")
    if f == nil then
        return true, false
    end
    local e = event_object(ctx, ev)
    local ok, consumed = xpcall(f, on_error, e)
    if not ok then
        return false, consumed, "on_input"
    end
    return true, consumed == true or e.flags ~= 0
end

dispatch[OP_EVENT] = function(ctx, handler, ev)
    local fn = ctx.handlers[handler]
    if fn == nil then
        return true, 0
    end
    local e = event_object(ctx, ev)
    local ok, msg, origin = flush_failed(ctx, spawn(ctx, handler, fn, e))
    if not ok then
        return false, msg, origin
    end
    return true, e.flags
end

dispatch[OP_SIGNAL] = function(ctx, handler, args)
    local fn = ctx.handlers[handler]
    if fn == nil then
        return true
    end
    return flush_failed(ctx, spawn(ctx, handler, fn, args))
end

dispatch[OP_SETTLE] = function(ctx, token, status, value)
    local hd = ctx.handles[token]
    if hd ~= nil then
        hd.status, hd.value = status, value
    end
    ctx.anim_events[token] = nil
    local co = ctx.waiting[token]
    if co == nil then
        return true
    end
    ctx.waiting[token] = nil
    if status == "cancelled" then
        -- A cancelled call (close, reload, world change) never resumes its task.
        ctx.tasks[co] = nil
        co_close(co)
        return true
    end
    return flush_failed(ctx, step(ctx, co, status, value))
end

dispatch[OP_CANCEL_ALL] = function(ctx)
    cancel_all(ctx)
    return true
end

dispatch[OP_CONVERT] = function(ctx, name, back, value)
    local spec = ctx.converters[name]
    local f
    if spec ~= nil then
        f = back and spec.back or spec.to
    else
        f = hook(ctx, name)
    end
    if f == nil then
        return false, "no converter " .. name, "converter:" .. name
    end
    local ok, result = xpcall(f, on_error, value)
    if not ok then
        return false, result, "converter:" .. name
    end
    return true, result
end

dispatch[OP_WATCH] = function(ctx, id, value, state)
    local fn = ctx.watchers[id]
    if fn == nil then
        return true
    end
    return flush_failed(ctx, spawn(ctx, "watch:" .. id, fn, value, state))
end

dispatch[OP_ANIM_EVENT] = function(ctx, token, name)
    local fn = ctx.anim_events[token]
    if fn == nil then
        return true
    end
    return flush_failed(ctx, spawn(ctx, "anim:" .. token, fn, name))
end

dispatch[OP_DROP_HANDLER] = function(ctx, id, what)
    if what == "graph-update" then
        local gu = ctx.graph_updates
        for i = #gu, 1, -1 do
            if gu[i].index == id then
                tremove(gu, i)
            end
        end
    elseif what == "update" then
        ctx.update_fn = nil
    elseif what == "watch" then
        ctx.watchers[id] = nil
    else
        ctx.handlers[id] = nil
    end
    return true
end

-- ───────────────────────────── graphs (#291) ─────────────────────────────
--
-- A compiled graph is a chunk run in this context's environment with (ui, script, dbg, dbgv)
-- that returns its hooks. dbg/dbgv exist only in debug builds (the editor preview).

local function graph_debug(ctx, graph)
    local function dbg(node)
        local co, main = co_running()
        local in_task = not main and ctx.tasks[co] ~= nil
        local token = ctx.call("trace", graph, node, in_task)
        if token ~= nil then
            ctx.ui.await(new_handle(ctx, token, "debug"))
        end
    end
    local function dbgv(node, port, value)
        local t = type(value)
        if t == "function" or t == "thread" or t == "userdata" then
            value = tostring(value)
        elseif t == "table" and rawget(value, "__ctx") ~= nil then
            value = "ui.Element(" .. tostring(value.key) .. ")"
        end
        pcall(ctx.h, "traceValue", graph, node, port, value) -- a value that cannot cross is skipped
    end
    return dbg, dbgv
end

-- OP_GRAPH_LOAD(index, {id, src, chunk, debug}) loads one graph; (0, nil) unloads them all.
dispatch[OP_GRAPH_LOAD] = function(ctx, index, g)
    if g == nil then
        ctx.graphs = {}
        ctx.graph_updates = {}
        return true
    end
    local fn, err = load(g.src, "=" .. g.chunk, "t", ctx.env)
    if fn == nil then
        return false, err, "graph-load:" .. index
    end
    local d, dv
    if g.debug then
        d, dv = graph_debug(ctx, g.id)
    end
    local ok, G = xpcall(fn, on_error, ctx.ui, ctx.module, d, dv)
    if not ok then
        return false, G, "graph-load:" .. index
    end
    if type(G) ~= "table" then
        G = {}
    end
    ctx.graphs[index] = G
    if type(G.update) == "function" then
        local gu = ctx.graph_updates
        gu[#gu + 1] = { index = index, fn = G.update }
    end
    return true, { on_open = type(G.on_open) == "function", update = type(G.update) == "function",
        on_close = type(G.on_close) == "function" }
end

-- OP_GRAPH_HOOK(index, "on_open" | "on_close"): on_open is a task; on_close is synchronous.
dispatch[OP_GRAPH_HOOK] = function(ctx, index, name)
    local G = ctx.graphs[index]
    local f = G and G[name]
    if type(f) ~= "function" then
        return true
    end
    if name == "on_open" then
        return flush_failed(ctx, spawn(ctx, "graph-open:" .. index, f))
    end
    local ok, msg = xpcall(f, on_error)
    if not ok then
        return false, msg, "graph-close:" .. index
    end
    return true
end

-- Delivers queued custom events after a dispatch: each handler runs as a task, in raise order.
local function drain(ctx)
    local q = ctx.raised
    if #q == 0 then
        return true
    end
    local n = 0
    while #q > 0 do
        local ev = tremove(q, 1)
        n = n + 1
        if n > MAX_RAISED then
            ctx.raised = {}
            return false, "more than " .. MAX_RAISED .. " custom events in one dispatch: does a handler raise"
                .. " the event it handles?", "raise"
        end
        local list = ctx.custom[ev[1]]
        if list ~= nil then
            for i = 1, #list do
                local id = list[i]
                local fn = ctx.handlers[id]
                if fn ~= nil then
                    local ok, msg = spawn(ctx, "custom:" .. id, fn, ev[2])
                    if not ok then
                        return false, msg, "custom:" .. id
                    end
                end
            end
        end
    end
    return true
end

-- __cenda_ui_dispatch(ctx, op, ...) -> ok, result | false, message, origin
function __cenda_ui_dispatch(id, op, a, b, c)
    local ctx = contexts[id]
    if ctx == nil then
        return false, "no script context " .. tostring(id), "dispatch"
    end
    if op == OP_UPDATE then
        local ok, r1, r2 = dispatch[OP_UPDATE](ctx, a)
        if ok and #ctx.raised > 0 then
            return drain(ctx)
        end
        return ok, r1, r2
    end
    local ok, r1, r2 = dispatch[op](ctx, a, b, c)
    if op == OP_LOAD or op == OP_RELOAD then
        ctx.update_fn = hook(ctx, "update")
    end
    if ok and op ~= OP_CONVERT and op ~= OP_CLOSE and op ~= OP_DROP_HANDLER and #ctx.raised > 0 then
        local ok2, m2, o2 = drain(ctx)
        if not ok2 then
            return false, m2, o2
        end
    end
    return ok, r1, r2
end
