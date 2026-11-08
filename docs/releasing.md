# Releasing a game

```
host/release.sh scene.blend "Game Name" [out-dir]   # default out-dir: release/
```

This produces `Game Name.app` and `Game Name.zip`. The app contains:

- the UPBGE standalone player (`Blenderplayer`) with the jolt shim linked in
  and the runtime initialised before the game starts;
- Blender's runtime resources (bundled libraries, color management, fonts,
  scripts);
- `libjolt_upbge.dylib`, found by the player in the bundle's Resources;
- the .blend, as `Resources/game.blend`. Its stored `main.clj` is the game's
  code and runs when the game starts.

The app opens the game in a window. Arguments are passed to the player, so
`-f` gives fullscreen. Quit with Esc or by closing the window; the stock
player ignores SIGTERM.

## Before releasing

1. Store the final game code: `(game/set-main-file! ".../main.clj")`.
2. Save: `(bpy/save! ".../scene.blend")`.
3. Play the saved file fresh with `host/play.sh scene.blend`, and check
   `(game/errors)` is empty.
4. `jolt lib` if anything in `src/` changed, so the release gets the current
   runtime.

## Debugging a release

```
UPBGE_JOLT_REPL=1 UPBGE_JOLT_PORT_FILE=/tmp/game.port "release/Game Name.app/Contents/MacOS/game"
```

This starts the nREPL server inside the released game. Connect to the port
in `/tmp/game.port` to inspect it with `game/errors`, `bge/...` and the rest.
Releases don't start a server unless asked.

## Distribution

The bundle is ad-hoc signed, which is enough to run it locally. To
distribute it, sign with a Developer ID (`codesign --deep --options runtime
--sign "Developer ID Application: ..."`) and notarize (`xcrun notarytool`).
The app is about 340 MB zipped, mostly Blender's bundled libraries. Linux and
Windows packaging are not written yet.

## Requirements

The player must be built: `host/integrate.sh` configures `WITH_PLAYER=ON`
(`UPBGE_JOLT_TARGET=blenderplayer host/integrate.sh` builds only it).
