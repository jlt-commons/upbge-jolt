(ns yacht
  "Builds the motor yacht itself: a lofted deep-V hull, teak decks, a white
  coachroof with wraparound windscreen, hardtop + radar arch, rails, furniture,
  the helm, and a walkable interior. Everything is parented to the hull object
  Y-Yacht, which is also the floating rigid body driven by main.clj.

  Sea/sky live in build.clj; this namespace only needs them to exist."
  (:require [upbge-jolt.bpy :as bpy]
            [upbge-jolt.bridge :as b]
            [upbge-jolt.nodes :as nodes]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; small math helpers
;; ---------------------------------------------------------------------------

(def PI Math/PI)
(defn clamp [x lo hi] (min hi (max lo x)))
(defn smooth [t] (let [t (clamp t 0.0 1.0)] (* t t (- 3.0 (* 2.0 t)))))
(defn lerp [a b t] (+ a (* (- b a) (double t))))
(defn vsub [[ax ay az] [bx by bz]] [(- ax bx) (- ay by) (- az bz)])
(defn vadd [[ax ay az] [bx by bz]] [(+ ax bx) (+ ay by) (+ az bz)])
(defn vmul [[x y z] s] [(* x s) (* y s) (* z s)])
(defn vlen [[x y z]] (Math/sqrt (+ (* x x) (* y y) (* z z))))
(defn vnorm [v] (let [l (vlen v)] (if (< l 1e-9) [0.0 0.0 1.0] (vmul v (/ 1.0 l)))))
(defn vcross [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by)) (- (* az bx) (* ax bz)) (- (* ax by) (* ay bx))])

(def third #(nth % 2))

(defn profile
  "Piecewise smoothstep interpolation over control points [[x y] ...]."
  [pts x]
  (loop [i 0]
    (if (>= i (dec (count pts)))
      (double (second (peek pts)))
      (let [[x0 y0] (nth pts i)
            [x1 y1] (nth pts (inc i))]
        (if (<= x x1)
          (lerp y0 y1 (smooth (/ (- x x0) (- x1 x0))))
          (recur (inc i)))))))

;; ---------------------------------------------------------------------------
;; node helpers (same shape as build.clj)
;; ---------------------------------------------------------------------------

(defn set-in [nt v node k]
  (let [s (nodes/socket node :in k)]
    (if (map? v)
      (b/call (b/get nt "links") "new" {:output v :input s})
      (b/set! s "default_value" v))))

(defn pnode
  "Principled material with sane defaults; updates in place when it exists."
  [name {:keys [base metallic roughness coat coat-rough transmission alpha ior
                emission es sheen sheen-rough coat-ior backface]}]
  (let [m (or (b/lookup (bpy/data) "materials" name) (nodes/material! name))
        nt (nodes/tree m)
        bsdf (nodes/find nt "Principled BSDF")
        si (fn [k v] (when (some? v) (b/set! (nodes/socket bsdf :in k) "default_value" v)))]
    (when (some? base) (si "Base Color" (if (= 3 (count base)) (conj (vec base) 1.0) base)))
    (si "Metallic" metallic)
    (si "Roughness" roughness)
    (si "Coat Weight" coat)
    (si "Coat Roughness" coat-rough)
    (si "Transmission Weight" transmission)
    (si "Alpha" alpha)
    (si "IOR" ior)
    (si "Emission Color" (when emission (if (= 3 (count emission)) (conj (vec emission) 1.0) emission)))
    (si "Emission Strength" es)
    (si "Sheen Weight" sheen)
    (si "Sheen Roughness" sheen-rough)
    (si "Coat IOR" coat-ior)
    (when (some? backface) (b/set! m "use_backface_culling" (boolean backface)))
    (when (or (and alpha (< alpha 1.0)) transmission)
      (b/set! m "surface_render_method" "DITHERED"))
    m))

(defn teak-material!
  "Draw the procedural teak planks: Object X drives bands (plank seams run
  along Y), a noise adds grain."
  []
  (let [m (nodes/material! "Y-Teak")
        nt (nodes/tree m)
        out (nodes/find nt "Material Output")
        bsdf (nodes/find nt "Principled BSDF")
        tc (nodes/node! nt "ShaderNodeTexCoord")
        mp (nodes/node! nt "ShaderNodeMapping")
        _ (set-in nt (nodes/socket tc :out "Object") mp "Vector")
        _ (set-in nt [1.0 1.0 1.0] mp "Scale")
        wave (nodes/node! nt "ShaderNodeTexWave"
                          {:props {"wave_type" "BANDS" "bands_direction" "X"
                                   "wave_profile" "SIN"}})
        _ (set-in nt (nodes/socket mp :out "Vector") wave "Vector")
        _ (set-in nt 6.5 wave "Scale")
        _ (set-in nt 1.5 wave "Distortion")
        _ (set-in nt 2.0 wave "Detail")
        _ (set-in nt 1.0 wave "Detail Scale")
        _ (set-in nt 0.5 wave "Detail Roughness")
        ramp (nodes/node! nt "ShaderNodeValToRGB")
        _ (set-in nt (nodes/socket wave :out "Fac") ramp "Fac")
        el (b/get ramp "color_ramp")
        els (b/get el "elements")
        e0 (b/lookup el "elements" 0)
        e1 (b/lookup el "elements" 1)]
    (b/set! el "interpolation" "EASE")
    (b/set! e0 "position" 0.012)
    (b/set! (b/get e0 "color") "default_value" [0.045 0.030 0.016 1.0])
    (b/set! e1 "position" 0.10)
    (b/set! (b/get e1 "color") "default_value" [0.56 0.36 0.17 1.0])
    ;; grain
    (let [noise (nodes/node! nt "ShaderNodeTexNoise")
          _ (set-in nt (nodes/socket tc :out "Object") noise "Vector")
          _ (set-in nt 55.0 noise "Scale")
          _ (set-in nt 3.0 noise "Detail")
          _ (set-in nt 0.6 noise "Roughness")
          mix (nodes/node! nt "ShaderNodeMixRGB")
          _ (set-in nt (b/get noise "Fac") mix "Fac")
          _ (set-in nt (nodes/socket ramp :out "Color") mix "Color1")
          _ (set-in nt [0.72 0.50 0.26 1.0] mix "Color2")
          _ (set-in nt (nodes/socket mix :out "Color") bsdf "Base Color")]
      (b/set! (nodes/socket bsdf :in "Roughness") "default_value" 0.62)
      (b/set! (nodes/socket bsdf :in "Metallic") "default_value" 0.0)
      (b/set! (nodes/socket bsdf :in "Coat Weight") "default_value" 0.25)
      (b/set! (nodes/socket bsdf :in "Coat Roughness") "default_value" 0.25))
    (set-in nt (nodes/socket bsdf :out 0) out "Surface")
    (b/set! (b/get nt "nodes") "active" bsdf)
    m))

;; ---------------------------------------------------------------------------
;; materials
;; ---------------------------------------------------------------------------

