(ns upbge-jolt.bpy
  "The ergonomic REPL surface over upbge-jolt.bridge: keyword access, object
  lookup by name, transform helpers — the shape of bpy from Clojure.
  Shapes and names track bpy where the mock's toy surface allows."
  (:require [upbge-jolt.bridge :as b]))

(defn scene
  "The root scene pointer."
  []
  (b/root))

(defn objects
  "All objects in the scene, as pointer maps."
  []
  (b/coll (scene) "objects"))

(defn name-of
  "The name property of `obj`."
  [obj]
  (b/get obj "name"))

(defn object
  "Look up an object by name; nil when absent."
  [nm]
  (some #(when (= nm (name-of %)) %) (objects)))

(defn get
  "Read property `k` (keyword or string) on `p`."
  [p k]
  (b/get p (name k)))

(defn put!
  "Write property `k` on `p`."
  [p k v]
  (b/set! p (name k) v))

(defn get-in
  "Nested property read: (get-in obj [:data :vertices]) walks pointer
  properties then reads the final key."
  ([p ks]
   (reduce (fn [acc k]
             (if (nil? acc)
               (reduced nil)
               (if (map? acc) (b/get acc (name k)) (reduced nil))))
           p
           ks)))

(defn pos
  "Location vector of `obj`."
  [obj]
  (get obj :location))

(defn move!
  "Set the world location of `obj` to `xyz`."
  [obj xyz]
  (put! obj :location xyz))

(defn translate!
  "Add `dxyz` to the current location."
  [obj dxyz]
  (let [cur (pos obj)]
    (put! obj :location (mapv + cur dxyz))))
