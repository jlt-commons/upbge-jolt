# Yacht demo: progress

Task: `example/PROMPT.md`. Work is done live over nREPL (see
`skills/upbge-jolt/SKILL.md` and `docs/`). Written by the session supervisor
after the second agent session was stopped, since that session didn't write
its own notes.

## Files

- `yacht.blend`: the scene, with `main.clj` stored in it (saved 2026-11-08
  18:49, after the second session). Open it in the editor to resume.
- `build.clj` (ns `yacht-build`, `(yacht-build/build!)`): ocean, sky, sun,
  HeroCam, look settings. **Done; don't retune.** The wave set is stored in
  the .blend as the Text `ocean-waves.edn`.
- `yacht.clj` (ns `yacht`, `(y/build!)`, `(y/materials!)`): the yacht. It
  builds a lofted deep-V hull and teak deck, a coachroof with side windows, a
  wraparound windscreen, a hardtop, a radar arch with dome, antennas and
  bimini, rails and stanchions, cleats, anchor, windlass and bow roller, nav
  lights, flag, swim platform with ladder and shower, a cockpit lounge with
  table and wet bar, a helm (console, two MFDs, compass, wheel, throttles,
  seats) and a sunpad. Below deck it builds the interior shell with windows,
  bulkheads, doors, a saloon (sofa, table, TV), galley, master cabin (bed,
  lamps, wardrobe), a head (vanity, mirror, toilet, shower), LED strips,
  interior lights and portholes. Cameras: HelmCam, ChaseCam, WalkCam,
  OrbitCam, IntroCam. HUD: Y-HUD, Y-Help and MFD text objects. Everything is
  parented to the hull object `Y-Yacht`.
- `main.clj` (ns `yacht-main`): the game logic. The yacht follows the same
  wave sum as the ocean shader (heave, pitch, roll) and main.clj owns speed,
  heading and planing attitude. The player walks in the yacht's local frame.
  Keys: E (helm on/off), W/S throttle, A/D steer, WASD + Shift walk, mouse
  look, C helm/chase camera, O orbit/photo, T time of day, H hide UI, P
  screenshot, F1 help, 1/2/3, Esc. There's an intro on start.
- `shot-intro.png`, `shot-walk.png`, `shot-walk2.png`: the last in-game
  screenshots.

## Resume

1. Open the scene in the editor: `host/run.sh editor $PWD/example/yacht/yacht.blend`
2. Connect over nREPL. Rebuilding the yacht from code is
   `(load-file ".../example/yacht/yacht.clj") (y/build!)`, but the .blend
   already holds the result.
3. Play: `(game/start!)`, then `(game/errors)`, screenshots, `(game/stop!)`.
   Or `host/play.sh example/yacht/yacht.blend`.
4. After edits to main.clj: `(game/set-main-file! ".../main.clj")` and
   `(bpy/save! ".../yacht.blend")`.

## Next

1. **Performance: stop setting Value nodes every frame.** `frame!` in
   main.clj (around line 349) sets five shader Value nodes each frame: wave
   time, and the hull mask's x, y, z and yaw. Each set makes EEVEE regenerate
   the ocean material from its large node tree every frame. Feed them through
   object colors instead with `nodes/object-params!`. The Ocean's color can
   carry time, mask x, mask y and yaw; z or a second set can come from
   another object. See `docs/game-logic.md`, "Shaders driven from game code".
2. Check the play experience end to end in a fresh game: intro skip, walk
   all rooms, stairs, doors, the helm and driving, the cameras, time of day.
3. Remaining PROMPT.md items: the guest cabin, wake and spray, audio (the
   engine, water and wind with `upbge-jolt.audio`), night lighting, quality
   setting, and the 10 screenshots from PROMPT.md section 10.
4. Write the README for the demo, per PROMPT.md section 9.

## Known problems

- **Fixed in session 4:** the GPU cost of the ocean. The Ocean is now 2.4 km
  and 5.7k vertices, casts no shadow, and HeroCam's clip_end is 4 km. The
  editor's Rendered view is responsive again (54 ticks/s at ~3% CPU).
- **The ocean material was switched from DISPLACEMENT to BUMP** to save GPU.
  The waves are now a lighting effect and no longer move geometry, which
  breaks PROMPT.md's "same wave function on CPU and GPU" visually. Restore
  displacement on the smaller ocean if fps allows, or displace only a
  near-camera patch.
- **Game fps is GPU-bound and varies with the view, 3-30 fps** (CPU ~5%).
  Measure over at least 25 s after any settings change: shader recompiles
  skew the average, and the intro camera's view changes the cost. Candidates:
  shadow-casting objects, the number of point lights, `use_fast_gi`,
  `taa_samples`.
- **main.clj still sets Value nodes every frame** (wave time and the hull
  mask). Move them to `nodes/object-params!`.
- **Session 4's Blender crashed** in the game's render, right after the live
  view's shading was changed while a game was running. `bpy/viewport!` now
  refuses that; stop the game first.
- Mouse warp (`bge/mouse!`) didn't take effect while the game window lacked
  the cursor, which made mouse look spin. main.clj guards against it. A
  person needs to check this in play.
- Setting materials one polygon at a time was very slow (49 s). Use bulk
  `foreach-set!` on `material_index`.
- Long builds can exceed the agent's nREPL request timeout. Split them into
  smaller evaluations.

## API gaps noted

- None blocking. See "Known problems".
