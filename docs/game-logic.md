# Game logic

A game's code is Clojure that registers handlers with `upbge-jolt.game` and
talks to the running engine through `upbge-jolt.bge`.

## main.clj: the game's code in the .blend

The game's code lives in a Text datablock named `main.clj` inside the .blend,
so saving the scene saves its logic. It is evaluated in the `user`
namespace when each game starts, whether from the viewport,
`(game/start!)`, `host/play.sh`, or a released app.

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
- Keyed registration replaces. Re-evaluating `(game/on-frame! :spin ...)`
  at the REPL hot-swaps the running handler.

When the game ends, UPBGE restores the pre-game scene. Edits made to Blender
data during the game are undone, and pointers held from before or during the
game become stale, so look them up again.

## The bge runtime API

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
starts with `(b/set! (b/get obj "game") "physics_type" "RIGID_BODY")`.
`"DYNAMIC"`, `"STATIC"`, `"CHARACTER"` and `"NO_COLLISION"` also work.

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

Call `(bge/mouse-look)` once per frame for mouse look. It returns this
frame's movement `[dx dy]`, normalised to the window size. On first use it
locks the pointer, which hides and grabs it. Blender then wraps the cursor
at the window edges and keeps positions continuous, so differences between
frames are pure mouse motion and a still mouse always reads zero.
`(bge/release-mouse-look!)` releases the lock, which you want on Esc, and
the next call re-locks. The lock is always released when the game ends.
Warping the cursor to the centre by hand with `bge/mouse!` is unreliable.
The warp is ignored while the window isn't focused, and the new position
only reads back on the next frame.

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

## 2D filters

Full-screen post-processing passes, run in pass order after the scene
renders. Built-in: `:blur :sharpen :grayscale :sepia :invert :sobel
:prewitt :laplacian :dilation :erosion`.

```clojure
(bge/add-filter! 0 :sepia)
(bge/add-filter! 1 :custom
  "uniform float strength;
   void main() {
     vec2 uv = bgl_TexCoord.xy;
     vec4 c = texture(bgl_RenderedTexture, uv);
     float v = smoothstep(0.9, 0.3, length(uv - 0.5));
     fragColor = vec4(c.rgb * mix(1.0 - strength, 1.0, v), c.a);
   }")
(bge/filter-uniform! 1 "strength" 0.45)   ; per frame if it changes
(bge/remove-filter! 1)
```

A custom program writes `fragColor` and can read:

- `bgl_RenderedTexture`, the frame so far, and `bgl_DepthTexture`, the scene
  depth from 0 near to 1 far, both read at `bgl_TexCoord.xy`.
- `bgl_RenderedTextureWidth` and `Height`, plus
  `bgl_TextureCoordinateOffset[9]`, the 3x3 neighbourhood for kernels.
- `gl_FragCoord`.
- its own `uniform float`/`vec2`/`vec3`/`vec4`, set with `filter-uniform!`.
  A uniform the program doesn't use is compiled out, and setting it is an
  error.

Classic BGE filter code, meaning `texture2D`, `gl_TexCoord[0].st` and
`gl_FragColor`, is accepted as written. Filters run on the scene-referred
image, before the view transform, so keep colors in linear terms. They are
dropped when the game ends, so add them from main.clj or `on-start!`.

## Shaders driven from game code

Feed per-frame game state into materials through the object's `color`, which
is RGBA. The shader reads it as a uniform, and changing it doesn't invalidate
the compiled material:

```clojure
;; in the editor: four parameters from the rendered object's color
(def p (nodes/object-params! nt))          ; {:r :g :b :a} output sockets
(nodes/link! nt (b/get (:r p) "node") "Red" phase-node 1)

;; in main.clj
(let [sea (bge/blender-object (bge/object "Sea")) clock (atom 0.0)]
  (game/on-frame! :sea (fn [dt] (b/set! sea "color" [(swap! clock + dt) 0 0 1]))))
```

Setting a Value node's `default_value` every frame also works, but it makes
EEVEE regenerate the material from its node tree every frame. That costs
real CPU for large trees like an ocean or sky graph, so reserve
`nodes/value!` for values that change rarely.

Two uses were verified in the running game at 60 fps.
- **Waves on CPU and GPU from one function.** Write the Gerstner function in
  the shader nodes as EEVEE displacement, with time from `color.r`, and the
  same function in Clojure for buoyancy sampling.
- **Masks.** A moving transparency footprint keeps the sea out of a hull.

## Performance

- A game at a fixed frame rate sleeps between frames, since `integrate.sh`
  restores the frame limiter UPBGE had commented out. An idle scene uses
  about 15% of a core at 60 fps, and the editor about 2% when idle.
- The game renders with EEVEE every frame, so ray tracing and high
  `taa_samples` are the main costs. Measure with `(bge/frame-info)`, and keep
  `use_raytracing` off and `taa_samples` low for play.
- Do bulk mesh edits with `b/foreach-set!` on things like vertex positions
  and `material_index`, not per-element calls.

## Audio

```clojure
(def engine (audio/play (audio/lowpass (audio/tone :sawtooth 55) 500 1) {:loop? true :volume 0.3}))
(audio/pitch! engine 1.8)          ; per frame, from RPM
(def water (audio/play (audio/lowpass (audio/noise 2.0) 900 0.7) {:loop? true :volume 0}))
(audio/volume! water 0.4)
(audio/stop! engine)
```

Tones are `:sine`, `:sawtooth`, `:square` and `:triangle`, alongside noise
and files. Shape them with `lowpass`, `highpass`, `volume` and `mix`.
Everything plays on Blender's output device.
