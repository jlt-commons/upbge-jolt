(ns upbge-jolt.nodes
  "Building node trees (materials, worlds, geometry nodes) from the REPL.

    (def mat (nodes/material! \"Sea\"))
    (def nt (nodes/tree mat))
    (def noise (nodes/node! nt \"ShaderNodeTexNoise\" {:inputs {\"Scale\" 4.0}}))
    (nodes/link! nt noise \"Fac\" (nodes/find nt \"Principled BSDF\") \"Roughness\")

  Sockets are named (\"Base Color\") or indexed (0, 1 — needed for Math
  nodes, whose inputs share the name \"Value\"). A Value node driven every
  frame from upbge-jolt.game/on-frame! (see `value!`) is how game logic feeds
  shaders: wave time, hull masks, time of day."
  (:refer-clojure :exclude [find])
  (:require [upbge-jolt.bridge :as b]))

(defn material!
  "A new node-based material named `name`."
  [name]
  (let [m (b/call (b/get (b/data) "materials") "new" {:name name})]
    (b/set! m "use_nodes" true)
    m))

(defn tree
  "The node tree of a material, world or light."
  [owner]
  (b/get owner "node_tree"))

(defn find
  "The node named `name` in tree `nt`, or nil."
  [nt name]
  (b/lookup nt "nodes" name))

(defn socket
  "Socket `k` (name or index) of `node`; dir is :in or :out."
  [node dir k]
  (or (b/lookup node (if (= dir :in) "inputs" "outputs") k)
      (throw (ex-info (str "no " (clojure.core/name dir) " socket " k) {:socket k}))))

(defn set-input!
  "Set an unlinked input's default value."
  [node k v]
  (b/set! (socket node :in k) "default_value" v))

(defn node!
  "Add a node of `type` (e.g. \"ShaderNodeMath\"). Options: :name, :props
  (node properties such as {\"operation\" \"MULTIPLY\"}), :inputs (default
  values by socket name or index)."
  ([nt type] (node! nt type {}))
  ([nt type {:keys [name props inputs]}]
   (let [n (b/call (b/get nt "nodes") "new" {:type type})]
     (when name (b/set! n "name" name))
     (doseq [[k v] props] (b/set! n (clojure.core/name k) v))
     (doseq [[k v] inputs] (set-input! n k v))
     n)))

(defn link!
  "Link output `out` of `from` to input `in` of `to`."
  [nt from out to in]
  (b/call (b/get nt "links") "new" {:output (socket from :out out) :input (socket to :in in)}))

(defn math!
  "A Math node doing `op` (\"ADD\" \"MULTIPLY\" \"SINE\" ...), with constant
  inputs given positionally (nil leaves an input for a link)."
  [nt op & consts]
  (let [n (node! nt "ShaderNodeMath" {:props {"operation" op}})]
    (doseq [[i v] (map-indexed vector consts) :when (some? v)]
      (set-input! n i v))
    n))

(defn value!
  "A Value node named `name` (a shader parameter game code can drive);
  returns its output socket, whose \"default_value\" is set per frame."
  [nt name v]
  (let [n (node! nt "ShaderNodeValue" {:name name})
        s (socket n :out 0)]
    (b/set! s "default_value" v)
    s))

(defn assign!
  "Put material `mat` on object `obj`'s first slot (adding a slot if empty)."
  [obj mat]
  (let [mats (b/get (b/get obj "data") "materials")]
    (if-let [slot (b/lookup obj "material_slots" 0)]
      (b/set! slot "material" mat)
      (b/call mats "append" {:material mat}))
    mat))
