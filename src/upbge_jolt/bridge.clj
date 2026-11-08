(ns upbge-jolt.bridge
  "Typed access to the host's RNA-style property surface, over the ujs_* C
  ABI (native/upbge_shim.h). The bindings resolve from the mock shim dylib
  (tests) or from the host process (a UPBGE build carrying the real shim).

  Pointers cross as flat maps {:owner :type :data} rather than raw addresses
  so they print, compare, and survive as REPL values. Strings returned by
  the host are valid only until the next ujs_* call, so every get copies
  into a jolt string immediately."
  (:require [upbge-jolt.core :as core]
            [jolt.ffi :as ffi]))

;; ujs_pointer = {int64 owner, int64 type, void* data} — 24 bytes, natural
;; alignment 8. ujs_prop_desc = {char name[64], int32 type, int32 array_len,
;; char item_type[64]} — 136 bytes.

(def ^:private ptr-size 24)
(def ^:private desc-size 136)

;; --- ABI bindings -------------------------------------------------------------

(ffi/defcfn ujs-last-error "ujs_last_error" [] :string)
(ffi/defcfn ujs-root "ujs_root" [:pointer] :bool)
(ffi/defcfn ujs-test-reset "ujs_test_reset" [] :void)
(ffi/defcfn ujs-free "ujs_free" [:pointer] :void)

(ffi/defcfn ujs-prop-find "ujs_prop_find" [:pointer :string :pointer] :bool)
(ffi/defcfn ujs-props-iter "ujs_props_iter" [:pointer] :int64)
(ffi/defcfn ujs-props-next "ujs_props_next" [:int64 :pointer] :bool)
(ffi/defcfn ujs-props-iter-free "ujs_props_iter_free" [:int64] :void)

(ffi/defcfn ujs-get-bool "ujs_get_bool" [:pointer :string :pointer] :bool)
(ffi/defcfn ujs-get-int "ujs_get_int" [:pointer :string :pointer] :bool)
(ffi/defcfn ujs-get-float "ujs_get_float" [:pointer :string :pointer] :bool)
(ffi/defcfn ujs-get-string "ujs_get_string" [:pointer :string :pointer] :bool)
(ffi/defcfn ujs-get-enum "ujs_get_enum" [:pointer :string :pointer :pointer] :bool)
(ffi/defcfn ujs-get-pointer "ujs_get_pointer" [:pointer :string :pointer] :bool)
(ffi/defcfn ujs-get-float-array "ujs_get_float_array" [:pointer :string :pointer :int32] :bool)
(ffi/defcfn ujs-get-float-array-n "ujs_get_float_array_n" [:pointer :string :pointer :pointer] :bool)

(ffi/defcfn ujs-set-bool "ujs_set_bool" [:pointer :string :bool] :bool)
(ffi/defcfn ujs-set-int "ujs_set_int" [:pointer :string :int64] :bool)
(ffi/defcfn ujs-set-float "ujs_set_float" [:pointer :string :double] :bool)
(ffi/defcfn ujs-set-string "ujs_set_string" [:pointer :string :string] :bool)
(ffi/defcfn ujs-set-enum "ujs_set_enum" [:pointer :string :int64] :bool)
(ffi/defcfn ujs-set-pointer "ujs_set_pointer" [:pointer :string :pointer] :bool)
(ffi/defcfn ujs-set-float-array "ujs_set_float_array" [:pointer :string :pointer :int32] :bool)

(ffi/defcfn ujs-coll-len "ujs_coll_len" [:pointer :string :pointer] :bool)
(ffi/defcfn ujs-coll-iter "ujs_coll_iter" [:pointer :string] :int64)
(ffi/defcfn ujs-coll-next "ujs_coll_next" [:int64 :pointer] :bool)
(ffi/defcfn ujs-coll-iter-free "ujs_coll_iter_free" [:int64] :void)

