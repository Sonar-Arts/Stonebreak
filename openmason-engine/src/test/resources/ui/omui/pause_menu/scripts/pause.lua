-- Pause menu code-behind (#297 pilot shape). Never executed by the format layer.
local common = require("stonebreak:ui/scripts/common")
local M = {}

function M.on_open(ui)
  ui.q("#resume"):on("click", function() ui.request("screen.resume") end)
  ui.q("#quit"):on("click", function() ui.request("screen.quit_to_menu") end)
  common.noop()
end

return M
