# Open Mason "om" scripting API (Python shim over the Java command layer).
#
# Evaluated once per script run, before user code, in the same context.
# The host binds the bridge as the global `_om_bridge`; this file wraps it in
# a Pythonic module and installs it as `om` in sys.modules, so user scripts
# start with `import om`.
#
# Conventions: Y-up, Euler XYZ rotations in degrees, sizes are full extents in
# model units. Parts are addressed by unique name. Face/vertex indices are
# part-local; face selections chain (extrude returns the new faces).

import sys as _sys
import types as _types
import json as _json
import math  # re-exported for scripts: om.math.sin etc.


class OmError(Exception):
    """A modeling command failed. Wraps the host-side error (args[0]) so the
    engine can surface its message, hint, and YOUR script line number.
    Catchable in scripts: `try: ... except om.OmError: ...`."""


class _BridgeProxy:
    """Wraps every bridge call so host errors re-raise as OmError WITH a
    Python traceback — host exceptions alone carry no guest line info."""

    def __init__(self, host):
        self._host = host

    def __getattr__(self, name):
        method = getattr(self._host, name)

        def _call(*args):
            try:
                return method(*args)
            except OmError:
                raise
            except BaseException as ex:
                raise OmError(ex) from None

        return _call


_b = _BridgeProxy(_om_bridge)  # noqa: F821  (bound by the host before this file runs)


def _vec3(value, name):
    """Accept tuple/list of 3, or a scalar (broadcast). None passes through."""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return [float(value)] * 3
    t = list(value)
    if len(t) != 3:
        raise ValueError(name + " needs 3 components (x, y, z), got " + str(len(t)))
    return [float(x) for x in t]


def _xyz(x, y, z, name):
    """Accept f(1,2,3) or f((1,2,3)) call styles."""
    if x is not None and not isinstance(x, (int, float)):
        return _vec3(x, name)
    return [float(x or 0), float(y or 0), float(z or 0)]


class FaceSelection:
    """A set of part-local faces. Topology ops return the newly created faces,
    so calls chain: p.faces(facing="+y").extrude(1.5).inset(0.4)."""

    def __init__(self, part, ids):
        self.part = part
        self.ids = [int(i) for i in ids]

    def __len__(self):
        return len(self.ids)

    def __iter__(self):
        return iter(self.ids)

    def __repr__(self):
        return "<faces %s of '%s'>" % (self.ids, self.part.name)

    def extrude(self, offset):
        """Extrude each face along its normal; returns the new side faces."""
        new = _b.extrudeFaces(self.part.name, self.ids, float(offset))
        return FaceSelection(self.part, new)

    def inset(self, amount):
        """Inset each face (border of new quads); returns the new border faces."""
        new = _b.insetFaces(self.part.name, self.ids, float(amount))
        return FaceSelection(self.part, new)

    def scale(self, factor, pivot=None):
        """Scale the faces about their shared centroid (or an explicit pivot)."""
        _b.scaleFaces(self.part.name, self.ids, float(factor), _vec3(pivot, "pivot"))
        return self

    def delete(self):
        """Delete these faces from the part."""
        _b.deleteFaces(self.part.name, self.ids)

    def set_material(self, name):
        """Assign a material (created with om.material) to these faces."""
        _b.setFaceMaterial(self.part.name, self.ids, name)
        return self

    def set_uv(self, region, rotation=0):
        """Set the UV region [u0,v0,u1,v1] (0..1) and rotation (0/90/180/270)."""
        r = [float(v) for v in region]
        _b.setFaceUV(self.part.name, self.ids, r, int(rotation))
        return self