(ffi/defcfn ujs-id-new "ujs_id_new" [:string :string :pointer] :bool)
(ffi/defcfn ujs-mesh-from-pydata "ujs_mesh_from_pydata"
  [:pointer :pointer :int32 :pointer :int32 :pointer :int32] :bool)
(ffi/defcfn ujs-coll-add "ujs_coll_add" [:pointer :string :pointer] :bool)
(ffi/defcfn ujs-coll-remove "ujs_coll_remove" [:pointer :string :pointer] :bool)
(ffi/defcfn ujs-operator-call "ujs_operator_call" [:string] :bool)

;; --- pointer marshalling --------------------------------------------------------

(defn- host-error
  "Read the host's last error and throw it. ex-data carries the property
  name when known."
  [prop]
  (throw (ex-info (or (ujs-last-error) "host call failed")
                  (cond-> {} prop (assoc :prop prop)))))

(defn- write-ptr!
  "Marshal a jolt pointer map into `buf` (24 bytes). nil marshals to a null
  pointer."
  [buf p]
  (ffi/write buf :int64 (or (:owner p) 0) 0)
  (ffi/write buf :int64 (or (:type p) 0) 8)
  (ffi/write buf :pointer (if (and p (:data p)) (:data p) ffi/null) 16))

(defn- read-ptr!
  "Read a ujs_pointer out of `buf` into a jolt map; null data reads as nil."
  [buf]
  (let [data (ffi/read buf :pointer 16)]
    (when-not (ffi/null? data)
      {:owner (ffi/read buf :int64 0)
       :type  (ffi/read buf :int64 8)
       :data  data})))

