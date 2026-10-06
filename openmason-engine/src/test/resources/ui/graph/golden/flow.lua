-- omui-graphc 1: graphs/flow.graph.json, source sha256 c49bc751b92a4dd39437295782cdb44e1288004ed1d659353fbbb26d4154b7e9
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

-- @node opened ui:event.open
H.opened = function()
  local o_each_item, o_each_index
  local b_tick, b_done
  -- @node tick ui:log
  b_tick = function()
    ui.log("tick")
    -- @node wait ui:flow.wait
    ui.await(ui.sleep(1))
    return b_tick()
  end
  -- @node done ui:log
  b_done = function()
    ui.log("done")
  end
  -- @node seq ui:flow.sequence
  local function run_seq_then0()
    -- @node each ui:flow.for-each
    local function run_each_body()
      -- @node item ui:log
      do
        -- @node line ui:format
        local t_line_text = "#" .. text(o_each_index) .. ": " .. text(o_each_item)
        -- @node item ui:log
        ui.log(t_line_text)
      end
    -- @node each ui:flow.for-each
    end
    for i, item in ipairs({ "a", "b", "c" } or {}) do
      o_each_item = item
      o_each_index = i
      run_each_body()
    end
    return b_done()
  -- @node seq ui:flow.sequence
  end
  local function run_seq_then1()
    -- @node br ui:flow.branch
    do
      -- @node read ui:data.read
      local t_read_value, t_read_state = ui.read("session.online")
      -- @node br ui:flow.branch
      if t_read_value then
        return b_done()
      end
    end
  -- @node seq ui:flow.sequence
  end
  local function run_seq_then2()
    return b_tick()
  end
  run_seq_then0()
  run_seq_then1()
  run_seq_then2()
end

-- @node -
function G.on_open()
  -- @node opened ui:event.open
  ui.async(H.opened)
-- @node -
end

return G
