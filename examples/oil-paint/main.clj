;; The wanderer's walk on the ridge. Stored in the .blend as the Text
;; "main.clj", so it runs on every game start and in a released app.
(require '[upbge-jolt.game :as game]
         '[upbge-jolt.bge :as bge]
         '[upbge-jolt.bridge :as b]
         '[upbge-jolt.bpy :as bpy])

(def spawn [15.9 150.0 252.2])
(def player (atom {:pos spawn :yaw 0.0 :pitch 0.1}))
(def cam (atom nil))
(def frames (atom 0))

(defn- clamp [x a b] (max a (min b x)))

(defn- add-finish-filters! []
  ;; a cheap bright-pass bloom on the aurora and the cabin, then the weave
  (bge/add-filter! 0 :custom
    "uniform float strength;
void main() {
  vec2 uv = bgl_TexCoord.xy;
  vec4 c = texture(bgl_RenderedTexture, uv);
  vec3 g = vec3(0.0);
  g += max(texture(bgl_RenderedTexture, uv + vec2(0.0025, 0.0)).rgb - vec3(0.62), vec3(0.0));
  g += max(texture(bgl_RenderedTexture, uv - vec2(0.0025, 0.0)).rgb - vec3(0.62), vec3(0.0));
  g += max(texture(bgl_RenderedTexture, uv + vec2(0.0, 0.0025)).rgb - vec3(0.62), vec3(0.0));
  g += max(texture(bgl_RenderedTexture, uv - vec2(0.0, 0.0025)).rgb - vec3(0.62), vec3(0.0));
  fragColor = vec4(c.rgb + g * strength, c.a);
}")
  (bge/filter-uniform! 0 "strength" 0.28)
  (bge/add-filter! 1 :custom
    "uniform float strength;
void main() {
  vec2 uv = bgl_TexCoord.xy;
  vec4 c = texture(bgl_RenderedTexture, uv);
  float weave = sin(uv.x * 900.0) * sin(uv.y * 900.0 + cos(uv.x * 14.0));
  c.rgb *= 1.0 + weave * 0.016 * strength;
  float vig = smoothstep(0.95, 0.32, length(uv - 0.5));
  c.rgb *= mix(0.88, 1.0, vig);
  fragColor = c;
}")
  (bge/filter-uniform! 1 "strength" 1.0))

(defn- ground-at [x north]
  (let [up (nth (:pos @player) 2)]
    (when-let [hit (bge/raycast [x north 900.0] [x north -50.0] @cam)]
      (nth (:point hit) 2))))

(defn- place! [x north yaw]
  (let [up (+ (or (ground-at x north) (nth spawn 2)) 1.75)]
    (swap! player assoc :pos [x north up] :yaw yaw :pitch 0.1)))

(game/on-start! :oilpaint
  (fn []
    (reset! cam (bge/object "Camera"))
    (reset! player {:pos spawn :yaw 0.0 :pitch 0.1})
    (reset! frames 0)
    (when-let [c @cam]
      (bge/move! c spawn)
      (bge/rotate-to! c [(+ (/ Math/PI 2) 0.1) 0.0 Math/PI]))
    (add-finish-filters!)))

(game/on-frame! :walk
  (fn [dt]
    (let [dt (min dt 1/20)
          [dx dy] (bge/mouse-look)
          settle? (< (swap! frames inc) 24)
          dx (if (or settle? (> (Math/abs dx) 0.12)) 0.0 dx)
          dy (if (or settle? (> (Math/abs dy) 0.12)) 0.0 dy)
          p @player
          yaw (clamp (+ (:yaw p) (* dx 1.9)) (- Math/PI) Math/PI)
          pitch (clamp (- (:pitch p) (* dy 1.6)) -1.15 1.15)
          fwd (if (bge/key-down? "WKEY") 1.0 (if (bge/key-down? "SKEY") -1.0 0.0))
          side (if (bge/key-down? "DKEY") 1.0 (if (bge/key-down? "AKEY") -1.0 0.0))
          speed (if (bge/key-down? "LEFTSHIFTKEY") 13.0 5.5)
          len (Math/hypot fwd side)
          sc (if (> len 1e-4) (/ (* speed dt) len) 0.0)
          sx (Math/sin yaw) cn (Math/cos yaw)
          mx (* sc (+ (* fwd sx) (* side cn)))
          mn (* sc (+ (* fwd (- cn)) (* side sx)))
          [x north up] (:pos p)
          nx (+ x mx) nn (+ north mn)
          off (or (> (Math/abs nx) 470.0) (> (Math/abs nn) 470.0))
          [nx nn] (if off [(nth spawn 0) (nth spawn 1)] [nx nn])
          hit (bge/raycast [nx nn (+ up 12.0)] [nx nn (- up 30.0)] @cam)
          gh (when hit (nth (:point hit) 2))
          target (if (and gh (> gh 1.0)) (+ gh 1.75) (nth spawn 2))
          up (+ up (* (- target up) (min 1.0 (* 10.0 dt))))
          [nx nn up] (if (< target 1.0) spawn [nx nn up])]
      (swap! player assoc :pos [nx nn up] :yaw yaw :pitch pitch)
      (bge/move! @cam [nx nn up])
      (bge/rotate-to! @cam [(+ (/ Math/PI 2) pitch) 0.0 (+ Math/PI yaw)])
      ;; the aurora breathes
      (when-let [a (bge/object "Aurora")]
        (let [clock (:clock (bge/frame-info))
              g (+ 1.0 (* 0.22 (Math/sin (* clock 0.11)))
                     (* 0.08 (Math/sin (+ (* clock 0.37) 1.0))))]
          (b/set! (bge/blender-object a) "color" [g 1.0 1.0 1.0]))))))

(game/on-frame! :keys
  (fn [_]
    (when (bge/key-pressed? "ESCKEY") (bge/release-mouse-look!))))

(println "oil paint: walk the ridge · WASD move · mouse look · shift run · esc releases")
