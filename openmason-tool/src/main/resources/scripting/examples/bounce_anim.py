# Example: author a simple bounce clip for a part named "body".
# Requires a loaded model with a part called "body" (rename to match yours).
# The clip is DETACHED: it is written to the absolute path below only if the
# script succeeds — load it afterwards with anim_load.
import om

part = om.part("body")           # teaching error if the part does not exist

c = om.anim.clip("bounce", duration=1.0, fps=30, loop=True)
c.key(part, 0.00, position=(0, 0, 0), easing="ease_out")
c.key(part, 0.25, position=(0, 1.5, 0), easing="ease_in")
c.key(part, 0.50, position=(0, 0, 0), easing="ease_out")
c.key(part, 0.75, position=(0, 0.75, 0), easing="ease_in")
c.key(part, 1.00, position=(0, 0, 0))

# EDIT ME: absolute output path for the .omanim file.
c.save("/tmp/bounce.omanim")
print("saved bounce clip — load it with anim_load")
