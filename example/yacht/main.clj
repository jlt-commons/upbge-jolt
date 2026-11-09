(ns yacht-main
  "Game logic for the yacht demo. Stored in the .blend as main.clj and evaluated
  on every game start.

  The yacht is driven kinematically as a rigid body: the hull samples the same
  Gerstner/Stokes wave sum as the ocean shader (ocean-waves.edn) for heave,
  pitch and roll, and main.clj owns speed, heading and the planing attitude.
  The player walks in the yacht's local frame, so the whole boat can move under
  them without losing the walk surface."
  (:require [upbge-jolt.bge :as bge]
            [upbge-jolt.game :as game]
            [upbge-jolt.bpy :as bpy]
            [upbge-jolt.bridge :as b]
            [upbge-jolt.nodes :as nodes]
            [upbge-jolt.audio :as audio]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; wave model (must mirror build.clj / the ocean shader)
;; ---------------------------------------------------------------------------

(def waves
  (try (read-string (b/call (b/lookup (bpy/data) "texts" "ocean-waves.edn") "as_string"))
       (catch Exception _ [])))

(def PI Math/PI)
(defn clamp [x lo hi] (min hi (max lo x)))
(defn smooth [t] (let [t (clamp t 0.0 1.0)] (* t t (- 3.0 (* 2.0 t)))))
(defn lerp [a b t] (+ a (* (- b a) (double t))))
(defn vadd [[ax ay az] [bx by bz]] [(+ ax bx) (+ ay by) (+ az bz)])
(defn vsub [[ax ay az] [bx by bz]] [(- ax bx) (- ay by) (- az bz)])
(defn vmul [[x y z] s] [(* x s) (* y s) (* z s)])
(defn vcross [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by)) (- (* az bx) (* ax bz)) (- (* ax by) (* ay bx))])
(defn vlen [[x y z]] (Math/sqrt (+ (* x x) (* y y) (* z z))))
(defn vnorm [v] (let [l (vlen v)] (if (< l 1e-9) [0.0 0.0 1.0] (vmul v (/ 1.0 l)))))

(defn wave-h
  "Exact GPU wave height at world (x,y) and time t."
  [x y t]
  (reduce
   (fn [h {:keys [len amp ang harm]}]
     (let [k (/ (* 2.0 PI) len)
           om (Math/sqrt (* 9.81 k))
           th (* (/ ang 180.0) PI)
           ph (- (* k (+ (* (Math/sin th) x) (* (Math/cos th) y))) (* om t))]
       (+ h (* amp (+ (Math/sin ph) (* harm (Math/sin (* 2.0 ph))))))))
   0.0 waves))

;; ---------------------------------------------------------------------------
;; yacht geometry constants (mirror yacht.clj)
;; ---------------------------------------------------------------------------

(defn profile [pts x]
  (loop [i 0]
    (if (>= i (dec (count pts)))
      (double (second (peek pts)))
      (let [[x0 y0] (nth pts i) [x1 y1] (nth pts (inc i))]
        (if (<= x x1)
          (lerp y0 y1 (smooth (/ (- x x0) (- x1 x0))))
          (recur (inc i)))))))

(defn deck-z [y]
  (let [u (clamp (/ (+ y 11.0) 21.4) 0.0 1.0)
        zs (profile [[0.0 1.52] [0.15 1.55] [0.35 1.62] [0.55 1.78] [0.72 2.00]
                     [0.85 2.24] [0.94 2.42] [1.0 2.52]] u)]
    (+ zs 0.10)))

(def ^:private roof-w [[-2.0 1.92] [0.0 1.92] [2.0 1.82] [4.0 1.58]
                       [6.0 1.30] [8.0 0.86] [8.8 0.26]])
(def ^:private roof-top [[-2.0 2.60] [0.0 2.57] [2.0 2.50] [4.0 2.42]
                         [6.0 2.33] [8.0 2.25] [8.8 2.22]])
(defn roof-half [y] (profile roof-w (clamp y -2.0 8.8)))
(defn roof-z [y] (profile roof-top (clamp y -2.0 8.8)))
(defn hull-half [y]
  (let [u (clamp (/ (+ y 11.0) 21.4) 0.0 1.0)]
    (profile [[0.0 2.32] [0.12 2.62] [0.30 2.80] [0.50 2.80] [0.62 2.72]
              [0.74 2.48] [0.84 2.02] [0.91 1.42] [0.96 0.78] [1.0 0.06]] u)))

;; ---------------------------------------------------------------------------
;; state + object cache
;; ---------------------------------------------------------------------------

;; Start on the starboard side of the swim platform: at x=0 the stern flag pole
;; is right in front of the eye. yaw is slightly to port so the transom steps
;; and cockpit are in view.
(def start-pos [1.25 -11.70 0.42])
(def start-yaw 0.18)

(def S (atom
        {:t 0.0
         :yacht {:pos [24.0 -16.0 0.0] :yaw 0.0 :pitch 0.0 :roll 0.0
                 :speed 0.0 :throttle 0.0 :target-pitch 0.0 :target-roll 0.0}
         :mode :intro
         :intro-t 0.0
         :player {:pos start-pos :yaw start-yaw :pitch 0.0}
         :driving false
         :chase false
         :orbit {:pos [10.0 -26.0 9.0] :yaw -0.6 :pitch -0.15}
         :ui true
         :help false
         :look true
         :msg ""
         :msg-t 0.0
         :time :golden
         :doors {:master {:angle 0.0 :open false}
                 :head {:angle 0.0 :open false}}
         :audio nil
         :audio-started false}))

