(ns yacht-build
  "Builds the golden-hour sea scene: a radial LOD ocean grid with EEVEE vertex
  displacement driven by a shared Gerstner/Stokes wave sum, a physical sky, a
  warm low sun, AgX tone mapping and EEVEE ray-traced GI.

  The wave parameters are also written to a Text datablock (ocean-waves.edn)
  so main.clj computes exactly the same height on the CPU for buoyancy."
  (:require [upbge-jolt.bpy :as bpy]
            [upbge-jolt.bridge :as b]
            [upbge-jolt.nodes :as nodes]
            [upbge-jolt.game :as game]
            [clojure.string :as str]))

;; --- shared wave model --------------------------------------------------------
;; :len wavelength (m), :amp amplitude (m), :ang direction (deg, from +Y toward
;; +X), :harm second-harmonic weight (Stokes sharpening of the crest).

(def waves
  [{:len 92.0  :amp 0.34  :ang  12.0 :harm 0.10}
   {:len 61.0  :amp 0.22  :ang -34.0 :harm 0.10}
   {:len 39.0  :amp 0.15  :ang  38.0 :harm 0.09}
   {:len 24.0  :amp 0.10  :ang  -6.0 :harm 0.08}
   {:len 15.0  :amp 0.070 :ang  64.0 :harm 0.07}
   {:len  9.4  :amp 0.050 :ang -52.0 :harm 0.06}
   {:len  5.7  :amp 0.035 :ang  20.0 :harm 0.05}
   {:len  3.5  :amp 0.025 :ang -74.0 :harm 0.04}
   {:len  2.3  :amp 0.014 :ang   8.0 :harm 0.03}
   {:len  1.5  :amp 0.009 :ang -20.0 :harm 0.03}])

(def wave-text-name "ocean-waves.edn")

;; --- small node-graph helpers -------------------------------------------------
;; Helpers take and return output *sockets*; `set-in` links a socket or sets a
;; constant on any input (by name or index).

(defn set-in
  [nt v node k]
  (let [s (nodes/socket node :in k)]
    (if (map? v)
      (b/call (b/get nt "links") "new" {:output v :input s})
      (b/set! s "default_value" v))))

(defn math
  "A Math node; a/b are constants or sockets. Returns its output socket."
  [nt op a b]
  (let [n (nodes/node! nt "ShaderNodeMath" {:props {"operation" op}})]
    (when (some? a) (set-in nt a n 0))
    (when (some? b) (set-in nt b n 1))
    (nodes/socket n :out 0)))

(defn map-range
  "MapRange node (clamped); `v` is a socket or constant. Returns its Result."
  [nt v from-min from-max to-min to-max]
  (let [n (nodes/node! nt "ShaderNodeMapRange" {:props {"clamp" true}})]
    (set-in nt v n "Value")
    (set-in nt (double from-min) n "From Min")
    (set-in nt (double from-max) n "From Max")
    (set-in nt (double to-min) n "To Min")
    (set-in nt (double to-max) n "To Max")
    (nodes/socket n :out "Result")))

(defn noise
  "4D noise; `w` is a socket or nil. Returns its Fac output."
  [nt nm scale detail roughness distortion w]
  (let [n (nodes/node! nt "ShaderNodeTexNoise" {:name nm
                                                :props {"noise_dimensions" "4D"}})]
    (set-in nt (double scale) n "Scale")
    (set-in nt (double detail) n "Detail")
    (set-in nt (double roughness) n "Roughness")
    (set-in nt (double distortion) n "Distortion")
    (when w (set-in nt w n "W"))
    (nodes/socket n :out "Fac")))

(defn vector-length [nt v]
  (let [n (nodes/node! nt "ShaderNodeVectorMath" {:props {"operation" "LENGTH"}})]
    (set-in nt v n "Vector")
    (nodes/socket n :out "Value")))

(defn mix-color [nt fac col-a col-b]
  (let [n (nodes/node! nt "ShaderNodeMixRGB")]
    (set-in nt fac n "Fac")
    (set-in nt col-a n "Color1")
    (set-in nt col-b n "Color2")
    (nodes/socket n :out "Color")))

