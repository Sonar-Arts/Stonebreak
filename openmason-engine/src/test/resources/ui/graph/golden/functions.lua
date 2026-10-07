-- omui-graphc 2: graphs/functions.graph.json, source sha256 67930bab37e0bcd4858ca896ccb6c9413a9d61fa5c0aa2865bffa39e52d6d00d
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
local v_score = 12

-- @node fn:announce/entry ui:function.entry
F.announce = function(a_message)
  -- @node fn:announce/log ui:log
  ui.log(a_message)
  -- @node fn:announce/ret ui:function.return
  do return end
end

-- @node fn:classify/entry ui:function.entry
F.classify = function(a_score)
  -- @node fn:classify/br ui:flow.branch
  do
    -- @node fn:classify/cmp ui:compare
    local t_cmp_result = (a_score > 10)
    -- @node fn:classify/br ui:flow.branch
    if t_cmp_result then
      -- @node fn:classify/hi ui:function.return
      do return 1, "high" end
    -- @node fn:classify/br ui:flow.branch
    else
      -- @node fn:classify/lo ui:function.return
      do return 2, "low" end
    -- @node fn:classify/br ui:flow.branch
    end
  end
end

-- @node fn:describe/entry ui:function.entry
F.describe = function(a_a, a_b)
  -- @node fn:describe/fmt ui:format
  local t_fmt_text = text(a_b) .. ": " .. text(a_a)
  -- @node fn:describe/ret ui:function.return
  do return t_fmt_text end
end

-- @node fn:double/entry ui:function.entry
F.double = function(a_x)
  -- @node fn:double/mul ui:math
  local t_mul_result = a_x * 2
  -- @node fn:double/ret ui:function.return
  do return t_mul_result end
end

-- @node -
-- Pure one-in-one-out function: also a binding converter.
ui.converter("double", { result = "number?", to = F.double })

-- @node go ui:event.click
H.go = function(ev)
  local o_rank_label
  -- @node rank ui:function.call
  do
    -- @node get ui:variable.get
    local t_get_value = v_score
    -- @node rank ui:function.call
    local exit, label = F.classify(t_get_value)
    o_rank_label = label
    if exit == 1 then
      -- @node show ui:element.set-text
      ui.get("status"):setText(o_rank_label)
    -- @node rank ui:function.call
    elseif exit == 2 then
      -- @node say ui:function.call
      do
        -- @node get ui:variable.get
        local t_get_value_2 = v_score
        -- @node desc ui:function.call
        local t_desc_text = F.describe(t_get_value_2, "score")
        -- @node say ui:function.call
        F.announce(t_desc_text)
        -- @node after ui:log
        do
          -- @node get ui:variable.get
          local t_get_value_3 = v_score
          -- @node twice ui:function.call
          local t_twice_y = F.double(t_get_value_3)
          -- @node after ui:log
          ui.log(t_twice_y)
        end
      -- @node say ui:function.call
      end
    -- @node rank ui:function.call
    end
  end
end

-- @node -
function G.on_open()
  -- @node go ui:event.click
  ui.get("resume"):on("click", H.go)
-- @node -
end

return G