(def R (atom {}))          ; cached KX objects / editor handles
(def once-init (atom false))

(defn msg! [s] (swap! S assoc :msg s :msg-t 2.6))

;; ---------------------------------------------------------------------------
;; input names
;; ---------------------------------------------------------------------------

(def once-keys ["EKEY" "CKEY" "OKEY" "HKEY" "PKEY" "TKEY" "F1KEY" "ESCKEY"
                "ONEKEY" "TWOKEY" "THREEKEY" "LEFTMOUSE"])

;; ---------------------------------------------------------------------------
;; collision / walk surfaces (yacht local)
;; ---------------------------------------------------------------------------

(defn in-rect [x y x0 x1 y0 y1] (and (>= x x0) (<= x x1) (>= y y0) (<= y y1)))

(def hole-x [-1.78 -0.68])
(def hole-y [-5.35 -2.15])
(defn in-hole? [x y] (in-rect x y (nth hole-x 0) (nth hole-x 1) (nth hole-y 0) (nth hole-y 1)))

(def regions
  [{:n :swim :b (fn [x y] (in-rect x y -2.12 2.12 -12.38 -10.92)) :f (fn [x y] 0.42)}
   {:n :transom :b (fn [x y] (in-rect x y -1.80 1.80 -10.94 -9.85))
    :f (fn [x y] (lerp 0.42 (deck-z -9.85) (smooth (/ (- y -10.94) 1.09))))}
   {:n :cockpit :b (fn [x y] (and (in-rect x y -2.22 2.22 -9.90 -2.15) (not (in-hole? x y))))
    :f (fn [x y] (+ (deck-z y) 0.02))}
   {:n :deck-p :b (fn [x y] (and (>= y -2.30) (<= y 7.20) (>= x -2.55) (<= x (- -1.90))))
    :f (fn [x y] (+ (deck-z y) 0.06))}
   {:n :deck-s :b (fn [x y] (and (>= y -2.30) (<= y 7.20) (<= x 2.55) (>= x 1.90)))
    :f (fn [x y] (+ (deck-z y) 0.06))}
   {:n :ramp-p :b (fn [x y] (and (>= y -2.30) (<= y -1.25) (>= x -2.42) (<= x -1.86)))
    :f (fn [x y] (lerp (+ (deck-z y) 0.06) (+ (roof-z y) 0.10)
                       (smooth (/ (- y -2.30) 1.05))))}
   {:n :ramp-s :b (fn [x y] (and (>= y -2.30) (<= y -1.25) (<= x 2.42) (>= x 1.86)))
    :f (fn [x y] (lerp (+ (deck-z y) 0.06) (+ (roof-z y) 0.10)
                       (smooth (/ (- y -2.30) 1.05))))}
   {:n :foredeck :b (fn [x y] (and (>= y -1.30) (<= y 7.40) (<= (Math/abs (double x)) (- (roof-half y) 0.14))))
    :f (fn [x y] (+ (roof-z y) 0.10))}
   {:n :sunpad :b (fn [x y] (in-rect x y -1.28 1.28 2.68 5.72))
    :f (fn [x y] (+ (roof-z y) 0.23))}
   {:n :interior :b (fn [x y] (and (>= y -2.28) (<= y 5.98) (<= (Math/abs (double x)) 1.45)))
    :f (fn [x y] -0.33)}
   {:n :stairs :b (fn [x y] (in-rect x y -1.66 -0.80 -5.30 -2.20))
    :f (fn [x y] (lerp 1.70 -0.33 (smooth (/ (- y -5.30) 3.10))))}])