;; --- ocean material -----------------------------------------------------------

(defn build-ocean-material
  "Node tree for the sea. Returns the material."
  [name]
  (let [mat (nodes/material! name)
        nt  (nodes/tree mat)
        out (nodes/find nt "Material Output")
        bsdf (nodes/find nt "Principled BSDF")
        ;; Wave time is the Ocean object's colour.r, set each frame by main.clj
        ;; via nodes/object-params!. A Value node would make EEVEE regenerate
        ;; this whole tree every frame.
        time (:r (nodes/object-params! nt))
        geo (nodes/node! nt "ShaderNodeNewGeometry")
        pos (nodes/socket geo :out "Position")
        sep (nodes/node! nt "ShaderNodeSeparateXYZ")
        _ (set-in nt pos sep "Vector")
        px (nodes/socket sep :out "X")
        py (nodes/socket sep :out "Y")
        ;; height = sum over waves of a*(sin(ph) + harm*sin(2*ph))
        height (reduce
                (fn [acc {:keys [len amp ang harm]}]
                  (let [k (/ (* 2.0 Math/PI) len)
                        om (Math/sqrt (* 9.81 k))
                        th (* (/ ang 180.0) Math/PI)
                        dx (Math/sin th)
                        dy (Math/cos th)
                        dot (math nt "ADD" (math nt "MULTIPLY" dx px)
                                  (math nt "MULTIPLY" dy py))
                        kd (math nt "MULTIPLY" dot k)
                        wt (math nt "MULTIPLY" om time)
                        ph (math nt "SUBTRACT" kd wt)
                        s1 (math nt "SINE" ph nil)
                        s2 (math nt "SINE" (math nt "MULTIPLY" ph 2.0) nil)
                        h (math nt "MULTIPLY"
                                (math nt "ADD" s1 (math nt "MULTIPLY" s2 harm))
                                amp)]
                    (if acc (math nt "ADD" acc h) h)))
                nil
                waves)
        ;; level-of-detail ripples and foam noise, both animated by time.
        ;; Detail is deliberately low: four noise octaves across a full-screen
        ;; ocean is what used to make the game fragment-bound (~30 fps at 8-10).
        ;; The displaced geometry carries the large shape; the noise is only
        ;; fine sparkle, so 3-4 octaves look the same and run much faster.
        wslow (math nt "MULTIPLY" time 0.18)
        wfast (math nt "MULTIPLY" time 0.42)
        ripple (noise nt "ripple" 7.0 3.0 0.60 0.50 wslow)
        ripple2 (noise nt "ripple2" 24.0 4.0 0.55 0.70 wfast)
        foamn (noise nt "foam" 5.0 3.0 0.70 0.30 (math nt "MULTIPLY" time 0.05))
        dist (vector-length nt pos)
        distf (map-range nt dist 250.0 6500.0 0.0 1.0)
        crest (map-range nt height -0.04 0.48 0.0 1.0)
        ;; body colour: dark blue-green, lighter teal in crests
        base (mix-color nt crest [0.010 0.045 0.050 1.0] [0.045 0.150 0.130 1.0])
        ;; foam: steep/high crests broken up by noise, only nearby
        foamh (map-range nt height 0.16 0.40 0.0 1.0)
        foamn2 (map-range nt foamn 0.38 0.66 0.0 1.0)
        foamnear (map-range nt distf 0.0 0.35 1.0 0.0)
        foamm (math nt "MINIMUM" 1.0
                    (math nt "MULTIPLY" 1.6
                          (math nt "MULTIPLY" (math nt "MULTIPLY" foamh foamn2) foamnear)))
        basef (mix-color nt foamm base [0.88 0.93 0.95 1.0])
        ;; roughness: glassy near, rougher far, foam rough
        rough (math nt "ADD" 0.035
                    (math nt "ADD" (math nt "MULTIPLY" crest 0.05)
                          (math nt "ADD" (math nt "MULTIPLY" distf 0.30)
                                (math nt "MULTIPLY" foamm 0.45))))
        bump (nodes/node! nt "ShaderNodeBump")
        _ (set-in nt 0.50 bump "Strength")
        _ (set-in nt 0.18 bump "Distance")
        _ (set-in nt ripple bump "Height")
        bump2 (nodes/node! nt "ShaderNodeBump")
        _ (set-in nt 0.38 bump2 "Strength")
        _ (set-in nt 0.05 bump2 "Distance")
        _ (set-in nt ripple2 bump2 "Height")
        _ (set-in nt (nodes/socket bump :out "Normal") bump2 "Normal")
        _ (set-in nt (nodes/socket bump2 :out "Normal") bsdf "Normal")
        _ (set-in nt basef bsdf "Base Color")
        _ (set-in nt rough bsdf "Roughness")
        _ (set-in nt 1.333 bsdf "IOR")
        _ (set-in nt 0.5 bsdf "Specular IOR Level")
        ;; faint teal glow in the thin crests (subsurface stand-in for EEVEE)
        _ (set-in nt (math nt "MULTIPLY" crest 0.15) bsdf "Emission Strength")
        _ (set-in nt [0.020 0.130 0.100 1.0] bsdf "Emission Color")
        _ (set-in nt (nodes/socket bsdf :out 0) out "Surface")
        ;; geometry displacement from the same height field
        disp (nodes/node! nt "ShaderNodeDisplacement")
        _ (set-in nt 0.0 disp "Midlevel")
        _ (set-in nt 1.0 disp "Scale")
        _ (set-in nt height disp "Height")
        _ (set-in nt (nodes/socket disp :out 0) out "Displacement")]
    (b/set! mat "displacement_method" "DISPLACEMENT")
    (b/set! mat "surface_render_method" "DITHERED")
    (b/set! mat "max_vertex_displacement" 2.0)
    mat))

