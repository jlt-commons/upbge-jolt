;; Oil paint, as a UPBGE scene. A port of the night ridge from
;; https://github.com/mattdesl/oil-paint (three.js) to upbge-jolt: every
;; thing in the picture is a dab of a bristle brush pinned to the world.
;;
;;   (load-file "examples/oil-paint/build.clj")
;;   (oilpaint-build/build!)
;;   (bpy/save! "examples/oil-paint/oil-paint.blend")
;;
;; The painter is the scene: terrain, snow, rock, pines, a cabin, the fjord,
;; the ranges, the night sky and the aurora are all drawn as quads carrying
;; a brush-atlas UV, two mixed colours and a height, with a shared material
;; that rakes the paint under the moon.
(ns oilpaint-build
  (:require [upbge-jolt.bpy :as bpy]
            [upbge-jolt.bridge :as b]
            [upbge-jolt.nodes :as nodes]))

;; ============================================================================
;; small math
;; ============================================================================

(def ^:private TAU (* 2 Math/PI))
(defn- clamp [x a b] (max a (min b x)))
(defn- sstep [a b x]
  (let [t (clamp (/ (- x a) (- b a)) 0.0 1.0)]
    (* t t (- 3 (* 2 t)))))
(defn- smin [a b k]
  (let [h (clamp (+ 0.5 (/ (* 0.5 (- b a)) k)) 0.0 1.0)]
    (- (+ b (* (- a b) h)) (* k h (- 1 h)))))
(defn- v3 [x y z] [x y z])
(defn- v+ [[ax ay az] [bx by bz]] [(+ ax bx) (+ ay by) (+ az bz)])
(defn- v- [[ax ay az] [bx by bz]] [(- ax bx) (- ay by) (- az bz)])
(defn- v* [[x y z] s] [(* x s) (* y s) (* z s)])
(defn- vmul [a b] (mapv * a b))
(defn- dot3 [[ax ay az] [bx by bz]] (+ (* ax bx) (* ay by) (* az bz)))
(defn- cross3 [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by)) (- (* az bx) (* ax bz)) (- (* ax by) (* ay bx))])
(defn- length3 [[x y z]] (Math/sqrt (+ (* x x) (* y y) (* z z))))
(defn- norm3 [v] (let [l (length3 v)] (if (< l 1e-9) [0.0 1.0 0.0] (v* v (/ 1.0 l)))))

;; ============================================================================
;; noise, dice and the ridge (night/land.js)
;; ============================================================================

(def ^:private EXTENT 512.0)
(def ^:private LAND_RES 513)

(def ^:private perm
  (let [p (long-array 256)
        s (long-array [20261110])]
    (dotimes [i 256] (aset p i i))
    (loop [i 255]
      (when (> i 0)
        (aset s 0 (bit-and (unchecked-add (unchecked-multiply 1664525 (aget s 0)) 1013904223) 0xFFFFFFFF))
        (let [j (mod (aget s 0) (inc i))
              t (aget p i)]
          (aset p i (aget p j)) (aset p j t))
        (recur (dec i))))
    (let [q (long-array 512)]
      (dotimes [i 512] (aset q i (aget p (bit-and i 255))))
      q)))

(defn- fade [t] (* t t t (- (* t (- (* t 6) 15)) -10.0)))
(defn- grad2 [h x y]
  (+ (if (odd? (bit-and h 1)) (- x) x)
     (if (odd? (bit-and h 2)) (- y) y)))
(defn- noise [x y]
  (let [xf (Math/floor x) yf (Math/floor y)
        X (bit-and (long xf) 255) Y (bit-and (long yf) 255)
        fx (- x xf) fy (- y yf)
        u (fade fx) v (fade fy)
        a (+ (aget perm X) Y) b (+ (aget perm (inc X)) Y)
        l (fn [p q t] (+ p (* (- q p) t)))
        g (fn [i dx dy] (grad2 (aget perm (bit-and i 511)) dx dy))]
    (* 1.4
       (l (l (g a fx fy) (g b (- fx 1.0) fy) u)
          (l (g (inc a) fx (- fy 1.0)) (g (inc b) (- fx 1.0) (- fy 1.0)) u)
          v))))

(defn- make-rng [seed]
  (let [s (long-array [(long seed)])]
    (fn []
      (aset s 0 (bit-and (unchecked-add (unchecked-multiply 1664525 (aget s 0)) 1013904223) 0xFFFFFFFF))
      (/ (aget s 0) 4294967296.0))))

(def ^:private CABIN {:x -4.7 :y 226.6 :z -64.0})
(defn- crest-x [z] (+ (* 30 (Math/sin (/ z 140.0))) (* 12 (Math/sin (+ (/ z 47.0) 1.0)))))
(defn- crest-h [z] (+ 222.0 (* 28.0 (Math/exp (- (Math/pow (/ (- z 150.0) 80.0) 2))))))
(def ^:private START {:x (crest-x 300.0) :z 300.0})
(def ^:private MOON (norm3 [-0.78 0.3 0.44])) ; three's (-0.78, 0.44, 0.3), in Blender xyz

(def ^:private HORNS
  [{:x (+ (crest-x -175.0) 4.0) :z -175.0 :top 378.0 :fall 1.52 :sx 0.94 :sz -0.34 :sheer 3.0 :turn 0.2}
   {:x (- (crest-x -175.0) 96.0) :z -226.0 :top 318.0 :fall 1.5 :sx -0.9 :sz -0.44 :sheer 1.8 :turn 1.9}])

(defn- horn-h [x z {:keys [top fall sx sz sheer turn] :as o}]
  (let [ox (:x o) oz (:z o)
        dx0 (- x ox) dz0 (- z oz)
        t (+ (* dx0 sx) (* dz0 sz))
        dx (if (> t 0) (+ dx0 (* sx t (dec sheer))) dx0)
        dz (if (> t 0) (+ dz0 (* sz t (dec sheer))) dz0)
        r (Math/hypot dx dz)
        face (- 1.0 (Math/abs (Math/cos (* 1.5 (- (Math/atan2 dz dx) turn)))))
        rr (* r (+ 1.0 (* 0.5 face (sstep 0.0 30.0 r))
                  (* 0.26 (noise (+ (/ x 52.0) ox) (/ z 52.0)))
                  (* 0.09 (noise (/ x 19.0) (+ (/ z 19.0) oz)))))]
    (- top (* fall 100.0 (Math/pow (/ rr 100.0) 0.76)))))

(defn- raw-height
  "Height at (x, z) and the horn share, [h hw], as night/land.js."
  [x z]
  (let [d (- x (crest-x z)) ad (Math/abs d)
        steep (if (< d 0.0) 0.95 0.72)
        w (+ 15.0 (* 16.0 (Math/exp (- (Math/pow (/ (- z 150.0) 60.0) 2)))))
        h0 (- (crest-h z) (* steep (- (Math/sqrt (+ (* d d) (* w w))) w)))
        h1 (+ h0 (* 16.0 (noise (+ (/ x 190.0) 2.3) (- (/ z 190.0) 1.1)) (sstep 15.0 120.0 ad)))
        h2 (+ h1 (* 5.0 (noise (/ x 55.0) (+ (/ z 55.0) 7.0)) (sstep 8.0 60.0 ad)))
        gully (- 1.0 (Math/abs (noise (+ (/ z 44.0) (* 0.5 (noise (/ x 90.0) (/ z 90.0)))) (+ (/ x 420.0) 3.0))))
        h3 (- h2 (* 15.0 gully gully gully (sstep 30.0 150.0 ad)))
        h4 (+ h3 (+ (* 0.9 (noise (+ (/ x 17.0) 3.0) (/ z 17.0)) (sstep 3.0 25.0 ad))
                    (* 0.3 (noise (/ x 9.0) (+ (/ z 9.0) 2.0)))))
        horns (- (smin (- (horn-h x z (nth HORNS 0))) (- (horn-h x z (nth HORNS 1))) 10.0))
        hw (sstep -6.0 30.0 (- horns h4))
        crag (max 0.0 (- (noise (+ (/ x 27.0) 11.0) (- (/ z 27.0) 4.0)) 0.4))
        h5 (+ h4 (* 24.0 (Math/pow crag 1.15) (sstep 8.0 26.0 ad) (- 1.0 hw)))
        h6 (- (smin (- h5) (- horns) 14.0))
        beds (max hw (* 0.4 (sstep 60.0 150.0 ad)) (* (sstep 0.05 0.3 crag) (- 1.0 hw)))
        tilt (+ h6 (* 0.42 x) (* 0.2 z) (* 9.0 (noise (/ x 90.0) (/ z 90.0))))
        broken (sstep -0.25 0.3 (noise (+ (/ x 33.0) 5.0) (- (/ z 33.0) 2.0)))
        h7 (+ h6 (* beds (+ (* 1.6 broken (Math/sin (* tilt 0.571)))
                            (* 2.6 hw (Math/sin (+ (* tilt 0.17) 1.3))))))
        edge (- EXTENT (Math/pow (+ (* x x x x) (* z z z z)) 0.25))
        h8 (smin h7 (* 1.25 (- edge 18.0)) 22.0)
        shelf (Math/hypot (- x (:x CABIN)) (- z (:z CABIN)))
        h9 (if (< shelf 27.0)
             (+ h8 (* (- (:y CABIN) h8) (sstep 27.0 9.0 shelf)))
             h8)]
    [h9 hw]))

;; The height map: the picture, the feet and every painted thing read it.
(def ^:private LAND (atom nil))

(defn- prepare-land! []
  (let [n LAND_RES
        h (double-array (* n n))
        horn (double-array (* n n))
        rock (double-array (* n n))
        at (fn [i] (+ (- EXTENT) (* (/ (double i) (double (dec n))) 2.0 EXTENT)))]
    (println "oil-paint: laying the ground")
    (dotimes [j n]
      (when (zero? (mod j 64)) (println (format "  rows %d/%d" j n)))
      (let [z (at j)]
        (dotimes [i n]
          (let [x (at i)
                [hh hw] (raw-height x z)
                k (+ i (* j n))]
            (aset h k hh)
            (aset horn k hw)))))
    (println "oil-paint: rock and shade")
    (dotimes [j n]
      (let [z (at j)
            j0 (max 0 (dec j)) j1 (min (dec n) (inc j))]
        (dotimes [i n]
          (let [i0 (max 0 (dec i)) i1 (min (dec n) (inc i))
                k (+ i (* j n))
                hx (/ (- (aget h (+ i1 (* j n))) (aget h (+ i0 (* j n)))) (double (- i1 i0)))
                hz (/ (- (aget h (+ i (* j1 n))) (aget h (+ i (* j0 n)))) (double (- j1 j0)))
                ny (/ 1.0 (Math/sqrt (+ 1.0 (* hx hx) (* hz hz))))
                x (at i)
                limit (+ 0.64 (* 0.05 (aget horn k)))]
            (aset rock k (sstep (+ limit 0.05) (- limit 0.05)
                                (+ ny (* 0.07 (noise (/ x 9.0) (/ z 9.0)))
                                   (* 0.14 (max 0.0 (noise (+ (/ x 41.0) 8.0) (/ z 41.0)))))))))))
    (reset! LAND {:n n :h h :horn horn :rock rock})
    (println "oil-paint: ground ready")))

(defn- height-at [x z]
  (let [{:keys [^longs n] :as l} @LAND
        n (:n l)
        h ^doubles (:h l)
        fx (clamp (* (/ (+ x EXTENT) (* 2.0 EXTENT)) (dec n)) 0.0 (- n 1.001))
        fz (clamp (* (/ (+ z EXTENT) (* 2.0 EXTENT)) (dec n)) 0.0 (- n 1.001))
        i (long (Math/floor fx)) j (long (Math/floor fz))
        tx (- fx i) tz (- fz j)
        a (aget h (+ i (* j n))) b (aget h (+ (inc i) (* j n)))
        c (aget h (+ i (* (inc j) n))) d (aget h (+ (inc i) (* (inc j) n)))]
    (+ (* (+ a (* (- b a) tx)) (- 1.0 tz)) (* (+ c (* (- d c) tx)) tz))))

