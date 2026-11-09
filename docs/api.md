# API reference

Generated from the source docstrings by `docs/gen_api.py` (`jolt docs`); do
not edit by hand. Guides: [getting started](getting-started.md),
[building scenes](scenes.md), [game logic](game-logic.md),
[releasing](releasing.md), [architecture](architecture.md),
[troubleshooting](troubleshooting.md).

Functions that touch Blender or the game (`bpy`, `bridge`, `nodes`, `game`,
`bge`, `audio`) marshal onto the host thread themselves, so they can be
called from any nREPL session and from game frame handlers alike.

Namespaces: [`upbge-jolt.bpy`](#upbge-joltbpy), [`upbge-jolt.bridge`](#upbge-joltbridge), [`upbge-jolt.nodes`](#upbge-joltnodes), [`upbge-jolt.game`](#upbge-joltgame), [`upbge-jolt.bge`](#upbge-joltbge), [`upbge-jolt.audio`](#upbge-joltaudio), [`upbge-jolt.core`](#upbge-joltcore)


## upbge-jolt.bpy

The ergonomic REPL surface over upbge-jolt.bridge: keyword access, object
lookup by name, transform helpers — the shape of bpy from Clojure.
Shapes and names track bpy where the mock's toy surface allows.

### `scene`

`(scene)`

The root scene pointer.

### `objects`

`(objects)`

All objects in the scene, as pointer maps.

### `name-of`

`(name-of obj)`

The name property of `obj`.

### `object`

`(object nm)`

Look up an object by name; nil when absent.

### `get`

`(get p k)`

Read property `k` (keyword or string) on `p`.

### `put!`

`(put! p k v)`

Write property `k` on `p`.

### `get-in`

`(get-in p ks)`

Nested property read: (get-in obj [:data :vertices]) walks pointer
properties then reads the final key.

### `pos`

`(pos obj)`

Location vector of `obj`.

### `move!`

`(move! obj xyz)`

Set the world location of `obj` to `xyz`.

### `translate!`

`(translate! obj dxyz)`

Add `dxyz` to the current location.

### `add-mesh!`

`(add-mesh! name verts faces)`

Create a mesh named `name` from vertex positions (a seq of [x y z]
triples) and faces (a seq of vertex-index seqs); returns the mesh.

### `add-object!`

`(add-object! name data)`

Create an object named `name` carrying `data` (a mesh pointer, or nil);
returns the object.

### `link!`

`(link! obj)`

Link `obj` into the scene's objects collection; returns it.

### `unlink!`

`(unlink! obj)`

Unlink `obj` from the scene's objects collection; returns it.

### `add!`

`(add! name verts faces)`

Create a mesh object and link it into the scene in one step. Returns the
object.

### `ops`

`(ops opname)` `(ops opname props)` `(ops opname props opts)`

Run the named operator (the `bpy.ops` equivalent), optionally with
properties and an editor area to run in:
(ops "mesh.primitive_cube_add" {:size 2 :location [0 0 1]})
(ops "view3d.view_all" {} {:area "VIEW_3D"}). Returns true.

### `data`

`(data)`

bpy.data.

### `context`

`(context)`

bpy.context.

### `save!`

`(save! path)`

Save the open file to `path` (wm.save_as_mainfile).

### `open!`

`(open! path)`

Open the .blend file at `path` (wm.open_mainfile).

### `delete!`

`(delete! obj)`

Remove `obj` from the file entirely (bpy.data.objects.remove).

### `viewport!`

`(viewport! {:keys [shading camera?] :or {shading "RENDERED"}})`

Set every 3D viewport's display, so REPL work is visible as it happens:
:shading "RENDERED" (EEVEE: materials, world sky, displacement, lights),
"MATERIAL" or "SOLID"; :camera? true looks through the scene camera.
Returns the number of viewports changed.


## upbge-jolt.bridge

Typed access to the host's RNA-style property surface, over the ujs_* C
ABI (native/upbge_shim.h). The bindings resolve from the mock shim dylib
(tests) or from the host process (a UPBGE build carrying the real shim).

Pointers cross as flat maps {:owner :type :data} rather than raw addresses
so they print, compare, and survive as REPL values. Strings returned by
the host are valid only until the next ujs_* call, so every get copies
into a jolt string immediately.

### `reset-host!`

`(reset-host!)`

Test hook: reset the mock host state.

### `root`

`(root)`

The root pointer (editor: context scene data / Main; mock: the Scene).

### `prop-find`

`(prop-find p name)`

Describe property `name` on `p`, or nil when absent.

### `props`

`(props p)`

All property descriptors of `p`.

### `get-float-array-n`

`(get-float-array-n p name)`

Read a dynamic-length float array property; returns a vector of doubles.

### `set-float-array-n!`

`(set-float-array-n! p name xs)`

Write a dynamic-length float array property from a seq of doubles.

### `coll-count`

`(coll-count p name)`

Length of collection property `name` on `p`.

### `coll`

`(coll p name)`

Vector of collection `name`'s members on `p`. Errors when `name` is not a
collection property. Realized inside (the iterator handle is freed before
returning), so members stay valid independent of later host calls.

### `id-new`

`(id-new type name)`

Create a new ID datablock of `type` (e.g. "Object", "Mesh"), named
`name`. Returns the pointer map.

### `object-new`

`(object-new name data)`

Create an Object named `name` carrying `data` (a mesh or other obdata
pointer; nil makes an Empty), typed from its data like
bpy.data.objects.new. Use this rather than (id-new "Object" ...), which
makes an Empty that ignores a later data assignment.

### `mesh-from-pydata!`

`(mesh-from-pydata! mesh verts faces)`

Build `mesh` geometry from `verts` (a seq of [x y z] triples) and `faces`
(a seq of vertex-index seqs), like bpy's Mesh.from_pydata. Returns the mesh.

### `coll-add!`

`(coll-add! coll name member)`

Link `member` into collection `name` on `coll` (e.g. the scene's
"objects"). Idempotent.

### `coll-remove!`

`(coll-remove! coll name member)`

Unlink `member` from collection `name` on `coll`. No-op when absent.

### `operator!`

`(operator! opname)`

Run the named operator (the `bpy.ops` equivalent, e.g.
"mesh.primitive_cube_add") with its default arguments. Returns true when
the operator finished.

### `kx-objects`

`(kx-objects)`

All KX objects in the running game, as pointer maps. Empty when no game is
running.

### `kx-object`

`(kx-object name)`

Find a KX object by name; nil when absent.

### `kx-name`

`(kx-name obj)`

The name of a KX object.

### `kx-position`

`(kx-position obj)`

World position of a KX object, as [x y z].

### `kx-move!`

`(kx-move! obj xyz)`

Set the world position of a KX object.

### `data`

`(data)`

bpy.data: the Main database (materials, meshes, images, objects, ...).

### `context`

`(context)`

bpy.context.

### `call`

`(call p fname)` `(call p fname kwargs)`

Call RNA function `fname` on `p` with keyword arguments, like bpy:
(call (coll-of (data) "materials") "new" {:name "Teak"}).
Returns the function's result (pointer map, number, string, vector) or nil.

### `op!`

`(op! opname)` `(op! opname props)` `(op! opname props {:keys [area]})`

Run operator `opname` with keyword properties, optionally in an editor
area: (op! "wm.save_as_mainfile" {:filepath "/tmp/x.blend"})
(op! "view3d.view_all" {} {:area "VIEW_3D"}). Returns true.

### `coll-ptr`

`(coll-ptr p name)`

The function-carrying struct of collection `name` on `p` -- the `self` for
(call ...) on things like bpy.data.materials.new or node_tree.links.new.

### `lookup`

`(lookup p name k)`

Collection item by name or index: (lookup (data) "objects" "Cube"),
(lookup mesh "vertices" 0). nil when absent.

### `foreach-get`

`(foreach-get p name attr n)`

Bulk-read attribute `attr` across collection `name` (foreach_get); `n` is
the total value count (items x attr width). Returns a double-array.

### `foreach-set!`

`(foreach-set! p name attr xs)`

Bulk-write attribute `attr` across collection `name` (foreach_set) from a
double-array or seq of numbers, item-major.

### `get`

`(get p name)`

Read property `name` on `p`. Float/int/bool arrays return vectors, enums
{:value n :idname "..."}, pointers maps or nil, collections their
function struct (for call).

### `set!`

`(set! p name v)`

Write property `name` on `p`. Arrays accept seqs; enums accept identifier
strings/keywords (a set or seq for flag enums) or numbers; pointers a map
or nil.

### `kx-running?`

`(kx-running?)`

Whether a game is running.

### `kx-end-game!`

`(kx-end-game!)`

End the running game.

### `kx-vec`

`(kx-vec obj what)`

Read a KX vector: :position :scale :linear-velocity :angular-velocity
:local-linear-velocity :local-angular-velocity.

### `kx-set-vec!`

`(kx-set-vec! obj what xyz)`

Write a KX vector (see kx-vec).

### `kx-orientation`

`(kx-orientation obj)`

World orientation as a row-major 3x3 vector of 9 doubles.

### `kx-set-orientation!`

`(kx-set-orientation! obj m9)`

Set world orientation from a row-major 3x3 (9 doubles).

### `kx-apply!`

`(kx-apply! obj what v & {:keys [at local?]})`

Apply :force, :torque or :impulse (at world point `at`) to `obj`.

### `kx-mass`

`(kx-mass obj)`

### `kx-set-mass!`

`(kx-set-mass! obj m)`

### `kx-set-damping!`

`(kx-set-damping! obj linear angular)`

### `kx-suspend-dynamics!`

`(kx-suspend-dynamics! obj suspend?)`

### `kx-raycast`

`(kx-raycast from to ignore)`

First physics hit from `from` to `to`, skipping `ignore` (or nil):
{:object kx-obj :point [x y z] :normal [x y z]} or nil on a miss.

### `kx-parent`

`(kx-parent obj)`

### `kx-set-parent!`

`(kx-set-parent! obj parent)`

Parent `obj` to `parent` (nil removes the parent).

### `kx-spawn!`

`(kx-spawn! name)` `(kx-spawn! name at lifespan)`

Spawn a copy of object `name` (typically one kept in an excluded/hidden
collection) at `at`'s transform (nil = the original's), living `lifespan`
seconds (0 = forever).

