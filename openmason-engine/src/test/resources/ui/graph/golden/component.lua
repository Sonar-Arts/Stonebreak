-- omui-graphc 1: graphs/knob.graph.json, source sha256 361e3ee8f73246f83a801ec2b62c98a81393fa652981bb734d0ddbf44f9bc18e
-- Generated from the behavior graph, which stays canonical: never edit this chunk. "-- @node"
-- lines map the code below them to graph nodes (errors, traces, breakpoints).
-- inputs sha256 4f2abd5c574a29aa407564474db553f55478125fe0c1c5078abbefcba3b257d7
local ui, script, dbg, dbgv = ...
local G, F, H = {}, {}, {}
-- Any value as text; integral floats print without ".0" (3.0 -> "3").
local function text(v)
  if math.type(v) == "float" and v == math.floor(v) and v > -2^53 and v < 2^53 then
    return string.format("%d", v)
  end
  return tostring(v)
end
local v_value = 0

-- @node press ui:event.click
H.press = function(ev)
  -- @node inc ui:variable.increment
  v_value = (v_value or 0) + 1
  -- @node show ui:element.set-text
  do
    -- @node get ui:variable.get
    local t_get_value = v_value
    -- @node show ui:element.set-text
    ui.get("value"):setText(text(t_get_value))
  end
  -- @node emit ui:signal.emit
  do
    -- @node get ui:variable.get
    local t_get_value_2 = v_value
    -- @node emit ui:signal.emit
    ui.emit("changed", { value = t_get_value_2 })
  end
end

-- @node -
function G.on_open()
  -- @node press ui:event.click
  ui.get("plus"):on("click", H.press)
-- @node -
end

return G