(defmacro ^:private with-ptr
  "Bind `sym` to a freshly-marshalled 24-byte ujs_pointer for `p`, freed at
  scope end."
  [sym p & body]
  `(let [buf# (ffi/alloc ptr-size)]
     (try
       (write-ptr! buf# ~p)
       (let [~sym buf#] ~@body)
       (finally (ffi/free buf#)))))

;; --- descriptors ----------------------------------------------------------------

(defn- read-desc!
  "ujs_prop_desc out of raw memory: name[64], type, array_len, item_type[64]."
  [buf]
  {:name      (ffi/ptr->string buf)
   :type      (case (ffi/read buf :int32 64)
                0 :bool
                1 :int
                2 :float
                3 :string
                4 :enum
                5 :pointer
                6 :collection)
   :array-len (ffi/read buf :int32 68)
   :item-type (let [s (ffi/ptr->string (+ buf 72))]
                (when-not (empty? s) s))})

;; --- lifecycle / introspection ---------------------------------------------------

(defn reset-host!
  "Test hook: reset the mock host state."
  []
  (core/marshal (fn [] (ujs-test-reset))))

(defn root
  "The root pointer (editor: context scene data / Main; mock: the Scene)."
  []
  (core/marshal
   (fn []
     (let [out (ffi/alloc ptr-size)]
       (try
         (if (ujs-root out) (read-ptr! out) (host-error nil))
         (finally (ffi/free out)))))))

(defn prop-find
  "Describe property `name` on `p`, or nil when absent."
  [p name]
  (core/marshal
   (fn []
     (with-ptr buf p
       (let [out (ffi/alloc desc-size)]
         (try
           (when (ujs-prop-find buf name out) (read-desc! out))
           (finally (ffi/free out))))))))

(defn- props-step
  "One iteration of property enumeration: [desc more?] — desc nil when done."
  [it]
  (let [out (ffi/alloc desc-size)]
    (try
      (if (ujs-props-next it out)
        [(read-desc! out) true]
        [nil false])
      (finally (ffi/free out)))))

(defn- props-realize
  "Drain a property iterator into a vector, freeing the handle."
  [it]
  (loop [acc []]
    (let [[d more?] (props-step it)]
      (if more?
        (recur (conj acc d))
        (do (ujs-props-iter-free it) acc)))))

(defn props
  "All property descriptors of `p`."
  [p]
  (core/marshal
   (fn []
     (with-ptr buf p
       (let [it (ujs-props-iter buf)]
         (if (zero? it) [] (props-realize it)))))))

;; --- typed get: one helper per property type -------------------------------------

(defn- get-bool [p name]
  (with-ptr buf p
    (let [out (ffi/alloc 1)]
      (try
        (if (ujs-get-bool buf name out)
          (ffi/read out :bool)
          (host-error name))
        (finally (ffi/free out))))))

(defn- get-int [p name]
  (with-ptr buf p
    (let [out (ffi/alloc 8)]
      (try
        (if (ujs-get-int buf name out)
          (ffi/read out :int64)
          (host-error name))
        (finally (ffi/free out))))))

(defn- get-float-scalar [p name]
  (with-ptr buf p
    (let [out (ffi/alloc 8)]
      (try
        (if (ujs-get-float buf name out)
          (ffi/read out :double)
          (host-error name))
        (finally (ffi/free out))))))

(defn- get-float-fixed [p name n]
  (with-ptr buf p
    (let [out (ffi/alloc (* 8 n))]
      (try
        (if (ujs-get-float-array buf name out n)
          (vec (map #(ffi/read out :double (* 8 %)) (range n)))
          (host-error name))
        (finally (ffi/free out))))))

(defn- get-string [p name]
  (with-ptr buf p
    (let [out (ffi/alloc 8)]
      (try
        (if (ujs-get-string buf name out)
          ;; the host's const char* is valid only until the next ujs_*
          ;; call — copy it now
          (let [addr (ffi/read out :pointer)]
            (when-not (ffi/null? addr) (ffi/ptr->string addr)))
          (host-error name))
        (finally (ffi/free out))))))

(defn- get-enum [p name]
  (with-ptr buf p
    (let [v-out (ffi/alloc 8)
          s-out (ffi/alloc 8)]
      (try
        (if (ujs-get-enum buf name v-out s-out)
          (let [addr (ffi/read s-out :pointer)]
            {:value  (ffi/read v-out :int64)
             :idname (when-not (ffi/null? addr) (ffi/ptr->string addr))})
          (host-error name))
        (finally (ffi/free v-out) (ffi/free s-out))))))

(defn- get-pointer [p name]
  (with-ptr buf p
    (let [out (ffi/alloc ptr-size)]
      (try
        (if (ujs-get-pointer buf name out)
          (read-ptr! out)
          (host-error name))
        (finally (ffi/free out))))))

(defn get-float-array-n
  "Read a dynamic-length float array property; returns a vector of doubles."
  [p name]
  (core/marshal
   (fn []
     (with-ptr buf p
       (let [d-out (ffi/alloc 8)
             n-out (ffi/alloc 4)]
         (try
           (if (ujs-get-float-array-n buf name d-out n-out)
             (let [data (ffi/read d-out :pointer)
                   n    (ffi/read n-out :int32)]
              (try
                (vec (map #(ffi/read data :double (* 8 %)) (range n)))
                (finally (ujs-free data))))
             (host-error name))
           (finally (ffi/free d-out) (ffi/free n-out))))))))

(defn- get-float
  "Read a float property: scalar, fixed array, or dynamic array per the
  descriptor's array-len."
  [p name d]
  (if (not= 0 (:array-len d))
    (if (pos? (:array-len d))
      (get-float-fixed p name (:array-len d))
      (get-float-array-n p name))
    (get-float-scalar p name)))

(defn- get*
  "The tick-thread body of `get`."
  [p name]
  (let [d (or (prop-find p name) (host-error name))]
    (case (:type d)
      :bool       (get-bool p name)
      :int        (get-int p name)
      :float      (get-float p name d)
      :string     (get-string p name)
      :enum       (get-enum p name)
      :pointer    (get-pointer p name)
      :collection (host-error name))))

(defn get
  "Read property `name` on `p`. Float arrays return vectors of doubles,
  enums as {:value n :idname \"...\"}, pointers as maps or nil."
  [p name]
  (core/marshal (fn [] (get* p name))))

;; --- typed set: one helper per property type --------------------------------------

(defn- set-bool! [p name v]
  (with-ptr buf p
    (or (ujs-set-bool buf name (boolean v)) (host-error name))))

(defn- set-int! [p name v]
  (with-ptr buf p
    (or (ujs-set-int buf name (long v)) (host-error name))))

(defn- set-float-scalar! [p name v]
  (with-ptr buf p
    (or (ujs-set-float buf name (double v)) (host-error name))))

(defn- set-float-array! [p name xs]
  (let [xs (vec xs)
        n (count xs)
        dbuf (ffi/alloc (* 8 n))]
    (try
      (dotimes [i n] (ffi/write dbuf :double (double (nth xs i)) (* 8 i)))
      (with-ptr buf p
        (or (ujs-set-float-array buf name dbuf n) (host-error name)))
      (finally (ffi/free dbuf)))))

(defn- set-string! [p name v]
  (with-ptr buf p
    (or (ujs-set-string buf name (str v)) (host-error name))))

(defn- set-enum! [p name v]
  (if-not (number? v)
    (throw (ex-info "enum set needs a numeric value (idname resolution comes with the operator layer)"
                    {:prop name :value v}))
    (with-ptr buf p
      (or (ujs-set-enum buf name (long v)) (host-error name)))))

(defn- set-pointer! [p name v]
  (with-ptr buf p
    (let [vbuf (ffi/alloc ptr-size)]
      (try
        (write-ptr! vbuf v)
        (or (ujs-set-pointer buf name vbuf) (host-error name))
        (finally (ffi/free vbuf))))))

(defn- set-float
  "Write a float property: array when the descriptor has an array-len, scalar
  otherwise."
  [p name v d]
  (if (not= 0 (:array-len d))
    (set-float-array! p name v)
    (set-float-scalar! p name v)))

(defn- set*!
  "The tick-thread body of `set!`."
  [p name v]
  (let [d (or (prop-find p name) (host-error name))]
    (case (:type d)
      :bool       (set-bool! p name v)
      :int        (set-int! p name v)
      :float      (set-float p name v d)
      :string     (set-string! p name v)
      :enum       (set-enum! p name v)
      :pointer    (set-pointer! p name v)
      :collection (host-error name))))

(defn set!
  "Write property `name` on `p`. Floats accept a scalar or a seq; pointers
  accept a map or nil (nil clears)."
  [p name v]
  (core/marshal (fn [] (set*! p name v))))

(defn set-float-array-n!
  "Write a dynamic-length float array property from a seq of doubles."
  [p name xs]
  (core/marshal (fn [] (set-float-array! p name xs))))

;; --- collections -------------------------------------------------------------------

(defn coll-count
  "Length of collection property `name` on `p`."
  [p name]
  (core/marshal
   (fn []
     (with-ptr buf p
       (let [out (ffi/alloc 8)]
         (try
           (if (ujs-coll-len buf name out)
             (ffi/read out :int64)
             (host-error name))
           (finally (ffi/free out))))))))

(defn- coll-step
  "One iteration of collection enumeration: [member more?] — member may be
  nil for a null entry, more? false when done."
  [it]
  (let [out (ffi/alloc ptr-size)]
    (try
      (if (ujs-coll-next it out)
        [(read-ptr! out) true]
        [nil false])
      (finally (ffi/free out)))))

(defn- coll-realize
  "Drain a collection iterator into a vector, freeing the handle."
  [it]
  (loop [acc []]
    (let [[m more?] (coll-step it)]
      (if more?
        (recur (if m (conj acc m) acc))
        (do (ujs-coll-iter-free it) acc)))))

(defn coll
  "Vector of collection `name`'s members on `p`. Errors when `name` is not a
  collection property. Realized inside (the iterator handle is freed before
  returning), so members stay valid independent of later host calls."
  [p name]
  ;; coll-len validates the property is a collection before the iterator
  ;; runs; a non-collection answers false with a message in last_error
  (core/marshal
   (fn []
     (coll-count p name)
     (with-ptr buf p
       (let [it (ujs-coll-iter buf name)]
         (if (zero? it) [] (coll-realize it)))))))

;; --- creation / structural mutation -------------------------------------------

(defn id-new
  "Create a new ID datablock of `type` (e.g. \"Object\", \"Mesh\"), named
  `name`. Returns the pointer map."
  [type name]
  (core/marshal
   (fn []
     (let [out (ffi/alloc ptr-size)]
       (try
         (if (ujs-id-new type name out)
           (read-ptr! out)
           (host-error name))
         (finally (ffi/free out)))))))

(defn- face-encoding
  "A seq of face index-seqs -> [offsets indices], as flat int32 vectors.
  offsets has (count faces)+1 prefix sums into indices."
  [faces]
  (let [faces   (mapv vec faces)
        offsets (loop [i 0 acc [0]]
                  (if (= i (count faces))
                    (vec acc)
                    (recur (inc i) (conj acc (+ (peek acc) (count (nth faces i)))))))
        indices (vec (apply concat faces))]
    [offsets indices]))

(defn- write-doubles!
  "Write `xs` into `buf` as a packed double array."
  [buf xs]
  (doseq [[i x] (map-indexed vector xs)]
    (ffi/write buf :double (double x) (* 8 i))))

(defn- write-ints!
  "Write `xs` into `buf` as a packed int32 array."
  [buf xs]
  (doseq [[i x] (map-indexed vector xs)]
    (ffi/write buf :int32 x (* 4 i))))

(defn- mesh-from-pydata*!
  "The tick-thread body of `mesh-from-pydata!`."
  [mesh verts faces]
  (let [verts            (mapv (fn [xyz] (mapv double xyz)) verts)
        nverts           (count verts)
        [offsets indices] (face-encoding faces)
        vbuf             (ffi/alloc (* 8 nverts 3))
        obuf             (ffi/alloc (* 4 (count offsets)))
        ibuf             (ffi/alloc (* 4 (count indices)))]
    (try
      (write-doubles! vbuf (vec (apply concat verts)))
      (write-ints! obuf offsets)
      (write-ints! ibuf indices)
      (with-ptr buf mesh
        (or (ujs-mesh-from-pydata buf vbuf nverts obuf (count faces) ibuf (count indices))
            (host-error "vertices")))
      mesh
      (finally (ffi/free vbuf) (ffi/free obuf) (ffi/free ibuf)))))

(defn mesh-from-pydata!
  "Build `mesh` geometry from `verts` (a seq of [x y z] triples) and `faces`
  (a seq of vertex-index seqs), like bpy's Mesh.from_pydata. Returns the mesh."
  [mesh verts faces]
  (core/marshal (fn [] (mesh-from-pydata*! mesh verts faces))))

(defn coll-add!
  "Link `member` into collection `name` on `coll` (e.g. the scene's
  \"objects\"). Idempotent."
  [coll name member]
  (core/marshal
   (fn []
     (with-ptr cbuf coll
       (with-ptr mbuf member
         (or (ujs-coll-add cbuf name mbuf) (host-error name)))))))

(defn coll-remove!
  "Unlink `member` from collection `name` on `coll`. No-op when absent."
  [coll name member]
  (core/marshal
   (fn []
     (with-ptr cbuf coll
       (with-ptr mbuf member
         (or (ujs-coll-remove cbuf name mbuf) (host-error name)))))))

(defn operator!
  "Run the named operator (the `bpy.ops` equivalent, e.g.
  \"mesh.primitive_cube_add\") with its default arguments. Returns true when
  the operator finished."
  [opname]
  (core/marshal
   (fn []
     (or (ujs-operator-call opname) (host-error opname)))))