### `kx-remove!`

`(kx-remove! obj)`

### `kx-set-camera!`

`(kx-set-camera! cam)`

### `kx-set-visible!`

`(kx-set-visible! obj visible? recursive?)`

### `kx-blender-object`

`(kx-blender-object obj)`

The Blender Object (RNA pointer) a KX object came from: change its lights,
materials and other data through get/set!/call while the game runs.

### `kx-input`

`(kx-input k)`

Input state for a bge event name ("WKEY", :LEFTSHIFTKEY, "LEFTMOUSE"):
#{:active :activated :released} subset.

### `kx-mouse`

`(kx-mouse)`

Mouse position, normalized [x y] in 0..1.

### `kx-set-mouse!`

`(kx-set-mouse! x y)`

### `kx-show-mouse!`

`(kx-show-mouse! visible?)`

### `kx-screenshot!`

`(kx-screenshot! path)`

Save the game canvas to `path` (PNG).

### `kx-frame-info`

`(kx-frame-info)`

{:fps average :clock engine-seconds :frame-time last-timestep}.

### `kx-canvas-size`

`(kx-canvas-size)`

### `kx-set-text!`

`(kx-set-text! obj text)`

Set the string a game text object shows.

### `kx-add-filter!`

`(kx-add-filter! index type)` `(kx-add-filter! index type glsl)`

