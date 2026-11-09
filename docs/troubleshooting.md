# Troubleshooting

## Errors

**`stale pointer: the data it pointed into was replaced ...`**
A file load, an undo, or a game ending replaced the datablocks. Look the
item up again with `b/lookup` or `bpy/object`. In game code, look objects up
inside `on-start!` or `main.clj`, not before the game.

**`stale kx object` / `no game running`**
A game object handle from an earlier run, or one used after the game ended.
Get handles with `bge/object` while the game runs.

**`<op>: poll failed ...`**
The operator needs a context, usually a 3D view, a selection, or an active
object. Pass `{:area "VIEW_3D"}` for the view. Prefer an RNA call where one
exists.

**`X has no function y`**
Either `b/call` was given the object rather than its collection struct, so
use `(b/get owner "collection")`, or the function is Python-only, for
example `find`, `get`, `foreach_*` on collections. Use `b/lookup` and
`b/foreach-get`.

**`Can't change/establish root binding of: x with set`**
`set!` was written unqualified. Use `b/set!`.

**`no audio device`**
Sound is disabled in Blender's preferences, or there is no output device.

**A REPL eval never returns**
Bridge calls wait for the host thread. If the host isn't ticking, they wait,
which happens when a modal dialog is open or a script blocks the main
thread. The editor pump runs between viewport frames, so a viewport frame
that takes seconds delays every eval, see "Editor or game is slow" below.
The game pumps from its loop.

**`filter N failed to compile (GLSL error, see the console)`**
Blender's stderr has the shader compiler's message, with line numbers in
the generated source. On macOS the GLSL is translated to Metal, so names
the filter shader doesn't declare, like other `gl_*` built-ins, fail there.

**The viewport doesn't change**
Check the shading is Rendered or Material with `bpy/viewport!`, and that
the view is through the right camera. Displacement and the world only show
when rendered.

## Editor or game is slow

The engine sleeps between game frames and never spins the CPU while waiting
for the GPU, so a slow scene is GPU-bound. Check with `(:fps
(bge/frame-info))` in a game, or watch for the watchdog's message in the
editor. Common causes:

- **Shadows over very large or displaced surfaces.** The sun's shadow map
  has to cover every surface out to the camera's `clip_end`. A
  kilometres-wide displaced ocean with a 40 km clip distance was measured at
  about 12 s per frame. Keep big surfaces modest, lower `clip_end`, or turn
  off shadow casting on them with `visible_shadow`.
- **High `taa_samples`, ray tracing, and fast GI.**
- **Setting Value nodes every frame.** Use `nodes/object-params!` instead,
  see [game logic](game-logic.md).

## Known limitations

- **Overlay scenes.** UPBGE 0.5 has no `addScene`. Build HUDs from text
  objects and planes parented to the camera.
- **Releases** are packaged for macOS only, and are ad-hoc signed.
- **The standalone player ignores SIGTERM.** That's stock UPBGE behaviour.
  Quit with Esc, by closing the window, or with `(game/stop!)` over the
  REPL.
- **Game-time edits to Blender data** are undone when the game ends. This
  covers materials and lights reached through `bge/blender-object`, and
  it's UPBGE's restore that does it.