;; --- ocean mesh ---------------------------------------------------------------

(defn ring-radius
  "Ring i (1..rings) radius: linear near the origin, widening quadratically so
  the far rings cover the horizon with few vertices."
  [i rings rmax s0]
  (let [b (/ (- rmax (* s0 rings)) (* rings rings))]
    (+ (* s0 i) (* b i i))))

(defn build-ocean
  "Radial disc mesh, dense near the origin, out to `rmax` metres."
  [name {:keys [rings segments rmax s0]}]
  (let [idx (fn [i j] (+ 1 (* (dec i) segments) j))
        verts (into [[0.0 0.0 0.0]]
                    (for [i (range 1 (inc rings))
                          :let [r (ring-radius i rings rmax s0)]
                          j (range segments)
                          :let [a (* 2.0 Math/PI (/ (double j) segments))]]
                      [(* r (Math/cos a)) (* r (Math/sin a)) 0.0]))
        fan (for [j (range segments)]
              [0 (idx 1 j) (idx 1 (mod (inc j) segments))])
        quads (for [i (range 1 rings)
                    j (range segments)
                    :let [jn (mod (inc j) segments)]]
                [(idx i j) (idx i jn) (idx (inc i) jn) (idx (inc i) j)])
        obj (bpy/add! name verts (concat fan quads))
        mesh (b/get obj "data")]
    (try (b/call mesh "shade_smooth") (catch Exception _ nil))
    obj))

;; --- sky, sun, camera, render -------------------------------------------------

(defn mix-blend [nt blend fac col-a col-b]
  (let [n (nodes/node! nt "ShaderNodeMixRGB" {:props {"blend_type" blend}})]
    (set-in nt fac n "Fac")
    (set-in nt col-a n "Color1")
    (set-in nt col-b n "Color2")
    (nodes/socket n :out "Color")))

(declare sun-direction)

(defn vdot [nt a b]
  (let [n (nodes/node! nt "ShaderNodeVectorMath" {:props {"operation" "DOT_PRODUCT"}})]
    (set-in nt a n 0)
    (set-in nt b n 1)
    (nodes/socket n :out "Value")))