Add 2D filter pass `index` of `type` (a keyword from filter-types, or
:custom with a GLSL fragment program).

### `kx-remove-filter!`

`(kx-remove-filter! index)`

### `kx-filter-uniform!`

`(kx-filter-uniform! index name v)`

Set float uniform `name` (1-4 components) on filter pass `index`.


## upbge-jolt.nodes

Building node trees (materials, worlds, geometry nodes) from the REPL.

  (def mat (nodes/material! "Sea"))
  (def nt (nodes/tree mat))
  (def noise (nodes/node! nt "ShaderNodeTexNoise" {:inputs {"Scale" 4.0}}))
  (nodes/link! nt noise "Fac" (nodes/find nt "Principled BSDF") "Roughness")

Sockets are named ("Base Color") or indexed (0, 1 — needed for Math
nodes, whose inputs share the name "Value"). Game logic feeds shaders
through `object-params!` (the object's color, set every frame: wave time,
hull masks, time of day). Setting a Value node (`value!`) every frame also
works but makes EEVEE regenerate the material each frame, which is costly
for large node trees; use it for values that change rarely.

### `material!`

`(material! name)`

A new node-based material named `name`.

### `tree`

`(tree owner)`

The node tree of a material, world or light.

### `find`

`(find nt name)`

The node named `name` in tree `nt`, or nil.

