---
name: upbge-jolt
description: Build 3D scenes and games in a live UPBGE (Blender game engine) instance by evaluating Clojure over nREPL with upbge-jolt. Use when a task involves creating or editing Blender/UPBGE scenes, materials, node trees, game logic, physics, input, audio, or packaging a game, and an upbge-jolt engine is running (a .nrepl-port file in the project root) or can be started.
---

# upbge-jolt

UPBGE (the Blender game engine fork) runs with a jolt (Clojure) runtime and an
nREPL server inside it. You build scenes and games by evaluating Clojure in the
live engine; the 3D viewport shows the result immediately. Full docs:
`docs/` (start with `docs/README.md`), API reference `docs/api.md`.

## Connect

The port is in `.nrepl-port` in the project root. If no engine is running,
start one (editor mode, live viewport):

```
UPBGE_JOLT_LIB=$PWD/libjolt_upbge.dylib \
  native/upbge/build_darwin/bin/Blender.app/Contents/MacOS/Blender \
  [scene.blend] --python host/upbge_jolt_bootstrap_editor.py
```

Then in the REPL:

```clojure
(require '[upbge-jolt.bpy :as bpy] '[upbge-jolt.bridge :as b] '[upbge-jolt.nodes :as nodes]
         '[upbge-jolt.game :as game] '[upbge-jolt.bge :as bge] '[upbge-jolt.audio :as audio])
```

## Rules that prevent most mistakes

1. **Write the bridge setter as `b/set!`.** `set!` is a Clojure special
   form, so an unqualified `set!` never reaches the bridge.
2. **Collections are function structs.** `(b/get owner "materials")` returns
   the thing you `b/call`, as `bpy.data.materials` is in Python:
   `(b/call (b/get (b/data) "materials") "new" {:name "X"})`. Find items with
   `(b/lookup owner "objects" "Name")` or by index. Iterate with `b/coll`.
3. **Only RNA functions exist.** Python-only helpers (`find`, `get`,
   `keyframe_insert`, `foreach_get`) are not available. Use `b/lookup` and
   `b/foreach-get`/`b/foreach-set!`.
4. **Pointers go stale.** After a game run, a file load or undo, every
   pointer you held is refused with "stale pointer". Look things up again;
   don't keep pointers across those events.
5. **Game edits are undone.** When a game stops, UPBGE restores the
   pre-game scene. Build in the editor; use the game to run and test.
6. **Prefer RNA calls to operators.** Operators need context. When you must
   use one, pass properties and an area:
   `(bpy/ops "object.modifier_add" {:type "SUBSURF"} {:area "VIEW_3D"})`,
   with the object selected and active first.
7. **Use absolute paths** for every file you save, render or screenshot.

## Building scenes

```clojure
(bpy/add! "Name" verts faces)            ; mesh object from vertex/face lists, linked
(bpy/ops "mesh.primitive_uv_sphere_add" {:radius 0.5 :location [0 0 1]})
(b/set! obj "location" [0 0 1])  (b/set! obj "rotation_euler" [0 0 1.57])
(bpy/delete! obj)
(def mat (nodes/material! "Teak"))  (def nt (nodes/tree mat))
(nodes/node! nt "ShaderNodeTexNoise" {:inputs {"Scale" 40.0}})
(nodes/link! nt from "Fac" to "Roughness")   ; sockets by name, or index for Math
(nodes/math! nt "MULTIPLY" nil 2.0)          ; nil leaves an input for a link
(nodes/assign! obj mat)
(b/set! (b/lookup (b/data) "lights" "Sun") "type" "SUN")   ; enums by identifier
(b/props obj)                                ; discover property names
```

Lofted or procedural shapes: generate vertex grids and quads in Clojure and
pass them to `bpy/add!`. Waves and terrain: EEVEE displacement
(`(b/set! mat "displacement_method" "DISPLACEMENT")`). Look: `scene.eevee`
(`use_raytracing`, `use_fast_gi`, `taa_samples`) and `scene.view_settings`
(`view_transform` "AgX").