(defn setup-sky!
  "Golden-hour sky: an artistic horizon->zenith gradient (warm around the sun,
  blue and cool away from it) plus the physical Sky Texture's sun glow near
  the sun. The node tree is rebuilt on a fresh world each time."
  [sc]
  (let [worlds (b/get (bpy/data) "worlds")
        _ (when-let [old (b/lookup (bpy/data) "worlds" "SeaWorld")]
            (try (b/call worlds "remove" {:world old}) (catch Exception _ nil)))
        world (b/call worlds "new" {:name "SeaWorld"})
        _ (b/set! sc "world" world)
        _ (b/set! world "use_nodes" true)
        wnt (nodes/tree world)
        out (nodes/find wnt "World Output")
        bg (nodes/find wnt "Background")
        sky (nodes/node! wnt "ShaderNodeTexSky" {:name "Sky Texture"})
        tc (nodes/node! wnt "ShaderNodeTexCoord")
        dir (nodes/socket tc :out "Generated")
        sep (nodes/node! wnt "ShaderNodeSeparateXYZ")
        _ (set-in wnt dir sep "Vector")
        z (nodes/socket sep :out "Z")
        sunv (sun-direction 4.0)
        dot (vdot wnt dir sunv)
        sunmask (map-range wnt dot -0.25 0.95 0.0 1.0)
        gmask (map-range wnt dot 0.9997 0.99999 0.0 1.0)
        dpos (math wnt "MAXIMUM" dot 0.0)
        pow60 (math wnt "POWER" dpos 60.0)
        pow1200 (math wnt "POWER" dpos 1200.0)
        halo (math wnt "ADD" (math wnt "MULTIPLY" pow60 0.015)
                           (math wnt "MULTIPLY" pow1200 0.06))
        halocol (mix-color wnt (math wnt "MINIMUM" halo 1.0)
                           [0.0 0.0 0.0 1.0] [1.0 0.50 0.20 1.0])
        f1 (map-range wnt z 0.0 0.04 0.0 1.0)
        f2 (map-range wnt z 0.0 0.12 0.0 1.0)
        f3 (map-range wnt z 0.08 0.45 0.0 1.0)
        horcol (mix-color wnt sunmask [0.20 0.32 0.62 1.0] [1.05 0.36 0.05 1.0])
        c1 (mix-color wnt f1 horcol [0.32 0.28 0.34 1.0])
        c2 (mix-color wnt f2 c1 [0.035 0.100 0.280 1.0])
        grad (mix-color wnt f3 c2 [0.006 0.015 0.060 1.0])
        glow0 (mix-blend wnt "MULTIPLY" 1.0 (nodes/socket sky :out "Color")
                        [1.0 0.45 0.14 1.0])
        glow1 (mix-color wnt gmask [0.0 0.0 0.0 1.0] glow0)
        glow2 (mix-blend wnt "MULTIPLY" 1.0 glow1 [0.15 0.15 0.15 1.0])
        skycol (mix-blend wnt "ADD" 1.0 (mix-blend wnt "ADD" 1.0 grad halocol) glow2)]
    (b/set! sky "sky_type" "MULTIPLE_SCATTERING")
    (b/set! sky "sun_elevation" 0.0698) ; radians (4 degrees)
    (b/set! sky "sun_rotation" 1.0472)  ; radians (sun at azimuth +60)
    (b/set! sky "sun_intensity" 1.0)
    (b/set! sky "sun_size" 0.0095)      ; radians (~0.5 degree disc)
    (b/set! sky "sun_disc" true)
    (b/set! sky "altitude" 10.0)
    (b/set! sky "air_density" 1.0)
    (b/set! sky "aerosol_density" 2.0)
    (b/set! sky "ozone_density" 1.0)
    (set-in wnt skycol bg "Color")
    (set-in wnt 1.0 bg "Strength")
    (set-in wnt (nodes/socket bg :out 0) out "Surface")
    sky))

(defn sun-direction
  "Unit vector from the scene toward the sky's sun (matches sun_rotation 180
  with this sky model): sun at azimuth 60 deg from +Y toward +X."
  [elev-deg]
  (let [e (* (/ elev-deg 180.0) Math/PI)
        a (* (/ 60.0 180.0) Math/PI)]
    [(* (Math/cos e) (Math/sin a)) (* (Math/cos e) (Math/cos a)) (Math/sin e)]))