(defn- land-at [x z]
  (let [l @LAND n (:n l)
        i (long (clamp (Math/round (* (/ (+ x EXTENT) (* 2.0 EXTENT)) (dec n))) 0 (dec n)))
        j (long (clamp (Math/round (* (/ (+ z EXTENT) (* 2.0 EXTENT)) (dec n))) 0 (dec n)))
        k (+ i (* j n))]
    [(aget ^doubles (:rock l) k) (aget ^doubles (:horn l) k)]))

(defn- normal-at [x z]
  (let [e 1.2]
    (norm3 [(- (height-at (- x e) z) (height-at (+ x e) z))
            (- (height-at x (- z e)) (height-at x (+ z e)))
            (* 2.0 e)])))

;; ============================================================================
;; the moon's reach: a march over the height field (night/land.js + shade)
;; ============================================================================

(defn- moon-lit
  "How lit a spot is: 0 in the horns' shadow, 1 in the open."
  [p n]
  (let [q (v+ (v+ p (v* n 0.6)) [0.0 0.0 0.3])]
    (loop [t 1.5]
      (if (> t 150.0)
        1.0
        (let [x (+ (nth q 0) (* (nth MOON 0) t))
              y (+ (nth q 2) (* (nth MOON 2) t))
              z (+ (nth q 1) (* (nth MOON 1) t))]
          (if (> (height-at x z) y)
            0.0
            (recur (+ t 2.4))))))))

(defn- moon-on [p n]
  (let [m (moon-lit p n)
        face (clamp (+ (* (dot3 n MOON) 1.7) 0.15) 0.0 1.0)]
    (* m face)))

;; ============================================================================
;; paint: three mixes, broken colour, snow and rock (night/paint.js)
;; ============================================================================

(defn- paint3 [cl cm cs x r]
  (let [x (+ x (* (- r 0.5) 0.2))
        a (sstep 0.24 0.36 x)
        b (sstep 0.56 0.7 x)
        c (v+ cs (v* (v- cm cs) a))]
    (v+ c (v* (v- cl c) b))))

(defn- jitter [c r amount]
  (let [[rx ry rz] r
        a (* (- ry 0.5) 0.9 amount)
        k [0.57735 0.57735 0.57735]
        ca (Math/cos a) sa (Math/sin a)
        c1 (v+ (v+ (v* c ca) (v* (cross3 k c) sa)) (v* k (* (dot3 k c) (- 1.0 ca))))
        g (dot3 c1 [0.3 0.59 0.11])
        c2 (v+ [g g g] (v* (v- c1 [g g g]) (+ 1.0 (* (- rz 0.5) 0.7 amount))))
        f (+ 1.0 (* (- rx 0.5) 0.5 amount))]
    (mapv (fn [x] (max 0.0 (* x f))) c2)))

(defn- mix3 [a b t] (v+ a (v* (v- b a) t)))

(defn- snow-paint [x r a kind]
  (let [x (clamp (+ x 0.1) 0.0 1.0)
        L (mix3 [0.42 0.6 0.74] [0.3 0.68 0.64] a)
        M (mix3 [0.16 0.29 0.46] [0.1 0.36 0.42] a)
        S (mix3 [0.05 0.1 0.25] [0.035 0.13 0.23] a)
        crust (sstep 0.52 0.66 kind) ice (sstep 0.8 0.9 kind)
        L (mix3 (vmul L [0.84 0.9 0.9]) L crust)
        M (mix3 (vmul M [0.9 0.96 0.92]) M crust)
        L (mix3 [0.26 0.58 0.72] L ice)
        M (mix3 [0.07 0.28 0.44] M ice)]
    (paint3 L M S x r)))

(defn- rock-paint [x r kind]
  (let [t (sstep 0.45 0.7 kind)
        L (mix3 [0.12 0.2 0.28] [0.16 0.17 0.27] t)
        M (mix3 [0.04 0.07 0.12] [0.06 0.06 0.115] t)]
    (paint3 L M [0.012 0.024 0.055] x r)))

(defn- aurora-on [x z n]
  (let [patch (noise (+ (/ x 900.0) 0.0) (/ z 900.0))
        a (sstep 0.2 0.75 (+ 0.5 (* 0.5 patch)))]
    (clamp (* 1.0 a (max (nth n 2) 0.0)) 0.0 0.85)))

;; the cabin's lit openings, as warm wedges on the snow
(def ^:private WARM
  ;; [x, north(z), up(y), strength] in Blender coordinates
  [[(+ (:x CABIN) -1.15) (+ (:z CABIN) 2.75) (:y CABIN) 0.75]
   [(+ (:x CABIN) 1.75) (+ (:z CABIN) 2.75) (+ (:y CABIN) 1.1) 1.1]
   [(+ (:x CABIN) -0.3) (- (:z CABIN) 2.75) (+ (:y CABIN) 1.3) -0.7]])

(defn- warm-at [p n]
  (reduce (fn [s [wx wy wz w]]
            (let [to (v- [wx wy wz] p)
                  front (* (if (neg? w) -1.0 1.0) (- (nth p 1) wz))
                  d2 (dot3 to to)
                  wedge (* (sstep 0.0 0.8 front)
                           (sstep 0.35 1.3 (/ front (+ (Math/abs (nth to 0)) 0.4))))
                  face (clamp (+ (* (dot3 n to) (/ 1.0 (Math/sqrt (+ d2 1e-4))) 0.6) 0.5) 0.0 1.0)]
              (+ s (* (Math/abs w) wedge face (/ 1.0 (+ 1.0 (* d2 0.3)))))))
          0.0 WARM))

(defn- lamp-on [c own p n]
  (let [k (clamp (* (warm-at p n) 1.55) 0.0 1.0)
        whole (Math/pow k 1.1)
        warm (vmul own [1.0 0.72 0.38])]
    (v+ (mix3 c warm whole) (v* warm (* 0.4 k (- 1.0 whole))))))

;; ============================================================================
;; the brush: one sheet of eight marks, loaded from the PNG tool/gen-brush.mjs
;; ============================================================================

(defn- brush-image []
  (let [path "/Users/yogthos/src/jlt-commons/upbge-jolt/examples/oil-paint/brush.png"
        img (or (b/lookup (b/data) "images" "brush.png")
                (b/call (b/get (b/data) "images") "load" {:filepath path}))]
    (b/set! (b/get img "colorspace_settings") "name" "Non-Color")
    (b/call img "pack")
    img))

;; ============================================================================
;; dab quads
;; ============================================================================

(def ^:private DAB-STRIDE 20)
(defn- dab-buf [] {:d (atom (double-array (* 4096 DAB-STRIDE)))
                   :n (long-array [0])
                   :cap (long-array [4096])})
(defn- dab-add!
  "One dab: centre, across vector, along vector, two colours, brush cell,
  and the two flips of the mark. Grows and mutates `buf` in place."
  [buf p a l c1 c2 cell flipx flipy]
  (let [n (aget ^longs (:n buf) 0)
        cap (aget ^longs (:cap buf) 0)]
    (when (>= n cap)
      (let [cap2 (* 2 cap)
            old ^doubles @(:d buf)
            d2 (double-array (* cap2 DAB-STRIDE))]
        (System/arraycopy old 0 d2 0 (* n DAB-STRIDE))
        (reset! (:d buf) d2)
        (aset ^longs (:cap buf) 0 cap2)))
    (let [d ^doubles @(:d buf)
          o (* n DAB-STRIDE)]
      (aset d o (double (nth p 0))) (aset d (+ o 1) (double (nth p 1))) (aset d (+ o 2) (double (nth p 2)))
      (aset d (+ o 3) (double (nth a 0))) (aset d (+ o 4) (double (nth a 1))) (aset d (+ o 5) (double (nth a 2)))
      (aset d (+ o 6) (double (nth l 0))) (aset d (+ o 7) (double (nth l 1))) (aset d (+ o 8) (double (nth l 2)))
      (aset d (+ o 9) (double (nth c1 0))) (aset d (+ o 10) (double (nth c1 1))) (aset d (+ o 11) (double (nth c1 2)))
      (aset d (+ o 12) (double (nth c2 0))) (aset d (+ o 13) (double (nth c2 1))) (aset d (+ o 14) (double (nth c2 2)))
      (aset d (+ o 15) (double cell))
      (aset d (+ o 16) (double flipx))
      (aset d (+ o 17) (double flipy))
      (aset d (+ o 18) 0.0)
      (aset d (+ o 19) 0.0)
      (aset ^longs (:n buf) 0 (inc n)))))

(defn- surface-basis
  "Across and along vectors for a stroke lying on a surface with normal n,
  running along dir."
  [dir n]
  (let [n (norm3 n)
        dp (dot3 dir n)
        l (norm3 (v- dir (v* n dp)))]
    [(norm3 (cross3 n l)) l]))

(defn- build-dab-object!
  "Turn a dab buffer into a mesh object. Returns the object."
  [name buf]
  (let [d ^doubles @(:d buf)
        n (aget ^longs (:n buf) 0)]
  (println (format "oil-paint: %s, %d dabs" name n))
  (let [nv (* n 4)
        verts (double-array (* nv 3))
        uvs (double-array (* nv 2))
        uvlay (double-array (* nv 2))
        col1 (double-array (* nv 4))
        col2 (double-array (* nv 4))
        faces (object-array n)]
    (dotimes [i n]
      (let [o (* i DAB-STRIDE)
            px (aget d o) py (aget d (+ o 1)) pz (aget d (+ o 2))
            ax (aget d (+ o 3)) ay (aget d (+ o 4)) az (aget d (+ o 5))
            lx (aget d (+ o 6)) ly (aget d (+ o 7)) lz (aget d (+ o 8))
            c1r (aget d (+ o 9)) c1g (aget d (+ o 10)) c1b (aget d (+ o 11))
            c2r (aget d (+ o 12)) c2g (aget d (+ o 13)) c2b (aget d (+ o 14))
            cell (long (aget d (+ o 15)))
            fx (aget d (+ o 16)) fy (aget d (+ o 17))
            cellx (mod cell 4) celly (quot cell 4)]
        (dotimes [v 4]
          (let [sx (if (or (= v 1) (= v 2)) 1.0 -1.0)
                sy (if (>= v 2) 1.0 -1.0)
                vi (+ (* i 4) v)
                vx (+ px (* ax sx) (* lx sy))
                vy (+ py (* ay sx) (* ly sy))
                vz (+ pz (* az sx) (* lz sy))
                u (* fx sx) uu (+ 0.5 (* 0.5 u))
                vv (+ 0.5 (* 0.5 (* fy sy)))
                tx (/ (+ cellx uu) 4.0)
                ty (/ (+ celly vv) 2.0)]
            (aset verts (+ (* vi 3) 0) (double vx))
            (aset verts (+ (* vi 3) 1) (double vy))
            (aset verts (+ (* vi 3) 2) (double vz))
            (aset uvs (+ (* vi 2) 0) (double tx))
            (aset uvs (+ (* vi 2) 1) (double ty))
            (aset col1 (+ (* vi 4) 0) (double c1r)) (aset col1 (+ (* vi 4) 1) (double c1g))
            (aset col1 (+ (* vi 4) 2) (double c1b)) (aset col1 (+ (* vi 4) 3) 1.0)
            (aset col2 (+ (* vi 4) 0) (double c2r)) (aset col2 (+ (* vi 4) 1) (double c2g))
            (aset col2 (+ (* vi 4) 2) (double c2b)) (aset col2 (+ (* vi 4) 3) 1.0)))
        (aset faces i [(+ (* i 4) 0) (+ (* i 4) 1) (+ (* i 4) 2) (+ (* i 4) 3)])))
    ;; from_pydata wants seqs; chunk it so we do not hold one huge seq
    (let [obj (bpy/add! name
                        (mapv (fn [i] [(aget verts (* i 3)) (aget verts (+ (* i 3) 1)) (aget verts (+ (* i 3) 2))])
                              (range nv))
                        (vec faces))
          mesh (b/get obj "data")]
      (let [uvl (b/call (b/get mesh "uv_layers") "new" {:name "UVMap"})]
        (b/foreach-set! uvl "data" "uv" uvs))
      (b/foreach-set! (b/call (b/get mesh "color_attributes") "new" {:name "Col1" :type "FLOAT_COLOR" :domain "POINT"})
                      "data" "color" col1)
      (b/foreach-set! (b/call (b/get mesh "color_attributes") "new" {:name "Col2" :type "FLOAT_COLOR" :domain "POINT"})
                      "data" "color" col2)
      obj))))

