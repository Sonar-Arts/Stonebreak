-- Sample Lua code-behind (#292): code-behind events, a binding through a Lua converter, host-sampled
-- animation and an awaited host action, identical in the Open Mason preview and in Stonebreak.
---@type ui.Module
local M = {}

-- Bindings name this converter: session.online -> style:display of the "online" badge.
ui.converter("display_if", { result = "string", to = function(online) return online and "flex" or "none" end })

resyncs = resyncs or 0 -- an environment global: survives hot reload

function M.on_open(ui)
  local panel = ui.q("#panel")
  panel:style("opacity", 0):style("translate-y", 24)
  ui.tween(panel, { opacity = 1, ["translate-y"] = 0 }, 0.35, "ease-out")

  ui.q("#resync"):on("click", function()
    local status = ui.q("#status")
    status:setText("Resyncing...")
    local result, err = ui.await(ui.action("stonebreak:network.resync"))
    if result then
      resyncs = resyncs + 1
      status:setText("Audited " .. result.audited .. " chunks (" .. resyncs .. ")")
      ui.tween(status, { color = "#7CFC00" }, 0.25, "ease-in-out")
    else
      status:setText("Resync failed: " .. tostring(err))
      ui.tween(status, { color = "#FF5050" }, 0.25)
    end
  end)

  ui.q("#resume"):on("click", function()
    ui.await(ui.tween(panel, { opacity = 0 }, 0.2, "ease-in"))
    ui.request("stonebreak:screen.pause.resume")
  end)
end

function M.on_close(ui)
  ui.log("scripted pause closed after " .. resyncs .. " resync(s)")
end

return M