(defn euler-toward-sun
  "Euler (XYZ) that points a light's local -Z along -u (from sun to scene)."
  [[ux uy uz]]
  (let [theta (Math/acos uz)
        phi (Math/atan2 ux (- uy))]
    [theta 0.0 phi]))

(defn build-sun!
  [sc elev]
  (let [ld (b/id-new "Light" "SunData")
        obj (b/object-new "Sun" ld)]
    (b/set! ld "type" "SUN")
    (b/set! ld "color" [1.0 0.56 0.27])
    (b/set! ld "energy" 20.0)
    (b/set! ld "use_shadow" true)
    (b/set! ld "shadow_soft_size" 0.017) ; ~1 degree, soft golden-hour shadows
    (bpy/link! obj)
    (b/set! obj "rotation_euler" (euler-toward-sun (sun-direction elev)))
    obj))

(defn build-camera!
  [sc]
  (let [cd (b/id-new "Camera" "HeroCamData")
        obj (b/object-new "HeroCam" cd)]
    (b/set! cd "lens" 35.0)
    (b/set! cd "clip_start" 0.1)
    (b/set! cd "clip_end" 40000.0)
    (bpy/link! obj)
    (b/set! obj "location" [0.0 -30.0 2.2])
    ;; look toward the sun (azimuth +60 from +Y), tilted 1.5 deg down
    (b/set! obj "rotation_euler" [(- (/ Math/PI 2) 0.026) 0.0 (- (/ Math/PI 3))])
    (b/set! sc "camera" obj)
    obj))

(defn setup-render! [sc]
  (let [rs (b/get sc "render")
        ee (b/get sc "eevee")
        vs (b/get sc "view_settings")
        rto (b/get ee "ray_tracing_options")]
    (b/set! rs "engine" "BLENDER_EEVEE")
    ;; 720p for play. The ocean fragment shader is the whole frame cost, so
    ;; play resolution is the cheapest knob; stills render at taa_render_samples.
    (b/set! rs "resolution_x" 1280)
    (b/set! rs "resolution_y" 720)
    (b/set! rs "resolution_percentage" 100)
    (b/set! rs "fps" 60)
    (b/set! rs "film_transparent" false)
    (b/set! (b/get rs "image_settings") "file_format" "PNG")
    ;; EEVEE quality for play. 8 TAA samples is enough with the displaced sea;
    ;; measured the same fps as 16/32 in game because the ocean shader, not
    ;; TAA, is the bottleneck.
    (b/set! ee "taa_samples" 8)
    (b/set! ee "taa_render_samples" 64)
    (b/set! ee "use_shadows" true)
    (b/set! ee "use_shadow_jitter_viewport" true)
    (b/set! ee "shadow_ray_count" 2)
    (b/set! ee "shadow_step_count" 8)
    (b/set! ee "shadow_resolution_scale" 1.0)
    (b/set! ee "use_raytracing" true)
    (b/set! rto "use_denoise" true)
    (b/set! rto "trace_max_roughness" 0.6)
    (b/set! ee "use_fast_gi" false)
    (b/set! ee "clamp_surface_indirect" 10.0)
    ;; photographic tone mapping
    (b/set! vs "view_transform" "AgX")
    (b/set! vs "look" "AgX - Medium High Contrast")
    (b/set! vs "exposure" 0.15)
    (b/set! vs "gamma" 1.0)))

(defn clear-scene! []
  (doseq [o (vec (b/coll (bpy/data) "objects"))] (bpy/delete! o))
  (when-let [m (b/lookup (bpy/data) "materials" "Ocean")]
    (b/call (b/get (bpy/data) "materials") "remove" {:material m})))

(defn build!
  "Rebuild the sea scene from scratch. Returns a map of the built objects."
  []
  (clear-scene!)
  (let [sc (bpy/scene)
        _ (setup-render! sc)
        sky (setup-sky! sc)
        sun (build-sun! sc 4.0)
        cam (build-camera! sc)
        mat (build-ocean-material "Ocean")
        ocean (build-ocean "Ocean"
                           {:rings 480 :segments 288 :rmax 8000.0 :s0 0.22})]
    (nodes/assign! ocean mat)
    ;; keep the exact same wave model in the .blend for main.clj / buoyancy
    (let [t (or (b/lookup (bpy/data) "texts" wave-text-name)
                (b/call (b/get (bpy/data) "texts") "new" {:name wave-text-name}))]
      (b/call t "from_string" {:text (pr-str waves)}))
    {:scene sc :sky sky :sun sun :camera cam :ocean ocean :material mat}))