Check your work visually. Either `(bpy/viewport! {:shading "RENDERED"
:camera? true})` (call it again after changing the scene camera), or render
a still and read it:

```clojure
(b/set! (b/get (b/root) "render") "filepath" "/abs/shot.png")
(bpy/ops "render.render" {:write_still true})
```

## Game logic

Keep all game code in a `main.clj` file of requires, defs and handler
registrations. Store it in the scene so it runs on every game start, for
you, the user, and the released game:

```clojure
(game/set-main-file! "/abs/main.clj")
(bpy/save! "/abs/scene.blend")
```

In `main.clj`:

```clojure
(def boat (bge/object "Yacht"))                 ; handles: get them at game start
(game/on-frame! :drive
  (fn [dt]
    (when (bge/key-down? "WKEY") (bge/force! boat [0 4000 0] :local))
    (bge/text! (bge/object "Speed") (format "%.1f kn" (* 1.944 (Math/abs (second (bge/velocity boat :local))))))))
```

- Physics needs a type set in the editor first:
  `(b/set! (b/get obj "game") "physics_type" "RIGID_BODY")`.
  Use `"STATIC"` for floors and walls and `"CHARACTER"` or `"DYNAMIC"` for
  a player capsule.
- Buoyancy: `(bge/impulse! boat [0 0 f] point)` at sample points, using
  the same wave function as the shader.
- Mouse look: read `(bge/mouse)`, take the offset from 0.5 as the delta,
  then call `(bge/mouse! 0.5 0.5)` and `(bge/show-mouse! false)`.
- Riding a moving vehicle: `(bge/parent! player boat)`.
- Walking: `(bge/raycast from to player)` returns `{:object :point :normal}`
  or nil.
- Driving shaders: use `(nodes/object-params! nt)` in the editor, which
  gives `{:r :g :b :a}` sockets fed by the object's color. Per frame,
  `(b/set! (bge/blender-object obj) "color" [t 0 0 1])`. Don't set Value
  nodes every frame: that makes EEVEE regenerate the material each frame.
- Performance: the game renders with EEVEE every frame, so keep
  `use_raytracing` off and `taa_samples` low for play, and check
  `(:fps (bge/frame-info))`. Do bulk mesh edits with `b/foreach-set!`
  (positions, `material_index`), not per-element calls.
- Sound: `(audio/play (audio/lowpass (audio/tone :sawtooth 55) 500 1)
  {:loop? true})`, then call `audio/pitch!` and `audio/volume!` per frame.

Test loop:

```clojure
(game/start!)                                   ; returns immediately
(game/errors)                                   ; handler/main.clj failures
(bge/screenshot! "/abs/shot-1.png")             ; then read the image
(game/stop!)                                    ; then re-look-up any pointers
```

Re-evaluating `(game/on-frame! :key ...)` while the game runs hot-swaps
that handler.

## Long tasks: make work resumable

- Save often: `(bpy/save! ...)` after each milestone, with `main.clj`
  stored.
- Keep scene-building code in files (`build.clj` and so on), not only in
  REPL history.
- Keep a `PROGRESS.md` beside the scene: what's done, what's next, known
  problems, and how to rebuild. Note API gaps there with specifics.
- To resume, open the saved .blend in the editor, read the files, and
  inspect the live scene (`(mapv bpy/name-of (bpy/objects))`, `b/props`).

## Release

```
host/release.sh /abs/scene.blend "Game Name"   # -> release/Game Name.app + .zip
```

Before releasing, play the saved file fresh (`host/play.sh scene.blend`) and
make sure `(game/errors)` is empty. See `docs/releasing.md`.

## Known limitations

2D filters (`bge/add-filter!`) don't render correctly on macOS/Metal, so use
EEVEE settings for the look. There are no overlay scenes: build HUDs from
text objects parented to the camera. See `docs/troubleshooting.md`.
