# Architecture

```
UPBGE / Blender process
├─ host integration (host/upbge_jolt.cc, compiled into Blender and the player)
│    uj_host_init / uj_host_init_player: dlopen libjolt_upbge, start the
│    runtime (and the nREPL server), release the init thread
│    uj_host_tick: editor/headless pump   uj_host_game_tick: game loop pump
├─ shim (host/upbgejolt_shim.cc, the ujs_* C ABI in native/upbge_shim.h)
│    RNA properties, collections, functions, operators; KX game objects,
│    physics, input, canvas, filters; audio over audaspace
└─ libjolt_upbge.dylib (jolt build --library)
     upbge-jolt.core    op queue, tick, frame flow, nREPL lifecycle
     upbge-jolt.bridge  typed FFI over ujs_*
     bpy / nodes / game / bge / audio   the Clojure surfaces
```

## Calls in both directions

- **Host to jolt.** The host dlopens the runtime, calls `jolt_library_init`,
  and resolves the `uj_*` exports. It calls `uj_tick` every frame from a WM
  timer in the editor, or from `KX_KetsjiEngine::NextFrame` (through
  `uj_host_game_tick`) in a game.
- **Jolt to host.** The bridge binds the `ujs_*` symbols. They resolve from
  the host process (the shim is linked into the binary), or from the mock
  shim in tests (`test/upbge_jolt/mock_host.clj` loads
  `native/libupbge_shim.dylib`). The mock must never be a project
  `:jolt/native`: a declared native resolves ahead of the process and would
  shadow the real shim.

## Threading

Blender's data is touched only on its main thread. nREPL sessions run on
jolt threads. `core/marshal` queues work for the host thread and blocks the
caller on an ebb dataflow variable, and `uj_tick` drains the queue. Calls
already on the host thread run inline (`*in-tick*`): that covers queued ops
and frame handlers, which run inside the tick. After starting the server the
host calls `jolt_library_release_thread`, so collections on jolt threads
never wait for the next tick. From then on the main thread re-enters jolt
only through the `:collect-safe` `uj_tick`.

## Pointer safety

- **RNA pointers** point into datablocks that Blender frees on file load,
  undo/redo, and game end (UPBGE restores the pre-game state). The shim
  keeps a data generation, bumped by load/undo/redo/game-end callbacks. The
  bridge stamps each pointer with it (`:gen`) and refuses a pointer from an
  older generation.
- **KX object handles** are checked against the running scene's object lists
  on every call.
- **Is a game running?** `KX_GetActiveScene()` is not cleared when a game
  ends, so the shim tracks this itself. It is set by `uj_host_game_tick` and
  cleared by `uj_host_tick` and by `KX_KetsjiEngine::StopEngine`.

## Engine patches

`host/integrate.sh` patches the UPBGE tree. Every patch is guarded, so the
script is safe to rerun. It:

- adds and links the shim into Blender and the player, and compiles it with
  the game engine's defines (the KX classes' layout and vtables depend on
  `WITH_PYTHON` and others);
- adds the startup hook in Blender's `main` (`uj_host_start`) and
  initialises the runtime in the player;
- hooks the game-loop tick and the stop flag;
- restores the game's frame-limiter sleep, and stops the Metal backend
  spinning a core while it waits for the GPU;
- fills every swap-chain buffer before a game starts, so the editor doesn't
  flicker while the game runs;
- makes 2D filters work on Metal: `gl_VertexID` and `gl_FragCoord` for the
  filter shaders, the rendered and depth textures bound by sampler binding
  (Metal samplers have no uniform location), depth read through a depth
  sampler, classic BGE GLSL translated, and filter passes freed when
  removed or replaced;
- keeps the editor's post-view drawing (gizmos, camera passepartout) out of
  a game's render, where on Metal it overwrote the scene depth;
- fixes the build on current macOS: the clang `[[deprecated]]` on a type, an
  EEVEE shader identifier that is a Metal keyword, the epoxy link for the
  game engine, and the draco target.

## Tests

`jolt test` runs the jolt layers headlessly against the mock shim
(`native/mock_shim.c`). The mock models what the bridge relies on: typed
properties, a scene collection, Empty-versus-mesh objects, a game-running
flag, audio handle state, and data generations. Behaviour that needs the
real engine is verified live over nREPL. CI runs `jolt test` on Linux
(x86_64) and macOS (arm64) for every push to main and every pull request
(`.github/workflows/tests.yml`).
Checks for the live engine are kept in `test/live/`: load one over nREPL
while a game runs, for example `(load-file "test/live/filters.clj")`, which
returns `{:failed []}` when every 2D filter compiles and runs.