;; --- times of day -----------------------------------------------------------
;; main.clj switches scene.world to one of these names for the T key. Each is a
;; copy of the golden SeaWorld with the gradient constants, sun disc and glow
;; retuned, so the node graph stays identical and cheap to build.

(defn- socket-color! [nt node-name input rgba]
  (when-let [n (nodes/find nt node-name)]
    (b/set! (nodes/socket n :in input) "default_value" rgba)))

(defn- add-night-stars!
  "Add a sparse starfield and a small moon to a copied world graph, then feed
  the result to the Background color."
  [nt]
  (let [gen (nodes/socket (nodes/find nt "Texture Coordinate") :out "Generated")
        scale (nodes/node! nt "ShaderNodeVectorMath" {:props {"operation" "MULTIPLY"}})
        _ (set-in nt gen scale 0)
        _ (set-in nt [220.0 220.0 220.0] scale 1)
        wn (nodes/node! nt "ShaderNodeTexWhiteNoise" {:props {"noise_dimensions" "3D"}})
        _ (set-in nt (nodes/socket scale :out "Vector") wn "Vector")
        sr (nodes/node! nt "ShaderNodeMapRange" {:props {"clamp" true}})
        _ (set-in nt (nodes/socket wn :out "Value") sr "Value")
        _ (set-in nt 0.994 sr "From Min")
        _ (set-in nt 0.9999 sr "From Max")
        _ (set-in nt 0.0 sr "To Min")
        _ (set-in nt 1.0 sr "To Max")
        ;; moon: a small disc in one direction
        dot (vdot nt gen [0.35 -0.55 0.76])
        mr (nodes/node! nt "ShaderNodeMapRange" {:props {"clamp" true}})
        _ (set-in nt dot mr "Value")
        _ (set-in nt 0.99972 mr "From Min")
        _ (set-in nt 0.99992 mr "From Max")
        _ (set-in nt 0.0 mr "To Min")
        _ (set-in nt 3.2 mr "To Max")
        sky (nodes/socket (nodes/find nt "Mix (Legacy).009") :out "Color")
        add (nodes/node! nt "ShaderNodeMixRGB" {:props {"blend_type" "ADD"}})
        stars (nodes/socket sr :out "Result")
        moon (nodes/socket mr :out "Result")
        fac (nodes/node! nt "ShaderNodeMath" {:props {"operation" "ADD"}})
        _ (set-in nt stars fac 0)
        _ (set-in nt moon fac 1)
        _ (set-in nt 1.0 add "Fac")
        _ (set-in nt sky add "Color1")
        _ (set-in nt [0.90 0.93 1.0 1.0] add "Color2")
        _ (set-in nt (nodes/socket fac :out "Value") add "Fac")
        bg (nodes/find nt "Background")]
    (set-in nt (nodes/socket add :out "Color") bg "Color")))

