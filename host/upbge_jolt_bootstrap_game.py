"""upbge-jolt windowed bootstrap: start the nREPL server, then start the game
engine in the viewport. The game loop drives uj_host_tick (hooked into
KX_KetsjiEngine::NextFrame), so bridge calls marshal onto the game thread.

Run (no --background, so Blender opens a window):
  blender --python upbge_jolt_bootstrap_game.py

The game is started from a timer once the window has drawn, not from the
startup script itself: starting it before the first UI draw leaves one swap
buffer without the editor UI, and the window flickers between the two.
"""

import ctypes
import os
import sys

import bpy

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import upbge_jolt_portfile  # noqa: E402


def _view3d():
    for window in bpy.context.window_manager.windows:
        for area in window.screen.areas:
            if area.type == "VIEW_3D":
                for region in area.regions:
                    if region.type == "WINDOW":
                        return window, area, region
    return None


def _start_game():
    target = _view3d()
    if target is None:
        return 0.1  # window not up yet; try again
    window, area, region = target
    with bpy.context.temp_override(window=window, area=area, region=region):
        # Blocks while the game runs; the game loop pumps uj_host_tick each frame.
        bpy.ops.view3d.game_start()
    return None


def main():
    exe = ctypes.CDLL(None)
    init = exe.uj_host_init
    init.restype = ctypes.c_int

    port = init()
    if port < 0:
        print("upbge-jolt: init failed", file=sys.stderr)
        return
    print(f"upbge-jolt: nREPL listening on port {port}", file=sys.stderr, flush=True)
    path = upbge_jolt_portfile.write(port)
    print(f"upbge-jolt: wrote {path}", file=sys.stderr, flush=True)

    bpy.context.preferences.view.show_splash = False
    bpy.app.timers.register(_start_game, first_interval=0.5)


if __name__ == "__main__":
    main()
