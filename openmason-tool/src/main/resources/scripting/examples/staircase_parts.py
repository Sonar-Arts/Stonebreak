# Example: build a staircase from boxes with a loop — the kind of repetitive
# geometry scripting exists for. One undo entry; delete the parts to revert.
import om

STEPS = 6          # number of steps
RUN = 2.0          # depth of each step
RISE = 1.0         # height of each step
WIDTH = 6.0        # stair width

base = om.box("stairs_base", size=(WIDTH, RISE, RUN), at=(0, RISE / 2, 0))
for i in range(1, STEPS):
    om.box(
        f"stair_step{i}",
        size=(WIDTH, RISE, RUN),
        at=(0, RISE / 2 + i * RISE, -i * RUN),
        parent=base,
    )

print(om.summary())
