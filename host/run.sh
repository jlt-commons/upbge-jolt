#!/bin/bash
# Run UPBGE with the jolt runtime and its nREPL server (no Python involved):
#
#   host/run.sh editor   [scene.blend] [blender args]   live editing (default)
#   host/run.sh play     [scene.blend] [blender args]   open and start the game
#   host/run.sh headless [scene.blend] [blender args]   no window
#
# The port is written to .nrepl-port in the project root (override with
# UPBGE_JOLT_PORT_FILE) and removed on exit. UPBGE_JOLT_VIEW picks the editor
# viewport shading: RENDERED (default), MATERIAL, SOLID, or KEEP.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-editor}"
case "$MODE" in
  editor|play|headless) shift || true ;;
  *) MODE=editor ;;
esac
case "$(uname -s)" in
  Darwin) BLENDER="$ROOT/native/upbge/build_darwin/bin/Blender.app/Contents/MacOS/Blender"; LIB=libjolt_upbge.dylib ;;
  *)      BLENDER="$ROOT/native/upbge/build_linux/bin/blender"; LIB=libjolt_upbge.so ;;
esac
export UPBGE_JOLT="$MODE"
export UPBGE_JOLT_LIB="${UPBGE_JOLT_LIB:-$ROOT/$LIB}"
export UPBGE_JOLT_PORT_FILE="${UPBGE_JOLT_PORT_FILE:-$ROOT/.nrepl-port}"
if [ "$MODE" = headless ]; then
  exec "$BLENDER" -b "$@"
fi
exec "$BLENDER" "$@"
