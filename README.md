# upbge-jolt

A jolt (Clojure on Chez Scheme, no JVM) REPL embedded in UPBGE/Blender — a
live, editor-connectable scripting environment for the game engine, intended
to replace the Python/JavaScript APIs.

Connect CIDER/Calva/Cursive to the port in `.nrepl-port`, and evaluate
against the running editor or game:

```clojure
(require '[upbge-jolt.bridge :as b] '[upbge-jolt.bpy :as bpy])
(def cube (b/lookup (b/data) "objects" "Cube"))
(b/get cube "location")            ; => [1.0 2.0 3.0]
(b/set! cube "location" [10 20 30])

;; bpy.data.materials.new / node trees / links, as RNA function calls
(def mat (b/call (b/get (b/data) "materials") "new" {:name "Teak"}))
(b/set! mat "use_nodes" true)
(def nt (b/get mat "node_tree"))
(b/call (b/get nt "nodes") "new" {:type "ShaderNodeTexNoise"})

;; operators with properties, in an editor area when they need one
(bpy/ops "mesh.primitive_uv_sphere_add" {:radius 0.5 :location [2 0 1]})
(bpy/ops "view3d.view_all" {} {:area "VIEW_3D"})
(bpy/save! "/tmp/scene.blend")
```

`b/get` on a collection property returns its function struct (the `self` for
`b/call`, like `bpy.data.materials`); `b/coll` iterates it, `b/lookup` finds
items by name or index, `b/foreach-get`/`b/foreach-set!` move bulk data
(vertex coordinates, UVs, pixels). Enums take identifiers (`"SUN"`).

## How it fits together

```
┌────────────────────────── UPBGE / Blender process ──────────────────────────┐
│                                                                             │
│  frame loop ──► uj_tick ──► op queue drain (main-thread work runs here)     │
│       │                                                    ▲                │
│       │                                                    │ enqueue!       │
│  WM timer / KX_KetsjiEngine::NextFrame            nREPL session threads     │
│                                                        (jolt threads)      │
│  ┌──────────────────────────────────────────────────────────────────┐      │
│  │ libjolt_upbge.dylib — jolt build --library                      │      │
│  │   jolt_library_init / jolt_lookup / uj_* exports                │      │
│  │   upbge-jolt.core (queue, eval, nREPL lifecycle)                │      │
│  │   upbge-jolt.bridge (ujs_* defcfn bindings, typed marshalling)  │      │
│  └──────────────────────────────────────────────────────────────────┘      │
│                                     │ calls                                 │
│                                     ▼                                       │
│  ujs_* C symbols — the REAL shim: host/upbgejolt_shim.cc compiled into     │
│  the UPBGE build, implementing RNA get/set/collections over makesrna.      │
│  The MOCK shim (native/mock_shim.c) implements the same symbols over       │
│  plain C state, so the runtime is testable headlessly.                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

Two directions of calling:

- **host → jolt**: the host dlopens `libjolt_upbge`, runs `jolt_library_init`,
  resolves `uj_*` exports via `jolt_lookup`, and calls `uj_tick` from its
  frame loop (or a WM timer in the editor).
- **jolt → host**: the bridge namespaces bind `ujs_*` symbols. In the real
  host they resolve from the host process (the shim is compiled into the
  UPBGE binary with exported symbols); in tests they resolve from the mock
  shim dylib declared in `:jolt/native`. Same jolt code either way.

## Layout

- `src/upbge_jolt/core.clj` — op queue, tick, eval-string, nREPL lifecycle.
  Loadable with no host present.
- `src/upbge_jolt/bridge.clj` — typed RNA property access over `ujs_*`
  (scalars, fixed/dynamic float arrays, enums, pointers, collections).
- `src/upbge_jolt/library.clj` — the `ffi/export!` surface for
  `jolt build --library`: `uj_tick`, `uj_start_repl`, `uj_stop_repl`,
  `uj_eval`, `uj_ping`.
- `native/upbge_shim.h` — the C ABI contract both shims implement.
- `native/mock_shim.c` — mock host for headless tests (Scene/Cube/Lamp/Mesh).
- `host/harness.c` — C harness proving the embedding contract end to end.
- `host/upbgejolt_shim.cc` — the real shim over RNA (Blender 4.x API).
- `host/upbge_jolt.cc` — host integration: `uj_host_init` / `uj_host_tick`
  (dlopen `libjolt_upbge`, start the nREPL server, point the shim at the scene).
- `host/upbge_jolt_bootstrap.py` — headless bootstrap: loads the runtime and
  pumps `uj_host_tick` on the main thread (run with `blender -b --python ...`).
- `host/upbge_jolt_bootstrap_editor.py` — windowed editor bootstrap: pumps
  `uj_host_tick` from a `bpy.app.timers` callback so REPL edits show live.
- `host/upbge_jolt_bootstrap_game.py` — windowed game bootstrap: starts the
  game once the window has drawn; the game loop pumps the tick.
- `host/upbge_jolt_portfile.py` — writes `.nrepl-port` for the bootstraps.
- `host/integrate.sh` — drops the shim + integration into a cloned UPBGE
  tree, applies the build fixes, configures and builds.
- `test/` — clojure.test suites + runner.

## Tasks

```
jolt native   # build the mock shim dylib
jolt test     # unit tests (headless, mock shim)
jolt lib      # build libjolt_upbge.dylib (--library)
jolt metrics  # code-metrics report (jolt-metrics) over src
jolt upbge-deps  # clone UPBGE into native/upbge + build its dependencies
jolt upbge       # build the full UPBGE/Blender binary (deps first)
```

Harness (embedding contract check):

```
cc -o /tmp/uj_harness host/harness.c -ldl
/tmp/uj_harness ./libjolt_upbge.dylib
```

Build + run against the real engine (after `jolt upbge-deps` clones UPBGE):

```
host/integrate.sh                                   # integrate + build
UPBGE_JOLT_LIB=$PWD/libjolt_upbge.dylib \
  native/upbge/build_darwin/bin/Blender.app/Contents/MacOS/Blender \
  --background --python host/upbge_jolt_bootstrap.py