;; ============================================================================
;; materials
;; ============================================================================

(defn- dab-material []
  (let [mat (nodes/material! "OilPaintDab")
        nt (nodes/tree mat)
        img (brush-image)
        tex (nodes/node! nt "ShaderNodeTexImage" {:props {"image" img "extension" "REPEAT" "interpolation" "Linear"}})
        sep (nodes/node! nt "ShaderNodeSeparateColor")
        a1 (nodes/node! nt "ShaderNodeAttribute" {:props {"attribute_type" "GEOMETRY" "attribute_name" "Col1"}})
        a2 (nodes/node! nt "ShaderNodeAttribute" {:props {"attribute_type" "GEOMETRY" "attribute_name" "Col2"}})
        ramp (nodes/node! nt "ShaderNodeMapRange" {:props {"interpolation_type" "SMOOTHSTEP"}
                                                   :inputs {"From Min" 0.52 "From Max" 0.78 "To Min" 0.0 "To Max" 1.0}})
        mix (nodes/node! nt "ShaderNodeMixRGB" {:props {"blend_type" "MIX"}})
        shade (nodes/node! nt "ShaderNodeMapRange" {:props {"interpolation_type" "LINEAR"}
                                                     :inputs {"From Min" 0.0 "From Max" 1.0 "To Min" 0.93 "To Max" 1.05}})
        tint (nodes/node! nt "ShaderNodeMixRGB" {:props {"blend_type" "MULTIPLY"}})
        bump (nodes/node! nt "ShaderNodeBump" {:inputs {"Strength" 0.35 "Distance" 0.012}})
        bsdf (nodes/find nt "Principled BSDF")]
    (nodes/link! nt tex "Color" sep "Color")
    (nodes/link! nt sep "Blue" ramp "Value")
    (nodes/link! nt ramp "Result" mix "Fac")
    (nodes/link! nt a1 "Color" mix "Color1")
    (nodes/link! nt a2 "Color" mix "Color2")
    (nodes/link! nt sep "Red" shade "Value")
    (nodes/link! nt mix "Color" tint "Color1")
    (nodes/link! nt shade "Result" tint "Color2")
    (nodes/link! nt sep "Red" bump "Height")
    (nodes/link! nt tint "Color" bsdf "Base Color")
    (nodes/link! nt tint "Color" bsdf "Emission Color")
    (nodes/set-input! bsdf "Emission Strength" 0.5)
    (nodes/set-input! bsdf "Roughness" 0.86)
    (nodes/set-input! bsdf "Specular IOR Level" 0.25)
    (nodes/link! nt sep "Green" bsdf "Alpha")
    (nodes/link! nt bump "Normal" bsdf "Normal")
    (b/set! mat "blend_method" "HASHED")
    (b/set! mat "use_transparent_shadow" false)
    mat))

(defn- glow-material []
  (let [mat (nodes/material! "OilPaintGlow")
        nt (nodes/tree mat)
        img (brush-image)
        tex (nodes/node! nt "ShaderNodeTexImage" {:props {"image" img "extension" "REPEAT" "interpolation" "Linear"}})
        sep (nodes/node! nt "ShaderNodeSeparateColor")
        a1 (nodes/node! nt "ShaderNodeAttribute" {:props {"attribute_type" "GEOMETRY" "attribute_name" "Col1"}})
        a2 (nodes/node! nt "ShaderNodeAttribute" {:props {"attribute_type" "GEOMETRY" "attribute_name" "Col2"}})
        ramp (nodes/node! nt "ShaderNodeMapRange" {:props {"interpolation_type" "SMOOTHSTEP"}
                                                   :inputs {"From Min" 0.52 "From Max" 0.78 "To Min" 0.0 "To Max" 1.0}})
        mix (nodes/node! nt "ShaderNodeMixRGB" {:props {"blend_type" "MIX"}})
        dim (nodes/node! nt "ShaderNodeMixRGB" {:props {"blend_type" "MIX"}
                                                :inputs {"Color1" [0.0 0.0 0.0 1.0]}})
        emit (nodes/node! nt "ShaderNodeEmission" {:inputs {"Strength" 0.55}})
        params (nodes/object-params! nt)
        gain (nodes/math! nt "MULTIPLY" nil 0.55)
        clear (nodes/node! nt "ShaderNodeBsdfTransparent" {})
        add (nodes/node! nt "ShaderNodeAddShader" {})
        bsdf (nodes/find nt "Principled BSDF")
        out (nodes/find nt "Material Output")]
    (when bsdf (b/call (b/get nt "nodes") "remove" {:node bsdf}))
    (nodes/link! nt tex "Color" sep "Color")
    (nodes/link! nt sep "Blue" ramp "Value")
    (nodes/link! nt ramp "Result" mix "Fac")
    (nodes/link! nt a1 "Color" mix "Color1")
    (nodes/link! nt a2 "Color" mix "Color2")
    ;; the brush's alpha masks what is added, so overlapping strokes add up
    (nodes/link! nt sep "Green" dim "Fac")
    (nodes/link! nt mix "Color" dim "Color2")
    (nodes/link! nt dim "Color" emit "Color")
    (b/call (b/get nt "links") "new" {:output (:r params) :input (nodes/socket gain :in 0)})
    (nodes/link! nt gain 0 emit "Strength")
    (nodes/link! nt clear "BSDF" add 0)
    (nodes/link! nt emit "Emission" add 1)
    (nodes/link! nt add "Shader" out "Surface")
    (b/set! mat "blend_method" "BLEND")
    (b/set! mat "surface_render_method" "BLENDED")
    (b/set! mat "use_transparent_shadow" false)
    (b/set! mat "show_transparent_back" true)
    mat))

(defn- flat-material
  "A simple vertex-coloured emission material for the underpaintings."
  [name emission?]
  (let [mat (nodes/material! name)
        nt (nodes/tree mat)
        attr (nodes/node! nt "ShaderNodeAttribute" {:props {"attribute_type" "GEOMETRY" "attribute_name" "Col"}})
        bsdf (nodes/find nt "Principled BSDF")]
    (nodes/link! nt attr "Color" bsdf "Base Color")
    (nodes/link! nt attr "Color" bsdf "Emission Color")
    (nodes/set-input! bsdf "Emission Strength" (if emission? 1.0 0.12))
    (nodes/set-input! bsdf "Roughness" 0.95)
    (nodes/set-input! bsdf "Specular IOR Level" 0.15)
    mat))

;; ============================================================================
;; terrain
;; ============================================================================

(defn- terrain-object []
  (let [res 240
        ex (* 2.0 EXTENT)
        cell (/ ex res)
        nv (* (inc res) (inc res))
        verts (double-array (* nv 3))
        cols (double-array (* nv 4))
        faces (object-array (* res res))]
    (println "oil-paint: painting the ground")
    (dotimes [j (inc res)]
      (let [z (- (* -1.0 EXTENT) 0.0) ; placeholder
            zz (+ (- EXTENT) (* j cell))]
        (dotimes [i (inc res)]
          (let [x (+ (- EXTENT) (* i cell))
                h (- (height-at x zz) 0.06)
                k (+ i (* j (inc res)))
                n (normal-at x zz)
                lit (moon-on [x h zz] n)
                [rock horn] (land-at x zz)
                r (noise (/ x 11.0) (/ zz 11.0))
                c (if (> (+ rock (* (- r 0.5) 0.25)) 0.5)
                    (v* (rock-paint lit r horn) 0.42)
                    (v* (snow-paint lit r (* 0.4 (aurora-on x zz n)) r) 0.78))]
            (aset verts (+ (* k 3) 0) (double x))
            (aset verts (+ (* k 3) 1) (double zz))
            (aset verts (+ (* k 3) 2) (double h))
            (aset cols (+ (* k 4) 0) (double (nth c 0)))
            (aset cols (+ (* k 4) 1) (double (nth c 1)))
            (aset cols (+ (* k 4) 2) (double (nth c 2)))
            (aset cols (+ (* k 4) 3) 1.0)))))
    (dotimes [j res]
      (dotimes [i res]
        (let [a (+ i (* j (inc res)))
              b (+ a 1)
              c (+ a (inc res))
              d (+ c 1)]
          (aset faces (+ i (* j res)) [a b d c]))))
    (let [obj (bpy/add! "Ground"
                        (mapv (fn [i] [(aget verts (* i 3)) (aget verts (+ (* i 3) 1)) (aget verts (+ (* i 3) 2))])
                              (range nv))
                        (vec faces))
          mesh (b/get obj "data")]
      (b/foreach-set! (b/call (b/get mesh "color_attributes") "new" {:name "Col" :type "FLOAT_COLOR" :domain "POINT"})
                      "data" "color" cols)
      (nodes/assign! obj (flat-material "OilPaintGround" false))
      (b/set! obj "visible_shadow" false)
      (let [g (b/get obj "game")]
        (b/set! g "physics_type" "STATIC")
        (b/set! g "use_collision_bounds" true)
        (b/set! g "collision_bounds_type" "TRIANGLE_MESH"))
      obj)))

;; ============================================================================
;; dab helpers
;; ============================================================================

(defn- add-surface-dab!
  "A dab lying on / facing along normal n, running in direction dir. sa and
  sl are full width and length in metres."
  [buf p n dir sa sl height c1 c2 cell rnd]
  (let [[across along] (surface-basis dir n)
        nn (norm3 n)
        centre (v+ p (v* nn height))
        fx (if (< (rnd) 0.5) -1.0 1.0)
        fy (if (< (rnd) 0.5) -1.0 1.0)]
    (dab-add! buf centre (v* across (* 0.5 sa)) (v* along (* 0.5 sl)) c1 c2 cell fx fy)))

(defn- dab-object! [name buf mat]
  (let [obj (build-dab-object! name buf)]
    (nodes/assign! obj mat)
    (b/set! obj "visible_shadow" false)
    obj))

;; ============================================================================
;; the snow underfoot and the rock (night/snow.js)
;; ============================================================================

(defn- in-room? [x z]
  (and (< (Math/abs (- x (:x CABIN))) 3.2)
       (< (Math/abs (- z (:z CABIN))) 2.8)))

(def PROG (atom nil))

