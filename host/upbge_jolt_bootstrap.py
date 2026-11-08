"""upbge-jolt bootstrap: load libjolt_upbge into the running Blender process,
start the nREPL server, and pump the jolt op queue on the main thread.

Run:  blender -b --python upbge_jolt_bootstrap.py

The C entry points (uj_host_init / uj_host_tick) live in the blender binary
and are reached through ctypes.CDLL(None); bpy.app.timers keeps the process
alive and drives uj_host_tick on the main thread every 50ms.
"""

import ctypes
import os
import sys

import bpy

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import upbge_jolt_portfile  # noqa: E402

STEP = 0.05


def main():
    exe = ctypes.CDLL(None)

    init = exe.uj_host_init
    init.restype = ctypes.c_int
    tick = exe.uj_host_tick
    tick.restype = ctypes.c_int

    port = init()
    if port < 0:
        print("upbge-jolt: init failed", file=sys.stderr)
        return
    print(f"upbge-jolt: nREPL ready on port {port}", file=sys.stderr)
    path = upbge_jolt_portfile.write(port)
    print(f"upbge-jolt: wrote {path}", file=sys.stderr, flush=True)

    # Pump the op queue on the main thread (this script runs there, so the
    # bridge's marshalled RNA calls land on the main thread too). bpy.app.timers
    # does not keep --background mode alive, so a plain loop drives the tick
    # while the nREPL server (on its own jolt threads) serves clients.
    import time

    print("upbge-jolt: pumping op queue", file=sys.stderr)
    while True:
        tick()
        time.sleep(STEP)


if __name__ == "__main__":
    main()