```

Every bootstrap writes the port to `.nrepl-port` in the project root
(override with `UPBGE_JOLT_PORT_FILE`) and removes it on exit, so editors and
agent nREPL tools autodiscover it.

Build a scene interactively in the editor (windowed, live viewport):

```
UPBGE_JOLT_LIB=$PWD/libjolt_upbge.dylib \
  native/upbge/build_darwin/bin/Blender.app/Contents/MacOS/Blender \
  --python host/upbge_jolt_bootstrap_editor.py
```

A timer pumps `uj_tick` at 60 Hz and redraws the 3D viewport, so `bpy`
edits from the REPL appear immediately. The viewport opens in EEVEE Rendered
mode through the scene camera, so materials, the world and displacement show
as they are built (`UPBGE_JOLT_VIEW=MATERIAL|SOLID|KEEP` to change it, or
`(bpy/viewport! {:shading "MATERIAL" :camera? false})` from the REPL). Press P in the viewport (or
`(bpy/ops "view3d.game_start")`) to play the scene.

Play a scene in the game engine (windowed), one command:

```
host/play.sh scene.blend
```

The game loop pumps `uj_tick` each frame (`uj_host_game_tick`, hooked into
`KX_KetsjiEngine::NextFrame`); when the game ends the editor pump takes over,
so the REPL stays up and the game can be restarted with `(game/start!)`.

## Game logic: upbge-jolt.game and upbge-jolt.bge

A game's code lives in a Text datablock named `main.clj` inside the .blend,
so saving the scene saves its logic. It is evaluated when each game starts.
Develop it at the REPL against the running game, then store it:

```clojure
(require '[upbge-jolt.game :as game] '[upbge-jolt.bge :as bge] '[upbge-jolt.bpy :as bpy])

(game/on-frame! :spin                      ; every frame while the game runs
  (fn [dt] (bge/rotate-to! (bge/object "Cube") [0 0 (* 0.5 (:clock (bge/frame-info)))])))
(game/start!)                              ; play in the viewport; (game/stop!) ends it

(game/set-main-file! "game/main.clj")      ; store the code in the .blend
(bpy/save! "scene.blend")
```

Frame handlers run on the host thread with the frame's delta time, are
dropped when the game ends, and a handler that throws is removed and kept in
`(game/errors)`. `bge` covers transforms, physics (velocity, force, torque,
impulse at a point, mass, damping), raycasts, parenting, spawning and removing
objects, camera switching, visibility, keyboard/mouse input, mouse warp and
visibility, screenshots and frame timing. `bge/blender-object` crosses to the
Blender Object behind a game object, so lights, materials and node values can
be changed through the RNA bridge while the game runs. KX handles are
validated on every call: a stale one raises instead of touching freed memory.

## Pointers and data generations

RNA pointers point into Blender's datablocks, which are replaced when a file
loads, on undo/redo, and when a game ends (UPBGE restores the pre-game
state). The bridge stamps every pointer with the host's data generation and
refuses one from an older generation with a "stale pointer" error instead of
reading freed memory: look it up again (`b/lookup`, `bpy/object`). Game-time
edits to Blender data are undone when the game ends, by the same restore.

## Shaders driven from game logic

`upbge-jolt.nodes` builds node trees; a Value node set every frame from
`game/on-frame!` feeds game state into shaders at full frame rate. Verified
in the running game at 60 fps: a time value animating EEVEE vertex
displacement (`displacement_method` "DISPLACEMENT", the GPU half of a wave
function whose CPU twin in jolt drives buoyancy), and a moving mask making
water transparent inside a footprint (`surface_render_method` "DITHERED"),
the basis for keeping the sea out of a hull.

## Threading contract

One jolt runtime per process. After `jolt_library_init`, the init thread
owns the runtime; `uj_tick` is exported `:collect-safe` so the host's frame
loop (same thread) drives collection safe points every frame. nREPL accept
and session threads are jolt's own; scene-touching work from a session
marshals onto the host thread via the op queue — `core/marshal` enqueues the
work and blocks the caller on an ebb dataflow variable, and `uj_tick` drains
it on the frame. Reentrant calls (from inside a tick-drained op) run inline.

## Status

- [x] Op queue + tick + nREPL start/stop/eval — tested headlessly
- [x] Thread marshalling: bridge calls run on the host tick thread via
      `core/marshal` (ebb dataflow variable), reentrant inline — tested with a
      tick-pump thread
- [x] Bridge: typed get/set, arrays (fixed + dynamic), enums, pointers,
      collections, introspection — against the mock shim
- [x] Creation: `id-new` / `mesh-from-pydata!` / `coll-add!` / `coll-remove!`
      (create meshes, build geometry, link objects into the scene graph) —
      against the mock shim; the real shim's DNA/BKE code is unverified
- [x] `--library` build + C harness (init/tick/eval/repl/rebind) — 14 checks
- [x] Real shim over RNA (host/upbgejolt_shim.cc) — compiles, links and runs
      against the live UPBGE engine: objects enumerate, get/set round-trips,
      and `bpy/add!` creates + links a mesh object
- [x] Operator layer: `ujs_operator_call` / `bridge/operator!` / `bpy/ops`
      runs a named operator (`bpy.ops` equivalent, e.g.
      "mesh.primitive_cube_add") — verified against the live engine
- [x] Engine layer (frame events): `core/frame-flow` emits the tick count each
      host frame as an ebb flow — the per-frame seam for driving the engine
- [x] Engine layer (KX): `ujs_kx_*` / `upbge-jolt.bge` accesses the running
      game's `KX_GameObject`s (name, world position) — verified against the
      live game engine (windowed; the game loop pumps `uj_tick`)
- [x] Editor-side pump (`bpy.app.timers`) for live editing in the windowed
      editor; game-side tick from NextFrame
- [x] bpy.data / bpy.context roots (resolved live, valid across file loads),
      operators with properties and an editor-area context, generic RNA
      function calls, collection lookup, bulk foreach get/set, int/bool
      arrays, enums by identifier, save/open — verified against the live editor
- [x] Game sessions: main.clj stored in the .blend and loaded on game start,
      per-frame handlers with error isolation, start/stop from the REPL,
      one-command play — verified across repeated start/stop cycles
- [x] KX runtime: transforms, physics, raycasts, hierarchy, spawning,
      cameras, input, mouse, screenshots — verified in the running game
- [x] Audio (upbge-jolt.audio): tones, noise, files, low/high-pass, mix,
      looping playback with per-frame pitch/volume on Blender's device
- [x] Game text objects (`bge/text!`) for HUDs and instruments
- [ ] 2D filters (`bge/add-filter!`): compile on Metal after the VERTEX_ID
      engine fix, but render wrong (white/black) and custom GLSL fails Metal
      translation; use EEVEE's TAA, ray tracing, fast GI, shadows,
      volumetrics and AgX/Filmic view transforms (all over RNA) meanwhile
- unchecked box = in progress or planned

## License

GPL-2.0-or-later (UPBGE's license).