(defn materials! []
  (let [m {;; hull
           :navy   (pnode "Y-Navy" {:base [0.008 0.020 0.058] :metallic 0.0 :roughness 0.16
                                    :coat 1.0 :coat-rough 0.03 :coat-ior 1.6 :backface true})
           :red    (pnode "Y-Red" {:base [0.20 0.028 0.018] :roughness 0.55 :backface true})
           :boot   (pnode "Y-Boot" {:base [0.88 0.88 0.84] :roughness 0.30 :coat 0.4 :coat-rough 0.1 :backface true})
           :white  (pnode "Y-White" {:base [0.86 0.87 0.85] :roughness 0.24 :coat 0.55 :coat-rough 0.06})
           :steel  (pnode "Y-Steel" {:base [0.92 0.94 0.96] :metallic 1.0 :roughness 0.075})
           :brushed (pnode "Y-SteelBrushed" {:base [0.72 0.75 0.78] :metallic 1.0 :roughness 0.28})
           :dark   (pnode "Y-Anthracite" {:base [0.035 0.038 0.042] :roughness 0.42})
           :black  (pnode "Y-Black" {:base [0.012 0.012 0.012] :roughness 0.5})
           :glass  (pnode "Y-Glass" {:base [0.05 0.11 0.12] :roughness 0.03 :transmission 0.92
                                     :alpha 0.42 :ior 1.5 :coat 0.4 :coat-rough 0.03})
           :glassclear (pnode "Y-GlassClear" {:base [0.85 0.93 0.95] :roughness 0.02
                                              :transmission 0.95 :alpha 0.28 :ior 1.5})
           :leather (pnode "Y-Leather" {:base [0.66 0.55 0.40] :roughness 0.55 :sheen 0.25 :sheen-rough 0.4})
           :leatherd (pnode "Y-LeatherDark" {:base [0.20 0.14 0.09] :roughness 0.5 :sheen 0.2 :sheen-rough 0.5})
           :canvas (pnode "Y-Canvas" {:base [0.010 0.022 0.060] :roughness 0.92})
           :tan    (pnode "Y-Tan" {:base [0.74 0.60 0.42] :roughness 0.65 :sheen 0.2 :sheen-rough 0.6})
           :oak    (pnode "Y-Oak" {:base [0.62 0.47 0.30] :roughness 0.45 :coat 0.2 :coat-rough 0.2})
           :walnut (pnode "Y-Walnut" {:base [0.15 0.075 0.040] :roughness 0.4 :coat 0.25 :coat-rough 0.18})
           :stone  (pnode "Y-Stone" {:base [0.60 0.60 0.58] :roughness 0.22 :coat 0.3 :coat-rough 0.1})
           :linen  (pnode "Y-Linen" {:base [0.86 0.84 0.78] :roughness 0.8 :sheen 0.4 :sheen-rough 0.5})
           :tv     (pnode "Y-TV" {:base [0.004 0.006 0.008] :roughness 0.06 :coat 1.0 :coat-rough 0.02})
           :led    (pnode "Y-LED" {:base [1.0 0.82 0.55] :emission [1.0 0.78 0.42] :es 6.0 :roughness 0.4})
           :redl   (pnode "Y-NavRed" {:base [0.5 0.02 0.02] :emission [1.0 0.05 0.03] :es 24.0 :roughness 0.2})
           :greenl (pnode "Y-NavGreen" {:base [0.02 0.5 0.05] :emission [0.05 1.0 0.10] :es 24.0 :roughness 0.2})
           :whitel (pnode "Y-NavWhite" {:base [0.9 0.9 0.9] :emission [1.0 0.95 0.85] :es 30.0 :roughness 0.2})
           :radar  (pnode "Y-Radar" {:base [0.90 0.90 0.88] :roughness 0.35 :coat 0.4 :coat-rough 0.1})}
        teak (or (b/lookup (bpy/data) "materials" "Y-Teak") nil)]
     (assoc m :teak (if teak teak (teak-material!)))))

;; ---------------------------------------------------------------------------
;; mesh primitives
;; ---------------------------------------------------------------------------

(defn parent! [obj root]
  (when root (b/set! obj "parent" root))
  obj)

(defn box!
  ([name p0 p1] (box! name p0 p1 nil nil))
  ([name [x0 y0 z0] [x1 y1 z1] mat root]
   (let [v [[x0 y0 z0] [x1 y0 z0] [x1 y1 z0] [x0 y1 z0]
            [x0 y0 z1] [x1 y0 z1] [x1 y1 z1] [x0 y1 z1]]
         f [[0 3 2 1] [4 5 6 7] [0 1 5 4] [2 3 7 6] [3 0 4 7] [1 2 6 5]]
         o (bpy/add! name v f)]
     (when mat (nodes/assign! o mat))
     (parent! o root))))

