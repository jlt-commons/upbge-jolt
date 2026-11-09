# Yacht demo: progress

Task: `example/PROMPT.md`. Work is done live over nREPL (see
`skills/upbge-jolt/SKILL.md` and `docs/`).

## Files

- `yacht.blend`: the scene, with `main.clj` stored in it (saved 2026-11-08
  21:35). Open it in the editor to resume.
- `build.clj` (ns `yacht-build`): ocean, sky, sun, HeroCam, look settings.
  Wave set is the Text `ocean-waves.edn`. `(yacht-build/build!)` rebuilds the
  sea scene; `(yacht-build/tune!)` re-applies the play settings and the ocean
  tuning to an existing scene without a rebuild.
- `yacht.clj` (ns `yacht`, `(yacht/build!)`, `(yacht/materials!)`): the yacht. `build!`
  deletes all `Y-*` objects and rebuilds; it also (re)creates the
  `Y-WaveParams` empty and the ocean hull mask.
- `main.clj` (ns `yacht-main`): the game logic. Keys: E (helm on/off), W/S
  throttle, A/D steer, WASD + Shift walk, mouse look, C helm/chase camera,
  O orbit/photo, T time of day, H hide UI, P screenshot, F1 help, 1/2/3, Esc.
- `shots/`: showcase stills (JPEG, committed): `01-hero-exterior`,
  `02-side-profile`, `03-saloon-interior`, `04-helm`, `05-cockpit-lounge`, and
  time of day `06-golden-hour`, `07-midday`, `08-sunset`, `09-night`. The
  full-size PNGs in `screenshots/` and scratch `*.png` are git-ignored.
- Rebuilding from an empty scene: see README.md. The session-5 ocean tuning
  (ocean size, `clip_end` 4000, no ocean shadow casting) is in the sources.

## Resume

1. Open the scene in the editor: `host/run.sh editor $PWD/example/yacht/yacht.blend`
2. Connect over nREPL. The .blend already holds the built scene; to re-apply
   the current tuning to it, `(load-file ".../build.clj") (yacht-build/tune!)`.
3. Play: `(game/start!)`, then `(game/errors)`, screenshots, `(game/stop!)`.
   Or `host/play.sh example/yacht/yacht.blend`.
4. After edits to main.clj: `(game/set-main-file! ".../main.clj")` and
   `(bpy/save! ".../yacht.blend")`.

## Next

1. Play through in a fresh game: intro skip, walk every room, stairs, doors,
   the helm and driving, the cameras, time of day.
2. Remaining PROMPT.md items: the guest cabin, wake and spray, audio polish
   (engine/water/wind already exist via `upbge-jolt.audio`), dedicated night
   lighting, a quality setting, and finalising the 10 screenshots.

## Session 5 summary

- **Waves restored.** The `Ocean` material's `displacement_method` had been
  left on BUMP; it is back on DISPLACEMENT with `max_vertex_displacement` 2.0,
  so the same wave sum moves geometry and the CPU heave/pitch/roll match it.
- **Per-frame Value nodes eliminated.** Wave time now arrives as the Ocean
  object's `color.r` (`nodes/object-params!`), and the hull mask reads
  `Y-WaveParams`' Object texture coordinates; main.clj moves that empty with
  the yacht. Nothing rewrites a Value node per frame, so EEVEE no longer
  regenerates the ocean material every frame. `build.clj`/`yacht.clj` were
  ported to this design (validated on a scratch material: 13 time links, a
  `HullMask` node feeding Alpha, displacement from the wave sum) so a rebuild
  reproduces the live material.
- **Framerate.** The ocean fragment shader is the frame cost (hiding it gives
  ~60 fps). Cutting the LOD noise octaves (ripple 7->3, ripple2 24->4,
  foam 5->3) recovered ~8 fps. Play render is 720p, `taa_samples` 8,
  `use_fast_gi` off. Measured 39.0 fps average (min 35.9, max 42.2) over 26 s
  at the boarding view, looking forward over the water.
- **Scene fixes.** Bimini raised 0.62 m (yacht.clj z offset 2.90 -> 3.52, and
  the saved mesh raised to match). Aft interior bulkhead given a companionway
  header instead of a solid wall; forward bulkhead added so the master cabin
  no longer looks through the hull into the sea; interior lights 22 -> 55 W
  with shadows off. Stair region widened to -5.36 (the deck hole starts at
  -5.35, so a 5 cm floorless sliver blocked the stairs). Saloon sofa moved
  +0.85 m forward to clear the stair landing, with main.clj's obstacle and the
  start position updated.
- **Time of day.** `Y-WorldDay`, `Y-WorldSunset`, `Y-WorldNight` are tuned
  copies of the golden `SeaWorld` (night adds stars and a moon); T cycles them.

## Known problems

- **GPU-bound on the ocean.** ~39 fps at 720p in the default view; the sea
  fragment shader dominates, geometry displacement is not the cost. Further
  gains will need fewer noise octaves, simpler bump, or a smaller world-space
  LOD, not fewer vertices. Measure over at least 25 s after any change:
  shader recompiles skew the average and the intro camera changes the cost.
- Mouse look uses `bge/mouse-look` (pointer lock). The earlier warp-to-centre
  code spun; pointer lock fixed it, confirmed in a play session.
- Setting materials one polygon at a time is very slow; use bulk
  `foreach-set!` on `material_index`.
- Long builds can exceed the agent's nREPL request timeout. Split them.
- `bpy/viewport!` refuses to change shading while a game is running (session 4
  crashed Blender by doing it). Stop the game first.

## API gaps noted

- `bge/camera` does not exist; use `bge/camera!` / `bge/object`.
- `b/length` does not exist; use `b/coll-count`.
- `aget-double` / `aset-double` are not available. `foreach-get` returns a
  double array and `foreach-set!` takes a seq/vector.
- Assigning `location` on a live KX object does not stick (KX world position
  overrides it); edit mesh vertices, or use `bge/move!`/`bge/orient!`.
