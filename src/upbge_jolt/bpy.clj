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

(defn add-mesh!
  "Create a mesh named `name` from vertex positions (a seq of [x y z]
  triples) and faces (a seq of vertex-index seqs); returns the mesh."
  [name verts faces]
  (let [mesh (b/id-new "Mesh" name)]
    (b/mesh-from-pydata! mesh verts faces)
    mesh))

(defn add-object!
  "Create an object named `name` carrying `data` (a mesh pointer, or nil);
  returns the object."
  [name data]
  (b/object-new name data))

(defn link!
  "Link `obj` into the scene's objects collection; returns it."
  [obj]
  (b/coll-add! (scene) "objects" obj)
  obj)

(defn unlink!
  "Unlink `obj` from the scene's objects collection; returns it."
  [obj]
  (b/coll-remove! (scene) "objects" obj)
  obj)

(defn add!
  "Create a mesh object and link it into the scene in one step. Returns the
  object."
  [name verts faces]
  (link! (add-object! name (add-mesh! (str name "-mesh") verts faces))))

(defn ops
  "Run the named operator (the `bpy.ops` equivalent), optionally with
  properties and an editor area to run in:
  (ops \"mesh.primitive_cube_add\" {:size 2 :location [0 0 1]})
  (ops \"view3d.view_all\" {} {:area \"VIEW_3D\"}). Returns true."
  ([opname] (b/op! opname))
  ([opname props] (b/op! opname props))
  ([opname props opts] (b/op! opname props opts)))

(defn data
  "bpy.data."
  []
  (b/data))

(defn context
  "bpy.context."
  []
  (b/context))

(defn save!
  "Save the open file to `path` (wm.save_as_mainfile)."
  [path]
  (ops "wm.save_as_mainfile" {:filepath path}))

(defn open!
  "Open the .blend file at `path` (wm.open_mainfile)."
  [path]
  (ops "wm.open_mainfile" {:filepath path}))

(defn delete!
  "Remove `obj` from the file entirely (bpy.data.objects.remove)."
  [obj]
  (b/call (b/get (data) "objects") "remove" {:object obj :do_unlink true}))

(defn viewport!
  "Set every 3D viewport's display, so REPL work is visible as it happens:
  :shading \"RENDERED\" (EEVEE: materials, world sky, displacement, lights),
  \"MATERIAL\" or \"SOLID\"; :camera? true looks through the scene camera.
  Returns the number of viewports changed."
  [{:keys [shading camera?] :or {shading "RENDERED"}}]
  (let [screen (b/get (b/context) "screen")
        areas (filter #(= "VIEW_3D" (:idname (b/get % "type"))) (b/coll screen "areas"))]
    (doseq [area areas
            :let [space (b/get (b/get area "spaces") "active")]]
      (let [sh (b/get space "shading")]
        (b/set! sh "type" shading)
        (when (= shading "MATERIAL")
          (b/set! sh "use_scene_world" true)
          (b/set! sh "use_scene_lights" true)))
      (when (some? camera?)
        (b/set! (b/get space "region_3d") "view_perspective" (if camera? "CAMERA" "PERSP"))))
    (count areas)))