(defn cylinder!
  "A capped cylinder from p0 to p1, radius r, n sides."
  [name p0 p1 r n mat root]
  (let [ax (vsub p1 p0)
        l (vlen ax)
        [ux uy uz] (vnorm ax)
        helper (if (< (Math/abs uz) 0.9) [0.0 0.0 1.0] [1.0 0.0 0.0])
        s (vnorm (vcross helper [ux uy uz]))
        u (vcross [ux uy uz] s)
        ring (fn [base]
               (for [i (range n)
                     :let [a (* 2.0 PI (/ (double i) n))
                           c (vmul s (* r (Math/cos a)))
                           d (vmul u (* r (Math/sin a)))]]
                 (vadd base (vadd c d))))
        vp (vec (ring p0))
        vq (vec (ring p1))
        verts (vec (concat vp vq))
        side (for [i (range n)
                   :let [j (mod (inc i) n)]]
               [i j (+ n j) (+ n i)])
        cap0 (vec (reverse (range n)))
        cap1 (vec (map #(+ n %) (range n)))]
    (let [o (bpy/add! name verts (concat (vec side) [cap0 cap1]))]
      (when mat (nodes/assign! o mat))
      (parent! o root))))

(defn torus!
  "Torus around `axis` (a unit vector) at center, major R, minor r."
  [name center axis R r nu nv mat root]
  (let [[ux uy uz] (vnorm axis)
        helper (if (< (Math/abs uz) 0.9) [0.0 0.0 1.0] [1.0 0.0 0.0])
        s (vnorm (vcross helper [ux uy uz]))
        u (vcross [ux uy uz] s)
        ring-center (fn [i]
                      (let [a (* 2.0 PI (/ (double i) nu))
                            d (vadd (vmul s (* R (Math/cos a)))
                                    (vmul u (* R (Math/sin a))))]
                        (vadd center d)))
        ring (fn [i]
               (let [c (ring-center i)
                     a (* 2.0 PI (/ (double i) nu))
                     radial (vnorm (vsub c center))]
                 (for [j (range nv)
                       :let [b (* 2.0 PI (/ (double j) nv))
                             off (vadd (vmul radial (* r (Math/cos b)))
                                       (vmul [ux uy uz] (* r (Math/sin b))))]]
                   (vadd c off))))
        verts (vec (apply concat (for [i (range nu)] (ring i))))
        faces (for [i (range nu) j (range nv)
                    :let [i2 (mod (inc i) nu) j2 (mod (inc j) nv)]]
                [(+ (* i nv) j) (+ (* i2 nv) j) (+ (* i2 nv) j2) (+ (* i nv) j2)])]
    (let [o (bpy/add! name verts (vec faces))]
      (when mat (nodes/assign! o mat))
      (parent! o root))))

(defn sgnpow [x e] (* (Math/signum (double x)) (Math/pow (Math/abs (double x)) e)))

(defn superellipsoid!
  "Rounded-box / pillow: radii rx,ry,rz, squareness exponents e1 (vertical)
  and e2 (horizontal). Good for cushions, slabs, domes."
  [name center rx ry rz e1 e2 nu nv mat root]
  (let [[cx cy cz] center
        cv (fn [w e] (sgnpow (Math/cos w) e))
        sv (fn [w e] (sgnpow (Math/sin w) e))
        ring (fn [k]
               (let [v (- (* PI (/ (double k) nv)) (/ PI 2.0))]
                 (for [i (range nu)
                       :let [u (* 2.0 PI (/ (double i) nu))]]
                   [(+ cx (* rx (cv v e1) (cv u e2)))
                    (+ cy (* ry (cv v e1) (sv u e2)))
                    (+ cz (* rz (sv v e1)))])))
        tiers (vec (for [k (range 1 nv)] (ring k)))
        bottom [[cx cy (- cz rz)]]
        top [[cx cy (+ cz rz)]]
        verts (vec (concat bottom (apply concat tiers) top))
        nrt (count tiers)
        nring (* nu nrt)
        idx (fn [k i] (+ 1 (* k nu) i))
        faces (vec
               (concat
                (for [i (range nu) :let [j (mod (inc i) nu)]]
                  [0 (idx 0 i) (idx 0 j)])
                (for [k (range (dec nrt)) i (range nu)
                      :let [j (mod (inc i) nu)]]
                  [(idx k i) (idx k j) (idx (inc k) j) (idx (inc k) i)])
                (for [i (range nu) :let [j (mod (inc i) nu)]]
                  [(+ 1 nring) (idx (dec nrt) j) (idx (dec nrt) i)])))]
    (let [o (bpy/add! name verts faces)]
      (when mat (nodes/assign! o mat))
      (parent! o root))))

(defn tube!
  "Round tube along a polyline of points (open ends; caps optional)."
  [name pts r n mat root]
  (let [pts (vec pts)
        npts (count pts)
        rings (vec
               (for [i (range npts)]
                 (let [p (nth pts i)
                       t (vnorm (vsub (nth pts (min (dec npts) (inc i)))
                                      (nth pts (max 0 (dec i)))))
                       helper (if (< (Math/abs (third t)) 0.9) [0.0 0.0 1.0] [1.0 0.0 0.0])
                       s (vnorm (vcross helper t))
                       u (vcross t s)]
                   (for [j (range n)
                         :let [a (* 2.0 PI (/ (double j) n))
                               c (vmul s (* r (Math/cos a)))
                               d (vmul u (* r (Math/sin a)))]]
                     (vadd p (vadd c d))))))
        verts (vec (apply concat rings))
        faces (vec (for [i (range (dec npts)) j (range n)
                         :let [j2 (mod (inc j) n)]]
                     [(+ (* i n) j) (+ (* (inc i) n) j)
                      (+ (* (inc i) n) j2) (+ (* i n) j2)]))]
    (let [o (bpy/add! name verts faces)]
      (when mat (nodes/assign! o mat))
      (parent! o root))))

;; ---------------------------------------------------------------------------
;; hull shape
;; ---------------------------------------------------------------------------

(defn station
  "Hull station at u (0 stern .. 1 bow). Returns a map with the sheer/beam/keel
  and the 11-point cross-section ring in XYZ."
  [u]
  (let [b (profile [[0.0 2.32] [0.12 2.62] [0.30 2.80] [0.50 2.80] [0.62 2.72]
                    [0.74 2.48] [0.84 2.02] [0.91 1.42] [0.96 0.78] [1.0 0.06]] u)
        zs (profile [[0.0 1.52] [0.15 1.55] [0.35 1.62] [0.55 1.78] [0.72 2.00]
                     [0.85 2.24] [0.94 2.42] [1.0 2.52]] u)
        zk (profile [[0.0 -1.28] [0.12 -1.44] [0.30 -1.52] [0.55 -1.52] [0.72 -1.44]
                     [0.82 -1.28] [0.90 -0.95] [0.96 -0.35] [1.0 0.52]] u)
        th (* (/ (lerp 18.0 22.0 u) 180.0) PI)
        xc (* b 0.97)
        zc (+ zk (* xc (Math/tan th)))
        zc (min zc (- zs 0.28))
        xm (* xc 0.45)
        zm (+ zk (* 0.52 (- zc zk)))
        zbl (max (+ zc 0.006) -0.055)
        zbh (max (+ zc 0.025) 0.135)
        xb (* b 0.996)
        dy (- zs zk)
        rake (* 1.35 (Math/pow (smooth (/ (- u 0.55) 0.45)) 1.15))
        y0 (+ -11.0 (* 21.4 u))
        ypt (fn [z] (+ y0 (* rake (if (> dy 1e-6) (/ (- z zk) dy) 0.0))))
        sec (fn [x z] [x (ypt z) z])]
    {:u u :y y0 :b b :zs zs :zk zk :rake rake :rake-y ypt
     :ring (vec [(sec (- b) zs) (sec (- xb) zbh) (sec (- xb) zbl) (sec (- xc) zc)
                 (sec (- xm) zm) (sec 0.0 zk) (sec xm zm) (sec xc zc)
                 (sec xb zbl) (sec xb zbh) (sec b zs)])}))

(defn build-hull! [root mats]
  (let [n 58
        sts (vec (for [i (range (inc n))]
                   (station (* 0.5 (- 1.0 (Math/cos (* PI (/ (double i) n))))))))
        verts (vec (apply concat (map :ring sts)))
        idx (fn [i j] (+ (* i 11) j))
        side (for [i (range n) j (range 10)]
               [(idx i j) (idx (inc i) j) (idx (inc i) (inc j)) (idx i (inc j))])
        transom (vec (range 11))
        bow (vec (reverse (map #(+ (* n 11) %) (range 11))))
        faces (vec (concat (vec side) [transom bow]))
        face-z (fn [f]
                 (/ (reduce + (map #(third (nth verts %)) f)) (count f)))
        mi (fn [f] (let [z (face-z f)]
                     (cond (< z -0.055) 1 (< z 0.145) 2 :else 0)))
        matidx (mapv mi faces)
        o (bpy/add! "Y-Yacht" verts faces)
        mesh (b/get o "data")]
    (doseq [m [:navy :red :boot]]
      (b/call (b/get mesh "materials") "append" {:material (get mats m)}))
    ;; bulk material-index write: per-polygon set! costs minutes at this size
    (b/foreach-set! mesh "polygons" "material_index" (double-array matidx))
    (try (b/call mesh "shade_smooth") (catch Exception _ nil))
    (b/set! o "location" [24.0 -16.0 0.0])
    (parent! o nil)
    o))

(defn deck-z
  "Deck (sheer + camber) height at local y; used by geometry and walk mode."
  [y]
  (let [u (clamp (/ (+ y 11.0) 21.4) 0.0 1.0)
        s (station u)]
    (+ (:zs s) 0.10)))

(defn build-deck!
  "Teak main deck from the sheer outline, skipping the companionway opening."
  [root mats]
  (let [n 76
        hole-x [-1.78 -0.68]
        hole-y [-5.35 -2.15]
        fvals [-1.0 -0.78 -0.55 -0.30 0.0 0.30 0.55 0.78 1.0]
        sts (vec (for [i (range (inc n))]
                   (let [u (* 0.5 (- 1.0 (Math/cos (* PI (/ (double i) n)))))
                         s (station u)]
                     s)))
        ring (fn [s]
               (for [f fvals
                     :let [b (:b s)
                           zs (:zs s)
                           x (* b f)
                           z (+ zs (* 0.10 (- 1.0 (* f f))))]]
                 [x ((:rake-y s) z) z]))
        rings (vec (map ring sts))
        ncol (count fvals)
        verts (vec (apply concat rings))
        idx (fn [i j] (+ (* i ncol) j))
        quads (for [i (range n) j (range (dec ncol))
                    :let [f0 (nth fvals j)
                          f1 (nth fvals (inc j))
                          xc (* 0.5 (+ (* (:b (nth sts i)) f0) (* (:b (nth sts i)) f1)))
                          yc (* 0.5 (+ (:y (nth sts i)) (:y (nth sts (inc i)))))
                          hole? (and (> xc (nth hole-x 0)) (< xc (nth hole-x 1))
                                     (> yc (nth hole-y 0)) (< yc (nth hole-y 1)))]]
                (when-not hole?
                  [(idx i j) (idx i (inc j)) (idx (inc i) (inc j)) (idx (inc i) j)]))
        faces (vec (remove nil? quads))
        o (bpy/add! "Y-Deck" verts faces)]
    (nodes/assign! o (:teak mats))
    (parent! o root)))

;; ---------------------------------------------------------------------------
;; superstructure
;; ---------------------------------------------------------------------------

(def ^:private roof-w [[-2.0 1.92] [0.0 1.92] [2.0 1.82] [4.0 1.58]
                       [6.0 1.30] [8.0 0.86] [8.8 0.26]])

(def ^:private roof-top [[-2.0 2.60] [0.0 2.57] [2.0 2.50] [4.0 2.42]
                         [6.0 2.33] [8.0 2.25] [8.8 2.22]])

(defn roof-z
  "Top of the coachroof (foredeck) at local y."
  [y]
  (profile roof-top (clamp y -2.0 8.8)))

(defn roof-half
  [y]
  (profile roof-w (clamp y -2.0 8.8)))

(defn build-coachroof! [root mats]
  (let [n 30
        nu 22
        ys (vec (for [i (range (inc n))] (lerp -2.0 8.8 (/ (double i) n))))
        sec (fn [y]
              (let [w (roof-half y)
                    zt (roof-z y)
                    zb (- (deck-z y) 0.16)
                    zc (* 0.5 (+ zb zt))
                    rz (* 0.5 (- zt zb))
                    loop-pts (for [i (range nu)
                                   :let [a (* 2.0 PI (/ (double i) nu))
                                         cv (sgnpow (Math/cos a) 0.35)
                                         sv (sgnpow (Math/sin a) 0.35)]]
                               [(* w cv) y (+ zc (* rz sv))])]
                (vec loop-pts)))
        rings (vec (map sec ys))
        verts (vec (apply concat rings))
        idx (fn [i j] (+ (* i nu) j))
        quads (for [i (range n) j (range nu)
                    :let [j2 (mod (inc j) nu)]]
                [(idx i j) (idx (inc i) j) (idx (inc i) j2) (idx i j2)])
        cap0 (vec (reverse (range nu)))
        cap1 (vec (map #(+ (* n nu) %) (range nu)))
        o (bpy/add! "Y-Coachroof" verts (vec (concat quads [cap0 cap1])))]
    (nodes/assign! o (:white mats))
    (parent! o root)))

(defn coach-windows! [root mats]
  (doseq [side [-1.0 1.0]]
    (let [ys (vec (for [k (range 17)] (lerp -1.35 4.60 (/ (double k) 16.0))))
          lo (vec (for [y ys] (let [x (* side (- (roof-half y) 0.012))] [x y 2.02])))
          hi (vec (for [y ys] (let [x (* side (- (roof-half y) 0.012))] [x y 2.31])))
          verts (vec (concat lo hi))
          n (count ys)
          faces (vec (for [i (range (dec n))]
                       [i (inc i) (+ n (inc i)) (+ n i)]))
          o (bpy/add! (str "Y-CoachWin" side) verts faces)]
      (nodes/assign! o (:glass mats))
      (parent! o root))))

(defn build-windscreen! [root mats]
  (let [n 22
        w 1.90
        ybase -2.02
        fr 0.030
        pts (vec (for [i (range (inc n))
                       :let [t (/ (double i) n)
                             x (lerp (- w) w t)
                             k (Math/pow (/ x w) 2)]]
                   {:x x :yb (- ybase (* 0.34 k)) :yb2 (- ybase 0.30 (* 0.36 k))
                    :zb 1.93 :zt (- 2.60 (* 0.06 k))}))
        vb (vec (for [p pts] [(:x p) (:yb p) (:zb p)]))
        vt (vec (for [p pts] [(:x p) (:yb2 p) (:zt p)]))
        verts (vec (concat vb vt))
        faces (vec (for [i (range n)]
                     [i (inc i) (+ n (inc i)) (+ n i)]))
        glass (bpy/add! "Y-Windshield" verts faces)]
    (nodes/assign! glass (:glass mats))
    (parent! glass root)
    ;; frames
    (tube! "Y-WindTop" vt fr 6 (:steel mats) root)
    (tube! "Y-WindBot" vb fr 6 (:steel mats) root)
    (doseq [i [0 (quot n 2) n]]
      (let [p (nth pts i)]
        (cylinder! (str "Y-WindPost" i)
                   [(:x p) (:yb p) (:zb p)] [(:x p) (:yb2 p) (:zt p)]
                   0.028 6 (:steel mats) root)))))

(defn build-hardtop! [root mats]
  (superellipsoid! "Y-Hardtop" [0.0 -3.25 3.45] 2.48 1.82 0.075 0.22 0.22
                   28 10 (:white mats) root)
  ;; underside trim / headliner
  (superellipsoid! "Y-Headliner" [0.0 -3.25 3.37] 2.30 1.66 0.03 0.25 0.25
                   24 8 (:linen mats) root))

(defn build-arch! [root mats]
  (let [path [[-2.36 -5.30 1.48] [-2.54 -5.30 2.85] [-2.32 -5.30 3.72]
              [-1.1 -5.30 3.86] [0.0 -5.30 3.90] [1.1 -5.30 3.86]
              [2.32 -5.30 3.72] [2.54 -5.30 2.85] [2.36 -5.30 1.48]]]
    (tube! "Y-Arch" path 0.085 10 (:steel mats) root)
    ;; radar pod
    (cylinder! "Y-RadarBase" [0.0 -5.30 3.88] [0.0 -5.30 4.00] 0.32 18 (:radar mats) root)
    (superellipsoid! "Y-RadarDome" [0.0 -5.30 4.18] 0.34 0.34 0.21 0.75 0.75
                     20 10 (:radar mats) root)
    ;; antennas
    (cylinder! "Y-Ant1" [-1.35 -5.34 3.86] [-1.55 -5.34 5.15] 0.014 6 (:steel mats) root)
    (cylinder! "Y-Ant2" [1.35 -5.34 3.86] [1.55 -5.34 5.15] 0.014 6 (:steel mats) root)
    (cylinder! "Y-Ant3" [0.55 -5.34 3.90] [0.55 -5.34 4.70] 0.012 6 (:steel mats) root)))

(defn build-bimini! [root mats]
  (let [ny 10 nx 8
        xw 2.30
        y0 -8.70 y1 -5.34
        pts (vec (for [i (range (inc ny))
                       :let [t (/ (double i) ny)
                             y (lerp y0 y1 t)
                             z (+ 2.90 (* 0.66 t))]]
                   (for [j (range (inc nx))
                         :let [x (lerp (- xw) xw (/ (double j) nx))
                               sag (* 0.055 (- 1.0 (Math/pow (/ x xw) 2)))]]
                     [x y (- z sag)])))
        verts (vec (apply concat pts))
        idx (fn [i j] (+ (* i (inc nx)) j))
        faces (vec (for [i (range ny) j (range nx)]
                     [(idx i j) (idx (inc i) j) (idx (inc i) (inc j)) (idx i (inc j))]))
        o (bpy/add! "Y-Bimini" verts faces)]
    (nodes/assign! o (:canvas mats))
    (parent! o root)
    ;; perimeter rail
    (tube! "Y-BiminiEdge"
           (concat (for [j (range (inc nx))] (nth (nth pts ny) j))
                   (for [i (range (dec ny) -1 -1)] (nth (nth pts i) 0)))
           0.018 5 (:steel mats) root)))

(defn build-rails! [root mats]
  (doseq [side [-1.0 1.0]]
    (let [us (vec (for [k (range 29)] (lerp 0.015 0.965 (/ (double k) 28))))
          pts (vec (for [u us]
                     (let [s (station u)
                           x (* side (- (:b s) 0.055))
                           ytop ((:rake-y s) (:zs s))
                           z (:zs s)]
                       [[x ytop (+ z 0.70)] [x ytop (+ z 0.36)] [x ytop z]])))
          hi (mapv #(nth % 0) pts)
          mid (mapv #(nth % 1) pts)
          lo (mapv #(nth % 2) pts)]
      (tube! (str "Y-RailHi" side) hi 0.020 6 (:steel mats) root)
      (tube! (str "Y-RailMid" side) mid 0.016 6 (:steel mats) root)
      (doseq [k (range 0 29 3)]
        (cylinder! (str "Y-Stan" side "-" k) (nth lo k) (nth hi k) 0.020 6 (:steel mats) root)))
    ;; bow pulpit cross piece
    (let [s (station 0.965)
          x (* side (- (:b s) 0.055))
          y ((:rake-y s) (:zs s))]
      (tube! (str "Y-BowRail" side)
             [[(* side 2.45) (- y 1.6) (+ (:zs s) 0.70)]
              [x y (+ (:zs s) 0.70)]
              [( * side 0.55) (+ y 0.5) (+ (:zs s) 0.70)]]
             0.020 6 (:steel mats) root))))

(defn build-cleats! [root mats]
  (doseq [u [0.12 0.52 0.86] side [-1.0 1.0]]
    (let [s (station u)
          x (* side (- (:b s) 0.12))
          y ((:rake-y s) (:zs s))
          z (+ (:zs s) 0.12)]
      (cylinder! (str "Y-CleatBar" side u) [(- x 0.19) y z] [(+ x 0.19) y z] 0.038 8 (:steel mats) root)
      (cylinder! (str "Y-CleatA" side u) [(- x 0.19) y z] [(- x 0.19) y (- z 0.11)] 0.030 6 (:steel mats) root)
      (cylinder! (str "Y-CleatB" side u) [(+ x 0.19) y z] [(+ x 0.19) y (- z 0.11)] 0.030 6 (:steel mats) root))))

(defn build-anchor! [root mats]
  (let [s (station 0.94)
        z (:zs s)
        y ((:rake-y s) z)]
    (box! "Y-BowRoller" [-0.22 (+ y 0.25) (+ z 0.10)] [0.22 (+ y 1.15) (+ z 0.32)] (:steel mats) root)
    (cylinder! "Y-AnchorShank" [0.0 (+ y 0.55) (+ z 0.30)] [0.0 (+ y 1.25) (- z 0.55)] 0.045 6 (:dark mats) root)
    (box! "Y-AnchorFlukeA" [-0.34 (+ y 1.10) (- z 0.62)] [0.0 (+ y 1.32) (- z 0.42)] (:dark mats) root)
    (box! "Y-AnchorFlukeB" [0.0 (+ y 1.10) (- z 0.62)] [0.34 (+ y 1.32) (- z 0.42)] (:dark mats) root)
    (cylinder! "Y-Windlass" [0.0 (+ y 0.15) (+ z 0.18)] [0.0 (+ y 0.15) (+ z 0.42)] 0.16 12 (:brushed mats) root)))

(defn build-navlights! [root mats]
  ;; port red / starboard green on the coachroof shoulders, stern + masthead white
  (superellipsoid! "Y-NavRedP" [-1.55 6.6 2.47] 0.07 0.10 0.07 0.7 0.7 12 8 (:redl mats) root)
  (superellipsoid! "Y-NavGreenS" [1.55 6.6 2.47] 0.07 0.10 0.07 0.7 0.7 12 8 (:greenl mats) root)
  (superellipsoid! "Y-NavStern" [0.0 -10.85 1.85] 0.06 0.06 0.06 0.8 0.8 10 8 (:whitel mats) root)
  (superellipsoid! "Y-NavMast" [0.0 -5.30 5.30] 0.055 0.055 0.055 0.8 0.8 10 8 (:whitel mats) root)
  (cylinder! "Y-NavMastPole" [0.0 -5.30 4.55] [0.0 -5.30 5.30] 0.012 6 (:steel mats) root))

(defn build-flag! [root mats]
  (cylinder! "Y-FlagPole" [0.0 -10.7 1.65] [0.0 -10.7 2.45] 0.014 6 (:steel mats) root)
  (let [v [[0.0 -10.7 2.42] [0.0 -10.0 2.36] [0.0 -10.0 2.16] [0.0 -10.7 2.22]]]
    (let [o (bpy/add! "Y-Ensign" v [[0 1 2 3]])]
      (nodes/assign! o (:canvas mats))
      (parent! o root))))

;; ---------------------------------------------------------------------------
;; decks, cockpit furniture, helm
;; ---------------------------------------------------------------------------

(defn build-swim-platform! [root mats]
  (box! "Y-SwimPlatform" [-2.30 -12.55 0.30] [2.30 -10.95 0.42] (:teak mats) root)
  (box! "Y-TransomStep" [-1.6 -11.15 0.42] [1.6 -10.9 0.55] (:teak mats) root)
  ;; fold-down ladder
  (cylinder! "Y-LadderA" [-0.42 -12.45 0.34] [-0.42 -12.75 1.00] 0.020 6 (:steel mats) root)
  (cylinder! "Y-LadderB" [0.42 -12.45 0.34] [0.42 -12.75 1.00] 0.020 6 (:steel mats) root)
  (doseq [i (range 3)]
    (cylinder! (str "Y-LadderR" i) [-0.42 -12.62 (+ 0.50 (* 0.18 i))]
               [0.42 -12.62 (+ 0.50 (* 0.18 i))] 0.013 6 (:steel mats) root))
  ;; transom shower
  (cylinder! "Y-Shower" [1.65 -11.02 1.30] [1.65 -11.02 1.62] 0.016 6 (:steel mats) root)
  (superellipsoid! "Y-ShowerHead" [1.65 -11.05 1.64] 0.05 0.05 0.03 0.7 0.7 10 6 (:steel mats) root))

(defn seat-cushion! [name center rx ry rz mat root]
  (superellipsoid! name center rx ry rz 0.42 0.42 20 12 mat root))

(defn build-cockpit! [root mats]
  (let [zb 1.73]
    ;; aft U-lounge
    (box! "Y-LoungeBaseA" [-1.95 -8.45 1.55] [1.95 -7.85 1.96] (:white mats) root)
    (box! "Y-LoungeBaseP" [-1.95 -7.85 1.55] [-1.55 -6.30 1.96] (:white mats) root)
    (box! "Y-LoungeBaseS" [1.55 -7.85 1.55] [1.95 -6.30 1.96] (:white mats) root)
    (seat-cushion! "Y-LoungeSeatA" [0.0 -8.15 2.02] 1.85 0.30 0.13 (:leather mats) root)
    (seat-cushion! "Y-LoungeSeatP" [-1.75 -7.10 2.02] 0.30 0.72 0.13 (:leather mats) root)
    (seat-cushion! "Y-LoungeSeatS" [1.75 -7.10 2.02] 0.30 0.72 0.13 (:leather mats) root)
    (seat-cushion! "Y-LoungeBackA" [0.0 -8.42 2.36] 1.85 0.12 0.30 (:leather mats) root)
    (seat-cushion! "Y-LoungeBackP" [-1.92 -7.10 2.36] 0.12 0.72 0.30 (:leather mats) root)
    (seat-cushion! "Y-LoungeBackS" [1.92 -7.10 2.36] 0.12 0.72 0.30 (:leather mats) root)
    ;; coffee table
    (superellipsoid! "Y-CockpitTable" [0.0 -7.15 2.10] 0.62 0.42 0.05 0.4 0.35 18 8 (:teak mats) root)
    (cylinder! "Y-TableLeg" [0.0 -7.15 1.95] [0.0 -7.15 2.05] 0.055 10 (:steel mats) root)
    ;; wet bar
    (box! "Y-WetBar" [1.10 -6.05 1.55] [2.10 -4.55 2.35] (:white mats) root)
    (box! "Y-WetBarTop" [1.08 -6.08 2.35] [2.12 -4.52 2.42] (:stone mats) root)
    (box! "Y-WetBarFridge" [1.10 -5.30 1.70] [1.13 -4.80 2.20] (:tv mats) root)
    (cylinder! "Y-WetBarSink" [1.60 -5.55 2.40] [1.60 -5.55 2.44] 0.14 14 (:brushed mats) root)
    (cylinder! "Y-WetBarTap" [1.82 -5.55 2.42] [1.82 -5.55 2.72] 0.018 6 (:steel mats) root)
    ;; helm console
    (box! "Y-HelmConsole" [-0.75 -2.95 1.55] [1.55 -2.28 2.22] (:white mats) root)
    (box! "Y-HelmDash" [-0.75 -2.98 2.22] [1.55 -2.54 2.46] (:dark mats) root)
    ;; two multifunction displays (text objects overlay them)
    (superellipsoid! "Y-MFD1" [-0.20 -2.63 2.45] 0.30 0.02 0.15 0.35 0.35 14 8 (:tv mats) root)
    (superellipsoid! "Y-MFD2" [0.72 -2.63 2.45] 0.30 0.02 0.15 0.35 0.35 14 8 (:tv mats) root)
    ;; compass
    (superellipsoid! "Y-Compass" [1.28 -2.75 2.50] 0.10 0.08 0.10 0.7 0.7 14 8 (:dark mats) root)
    ;; wheel
    (torus! "Y-Wheel" [0.30 -3.02 2.20] [0.0 -1.0 0.25] 0.20 0.022 28 9 (:leatherd mats) root)
    (cylinder! "Y-WheelHub" [0.30 -3.06 2.20] [0.30 -2.98 2.20] 0.045 10 (:steel mats) root)
    (doseq [a [0.0 (/ (* 2 PI) 3.0) (/ (* 4 PI) 3.0)]]
      (cylinder! (str "Y-Spoke" a)
                 [0.30 -3.05 2.20]
                 [(+ 0.30 (* 0.19 (Math/sin a))) -3.02 (+ 2.20 (* 0.19 (Math/cos a)))]
                 0.012 6 (:steel mats) root))
    ;; throttle levers
    (box! "Y-ThrottleBase" [1.02 -3.02 2.20] [1.34 -2.86 2.28] (:dark mats) root)
    (cylinder! "Y-ThrottleL1" [1.10 -2.94 2.26] [1.10 -2.86 2.60] 0.016 6 (:steel mats) root)
    (cylinder! "Y-ThrottleL2" [1.25 -2.94 2.26] [1.25 -2.86 2.60] 0.016 6 (:steel mats) root)
    (superellipsoid! "Y-ThrottleK1" [1.10 -2.86 2.62] 0.035 0.035 0.03 0.6 0.6 10 6 (:steel mats) root)
    (superellipsoid! "Y-ThrottleK2" [1.25 -2.86 2.62] 0.035 0.035 0.03 0.6 0.6 10 6 (:steel mats) root)
    ;; two helm seats
    (doseq [[nm x] [["P" 0.10] ["S" 0.92]]]
      (cylinder! (str "Y-SeatPed" nm) [x -3.55 1.55] [x -3.55 1.98] 0.10 10 (:steel mats) root)
      (box! (str "Y-SeatBase" nm) [(- x 0.30) -3.88 1.97] [(+ x 0.30) -3.24 2.06] (:dark mats) root)
      (seat-cushion! (str "Y-SeatCush" nm) [x -3.52 2.12] 0.30 0.30 0.09 (:leatherd mats) root)
      (seat-cushion! (str "Y-SeatBack" nm) [x -3.78 2.34] 0.30 0.09 0.24 (:leatherd mats) root))))

(defn build-sunpad! [root mats]
  (let [y 4.2
        z (+ (roof-z y) 0.10)]
    (superellipsoid! "Y-Sunpad" [0.0 y z] 1.35 1.55 0.14 0.35 0.35 24 12 (:tan mats) root)
    (superellipsoid! "Y-SunpadPillow1" [-0.75 (+ y 1.15) (+ z 0.10)] 0.40 0.22 0.12 0.55 0.55 16 8 (:linen mats) root)
    (superellipsoid! "Y-SunpadPillow2" [0.75 (+ y 1.15) (+ z 0.10)] 0.40 0.22 0.12 0.55 0.55 16 8 (:linen mats) root)))

;; ---------------------------------------------------------------------------
;; interior
;; ---------------------------------------------------------------------------

(declare door-x! door-y!)

(defn build-interior! [root mats]
  (let [xw 1.72
        zf -0.36
        zc 1.54
        y0 -2.30
        y1 6.10
        win-z0 0.55
        win-z1 0.98]
    ;; floor
    (box! "Y-IntFloor" [(- xw) y0 (- zf 0.06)] [xw y1 zf] (:oak mats) root)
    ;; ceiling (with a gap above the companionway stairs, y < -2.3 handled by deck)
    (box! "Y-IntCeiling" [(- xw) y0 zc] [xw y1 (+ zc 0.06)] (:linen mats) root)
    ;; side liners: lower + upper panels, leaving a window slot
    (doseq [side [-1.0 1.0]]
      (let [x (* side xw)]
        (box! (str "Y-IntWallLow" side) [x y0 zf] [(+ x (* side 0.05)) y1 win-z0] (:walnut mats) root)
        (box! (str "Y-IntWallUp" side) [x y0 win-z1] [(+ x (* side 0.05)) y1 zc] (:walnut mats) root)
        ;; window frame + glass in the saloon stretch
        (box! (str "Y-WinGlass" side) [(* side (- xw -0.02)) -2.1 (+ win-z0 0.04)]
              [(* side (+ xw 0.01)) 1.15 (- win-z1 0.04)] (:glass mats) root)
        (box! (str "Y-WinSill" side) [(* side (- xw 0.03)) -2.1 win-z0]
              [(* side (+ xw 0.03)) 1.15 (+ win-z0 0.05)] (:oak mats) root)
        (box! (str "Y-WinHead" side) [(* side (- xw 0.03)) -2.1 (- win-z1 0.05)]
              [(* side (+ xw 0.03)) 1.15 win-z1] (:oak mats) root)))
    ;; bulkheads
    ;; aft bulkhead (with companionway opening at port)
    (box! "Y-BulkAftA" [(- xw) y0 zf] [-0.60 (+ y0 0.06) zc] (:walnut mats) root)
    (box! "Y-BulkAftB" [-0.60 y0 zf] [xw (+ y0 0.06) zc] (:walnut mats) root)
    ;; step down opening: just cut the aft bulkhead above the stairs
    ;; saloon / galley-head bulkhead at y=1.25
    (box! "Y-BulkGalleyA" [(- xw) 1.22 zf] [-0.62 1.30 zc] (:walnut mats) root)
    (box! "Y-BulkGalleyB" [0.62 1.22 zf] [xw 1.30 zc] (:walnut mats) root)
    (box! "Y-BulkGalleyTop" [-0.62 1.22 1.35] [0.62 1.30 zc] (:walnut mats) root)
    ;; master bulkhead at y=3.10 with a centre door gap
    (box! "Y-BulkMasterA" [(- xw) 3.05 zf] [-0.55 3.13 zc] (:walnut mats) root)
    (box! "Y-BulkMasterB" [0.55 3.05 zf] [xw 3.13 zc] (:walnut mats) root)
    (box! "Y-BulkMasterTop" [-0.55 3.05 1.35] [0.55 3.13 zc] (:walnut mats) root)
    ;; head partition at x=0.55 (starboard head) with a doorway 1.70..2.50
    (box! "Y-HeadWallA" [0.52 1.34 zf] [0.60 1.70 zc] (:walnut mats) root)
    (box! "Y-HeadWallB" [0.52 2.50 zf] [0.60 3.05 zc] (:walnut mats) root)
    (box! "Y-HeadWallTop" [0.52 1.70 1.35] [0.60 2.50 zc] (:walnut mats) root)
    ;; doors (animated by main.clj)
    (door-x! "Y-DoorMaster" root mats [-0.52 3.09 -0.36] 1.04)
    (door-y! "Y-DoorHead" root mats [0.55 1.70 -0.36] 0.80)
    ;; saloon: curved sofa (built from cushions along a curve)
    (box! "Y-SofaBase" [-1.55 -2.15 zf] [-0.75 0.20 0.34] (:white mats) root)
    (doseq [y [-1.95 -1.50 -1.05 -0.60 -0.15]]
      (seat-cushion! (str "Y-SofaSeat" y) [-1.12 y (- 0.34 0.10)] 0.38 0.21 0.10 (:leather mats) root))
    (doseq [y [-1.95 -1.50 -1.05 -0.60 -0.15]]
      (seat-cushion! (str "Y-SofaBack" y) [-1.48 y 0.68] 0.12 0.21 0.30 (:leather mats) root))
    (seat-cushion! "Y-SofaCorner" [-1.10 -2.28 0.24] 0.45 0.30 0.10 (:leather mats) root)
    ;; saloon table
    (superellipsoid! "Y-SalonTable" [0.10 -0.95 0.55] 0.55 0.85 0.05 0.35 0.35 18 8 (:oak mats) root)
    (cylinder! "Y-SalonTableLeg" [0.10 -0.95 0.30] [0.10 -0.95 0.50] 0.06 8 (:steel mats) root)
    ;; TV on forward bulkhead
    (box! "Y-TV" [-0.65 1.14 0.55] [0.65 1.20 1.15] (:tv mats) root)
    ;; galley (port side, y 1.4..3.0)
    (box! "Y-GalleyBase" [-1.60 1.40 zf] [-0.70 2.95 0.42] (:walnut mats) root)
    (box! "Y-GalleyTop" [-1.64 1.36 0.42] [-0.64 2.99 0.48] (:stone mats) root)
    (cylinder! "Y-GalleySink" [-1.15 2.15 0.46] [-1.15 2.15 0.50] 0.18 14 (:brushed mats) root)
    (cylinder! "Y-GalleyTap" [-1.38 2.15 0.46] [-1.38 2.15 0.80] 0.016 6 (:steel mats) root)
    (box! "Y-Hob" [-1.45 2.62 0.48] [-1.02 2.90 0.51] (:black mats) root)
    ;; master cabin: double bed
    (box! "Y-BedBase" [-0.95 3.68 zf] [0.95 5.88 0.22] (:walnut mats) root)
    (superellipsoid! "Y-Mattress" [0.0 4.78 0.36] 0.90 1.05 0.16 0.35 0.35 22 10 (:linen mats) root)
    (superellipsoid! "Y-Duvet" [0.0 5.13 0.47] 0.92 0.72 0.06 0.35 0.35 20 8 (:white mats) root)
    (superellipsoid! "Y-PillowL" [-0.45 3.88 0.50] 0.36 0.16 0.09 0.5 0.5 14 8 (:linen mats) root)
    (superellipsoid! "Y-PillowR" [0.45 3.88 0.50] 0.36 0.16 0.09 0.5 0.5 14 8 (:linen mats) root)
    ;; bedside cabinets + lamps
    (box! "Y-NightL" [-1.25 3.62 zf] [-0.98 3.98 0.44] (:walnut mats) root)
    (box! "Y-NightR" [0.98 3.62 zf] [1.25 3.98 0.44] (:walnut mats) root)
    (superellipsoid! "Y-LampL" [-1.12 3.80 0.56] 0.10 0.10 0.10 0.7 0.7 12 8 (:led mats) root)
    (superellipsoid! "Y-LampR" [1.12 3.80 0.56] 0.10 0.10 0.10 0.7 0.7 12 8 (:led mats) root)
    ;; wardrobe
    (box! "Y-Wardrobe" [-1.55 5.10 zf] [-0.95 5.95 1.50] (:walnut mats) root)
    ;; head: vanity, mirror, shower, toilet (starboard y 1.4..2.9)
    (box! "Y-HeadVanity" [0.66 1.45 zf] [1.45 2.20 0.55] (:stone mats) root)
    (cylinder! "Y-HeadBasin" [1.05 1.82 0.55] [1.05 1.82 0.60] 0.14 14 (:brushed mats) root)
    (box! "Y-HeadMirror" [0.68 1.42 0.75] [1.42 1.47 1.35] (:glass mats) root)
    (box! "Y-Toilet" [1.05 2.30 0.05] [1.40 2.75 0.45] (:white mats) root)
    (box! "Y-ShowerTray" [0.66 2.45 zf] [1.45 2.95 0.04] (:stone mats) root)
    (box! "Y-ShowerGlass" [0.66 2.44 0.05] [0.70 2.95 1.45] (:glassclear mats) root)
    ;; warm interior practical lights
    (doseq [[nm p] [["Y-IntLightSalon" [0.0 -0.8 1.35]]
                    ["Y-IntLightGalley" [-1.0 2.2 1.35]]
                    ["Y-IntLightMaster" [0.0 4.4 1.30]]
                    ["Y-IntLightHead" [1.0 2.2 1.30]]]]
      (let [ld (b/id-new "Light" (str nm "Data"))
            o (b/object-new nm ld)]
        (b/set! ld "type" "POINT")
        (b/set! ld "energy" 22.0)
        (b/set! ld "color" [1.0 0.72 0.42])
        (b/set! ld "shadow_soft_size" 0.18)
        (bpy/link! o)
        (b/set! o "location" p)
        (parent! o root)))
    ;; LED cove strips under the ceiling edges
    (doseq [side [-1.0 1.0]]
      (box! (str "Y-LEDStrip" side) [(* side 1.52) -2.1 1.48] [(* side 1.60) 5.9 1.52] (:led mats) root))))

(defn door-x!
  "Door panel hinged at `hinge`, extending +X by w (wall at constant y)."
  [name root mats hinge w]
  (let [[hx hy hz] hinge]
    (box! name [hx hy hz] [(+ hx w) (+ hy 0.05) (+ hz 1.90)] (:oak mats) root)))

(defn door-y!
  "Door panel hinged at `hinge`, extending +Y by w (wall at constant x)."
  [name root mats hinge w]
  (let [[hx hy hz] hinge]
    (box! name [hx hy hz] [(+ hx 0.05) (+ hy w) (+ hz 1.90)] (:oak mats) root)))

;; ---------------------------------------------------------------------------
;; stairs (companionway), hull portholes, ocean mask
;; ---------------------------------------------------------------------------

(defn build-stairs! [root mats]
  ;; 8 steps from cockpit deck (1.73) down to interior (-0.36), port side
  (doseq [i (range 8)]
    (let [t (/ (double i) 7.0)
          y (lerp -4.90 -2.35 t)
          z (lerp 1.63 -0.30 t)]
      (box! (str "Y-Step" i) [-1.66 y z] [-0.78 (+ y 0.34) (+ z 0.06)] (:teak mats) root)))
  ;; side cheeks of the companionway
  (box! "Y-StairCheekL" [-1.74 -4.95 -0.40] [-1.66 -2.25 1.70] (:walnut mats) root)
  (box! "Y-StairCheekR" [-0.78 -4.95 -0.40] [-0.70 -2.25 1.70] (:walnut mats) root))

(defn build-portholes! [root mats]
  (doseq [side [-1.0 1.0] y [-0.6 0.4 1.4 2.4 4.6]]
    (let [s (station (clamp (/ (+ y 11.0) 21.4) 0.0 1.0))
          x (* side (:b s) 0.955)
          z 0.62]
      (torus! (str "Y-PortRim" side y) [x y z] [side 0.0 0.0] 0.13 0.022 16 6 (:steel mats) root)
      (superellipsoid! (str "Y-PortGlass" side y) [x y z] 0.02 0.11 0.11 0.8 0.8 14 8 (:glass mats) root))))

(defn add-hull-mask!
  "Add the yacht-footprint water exclusion to the ocean material, driven by
  YachtX/YachtY/YachtYaw value nodes (updated per frame by main.clj)."
  []
  (let [mat (b/lookup (bpy/data) "materials" "Ocean")
        nt (nodes/tree mat)
        out (nodes/find nt "Material Output")
        bsdf (nodes/find nt "Principled BSDF")
        geo (or (nodes/find nt "Geometry") (nodes/node! nt "ShaderNodeNewGeometry"))]
    (if (nodes/find nt "YachtX")
      mat
      (let [_ nil
        pos (nodes/socket geo :out "Position")
        vx (nodes/value! nt "YachtX" 24.0)
        vy (nodes/value! nt "YachtY" -16.0)
        vz (nodes/value! nt "YachtZ" 0.0)
        vyaw (nodes/value! nt "YachtYaw" 0.0)
        comb (nodes/node! nt "ShaderNodeCombineXYZ")
        _ (set-in nt vx comb "X")
        _ (set-in nt vy comb "Y")
        _ (set-in nt vz comb "Z")
        sub (nodes/node! nt "ShaderNodeVectorMath" {:props {"operation" "SUBTRACT"}})
        _ (set-in nt pos sub 0)
        _ (set-in nt (nodes/socket comb :out "Vector") sub 1)
        rot (nodes/node! nt "ShaderNodeVectorRotate"
                         {:props {"rotation_type" "Z_AXIS"}})
        _ (set-in nt (nodes/socket sub :out "Vector") rot "Vector")
        _ (set-in nt [0.0 0.0 0.0] rot "Center")
        _ (set-in nt vyaw rot "Angle")
        sep (nodes/node! nt "ShaderNodeSeparateXYZ")
        _ (set-in nt (nodes/socket rot :out "Vector") sep "Vector")
        sx (nodes/socket sep :out "X")
        sy (nodes/socket sep :out "Y")
        sq (fn [s]
             (let [n (nodes/node! nt "ShaderNodeMath" {:props {"operation" "MULTIPLY"}})]
               (set-in nt s n 0)
               (set-in nt s n 1)
               (nodes/socket n :out 0)))
        m (fn [op a b]
            (let [n (nodes/node! nt "ShaderNodeMath" {:props {"operation" op}})]
              (when a (set-in nt a n 0))
              (when b (set-in nt b n 1))
              (nodes/socket n :out 0)))
        ex (m "MULTIPLY" (sq (m "MULTIPLY" sx (/ 1.0 3.05))) 1.0)
        ey (m "MULTIPLY" (sq (m "MULTIPLY" sy (/ 1.0 11.6))) 1.0)
        e (m "ADD" ex ey)
        alpha (let [n (nodes/node! nt "ShaderNodeMapRange" {:props {"clamp" true}})]
                (set-in nt e n "Value")
                (set-in nt 0.74 n "From Min")
                (set-in nt 1.0 n "From Max")
                (set-in nt 0.0 n "To Min")
                (set-in nt 1.0 n "To Max")
                (nodes/socket n :out "Result"))]
    (set-in nt alpha bsdf "Alpha")
    mat))))

;; ---------------------------------------------------------------------------
;; cameras, HUD text, build
;; ---------------------------------------------------------------------------

(defn camera! [name loc lens]
  (let [cd (b/id-new "Camera" (str name "Data"))
        o (b/object-new name cd)]
    (b/set! cd "lens" lens)
    (b/set! cd "clip_start" 0.05)
    (b/set! cd "clip_end" 40000.0)
    (bpy/link! o)
    (b/set! o "location" loc)
    o))

(defn text-object! [name body loc size mat root align]
  (bpy/ops "object.text_add" {:location loc} {:area "VIEW_3D"})
  (let [vl (b/get (bpy/context) "view_layer")
        o (b/get (b/get vl "objects") "active")
        d (b/get o "data")]
    (b/set! o "name" name)
    (b/set! d "body" body)
    (b/set! d "size" size)
    (b/set! d "align_x" (or align "CENTER"))
    (b/set! d "align_y" "CENTER")
    (b/set! d "extrude" 0.0)
    (b/call (b/get d "materials") "append" {:material mat})
    (parent! o root)
    o))

(defn build!
  "Build (or rebuild) the yacht. Assumes build.clj's sea scene exists."
  []
  ;; Names first, then delete by fresh lookup: removing one datablock can
  ;; invalidate the other pointers in a realised collection.
  (let [names (vec (for [o (bpy/objects)
                         :let [n (b/get o "name")]
                         :when (str/starts-with? n "Y-")]
                     n))]
    (doseq [n names]
      (when-let [o (bpy/object n)] (bpy/delete! o))))
  (let [mats (materials!)
        root (build-hull! nil mats)]
    (build-deck! root mats)
    (build-stairs! root mats)
    (build-coachroof! root mats)
    (coach-windows! root mats)
    (build-windscreen! root mats)
    (build-hardtop! root mats)
    (build-arch! root mats)
    (build-bimini! root mats)
    (build-rails! root mats)
    (build-cleats! root mats)
    (build-anchor! root mats)
    (build-navlights! root mats)
    (build-flag! root mats)
    (build-swim-platform! root mats)
    (build-cockpit! root mats)
    (build-sunpad! root mats)
    (build-interior! root mats)
    (build-portholes! root mats)
    ;; game physics: the hull object is the rigid body
    (let [g (b/get root "game")]
      (b/set! g "physics_type" "RIGID_BODY")
      (b/set! g "mass" 42000.0)
      (b/set! g "damping" 0.55)
      (b/set! g "rotation_damping" 0.85)
      (b/set! g "collision_bounds_type" "BOX"))
    ;; cameras
    (camera! "Y-HelmCam" [0.45 -3.1 3.05] 42.0)
    (camera! "Y-ChaseCam" [0.0 -22.0 7.5] 38.0)
    (camera! "Y-WalkCam" [34.0 -20.0 3.4] 60.0)
    (camera! "Y-OrbitCam" [24.0 -24.0 8.0] 45.0)
    (camera! "Y-IntroCam" [12.0 24.0 7.0] 40.0)
    ;; HUD / help / centre message text (positioned by main.clj each frame)
    (text-object! "Y-HUD" "" [0 0 0] 0.075 (:whitel mats) nil "CENTER")
    (text-object! "Y-Help" "" [0 0 0] 0.050 (:whitel mats) nil "LEFT")
    (text-object! "Y-Center" "" [0 0 0] 0.060 (:whitel mats) nil "CENTER")
    ;; MFD text (fixed to the dash, facing aft)
    (let [m1 (text-object! "Y-MFDText1" "0.0 kn\n1200 RPM" [-0.20 -2.60 2.50] 0.072 (:whitel mats) root "CENTER")
          m2 (text-object! "Y-MFDText2" "HDG 000\nDEPTH 12 m" [0.72 -2.60 2.50] 0.072 (:whitel mats) root "CENTER")]
      (doseq [t [m1 m2]] (b/set! t "rotation_euler" [(- (/ PI 2.0) 0.30) 0.0 0.0])))
    (add-hull-mask!)
    root))