class Part:
    """A named model part. All mutators return self for chaining."""

    def __init__(self, name):
        self._name = name

    @property
    def name(self):
        return self._name

    def __repr__(self):
        i = _json.loads(_b.infoJson(self._name))
        return "<part '%s': %d verts, %d faces>" % (self._name, i["verts"], i["faces"])

    # ----- transforms -----

    def move(self, x=None, y=None, z=None):
        """Translate by a delta: p.move(0, 1, 0) or p.move((0, 1, 0))."""
        _b.translate(self._name, _xyz(x, y, z, "move delta"))
        return self

    def rotate(self, x=None, y=None, z=None):
        """Rotate by Euler degree deltas: p.rotate(y=45)."""
        _b.rotate(self._name, _xyz(x, y, z, "rotation delta"))
        return self

    def scale(self, x=None, y=None, z=None):
        """Multiply scale: p.scale(1.5) broadcasts, p.scale(1, 2, 1) is per-axis."""
        if y is None and z is None:
            _b.scalePart(self._name, _vec3(x, "scale"))
        else:
            _b.scalePart(self._name, _xyz(x if x is not None else 1,
                                          y if y is not None else 1,
                                          z if z is not None else 1, "scale"))
        return self

    def set_position(self, x=None, y=None, z=None):
        _b.setTransform(self._name, None, _xyz(x, y, z, "position"), None, None)
        return self

    def set_rotation(self, x=None, y=None, z=None):
        _b.setTransform(self._name, None, None, _xyz(x, y, z, "rotation"), None)
        return self

    def set_origin(self, x=None, y=None, z=None):
        """Set the pivot the part rotates/scales around (part-local)."""
        _b.setTransform(self._name, _xyz(x, y, z, "origin"), None, None, None)
        return self

    # ----- hierarchy / lifecycle -----

    @property
    def parent(self):
        return None  # write-only convenience; query via om.summary()

    @parent.setter
    def parent(self, value):
        _b.setParent(self._name, value.name if isinstance(value, Part) else value)

    def set_parent(self, parent):
        _b.setParent(self._name, parent.name if isinstance(parent, Part) else parent)
        return self

    def rename(self, new_name):
        self._name = _b.renamePart(self._name, new_name)
        return self

    def duplicate(self, new_name, offset=None):
        """Copy this part (geometry + transform), optionally offsetting position."""
        return Part(_b.duplicatePart(self._name, new_name, _vec3(offset, "offset")))

    def delete(self):
        _b.removePart(self._name)

    def hide(self):
        _b.setVisibility(self._name, False)
        return self

    def show(self):
        _b.setVisibility(self._name, True)
        return self

    # ----- faces / vertices / geometry -----

    def faces(self, sel=None, facing=None):
        """Select faces: p.faces([0, 2]) by index, or p.faces(facing="+y") by
        world direction (+x/-x/+y/-y/+z/-z/up/down/north/south/east/west)."""
        if facing is not None:
            return FaceSelection(self, _b.facesByDirection(self._name, facing))
        if sel is None:
            return FaceSelection(self, range(_b.faceCount(self._name)))
        if isinstance(sel, int):
            sel = [sel]
        return FaceSelection(self, sel)

    def subdivide_edge(self, v_a, v_b, t=0.5):
        """Insert a vertex on the edge between two vertices; returns its index."""
        return _b.subdivideEdge(self._name, int(v_a), int(v_b), float(t))

    def vertex(self, index):
        """Position [x, y, z] of a part-local vertex."""
        return list(_b.vertex(self._name, int(index)))

    def vertex_count(self):
        return _b.vertexCount(self._name)

    def set_vertex(self, index, position):
        _b.moveVertices(self._name, [int(index)], _vec3(position, "position"), True)
        return self

    def move_vertices(self, indices, delta):
        _b.moveVertices(self._name, [int(i) for i in indices],
                        _vec3(delta, "delta"), False)
        return self

    def set_geometry(self, vertices, indices, tex_coords=None, faces=None):
        """Replace the part's mesh wholesale. vertices = flat [x,y,z,...],
        indices = flat triangles, faces = one face id per triangle."""
        _b.setGeometry(self._name,
                       [float(v) for v in vertices],
                       [int(i) for i in indices],
                       [float(v) for v in tex_coords] if tex_coords else None,
                       [int(i) for i in faces] if faces else None)
        return self

    # ----- queries -----

    def info(self):
        """{'name', 'verts', 'faces', 'tris', ...} for this part."""
        return _json.loads(_b.infoJson(self._name))


