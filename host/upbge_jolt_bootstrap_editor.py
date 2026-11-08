"""upbge-jolt editor bootstrap: start the nREPL server in a windowed Blender
and pump the jolt op queue from a bpy.app.timers callback, so REPL edits to
the scene show up live in the viewport.

Run (no --background):
  blender --python upbge_jolt_bootstrap_editor.py

Start the game from the viewport (P) or from the REPL with
(bpy/ops "view3d.game_start"); the game loop then drives the tick itself.
"""

import ctypes
import os
import sys

import bpy

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import upbge_jolt_portfile  # noqa: E402

STEP = 1.0 / 60.0

_tick = None


def _pump():
    _tick()
    # bridge writes go through RNA without notifiers; redraw so they show
    for window in bpy.context.window_manager.windows:
        for area in window.screen.areas:
            if area.type == "VIEW_3D":
                area.tag_redraw()
    return STEP


def main():
    """Start the runtime and the editor pump. Returns the port, or None."""
    global _tick
    exe = ctypes.CDLL(None)
    init = exe.uj_host_init
    init.restype = ctypes.c_int
    _tick = exe.uj_host_tick
    _tick.restype = ctypes.c_int

    port = init()
    if port <= 0:
        print("upbge-jolt: init failed", file=sys.stderr)
        return None
    print(f"upbge-jolt: nREPL listening on port {port}", file=sys.stderr, flush=True)
    path = upbge_jolt_portfile.write(port)
    print(f"upbge-jolt: wrote {path}", file=sys.stderr, flush=True)

    bpy.context.preferences.view.show_splash = False
    bpy.app.timers.register(_pump, first_interval=STEP, persistent=True)
    return port


if __name__ == "__main__":
    main()