(defn- snow-dabs! [buf rnd]
  (let [add (fn [x z sa sl]
              (let [h (height-at x z)]
                (when (and (> h 1.5) (not (in-room? x z)))
                  (let [p [x z h]
                        n (normal-at x z)
                        [rock horn] (land-at x z)
                        lit (clamp (+ (moon-on p n)
                                      (* 0.3 (- (noise (/ x 13.0) (/ z 13.0)) 0.5))) 0.0 1.0)
                        a (aurora-on x z n)
                        r1 (rnd) r2 (rnd) r3 (rnd) r4 (rnd) r5 (rnd)
                        kind (+ (noise (/ x 23.0) (/ z 23.0)) (* (- r3 0.5) 0.25))
                        stone? (> (+ rock (* (- r5 0.5) 0.35)) 0.5)
                        contour (norm3 (let [c (cross3 n [0.0 0.0 1.0])]
                                         (if (< (length3 c) 1e-4) [1.0 0.0 0.0] c)))
                        down (norm3 (cross3 contour n))
                        wa (+ (* (- (noise (/ x 11.0) (/ z 11.0)) 0.5) 2.2) (* (- r2 0.5) 0.9))
                        winddir [(Math/cos wa) (Math/sin wa) 0.0]]
                    (if stone?
                      (let [c1 (rock-paint lit (* 0.5 (+ r1 r3)) kind)
                            c2 (v* (rock-paint (+ lit 0.1) (+ r2 r4) (+ kind 0.2)) 1.3)
                            dir (norm3 (v+ (mix3 down contour (if (> r3 0.7) 1.0 0.0))
                                           (v* (v- [r1 r2 r3] [0.5 0.5 0.5]) 0.5)))
                            own [0.45 0.42 0.42]]
                        (add-surface-dab! buf p n dir (* sa 1.1) (* sl 0.9) 0.04
                                          (lamp-on (jitter c1 [r4 r3 r2] 0.3) own p n)
                                          (lamp-on (jitter c2 [r3 r2 r4] 0.3) (v* own 1.12) p n)
                                          (long (* 8 r5)) rnd))
                      (let [c1 (snow-paint lit r1 a kind)
                            c2 (snow-paint (+ lit 0.14) r2 a (+ kind 0.12))
                            c2 (if (and (> r4 0.955) (> lit 0.6)) [0.72 0.9 0.9] c2)
                            dir (norm3 (mix3 winddir contour (sstep 0.97 0.8 (nth n 2))))
                            own [0.95 0.95 0.95]]
                        (add-surface-dab! buf p n dir sa sl 0.05
                                          (lamp-on (jitter c1 [r4 r2 r3] 0.3) own p n)
                                          (lamp-on (jitter c2 [r3 r4 r2] 0.3) (v* own 1.12) p n)
                                          (long (* 8 r5)) rnd)))))))]
    ;; the crest underfoot: a stroke or two for every square metre
    (println "oil-paint: snow near")
    (doseq [z (range -230.0 370.0 0.7)
            dx (range -28.0 28.0 0.7)]
      (let [zz (+ z (* (- (rnd) 0.5) 0.9))
            x (+ (crest-x zz) dx (* (- (rnd) 0.5) 0.9))]
        (reset! PROG [:near x zz])
        (add x zz (+ 1.0 (* 0.03 (Math/abs dx))) (+ 2.3 (* 0.07 (Math/abs dx))))))
    (println "oil-paint: snow flanks")
    ;; the flanks, coarser
    (doseq [z (range -240.0 370.0 2.6)
            dx (range -110.0 110.0 3.2)]
      (let [zz (+ z (* (- (rnd) 0.5) 2.5))
            x (+ (crest-x zz) dx (* (- (rnd) 0.5) 2.5))]
        (reset! PROG [:flank x zz])
        (when (> (Math/abs dx) 26.0) (add x zz 2.4 5.6))))
    (println "oil-paint: snow horns")
    ;; the horns get bold cuts of the knife, in rock and caught snow
    (doseq [o HORNS
            z (range (- (:z o) 120.0) (+ (:z o) 80.0) 3.6)
            x (range (- (:x o) 115.0) (+ (:x o) 115.0) 3.6)]
      (let [h (height-at x z)
            [rock horn] (land-at x z)]
        (when (and (> h 20.0) (> (+ rock horn) 0.35))
          (let [p [x z h] n (normal-at x z)
                lit (moon-on p n)
                contour (norm3 (let [c (cross3 n [0.0 0.0 1.0])]
                                 (if (< (length3 c) 1e-4) [1.0 0.0 0.0] c)))
                down (norm3 (cross3 contour n))
                dir (norm3 (v+ (if (> (rnd) 0.8) contour down)
                               (v* (v- [(rnd) (rnd) (rnd)] [0.5 0.5 0.5]) 0.45)))
                c1 (rock-paint lit (rnd) (noise (/ x 41.0) (/ z 41.0)))
                c2 (v* (rock-paint (+ lit 0.1) (rnd) (+ (noise (/ x 41.0) (/ z 41.0)) 0.2)) 1.25)]
            (reset! PROG [:horn x z])
            (add-surface-dab! buf p n dir 2.2 6.2 0.12
                              (jitter c1 [(rnd) (rnd) (rnd)] 0.32)
                              (jitter c2 [(rnd) (rnd) (rnd)] 0.32)
                              (long (* 8 (rnd))) rnd))))))
    (println "oil-paint: snow far")
    ;; the rest of the ridge, loosely
    (dotimes [_ 12000]
      (let [x (* (- (rnd) 0.5) 2.0 EXTENT)
            z (* (- (rnd) 0.5) 2.0 EXTENT)
            h (height-at x z)]
        (when (> h 1.5)
          (let [p [x z h] n (normal-at x z)
                [rock horn] (land-at x z)
                lit (moon-on p n)
                stone? (> (+ rock (* (- (rnd) 0.5) 0.3)) 0.5)
                c1 (if stone? (rock-paint lit (rnd) (+ 0.4 (rnd))) (snow-paint lit (rnd) (aurora-on x z n) (rnd)))
                c2 (v* c1 1.25)]
            (reset! PROG [:far x z])
            (add-surface-dab! buf p n [(- (rnd) 0.5) (- (rnd) 0.5) 0.0] 3.2 8.0 0.2
                              c1 c2 (long (* 8 (rnd))) rnd))))))

(defn- lamp-pool!
  "The warm light a lantern lays on the snow: a circle of warm strokes that
  fade out toward its edge, painted so it reads even without the point light."
  [buf rnd cx cz radius warm]
  (let [step 0.55]
    (doseq [dz (range (- radius) radius step)
            dx (range (- radius) radius step)]
      (let [x (+ cx dx (* (- (rnd) 0.5) step))
            z (+ cz dz (* (- (rnd) 0.5) step))
            r (Math/hypot (- x cx) (- z cz))
            h (height-at x z)]
        (when (and (> h 1.0) (< r radius))
          (let [k (Math/pow (- 1.0 (/ r radius)) 1.5)
                p [x z (+ h 0.05)]
                n (normal-at x z)
                c (mix3 (snow-paint 0.5 (rnd) 0.0 0.5) [1.0 0.58 0.24] (* warm k k))
                c2 (v* (jitter c [(rnd) (rnd) (rnd)] 0.25) 1.22)]
            (add-surface-dab! buf p n [0.0 0.0 1.0] (+ 0.7 (* 0.5 (rnd))) (+ 1.4 (* 0.8 (rnd))) 0.03
                              c c2 (long (* 8 (rnd))) rnd)))))))

;; ============================================================================
;; spruces under snow (night/pines.js)
;; ============================================================================

(declare solid-tri! solid-quad! solid-box!)

(defn- make-pines! [solid buf rnd]
  (let [trees (atom [])
        near? (fn [x z gap] (every? (fn [t] (> (Math/hypot (- (:x t) x) (- (:z t) z)) gap)) @trees))
        stand! (fn [x z h full]
                 (when (near? x z (+ 1.6 (* h 0.16)))
                   (swap! trees conj {:x x :z z :y (height-at x z) :h h :r (+ (* h (+ 0.13 (* 0.07 (rnd)))) 0.25) :full full})))
        nvec [0.0 0.0 1.0]]
    ;; the wood he starts in: drifts, with an aisle along the crest
    (loop [z 172.0]
      (when (< z 350.0)
        (loop [off -96.0]
          (when (< off 96.0)
            (let [x (+ (crest-x z) off (* (- (rnd) 0.5) 3.0))
                  zz (+ z (* (- (rnd) 0.5) 3.0))
                  d (Math/abs (- x (crest-x zz)))
                  drift (+ 0.5 (* 0.5 (noise (+ (/ x 23.0) 4.0) (- (/ zz 23.0) 9.0))))
                  thick (* (sstep 176.0 250.0 zz) (+ 0.2 (* 0.8 drift)) (- 1.0 (* 0.5 (sstep 40.0 96.0 d))))]
              (when (and (<= (rnd) thick) (>= d 2.7))
                (let [y (height-at x zz) n (normal-at x zz)]
                  (when (and (> y 12.0) (<= (first (land-at x zz)) 0.4) (>= (nth n 2) 0.66))
                    (stand! x zz (* (+ 3.0 (* 8.5 (Math/pow (rnd) 1.4)))
                                    (+ 0.55 (* 0.45 (sstep 180.0 240.0 zz)))
                                    (+ 0.7 (* 0.3 (sstep 2.7 7.0 d))))
                            true))))
              (recur (+ off 3.6)))))
        (recur (+ z 3.6))))
    ;; a few alone on the knoll
    (doseq [[off z h] [[-11.0 122.0 3.3] [8.5 104.0 2.7] [-6.0 166.0 4.6] [15.0 171.0 6.2]
                       [-12.0 134.0 9.0] [13.0 132.0 8.0] [-10.0 118.0 6.0] [18.0 121.0 7.0]]]
      (stand! (+ (crest-x z) off) z h true))
    ;; clumps down the flanks
    (dotimes [_ 9000]
      (let [x (* (- (rnd) 0.5) 880.0)
            z (+ -330.0 (* (rnd) 780.0))
            y (height-at x z)]
        (when (and (> y 10.0) (< y (+ 150.0 (* 70.0 (sstep 100.0 250.0 z))))
                   (not (and (< (Math/abs (- x (crest-x z))) 100.0) (> z 165.0)))
                   (>= (noise (+ (/ x 37.0) 1.0) (+ (/ z 37.0) 6.0)) 0.12)
                   (<= (first (land-at x z)) 0.4)
                   (>= (nth (normal-at x z) 2) 0.7))
          (stand! x z (+ 3.0 (* 6.0 (rnd))) false))))
    (let [trees (vec (sort-by :z @trees))]
      (println (format "oil-paint: %d spruces" (count trees)))
      (doseq [t trees]
        ;; the dark cone, so the far side is not seen through it
        (let [sides 7 r (* (:r t) 0.72) tip [(:x t) (:z t) (+ (:y t) (* (:h t) 0.98))]]
          (dotimes [s sides]
            (let [a0 (/ (* s TAU) sides) a1 (/ (* (inc s) TAU) sides)
                  b1 [(+ (:x t) (* (Math/cos a1) r)) (:z t) (:y t)]
                  b0 [(+ (:x t) (* (Math/cos a0) r)) (:z t) (:y t)]]
              (solid-tri! solid tip b1 b0 [0.004 0.012 0.022]))))
        ;; boughs: a dark stroke of needles and the snow lying on it
        (let [tiers (if (:full t) (long (Math/round (+ 5.0 (* (:h t) 0.95)))) 4)
              lean [(* (- (rnd) 0.5) 0.07) (* (- (rnd) 0.5) 0.07)]
              curl [(* (- (rnd) 0.5) 0.2) (* (- (rnd) 0.5) 0.2)]]
          (dotimes [i tiers]
            (let [up (/ (+ i 0.4) tiers)
                  y (+ (:y t) (* (:h t) (+ 0.12 (* 0.86 up))))
                  rad (+ (* (:r t) (Math/pow (- 1.0 up) 0.8)) 0.1)
                  cx (+ (:x t) (* (:h t) (+ (* (first lean) up) (* (first curl) up up up))))
                  cz (+ (:z t) (* (:h t) (+ (* (second lean) up) (* (second curl) up up up))))
                  boughs (if (:full t) (max 3 (long (Math/round (+ 3.0 (* rad 4.2))))) 3)
                  a0 (* (rnd) TAU)
                  droop (+ 0.35 (* 0.55 (- 1.0 up)))]
              (dotimes [b boughs]
                (let [a (+ a0 (/ (* (+ b (* (rnd) 0.5)) TAU) boughs))
                      ox (Math/cos a) oz (Math/sin a)
                      np [(+ cx (* ox rad 0.55)) (+ cz (* oz rad 0.55)) (- y (* rad droop 0.45))]
                      nn [ox oz -0.25]]
                  ;; needles
                  (let [p np n (norm3 nn) lit (moon-on p n)
                        c (v+ (paint3 [0.085 0.2 0.19] [0.035 0.085 0.1] [0.012 0.03 0.055] (* lit 0.8) (rnd))
                              [0.0 (* 0.028 (max (+ (nth n 2) 0.4) 0.0)) (* 0.02 (max (+ (nth n 2) 0.4) 0.0))])
                        c2 (v+ (v* c 1.5) [0.0 0.006 0.01])]
                    (add-surface-dab! buf p n [ox oz (- droop)] (* rad (+ 0.85 (* -0.3 up))) (* rad (+ 1.35 (* -0.4 up))) 0.02
                                      c c2 (long (* 8 (rnd))) rnd))
                  ;; snow on the bough
                  (let [p [(+ cx (* ox rad 0.6)) (+ cz (* oz rad 0.6)) (+ (- y (* rad droop 0.22)) 0.06)]
                        n (norm3 [(* ox 0.6) (* oz 0.6) 1.0])
                        lit (moon-on p n)
                        c (snow-paint lit (rnd) (* 0.7 (aurora-on (first p) (second p) n)) (+ 0.25 (* 0.25 (rnd))))
                        c2 (v+ (v* c 1.25) [0.0 0.006 0.01])]
                    (add-surface-dab! buf p n [ox oz (* -0.7 droop)] (* rad (+ 0.62 (* 0.3 up))) (* rad (+ 0.95 (* 0.25 up))) 0.04
                                      c c2 (long (* 8 (rnd))) rnd))))))
          ;; the top is a lump of snow
          (let [tx (+ (:x t) (* (:h t) (+ (first lean) (first curl))))
                tz (+ (:z t) (* (:h t) (+ (second lean) (second curl))))
                p [tx tz (+ (:y t) (* (:h t) 0.99))]]
            (add-surface-dab! buf p [0.0 0.0 1.0] [(first curl) (second curl) 1.0]
                              (+ 0.2 (* (:h t) 0.02)) (+ 0.45 (* (:h t) 0.04)) 0.02
                              [0.42 0.6 0.74] [0.5 0.68 0.8] 1 rnd)))))
    trees))