class Clip:
    """A detached .omanim animation clip built by this script. Keys default
    omitted pose components to the part's CURRENT transform; save() queues the
    file, written only when the whole script succeeds."""

    def __init__(self, name):
        self._name = name

    @property
    def name(self):
        return self._name

    def __repr__(self):
        i = _json.loads(_b.animInfoJson(self._name))
        return "<clip '%s': %.2fs, %d tracks, %d keys>" % (
            self._name, i["duration"], i["tracks"], i["keyframes"])

    def key(self, part, time, position=None, rotation=None, scale=None, easing=None):
        """Upsert a keyframe: clip.key(body, 0.5, rotation=(0,10,0), easing="ease_in_out").
        Omitted components come from the part's current transform."""
        _b.animKey(self._name, part.name if isinstance(part, Part) else part,
                   float(time), _vec3(position, "position"),
                   _vec3(rotation, "rotation"), _vec3(scale, "scale"), easing)
        return self

    def layer(self, type=None, mask=None, fade_in=None, fade_out=None, priority=None):
        """Mixing metadata: type="base"|"overlay", mask=[parts the overlay owns]."""
        names = None
        if mask is not None:
            names = [m.name if isinstance(m, Part) else m for m in mask]
        _b.animLayer(self._name, type, names,
                     float(fade_in) if fade_in is not None else None,
                     float(fade_out) if fade_out is not None else None,
                     int(priority) if priority is not None else None)
        return self

    def save(self, path):
        """Queue this clip for writing as .omanim (relative paths land next to
        the CLI output; live runs need an absolute path)."""
        _b.animSave(self._name, path)
        return self

    def info(self):
        return _json.loads(_b.animInfoJson(self._name))


class _Anim:
    """om.anim — animation authoring."""

    def clip(self, name, duration=1.0, fps=30, loop=True):
        """Create a clip: om.anim.clip("idle", duration=2.0)."""
        return Clip(_b.animClip(name, float(duration), float(fps), bool(loop)))


anim = _Anim()


# ===================== module-level functions =====================

def _create(shape, name, size, at, rotate, parent):
    parent_name = parent.name if isinstance(parent, Part) else parent
    return Part(_b.createPart(shape, name, _vec3(size, "size"),
                              _vec3(at, "at"), _vec3(rotate, "rotate"), parent_name))


def box(name, size=(1, 1, 1), at=None, rotate=None, parent=None):
    """A cuboid. size is full extents (x, y, z)."""
    return _create("cube", name, size, at, rotate, parent)


cube = box


def cylinder(name, size=(1, 1, 1), at=None, rotate=None, parent=None):
    return _create("cylinder", name, size, at, rotate, parent)


def sphere(name, size=(1, 1, 1), at=None, rotate=None, parent=None):
    return _create("sphere", name, size, at, rotate, parent)


def cone(name, size=(1, 1, 1), at=None, rotate=None, parent=None):
    return _create("cone", name, size, at, rotate, parent)


def pyramid(name, size=(1, 1, 1), at=None, rotate=None, parent=None):
    return _create("pyramid", name, size, at, rotate, parent)


def plane(name, size=(1, 1, 1), at=None, rotate=None, parent=None):
    return _create("pane", name, size, at, rotate, parent)


def wedge(name, size=(1, 1, 1), at=None, rotate=None, parent=None):
    return _create("wedge", name, size, at, rotate, parent)


def torus(name, size=(1, 1, 1), at=None, rotate=None, parent=None):
    return _create("torus", name, size, at, rotate, parent)


def hemisphere(name, size=(1, 1, 1), at=None, rotate=None, parent=None):
    return _create("hemisphere", name, size, at, rotate, parent)


