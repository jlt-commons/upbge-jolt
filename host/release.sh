#!/bin/bash
# Package a game as a standalone app: the UPBGE player with the jolt runtime,
# Blender's runtime resources, and the .blend (whose main.clj holds the game
# logic, see upbge-jolt.game).
#
#   host/release.sh scene.blend "Game Name" [out-dir]
#
# Produces <out-dir>/<Game Name>.app (macOS) and a .zip of it. The app opens
# the game windowed; pass -f for fullscreen. Set UPBGE_JOLT_REPL=1 (and
# optionally UPBGE_JOLT_PORT_FILE=path) when launching to attach an nREPL to
# the running release.
#
# Needs the player built: host/integrate.sh (or UPBGE_JOLT_TARGET=blenderplayer
# host/integrate.sh) and the runtime: jolt lib.
set -euo pipefail

[ $# -ge 2 ] || { echo "usage: $0 scene.blend \"Game Name\" [out-dir]" >&2; exit 2; }
BLEND="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
NAME="$2"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${3:-$ROOT/release}"
BIN="$ROOT/native/upbge/build_darwin/bin"
PLAYER="$BIN/Blenderplayer.app/Contents/MacOS/Blenderplayer"
RES="$BIN/Blender.app/Contents/Resources"
LIB="$ROOT/native/libjolt_upbge.dylib"

[ "$(uname -s)" = Darwin ] || { echo "release.sh packages macOS apps only for now" >&2; exit 1; }
for f in "$BLEND" "$PLAYER" "$LIB" "$RES/lib"; do
  [ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

APP="$OUT/$NAME.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

cp "$PLAYER" "$APP/Contents/MacOS/Blenderplayer"
# Blender's runtime: bundled dylibs (rpath ../Resources/lib), and the
# versioned datafiles, color management, fonts and python the player loads
cp -R "$RES/lib" "$APP/Contents/Resources/lib"
VERSION_DIR="$(cd "$RES" && ls -d [0-9]*.[0-9]* | head -1)"
cp -R "$RES/$VERSION_DIR" "$APP/Contents/Resources/$VERSION_DIR"
cp "$LIB" "$APP/Contents/Resources/libjolt_upbge.dylib"
cp "$BLEND" "$APP/Contents/Resources/game.blend"
[ -f "$RES/upbge_icon.icns" ] && cp "$RES/upbge_icon.icns" "$APP/Contents/Resources/game.icns"

# the bundle's entry point opens the packaged .blend in the player
cat > "$APP/Contents/MacOS/game" <<'EOF'
#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$DIR/Blenderplayer" -w "$@" "$DIR/../Resources/game.blend"
EOF
chmod +x "$APP/Contents/MacOS/game"

ID="$(printf '%s' "$NAME" | tr -cd '[:alnum:]' | tr '[:upper:]' '[:lower:]')"
cat > "$APP/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>$NAME</string>
  <key>CFBundleDisplayName</key><string>$NAME</string>
  <key>CFBundleIdentifier</key><string>org.upbge-jolt.$ID</string>
  <key>CFBundleExecutable</key><string>game</string>
  <key>CFBundleIconFile</key><string>game.icns</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>LSMinimumSystemVersion</key><string>11.0</string>
</dict>
</plist>
EOF

# ad-hoc signature so the modified bundle runs locally (distribute with a
# Developer ID signature and notarization instead)
codesign --force --deep --sign - "$APP" >/dev/null 2>&1 || echo "warning: codesign failed" >&2

(cd "$OUT" && rm -f "$NAME.zip" && ditto -c -k --keepParent "$NAME.app" "$NAME.zip")
echo "app: $APP"
echo "zip: $OUT/$NAME.zip ($(du -sh "$OUT/$NAME.zip" | cut -f1))"
