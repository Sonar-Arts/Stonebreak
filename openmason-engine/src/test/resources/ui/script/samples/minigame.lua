-- Sample minigame (#292): 1,000 bouncing sprites (gravity, wall bounce, pointer hit test) and 200
-- eased sparkles, drawn into a Canvas every frame. All per-frame work stays in Lua and the canvas's
-- native buffer: no host crossing per sprite and no per-frame Java garbage.
local W, H = 640, 360
local N, T = 1000, 200
local SIZE = 4

local px, py, vx, vy, col = {}, {}, {}, {}, {}
local fx, fy, tx, ty, age, life = {}, {}, {}, {}, {}, {}
local seed = 12345
local function rand()
  seed = (seed * 1103515245 + 12345) % 2147483648
  return seed / 2147483648
end

local function retarget(i)
  fx[i], fy[i] = rand() * W, rand() * H
  tx[i], ty[i] = rand() * W, rand() * H
  age[i], life[i] = 0, 0.6 + rand() * 1.4
end

local function ease(t) -- ease-in-out (quadratic)
  if t < 0.5 then return 2 * t * t end
  local u = -2 * t + 2
  return 1 - u * u / 2
end

for i = 1, N do
  px[i], py[i] = rand() * (W - SIZE), rand() * (H - SIZE)
  vx[i], vy[i] = (rand() - 0.5) * 240, (rand() - 0.5) * 240
  col[i] = math.floor(rand() * 0xffffff)
end
for i = 1, T do retarget(i) end

local canvas
local mx, my = W / 2, H / 2
local hits = 0

function on_open(ui)
  local game = ui.q("#game")
  canvas = game:canvas()
  game:on("pointer-move", function(ev) mx, my = ev.lx, ev.ly end)
  game:on("click", function() hits = 0 end)
end

function update(dt)
  if dt > 0.05 then dt = 0.05 end
  local c = canvas
  c:clear()
  c:rect(0, 0, W, H, 0x101820, 1)
  local g = 400 * dt
  local right, bottom = W - SIZE, H - SIZE
  for i = 1, N do
    local x, y = px[i] + vx[i] * dt, py[i] + vy[i] * dt
    local dx, dy = vx[i], vy[i] + g
    if x < 0 then x, dx = -x, -dx elseif x > right then x, dx = 2 * right - x, -dx end
    if y > bottom then y, dy = 2 * bottom - y, -dy * 0.98 elseif y < 0 then y, dy = -y, -dy end
    local ex, ey = x - mx, y - my
    if ex * ex + ey * ey < 400 then
      hits = hits + 1
      dy = -math.abs(dy) - 60
    end
    px[i], py[i], vx[i], vy[i] = x, y, dx, dy
    c:rect(x, y, SIZE, SIZE, col[i], 1)
  end
  for i = 1, T do
    local a = age[i] + dt
    local t = a / life[i]
    if t >= 1 then retarget(i) a, t = 0, 0 end
    age[i] = a
    local e = ease(t)
    c:circle(fx[i] + (tx[i] - fx[i]) * e, fy[i] + (ty[i] - fy[i]) * e, 3, 0xffd700, 1 - t)
  end
  c:circle(mx, my, 20, 0xffffff, 0.25)
  c:text("hits", 8, 20, 16, 0xffffff, 1)
  c:number(hits, 52, 20, 16, 0xffffff, 1)
end
