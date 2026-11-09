# upbge-jolt

A live, editor-connectable scripting environment for the UPBGE/Blender game
engine, meant to replace the Python/JavaScript APIs. The REPL is jolt, which
is Clojure on Chez Scheme with no JVM involved.

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

`b/get` on a collection property returns its function struct, the `self` for
`b/call`, like `bpy.data.materials` in Python. `b/coll` iterates it and
`b/lookup` finds items by name or index. Vertex coordinates, UVs and pixels
move in bulk with `b/foreach-get` and `b/foreach-set!`. Enums take
identifiers, `"SUN"` for example.

## Features

- **Live REPL in the engine.** An nREPL server runs inside Blender in
  editor, game or headless mode, and inside released games. It writes
  `.nrepl-port` so editors and agents can find it. No Python is involved:
  a C startup hook in Blender's `main` loads the runtime.
- **The whole RNA data model.** Typed get and set on any property, covering
  arrays, enums by identifier and pointers. Collections and lookups, bulk
  `foreach-get` and `foreach-set!`, RNA function calls like
  `bpy.data.*.new` with node trees and links, and operators with properties
  and an editor-area context. The viewport updates as you edit.
- **Scene building.** Meshes from vertex and face lists, materials and
  shader node graphs through `upbge-jolt.nodes`, lights, cameras, worlds,
  EEVEE settings, save and open.
- **Safe pointers.** Pointers held across a file load, undo or game end are
  refused with a clear error instead of crashing the engine.
- **Game logic in Clojure.** The game's `main.clj` is stored in the .blend
  and runs on every start. Per-frame handlers come with error isolation
  and hot-swap from the REPL while the game runs.
- **Game runtime, `upbge-jolt.bge`.** Transforms, physics with forces,
  impulses, velocity and damping, raycasts, parenting, spawning, cameras,
  keyboard and mouse, pointer-locked mouse look, text objects for HUDs,
  screenshots and frame timing.
- **2D filters.** The built-in post-processing passes and custom GLSL
  filters, with the rendered image, scene depth and uniforms, work on macOS
  and Metal. Classic BGE filter code runs as written.
- **Audio, `upbge-jolt.audio`.** Tones, noise and sound files, low and
  high-pass filters, mixing, looping playback with per-frame pitch and
  volume.
- **Shaders driven from game code** through object color, without
  recompiling materials each frame.
- **Releases.** `host/release.sh` packages a .blend, the player and the
  runtime as a standalone macOS app.
- **Light on the machine.** Games sleep between frames, the editor idles at
  a few percent of a core, and a watchdog drops a GPU-bound viewport to
  Solid instead of letting it lock up the REPL.

See [the yacht example](example/yacht/README.md) for all of it in one game,
built by an agent over nREPL.

## Documentation

[`docs/`](docs/README.md) covers getting started, building scenes, game
logic, releasing, architecture, troubleshooting, and the [API
reference](docs/api.md), which is generated from docstrings by `jolt docs`.
For AI agents, [`skills/upbge-jolt/SKILL.md`](skills/upbge-jolt/SKILL.md)
is a condensed working guide.

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
│  │ libjolt_upbge.dylib - jolt build --library                      │      │
│  │   jolt_library_init / jolt_lookup / uj_* exports                │      │
│  │   upbge-jolt.core (queue, eval, nREPL lifecycle)                │      │
│  │   upbge-jolt.bridge (ujs_* defcfn bindings, typed marshalling)  │      │
│  └──────────────────────────────────────────────────────────────────┘      │
│                                     │ calls                                 │
│                                     ▼                                       │
│  ujs_* C symbols - the REAL shim: host/upbgejolt_shim.cc compiled into     │
│  the UPBGE build, implementing RNA get/set/collections over makesrna.      │
│  The MOCK shim (native/mock_shim.c) implements the same symbols over       │
│  plain C state, so the runtime is testable headlessly.                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

Two directions of calling:

- **host → jolt**: the host dlopens `libjolt_upbge`, runs `jolt_library_init`,
  resolves `uj_*` exports via `jolt_lookup`, and calls `uj_tick` from its
  frame loop, or from a WM timer in the editor.
- **jolt → host**: the bridge namespaces bind `ujs_*` symbols. In the real
  host they resolve from the host process, where the shim is compiled into
  the UPBGE binary with exported symbols. In tests they resolve from the
  mock shim dylib declared in `:jolt/native`. Same jolt code either way.

## Layout

- `src/upbge_jolt/core.clj`: op queue, tick, eval-string, nREPL lifecycle.
  Loadable with no host present.
- `src/upbge_jolt/bridge.clj`: typed RNA property access over `ujs_*`,
  covering scalars, fixed and dynamic float arrays, enums, pointers and
  collections.
- `src/upbge_jolt/library.clj`: the `ffi/export!` surface for
  `jolt build --library`, with `uj_tick`, `uj_start_repl`, `uj_stop_repl`,
  `uj_eval` and `uj_ping`.
- `native/upbge_shim.h`: the C ABI contract both shims implement.
- `native/mock_shim.c`: mock host for headless tests, covering
  Scene/Cube/Lamp/Mesh.
- `host/harness.c`: C harness proving the embedding contract end to end.
- `host/upbgejolt_shim.cc`: the real shim over RNA, for the Blender 4.x
  API.
- `host/upbge_jolt.cc`: host integration. `uj_host_start` is the startup
  hook in Blender's main, starting the runtime, the nREPL server, the port
  file and the WM-timer pump. Also `uj_host_tick`, `uj_host_game_tick` and
  `uj_host_init_player`. No Python.
- `src/upbge_jolt/boot.clj`: the jolt side of startup, with the live view,
  game start in play mode and the slow-viewport watchdog.
