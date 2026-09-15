# Example: paint a checkerboard texture on the +z faces of a new part.
# Run with a model loaded (live viewport). One undo entry; safe to try.
import om

# A flat panel to paint on.
panel = om.box("checker_panel", size=(8, 8, 1), at=(0, 4, 0))

# One shared 16x16 texture across the selected faces.
sel = panel.faces(facing="+z")
t = om.tex.create(sel, size=(16, 16), color=(40, 40, 48))

# Checkerboard: fill alternating 2x2 cells.
LIGHT = (215, 205, 180)
for y in range(0, 16, 2):
    for x in range(0, 16, 2):
        if (x // 2 + y // 2) % 2 == 0:
            t.rect((x, y, 2, 2), color=LIGHT)

print(om.summary())