### `socket`

`(socket node dir k)`

Socket `k` (name or index) of `node`; dir is :in or :out.

### `set-input!`

`(set-input! node k v)`

Set an unlinked input's default value.

### `node!`

`(node! nt type)` `(node! nt type {:keys [name props inputs]})`

Add a node of `type` (e.g. "ShaderNodeMath"). Options: :name, :props
(node properties such as {"operation" "MULTIPLY"}), :inputs (default
values by socket name or index).

### `link!`

`(link! nt from out to in)`

Link output `out` of `from` to input `in` of `to`.

### `math!`

`(math! nt op & consts)`

A Math node doing `op` ("ADD" "MULTIPLY" "SINE" ...), with constant
inputs given positionally (nil leaves an input for a link).

### `value!`

`(value! nt name v)`

A Value node named `name` (a shader parameter game code can drive);
returns its output socket, whose "default_value" is set per frame.

### `assign!`

`(assign! obj mat)`

Put material `mat` on object `obj`'s first slot (adding a slot if empty).

### `object-params!`

`(object-params! nt)`

Four shader parameters read from the rendered object's `color` property
(RGBA), the cheap way to feed per-frame game state into a material:
returns {:r :g :b :a} output sockets to link from. Drive them from game
code with (b/set! (bge/blender-object obj) "color" [r g b a]). Unlike
setting a Value node every frame, this does not invalidate the compiled
material, so EEVEE does not regenerate it each frame.


## upbge-jolt.game

Game sessions and per-frame logic, and the game's code stored in the
.blend file.

A game's jolt code lives in a Text datablock named "main.clj" inside the
.blend, so saving the scene saves its logic. When a game starts (from the
viewport, the REPL, or `blender file.blend --python
host/upbge_jolt_bootstrap_game.py`), main.clj is evaluated before the first
frame's handlers run. Develop it at the REPL, then store it with
`set-main!` (or `set-main-file!`) and save the file.

Handlers registered with `on-frame!` run every game frame on the host
thread, with the frame's delta time in seconds, and are dropped when the
game ends, so nothing outlives the KX objects it captured. A handler that
throws is removed and its error kept in `errors`.

### `running?`

`(running?)`

Whether a game session is in progress (as seen at the last tick).

### `on-frame!`

`(on-frame! k f)`

Run (f dt) every frame of the current game under `k` (replacing any handler
with that key). Cleared when the game ends.

### `off-frame!`

`(off-frame! k)`

### `on-start!`

`(on-start! k f)`

Run (f) when each game starts, after main.clj loads. Persists across runs.

### `on-stop!`

`(on-stop! k f)`

Run (f) after each game ends. KX objects are gone by then; use it to reset
jolt-side state.

### `errors`

`(errors)`

Errors from game handlers and main.clj, oldest first.

### `clear-errors!`

`(clear-errors!)`

### `main-text`

`(main-text)`

The "main.clj" Text datablock, or nil.

### `main-source`

`(main-source)`

The game's stored code, or nil.

### `set-main!`

`(set-main! code)`

Store `code` as the game's main.clj (creating the Text if needed). Save the
.blend to keep it.

### `set-main-file!`

`(set-main-file! path)`

Store the contents of the source file at `path` as main.clj.

### `load-main!`

`(load-main!)`

Evaluate the stored main.clj, if any. Returns the last value.

### `start!`

`(start!)`

Start the game in the 3D viewport. Returns immediately; the game runs on
the host thread until it ends (Esc, or `stop!`).

### `stop!`

`(stop!)`

End the running game (as Esc does).

### `install!`

`(install!)`

Hook the game driver into the host tick (idempotent). The library does
this at startup.


## upbge-jolt.bge

The running game engine (KX) surface — the `bge` equivalent. KX objects
exist only while a game is running and carry runtime state (world
transforms, physics, input). The RNA bridge (upbge-jolt.bpy) sees the
editor-side data; `blender-object` crosses from a KX object to it, so
lights, materials and node values can be changed while the game runs.

KX object handles are validated on every call: one from a removed object
or an earlier game run raises instead of touching freed memory. Per-frame
game logic belongs in upbge-jolt.game/on-frame!.

### `running?`

`(running?)`

Whether a game is running.

### `objects`

`(objects)`

All KX objects in the running game, as pointer maps.

### `object`

`(object name)`

The KX object named `name`, or nil.

