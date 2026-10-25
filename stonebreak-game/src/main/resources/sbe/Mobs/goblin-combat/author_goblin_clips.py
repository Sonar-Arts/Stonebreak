# Regenerates the goblin's six clips through Open Mason's om.anim API.
# Open Dev Working/Goblin/Goblin.omo in Open Mason, then run this with run_python_script.
# Rotations are Euler XYZ degrees about each part's pivot; +X swings a hanging limb forward,
# -X leans an upright part (body/head) forward. Parts not named in a pose return to rest.
import om

OUT = "/home/johnny/JetBrains Projects/IdeaProjects/Stonebreak/Dev Working/Goblin/Animations/"
P = {n: om.part(n) for n in ["body", "head", "upper_arm_l", "upper_arm_r", "forearm_l", "forearm_r",
                               "hand_l", "hand_r", "thigh_l", "thigh_r", "shin_l", "shin_r"]}
REST_BODY = (0.0, 1.025, 0.0)


def pose(clip, t, rots, body_y=None, easing="ease_in_out"):
    """Key every animated part at t; parts not in rots go back to rest (0,0,0)."""
    for name, part in P.items():
        r = rots.get(name, (0, 0, 0))
        if name == "body":
            pos = (REST_BODY[0], REST_BODY[1] + (body_y or 0.0), REST_BODY[2])
            clip.key(part, t, position=pos, rotation=r, easing=easing)
        else:
            clip.key(part, t, rotation=r, easing=easing)


# ---- Idle (2.0 s loop): breathing lean, head bob, arms drift
idle = om.anim.clip("Idle", duration=2.0, fps=30, loop=True)
pose(idle, 0.0, {})
pose(idle, 1.0, {"body": (-2, 0, 0), "head": (4, 0, 0), "upper_arm_l": (0, 0, -4), "upper_arm_r": (0, 0, 4)}, body_y=-0.01)
pose(idle, 2.0, {})
idle.save(OUT + "Idle.omanim")

# ---- Walking (0.8 s loop): opposite leg/arm swing, knee bend, bob
walk = om.anim.clip("Walking", duration=0.8, fps=30, loop=True)


def stride(s):  # s = +1 left leg forward, -1 right leg forward
    return {"thigh_l": (28 * s, 0, 0), "thigh_r": (-28 * s, 0, 0),
            "shin_l": (-10 if s > 0 else -30, 0, 0), "shin_r": (-30 if s > 0 else -10, 0, 0),
            "upper_arm_l": (-22 * s, 0, 0), "upper_arm_r": (22 * s, 0, 0),
            "body": (-4, 0, 0), "head": (2, 0, 0)}


def passing(lift_left):
    return {"thigh_l": (0, 0, 0), "thigh_r": (0, 0, 0),
            "shin_l": (-35 if lift_left else 0, 0, 0), "shin_r": (0 if lift_left else -35, 0, 0),
            "body": (-4, 0, 0), "head": (2, 0, 0)}


pose(walk, 0.0, stride(1), body_y=-0.02)
pose(walk, 0.2, passing(False), body_y=0.03)
pose(walk, 0.4, stride(-1), body_y=-0.02)
pose(walk, 0.6, passing(True), body_y=0.03)
pose(walk, 0.8, stride(1), body_y=-0.02)
walk.save(OUT + "Walking.omanim")

# ---- Stab (0.6 s one-shot, impact 0.25): cock back, jab forward, recover
stab = om.anim.clip("Stab", duration=0.6, fps=30, loop=False)
pose(stab, 0.0, {})
pose(stab, 0.14, {"upper_arm_r": (-30, 0, 8), "forearm_r": (50, 0, 0), "hand_r": (-20, 0, 0),
                  "body": (4, -12, 0), "thigh_l": (12, 0, 0), "shin_l": (-12, 0, 0)}, easing="ease_out")
