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
  "Warp the mouse to normalized [x y]. Returns whether the warp took effect:
  the host ignores it when the game window is not the active window. The
  new position is read back from the next frame on. For mouse look use
  `mouse-look`, which handles both."
  [x y]
  (b/kx-set-mouse! x y))

(defn mouse-look-step
  "Pure mouse-look transition. `st` is the previous state (nil at first),
  `pos` this frame's normalized mouse position, `warped?` whether the warp
  to the centre issued after reading it took effect. Returns [st' [dx dy]]:
  movement since last frame, measured from where the cursor was last put
  (the centre after a warp that took effect, else where it was), so a still
  mouse reads zero whether or not the window holds the cursor."
  [st pos warped?]
  (let [[x y] pos
        delta (if-let [[rx ry] (:ref st)]
                [(- (double x) rx) (- (double y) ry)]
                [0.0 0.0])]
    [{:ref (if warped? [0.5 0.5] [(double x) (double y)])} delta]))

(declare frame-info)

(def ^:private mouse-look-state (atom nil))

(defn pointer-lock!
  "Lock (hide and grab) or release the pointer. While locked, mouse
  positions keep moving past the window edges, so differences between
  frames are pure mouse motion. Released automatically when the game ends."
  [lock?]
  (b/kx-pointer-lock! lock?))

(defn mouse-look
  "Mouse movement this frame as normalized [dx dy] (x right, y down), for
  first-person look. Call once per frame from a game/on-frame! handler. The
  first call locks the pointer (`pointer-lock!`); release it with
  (pointer-lock! false) (Esc, menus), and the next call locks it again. A
  still mouse always reads [0 0]."
  []
  (let [clock (:clock (frame-info))
        st @mouse-look-state
        ;; a new game run restarts the engine clock: start fresh
        st (when (and st (<= (:clock st) clock)) st)
        st (if (:locked st) st (do (pointer-lock! true) nil))
        [st' delta] (mouse-look-step st (mouse) false)]
    (reset! mouse-look-state (assoc st' :clock clock :locked true))
    delta))

(defn release-mouse-look!
  "Release the pointer (show the cursor); mouse-look re-locks on its next
  call."
  []
  (swap! mouse-look-state assoc :locked false)
  (pointer-lock! false))

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

;; --- text and post-processing ------------------------------------------------

(defn text!
  "Set the string a game text object shows (HUD labels, instrument
  readouts; parent text objects to the camera to keep them on screen)."
  [obj s]
  (b/kx-set-text! obj s))

(defn add-filter!
  "Add 2D filter pass `index`: a built-in type (:blur :sharpen :grayscale
  :sepia :invert :sobel :prewitt :laplacian :dilation :erosion
  :motion-blur) or :custom with a GLSL fragment program reading
  bgl_RenderedTexture."
  ([index type] (b/kx-add-filter! index type))
  ([index type glsl] (b/kx-add-filter! index type glsl)))

(defn remove-filter! [index] (b/kx-remove-filter! index))

(defn filter-uniform!
  "Set a float uniform (1-4 components) on a custom filter pass."
  [index name v]
  (b/kx-filter-uniform! index name v))
