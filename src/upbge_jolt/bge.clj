(ns upbge-jolt.bge
  "The running game engine (KX) surface — the `bge` equivalent. KX objects
  exist only while a game is running and carry runtime state (world
  transforms, physics, input). The RNA bridge (upbge-jolt.bpy) sees the
  editor-side data; `blender-object` crosses from a KX object to it, so
  lights, materials and node values can be changed while the game runs.

  KX object handles are validated on every call: one from a removed object
  or an earlier game run raises instead of touching freed memory. Per-frame
  game logic belongs in upbge-jolt.game/on-frame!."
  (:refer-clojure :exclude [name remove])
  (:require [upbge-jolt.bridge :as b]))

;; --- scene -------------------------------------------------------------------

(defn running?
  "Whether a game is running."
  []
  (b/kx-running?))

(defn objects
  "All KX objects in the running game, as pointer maps."
  []
  (b/kx-objects))

(defn object
  "The KX object named `name`, or nil."
  [name]
  (b/kx-object name))

(defn name
  "The name of KX object `obj`."
  [obj]
  (b/kx-name obj))

;; --- transforms --------------------------------------------------------------

(defn position
  "World position of `obj`, as [x y z]."
  [obj]
  (b/kx-position obj))

(defn move!
  "Set the world position of `obj`."
  [obj xyz]
  (b/kx-set-vec! obj :position xyz))

(defn orientation
  "World orientation of `obj` as a row-major 3x3 (9 doubles)."
  [obj]
  (b/kx-orientation obj))

(defn orient!
  "Set the world orientation of `obj` from a row-major 3x3 (9 doubles)."
  [obj m9]
  (b/kx-set-orientation! obj m9))

(defn euler->matrix
  "Row-major 3x3 for XYZ euler angles (radians), Blender's default order."
  [[x y z]]
  (let [cx (Math/cos x) sx (Math/sin x)
        cy (Math/cos y) sy (Math/sin y)
        cz (Math/cos z) sz (Math/sin z)]
    [(* cy cz) (- (* sx sy cz) (* cx sz)) (+ (* cx sy cz) (* sx sz))
     (* cy sz) (+ (* sx sy sz) (* cx cz)) (- (* cx sy sz) (* sx cz))
     (- sy)    (* sx cy)                  (* cx cy)]))

(defn rotate-to!
  "Set the world orientation of `obj` from XYZ euler angles (radians)."
  [obj xyz]
  (orient! obj (euler->matrix xyz)))

(defn axis
  "World direction of `obj`'s local axis :x, :y or :z (a column of its
  orientation)."
  [obj ax]
  (let [m (orientation obj)
        c (case ax :x 0 :y 1 :z 2)]
    [(nth m c) (nth m (+ 3 c)) (nth m (+ 6 c))]))

(defn scale
  "World scale of `obj`."
  [obj]
  (b/kx-vec obj :scale))

(defn scale!
  "Set the world scale of `obj`."
  [obj xyz]
  (b/kx-set-vec! obj :scale xyz))

;; --- physics -----------------------------------------------------------------

(defn velocity
  "Linear velocity (world, or local with :local)."
  ([obj] (b/kx-vec obj :linear-velocity))
  ([obj frame] (b/kx-vec obj (if (= frame :local) :local-linear-velocity :linear-velocity))))

(defn velocity!
  "Set linear velocity (world, or local with :local)."
  ([obj v] (b/kx-set-vec! obj :linear-velocity v))
  ([obj v frame] (b/kx-set-vec! obj (if (= frame :local) :local-linear-velocity :linear-velocity) v)))

(defn angular-velocity
  ([obj] (b/kx-vec obj :angular-velocity))
  ([obj frame] (b/kx-vec obj (if (= frame :local) :local-angular-velocity :angular-velocity))))

(defn angular-velocity!
  ([obj v] (b/kx-set-vec! obj :angular-velocity v))
  ([obj v frame] (b/kx-set-vec! obj (if (= frame :local) :local-angular-velocity :angular-velocity) v)))

(defn force!
  "Apply a force this frame (world, or local with :local)."
  ([obj f] (b/kx-apply! obj :force f))
  ([obj f frame] (b/kx-apply! obj :force f :local? (= frame :local))))

(defn torque!
  ([obj t] (b/kx-apply! obj :torque t))
  ([obj t frame] (b/kx-apply! obj :torque t :local? (= frame :local))))

(defn impulse!
  "Apply an impulse at world point `at` (e.g. buoyancy at a hull sample)."
  [obj impulse at]
  (b/kx-apply! obj :impulse impulse :at at))

(defn mass [obj] (b/kx-mass obj))
(defn mass! [obj m] (b/kx-set-mass! obj m))
(defn damping! [obj linear angular] (b/kx-set-damping! obj linear angular))
(defn suspend-dynamics! [obj] (b/kx-suspend-dynamics! obj true))
(defn restore-dynamics! [obj] (b/kx-suspend-dynamics! obj false))

(defn raycast
  "First hit from `from` to `to`, skipping `ignore`:
  {:object :point :normal} or nil."
  ([from to] (b/kx-raycast from to nil))
  ([from to ignore] (b/kx-raycast from to ignore)))

;; --- hierarchy and lifecycle -------------------------------------------------

(defn parent [obj] (b/kx-parent obj))

(defn parent!
  "Parent `obj` to `p` (it then moves with it); nil unparents."
  [obj p]
  (b/kx-set-parent! obj p))

(defn spawn!
  "Spawn a copy of object `name` (kept in an excluded collection, or any
  object) at `at`'s transform, living `lifespan` seconds (0 = forever)."
  ([name] (b/kx-spawn! name))
  ([name at] (b/kx-spawn! name at 0))
  ([name at lifespan] (b/kx-spawn! name at lifespan)))

(defn remove!
  "Remove a KX object at the end of the frame."
  [obj]
  (b/kx-remove! obj))

(defn camera!
  "Make camera `cam` the active one."
  [cam]
  (b/kx-set-camera! cam))

(defn visible!
  ([obj v?] (b/kx-set-visible! obj v? false))
  ([obj v? recursive?] (b/kx-set-visible! obj v? recursive?)))

(defn blender-object
  "The Blender Object behind `obj`, for RNA access while the game runs."
  [obj]
  (b/kx-blender-object obj))

;; --- input -------------------------------------------------------------------

(defn key-down?
  "Whether input `k` (bge event name: \"WKEY\", :LEFTSHIFTKEY, :LEFTMOUSE)
  is held."
  [k]
  (contains? (b/kx-input k) :active))

(defn key-pressed?
  "Whether input `k` went down this frame."
  [k]
  (contains? (b/kx-input k) :activated))

(defn key-released?
  "Whether input `k` went up this frame."
  [k]
  (contains? (b/kx-input k) :released))

(defn mouse
  "Normalized mouse position [x y] (0..1, origin top-left)."
  []
  (b/kx-mouse))

(defn mouse!
  "Warp the mouse to normalized [x y] (centering it each frame gives
  pointer-lock style mouse look)."
  [x y]
  (b/kx-set-mouse! x y))

(defn show-mouse!
  [visible?]
  (b/kx-show-mouse! visible?))

;; --- canvas ------------------------------------------------------------------

(defn screenshot!
  "Save the game view to `path` (PNG), written at the end of the frame."
  [path]
  (b/kx-screenshot! path))

(defn frame-info
  "{:fps :clock :frame-time}."
  []
  (b/kx-frame-info))

(defn canvas-size
  "[width height] in pixels."
  []
  (b/kx-canvas-size))