- `host/run.sh`: the `editor | play | headless` launcher. `host/play.sh`
  plays a file and `host/release.sh` packages a game.
- `host/integrate.sh`: drops the shim and integration into a cloned UPBGE
  tree, applies the build fixes, configures and builds.
- `test/`: clojure.test suites and a runner.

## Tasks

```
jolt native   # build the mock shim dylib
jolt test     # unit tests (headless, mock shim)
jolt lib      # build native/libjolt_upbge.dylib (--library)
jolt metrics  # code-metrics report (jolt-metrics) over src
jolt docs     # regenerate docs/api.md from the source docstrings
jolt upbge-deps  # clone UPBGE into native/upbge + build its dependencies
jolt upbge       # build the full UPBGE/Blender binary (deps first)
```

Harness, the embedding contract check:

```
cc -o /tmp/uj_harness host/harness.c -ldl
/tmp/uj_harness native/libjolt_upbge.dylib
```

Build + run against the real engine, after `jolt upbge-deps` clones UPBGE:

```
host/integrate.sh                       # integrate + build Blender and the player
host/run.sh editor [scene.blend]        # live editing
host/run.sh play scene.blend            # open and start the game (host/play.sh)
host/run.sh headless [scene.blend]      # no window
```

Blender itself starts the runtime. A hook in its `main`, `uj_host_start`,
runs when `UPBGE_JOLT` names a mode, which `host/run.sh` sets, and without
it Blender behaves as stock. The port goes to `.nrepl-port` in the project
root and is removed on exit, so editors and agent nREPL tools autodiscover
it. Set `UPBGE_JOLT_PORT_FILE` to put it elsewhere.

A WM timer pumps `uj_tick` at 60 Hz, and bridge writes notify the editors,
so `bpy` edits from the REPL appear immediately. The viewport opens in EEVEE
Rendered mode through the scene camera, so materials, the world and
displacement show as they are built. Set `UPBGE_JOLT_VIEW` to MATERIAL,
SOLID or KEEP to change it, or call `(bpy/viewport! {:shading "MATERIAL"
:camera? false})` from the REPL. Press P in the viewport, or run
`(bpy/ops "view3d.game_start")`, to play the scene.

Play a scene in the game engine, windowed, with one command:

```
host/play.sh scene.blend
```

The game loop pumps `uj_tick` each frame through `uj_host_game_tick`,
hooked into `KX_KetsjiEngine::NextFrame`. When the game ends the editor
pump takes over, so the REPL stays up and the game can be restarted with
`(game/start!)`.

## Releasing a game

```
host/release.sh scene.blend "Game Name"      # -> release/Game Name.app + .zip
```

The app is the UPBGE standalone player with the jolt runtime linked in,
plus Blender's runtime resources, `libjolt_upbge.dylib` and the .blend. The
game's `main.clj`, stored in the .blend, runs when it starts. It opens
windowed and needs no environment: the player finds the runtime beside its
executable or in the bundle's Resources, and you can pass `-f` for
fullscreen. To debug a running release, launch it with `UPBGE_JOLT_REPL=1`
and `UPBGE_JOLT_PORT_FILE=path` to get the port. The bundle is ad-hoc
signed, so sign with a Developer ID and notarize to distribute. Quit with
Esc or by closing the window, since the stock player ignores SIGTERM.
Building the player needs `host/integrate.sh`, which configures
`WITH_PLAYER=ON`, and `UPBGE_JOLT_TARGET=blenderplayer` builds only the
player.

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

Frame handlers run on the host thread with the frame's delta time, and are
dropped when the game ends. A handler that throws is removed and kept in
`(game/errors)`. `bge` covers transforms, physics with velocity, force,
torque, impulse at a point, mass and damping, raycasts, parenting, spawning
and removing objects, camera switching, visibility, keyboard and mouse
input, mouse warp and visibility, screenshots and frame timing.
`bge/blender-object` crosses to the Blender Object behind a game object, so
lights, materials and node values can be changed through the RNA bridge
while the game runs. KX handles are validated on every call, and a stale
one raises instead of touching freed memory.

## Pointers and data generations

RNA pointers point into Blender's datablocks, which are replaced when a
file loads, on undo/redo, and when a game ends, because UPBGE restores the
pre-game state. The bridge stamps every pointer with the host's data
generation and refuses one from an older generation with a "stale pointer"
error instead of reading freed memory. When that happens, look the item up
again with `b/lookup` or `bpy/object`. Game-time edits to Blender data are
undone when the game ends, by the same restore.

## Shaders driven from game logic

`upbge-jolt.nodes` builds node trees, and `nodes/object-params!` feeds game
state into shaders through the object's color, set every frame from
`game/on-frame!`. That is cheaper than setting a Value node, which makes
EEVEE regenerate the material each frame. Two uses were verified in the
running game at 60 fps. One is a time value animating EEVEE vertex
displacement with `displacement_method` set to "DISPLACEMENT", the GPU half
of a wave function whose CPU twin in jolt drives buoyancy. The other is a
moving mask making water transparent inside a footprint, through
`surface_render_method` "DITHERED", the basis for keeping the sea out of a
hull.

## Threading contract

One jolt runtime per process. After `jolt_library_init` the init thread
owns the runtime, and `uj_tick` is exported `:collect-safe` so the host's
frame loop, on the same thread, drives collection safe points every frame.
nREPL accept and session threads are jolt's own. Scene-touching work from a
session marshals onto the host thread through the op queue: `core/marshal`
enqueues the work and blocks the caller on an ebb dataflow variable, and
`uj_tick` drains it on the frame. Reentrant calls from inside a
tick-drained op run inline.

## License

GPL-2.0-or-later, UPBGE's license.
