# upbge-jolt

A jolt (Clojure on Chez Scheme, no JVM) REPL embedded in UPBGE/Blender — a
live, editor-connectable scripting environment for the game engine, intended
to replace the Python/JavaScript APIs.

Connect CIDER/Calva/Cursive to the port in `.nrepl-port`, and evaluate
against the running editor or game:

```clojure
(require '[upbge-jolt.bridge :as b])
(def cube (first (b/coll (b/root) "objects")))
(b/get cube "location")            ; => [1.0 2.0 3.0]
(b/set! cube "location" [10 20 30])
```

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
- `host/upbge_jolt_bootstrap.py` — Python bootstrap: loads the runtime and
  pumps `uj_host_tick` on the main thread (run with `blender -b --python ...`).
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

The bootstrap prints the nREPL port; connect an editor and evaluate against
the live scene (see the README top).

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
- [ ] Operator layer (`bpy.ops` equivalent: WM_operatortype_find etc.)
- [ ] Engine layer (KX scene/objects, sensors, input)
- unchecked box = in progress or planned
- Editor-side pump via WM_event_timer_add; game-side tick from NextFrame

## License

GPL-2.0-or-later (UPBGE's license).