;; ============================================================================
;; the cabin (night/cabin.js, simplified)
;; ============================================================================

(defn- solid-buf [] (atom {:verts [] :faces [] :cols []}))

(defn- solid-vert! [sb p c]
  (let [i (count (:verts sb))]
    (swap! sb (fn [s] (-> s (update :verts conj p) (update :cols conj c))))
    i))

(defn- solid-tri! [sb a b c col]
  (let [s @sb
        i (count (:verts s))]
    (swap! sb (fn [st] (-> st (update :verts into [a b c]) (update :cols into [col col col]))))
    (swap! sb (fn [st] (update st :faces conj [i (inc i) (+ i 2)])))))

(defn- solid-quad! [sb a b c d col]
  (let [s @sb
        i (count (:verts s))]
    (swap! sb (fn [st] (-> st (update :verts into [a b c d]) (update :cols into [col col col col]))))
    (swap! sb (fn [st] (update st :faces conj [i (inc i) (+ i 2) (+ i 3)])))))

(defn- solid-box! [sb cx cnorth cup sx snorth sup col]
  (let [x0 (- cx (/ sx 2)) x1 (+ cx (/ sx 2))
        y0 (- cnorth (/ snorth 2)) y1 (+ cnorth (/ snorth 2))
        z0 (- cup (/ sup 2)) z1 (+ cup (/ sup 2))
        p000 [x0 y0 z0] p100 [x1 y0 z0] p110 [x1 y1 z0] p010 [x0 y1 z0]
        p001 [x0 y0 z1] p101 [x1 y0 z1] p111 [x1 y1 z1] p011 [x0 y1 z1]]
    (solid-quad! sb p100 p110 p111 p101 col)
    (solid-quad! sb p000 p001 p011 p010 col)
    (solid-quad! sb p010 p011 p111 p110 col)
    (solid-quad! sb p000 p100 p101 p001 col)
    (solid-quad! sb p001 p101 p111 p011 col)
    (solid-quad! sb p000 p010 p110 p100 col)))

(defn- solid-object! [sb name mat]
  (let [s @sb
        obj (bpy/add! name (vec (:verts s)) (vec (:faces s)))
        mesh (b/get obj "data")]
    (b/foreach-set! (b/call (b/get mesh "color_attributes") "new" {:name "Col" :type "FLOAT_COLOR" :domain "POINT"})
                    "data" "color" (vec (mapcat (fn [c] (conj c 1.0)) (:cols s))))
    (nodes/assign! obj mat)
    (b/set! obj "visible_shadow" true)
    obj))

(defn- plank-face! [buf rnd o u v n base rows sa sl density tone]
  (let [U (length3 u) V (length3 v)
        du (norm3 u)
        courses (max 1 (long (Math/round (/ V rows))))
        high (/ V courses)]
    (dotimes [j courses]
      (let [shade (* tone (+ 0.78 (* 0.44 (rnd))))]
        (loop [s (* -1.0 (rnd) sl)]
          (when (< s U)
            (let [len (* sl (+ 0.7 (* 0.7 (rnd))))
                  p (v+ (v+ o (v* u (/ (+ s (/ len 2.0)) U))) (v* v (/ (+ j 0.5) courses)))
                  lit (moon-on p n)
                  c (v* base (* shade (+ 0.9 (* 0.2 (rnd)))))]
              (add-surface-dab! buf p n du high len 0.006
                                (jitter c [(rnd) (rnd) (rnd)] 0.3)
                                (v+ (v* c 1.22) [0.012 0.008 0.004])
                                (long (* 8 (rnd))) rnd)
              (recur (+ s (* len 0.9))))))))))

(defn- scatter-face! [buf rnd o u v n base sa sl density]
  (let [area (* 0.5 (length3 (cross3 u v)))
        count (max 1 (long (Math/round (* (/ area (* sa sl)) 2.2 density))))]
    (dotimes [_ count]
      (let [s (rnd) t (rnd)
            s (if (> (+ s t) 1.0) (- 1.0 s) s)
            t (if (> (+ s t) 1.0) (- 1.0 t) t)
            p (v+ (v+ o (v* u s)) (v* v t))
            nn (norm3 n)
            dir (norm3 (v+ (norm3 u) (v* (cross3 nn (norm3 u)) (* (- (rnd) 0.5) 0.4))))
            lit (moon-on p nn)
            c (v* base (+ 0.8 (* 0.4 (rnd))))]
        (add-surface-dab! buf p nn dir (* sa (+ 0.8 (* 0.4 (rnd)))) (* sl (+ 0.75 (* 0.5 (rnd)))) 0.01
                          (jitter c [(rnd) (rnd) (rnd)] 0.25)
                          (v+ (v* c 1.2) [0.01 0.008 0.004])
                          (long (* 8 (rnd))) rnd)))))

(defn- make-cabin! [solid buf rnd]
  (let [cx (:x CABIN) cn (:z CABIN) cu (:y CABIN)
        W 6.4 D 5.2 H 2.4
        logcol [0.2 0.19 0.2] snowcol [0.42 0.6 0.74] stonecol [0.22 0.26 0.3]
        framecol [0.13 0.12 0.13] doorcol [0.2 0.17 0.17]
        n-s [0.0 -1.0 0.0] n-n [0.0 1.0 0.0] n-e [1.0 0.0 0.0] n-w [-1.0 0.0 0.0]
        ;; walls, in logs
        f-s [(- cx (/ W 2)) (- cn (/ D 2)) cu]
        f-n [(- cx (/ W 2)) (+ cn (/ D 2)) cu]
        f-w [(- cx (/ W 2)) (- cn (/ D 2)) cu]
        f-e [(+ cx (/ W 2)) (- cn (/ D 2)) cu]
        u-x [W 0 0] v-z [0 0 H] v-n [0 D 0]]
    ;; underpainting
    (solid-box! solid cx cn (+ cu (/ H 2)) W D H logcol)
    ;; gables
    (solid-tri! solid [(- cx (/ W 2)) (- cn (/ D 2)) (+ cu H)] [(- cx (/ W 2)) (+ cn (/ D 2)) (+ cu H)]
                [(- cx (/ W 2)) cn (+ cu 4.1)] logcol)
    (solid-tri! solid [(+ cx (/ W 2)) (- cn (/ D 2)) (+ cu H)] [(+ cx (/ W 2)) (+ cn (/ D 2)) (+ cu H)]
                [(+ cx (/ W 2)) cn (+ cu 4.1)] logcol)
    ;; roof: two slopes, snow lying on each
    (let [hw (/ (+ W 0.6) 2.0)
          hd (+ (/ D 2) 0.3)
          eave (+ cu H)
          ridge (+ cu 4.1)
          s0 [(- cx hw) (- cn hd) eave] s1 [(+ cx hw) (- cn hd) eave]
          s2 [(+ cx hw) cn ridge] s3 [(- cx hw) cn ridge]
          n0 [(- cx hw) cn ridge] n1 [(+ cx hw) cn ridge]
          n2 [(+ cx hw) (+ cn hd) eave] n3 [(- cx hw) (+ cn hd) eave]
          su [(* 2.0 hw) 0.0 0.0]]
      (solid-quad! solid s0 s1 s2 s3 [0.09 0.07 0.075])
      (solid-quad! solid n0 n1 n2 n3 [0.09 0.07 0.075])
      (scatter-face! buf rnd s0 su [0.0 hd (- ridge eave)] [0.0 -0.3 0.9] snowcol 0.35 1.4 1.2)
      (scatter-face! buf rnd n0 su [0.0 (- hd) (- ridge eave)] [0.0 0.3 0.9] snowcol 0.35 1.4 1.2))
    ;; log strokes on the four walls
    (plank-face! buf rnd f-s u-x v-z n-s logcol 0.27 0.27 1.15 1.2 1.0)
    (plank-face! buf rnd [(- cx (/ W 2)) (- cn (/ D 2)) cu] [0.0 D 0.0] [0.0 0.0 H] n-w logcol 0.27 0.27 1.15 1.2 1.0)
    (plank-face! buf rnd [(+ cx (/ W 2)) (- cn (/ D 2)) cu] [0.0 D 0.0] [0.0 0.0 H] n-e logcol 0.27 0.27 1.15 1.2 1.0)
    (plank-face! buf rnd [(- cx (/ W 2)) (+ cn (/ D 2)) cu] u-x v-z n-n logcol 0.27 0.27 1.15 1.2 1.0)
    ;; chimney, up the west wall
    (solid-box! solid (- cx 3.65) cn (+ cu 2.45) 0.9 1.0 4.9 stonecol)
    (solid-box! solid (- cx 3.65) cn (+ cu 4.99) 1.02 1.12 0.18 snowcol)
    (plank-face! buf rnd [(- cx 4.1) (- cn 0.5) cu] [0 1.0 0] [0 0 4.9] [-1 0 0] stonecol 0.19 0.22 0.28 1.2 1.0)
    ;; windows: the lit glass, its warm glow, and the cross of the frame
    (doseq [[x z face] [[(- cx 1.7) (+ cn 2.62) 1] [(+ cx 0.3) (- cn 2.62) -1] [(+ cx 3.22) (+ cn 0.7) 0]]]
      (let [p [x z (+ cu 1.45)]
            u (if (zero? face) [0 0.9 0] [(* 1.1 face) 0 0])
            v [0 0 0.9]]
        (scatter-face! buf rnd p u v (if (zero? face) [1 0 0] [0 0 face]) [1.0 0.62 0.22] 0.14 0.22 1.3)
        (solid-box! solid x z (+ cu 1.45) (if (zero? face) 0.05 0.045) (if (zero? face) 0.9 0.05) 0.9 framecol)))
    ;; doorway and its open door
    (solid-box! solid (+ cx 1.75) (+ cn 2.62) (+ cu 1.0) 1.3 0.14 2.1 framecol)
    (solid-box! solid (+ cx 2.42) (+ cn 3.12) (+ cu 1.0) 0.07 1.0 1.96 doorcol)
    (plank-face! buf rnd [(+ cx 2.42) (+ cn 2.62) cu] [0 1.0 0] [0 0 1.96] [1 0 0] doorcol 0.18 0.16 0.7 1.0 1.0)
    (solid-box! solid (+ cx 1.75) (+ cn 3.0) (+ cu 0.03) 1.5 0.7 0.06 stonecol)
    ;; a stack of wood against the south wall, snow on it
    (solid-box! solid (- cx 2.6) (+ cn 3.1) (+ cu 0.45) 1.1 0.45 0.9 doorcol)
    (solid-box! solid (- cx 2.6) (+ cn 3.12) (+ cu 0.98) 1.2 0.55 0.14 snowcol)
    ;; snow on the sills
    (doseq [[x z w d] [[(- cx 0.3) (- cn 2.7) 1.24 0.15] [(- cx 1.15) (+ cn 2.7) 1.24 0.15] [(+ cx 3.28) cn 0.15 1.3]]]
      (solid-box! solid x z (+ cu 1.04) w d 0.09 snowcol))
    ;; and the fire's light lying on the snow at the door
    (loop [i 0]
      (when (< i 40)
        (let [a (* (rnd) TAU) r (* 2.6 (Math/sqrt (rnd)))
              x (+ cx 1.75 (* (Math/cos a) r))
              z (+ cn 3.1 (* (Math/sin a) r))
              h (height-at x z)
              p [x z (+ h 0.06)]]
          (add-surface-dab! buf p [0 0 1] [(Math/cos a) (Math/sin a) 0] (* 0.7 (+ 0.5 (rnd))) (* 1.2 (+ 0.5 (rnd))) 0.01
                            [1.0 0.62 0.24] [1.0 0.7 0.3] (long (* 8 (rnd))) rnd))
        (recur (inc i))))))