pose(stab, 0.25, {"upper_arm_r": (82, 0, 0), "forearm_r": (0, 0, 0), "hand_r": (-82, 0, 0),
                  "body": (-14, 14, 0), "head": (10, -8, 0), "upper_arm_l": (-25, 0, 0),
                  "thigh_l": (25, 0, 0), "shin_l": (-20, 0, 0), "thigh_r": (-12, 0, 0)}, easing="ease_in")
pose(stab, 0.36, {"upper_arm_r": (75, 0, 0), "hand_r": (-75, 0, 0), "body": (-10, 10, 0),
                  "head": (6, -4, 0), "thigh_l": (22, 0, 0), "shin_l": (-18, 0, 0), "thigh_r": (-10, 0, 0)})
pose(stab, 0.6, {})
stab.save(OUT + "Stab.omanim")

# ---- Smash (0.9 s one-shot, impact 0.55): two-handed overhead wind-up then slam
smash = om.anim.clip("Smash", duration=0.9, fps=30, loop=False)
pose(smash, 0.0, {})
pose(smash, 0.4, {"upper_arm_r": (165, 0, -10), "forearm_r": (25, 0, 0), "hand_r": (0, 0, 0),
                  "upper_arm_l": (150, 0, 15), "forearm_l": (20, 0, 0),
                  "body": (10, 0, 0), "head": (-12, 0, 0), "thigh_r": (-10, 0, 0)}, easing="ease_out")
pose(smash, 0.55, {"upper_arm_r": (60, 0, 0), "forearm_r": (0, 0, 0), "hand_r": (-90, 0, 0),
                   "upper_arm_l": (50, 0, 10), "forearm_l": (0, 0, 0),
                   "body": (-20, 0, 0), "head": (14, 0, 0),
                   "thigh_l": (20, 0, 0), "shin_l": (-25, 0, 0), "thigh_r": (-15, 0, 0)}, easing="ease_in")
pose(smash, 0.66, {"upper_arm_r": (55, 0, 0), "hand_r": (-85, 0, 0), "upper_arm_l": (45, 0, 10),
                   "body": (-18, 0, 0), "head": (12, 0, 0),
                   "thigh_l": (20, 0, 0), "shin_l": (-25, 0, 0), "thigh_r": (-15, 0, 0)})
pose(smash, 0.9, {})
smash.save(OUT + "Smash.omanim")

# ---- DrawBow (1.2 s one-shot, holds full draw): bow arm up, string hand pulls to the cheek
FULL_DRAW = {"upper_arm_l": (85, 0, -10), "hand_l": (-85, 0, 0),
             "upper_arm_r": (78, 0, 22), "forearm_r": (95, 0, 0), "hand_r": (-40, 0, 0),
             "body": (0, -18, 0), "head": (0, 16, 0), "thigh_l": (10, 0, 0), "thigh_r": (-8, 0, 0)}
draw = om.anim.clip("DrawBow", duration=1.2, fps=30, loop=False)
pose(draw, 0.0, {})
pose(draw, 0.3, {"upper_arm_l": (85, 0, -10), "hand_l": (-85, 0, 0),
                 "upper_arm_r": (85, 0, 15), "forearm_r": (10, 0, 0),
                 "body": (0, -10, 0), "head": (0, 8, 0)}, easing="ease_out")
pose(draw, 1.0, FULL_DRAW, easing="ease_in_out")
pose(draw, 1.2, FULL_DRAW)
draw.save(OUT + "DrawBow.omanim")

# ---- ReleaseBow (0.4 s one-shot): string hand flies back, then settle to rest
release = om.anim.clip("ReleaseBow", duration=0.4, fps=30, loop=False)
pose(release, 0.0, FULL_DRAW)
pose(release, 0.08, {"upper_arm_l": (88, 0, -10), "hand_l": (-88, 0, 0),
                     "upper_arm_r": (55, 0, 45), "forearm_r": (70, 0, 0), "hand_r": (-20, 0, 0),
                     "body": (2, -20, 0), "head": (0, 16, 0), "thigh_l": (10, 0, 0), "thigh_r": (-8, 0, 0)},
     easing="ease_out")
pose(release, 0.4, {})
release.save(OUT + "ReleaseBow.omanim")