(defn build-time-worlds!
  "Create Y-WorldDay, Y-WorldSunset and Y-WorldNight as tuned copies of the
  golden SeaWorld, then leave the scene on SeaWorld. Idempotent."
  []
  (let [base (b/lookup (bpy/data) "worlds" "SeaWorld")
        worlds (b/get (bpy/data) "worlds")
        presets
        [{:name "Y-WorldDay" :elev 55.0 :rot 60.0 :disc true
          :halo [1.0 0.88 0.65 1.0]
          :hor-away [0.42 0.60 0.93 1.0] :hor-sun [1.0 0.94 0.80 1.0]
          :c1 [0.30 0.52 0.95 1.0] :c2 [0.06 0.22 0.60 1.0]
          :zen [0.02 0.09 0.32 1.0]
          :glow-tint [1.0 0.94 0.82 1.0] :glow-mult [0.30 0.30 0.30 1.0]}
         {:name "Y-WorldSunset" :elev 2.0 :rot 250.0 :disc true
          :halo [1.0 0.45 0.12 1.0]
          :hor-away [0.24 0.18 0.38 1.0] :hor-sun [1.30 0.46 0.10 1.0]
          :c1 [0.38 0.22 0.38 1.0] :c2 [0.07 0.05 0.20 1.0]
          :zen [0.02 0.02 0.10 1.0]
          :glow-tint [1.0 0.48 0.16 1.0] :glow-mult [0.22 0.22 0.22 1.0]}
         {:name "Y-WorldNight" :elev 28.0 :rot 200.0 :disc true
          :halo [0.18 0.22 0.36 1.0]
          :hor-away [0.020 0.030 0.070 1.0] :hor-sun [0.040 0.055 0.110 1.0]
          :c1 [0.012 0.020 0.050 1.0] :c2 [0.005 0.009 0.028 1.0]
          :zen [0.002 0.004 0.014 1.0]
          :glow-tint [0.55 0.62 0.90 1.0] :glow-mult [0.05 0.05 0.05 1.0]
          :stars true}]]
    (doseq [p presets]
      (when-let [old (b/lookup (bpy/data) "worlds" (:name p))]
        (b/call worlds "remove" {:world old}))
      (let [w (b/call base "copy")
            _ (b/set! w "name" (:name p))
            nt (nodes/tree w)
            sky (nodes/find nt "Sky Texture")]
        (b/set! sky "sun_elevation" (* (/ (double (:elev p)) 180.0) Math/PI))
        (b/set! sky "sun_rotation" (* (/ (double (:rot p)) 180.0) Math/PI))
        (b/set! sky "sun_disc" (:disc p))
        (b/set! sky "sun_size" 0.0095)
        (socket-color! nt "Mix (Legacy)" "Color2" (:halo p))
        (socket-color! nt "Mix (Legacy).001" "Color1" (:hor-away p))
        (socket-color! nt "Mix (Legacy).001" "Color2" (:hor-sun p))
        (socket-color! nt "Mix (Legacy).002" "Color2" (:c1 p))
        (socket-color! nt "Mix (Legacy).003" "Color2" (:c2 p))
        (socket-color! nt "Mix (Legacy).004" "Color2" (:zen p))
        (socket-color! nt "Mix (Legacy).005" "Color2" (:glow-tint p))
        (socket-color! nt "Mix (Legacy).007" "Color2" (:glow-mult p))
        (when (:stars p) (add-night-stars! nt))))
    (b/set! (bpy/scene) "world" base)
    (println "worlds:" (map bpy/name-of (b/coll (bpy/data) "worlds")))))

;; --- interior fixes ---------------------------------------------------------
;; The saved scene predates two hull-interior fixes in yacht.clj: the aft
;; bulkhead had no companionway opening (the stairs dead-ended into a wall the
;; player walked through), and the forward end of the interior was open, so
;; from the master cabin you saw straight through the backface-culled hull into
;; the sea. This patches the live scene without a full yacht rebuild.

(defn- add-local-box!
  [name p0 p1 mat root]
  (let [[x0 y0 z0] p0 [x1 y1 z1] p1
        v [[x0 y0 z0] [x1 y0 z0] [x1 y1 z0] [x0 y1 z0]
           [x0 y0 z1] [x1 y0 z1] [x1 y1 z1] [x0 y1 z1]]
        f [[0 3 2 1] [4 5 6 7] [0 1 5 4] [2 3 7 6] [3 0 4 7] [1 2 6 5]]
        o (bpy/add! name v f)]
    (when mat (nodes/assign! o mat))
    (when root (b/set! o "parent" root))
    o))