;; ============================================================================
;; the night sky, stars, moon and the aurora (night/sky.js)
;; ============================================================================

(def ^:private SHELL 30000.0)

(defn- night-sky-colour
  "The sky with nothing in it, as nightSky() in night/paint.js. `v` is a
  Blender direction [x, north, up]."
  [v]
  (let [x (nth v 0) north (nth v 1) up (nth v 2)
        ;; three's y is up, z is north
        v3 [x up north]
        air [0.035 0.12 0.17]]
    (if (< up 0.0)
      [0.012 0.05 0.075]
      (let [u (Math/pow (clamp up 0.0 1.0) 0.5)
            c (mix3 [0.018 0.075 0.105] [0.004 0.014 0.036] u)
            northness (/ (max (- north) 0.0) (max (Math/sqrt (+ (* x x) (* north north))) 1e-4))
            band (* northness northness
                    (Math/exp (- (Math/pow (/ (- up 0.24) 0.3) 2))))
            moon (max (dot3 v3 MOON) 0.0)
            c (v+ c [(* 0.008 band) (* 0.075 band) (* 0.055 band)])
            c (v+ c [(* 0.1 (Math/pow moon 30.0)) (* 0.14 (Math/pow moon 30.0)) (* 0.18 (Math/pow moon 30.0))])
            c (v+ c [(* 0.25 (Math/pow moon 600.0)) (* 0.3 (Math/pow moon 600.0)) (* 0.32 (Math/pow moon 600.0))])]
        (mix3 air c (sstep 0.0 0.06 up))))))

(defn- sky-dome! []
  (let [seg 64 rings 32
        nv (* (inc seg) (inc rings))
        verts (double-array (* nv 3))
        cols (double-array (* nv 4))
        faces (object-array (* seg rings))
        add-col! (fn [k c]
                   (aset cols (+ (* k 4) 0) (double (nth c 0)))
                   (aset cols (+ (* k 4) 1) (double (nth c 1)))
                   (aset cols (+ (* k 4) 2) (double (nth c 2)))
                   (aset cols (+ (* k 4) 3) 1.0))]
    (dotimes [j (inc rings)]
      (let [elev (* (/ (double j) rings) (/ Math/PI 2.0))
            up (Math/sin elev) horiz (Math/cos elev)]
        (dotimes [i (inc seg)]
          (let [az (* (/ (double i) seg) TAU)
                x (* (Math/cos az) horiz)
                north (* (Math/sin az) horiz)
                k (+ i (* j (inc seg)))
                c (night-sky-colour [x north up])]
            (aset verts (* k 3) (double (* x SHELL)))
            (aset verts (+ (* k 3) 1) (double (* north SHELL)))
            (aset verts (+ (* k 3) 2) (double (* up SHELL)))
            (add-col! k c)))))
    (dotimes [j rings]
      (dotimes [i seg]
        (let [a (+ i (* j (inc seg)))
              b (+ a 1)
              c (+ a (inc seg))
              d (+ c 1)]
          (aset faces (+ i (* j seg)) [a b d c]))))
    (let [obj (bpy/add! "SkyDome"
                        (mapv (fn [i] [(aget verts (* i 3)) (aget verts (+ (* i 3) 1)) (aget verts (+ (* i 3) 2))])
                              (range nv))
                        (vec faces))
          mesh (b/get obj "data")]
      (b/foreach-set! (b/call (b/get mesh "color_attributes") "new" {:name "Col" :type "FLOAT_COLOR" :domain "POINT"})
                      "data" "color" cols)
      (nodes/assign! obj (flat-material "OilPaintSky" true))
      (b/set! obj "visible_shadow" false)
      obj)))

(defn- stars! [buf rnd]
  ;; a wash of sky-coloured strokes, then single touches of stars and the moon
  (let [add (fn [v size col cell]
              (let [p (v* (norm3 v) (* SHELL 0.985))
                    n (v* (norm3 v) -1.0)]
                (add-surface-dab! buf p n [0.0 0.0 1.0] size size 0.0 col (v* col 1.15) cell rnd)))]
    (dotimes [_ 1500]
      (let [a (* (rnd) TAU) up (+ 0.03 (* (Math/pow (rnd) 1.4) 0.95))
            s (Math/sqrt (max 0.0 (- 1.0 (* up up))))
            v [(Math/cos a) (Math/sin a) up]] ; x, north, up
        (add v (+ 120.0 (* 180.0 (rnd))) [0.02 0.04 0.06] 0)))
    (dotimes [_ 1600]
      (let [a (* (rnd) TAU) up (+ 0.03 (* (rnd) 0.97))
            s (Math/sqrt (max 0.0 (- 1.0 (* up up))))
            v [(Math/cos a) (Math/sin a) up]
            big (* (rnd) (rnd) (rnd))
            col (v* (if (> (rnd) 0.8) [1.0 0.9 0.72] [0.7 0.84 1.0])
                    (* (+ 0.4 (* 0.75 big)) (sstep 0.03 0.2 up)))]
        (add v (+ 28.0 (* 130.0 big)) col (if (> (rnd) 0.3) 5 4))))
    ;; the moon
    (dotimes [i 3]
      (add MOON (+ 120.0 (* 50.0 i)) [1.1 1.1 1.0] 4))
    (dotimes [_ 6]
      (let [j [(+ (nth MOON 0) (* (- (rnd) 0.5) 0.02))
               (+ (nth MOON 1) (* (- (rnd) 0.5) 0.02))
               (+ (nth MOON 2) (* (- (rnd) 0.5) 0.02))]]
        (add j (+ 500.0 (* 700.0 (rnd))) [0.09 0.1 0.11] 1)))))

;; --- the aurora -------------------------------------------------------------

(defn- curtain-foot
  "Where the foot of curtain k is at u (-1 west .. 1 east), in Blender
  [x, north, up]. Static time: the demo's curtains keep their shape."
  [u k]
  (if (< k 1.5)
    (let [ph (* u 8.0)
          x (+ (* 11000.0 u) (* 1500.0 (Math/sin ph)))
          north (+ -9200.0 (* 2300.0 (Math/cos (+ (* ph 0.83) 0.5)))
                   (* 800.0 (Math/sin (+ (* u 19.0) 0.33)))
                   (* 260.0 (Math/sin (- (* u 47.0) 0.65))))
          up (+ 1800.0 (* 600.0 (Math/sin (+ (* u 5.0) 0.05)))
                (* 200.0 (Math/sin (+ (* u 11.0) 1.1))))]
      (if (> k 0.5)
        (let [ph (* u 6.0)
              x (+ (* 16000.0 u) (* 800.0 (Math/sin ph)))
              north (+ -17500.0 (* 1600.0 (Math/sin (+ (* u 5.0) 1.3)))
                       (* 500.0 (Math/sin (- (* u 29.0) 0.4))))
              up (+ 1900.0 (* 120.0 (Math/sin (+ (* u 23.0) 0.5))))]
          [x north up])
        [x north up]))
    ;; the river: seen along its length, coming out of the north and overhead
    (let [z (* 2600.0 (Math/tan (- 1.46 (* u 0.5 2.61))))
          x (+ 1900.0 (* 1500.0 (Math/sin (/ z 2600.0))) (* 420.0 (Math/sin (/ z 700.0))))
          up (+ 2600.0 (* 120.0 (Math/sin (/ z 500.0))))]
      [x z up])))

(defn- curtain-colour [s I]
  (let [c (mix3 [0.05 0.92 0.45] [0.03 0.55 0.62] (sstep 0.18 0.5 s))
        c (mix3 c [0.5 0.2 0.82] (sstep 0.45 0.8 s))
        c (mix3 c [0.78 0.2 0.55] (sstep 0.8 1.1 s))]
    (mix3 c [0.38 1.0 0.36] (* (- 1.0 (sstep 0.0 0.16 s)) (sstep 0.3 0.85 I)))))

(defn- aurora! [buf rnd]
  (let [random-u (fn [] (- (* (rnd) 2.0) 1.0))
        curtain (fn [k]
                  (let [u (random-u)
                        foot (curtain-foot u k)
                        len (* (if (= k 1) 5200.0 6600.0)
                               (+ 0.6 (* 0.9 (+ 0.5 (* 0.5 (noise (* u 1.1) 0.77))))))]
                    {:u u :k k :foot foot :len len}))
        burn (fn [h s]
               (let [u (:u h) k (:k h)
                     reach (Math/pow (sstep 0.3 0.7 (+ 0.5 (* 0.5 (noise (+ (* u 0.33) (* k 0.31)) (+ 0.21 (* k 0.17)))))) 1.7)
                     ray (+ 0.3 (* 0.7 (+ 0.5 (* 0.5 (noise (* u 5.0) (+ 0.63 (* k 0.29)))))))
                     fine (+ 0.3 (* 0.7 (+ 0.5 (* 0.5 (noise (* u 21.0) (+ 0.11 (* k 0.4)))))))
                     I (* reach (+ 0.3 (* 0.7 (sstep 0.3 0.75 ray))) (+ 0.55 (* 0.9 fine)))
                     crown (* 0.24 (sstep 0.4 0.75 s) (sstep 0.4 0.85 I))]
                 (* I (+ (Math/exp (* -2.3 s)) crown)
                    (+ 0.8 (* 0.3 (Math/sin (+ (* s 7.0) (* u 55.0))))))))
        edge (fn [u] (sstep 1.0 0.8 (Math/abs u)))]
    ;; paint: the thick strokes of the curtain's own colours
    (dotimes [_ 3600]
      (let [h (curtain (if (> (rnd) 0.45) 0 1))
            {:keys [u k foot len]} h
            s0 (* (rnd) (rnd) 0.2)
            tall (+ 0.16 (* 0.26 (rnd)))
            s (+ s0 tall 0.5)
            mid (+ s0 (* tall 0.5))
            tone (clamp (* (burn h mid) 2.0) 0.0 1.0)
            r1 (rnd) r2 (rnd)
            c (curtain-colour mid (burn h mid))
            gap (+ 0.12 (* 0.88 (sstep 0.25 0.7 (+ 0.5 (* 0.5 (noise (+ (* u 0.9) (* k 3.1)) (+ 0.2 (* k 1.7))))))))
            centre (v+ foot [0.0 0.0 (* (+ s0 (* tall 0.5)) len)])
            skyc (night-sky-colour (norm3 centre))
            body (sstep 0.15 0.75 tone)
            base (v* (jitter c [r1 r2 (rnd)] 0.35) (* gap (+ 0.1 (* 0.32 tone)) (edge u) 1.6))
            c1 (mix3 skyc base body)
            c2 (mix3 skyc (v* base 1.4) body)
            n (norm3 (v* centre -1.0))
            dir [( * (- (rnd) 0.5) 0.12) 0.0 1.0]
            width (+ 45.0 (* 95.0 (rnd)))]
        (when (> tone 0.04)
          (add-surface-dab! buf centre n dir width (* tall len) 0.0 c1 c2 (long (* 8 (rnd))) rnd))))
    ;; glow: strokes of light that stand in the dark reaches too
    (dotimes [_ 2800]
      (let [h (curtain (if (> (rnd) 0.55) 0 (if (> (rnd) 0.7) 1 2)))
            {:keys [u k foot len]} h
            s0 (* (rnd) (rnd) 0.16)
            tall (+ 0.22 (* 0.34 (rnd)))
            mid (+ s0 (* tall 0.5))
            b (burn h mid)
            power (min (* b (+ 0.4 (* 0.9 (rnd)))) 0.6)
            c (curtain-colour mid b)
            gap (+ 0.1 (* 0.9 (sstep 0.28 0.72 (+ 0.5 (* 0.5 (noise (+ (* u 0.8) (* k 2.3)) (+ 0.7 (* k 1.3))))))))
            col (v* (jitter c [(rnd) (rnd) (rnd)] 0.3)
                    (* gap power 0.16 (edge u)))
            centre (v+ foot [0.0 0.0 (* (+ s0 (* tall 0.5)) len)])
            n (norm3 (v* centre -1.0))
            width (+ 90.0 (* 260.0 (rnd)))]
        (when (> power 0.03)
          (add-surface-dab! buf centre n [0.0 0.0 1.0] width (* tall len) 0.0 col (v* col 1.35) (long (* 8 (rnd))) rnd))))
    ;; the ragged bright edge, and the rose fringe below it
    (dotimes [_ 700]
      (let [h (curtain (if (> (rnd) 0.4) 0 1))
            {:keys [u k foot len]} h
            s0 (* (- (rnd) 0.35) 0.06)
            mid (+ s0 0.05)
            b (burn h mid)
            c (if (> (rnd) 0.65) [1.0 0.25 0.5] [0.38 1.0 0.36])
            col (v* (jitter c [(rnd) (rnd) (rnd)] 0.3) (* 0.22 (edge u) (sstep 0.35 0.8 b)))
            centre (v+ foot [0.0 0.0 (* (+ s0 0.025) len)])
            n (norm3 (v* centre -1.0))]
        (when (> (nth col 1) 0.005)
          (add-surface-dab! buf centre n [( * (- (rnd) 0.5) 0.3) 0.0 (if (> (rnd) 0.5) 1.0 -1.0)]
                            (+ 90.0 (* 150.0 (rnd))) (* len 0.03) 0.0 col (v* col 1.2) (long (* 8 (rnd))) rnd))))))