### `name`

`(name obj)`

The name of KX object `obj`.

### `position`

`(position obj)`

World position of `obj`, as [x y z].

### `move!`

`(move! obj xyz)`

Set the world position of `obj`.

### `orientation`

`(orientation obj)`

World orientation of `obj` as a row-major 3x3 (9 doubles).

### `orient!`

`(orient! obj m9)`

Set the world orientation of `obj` from a row-major 3x3 (9 doubles).

### `euler->matrix`

`(euler->matrix [x y z])`

Row-major 3x3 for XYZ euler angles (radians), Blender's default order.

### `rotate-to!`

`(rotate-to! obj xyz)`

Set the world orientation of `obj` from XYZ euler angles (radians).

### `axis`

`(axis obj ax)`

World direction of `obj`'s local axis :x, :y or :z (a column of its
orientation).

### `scale`

`(scale obj)`

World scale of `obj`.

### `scale!`

`(scale! obj xyz)`

Set the world scale of `obj`.

### `velocity`

`(velocity obj)` `(velocity obj frame)`

Linear velocity (world, or local with :local).

### `velocity!`

`(velocity! obj v)` `(velocity! obj v frame)`

Set linear velocity (world, or local with :local).

### `angular-velocity`

`(angular-velocity obj)` `(angular-velocity obj frame)`

### `angular-velocity!`

`(angular-velocity! obj v)` `(angular-velocity! obj v frame)`

### `force!`

`(force! obj f)` `(force! obj f frame)`

Apply a force this frame (world, or local with :local).

### `torque!`

`(torque! obj t)` `(torque! obj t frame)`

### `impulse!`

`(impulse! obj impulse at)`

Apply an impulse at world point `at` (e.g. buoyancy at a hull sample).

### `mass`

`(mass obj)`

### `mass!`

`(mass! obj m)`

### `damping!`

`(damping! obj linear angular)`

### `suspend-dynamics!`

`(suspend-dynamics! obj)`

### `restore-dynamics!`

`(restore-dynamics! obj)`

### `raycast`

`(raycast from to)` `(raycast from to ignore)`

First hit from `from` to `to`, skipping `ignore`:
{:object :point :normal} or nil.

### `parent`

`(parent obj)`

### `parent!`

`(parent! obj p)`

Parent `obj` to `p` (it then moves with it); nil unparents.

### `spawn!`

`(spawn! name)` `(spawn! name at)` `(spawn! name at lifespan)`

Spawn a copy of object `name` (kept in an excluded collection, or any
object) at `at`'s transform, living `lifespan` seconds (0 = forever).

### `remove!`

`(remove! obj)`

Remove a KX object at the end of the frame.

### `camera!`

`(camera! cam)`

Make camera `cam` the active one.

### `visible!`

`(visible! obj v?)` `(visible! obj v? recursive?)`

### `blender-object`

`(blender-object obj)`

The Blender Object behind `obj`, for RNA access while the game runs.

### `key-down?`

`(key-down? k)`

Whether input `k` (bge event name: "WKEY", :LEFTSHIFTKEY, :LEFTMOUSE)
is held.

### `key-pressed?`

`(key-pressed? k)`

Whether input `k` went down this frame.

### `key-released?`

`(key-released? k)`

Whether input `k` went up this frame.

### `mouse`

`(mouse)`

Normalized mouse position [x y] (0..1, origin top-left).

### `mouse!`

`(mouse! x y)`

Warp the mouse to normalized [x y] (centering it each frame gives
pointer-lock style mouse look).

### `show-mouse!`

`(show-mouse! visible?)`

### `screenshot!`

`(screenshot! path)`

Save the game view to `path` (PNG), written at the end of the frame.

### `frame-info`

`(frame-info)`

{:fps :clock :frame-time}.

### `canvas-size`

`(canvas-size)`

[width height] in pixels.

### `text!`

`(text! obj s)`

Set the string a game text object shows (HUD labels, instrument
readouts; parent text objects to the camera to keep them on screen).

### `add-filter!`

`(add-filter! index type)` `(add-filter! index type glsl)`

Add 2D filter pass `index`: a built-in type (:blur :sharpen :grayscale
:sepia :invert :sobel :prewitt :laplacian :dilation :erosion
:motion-blur) or :custom with a GLSL fragment program reading
bgl_RenderedTexture.

### `remove-filter!`

`(remove-filter! index)`