def cross(name, size=(1, 1, 1), at=None, rotate=None, parent=None):
    return _create("cross", name, size, at, rotate, parent)


def sprite(name, size=(1, 1, 1), at=None, rotate=None, parent=None):
    return _create("sprite", name, size, at, rotate, parent)


def part(name):
    """Look up an existing part by name."""
    if not _b.partExists(name):
        _b.infoJson(name)  # raises the teaching error with known names
    return Part(name)


def parts():
    """All parts, in creation order."""
    return [Part(n) for n in _b.partNames()]


def mirror(source, axis="x", name=None):
    """Mirror a part across a model axis into a new part (winding-safe)."""
    src = source.name if isinstance(source, Part) else source
    return Part(_b.mirrorPart(src, axis, name))


def material(name, tint=None, emissive=False, layer=None):
    """Define a named material (data-level; pixels are painted in the tool).
    tint is (r, g, b) or (r, g, b, a), 0..255."""
    t = None
    if tint is not None:
        t = [int(c) for c in tint]
        if len(t) == 3:
            t.append(255)
    _b.defineMaterial(name, t, bool(emissive), layer)
    return name


def summary():
    """Whole-model digest: totals, bbox, per-part rows."""
    return _json.loads(_b.summaryJson())


def help():
    """Cheat sheet for the om API."""
    return HELP


HELP = """om — Open Mason scripting (Y-up, degrees, sizes = full extents)
Create:   om.box("body", size=(8,4,6), at=(0,4,0))     # also cylinder/sphere/cone/
          om.cylinder("leg", size=(1,3,1), rotate=(0,0,15), parent=body)   # pyramid/plane/wedge/torus/hemisphere/cross/sprite
Parts:    p = om.part("body"); om.parts(); p.info(); p.delete(); p.rename("torso")
          p.duplicate("leg2", offset=(-6,0,0)); om.mirror(p, axis="x")
Move:     p.move(0,1,0)  p.rotate(y=45)  p.scale(1.5)  p.set_position(...)
          p.set_rotation(...)  p.set_origin(...)  p.parent = other
Faces:    top = p.faces(facing="+y")     # +x/-x/+y/-y/+z/-z/up/down/north/south/east/west
          p.faces([0,2])                 # by part-local index
          cap = top.extrude(1.5)         # returns NEW side faces
          cap.inset(0.4).scale(0.8); cap.delete()
Verts:    p.subdivide_edge(0, 1, t=0.5); p.set_vertex(3, (4,5,3)); p.vertex(3)
          p.move_vertices([0,1], (0,0.5,0)); p.set_geometry(verts, tris, faces=[...])
Material: om.material("shell", tint=(255,120,40)); top.set_material("shell")
          top.set_uv((0,0,0.5,0.5), rotation=90)
Query:    om.summary()  ->  {"totals": {...}, "bbox": [[..],[..]], "parts": [...]}
Animate:  c = om.anim.clip("idle", duration=2.0, fps=30, loop=True)
          c.key(body, 0.0)                      # omitted pose = part's current transform
          c.key(body, 1.0, position=(0, 0.5, 0), easing="ease_in_out")
          c.layer(type="overlay", mask=[arm_l, arm_r], priority=1)
          c.save("idle.omanim")                 # written only if the script succeeds
Loops:    import math; [om.box(f"leg{i}", at=(math.cos(i*math.pi/2)*3, 1, math.sin(i*math.pi/2)*3)) for i in range(4)]
"""

# Install as the `om` module so user scripts `import om`.
_om = _types.ModuleType("om")
_om.__doc__ = HELP
for _k in ("FaceSelection", "Part", "Clip", "anim", "OmError", "box", "cube", "cylinder",
           "sphere", "cone", "pyramid", "plane", "wedge", "torus", "hemisphere",
           "cross", "sprite", "part", "parts", "mirror", "material", "summary",
           "help", "HELP", "math"):
    setattr(_om, _k, globals()[_k])
_sys.modules["om"] = _om