;; ============================================================================
;; the fjord, the ranges and their lamps (night/far.js)
;; ============================================================================

(def ^:private RINGS
  [{:r 2300.0 :top 620.0 :seed 3.1 :open [0.0 0.3 1.0] :count 2600}
   {:r 4300.0 :top 1000.0 :seed 11.7 :open [0.35 0.12 0.6] :count 1600}
   {:r 7600.0 :top 1500.0 :seed 23.9 :open [0.0 0.0 0.0] :count 1100}])

(defn- range-crest [k az broad]
  (let [o (nth RINGS k)
        c (Math/cos az) s (Math/sin az)
        n (+ 0.45 (* 0.55 (noise (+ (* c 2.2) (:seed o)) (* s 2.2)))
             (* 0.28 (noise (* c 6.5) (+ (* s 6.5) (:seed o)))))
        n (if broad n (+ n (* 0.12 (noise (+ (* c 17.0) (:seed o)) (* s 17.0)))
                            (* 0.05 (noise (* c 41.0) (+ (* s 41.0) (:seed o))))))
        turn (Math/abs (- (mod (- az (first (:open o)) (* Math/PI 3.0)) (* Math/PI 2.0)) Math/PI))
        gap (if (> (nth (:open o) 2) 0.0)
              (+ 0.1 (* 0.9 (sstep (nth (:open o) 1) (nth (:open o) 2) turn)))
              1.0)]
    (* (:top o) gap (min 1.4 (max 0.1 n)))))

(defn- water-plane! []
  (let [seg 72 half 16000.0
        nv (* (inc seg) (inc seg))
        verts (double-array (* nv 3))
        cols (double-array (* nv 4))
        faces (object-array (* seg seg))]
    (dotimes [j (inc seg)]
      (dotimes [i (inc seg)]
        (let [x (* (- (/ (double i) seg) 0.5) 2.0 half)
              north (* (- (/ (double j) seg) 0.5) 2.0 half)
              k (+ i (* j (inc seg)))
              d (Math/hypot x north)
              t (sstep 200.0 7000.0 d)
              northness (clamp (/ (- north) (max d 1.0)) 0.0 1.0)
              c (v+ (mix3 [0.012 0.06 0.09] [0.05 0.13 0.18] t)
                    [0.0 (* 0.03 northness t) (* 0.02 northness t)])]
          (aset verts (* k 3) (double x))
          (aset verts (+ (* k 3) 1) (double north))
          (aset verts (+ (* k 3) 2) 0.0)
          (aset cols (* k 4) (double (nth c 0)))
          (aset cols (+ (* k 4) 1) (double (nth c 1)))
          (aset cols (+ (* k 4) 2) (double (nth c 2)))
          (aset cols (+ (* k 4) 3) 1.0))))
    (dotimes [j seg]
      (dotimes [i seg]
        (let [a (+ i (* j (inc seg)))
              b (+ a 1)
              c (+ a (inc seg))
              d (+ c 1)]
          (aset faces (+ i (* j seg)) [a b d c]))))
    (let [obj (bpy/add! "Fjord"
                        (mapv (fn [i] [(aget verts (* i 3)) (aget verts (+ (* i 3) 1)) (aget verts (+ (* i 3) 2))])
                              (range nv))
                        (vec faces))
          mesh (b/get obj "data")]
      (b/foreach-set! (b/call (b/get mesh "color_attributes") "new" {:name "Col" :type "FLOAT_COLOR" :domain "POINT"})
                      "data" "color" cols)
      (nodes/assign! obj (flat-material "OilPaintWater" false))
      (b/set! obj "visible_shadow" false)
      obj)))

(defn- water-strokes! [buf rnd]
  (dotimes [_ 9000]
    (let [a (* (rnd) TAU)
          rad (+ 80.0 (* 6200.0 (Math/sqrt (rnd))))
          x (* (Math/sin a) rad)
          north (* (- (Math/cos a)) rad)]
      (when (and (> rad 650.0)
                 (< (max (Math/abs x) (Math/abs north)) 8000.0))
        (let [p [x north 0.0]
              d (Math/hypot x north)
              t (sstep 200.0 7000.0 d)
              northness (clamp (/ (- north) (max d 1.0)) 0.0 1.0)
              c (v* (v+ (mix3 [0.012 0.06 0.09] [0.05 0.13 0.18] t)
                        [0.0 (* 0.05 northness t) (* 0.03 northness t)])
                    (+ 0.55 (* 1.1 (rnd) (rnd))))
              c2 (v+ (v* c 1.35) [0.0 0.01 0.01])
              dir [(Math/cos a) (Math/sin a) 0.0]]
          (add-surface-dab! buf p [0.0 0.0 1.0] dir (+ 2.5 (* 3.0 (rnd))) (+ 7.0 (* 8.0 (rnd))) 0.02
                            c c2 (long (* 8 (rnd))) rnd))))))

(defn- ranges! [solid buf rnd]
  (let [SEG 360]
    (dotimes [k (count RINGS)]
      (let [o (nth RINGS k)
            verts (double-array (* (inc SEG) 2 3))
            cols (double-array (* (inc SEG) 2 4))
            faces (object-array SEG)]
        (dotimes [i (inc SEG)]
          (let [az (* (/ (double i) SEG) TAU)
                x (* (Math/sin az) (:r o))
                north (* (- (Math/cos az)) (:r o))
                top (range-crest k az false)
                k0 (* i 2)
                c0 [0.03 0.055 0.085]
                c1 [0.14 0.24 0.34]]
            (aset verts (* k0 3) (double x)) (aset verts (+ (* k0 3) 1) (double north)) (aset verts (+ (* k0 3) 2) -20.0)
            (aset verts (+ (* k0 3) 3) (double x)) (aset verts (+ (* k0 3) 4) (double north)) (aset verts (+ (* k0 3) 5) (double top))
            (dotimes [q 2]
              (let [c (if (zero? q) c0 c1)
                    kk (+ (* k0 4) (* q 4))]
                (aset cols kk (double (nth c 0))) (aset cols (+ kk 1) (double (nth c 1)))
                (aset cols (+ kk 2) (double (nth c 2))) (aset cols (+ kk 3) 1.0)))
            (when (< i SEG)
              (aset faces i [( * i 2) (+ (* i 2) 1) (+ (* i 2) 3) (+ (* i 2) 2)]))))
        (let [obj (bpy/add! (str "Range" k)
                            (mapv (fn [i] [(aget verts (* i 3)) (aget verts (+ (* i 3) 1)) (aget verts (+ (* i 3) 2))])
                                  (range (* (inc SEG) 2)))
                            (vec faces))
              mesh (b/get obj "data")]
          (b/foreach-set! (b/call (b/get mesh "color_attributes") "new" {:name "Col" :type "FLOAT_COLOR" :domain "POINT"})
                          "data" "color" cols)
          (nodes/assign! obj (flat-material (str "OilPaintRange" k) false))
          (b/set! obj "visible_shadow" false))
        ;; strokes over the wall
        (dotimes [_ (:count o)]
          (let [az (* (rnd) TAU)
                top (range-crest k az false)
                t (Math/pow (rnd) 0.8)
                rr (- (:r o) 40.0 (* 60.0 (rnd)))
                x (* (Math/sin az) rr)
                north (* (- (Math/cos az)) rr)
                up (* t top)
                n (norm3 [(Math/sin az) (- (Math/cos az)) 0.25])
                lit (clamp (+ (* (dot3 n MOON) 1.5) 0.25) 0.0 1.0)
                band (noise (+ x north) (* up 2.0))
                stone? (or (< t 0.07) (> band 0.1))
                c (if stone? (rock-paint lit (rnd) (rnd))
                      (snow-paint lit (rnd) (clamp (* 0.6 (nth n 2)) 0.0 1.0) (* 0.6 (rnd))))
                side (norm3 (cross3 n [0.0 0.0 1.0]))
                dir (norm3 (v+ (v* (norm3 (cross3 side n)) 0.6) side))]
            (add-surface-dab! buf [x north up] n dir
                              (* (:r o) (+ 0.008 (* 0.006 (rnd))))
                              (* (:r o) (+ 0.02 (* 0.02 (rnd)))) 0.0
                              (v* (jitter c [(rnd) (rnd) (rnd)] 0.18) 0.82)
                              (v* c 0.92) (long (* 8 (rnd))) rnd)))))))

;; ============================================================================
;; the wanderer on the crest
;; ============================================================================

