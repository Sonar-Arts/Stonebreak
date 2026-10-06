-- omui-graphc 1: graphs/counter.graph.json, source sha256 ac29211fdf5713ff4ca4f0462dfc2c72e6206daa68337367ad0ba3035f89e08c (debug build)
-- Generated from the behavior graph, which stays canonical: never edit this chunk. "-- @node"
-- lines map the code below them to graph nodes (errors, traces, breakpoints).
-- inputs sha256 e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
local ui, script, dbg, dbgv = ...
local G, F, H = {}, {}, {}
-- Any value as text; integral floats print without ".0" (3.0 -> "3").
local function text(v)
  if math.type(v) == "float" and v == math.floor(v) and v > -2^53 and v < 2^53 then
    return string.format("%d", v)
  end
  return tostring(v)
end
local v_clicks = 0
local v_elapsed = 0
local v_online = false

-- @node bye ui:event.close
H.bye = function()
  dbg("bye")
  -- @node log ui:log
  dbg("log")
  ui.log("bye")
end

-- @node frame ui:event.update
H.frame = function(dt)
  local o_frame_dt = dt
  dbgv("frame", "dt", o_frame_dt)
  dbg("frame")
  -- @node acc ui:variable.increment
  dbg("acc")
  v_elapsed = (v_elapsed or 0) + o_frame_dt
end

-- @node on_quit ui:event.click
H.on_quit = function(ev)
  dbg("on_quit")
  local o_ping_ok, o_ping_error
  -- @node ping ui:action.invoke
  dbg("ping")
  local result, err = ui.await(ui.action("test:net.ping", { reason = "quit" }))
  o_ping_ok = err == nil
  dbgv("ping", "ok", o_ping_ok)
  o_ping_error = err
  dbgv("ping", "error", o_ping_error)
  -- @node ok ui:flow.branch
  dbg("ok")
  if o_ping_ok then
    -- @node close ui:screen.close
    dbg("close")
    ui.close()
  -- @node ok ui:flow.branch
  else
    -- @node fail ui:element.set-text
    dbg("fail")
    ui.get("status"):setText(o_ping_error)
  -- @node ok ui:flow.branch
  end
end

-- @node on_resume ui:event.click
H.on_resume = function(ev)
  dbg("on_resume")
  -- @node count ui:variable.increment
  dbg("count")
  v_clicks = (v_clicks or 0) + 1
  -- @node show ui:element.set-text
  dbg("show")
  do
    -- @node get ui:variable.get
    local t_get_value = v_clicks
    dbgv("get", "value", t_get_value)
    -- @node fmt ui:format
    local t_fmt_text = "Clicked " .. text(t_get_value) .. " times"
    dbgv("fmt", "text", t_fmt_text)
    -- @node show ui:element.set-text
    ui.get("status"):setText(t_fmt_text)
  end
  -- @node br ui:flow.branch
  dbg("br")
  do
    -- @node get ui:variable.get
    local t_get_value_2 = v_clicks
    dbgv("get", "value", t_get_value_2)
    -- @node enough ui:compare
    local t_enough_result = (t_get_value_2 >= 3)
    dbgv("enough", "result", t_enough_result)
    -- @node br ui:flow.branch
    if t_enough_result then
      -- @node fade ui:anim.tween
      dbg("fade")
      ui.await(ui.tween(ui.get("panel"), { ["opacity"] = 0 }, 0.2, "ease-out", { delay = 0 }))
      -- @node req ui:action.request
      dbg("req")
      ui.request("stonebreak:screen.pause.resume", {})
    -- @node br ui:flow.branch
    else
      -- @node pulse ui:element.set-class
      dbg("pulse")
      ui.get("panel"):toggleClass("pulse", true)
    -- @node br ui:flow.branch
    end
  end
end

-- @node opened ui:event.open
H.opened = function()
  dbg("opened")
  -- @node hide ui:element.set-visible
  dbg("hide")
  ui.get("panel"):style("display", false and "flex" or "none")
end

-- @node watch ui:event.watch
H.watch = function(value, state)
  local o_watch_value = value
  dbgv("watch", "value", o_watch_value)
  dbg("watch")
  -- @node store ui:variable.set
  dbg("store")
  v_online = o_watch_value
end

-- @node -
function G.on_open()
  -- @node on_quit ui:event.click
  ui.get("quit"):on("click", H.on_quit)
  -- @node on_resume ui:event.click
  ui.get("resume"):on("click", H.on_resume)
  -- @node watch ui:event.watch
  ui.watch("session.online", H.watch)
  -- @node opened ui:event.open
  ui.async(H.opened)
-- @node -
end

function G.update(dt)
  -- @node frame ui:event.update
  H.frame(dt)
-- @node -
end

function G.on_close()
  -- @node bye ui:event.close
  H.bye()
-- @node -
end

return G
