#!/bin/bash
# Play a .blend in the game engine with the jolt runtime and nREPL server:
#   host/play.sh scene.blend      (or no argument for the default scene)
# The scene's stored main.clj (upbge-jolt.game) loads when the game starts;
# .nrepl-port in the project root points editors and agents at the REPL.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
case "$(uname -s)" in
  Darwin) BLENDER="$ROOT/native/upbge/build_darwin/bin/Blender.app/Contents/MacOS/Blender"; LIB=libjolt_upbge.dylib ;;
  *)      BLENDER="$ROOT/native/upbge/build_linux/bin/blender"; LIB=libjolt_upbge.so ;;
esac
export UPBGE_JOLT_LIB="${UPBGE_JOLT_LIB:-$ROOT/$LIB}"
exec "$BLENDER" "$@" --python "$ROOT/host/upbge_jolt_bootstrap_game.py"
