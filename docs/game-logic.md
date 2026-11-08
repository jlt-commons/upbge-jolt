# Game logic

A game's code is Clojure that registers handlers with `upbge-jolt.game` and
talks to the running engine through `upbge-jolt.bge`.

## main.clj: the game's code in the .blend

The game's code lives in a Text datablock named `main.clj` inside the .blend,
so saving the scene saves its logic. It is evaluated (in the `user`
namespace) when each game starts: from the viewport, `(game/start!)`,
`host/play.sh`, or a released app.

```clojure
(game/set-main-file! "/abs/path/main.clj")   ; store a source file in the scene
(game/set-main! "(println :hello)")          ; or a string
(game/main-source)                           ; what is stored
(bpy/save! "/abs/path/scene.blend")          ; keep it
```

Develop the code at the REPL against a running game, keep it in a source
file, and store it before saving.

## Sessions and frame handlers

```clojure
(game/on-frame! :spin                        ; every frame while the game runs
  (fn [dt] (bge/rotate-to! (bge/object "Cube") [0 0 (* 0.5 (:clock (bge/frame-info)))])))
(game/on-start! :setup (fn [] ...))          ; each game start, after main.clj
(game/on-stop! :reset (fn [] ...))           ; after the game ends
(game/start!)  (game/stop!)  (game/running?)
(game/errors)                                ; failures, oldest first
```

- Frame handlers get the frame's delta time in seconds and run on the host
  thread, so `bge` and `bridge` calls inside them are direct.
- Handlers are dropped when the game ends, so nothing outlives the game
  objects it captured. Register them from `main.clj` or an `on-start!` hook.
- A handler that throws is removed, and its error is kept in
  `(game/errors)`. The others keep running.
- Keyed registration replaces: re-evaluating `(game/on-frame! :spin ...)`
  at the REPL hot-swaps the running handler.

When the game ends, UPBGE restores the pre-game scene. Edits made to Blender
data during the game are undone, and pointers held from before or during the
game become stale: look them up again.

## The runtime API (bge)

Game objects come from `(bge/object "Name")` or `(bge/objects)`. Every call
validates its object, so a handle to a removed object or one from an earlier
run raises instead of crashing.

```clojure
(def boat (bge/object "Yacht"))
(bge/position boat)  (bge/move! boat [0 0 1])
(bge/orientation boat)                         ; row-major 3x3
(bge/rotate-to! boat [0 0 1.57])               ; XYZ euler, radians
(bge/axis boat :y)                             ; local Y in world space
(bge/scale boat)  (bge/scale! boat [1 1 1])
```

Physics needs a physics type on the Blender object, set before the game
starts: `(b/set! (b/get obj "game") "physics_type" "RIGID_BODY")` (or
`"DYNAMIC"`, `"STATIC"`, `"CHARACTER"`, `"NO_COLLISION"`).

```clojure
(bge/velocity boat)  (bge/velocity! boat [0 5 0] :local)
(bge/force! boat [0 2000 0] :local)            ; this frame
(bge/torque! boat [0 0 300])
(bge/impulse! boat [0 0 50] [1.0 -2.0 0.0])    ; at a world point: buoyancy
(bge/mass boat)  (bge/mass! boat 8000)  (bge/damping! boat 0.2 0.4)
(bge/raycast [0 0 5] [0 0 -5] player)          ; {:object :point :normal} or nil
```

Hierarchy, spawning, cameras:

```clojure
(bge/parent! player boat)          ; rides along; (bge/parent! player nil) frees it
(bge/spawn! "Spray" boat 1.5)      ; copy an object (kept in an excluded collection)
(bge/remove! obj)
(bge/camera! (bge/object "ChaseCam"))
(bge/visible! obj false)
```

Input uses bge event names: `"WKEY"`, `"LEFTSHIFTKEY"`, `"SPACEKEY"`,
`"ESCKEY"`, `"F1KEY"`, `"LEFTMOUSE"`, `"WHEELUPMOUSE"`, and so on.

```clojure
(bge/key-down? "WKEY")  (bge/key-pressed? "EKEY")  (bge/key-released? "EKEY")
(bge/mouse)                        ; normalized [x y]
(bge/show-mouse! false)
(bge/mouse! 0.5 0.5)               ; recenter each frame for mouse look
```

Mouse look: read `(bge/mouse)`, take the offset from `[0.5 0.5]` as the
delta, rotate, then `(bge/mouse! 0.5 0.5)`.

HUD and capture:

```clojure
(bge/text! (bge/object "SpeedLabel") "23.4 kn")   ; a text object, parented to the camera
(bge/screenshot! "/abs/path/shot.png")
(bge/frame-info)                                   ; {:fps :clock :frame-time}
(bge/canvas-size)
```

`(bge/blender-object obj)` returns the Blender Object behind a game object.
Through it, light energy, material node values and other data can be changed
while the game runs.

## Shaders driven from game code

A Value node set every frame feeds game state into a material at full frame
rate:

```clojure
(def t (nodes/value! nt "time" 0.0))     ; built in the editor
;; in main.clj, after looking the socket up again:
(let [t (-> (b/lookup (b/data) "materials" "Sea") nodes/tree (nodes/find "time") (nodes/socket :out 0))
      clock (atom 0.0)]
  (game/on-frame! :sea (fn [dt] (b/set! t "default_value" (swap! clock + dt)))))
```

This was verified at 60 fps in the running game, for two uses:
- **Waves on CPU and GPU from one function.** Write the Gerstner function in
  the shader nodes (EEVEE displacement) and the same function in Clojure for
  buoyancy sampling.
- **Masks.** A moving transparency footprint keeps the sea out of a hull.

## Audio

```clojure
(def engine (audio/play (audio/lowpass (audio/tone :sawtooth 55) 500 1) {:loop? true :volume 0.3}))
(audio/pitch! engine 1.8)          ; per frame, from RPM
(def water (audio/play (audio/lowpass (audio/noise 2.0) 900 0.7) {:loop? true :volume 0}))
(audio/volume! water 0.4)
(audio/stop! engine)
```

Tones (`:sine :sawtooth :square :triangle`), noise and files, shaped with
`lowpass`, `highpass`, `volume` and `mix`, play on Blender's output device.
