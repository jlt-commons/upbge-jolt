# Yacht demo: progress

Task: `example/PROMPT.md`. Work is done live over nREPL, see
`skills/upbge-jolt/SKILL.md` and `docs/`.

## Files

- `yacht.blend`: the scene, with `main.clj` stored in it, saved 2026-11-08
  21:35. Open it in the editor to resume.
- `build.clj` (ns `yacht-build`): ocean, sky, sun, HeroCam, look settings.
  The wave set is the Text `ocean-waves.edn`. `(yacht-build/build!)` rebuilds
  the sea scene, and `(yacht-build/tune!)` re-applies the play settings and
  the ocean tuning to an existing scene without a rebuild.
- `yacht.clj` (ns `yacht`, `(yacht/build!)`, `(yacht/materials!)`): the
  yacht. `build!` deletes all `Y-*` objects and rebuilds, and it also
  recreates the `Y-WaveParams` empty and the ocean hull mask.
- `main.clj` (ns `yacht-main`): the game logic. Keys: E for helm on/off,
  W/S throttle, A/D steer, WASD plus Shift to walk, mouse look, C for the
  helm/chase camera, O orbit and photo, T time of day, H hide UI, P
  screenshot, F1 help, 1/2/3, Esc.
- `shots/`: showcase stills in JPEG, committed: `01-hero-exterior`,
  `02-side-profile`, `03-saloon-interior`, `04-helm`, `05-cockpit-lounge`,
  and the time-of-day set `06-golden-hour`, `07-midday`, `08-sunset`,
  `09-night`. The full-size PNGs in `screenshots/` and scratch `*.png` are
  git-ignored.
- Rebuilding from an empty scene: see README.md. The session-5 ocean
  tuning, that is ocean size, `clip_end` 4000 and no ocean shadow casting,
  is in the sources.

## Resume

1. Open the scene in the editor: `host/run.sh editor $PWD/example/yacht/yacht.blend`
2. Connect over nREPL. The .blend already holds the built scene. To
   re-apply the current tuning to it, `(load-file ".../build.clj")`
   then `(yacht-build/tune!)`.
3. Play: `(game/start!)`, then `(game/errors)`, screenshots,
   `(game/stop!)`. Or `host/play.sh example/yacht/yacht.blend`.
4. After edits to main.clj: `(game/set-main-file! ".../main.clj")` and
   `(bpy/save! ".../yacht.blend")`.

## Next

1. Play through in a fresh game: intro skip, walk every room, stairs,
   doors, the helm and driving, the cameras, time of day.
2. Remaining PROMPT.md items: the guest cabin, wake and spray, audio
   polish since engine, water and wind already exist through
   `upbge-jolt.audio`, dedicated night lighting, a quality setting, and
   finalising the 10 screenshots.

## Session 5 summary

- **Waves restored.** The `Ocean` material's `displacement_method` had been
  left on BUMP. It is back on DISPLACEMENT with `max_vertex_displacement`
  2.0, so the same wave sum moves geometry and the CPU heave, pitch and
  roll match it.
- **Per-frame Value nodes eliminated.** Wave time now arrives as the Ocean
  object's `color.r` through `nodes/object-params!`, and the hull mask
  reads `Y-WaveParams`' Object texture coordinates. main.clj moves that
  empty with the yacht. Nothing rewrites a Value node per frame, so EEVEE
  no longer regenerates the ocean material every frame. `build.clj` and
  `yacht.clj` were ported to this design, validated on a scratch material
  with 13 time links, a `HullMask` node feeding Alpha and displacement from
  the wave sum, so a rebuild reproduces the live material.
- **Framerate.** The ocean fragment shader is the frame cost, and hiding it
  gives about 60 fps. Cutting the LOD noise octaves recovered roughly
  8 fps, with ripple 7 to 3, ripple2 24 to 4, and foam 5 to 3. Play render
  is 720p, `taa_samples` 8, `use_fast_gi` off. Measured 39.0 fps average,
  min 35.9 and max 42.2, over 26 s at the boarding view looking forward
  over the water.
- **Scene fixes.** Bimini raised 0.62 m, with the yacht.clj z offset 2.90
  to 3.52 and the saved mesh raised to match. The aft interior bulkhead
  got a companionway header instead of a solid wall. A forward bulkhead
  was added so the master cabin no longer looks through the hull into the
  sea. Interior lights went from 22 to 55 W with shadows off. The stair
  region was widened to -5.36, because the deck hole starts at -5.35 and a
  5 cm floorless sliver blocked the stairs. The saloon sofa moved 0.85 m
  forward to clear the stair landing, with main.clj's obstacle and the
  start position updated.
- **Time of day.** `Y-WorldDay`, `Y-WorldSunset` and `Y-WorldNight` are
  tuned copies of the golden `SeaWorld`, and night adds stars and a moon.
  T cycles them.

## Known problems

- **GPU-bound on the ocean.** About 39 fps at 720p in the default view.
  The sea fragment shader dominates, and geometry displacement is not the
  cost. Further gains will need fewer noise octaves, simpler bump, or a
  smaller world-space LOD, not fewer vertices. Measure over at least 25 s
  after any change, because shader recompiles skew the average and the
  intro camera changes the cost.
- Mouse look uses `bge/mouse-look` with pointer lock. The earlier
  warp-to-centre code spun, and pointer lock fixed it, confirmed in a play
  session.
- Setting materials one polygon at a time is very slow. Use bulk
  `foreach-set!` on `material_index`.
- Long builds can exceed the agent's nREPL request timeout, so split them.
- `bpy/viewport!` refuses to change shading while a game is running, after
  session 4 crashed Blender by doing it. Stop the game first.

## API gaps noted

- `bge/camera` does not exist. Use `bge/camera!` or `bge/object`.
- `b/length` does not exist. Use `b/coll-count`.
- `aget-double` and `aset-double` are not available. `foreach-get` returns
  a double array and `foreach-set!` takes a seq or vector.
- Assigning `location` on a live KX object does not stick, because the KX
  world position overrides it. Edit mesh vertices, or use `bge/move!` or
  `bge/orient!`.
