"""upbge-jolt windowed bootstrap: start the nREPL server, then start the game
engine in the viewport. The game loop drives uj_host_tick (hooked into
KX_KetsjiEngine::NextFrame), so bridge calls marshal onto the game thread.

Run (no --background, so Blender opens a window):
  blender --python upbge_jolt_bootstrap_game.py
"""

import ctypes
import sys

import bpy


def main():
    exe = ctypes.CDLL(None)
    init = exe.uj_host_init
    init.restype = ctypes.c_int

    port = init()
    if port < 0:
        print("upbge-jolt: init failed", file=sys.stderr)
        return
    print(f"upbge-jolt: nREPL listening on port {port}", file=sys.stderr, flush=True)

    # Blocks while the game runs; the game loop pumps uj_host_tick each frame.
    bpy.ops.view3d.game_start()


if __name__ == "__main__":
    main()
