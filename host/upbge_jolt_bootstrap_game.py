"""upbge-jolt windowed game bootstrap: the editor bootstrap (nREPL server,
.nrepl-port, tick pump) plus starting the game engine in the viewport.

Run (no --background, so Blender opens a window), optionally with a file:
  blender [scene.blend] --python upbge_jolt_bootstrap_game.py

While the game runs, its loop drives the tick (uj_host_game_tick from
KX_KetsjiEngine::NextFrame); when it ends, the editor pump takes over again,
so the REPL keeps working. The game's stored main.clj, if any, loads on start
(see upbge-jolt.game).

The game is started from a timer once the window has drawn, not from the
startup script itself: starting it before the first UI draw leaves one swap
buffer without the editor UI, and the window flickers between the two.
"""

import os
import sys

import bpy

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import upbge_jolt_bootstrap_editor as editor  # noqa: E402


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
        # Blocks while the game runs; the game loop pumps the tick each frame.
        bpy.ops.view3d.game_start()
    return None


def main():
    if editor.main() is None:
        return
    bpy.app.timers.register(_start_game, first_interval=0.5)


if __name__ == "__main__":
    main()