### `filter-uniform!`

`(filter-uniform! index name v)`

Set a float uniform (1-4 components) on a custom filter pass.


## upbge-jolt.audio

Procedural and file audio on Blender's output device (audaspace).

Sounds are recipes: build them from tones, noise or files, shape them with
filters, and `play` one to get a handle whose pitch and volume can be driven
every frame (an engine note rising with RPM, water rush growing with
speed). Sounds and handles own native memory; `free!` them when done.

  (def engine (audio/play (audio/lowpass (audio/tone :sawtooth 55) 400 1) {:loop? true}))
  (audio/pitch! engine 1.8)
  (audio/stop! engine)

### `tone`

`(tone wave freq)`

An endless tone: wave is :sine :sawtooth :square or :triangle.

### `noise`

`(noise)` `(noise seconds)`

White noise, `seconds` long (loop it with {:loop? true}).

### `file`

`(file path)`

A sound file (wav, ogg, flac, mp3).

### `lowpass`

`(lowpass s freq q)`

### `highpass`

`(highpass s freq q)`

### `volume`

`(volume s v)`

### `mix`

`(mix a c)`

### `free!`

`(free! s)`

Release a sound (playing handles keep their own reference).

### `play`

`(play s)` `(play s {:keys [loop? volume pitch]})`

Play `s`; {:loop? true} loops forever. Returns a handle.

### `pitch!`

`(pitch! h v)`

Playback speed factor (1 = original).

### `volume!`

`(volume! h v)`

Gain (1 = original).

### `pause!`

`(pause! h)`

### `resume!`

`(resume! h)`

### `stop!`

`(stop! h)`

Stop and release a playing handle.


## upbge-jolt.core

The embedded runtime's own machinery: the op queue the host drains from
its frame loop (uj_tick), and the nREPL server lifecycle (uj_start_repl /
uj_stop_repl). This namespace must be loadable with NO host shim present —
it is   what jolt_library_init brings up first.

### `enqueue!`

`(enqueue! f)`

Queue `f` to run on the host thread at the next tick. Safe from any
thread.

### `tick`

`(tick)`

Drain the op queue. Runs on the host's calling thread — the only consumer,
so peek-then-pop needs no lock (enqueue! is many-producer, tick is
one-consumer). Ops enqueued during a tick wait for the next one. After the
drain, notifies every frame emitter with the new tick count.

### `after-ticks!`

`(after-ticks! n f)`

Run `f` on the host thread once `n` more ticks have run (n >= 1). For host
startup work that needs the window drawn first. A throwing f is reported
like a failed op.

### `on-host-thread?`

`(on-host-thread?)`

Whether the current thread is the host's tick/frame thread (inside a
tick-drained op).

### `marshal`

`(marshal f)`

Run `f` on the host's tick thread and return its value, blocking the caller
until it runs. Called from the tick thread itself (reentrantly) it runs
inline. Errors thrown by `f` are re-thrown on the calling thread.

Contract: the host must be ticking — draining the op queue — for a blocking
caller to return. If the host is not ticking, the caller blocks until it
resumes (the editor-side WM timer and the game-side frame loop are the two
pumps that guarantee this).

### `tick-count`

`(tick-count)`

Number of ticks run. Exposed for tests and for repl sessions to poll.

### `frame-flow`

`(frame-flow)`

An ebb discrete flow emitting the tick count each time `tick` runs (every
host frame). Each subscription registers an emitter that tick notifies; the
returned cleanup (run by ebb on cancellation) deregisters it. This is the
engine-layer seam: drive per-frame logic from the REPL with
`(m/reduce f nil (frame-flow))`.

### `pending-ops`

`(pending-ops)`

Ops currently queued. For tests.

### `eval-string`

`(eval-string s)`

Evaluate `s` and return its printed value, or the printed error. Never
throws.

### `start-repl!`

`(start-repl! port)` `(start-repl! port middleware)`

Start the embedded nREPL server. `port` 0 asks the OS for a free one.
Returns the stop fn from jolt.nrepl/start (its meta carries :port). No
port file is written — embedded mode; the host decides where to surface
the port.

### `stop-repl!`

`(stop-repl!)`

Stop the running nREPL server, if any.

### `eval-via-socket`

`(eval-via-socket port code)`

Eval `code` against the running server as an editor client would — an
nREPL client connect, session, eval, response-values. Test helper.
