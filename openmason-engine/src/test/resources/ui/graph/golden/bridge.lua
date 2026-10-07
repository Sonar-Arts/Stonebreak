-- omui-graphc 2: graphs/bridge.graph.json, source sha256 65ad60a0b1fb70404a185a936fe8a8d35319de0ebb74943c2626004e28ac312c
-- Generated from the behavior graph, which stays canonical: never edit this chunk. "-- @node"
-- lines map the code below them to graph nodes (errors, traces, breakpoints).
-- inputs sha256 66b3efee6152a6b513bf5ef5e3384dfbe8d04d04ddd5fd0075ca6efc391a526f
local ui, script, dbg, dbgv = ...
local G, F, H = {}, {}, {}

-- @node on_changed ui:event.signal
H.on_changed = function(args)
  local o_on_changed_value = args.value
  -- @node record ui:element.set-visible
  do
    -- @node top lua:call
    local t_top_result = best()
    -- @node cmp ui:compare
    local t_cmp_result = (o_on_changed_value >= t_top_result)
    -- @node record ui:element.set-visible
    ui.get("knob/value"):style("display", t_cmp_result and "flex" or "none")
  end
end

-- @node on_score ui:event.custom
H.on_score = function(args)
  local o_on_score_score = args.score
  local o_fmt_text
  -- @node fmt lua:call
  local text_2 = script.format_score(o_on_score_score, "Score: ")
  o_fmt_text = text_2
  -- @node show ui:element.set-text
  ui.get("status"):setText(o_fmt_text)
  -- @node cool lua:call
  script.cooldown(0.5)
  -- @node raise ui:event.raise
  ui.raise("cooled", { score = o_on_score_score })
end

-- @node on_score ui:event.custom
ui.on("scored", H.on_score)

-- @node -
function G.on_open()
  -- @node on_changed ui:event.signal
  ui.get("knob"):on("changed", H.on_changed)
-- @node -
end

return G