(defn- make-wanderer!
  "A cloaked figure with a lantern, seen from behind: the picture's anchor.
  A static painted stand-in for the walker in the original."
  [solid buf rnd]
  (let [fx (crest-x 141.0) fz 141.0 fy (height-at fx fz)
        w (fn [dx dn du] [(+ fx dx) (+ fz dn) (+ fy du)])
        cloak-l [0.09 0.19 0.32] cloak-m [0.045 0.1 0.19] cloak-s [0.018 0.04 0.1]
        hat-l [0.34 0.22 0.12] hat-m [0.14 0.085 0.055] hat-s [0.04 0.025 0.03]
        skin-l [0.45 0.36 0.32] skin-m [0.2 0.16 0.17] skin-s [0.08 0.06 0.09]
        lantern [(+ fx 0.38) (- fz 0.08) (+ fy 1.02)]
        lit-fig (fn [c] (paint3 (v* c 1.05) c (v* c 0.5) 0.62 0.5))
        addd (fn [p n dir sa sl c1 c2]
               (add-surface-dab! buf p n dir sa sl 0.02 c1 c2 (long (* 8 (rnd))) rnd))]
    ;; the cloak: a cone, dark at the back and lighter along its edge
    (let [sides 10 base-r 0.48 top-r 0.18 h 1.3]
      (dotimes [s sides]
        (let [a0 (/ (* s TAU) sides) a1 (/ (* (inc s) TAU) sides)
              b0 (w (* (Math/cos a0) base-r) (* (Math/sin a0) base-r) 0.0)
              b1 (w (* (Math/cos a1) base-r) (* (Math/sin a1) base-r) 0.0)
              t0 (w (* (Math/cos a0) top-r) (* (Math/sin a0) top-r) h)
              t1 (w (* (Math/cos a1) top-r) (* (Math/sin a1) top-r) h)]
          (solid-quad! solid b0 b1 t1 t0 cloak-m)))
      (dotimes [tier 9]
        (let [t (/ (double tier) 8.0)
              r (+ (* base-r (- 1.0 t)) (* top-r t))
              y (+ fy (* h t) 0.01)]
          (dotimes [k 18]
            (let [a (+ (/ (* k TAU) 18.0) (* (- (rnd) 0.5) 0.2))
                  ox (Math/cos a) oz (Math/sin a)
                  p [(+ fx (* ox r)) (+ fz (* oz r)) y]
                  n (norm3 [ox oz 0.25])
                  lit (clamp (+ 0.4 (* 0.5 (noise (* a 2.0) (* t 3.0)))) 0.0 1.0)
                  c1 (jitter (paint3 cloak-l cloak-m cloak-s lit (rnd)) [(rnd) (rnd) (rnd)] 0.4)
                  warm-k (clamp (- 1.0 (/ (Math/hypot (- (first p) (first lantern))
                                                       (Math/hypot (- (nth p 1) (nth lantern 1))
                                                                   (- (nth p 2) (nth lantern 2)))) 0.55)) 0.0 1.0)
                  c1 (mix3 c1 [1.0 0.62 0.24] (* 0.45 warm-k))
                  c2 (v+ (v* c1 1.25) [0.0 0.008 0.012])]
              (addd p n [ox oz -1.0] (+ 0.06 (* 0.04 (rnd))) (+ 0.18 (* 0.14 (rnd))) c1 c2))))))
    ;; hood and hat
    (let [hx fx hz fz hy (+ fy 1.42)]
      (dotimes [_ 90]
        (let [a (* (rnd) TAU) e (* (- (rnd) 0.5) 0.7)
              ox (* (Math/cos a) (Math/cos e)) oz (* (Math/sin a) (Math/cos e))
              p [(+ hx (* ox 0.15)) (+ hz (* oz 0.15)) (+ hy (* 0.14 (Math/sin e)))]
              n (norm3 [ox oz (* 0.7 (Math/sin e))])]
          (addd p n [ox oz 0.4] 0.05 0.08 (lit-fig skin-m) (lit-fig skin-l))))
      ;; brim
      (dotimes [_ 70]
        (let [a (* (rnd) TAU) r (+ 0.2 (* 0.09 (rnd)))
              p [(+ hx (* (Math/cos a) r)) (+ hz (* (Math/sin a) r)) (+ hy 0.13)]
              n (norm3 [(Math/cos a) (Math/sin a) 0.7])]
          (addd p n [(Math/cos a) (Math/sin a) 0.2] 0.06 0.12 (lit-fig hat-m) (lit-fig hat-l))))
      ;; crown
      (dotimes [_ 60]
        (let [a (* (rnd) TAU) r (* 0.12 (Math/sqrt (rnd)))
              p [(+ hx (* (Math/cos a) r)) (+ hz (* (Math/sin a) r)) (+ hy 0.2 (* 0.1 (rnd)))]
              n (norm3 [(Math/cos a) (Math/sin a) 0.9])]
          (addd p n [(Math/cos a) (Math/sin a) 0.3] 0.05 0.1 (lit-fig hat-m) (lit-fig hat-l)))))
    ;; boots and the lantern's warm dabs
    (dotimes [k 2]
      (let [bx (+ fx (if (zero? k) -0.13 0.13)) bz (+ fz 0.02)]
        (solid-box! solid bx bz (+ fy 0.08) 0.16 0.24 0.16 [0.04 0.035 0.05])
        (dotimes [_ 8]
          (let [p [(+ bx (* (- (rnd) 0.5) 0.12)) (+ bz (* (- (rnd) 0.5) 0.18)) (+ fy 0.06)]
                n (norm3 [(- (rnd) 0.5) (- (rnd) 0.5) 0.7])]
            (addd p n [(- (rnd) 0.5) (- (rnd) 0.5) 0.2] 0.07 0.12 [0.09 0.08 0.09] [0.16 0.14 0.15])))))
    (dotimes [_ 18]
      (let [p [(+ (first lantern) (* (- (rnd) 0.5) 0.1))
               (+ (nth lantern 1) (* (- (rnd) 0.5) 0.08))
               (+ (nth lantern 2) (* (rnd) 0.08))]
            c (v* [1.0 0.62 0.24] (+ 0.4 (* 0.5 (rnd))))]
        (add-surface-dab! buf p [0.0 0.0 1.0] [0.0 0.0 1.0] (+ 0.05 (* 0.05 (rnd))) (+ 0.08 (* 0.08 (rnd))) 0.0
                          c (v* c 1.2) (long (* 8 (rnd))) rnd)))
    {:lantern [fx (+ fz 0.0) (+ fy 1.1)]}))

(defn- cabin-glow!
  "The lit openings seen from the ridge: a small warm beacon under the horn."
  [buf rnd]
  (let [cx (:x CABIN) cn (:z CABIN) cu (:y CABIN)
        openings [[(- cx 1.7) (+ cn 2.62) (+ cu 1.45) [0.0 1.0 0.0]]
                  [(+ cx 0.3) (- cn 2.62) (+ cu 1.45) [0.0 -1.0 0.0]]
                  [(+ cx 3.22) (+ cn 0.7) (+ cu 1.45) [1.0 0.0 0.0]]
                  [(+ cx 1.75) (+ cn 2.62) (+ cu 1.0) [0.0 1.0 0.0]]]]
    (doseq [[x north up n] openings]
      (dotimes [_ 7]
        (let [p [(+ x (* (- (rnd) 0.5) 0.6)) (+ north (* (- (rnd) 0.5) 0.1)) (+ up (* (- (rnd) 0.5) 0.5))]
              c (v* [1.0 0.55 0.2] (+ 0.6 (* 0.7 (rnd))))]
          (add-surface-dab! buf p n [0.0 1.0 0.0] (+ 0.35 (* 0.6 (rnd))) (+ 0.6 (* 0.9 (rnd))) 0.02
                            c (v* c 1.2) (long (* 8 (rnd))) rnd))))))

;; ============================================================================
;; scene setup
;; ============================================================================

(defn- clear-scene! []
  (doseq [o (vec (bpy/objects))]
    (when (not= "Camera" (bpy/name-of o))
      (bpy/delete! o)))
  (doseq [m (vec (b/coll (b/data) "materials"))]
    (b/call (b/get (b/data) "materials") "remove" {:material m})))

(defn- setup-render! []
  (b/set! (b/get (b/root) "view_settings") "view_transform" "Standard")
  (b/set! (b/get (b/root) "view_settings") "look" "None")
  (let [eevee (b/get (b/root) "eevee")]
    (b/set! eevee "taa_samples" 16)
    (b/set! eevee "use_raytracing" false)
    (b/set! eevee "use_shadows" true)
    (b/set! eevee "use_fast_gi" false))
  (let [r (b/get (b/root) "render")]
    (b/set! r "resolution_x" 1280)
    (b/set! r "resolution_y" 720)
    (b/set! r "resolution_percentage" 100)
    (b/set! r "film_transparent" false)))

(defn- setup-world! []
  (let [w (b/get (b/root) "world")]
    (b/set! w "use_nodes" true)
    (let [nt (nodes/tree w)
          bg (nodes/find nt "Background")]
      (nodes/set-input! bg "Color" [0.012 0.045 0.065 1.0])
      (nodes/set-input! bg "Strength" 0.35))))

(defn- setup-camera! []
  (let [cam (bpy/object "Camera")
        x (crest-x 150.0)
        north 150.0
        up (+ (height-at x north) 2.0)]
    (b/set! cam "location" [x north up])
    (b/set! cam "rotation_euler" [1.62 0.0 Math/PI])
    (let [d (b/get cam "data")]
      (b/set! d "lens" 24.0)
      (b/set! d "clip_start" 0.2)
      (b/set! d "clip_end" 45000.0))
    cam))

(defn- sun-object! [name energy color angle rot]
  (let [ld (b/call (b/get (b/data) "lights") "new" {:name name :type "SUN"})
        obj (bpy/link! (bpy/add-object! name ld))]
    (b/set! ld "energy" (double energy))
    (b/set! ld "color" color)
    (b/set! ld "angle" (double angle))
    (b/set! obj "rotation_euler" rot)
    obj))

(defn- setup-lights! []
  ;; the moon low in the west, cool; and the canvas rake that shows the paint
  (sun-object! "MoonLight" 2.0 [0.5 0.68 0.95] 0.09 [1.15 -0.2 -0.8])
  (sun-object! "RakeLight" 0.7 [0.85 0.9 1.0] 0.3 [0.5 0.7 0.4])
  ;; the carried lamp: a warm point light in the eye's hand
  (let [ld (b/call (b/get (b/data) "lights") "new" {:name "LanternLight" :type "POINT"})
        obj (bpy/link! (bpy/add-object! "LanternLight" ld))
        cam (bpy/object "Camera")]
    (b/set! ld "energy" 300.0)
    (b/set! ld "color" [1.0 0.6 0.25])
    (b/set! ld "shadow_soft_size" 0.25)
    (b/set! obj "parent" cam)
    (b/set! obj "location" [0.35 0.22 -0.65]))
  ;; a little warm lamp at the cabin doorstep, so its snow glows
  (let [ld (b/call (b/get (b/data) "lights") "new" {:name "CabinLamp" :type "POINT"})
        obj (bpy/link! (bpy/add-object! "CabinLamp" ld))]
    (b/set! ld "energy" 1600.0)
    (b/set! ld "color" [1.0 0.55 0.2])
    (b/set! ld "shadow_soft_size" 0.4)
    (b/set! obj "location" [(+ (:x CABIN) 1.75) (+ (:z CABIN) 2.9) (+ (:y CABIN) 1.2)]))
  ;; the wanderer's carried lantern
  (let [wx (crest-x 141.0) wz 141.0 wy (height-at wx wz)
        ld (b/call (b/get (b/data) "lights") "new" {:name "WandererLantern" :type "POINT"})
        obj (bpy/link! (bpy/add-object! "WandererLantern" ld))]
    (b/set! ld "energy" 500.0)
    (b/set! ld "color" [1.0 0.6 0.24])
    (b/set! ld "shadow_soft_size" 0.2)
    (b/set! obj "location" [(+ wx 0.38) (- wz 0.08) (+ wy 1.02)])))

;; ============================================================================
;; entry point (partial for now)
;; ============================================================================

(defn build! []
  (clear-scene!)
  (prepare-land!)
  (setup-render!)
  (setup-world!)
  (setup-camera!)
  (setup-lights!)
  (terrain-object)
  (let [mat (dab-material)
        rnd (make-rng 4141)
        solid (solid-buf)]
    (let [buf (dab-buf)]
      (snow-dabs! buf rnd)
      (lamp-pool! buf rnd (crest-x 141.0) 141.0 4.5 0.85)
      (lamp-pool! buf rnd (+ (:x CABIN) 1.75) (+ (:z CABIN) 2.9) 3.2 0.8)
      (dab-object! "SnowDabs" buf mat))
    (let [buf (dab-buf)]
      (make-pines! solid buf rnd)
      (make-cabin! solid buf rnd)
      (make-wanderer! solid buf rnd)
      (dab-object! "WoodDabs" buf mat))
    (solid-object! solid "PaintedSolid" (flat-material "OilPaintSolid" false))
    (sky-dome!)
    (water-plane!)
    (let [buf (dab-buf)]
      (water-strokes! buf rnd)
      (dab-object! "WaterDabs" buf mat))
    (let [buf (dab-buf)]
      (ranges! solid buf rnd)
      (dab-object! "RangeDabs" buf mat))
    (let [glow (glow-material)]
      (let [buf (dab-buf)]
        (stars! buf rnd)
        (dab-object! "Stars" buf glow))
      (let [buf (dab-buf)]
        (cabin-glow! buf rnd)
        (dab-object! "CabinGlow" buf glow))
      (let [buf (dab-buf)]
        (aurora! buf rnd)
        (dab-object! "Aurora" buf glow))))
  (println "oil-paint: done")
  :ok)
