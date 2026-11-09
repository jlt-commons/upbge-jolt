#!/bin/bash
# Play a .blend in the game engine with the jolt runtime and nREPL server:
#   host/play.sh scene.blend
# Same as host/run.sh play. The scene's stored main.clj (upbge-jolt.game)
# loads when the game starts; when it ends, the editor and REPL stay up.
exec "$(dirname "$0")/run.sh" play "$@"