(defn floor-at
  "Nearest walkable floor to feet height pz at local (x,y), or nil."
  [x y pz]
  (let [cands (for [r regions :when ((:b r) x y)]
                [((:f r) x y) r])]
    (when (seq cands)
      (first (apply min-key #(Math/abs (- (double (first %)) pz)) cands)))))

(def static-obstacles
  ;; [x0 x1 y0 y1 z0 z1] local AABBs
  [[-1.97 -0.55 -8.50 -7.80 1.45 2.70] ; aft bench, port half
   [0.55 1.97 -8.50 -7.80 1.45 2.70]    ; aft bench, starboard half
   ;; centre gap between the halves is the boarding walk-through from the
   ;; swim platform / transom steps into the cockpit
   [-1.97 -1.50 -7.85 -6.25 1.45 2.70]  ; port bench
   [1.50 1.97 -7.85 -6.25 1.45 2.70]    ; stbd bench
   [-0.68 0.68 -6.82 -5.88 1.50 2.45]   ; cockpit table (moved fwd to clear the boarding walk-through)
   [1.03 2.17 -6.10 -4.50 1.45 2.55]    ; wet bar
   [-0.80 1.60 -3.02 -2.24 1.50 2.55]   ; helm console
   [-0.26 0.48 -3.92 -3.20 1.50 2.75]   ; helm seat P
   [0.60 1.26 -3.92 -3.20 1.50 2.75]    ; helm seat S
   ;; interior
   [-1.62 -0.70 -2.30 0.30 -0.42 0.80]  ; saloon sofa
   [-0.50 0.70 -1.90 0.00 -0.42 0.66]   ; saloon table
   [-1.66 -0.60 1.30 3.02 -0.42 0.55]   ; galley
   [-1.72 -0.55 1.20 1.32 -0.42 1.60]   ; galley bulkhead left
   [0.55 1.72 1.20 1.32 -0.42 1.60]     ; galley bulkhead right
   [-1.72 -0.55 3.02 3.14 -0.42 1.60]   ; master bulkhead left
   [0.55 1.72 3.02 3.14 -0.42 1.60]     ; master bulkhead right
   [0.50 0.64 1.32 1.70 -0.42 1.60]     ; head wall aft
   [0.50 0.64 2.50 3.06 -0.42 1.60]     ; head wall fwd
   [-1.60 -0.95 5.05 6.02 -0.42 1.55]   ; wardrobe
   [-1.02 1.02 3.58 5.92 -0.42 0.72]    ; master bed
   [-1.30 -0.95 3.72 4.12 -0.42 0.50]   ; nightstand L
   [0.95 1.30 3.72 4.12 -0.42 0.50]     ; nightstand R
   [0.62 1.48 1.42 2.28 -0.42 0.60]     ; head vanity
   [1.02 1.46 2.28 2.82 -0.42 0.50]     ; toilet
   [0.62 0.78 2.42 3.02 -0.42 1.50]])   ; shower screen

(defn blocked? [x y pz]
  (let [body0 (+ pz 0.06) body1 (+ pz 1.72)]
    (or (some (fn [[x0 x1 y0 y1 z0 z1]]
                (and (> body1 z0) (< body0 z1)
                     (> (+ x 0.26) x0) (< (- x 0.26) x1)
                     (> (+ y 0.26) y0) (< (- y 0.26) y1)))
              static-obstacles)
        (let [{:keys [doors]} @S]
          (or (and (not (get-in doors [:master :open]))
                   (> (+ x 0.26) -0.60) (< (- x 0.26) 0.60)
                   (> (+ y 0.26) 3.00) (< (- y 0.26) 3.16))
              (and (not (get-in doors [:head :open]))
                   (> (+ x 0.26) 0.46) (< (- x 0.26) 0.66)
                   (> (+ y 0.26) 1.62) (< (- y 0.26) 2.58)))))))

(defn try-step
  "Returns [x y floor] when the move is legal."
  [x y pz]
  (when-let [f (floor-at x y pz)]
    (when (and (<= (Math/abs (- (double f) pz)) 0.38)
               (not (blocked? x y (double f))))
      [x y (double f)])))

;; ---------------------------------------------------------------------------
;; matrices
;; ---------------------------------------------------------------------------

(defn mv [m [x y z]]
  [(+ (* (nth m 0) x) (* (nth m 1) y) (* (nth m 2) z))
   (+ (* (nth m 3) x) (* (nth m 4) y) (* (nth m 5) z))
   (+ (* (nth m 6) x) (* (nth m 7) y) (* (nth m 8) z))])

(defn rot-z [a [x y]]
  [(- (* x (Math/cos a)) (* y (Math/sin a)))
   (+ (* x (Math/sin a)) (* y (Math/cos a)))])

(defn look-orient [f up]
  (let [f (vnorm f) up (vnorm up)
        r (vnorm (vcross f up))
        u (vcross r f)]
    [(nth r 0) (nth u 0) (- (nth f 0))
     (nth r 1) (nth u 1) (- (nth f 1))
     (nth r 2) (nth u 2) (- (nth f 2))]))

(defn set-cam! [cam pos f up]
  (bge/move! cam pos)
  (bge/orient! cam (look-orient f up))
  (bge/camera! cam)
  cam)

;; ---------------------------------------------------------------------------
;; init
;; ---------------------------------------------------------------------------

(defn cache-ocean!
  "The ocean shader reads wave time from the Ocean object's color.r and the
  hull mask from an empty named Y-WaveParams: its transform is the yacht's
  transform, so the shader's Object texture coordinates are the hull-local
  coordinates the mask needs. Both are cheap per-frame updates; nothing
  invalidates the compiled ocean material."
  []
  (try
    (swap! R assoc
           :ocean (bge/blender-object (bge/object "Ocean"))
           :wave-params (bge/object "Y-WaveParams"))
    (catch Exception e nil)))

(defn init! []
  (let [oc #(bge/object %)]
    (reset! R
            {:root (oc "Y-Yacht")
             :cam-walk (oc "Y-WalkCam")
             :cam-helm (oc "Y-HelmCam")
             :cam-chase (oc "Y-ChaseCam")
             :cam-orbit (oc "Y-OrbitCam")
             :cam-intro (oc "Y-IntroCam")
             :hud (oc "Y-HUD") :help (oc "Y-Help") :center (oc "Y-Center")
             :mfd1 (oc "Y-MFDText1") :mfd2 (oc "Y-MFDText2")
             :door-master (oc "Y-DoorMaster")
             :door-head (oc "Y-DoorHead")
             :sun (oc "Sun")}))
  (cache-ocean!)
  (let [root (:root @R)]
    (when root
      (try (bge/suspend-dynamics! root) (catch Exception _ nil))
      (let [p (bge/position root)]
        (swap! S assoc-in [:yacht :pos] p))))
  (bge/show-mouse! false)
  (reset! once-init true)
  (swap! S assoc :mode :intro :intro-t 0.0 :t 0.0 :driving false)
  (msg! "Golden hour · any key to skip"))

(defn start-audio! []
  (when-not (:audio-started @S)
    (swap! S assoc :audio-started true)
    (try
      (let [eng (audio/lowpass (audio/tone :sawtooth 55.0) 320.0 1.0)
            wat (audio/lowpass (audio/noise 2.0) 1200.0 0.8)
            win (audio/highpass (audio/noise 2.0) 600.0 0.7)
            he (audio/play eng {:loop? true :volume 0.0})
            hw (audio/play wat {:loop? true :volume 0.0})
            hwn (audio/play win {:loop? true :volume 0.0})]
        (swap! S assoc :audio {:engine he :water hw :wind hwn}))
      (catch Exception e (swap! S assoc :audio nil)))))

(defn stop-audio! []
  (when-let [a (:audio @S)]
    (doseq [h (vals a)] (try (audio/stop! h) (catch Exception _ nil)))
    (swap! S assoc :audio nil :audio-started false)))

;; ---------------------------------------------------------------------------
;; yacht motion
;; ---------------------------------------------------------------------------

(defn update-yacht! [dt]
  (let [{:keys [yacht]} @S
        {:keys [pos yaw speed throttle]} yacht
        [px py _] pos
        ;; 12 buoyancy samples around the hull footprint (local x,y)
        samples [[-2.2 -9.4] [0.0 -10.2] [2.2 -9.4]
                 [-2.45 -4.5] [2.45 -4.5]
                 [-2.45 0.8] [2.45 0.8]
                 [-2.0 5.0] [2.0 5.0] [0.0 7.4]
                 [-1.1 0.0] [1.1 0.0]]
        h (fn [[lx ly]]
            (let [[wx wy] (rot-z yaw [lx ly])]
              (wave-h (+ px wx) (+ py wy) (:t @S))))
        hs (map h samples)
        avg (/ (reduce + hs) (count hs))
        mean-of (fn [pred] (let [vs (keep (fn [[s v]] (when (pred s) v)) (map vector samples hs))]
                             (if (seq vs) (/ (reduce + vs) (count vs)) avg)))
        fore (mean-of (fn [[_ ly]] (> ly 4.0)))
        aft (mean-of (fn [[_ ly]] (< ly -4.0)))
        stbd (mean-of (fn [[lx _]] (> lx 1.0)))
        port (mean-of (fn [[lx _]] (< lx -1.0)))
        sN (clamp (/ speed 18.0) 0.0 1.0)
        plan (* 0.17 sN (- 1.25 (* 0.35 sN)))
        water-pitch (Math/atan2 (- fore aft) 12.0)
        water-roll (- (Math/atan2 (- stbd port) 5.2))
        steer (:steer @S)
        bank (- (* steer sN 0.12))
        tpitch (+ water-pitch plan)
        troll (+ water-roll bank)
        k (clamp (* dt 4.5) 0.0 1.0)
        pitch (lerp (:pitch yacht) tpitch k)
        roll (lerp (:roll yacht) troll k)
        heave (lerp (nth pos 2) (+ avg (* 0.05 sN)) k)
        ;; propulsion / steering
        speed' (let [target (* throttle 18.0)
                     aim (if (:driving @S) target 0.0)
                     accel (if (> aim speed) 1.3 0.75)]
                 (if (> aim speed) (min aim (+ speed (* accel dt)))
                     (max aim (- speed (* accel dt)))))
        sN2 (clamp (/ speed' 18.0) 0.0 1.0)
        yaw' (+ yaw (* steer (clamp (/ speed' 6.0) 0.0 1.0) 0.38 dt))
        [fx fy] (rot-z yaw' [0.0 1.0])
        pos' [(+ px (* fx speed' dt)) (+ py (* fy speed' dt)) heave]]
    (swap! S update :yacht assoc
           :pos pos' :yaw yaw' :pitch pitch :roll roll :speed speed')
    (when-let [root (:root @R)]
      (bge/move! root pos')
      (bge/orient! root (bge/euler->matrix [pitch roll yaw'])))
    ;; Cheap per-frame uniforms: wave time via the Ocean object's color,
    ;; hull mask via the Y-WaveParams empty's transform. Neither regenerates
    ;; the ocean material.
    (when-let [o (:ocean (deref R))]
      (try (b/set! o "color" [(double (:t @S)) 0.0 0.0 1.0])
           (catch Exception _ nil)))
    (when-let [wp (:wave-params (deref R))]
      (try
        (bge/move! wp pos')
        (bge/orient! wp (bge/euler->matrix [0.0 0.0 (- (double yaw'))]))
        (catch Exception _ nil)))))

;; ---------------------------------------------------------------------------
;; player
;; ---------------------------------------------------------------------------

(defn wrap-pi [a] (- (mod (+ (double a) PI) (* 2.0 PI)) PI))

(def ^:private mouse-deadzone 0.0025)
(def ^:private mouse-state (atom {:last nil}))

(defn mouse-look-delta
  "Normalised mouse movement since the last frame, with a small deadzone.

  Tries to recentre the cursor after reading. If the host ignores the warp
  (an embedded window that does not hold the cursor), the reads stay at a
  fixed off-centre position; using raw position deltas then makes the view
  hold still instead of spinning forever. When the warp does work, the
  recentred position is the next frame's baseline."
  []
  (let [m (bge/mouse)
        mx (double (nth m 0)) my (double (nth m 1))
        inside (and (>= mx 0.02) (<= mx 0.98) (>= my 0.02) (<= my 0.98))]
    (if-not inside
      (do (swap! mouse-state assoc :last nil) [0.0 0.0])
      (let [last (:last @mouse-state)
            dx0 (- mx (if last (double (nth last 0)) mx))
            dy0 (- my (if last (double (nth last 1)) my))
            dx (if (< (Math/abs dx0) mouse-deadzone) 0.0 dx0)
            dy (if (< (Math/abs dy0) mouse-deadzone) 0.0 dy0)
            _ (bge/mouse! 0.5 0.5)
            am (bge/mouse)
            ax (double (nth am 0)) ay (double (nth am 1))
            warped (and (< (Math/abs (- ax 0.5)) 0.08)
                        (< (Math/abs (- ay 0.5)) 0.08))]
        ;; Offset the recentre bias so a still mouse reads as zero movement.
        (swap! mouse-state assoc :last (if warped [ax ay] [mx my]))
        [(clamp dx -0.10 0.10) (clamp dy -0.10 0.10)]))))

(defn update-look! [dt]
  (let [{:keys [mode look]} @S]
    (when (and look (not= mode :intro) (not= mode :orbit))
      (let [[dx dy] (mouse-look-delta)]
        (when (or (not (zero? dx)) (not (zero? dy)))
          (swap! S update :player
                 (fn [p] (-> p
                             (update :yaw (fn [y] (wrap-pi (- y (* dx 2.4)))))
                             (update :pitch (fn [ph] (clamp (- ph (* dy 1.8)) -1.30 1.30)))))))))))

(defn player-world []
  (let [{:keys [player]} @S
        root (:root @R)
        m (bge/orientation root)
        p (bge/position root)
        [lx ly lz] (:pos player)]
    (vadd p (mv m [lx ly (+ lz 1.65)]))))

(defn player-forward []
  (let [{:keys [player]} @S
        {:keys [yaw pitch]} player
        f [(- (* (Math/sin yaw) (Math/cos pitch)))
           (* (Math/cos yaw) (Math/cos pitch))
           (Math/sin pitch)]]
    (mv (bge/orientation (:root @R)) f)))

(defn player-up []
  (mv (bge/orientation (:root @R)) [0.0 0.0 1.0]))

(defn update-player! [dt]
  (let [{:keys [player driving mode]} @S
        {:keys [pos yaw]} player
        [px py pz] pos
        fw (bge/key-down? "WKEY") bk (bge/key-down? "SKEY")
        lf (bge/key-down? "AKEY") rt (bge/key-down? "DKEY")
        shift (bge/key-down? "LEFTSHIFTKEY")
        f [(- (Math/sin yaw)) (Math/cos yaw)]
        r [(Math/cos yaw) (Math/sin yaw)]
        mx (+ (* (nth f 0) (if fw 1 0)) (* (nth f 0) (if bk -1 0))
              (* (nth r 0) (if rt 1 0)) (* (nth r 0) (if lf -1 0)))
        my (+ (* (nth f 1) (if fw 1 0)) (* (nth f 1) (if bk -1 0))
              (* (nth r 1) (if rt 1 0)) (* (nth r 1) (if lf -1 0)))
        ml (Math/sqrt (+ (* mx mx) (* my my)))
        step (* (if shift 4.6 2.35) dt)
        [mx my] (if (> ml 1e-6) [(/ (* mx step) ml) (/ (* my step) ml)] [0.0 0.0])
        pz (if driving (+ (deck-z py) 0.02) pz)
        try-one (fn [nx ny] (try-step nx ny pz))
        res (or (try-one (+ px mx) (+ py my))
                (try-one (+ px mx) py)
                (try-one px (+ py my)))]
    (if (and res (not driving))
      (swap! S assoc-in [:player :pos] [(nth res 0) (nth res 1) (nth res 2)])
      (swap! S update :player assoc :pos [px py pz]))))

;; ---------------------------------------------------------------------------
;; helm / doors / time
;; ---------------------------------------------------------------------------

(defn near-helm? []
  (let [{:keys [player]} @S
        [x y _] (:pos player)]
    (and (< (Math/abs (- x 0.52)) 1.15)
         (< (Math/abs (- y -3.55)) 1.15))))

(defn door-data []
  {:master {:hinge [-0.52 3.09 -0.36] :size [1.04 0.05] :axis :x :center [0.0 3.09 -0.0]}
   :head {:hinge [0.55 1.70 -0.36] :size [0.05 0.80] :axis :y :center [0.55 2.10 0.0]}})

(defn near-door []
  (let [{:keys [player]} @S
        [x y _] (:pos player)]
    (some (fn [[k d]]
            (let [[cx cy _] (:center d)]
              (when (and (< (Math/abs (- x cx)) 1.20)
                         (< (Math/abs (- y cy)) 1.20))
                k)))
          (door-data))))

(defn toggle-door! [k]
  (swap! S update-in [:doors k :open] not)
  (msg! (if (get-in @S [:doors k :open]) "Door open" "Door closed")))

(defn update-doors! [dt]
  (let [{:keys [doors]} @S
        dd (door-data)
        root (:root @R)]
    (doseq [[k d] dd]
      (let [target (if (get-in doors [k :open]) 1.45 0.0)
            cur (get-in doors [k :angle])
            ang (lerp cur target (clamp (* dt 7.0) 0.0 1.0))
            _ (swap! S assoc-in [:doors k :angle] ang)
            [hx hy hz] (:hinge d)
            ca (Math/cos ang) sa (Math/sin ang)
            [ox oy] (let [rx (- (* hx ca) (* hy sa))
                          ry (+ (* hx sa) (* hy ca))]
                      [(- hx rx) (- hy ry)])
            m (bge/orientation root)
            p (bge/position root)
            wp (vadd p (mv m [ox oy 0.0]))
            om (let [mz [ca sa 0.0 (- sa) ca 0.0 0.0 0.0 1.0]
                     rm (bge/orientation root)]
                 ;; root * Rz(angle)
                 [(+ (* (nth rm 0) ca) (* (nth rm 1) (- sa)))
                  (+ (* (nth rm 0) sa) (* (nth rm 1) ca))
                  (nth rm 2)
                  (+ (* (nth rm 3) ca) (* (nth rm 4) (- sa)))
                  (+ (* (nth rm 3) sa) (* (nth rm 4) ca))
                  (nth rm 5)
                  (+ (* (nth rm 6) ca) (* (nth rm 7) (- sa)))
                  (+ (* (nth rm 6) sa) (* (nth rm 7) ca))
                  (nth rm 8)])]
        (when-let [obj (get @R (case k :master :door-master :head :door-head))]
          (try (bge/move! obj wp) (bge/orient! obj om) (catch Exception _ nil)))))))

(def times [:golden :day :sunset :night])
(defn cycle-time! []
  (let [cur (:time @S)
        idx (first (keep-indexed (fn [i v] (when (= v cur) i)) times))
        idx (mod (inc (or idx 0)) (count times))
        nxt (nth times idx)]
    (swap! S assoc :time nxt)
    (msg! (str/capitalize (name nxt)))))

(defn set-time! [k]
  (let [sun-obj (bge/blender-object (:sun @R))
        light (try (b/get sun-obj "data") (catch Exception _ nil))
        rot (fn [elev az]
              (let [e (* (/ elev 180.0) PI)
                    a (* (/ az 180.0) PI)
                    d [( * (Math/cos e) (Math/sin a)) (* (Math/cos e) (Math/cos a)) (Math/sin e)]
                    theta (Math/acos (nth d 2))
                    phi (Math/atan2 (nth d 0) (- (nth d 1)))]
                [theta 0.0 phi]))
        worlds {"SeaWorld" "SeaWorld"
                :day "Y-WorldDay" :sunset "Y-WorldSunset" :night "Y-WorldNight"
                :golden "SeaWorld"}
        wn (get worlds (if (keyword? k) k (keyword k)) (get worlds k))]
    (try
      (when-let [w (b/lookup (bpy/data) "worlds" (or wn "SeaWorld"))]
        (b/set! (bpy/scene) "world" w))
      (catch Exception _ nil))
    (when light
      (try
        (case k
          :golden (do (b/set! light "color" [1.0 0.56 0.27]) (b/set! light "energy" 20.0)
                      (b/set! sun-obj "rotation_euler" (rot 4.0 60.0)))
          :day (do (b/set! light "color" [1.0 0.96 0.90]) (b/set! light "energy" 11.0)
                   (b/set! sun-obj "rotation_euler" (rot 55.0 60.0)))
          :sunset (do (b/set! light "color" [1.0 0.42 0.16]) (b/set! light "energy" 9.0)
                      (b/set! sun-obj "rotation_euler" (rot 2.0 250.0)))
          :night (do (b/set! light "color" [0.55 0.68 1.0]) (b/set! light "energy" 0.6)
                     (b/set! sun-obj "rotation_euler" (rot 38.0 200.0))))
        (catch Exception _ nil)))
    (try
      (let [vs (b/get (bpy/scene) "view_settings")]
        (b/set! vs "exposure" (case k :golden 0.15 :day 0.0 :sunset 0.1 :night 1.2)))
      (catch Exception _ nil))))

;; ---------------------------------------------------------------------------
;; cameras
;; ---------------------------------------------------------------------------

(defn intro-pose [t]
  ;; local camera offset + target (the boat centre)
  (let [u (clamp (/ t 18.0) 0.0 1.0)
        a (lerp -140.0 150.0 (smooth (clamp (/ u 0.72) 0.0 1.0)))
        rad (lerp 24.0 15.0 (Math/sin (* PI u)))
        hgt (lerp 6.5 2.0 u)
        end-loc [0.0 -14.5 2.2]
        loc (if (> u 0.78)
              (let [k (smooth (/ (- u 0.78) 0.22))]
                (let [[x y] (rot-z (* (/ a 180.0) PI) [rad 0.0])]
                  [(lerp x (nth end-loc 0) k)
                   (lerp y (nth end-loc 1) k)
                   (lerp hgt (nth end-loc 2) k)]))
              (let [[x y] (rot-z (* (/ a 180.0) PI) [0.0 rad])]
                ;; start near the aft port quarter where the sun sits behind the boat
                [x (+ y -4.0) hgt]))]
    loc))

(defn update-camera! [dt]
  (let [{:keys [mode player chase driving orbit]} @S
        root (:root @R)
        rm (bge/orientation root)
        rp (bge/position root)
        up (mv rm [0.0 0.0 1.0])]
    (case mode
      :intro
      (let [lt (intro-pose (:intro-t @S))
            wp (vadd rp (mv rm lt))]
        (set-cam! (:cam-intro @R) wp (vsub (vadd rp (mv rm [0.0 -1.5 1.8])) wp) up))
      :orbit
      (let [{:keys [pos yaw pitch]} orbit
            f [(- (* (Math/sin yaw) (Math/cos pitch)))
               (* (Math/cos yaw) (Math/cos pitch))
               (Math/sin pitch)]]
        (set-cam! (:cam-orbit @R) pos f [0.0 0.0 1.0]))
      :chase
      (let [desired (vadd rp (mv rm [0.0 -26.0 9.0]))
            cur (or (:chase-pos @S) desired)
            k (clamp (* dt 3.0) 0.0 1.0)
            npos (mapv #(lerp (nth cur %) (nth desired %) k) (range 3))]
        (swap! S assoc :chase-pos npos)
        (set-cam! (:cam-chase @R) npos
                  (vadd rp (mv rm [0.0 -2.0 2.6])) up))
      :helm
      (let [[lx ly lz] [0.45 -3.35 1.73]
            eye (vadd rp (mv rm [lx ly (+ lz 1.30)]))
            {:keys [yaw pitch]} player
            f (mv rm [(- (* (Math/sin yaw) (Math/cos pitch)))
                      (* (Math/cos yaw) (Math/cos pitch))
                      (Math/sin pitch)])]
        (set-cam! (:cam-helm @R) eye f up))
      ;; walk
      (set-cam! (:cam-walk @R) (player-world) (player-forward) (player-up)))))

;; ---------------------------------------------------------------------------
;; HUD
;; ---------------------------------------------------------------------------

(defn place-ui!
  "Put a text object in front of `cam`: fwd-dist, up offset, right offset."
  [obj cam fwd-dist up-off right-off]
  (when obj
    (let [m (bge/orientation cam)
          p (bge/position cam)
          fwd [(- (nth m 2)) (- (nth m 5)) (- (nth m 8))]
          up [(nth m 1) (nth m 4) (nth m 7)]
          right [(nth m 0) (nth m 3) (nth m 6)]
          wp (vadd p (vadd (vmul fwd (double fwd-dist))
                           (vadd (vmul up (double up-off))
                                 (vmul right (double right-off)))))]
      (bge/move! obj wp)
      (bge/orient! obj m))))

(defn place-text! [obj cam text up-off]
  (when obj
    (bge/text! obj text)
    (place-ui! obj cam 1.60 up-off 0.0)))

(defn update-hud! [dt]
  (let [{:keys [mode driving help ui time msg msg-t player]} @S
        active-cam (case mode
                     :intro (:cam-intro @R) :orbit (:cam-orbit @R)
                     :chase (:cam-chase @R) :helm (:cam-helm @R)
                     (:cam-walk @R))
        kn (* (:speed (:yacht @S)) 1.94384)
        hdg (mod (* 360.0 (+ 0.0 (/ (:yaw (:yacht @S)) (* 2 PI)))) 360.0)
        rpm (+ 700.0 (* 2100.0 (Math/abs (double (:throttle (:yacht @S))))))
        depth (+ 12.0 (* 2.5 (Math/sin (* 0.05 (:t @S)))))
        tt (name time)]
    (place-text! (:hud @R) active-cam
                 (str (format "%.1f kn" kn) "   HDG " (format "%03d" (int hdg)) "°   "
                      (format "%.1f m" depth) "   " (str/upper-case tt)
                      (if driving "\n[W/S] throttle  [A/D] steer  [C] chase  [E] release"
                          "\n[E] helm   [O] photo   [T] time   [H] UI   [F1] help"))
                 0.52)
    (when (:mfd1 @R) (bge/text! (:mfd1 @R) (str (format "%.1f kn\n" kn) (format "%d RPM" (int rpm)))))
    (when (:mfd2 @R) (bge/text! (:mfd2 @R) (str "HDG " (format "%03d" (int hdg)) "\nDEPTH " (format "%.1f m" depth))))
    (when (:center @R)
      (bge/text! (:center @R) (if (and (> msg-t 0.0) (seq msg)) msg ""))
      (place-ui! (:center @R) active-cam 1.60 -0.16 0.0))
    (when (:help @R)
      (bge/text! (:help @R)
                 (if help
                   (str "MOTORYACHT — CONTROLS\n\n"
                        "W A S D   walk / drive\n"
                        "Shift     run\n"
                        "Mouse     look\n"
                        "E         take / release helm, open door\n"
                        "C         helm / chase view\n"
                        "O         photo (free fly)\n"
                        "T         time of day\n"
                        "H         hide UI\n"
                        "P         screenshot\n"
                        "F1        this panel\n"
                        "Esc       release mouse\n\n"
                        "Throttle W/S · steer A/D")
                   ""))
      (place-ui! (:help @R) active-cam 1.60 -0.05 -0.62))
    (swap! S assoc :msg-t (max 0.0 (- msg-t dt)))))

;; ---------------------------------------------------------------------------
;; doors / input actions
;; ---------------------------------------------------------------------------

(defn handle-actions! []
  (when (bge/key-pressed? "LEFTMOUSE") (start-audio!))
  (when (some bge/key-pressed? ["WKEY" "AKEY" "SKEY" "DKEY" "SPACEKEY" "EKEY" "CKEY" "OKEY" "TKEY" "F1KEY"])
    (start-audio!))
  (when (and (= (:mode @S) :intro)
             (some bge/key-pressed? ["WKEY" "AKEY" "SKEY" "DKEY" "EKEY" "CKEY" "OKEY" "TKEY" "F1KEY" "HKEY" "PKEY" "SPACEKEY" "LEFTMOUSE" "ESCKEY"]))
    (swap! S assoc :mode :walk :player {:pos start-pos :yaw start-yaw :pitch 0.0} :look true)
    (bge/show-mouse! false)
    (bge/mouse! 0.5 0.5))
  (when (and (not= (:mode @S) :intro) (bge/key-pressed? "EKEY") (:driving @S))
    (swap! S assoc :driving false :throttle 0.0 :mode :walk)
    (msg! "Wheel released"))
  (when (and (not= (:mode @S) :intro) (bge/key-pressed? "EKEY") (not (:driving @S)))
    (if-let [dk (near-door)]
      (toggle-door! dk)
      (when (and (near-helm?) (= (:mode @S) :walk))
        (swap! S assoc :driving true :mode (if (:chase @S) :chase :helm)
               :player {:pos [0.45 -3.55 (deck-z -3.55)] :yaw 0.0 :pitch 0.0})
        (msg! "At the helm · W/S throttle · A/D steer"))))
  (when (and (:driving @S) (bge/key-pressed? "CKEY"))
    (swap! S update :chase not)
    (swap! S assoc :mode (if (:chase @S) :chase :helm)))
  (when (and (not (:driving @S)) (not= (:mode @S) :intro) (bge/key-pressed? "OKEY"))
    (if (= (:mode @S) :orbit)
      (swap! S assoc :mode :walk)
      (do (swap! S assoc :mode :orbit)
          (swap! S assoc-in [:orbit :pos]
                 (vadd (bge/position (:root @R)) [10.0 -22.0 10.0]))
          (swap! S assoc-in [:orbit :yaw] -0.6)
          (swap! S assoc-in [:orbit :pitch] -0.15)
          (msg! "Photo mode · WASD fly · O back"))))
  (when (bge/key-pressed? "TKEY")
    (cycle-time!)
    (set-time! (:time @S)))
  (when (bge/key-pressed? "HKEY") (swap! S update :ui not))
  (when (bge/key-pressed? "F1KEY") (swap! S update :help not))
  (when (bge/key-pressed? "PKEY")
    (try (bge/screenshot! "/Users/yogthos/src/jlt-commons/upbge-jolt/example/yacht/shot-user.png")
         (msg! "Screenshot saved") (catch Exception _ nil)))
  (when (bge/key-pressed? "LEFTMOUSE")
    (swap! S assoc :look true)
    (bge/show-mouse! false)
    (bge/mouse! 0.5 0.5))
  (when (bge/key-pressed? "ESCKEY")
    (swap! S assoc :look false)
    (bge/show-mouse! true))
  ;; driving throttle/steer
  (when (:driving @S)
    (let [thr (:throttle (:yacht @S))
          thr' (cond
                 (bge/key-pressed? "WKEY") (min 1.0 (+ thr 0.22))
                 (bge/key-pressed? "SKEY") (max -0.35 (- thr 0.22))
                 :else thr)
          st (cond (bge/key-down? "AKEY") 1.0 (bge/key-down? "DKEY") -1.0 :else 0.0)]
      (swap! S assoc-in [:yacht :throttle] thr')
      (swap! S assoc :steer st)))
  (when (not (:driving @S)) (swap! S assoc :steer 0.0)))

;; ---------------------------------------------------------------------------
;; orbit free fly
;; ---------------------------------------------------------------------------

(defn update-orbit! [dt]
  (when (= (:mode @S) :orbit)
    (let [{:keys [pos yaw pitch]} (:orbit @S)
          [dx dy] (mouse-look-delta)
          yaw' (- yaw (* dx 2.2))
          pitch' (clamp (- pitch (* dy 1.8)) -1.4 1.4)
          sp (* (if (bge/key-down? "LEFTSHIFTKEY") 18.0 7.0) dt)
          f [( * (Math/sin yaw') (Math/cos pitch')) (* (Math/cos yaw') (Math/cos pitch')) (Math/sin pitch')]
          r (vnorm (vcross f [0.0 0.0 1.0]))
          mv (vadd (vmul f (cond (bge/key-down? "WKEY") sp (bge/key-down? "SKEY") (- sp) :else 0.0))
                   (vmul r (cond (bge/key-down? "DKEY") sp (bge/key-down? "AKEY") (- sp) :else 0.0)))
          up (cond (bge/key-down? "EKEY") sp (bge/key-down? "QKEY") (- sp) :else 0.0)
          npos (vadd (vadd pos mv) [0.0 0.0 up])]
      (swap! S assoc :orbit {:pos npos :yaw yaw' :pitch pitch'}))))

;; ---------------------------------------------------------------------------
;; audio
;; ---------------------------------------------------------------------------

(defn update-audio! [dt]
  (when-let [a (:audio @S)]
    (let [{:keys [speed throttle]} (:yacht @S)
          sN (clamp (/ (Math/abs (double speed)) 18.0) 0.0 1.0)
          thr (Math/abs (double throttle))]
      (try
        (audio/pitch! (:engine a) (+ 0.65 (* thr 0.55) (* sN 0.55)))
        (audio/volume! (:engine a) (if (:driving @S) (+ 0.18 (* thr 0.42)) 0.10))
        (audio/volume! (:water a) (* 0.30 sN))
        (audio/volume! (:wind a) (+ 0.04 (* 0.20 sN)))
        (catch Exception _ nil)))))

;; ---------------------------------------------------------------------------
;; frame / start hooks
;; ---------------------------------------------------------------------------

(defn frame! [dt]
  (when (bge/running?)
    (when-not @once-init (init!))
    (let [dt (clamp dt 0.001 0.10)]
      (swap! S update :t + dt)
      (handle-actions!)
      (update-look! dt)
      (update-orbit! dt)
      (update-yacht! dt)
      (when (= (:mode @S) :intro)
        (swap! S update :intro-t + dt)
        (when (> (:intro-t @S) 18.0)
          (swap! S assoc :mode :walk :player {:pos start-pos :yaw start-yaw :pitch 0.0})
          (msg! "Walk mode · W A S D to move · E at the helm")))
      (when (contains? #{:walk :helm :chase} (:mode @S))
        (update-player! dt))
      (update-doors! dt)
      (update-camera! dt)
      (when (:ui @S) (update-hud! dt))
      (when-not (:ui @S)
        (doseq [o [(:hud @R) (:center @R) (:help @R)]]
          (when o (bge/text! o ""))))
      (update-audio! dt))))

(game/on-start! ::init init!)
(game/on-frame! ::main frame!)
(game/on-stop! ::stop (fn [] (stop-audio!)))

;; convenience for the REPL while a game runs
(comment
  (init!)
  @S
  (set-time! :night))
