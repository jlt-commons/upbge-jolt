(ns upbge-jolt.bridge
  "Typed access to the host's RNA-style property surface, over the ujs_* C
  ABI (native/upbge_shim.h). The bindings resolve from the mock shim dylib
  (tests) or from the host process (a UPBGE build carrying the real shim).

  Pointers cross as flat maps {:owner :type :data} rather than raw addresses
  so they print, compare, and survive as REPL values. Strings returned by
  the host are valid only until the next ujs_* call, so every get copies
  into a jolt string immediately."
  (:refer-clojure :exclude [get set!])
  (:require [clojure.string :as str]
            [upbge-jolt.core :as core]
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
(ffi/defcfn ujs-object-new "ujs_object_new" [:string :pointer :pointer] :bool)
(ffi/defcfn ujs-mesh-from-pydata "ujs_mesh_from_pydata"
  [:pointer :pointer :int32 :pointer :int32 :pointer :int32] :bool)
(ffi/defcfn ujs-coll-add "ujs_coll_add" [:pointer :string :pointer] :bool)
(ffi/defcfn ujs-coll-remove "ujs_coll_remove" [:pointer :string :pointer] :bool)
(ffi/defcfn ujs-operator-call "ujs_operator_call" [:string] :bool)

(ffi/defcfn ujs-kx-object-count "ujs_kx_object_count" [:pointer] :bool)
(ffi/defcfn ujs-kx-object-at "ujs_kx_object_at" [:int64 :pointer] :bool)
(ffi/defcfn ujs-kx-object-find "ujs_kx_object_find" [:string :pointer] :bool)
(ffi/defcfn ujs-kx-name "ujs_kx_name" [:pointer :pointer] :bool)
(ffi/defcfn ujs-kx-world-position "ujs_kx_world_position" [:pointer :pointer] :bool)
(ffi/defcfn ujs-kx-set-world-position "ujs_kx_set_world_position" [:pointer :pointer] :bool)

(ffi/defcfn ujs-data-root "ujs_data_root" [:pointer] :bool)
(ffi/defcfn ujs-context-root "ujs_context_root" [:pointer] :bool)
(ffi/defcfn ujs-operator-call-ex "ujs_operator_call_ex"
  [:string :int32 :pointer :pointer :string] :bool)
(ffi/defcfn ujs-func-call "ujs_func_call"
  [:pointer :string :int32 :pointer :pointer :pointer] :bool)
(ffi/defcfn ujs-coll-lookup "ujs_coll_lookup"
  [:pointer :string :string :int64 :pointer] :bool)
(ffi/defcfn ujs-coll-foreach-get "ujs_coll_foreach_get"
  [:pointer :string :string :pointer :int64] :bool)
(ffi/defcfn ujs-coll-foreach-set "ujs_coll_foreach_set"
  [:pointer :string :string :pointer :int64] :bool)
(ffi/defcfn ujs-get-int-array "ujs_get_int_array" [:pointer :string :pointer :pointer] :bool)
(ffi/defcfn ujs-set-int-array "ujs_set_int_array" [:pointer :string :pointer :int32] :bool)
(ffi/defcfn ujs-coll-ptr "ujs_coll_ptr" [:pointer :string :pointer] :bool)
(ffi/defcfn ujs-set-enum-id "ujs_set_enum_id" [:pointer :string :string] :bool)

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

(defn object-new
  "Create an Object named `name` carrying `data` (a mesh or other obdata
  pointer; nil makes an Empty), typed from its data like
  bpy.data.objects.new. Use this rather than (id-new \"Object\" ...), which
  makes an Empty that ignores a later data assignment."
  [name data]
  (core/marshal
   (fn []
     (with-ptr dbuf data
       (let [out (ffi/alloc ptr-size)]
         (try
           (if (ujs-object-new name dbuf out)
             (read-ptr! out)
             (host-error name))
           (finally (ffi/free out))))))))

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

;; --- KX engine (the running game) ---------------------------------------------

(defn- kx-object-at
  "Read the KX object at `i` into a pointer map (nil when out of range)."
  [i]
  (let [o (ffi/alloc ptr-size)]
    (try
      (when (ujs-kx-object-at i o) (read-ptr! o))
      (finally (ffi/free o)))))

(defn- kx-objects*
  "The tick-thread body of `kx-objects`."
  []
  (let [out (ffi/alloc 8)]
    (try
      (if (ujs-kx-object-count out)
        (vec (map kx-object-at (range (ffi/read out :int64))))
        (host-error nil))
      (finally (ffi/free out)))))

(defn kx-objects
  "All KX objects in the running game, as pointer maps. Empty when no game is
  running."
  []
  (core/marshal kx-objects*))

(defn kx-object
  "Find a KX object by name; nil when absent."
  [name]
  (core/marshal
   (fn []
     (let [out (ffi/alloc ptr-size)]
       (try
         (when (ujs-kx-object-find name out) (read-ptr! out))
         (finally (ffi/free out)))))))

(defn kx-name
  "The name of a KX object."
  [obj]
  (core/marshal
   (fn []
     (with-ptr buf obj
       (let [out (ffi/alloc 8)]
         (try
           (if (ujs-kx-name buf out)
             (let [addr (ffi/read out :pointer)]
               (when-not (ffi/null? addr) (ffi/ptr->string addr)))
             (host-error "name"))
           (finally (ffi/free out))))))))

(defn kx-position
  "World position of a KX object, as [x y z]."
  [obj]
  (core/marshal
   (fn []
     (with-ptr buf obj
       (let [out (ffi/alloc 24)]
         (try
           (if (ujs-kx-world-position buf out)
             (vec (for [i (range 3)] (ffi/read out :double (* 8 i))))
             (host-error "position"))
           (finally (ffi/free out))))))))

(defn kx-move!
  "Set the world position of a KX object."
  [obj xyz]
  (core/marshal
   (fn []
     (let [dbuf (ffi/alloc 24)]
       (try
         (doseq [[i v] (map-indexed vector xyz)]
           (ffi/write dbuf :double (double v) (* 8 i)))
         (with-ptr buf obj
           (or (ujs-kx-set-world-position buf dbuf) (host-error "position")))
         (finally (ffi/free dbuf)))))))

;; --- bpy.data / bpy.context, operators with arguments, RNA function calls,
;; collection lookup and bulk access ---------------------------------------


(defn- root-of [f]
  (core/marshal
   (fn []
     (let [out (ffi/alloc ptr-size)]
       (try
         (if (f out) (read-ptr! out) (host-error nil))
         (finally (ffi/free out)))))))

(defn data
  "bpy.data: the Main database (materials, meshes, images, objects, ...)."
  []
  (root-of ujs-data-root))

(defn context
  "bpy.context."
  []
  (root-of ujs-context-root))


;; ujs_value = {int32 type, int32 len, int64 i, double f, char* s, double* fa,
;; ujs_pointer p} -- 64 bytes; offsets below
(def ^:private val-size 64)
(def ^:private tag {:bool 0 :int 1 :float 2 :string 3 :enum 4 :pointer 5 :none -1})

(defn- write-val!
  "Marshal a jolt value into the ujs_value at `buf`; returns native
  allocations the caller frees after the call."
  [buf v]
  (let [allocs (atom [])
        put-tag! (fn [t] (ffi/write buf :int32 (tag t) 0))]
    (cond
      (boolean? v) (do (put-tag! :bool) (ffi/write buf :int64 (if v 1 0) 8))
      (integer? v) (do (put-tag! :int) (ffi/write buf :int64 (long v) 8)
                       (ffi/write buf :double (double v) 16))
      (number? v) (do (put-tag! :float) (ffi/write buf :double (double v) 16)
                      (ffi/write buf :int64 (long v) 8))
      (or (string? v) (keyword? v))
      (let [sp (ffi/string->ptr (if (keyword? v) (clojure.core/name v) v))]
        (swap! allocs conj sp)
        (put-tag! :string) (ffi/write buf :pointer sp 24))
      (or (nil? v) (map? v))
      (do (put-tag! :pointer)
          (ffi/write buf :int64 (or (:owner v) 0) 40)
          (ffi/write buf :int64 (or (:type v) 0) 48)
          (ffi/write buf :pointer (if (:data v) (:data v) ffi/null) 56))
      (sequential? v)
      (let [xs (vec v) n (count xs) fa (ffi/alloc (* 8 (max 1 n)))]
        (swap! allocs conj fa)
        (dotimes [i n] (ffi/write fa :double (double (nth xs i)) (* 8 i)))
        (put-tag! :float) (ffi/write buf :int32 n 4) (ffi/write buf :pointer fa 32))
      :else (throw (ex-info "cannot pass value to the host" {:value v})))
    @allocs))

(defn- read-val
  "Unmarshal a ujs_value result."
  [buf]
  (let [t (ffi/read buf :int32 0)]
    (case (int t)
      -1 nil
      0 (not= 0 (ffi/read buf :int64 8))
      1 (ffi/read buf :int64 8)
      2 (let [n (ffi/read buf :int32 4)]
          (if (pos? n)
            (let [fa (ffi/read buf :pointer 32)]
              (vec (for [i (range n)] (ffi/read fa :double (* 8 i)))))
            (ffi/read buf :double 16)))
      3 (let [s (ffi/read buf :pointer 24)] (when-not (ffi/null? s) (ffi/ptr->string s)))
      4 (let [s (ffi/read buf :pointer 24)]
          (if (ffi/null? s) (ffi/read buf :int64 8) (ffi/ptr->string s)))
      5 (let [data (ffi/read buf :pointer 56)]
          (when-not (ffi/null? data)
            {:owner (ffi/read buf :int64 40) :type (ffi/read buf :int64 48) :data data})))))

(defn- kw-name [k] (if (keyword? k) (clojure.core/name k) (str k)))

(defn call
  "Call RNA function `fname` on `p` with keyword arguments, like bpy:
  (call (coll-of (data) \"materials\") \"new\" {:name \"Teak\"}).
  Returns the function's result (pointer map, number, string, vector) or nil."
  ([p fname] (call p fname {}))
  ([p fname kwargs]
   (core/marshal
    (fn []
      (let [ks (vec (keys kwargs))
            n (count ks)
            names (ffi/alloc (* 8 (max 1 n)))
            vals (ffi/alloc (* val-size (max 1 n)))
            ret (ffi/alloc val-size)
            allocs (atom [])]
        (try
          (dotimes [i n]
            (let [sp (ffi/string->ptr (kw-name (nth ks i)))]
              (swap! allocs conj sp)
              (ffi/write names :pointer sp (* 8 i))
              (swap! allocs into (write-val! (+ vals (* val-size i)) (clojure.core/get kwargs (nth ks i))))))
          (with-ptr buf p
            (if (ujs-func-call buf fname n names vals ret)
              (read-val ret)
              (host-error fname)))
          (finally
            (doseq [a @allocs] (ffi/free a))
            (ffi/free names) (ffi/free vals) (ffi/free ret))))))))

(defn- op-val-str [v]
  (cond (sequential? v) (str/join "," (map op-val-str v))
        (keyword? v) (clojure.core/name v)
        (set? v) (str/join "," (map op-val-str v))
        :else (str v)))

(defn op!
  "Run operator `opname` with keyword properties, optionally in an editor
  area: (op! \"wm.save_as_mainfile\" {:filepath \"/tmp/x.blend\"})
  (op! \"view3d.view_all\" {} {:area \"VIEW_3D\"}). Returns true."
  ([opname] (op! opname {} {}))
  ([opname props] (op! opname props {}))
  ([opname props {:keys [area]}]
   (core/marshal
    (fn []
      (let [ks (vec (keys props))
            n (count ks)
            kbuf (ffi/alloc (* 8 (max 1 n)))
            vbuf (ffi/alloc (* 8 (max 1 n)))
            allocs (atom [])]
        (try
          (dotimes [i n]
            (let [kp (ffi/string->ptr (kw-name (nth ks i)))
                  vp (ffi/string->ptr (op-val-str (clojure.core/get props (nth ks i))))]
              (swap! allocs conj kp vp)
              (ffi/write kbuf :pointer kp (* 8 i))
              (ffi/write vbuf :pointer vp (* 8 i))))
          (or (ujs-operator-call-ex opname n kbuf vbuf (some-> area kw-name))
              (host-error opname))
          (finally
            (doseq [a @allocs] (ffi/free a))
            (ffi/free kbuf) (ffi/free vbuf))))))))



(defn- coll-ptr* [p name]
  (with-ptr buf p
    (let [out (ffi/alloc ptr-size)]
      (try
        (if (ujs-coll-ptr buf name out) (read-ptr! out) (host-error name))
        (finally (ffi/free out))))))

(defn coll-ptr
  "The function-carrying struct of collection `name` on `p` -- the `self` for
  (call ...) on things like bpy.data.materials.new or node_tree.links.new."
  [p name]
  (core/marshal #(coll-ptr* p name)))

(defn- int-array* [p name]
  (with-ptr buf p
    (let [out (ffi/alloc 8) len (ffi/alloc 4)]
      (try
        (if (ujs-get-int-array buf name out len)
          (let [a (ffi/read out :pointer) n (ffi/read len :int32)]
            (try (vec (ffi/read-array a :int64 n)) (finally (ujs-free a))))
          (host-error name))
        (finally (ffi/free out) (ffi/free len))))))

(defn- set-int-array! [p name xs]
  (let [xs (vec xs) n (count xs) ib (ffi/alloc (* 8 (max 1 n)))]
    (try
      (dotimes [i n] (ffi/write ib :int64 (long (if (boolean? (nth xs i)) (if (nth xs i) 1 0) (nth xs i))) (* 8 i)))
      (with-ptr buf p (or (ujs-set-int-array buf name ib n) (host-error name)))
      (finally (ffi/free ib)))))

(defn- get* [p name]
  (let [d (or (prop-find p name) (host-error name))
        arr? (not= 0 (:array-len d))]
    (case (:type d)
      :bool       (if arr? (mapv pos? (int-array* p name)) (get-bool p name))
      :int        (if arr? (int-array* p name) (get-int p name))
      :float      (get-float p name d)
      :string     (get-string p name)
      :enum       (get-enum p name)
      :pointer    (get-pointer p name)
      :collection (coll-ptr* p name))))

(defn- set-enum! [p name v]
  (with-ptr buf p
    (or (if (number? v)
          (ujs-set-enum buf name (long v))
          (ujs-set-enum-id buf name (if (coll? v)
                                      (str/join "," (map kw-name v))
                                      (kw-name v))))
        (host-error name))))

(defn- set*! [p name v]
  (let [d (or (prop-find p name) (host-error name))
        arr? (not= 0 (:array-len d))]
    (case (:type d)
      :bool       (if arr? (set-int-array! p name v) (set-bool! p name v))
      :int        (if arr? (set-int-array! p name v) (set-int! p name v))
      :float      (set-float p name v d)
      :string     (set-string! p name v)
      :enum       (set-enum! p name v)
      :pointer    (set-pointer! p name v)
      :collection (host-error name))))

(defn lookup
  "Collection item by name or index: (lookup (data) \"objects\" \"Cube\"),
  (lookup mesh \"vertices\" 0). nil when absent."
  [p name k]
  (core/marshal
   (fn []
     (with-ptr buf p
       (let [out (ffi/alloc ptr-size)]
         (try
           (when (ujs-coll-lookup buf name (when (string? k) k) (if (number? k) (long k) 0) out)
             (read-ptr! out))
           (finally (ffi/free out))))))))

(defn foreach-get
  "Bulk-read attribute `attr` across collection `name` (foreach_get); `n` is
  the total value count (items x attr width). Returns a double-array."
  [p name attr n]
  (core/marshal
   (fn []
     (let [b (ffi/alloc (* 8 (max 1 n)))]
       (try
         (with-ptr buf p
           (if (ujs-coll-foreach-get buf name attr b n)
             (ffi/read-array b :double n)
             (host-error attr)))
         (finally (ffi/free b)))))))

(defn foreach-set!
  "Bulk-write attribute `attr` across collection `name` (foreach_set) from a
  double-array or seq of numbers, item-major."
  [p name attr xs]
  (let [arr (if (instance? (Class/forName "[D") xs) xs (double-array xs))
        n (alength arr)]
    (core/marshal
     (fn []
       (let [b (ffi/alloc (* 8 (max 1 n)))]
         (try
           (ffi/write-array b :double arr)
           (with-ptr buf p
             (or (ujs-coll-foreach-set buf name attr b n) (host-error attr)))
           (finally (ffi/free b))))))))

(defn get
  "Read property `name` on `p`. Float/int/bool arrays return vectors, enums
  {:value n :idname \"...\"}, pointers maps or nil, collections their
  function struct (for call)."
  [p name]
  (core/marshal (fn [] (get* p name))))

(defn set!
  "Write property `name` on `p`. Arrays accept seqs; enums accept identifier
  strings/keywords (a set or seq for flag enums) or numbers; pointers a map
  or nil."
  [p name v]
  (core/marshal (fn [] (set*! p name v))))

;; --- KX runtime: transforms, physics, raycasts, hierarchy, spawning,
;; cameras, input, canvas ------------------------------------------------------

(ffi/defcfn ujs-kx-running "ujs_kx_running" [] :bool)
(ffi/defcfn ujs-kx-end-game "ujs_kx_end_game" [] :bool)
(ffi/defcfn ujs-kx-get-vec "ujs_kx_get_vec" [:pointer :string :pointer] :bool)
(ffi/defcfn ujs-kx-set-vec "ujs_kx_set_vec" [:pointer :string :pointer] :bool)
(ffi/defcfn ujs-kx-get-orientation "ujs_kx_get_orientation" [:pointer :pointer] :bool)
(ffi/defcfn ujs-kx-set-orientation "ujs_kx_set_orientation" [:pointer :pointer] :bool)
(ffi/defcfn ujs-kx-apply "ujs_kx_apply" [:pointer :string :pointer :pointer :bool] :bool)
(ffi/defcfn ujs-kx-get-mass "ujs_kx_get_mass" [:pointer :pointer] :bool)
(ffi/defcfn ujs-kx-set-mass "ujs_kx_set_mass" [:pointer :double] :bool)
(ffi/defcfn ujs-kx-set-damping "ujs_kx_set_damping" [:pointer :double :double] :bool)
(ffi/defcfn ujs-kx-suspend-dynamics "ujs_kx_suspend_dynamics" [:pointer :bool] :bool)
(ffi/defcfn ujs-kx-raycast "ujs_kx_raycast" [:pointer :pointer :pointer :pointer :pointer :pointer] :bool)
(ffi/defcfn ujs-kx-get-parent "ujs_kx_get_parent" [:pointer :pointer] :bool)
(ffi/defcfn ujs-kx-set-parent "ujs_kx_set_parent" [:pointer :pointer] :bool)
(ffi/defcfn ujs-kx-spawn "ujs_kx_spawn" [:string :pointer :double :pointer] :bool)
(ffi/defcfn ujs-kx-remove "ujs_kx_remove" [:pointer] :bool)
(ffi/defcfn ujs-kx-set-camera "ujs_kx_set_camera" [:pointer] :bool)
(ffi/defcfn ujs-kx-set-visible "ujs_kx_set_visible" [:pointer :bool :bool] :bool)
(ffi/defcfn ujs-kx-blender-object "ujs_kx_blender_object" [:pointer :pointer] :bool)
(ffi/defcfn ujs-kx-input "ujs_kx_input" [:string :pointer] :bool)
(ffi/defcfn ujs-kx-mouse "ujs_kx_mouse" [:pointer] :bool)
(ffi/defcfn ujs-kx-set-mouse "ujs_kx_set_mouse" [:double :double] :bool)
(ffi/defcfn ujs-kx-show-mouse "ujs_kx_show_mouse" [:bool] :bool)
(ffi/defcfn ujs-kx-screenshot "ujs_kx_screenshot" [:string] :bool)
(ffi/defcfn ujs-kx-frame-info "ujs_kx_frame_info" [:pointer] :bool)
(ffi/defcfn ujs-kx-canvas-size "ujs_kx_canvas_size" [:pointer :pointer] :bool)

(defn- doubles-in
  "Allocate n doubles holding xs (nil -> null pointer)."
  [xs n]
  (if (nil? xs)
    ffi/null
    (let [b (ffi/alloc (* 8 n))]
      (dotimes [i n] (ffi/write b :double (double (nth xs i)) (* 8 i)))
      b)))

(defn- free-doubles [b] (when-not (ffi/null? b) (ffi/free b)))

(defn- read-doubles [b n] (vec (for [i (range n)] (ffi/read b :double (* 8 i)))))

(defmacro ^:private on-host
  "Run body on the host thread (marshal)."
  [& body]
  `(core/marshal (fn [] ~@body)))

(defn kx-running?
  "Whether a game is running."
  []
  (on-host (ujs-kx-running)))

(defn kx-end-game!
  "End the running game."
  []
  (on-host (or (ujs-kx-end-game) (host-error "end game"))))

(defn kx-vec
  "Read a KX vector: :position :scale :linear-velocity :angular-velocity
  :local-linear-velocity :local-angular-velocity."
  [obj what]
  (on-host
   (let [out (ffi/alloc 24)]
     (try
       (with-ptr buf obj
         (if (ujs-kx-get-vec buf (str/replace (kw-name what) "-" "_") out)
           (read-doubles out 3)
           (host-error (kw-name what))))
       (finally (ffi/free out))))))

(defn kx-set-vec!
  "Write a KX vector (see kx-vec)."
  [obj what xyz]
  (on-host
   (let [in (doubles-in xyz 3)]
     (try
       (with-ptr buf obj
         (or (ujs-kx-set-vec buf (str/replace (kw-name what) "-" "_") in)
             (host-error (kw-name what))))
       (finally (free-doubles in))))))

(defn kx-orientation
  "World orientation as a row-major 3x3 vector of 9 doubles."
  [obj]
  (on-host
   (let [out (ffi/alloc 72)]
     (try
       (with-ptr buf obj
         (if (ujs-kx-get-orientation buf out) (read-doubles out 9) (host-error "orientation")))
       (finally (ffi/free out))))))

(defn kx-set-orientation!
  "Set world orientation from a row-major 3x3 (9 doubles)."
  [obj m9]
  (on-host
   (let [in (doubles-in m9 9)]
     (try
       (with-ptr buf obj (or (ujs-kx-set-orientation buf in) (host-error "orientation")))
       (finally (free-doubles in))))))

(defn kx-apply!
  "Apply :force, :torque or :impulse (at world point `at`) to `obj`."
  [obj what v & {:keys [at local?]}]
  (on-host
   (let [vb (doubles-in v 3) ab (doubles-in at 3)]
     (try
       (with-ptr buf obj
         (or (ujs-kx-apply buf (kw-name what) vb ab (boolean local?)) (host-error (kw-name what))))
       (finally (free-doubles vb) (free-doubles ab))))))

(defn kx-mass [obj]
  (on-host
   (let [out (ffi/alloc 8)]
     (try (with-ptr buf obj (if (ujs-kx-get-mass buf out) (ffi/read out :double) (host-error "mass")))
          (finally (ffi/free out))))))

(defn kx-set-mass! [obj m]
  (on-host (with-ptr buf obj (or (ujs-kx-set-mass buf (double m)) (host-error "mass")))))

(defn kx-set-damping! [obj linear angular]
  (on-host (with-ptr buf obj (or (ujs-kx-set-damping buf (double linear) (double angular)) (host-error "damping")))))

(defn kx-suspend-dynamics! [obj suspend?]
  (on-host (with-ptr buf obj (or (ujs-kx-suspend-dynamics buf (boolean suspend?)) (host-error "dynamics")))))

(defn kx-raycast
  "First physics hit from `from` to `to`, skipping `ignore` (or nil):
  {:object kx-obj :point [x y z] :normal [x y z]} or nil on a miss."
  [from to ignore]
  (on-host
   (let [fb (doubles-in from 3) tb (doubles-in to 3)
         hit (ffi/alloc ptr-size) pt (ffi/alloc 24) nm (ffi/alloc 24)]
     (try
       (with-ptr ib ignore
         (if (ujs-kx-raycast fb tb ib hit pt nm)
           (when-let [o (read-ptr! hit)]
             {:object o :point (read-doubles pt 3) :normal (read-doubles nm 3)})
           (host-error "raycast")))
       (finally (free-doubles fb) (free-doubles tb) (ffi/free hit) (ffi/free pt) (ffi/free nm))))))

(defn kx-parent [obj]
  (on-host
   (let [out (ffi/alloc ptr-size)]
     (try (with-ptr buf obj (if (ujs-kx-get-parent buf out) (read-ptr! out) (host-error "parent")))
          (finally (ffi/free out))))))

(defn kx-set-parent!
  "Parent `obj` to `parent` (nil removes the parent)."
  [obj parent]
  (on-host (with-ptr buf obj (with-ptr pb parent (or (ujs-kx-set-parent buf pb) (host-error "parent"))))))

(defn kx-spawn!
  "Spawn a copy of object `name` (typically one kept in an excluded/hidden
  collection) at `at`'s transform (nil = the original's), living `lifespan`
  seconds (0 = forever)."
  ([name] (kx-spawn! name nil 0))
  ([name at lifespan]
   (on-host
    (let [out (ffi/alloc ptr-size)]
      (try (with-ptr ab at
             (if (ujs-kx-spawn name ab (double lifespan) out) (read-ptr! out) (host-error name)))
           (finally (ffi/free out)))))))

(defn kx-remove! [obj]
  (on-host (with-ptr buf obj (or (ujs-kx-remove buf) (host-error "remove")))))

(defn kx-set-camera! [cam]
  (on-host (with-ptr buf cam (or (ujs-kx-set-camera buf) (host-error "camera")))))

(defn kx-set-visible! [obj visible? recursive?]
  (on-host (with-ptr buf obj (or (ujs-kx-set-visible buf (boolean visible?) (boolean recursive?)) (host-error "visible")))))

(defn kx-blender-object
  "The Blender Object (RNA pointer) a KX object came from: change its lights,
  materials and other data through get/set!/call while the game runs."
  [obj]
  (on-host
   (let [out (ffi/alloc ptr-size)]
     (try (with-ptr buf obj (if (ujs-kx-blender-object buf out) (read-ptr! out) (host-error "blender object")))
          (finally (ffi/free out))))))

(defn kx-input
  "Input state for a bge event name (\"WKEY\", :LEFTSHIFTKEY, \"LEFTMOUSE\"):
  #{:active :activated :released} subset."
  [k]
  (on-host
   (let [out (ffi/alloc 4)]
     (try
       (if (ujs-kx-input (kw-name k) out)
         (let [s (ffi/read out :int32)]
           (cond-> #{}
             (pos? (bit-and s 1)) (conj :active)
             (pos? (bit-and s 2)) (conj :activated)
             (pos? (bit-and s 4)) (conj :released)))
         (host-error (kw-name k)))
       (finally (ffi/free out))))))

(defn kx-mouse
  "Mouse position, normalized [x y] in 0..1."
  []
  (on-host
   (let [out (ffi/alloc 16)]
     (try (if (ujs-kx-mouse out) (read-doubles out 2) (host-error "mouse"))
          (finally (ffi/free out))))))

(defn kx-set-mouse! [x y]
  (on-host (or (ujs-kx-set-mouse (double x) (double y)) (host-error "mouse"))))

(defn kx-show-mouse! [visible?]
  (on-host (or (ujs-kx-show-mouse (boolean visible?)) (host-error "mouse"))))

(defn kx-screenshot!
  "Save the game canvas to `path` (PNG)."
  [path]
  (on-host (or (ujs-kx-screenshot path) (host-error "screenshot"))))

(defn kx-frame-info
  "{:fps average :clock engine-seconds :frame-time last-timestep}."
  []
  (on-host
   (let [out (ffi/alloc 24)]
     (try (if (ujs-kx-frame-info out)
            (let [[f c t] (read-doubles out 3)] {:fps f :clock c :frame-time t})
            (host-error "frame info"))
          (finally (ffi/free out))))))

(defn kx-canvas-size []
  (on-host
   (let [w (ffi/alloc 4) h (ffi/alloc 4)]
     (try (if (ujs-kx-canvas-size w h) [(ffi/read w :int32) (ffi/read h :int32)] (host-error "canvas"))
          (finally (ffi/free w) (ffi/free h))))))
