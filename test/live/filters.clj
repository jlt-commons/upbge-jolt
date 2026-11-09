;; Live check for 2D filters: run against a game in progress, over nREPL.
;;
;;   host/run.sh play /abs/scene.blend
;;   (load-file "test/live/filters.clj")   ; => {:failed [] ...}
;;
;; Each filter is added, left on for a few frames, and removed. Pass a
;; directory to (check "/abs/dir/") to also save a screenshot of each, for
;; looking at: the built-ins should be visibly different from :none, :depth
;; should be light with darker near objects across the whole frame, and
;; :legacy should be the inverted image.
(ns live.filters
  (:require [upbge-jolt.bge :as bge]))

(def builtins
  [:blur :sharpen :grayscale :sepia :invert :sobel :prewitt :laplacian
   :dilation :erosion])

(def customs
  {:color   "void main(){ vec4 c = texture(bgl_RenderedTexture, bgl_TexCoord.xy); fragColor = vec4(c.r, c.g * 0.3, c.b * 0.3, 1.0); }"
   :depth   "void main(){ float d = texture(bgl_DepthTexture, bgl_TexCoord.xy).r; fragColor = vec4(vec3(pow(d, 32.0)), 1.0); }"
   :uniform "uniform float amount;\nvoid main(){ vec4 c = texture(bgl_RenderedTexture, bgl_TexCoord.xy); fragColor = mix(c, vec4(0.0, 0.0, 1.0, 1.0), amount); }"
   :size    "void main(){ vec2 px = vec2(bgl_RenderedTextureWidth, bgl_RenderedTextureHeight); fragColor = texture(bgl_RenderedTexture, floor(bgl_TexCoord.xy * px / 16.0) * 16.0 / px); }"
   :kernel  "void main(){ vec4 s = vec4(0.0); for (int i = 0; i < 9; i++) s += texture(bgl_RenderedTexture, bgl_TexCoord.xy + bgl_TextureCoordinateOffset[i].xy * 6.0); fragColor = s / 9.0; }"
   :coord   "void main(){ fragColor = vec4(gl_FragCoord.xy / vec2(bgl_RenderedTextureWidth, bgl_RenderedTextureHeight), 0.0, 1.0); }"
   :legacy  "uniform sampler2D bgl_RenderedTexture;\nvoid main(void)\n{\n  vec4 c = texture2D(bgl_RenderedTexture, gl_TexCoord[0].st);\n  gl_FragColor = vec4(1.0 - c.rgb, 1.0);\n}\n"})

(defn- try-filter [dir k add!]
  (let [r (try (add!)
               (Thread/sleep 300)
               (when dir (bge/screenshot! (str dir "filter-" (name k) ".png")))
               :ok
               (catch Exception e (ex-message e))
               (finally (try (bge/remove-filter! 0) (catch Exception _ nil))
                        (try (bge/remove-filter! 1) (catch Exception _ nil))))]
    [k r]))

(defn check
  ([] (check nil))
  ([dir]
   (when dir (bge/screenshot! (str dir "filter-none.png")))
   (let [results (into (sorted-map)
                       (concat
                        (for [k builtins] (try-filter dir k #(bge/add-filter! 0 k)))
                        (for [[k src] customs]
                          (try-filter dir k #(do (bge/add-filter! 0 :custom src)
                                                 (when (= k :uniform)
                                                   (bge/filter-uniform! 0 "amount" 0.5)))))
                        [(try-filter dir :chain #(do (bge/add-filter! 0 :grayscale)
                                                     (bge/add-filter! 1 :invert)))]))]
     {:failed (vec (keep (fn [[k r]] (when (not= r :ok) [k r])) results))
      :results results})))

(check)
