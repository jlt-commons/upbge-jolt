# Building scenes

Scene work goes through Blender's RNA, the same data model `bpy` exposes to
Python. A pointer is a map `{:owner :type :data :gen}`; properties are read
and written by their RNA names.

## Reading and writing properties

```clojure
(def cube (b/lookup (b/data) "objects" "Cube"))   ; bpy.data.objects["Cube"]
(b/get cube "location")                           ; => [0.0 0.0 0.0]
(b/set! cube "location" [0 0 2])
(b/set! cube "scale" [0.5 0.5 2])
(b/get (b/get cube "data") "name")                ; follow a pointer

(b/set! (b/lookup (b/data) "lights" "Light") "type" "SUN")   ; enums by identifier
(b/get cube "lock_location")                      ; bool/int arrays as vectors
(b/props cube)                                    ; introspection: every property
```

Writes run the property's RNA update, as `bpy` does, so the viewport, the
properties editor and dependent data update immediately.

Roots: `(b/root)` is the context scene, `(b/data)` is `bpy.data`, and
`(b/context)` is `bpy.context`. All three resolve live, so they stay valid
across file loads.

## Collections and functions

`b/get` on a collection property returns its function struct, the `self` for
`b/call`, as `bpy.data.materials` is in Python. `b/coll` iterates the items and
`b/lookup` finds one by name or index:

```clojure
(def mat (b/call (b/get (b/data) "materials") "new" {:name "Teak"}))
(b/call (b/get (b/get cube "data") "materials") "append" {:material mat})
(b/coll (b/root) "objects")                  ; every object in the scene
(b/lookup mesh "vertices" 0)                 ; by index
(b/call (b/get (b/data) "objects") "remove" {:object obj :do_unlink true})
```

`b/call` takes keyword arguments and returns the function's result: a
pointer, number, string or vector. It covers the RNA API: `nodes.new`,
`links.new`, `modifiers.new`, `images.new`, `texts.new`, `from_string`,
`select_set`, and so on. Python-only helpers such as
`bpy_prop_collection.find` and `keyframe_insert` are not RNA and are not available (set
keyframes through `animation_data` and F-curves, or drive values from frame handlers).

## Meshes

```clojure
(bpy/add! "Ramp" [[0 0 0] [4 0 0] [4 2 1] [0 2 1]] [[0 1 2 3]])  ; verts + faces
```

`bpy/add!` builds the mesh (from_pydata, with edges computed), creates an
object typed from its data, and links it into the scene. For lofted surfaces
(hulls, terrain), generate the vertex grid and the quads in Clojure. Bulk
attribute data moves with `b/foreach-get` and `b/foreach-set!`:

```clojure
(def m (b/get (bpy/object "Ramp") "data"))
(b/foreach-set! m "vertices" "co" (double-array [...]))   ; 3 values per vertex
```

## Operators

```clojure
(bpy/ops "mesh.primitive_uv_sphere_add" {:radius 0.5 :location [2 0 1] :segments 48})
(bpy/ops "object.modifier_add" {:type "SUBSURF"} {:area "VIEW_3D"})
(bpy/ops "view3d.view_all" {} {:area "VIEW_3D"})
```

Properties are given as a map and parsed by each property's RNA type.
`{:area "VIEW_3D"}` (or `"NODE_EDITOR"`, `"IMAGE_EDITOR"`, ...) runs the
operator in that editor's context, which operators that poll for a 3D view
need. A failure reports the operator's own message or poll error. Operators
act on the selection and the active object, so set those first:

```clojure
(b/call obj "select_set" {:state true})
(b/set! (b/get (b/get (b/context) "view_layer") "objects") "active" obj)
```

Prefer RNA calls over operators where both exist: they need no context and
cannot fail a poll.

## Materials and node trees

`upbge-jolt.nodes` wraps node-tree building:

```clojure
(def mat (nodes/material! "Sea"))
(def nt (nodes/tree mat))
(def bsdf (nodes/find nt "Principled BSDF"))
(nodes/set-input! bsdf "Roughness" 0.05)
(def noise (nodes/node! nt "ShaderNodeTexNoise" {:inputs {"Scale" 4.0}}))
(nodes/link! nt noise "Fac" bsdf "Roughness")
(def k (nodes/math! nt "MULTIPLY" nil 0.35))      ; Math sockets by index
(def t (nodes/value! nt "time" 0.0))              ; a parameter game code drives
(nodes/assign! (bpy/object "Sea") mat)
```

EEVEE vertex displacement (waves, terrain) needs
`(b/set! mat "displacement_method" "DISPLACEMENT")` and a Displacement node
on the output. Transparency uses `(b/set! mat "surface_render_method"
"DITHERED")`.

## World, light and look

```clojure
(def world (b/get (b/root) "world"))
(b/set! world "use_nodes" true)
(def sky (nodes/node! (nodes/tree world) "ShaderNodeTexSky" {}))
(def vs (b/get (b/root) "view_settings"))
(b/set! vs "view_transform" "AgX")
(def eevee (b/get (b/root) "eevee"))
(b/set! eevee "use_raytracing" true)
(b/set! eevee "use_fast_gi" true)
(b/set! eevee "taa_samples" 16)
```

The game renders with EEVEE, so these settings carry into it.

## Seeing your work

The editor's viewport shows the scene live. `(bpy/viewport! {:shading
"RENDERED" :camera? true})` switches shading and the camera view; call it
again after replacing the scene camera. For a still image:

```clojure
(b/set! (b/get (b/root) "render") "filepath" "/abs/path/shot.png")
(bpy/ops "render.render" {:write_still true})
```

## Saving and resuming

```clojure
(bpy/save! "/abs/path/scene.blend")
(bpy/open! "/abs/path/scene.blend")
```

Keep the code that builds a scene in files (for example `build.clj`) and the
game code in `main.clj`, stored in the .blend (see [game logic](game-logic.md)).
A later session, human or agent, resumes from the .blend plus those files.
Text datablocks (`(b/call (b/get (b/data) "texts") "new" {:name "notes.edn"})`
and `from_string`) carry extra state inside the .blend.

After a file load, undo, or a game ending, re-look-up anything you held:
older pointers are refused as stale (see [architecture](architecture.md)).
