# Getting started

## Build

Prerequisites: [jolt](https://github.com/jolt-lang/jolt) (`jolt` on the
PATH), a C/C++ toolchain, CMake, and the UPBGE build dependencies.

```
jolt upbge-deps      # clone UPBGE into native/upbge and build its dependencies
host/integrate.sh    # add the shim and host integration, apply fixes, build
                     # Blender and the standalone player
jolt lib             # build libjolt_upbge.dylib (the jolt runtime)
jolt test            # headless tests against a mock host
```

`host/integrate.sh` is idempotent: rerun it after changing anything in
`host/` or `native/upbge_shim.h`. It builds incrementally (about a minute for
a shim change). `UPBGE_JOLT_TARGET=blenderplayer host/integrate.sh` builds
only the player, leaving a running Blender alone.

Jolt code in `src/` needs only `jolt lib`. While developing it, skip even
that: `(load-file "src/upbge_jolt/bge.clj")` over nREPL reloads a namespace in
the running engine.

## Run

```
host/run.sh editor   [scene.blend]    # live editing (the default mode)
host/run.sh play     scene.blend      # open and start the game (= host/play.sh)
host/run.sh headless [scene.blend]    # no window
```

No Python is involved. Blender's `main` has a startup hook
(`uj_host_start`, added by `integrate.sh`) that does nothing unless
`UPBGE_JOLT` names a mode; `host/run.sh` sets it. The hook:

- loads the runtime and starts the nREPL server;
- writes the port to `.nrepl-port` in the project root (override with
  `UPBGE_JOLT_PORT_FILE`) and removes it on exit;
- drives the runtime from a window-manager timer at 60 Hz;
- hands off to `upbge-jolt.boot` for the mode's setup.

**Editor** is the mode for building scenes. The viewport opens in EEVEE
Rendered mode through the scene camera, so materials, the sky and
displacement appear as they are built. Set `UPBGE_JOLT_VIEW=MATERIAL`,
`SOLID` or `KEEP` to change that, or call `(bpy/viewport! {...})`. Start the
game with P in the viewport or `(game/start!)`. When it ends, the editor
carries on and the REPL stays up.

A watchdog keeps a GPU-heavy scene from locking up the editor: the REPL is
served between viewport frames, so if Rendered frames repeatedly take
seconds, the viewport drops to Solid and a message on stderr explains why.
`UPBGE_JOLT_WATCHDOG=0` turns it off.

**Play** opens the file and starts the game once the window has drawn.

**Headless** has no window and no game engine: scripted scene generation and
tests.

## Connect

Any nREPL client works: CIDER, Calva, Cursive, `jolt`'s own client, or an
agent's nREPL tool. Clients that read `.nrepl-port` find the server without
being told the port.

```clojure
(require '[upbge-jolt.bpy :as bpy]
         '[upbge-jolt.bridge :as b]
         '[upbge-jolt.nodes :as nodes]
         '[upbge-jolt.game :as game]
         '[upbge-jolt.bge :as bge]
         '[upbge-jolt.audio :as audio])

(mapv bpy/name-of (bpy/objects))         ; => ["Cube" "Light" "Camera"]
(bpy/move! (bpy/object "Cube") [0 0 1])  ; the viewport updates
```

`set!` is a Clojure special form, so the bridge's setter is always written
`b/set!` and is never referred unqualified.

Next: [building scenes](scenes.md).