(defn fix-interior!
  "Patch the interior of the existing scene: open the companionway through the
  aft bulkhead, add the forward bulkhead, warm up the practical lights.
  Idempotent."
  []
  (let [root (bpy/object "Y-Yacht")
        walnut (b/lookup (bpy/data) "materials" "Y-Walnut")]
    (when-let [a (bpy/object "Y-BulkAftA")] (bpy/delete! a))
    (when-not (bpy/object "Y-BulkAftHeader")
      (add-local-box! "Y-BulkAftHeader" [-1.72 -2.30 1.35] [-0.60 -2.24 1.54] walnut root))
    (when-not (bpy/object "Y-BulkFwd")
      (add-local-box! "Y-BulkFwd" [-1.72 6.04 -0.36] [1.72 6.12 1.54] walnut root))
    (doseq [nm ["Y-IntLightSalon" "Y-IntLightGalley" "Y-IntLightMaster" "Y-IntLightHead"]]
      (when-let [o (bpy/object nm)]
        (let [d (b/get o "data")]
          (b/set! d "energy" 55.0)
          (b/set! d "use_shadow" false))))
    (println "interior patched; lights"
             (mapv (fn [nm] (b/get (b/get (bpy/object nm) "data") "energy"))
                   ["Y-IntLightSalon" "Y-IntLightGalley" "Y-IntLightMaster" "Y-IntLightHead"]))))

(defn- move-mesh-y! [nm dy]
  (when-let [o (bpy/object nm)]
    (let [mesh (b/get o "data")
          n (* 3 (b/coll-count mesh "vertices"))
          co (b/foreach-get mesh "vertices" "co" n)
          co2 (vec (map-indexed (fn [i v] (if (= 1 (mod i 3)) (+ dy (double v)) (double v))) co))]
      (b/foreach-set! mesh "vertices" "co" co2)
      (b/call mesh "update"))))

(defn fix-stairs-landing!
  "The port saloon sofa ended right where the companionway stairs land, so the
  player could not step off the bottom step. Shift the whole sofa group forward
  by 0.85 m, leaving a landing; main.clj's sofa obstacle moves with it.
  Idempotent only with the marker stored in the scene."
  []
  (if (bpy/object "Y-SofaMoved")
    (println "sofa already moved")
    (do (doseq [o (bpy/objects)
                :let [nm (bpy/name-of o)]
                :when (str/starts-with? nm "Y-Sofa")]
          (move-mesh-y! nm 0.85))
        (let [mark (bpy/add! "Y-SofaMoved" [[0.0 0.0 0.0] [0.01 0.0 0.0] [0.0 0.01 0.0]] [[0 1 2]])]
          (b/set! mark "hide_viewport" true)
          (b/set! mark "hide_render" true))
        (println "sofa moved +0.85 y"))))

(defn tune-ocean!
  "Apply the session-5 wave/performance settings to an existing Ocean material:
  real vertex displacement is back on, and the fine noise uses 3-4 octaves.
  Idempotent; safe to re-run after opening the saved .blend."
  []
  (let [mat (b/lookup (bpy/data) "materials" "Ocean")
        nt (nodes/tree mat)]
    (b/set! mat "displacement_method" "DISPLACEMENT")
    (b/set! mat "max_vertex_displacement" 2.0)
    (doseq [[nm d] [["ripple" 3.0] ["ripple2" 4.0] ["foam" 3.0]]]
      (when-let [n (nodes/find nt nm)]
        (b/set! (nodes/socket n :in "Detail") "default_value" d)))
    (println "ocean:"
             (b/get mat "displacement_method")
             "noise detail ripple/ripple2/foam"
             (mapv (fn [nm] (b/get (nodes/socket (nodes/find nt nm) :in "Detail") "default_value"))
                   ["ripple" "ripple2" "foam"]))))

(defn tune!
  "Apply the play-quality render settings and the ocean tuning to the current
  scene without rebuilding it."
  []
  (tune-ocean!)
  (fix-interior!)
  (fix-stairs-landing!)
  (setup-render! (bpy/scene))
  (println "render:"
           (b/get (b/get (bpy/scene) "render") "resolution_x")
           "x" (b/get (b/get (bpy/scene) "render") "resolution_y")
           "taa" (b/get (b/get (bpy/scene) "eevee") "taa_samples")
           "fastgi" (b/get (b/get (bpy/scene) "eevee") "use_fast_gi")))

(defn save! []
  (bpy/save! "/Users/yogthos/src/jlt-commons/upbge-jolt/example/yacht/yacht.blend"))

(comment
  (build!)
  (tune!)
  (save!))
