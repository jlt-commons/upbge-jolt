(ns upbge-jolt.bridge-test
  "Property/collection marshalling against the mock host shim
  (native/mock_shim.c, loaded as native/libupbge_shim.dylib)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [jolt.ffi :as ffi]
            [upbge-jolt.bridge :as b]
            [upbge-jolt.tick-pump :as pump]))

(defn reset-mock [f]
  (#'b/reset-host!)
  (f))

(use-fixtures :once pump/fixture)
(use-fixtures :each reset-mock)

(deftest root-pointer
  (testing "the root pointer resolves"
    (let [r (b/root)]
      (is (map? r))
      (is (pos? (:data r)) "data is a host address"))))

(deftest scalar-reads
  (let [cube (first (b/coll (b/root) "objects"))]
    (testing "string"
      (is (= "Cube" (b/get cube "name"))))
    (testing "bool"
      (is (false? (b/get cube "hide_viewport"))))
    (testing "int"
      (is (= 7 (b/get cube "pass_index"))))
    (testing "fixed float array"
      (is (= [1.0 2.0 3.0] (b/get cube "location"))))
    (testing "enum carries value and idname"
      (is (= {:value 1 :idname "EDIT"} (b/get cube "mode"))))))

(deftest scalar-writes
  (let [cube (first (b/coll (b/root) "objects"))]
    (testing "int round-trip"
      (b/set! cube "pass_index" 42)
      (is (= 42 (b/get cube "pass_index"))))
    (testing "bool round-trip"
      (b/set! cube "hide_viewport" true)
      (is (true? (b/get cube "hide_viewport"))))
    (testing "float round-trip"
      (let [lamp (second (b/coll (b/root) "objects"))]
        (b/set! lamp "energy" 250.5)
        (is (= 250.5 (b/get lamp "energy")))))
    (testing "string round-trip"
      (b/set! cube "name" "CubeRenamed")
      (is (= "CubeRenamed" (b/get cube "name"))))
    (testing "fixed array round-trip"
      (b/set! cube "location" [10.0 20.0 30.0])
      (is (= [10.0 20.0 30.0] (b/get cube "location"))))
    (testing "enum round-trip by value"
      (b/set! cube "mode" 3)
      (is (= 3 (:value (b/get cube "mode")))))))

(deftest type-errors
  (let [cube (first (b/coll (b/root) "objects"))]
    (testing "enum set with an unknown identifier names it"
      (is (thrown-with-msg?
            Exception #"no enum item not-a-number"
            (b/set! cube "mode" :not-a-number))))
    (testing "set on unknown property throws with the host message"
      (is (thrown-with-msg?
            Exception #"no such property"
            (b/set! cube "nonexistent" 1))))
    (testing "get on unknown property throws"
      (is (thrown-with-msg?
            Exception #"no such property"
            (b/get cube "nonexistent"))))))

(deftest pointer-properties
  (let [cube (first (b/coll (b/root) "objects"))]
    (testing "pointer read yields a usable pointer"
      (let [mesh (b/get cube "data")]
        (is (map? mesh))
        (is (pos? (:data mesh)))
        (testing "dynamic float array through the nested pointer"
          (is (= 9 (count (b/get mesh "vertices"))))
          (is (every? zero? (take 3 (b/get mesh "vertices")))))))
    (testing "dynamic array write round-trips"
      (let [mesh (b/get cube "data")]
        (b/set! mesh "vertices" (vec (repeat 9 0.5)))
        (is (= (vec (repeat 9 0.5)) (b/get mesh "vertices")))))
    (testing "null pointer property reads as nil and accepts nil writes"
      (let [mesh (b/get cube "data")]
        (b/set! cube "data" nil)
        (is (nil? (b/get cube "data")))
        (b/set! cube "data" mesh)
        (is (map? (b/get cube "data")))))))

(deftest collections
  (let [root (b/root)]
    (testing "length"
      (is (= 2 (b/coll-count root "objects"))))
    (testing "iteration yields each member"
      (is (= ["Cube" "Lamp"]
             (map #(b/get % "name") (b/coll root "objects")))))
    (testing "unknown collection errors"
      (is (thrown-with-msg? Exception #"not a collection" (b/coll root "things"))))))

(deftest introspection
  (let [cube (first (b/coll (b/root) "objects"))]
    (testing "prop-find describes a property"
      (is (= {:name "location" :type :float :array-len 3 :item-type nil}
             (b/prop-find cube "location"))))
    (testing "props lists every property with its type"
      (let [props (b/props cube)
            by-name (into {} (map (juxt :name identity) props))]
        (is (= 7 (count props)))
        (is (= :string (:type (by-name "name"))))
        (is (= :pointer (:type (by-name "data"))))
        (is (= "Mesh" (:item-type (by-name "data"))))))))

(deftest id-new
  (testing "id-new makes a mesh and an object"
    (let [mesh (b/id-new "Mesh" "Tri")]
      (is (map? mesh))
      (is (= "Tri" (b/get mesh "name"))))
    (let [obj (b/id-new "Object" "Thing")]
      (is (= "Thing" (b/get obj "name")))
      (is (= [0.0 0.0 0.0] (b/get obj "location")))
      (is (nil? (b/get obj "data")))))
  (testing "an Object from id-new is an Empty: like Blender, it ignores a data assignment"
    (let [obj (b/id-new "Object" "Hollow")
          mesh (b/id-new "Mesh" "HollowMesh")]
      (b/set! obj "data" mesh)
      (is (= "EMPTY" (b/get obj "type")))
      (is (nil? (b/get obj "data")))))
  (testing "id-new rejects an unknown type"
    (is (thrown-with-msg? Exception #"unknown id type"
          (b/id-new "Nonsense" "x")))))

(deftest object-new
  (testing "object-new types the object from its data, like bpy.data.objects.new"
    (let [mesh (b/id-new "Mesh" "Body")
          obj (b/object-new "Body" mesh)]
      (is (= "Body" (b/get obj "name")))
      (is (= "MESH" (b/get obj "type")))
      (is (= "Body" (b/get (b/get obj "data") "name")))))
  (testing "nil data makes an Empty"
    (let [obj (b/object-new "Marker" nil)]
      (is (= "EMPTY" (b/get obj "type")))
      (is (nil? (b/get obj "data"))))))

(deftest mesh-from-pydata
  (testing "builds geometry from verts and faces"
    (let [mesh (b/id-new "Mesh" "Quad")]
      (b/mesh-from-pydata! mesh [[0 0 0] [1 0 0] [0 1 0] [1 1 0]]
                            [[0 1 2] [1 3 2]])
      (is (= [0.0 0.0 0.0 1.0 0.0 0.0 0.0 1.0 0.0 1.0 1.0 0.0]
             (b/get mesh "vertices")))))
  (testing "rejects out-of-range face indices"
    (let [mesh (b/id-new "Mesh" "Bad")]
      (is (thrown-with-msg? Exception #"face index out of range"
            (b/mesh-from-pydata! mesh [[0 0 0] [1 0 0]] [[0 1 5]]))))))

(deftest scene-graph-mutation
  (let [scene (b/root)]
    (testing "coll-add!/coll-remove! manage the scene graph"
      (let [obj (b/id-new "Object" "Extra")]
        (b/coll-add! scene "objects" obj)
        (is (= ["Cube" "Lamp" "Extra"]
               (map #(b/get % "name") (b/coll scene "objects"))))
        (b/coll-remove! scene "objects" obj)
        (is (= ["Cube" "Lamp"]
               (map #(b/get % "name") (b/coll scene "objects"))))))
    (testing "coll-add! is idempotent"
      (let [obj (b/id-new "Object" "Dup")]
        (b/coll-add! scene "objects" obj)
        (b/coll-add! scene "objects" obj)
        (is (= 3 (b/coll-count scene "objects")))))))

(deftest operators
  (let [scene (b/root)]
    (testing "operator! runs a named operator and mutates the scene"
      (is (true? (b/operator! "mesh.primitive_cube_add")))
      (is (= ["Cube" "Lamp" "Cube"]
             (map #(b/get % "name") (b/coll scene "objects")))))
    (testing "operator! rejects an unknown operator"
      (is (thrown-with-msg? Exception #"unknown operator"
            (b/operator! "nonsense.op"))))
    (testing "a no-op operator returns true"
      (is (true? (b/operator! "object.select_all"))))))

;; --- round 1: bpy.data/context, operators with args, function calls,
;; lookup, bulk access, int arrays, enums by identifier

(defn- cube [] (first (b/coll (b/root) "objects")))

(deftest data-and-context-roots
  (testing "bpy.data and bpy.context resolve"
    (is (= "" (b/get (b/data) "filepath")))
    (is (= (:data (b/root)) (:data (b/get (b/context) "scene"))))))

(deftest operators-with-arguments
  (testing "op! passes typed properties"
    (is (true? (b/op! "mesh.primitive_cube_add" {:size 2.0 :location [4 5 6]})))
    (is (= [4.0 5.0 6.0] (b/get (last (b/coll (b/root) "objects")) "location"))))
  (testing "op! runs in an editor area"
    (is (true? (b/op! "mesh.primitive_cube_add" {} {:area "VIEW_3D"}))))
  (testing "op! reports a missing area"
    (is (thrown-with-msg? Exception #"no NODE_EDITOR area"
          (b/op! "mesh.primitive_cube_add" {} {:area :NODE_EDITOR}))))
  (testing "op! rejects an unknown property"
    (is (thrown-with-msg? Exception #"no such property: bogus"
          (b/op! "mesh.primitive_cube_add" {:bogus 1})))))

(deftest function-calls
  (let [objects (b/get (b/root) "objects")
        obj (b/id-new "Object" "Linked")]
    (testing "a collection's function struct takes calls"
      (is (nil? (b/call objects "link" {:object obj})))
      (is (= ["Cube" "Lamp" "Linked"] (mapv #(b/get % "name") (b/coll (b/root) "objects")))))
    (testing "an unknown function is reported"
      (is (thrown-with-msg? Exception #"has no function nope"
            (b/call objects "nope" {}))))))

(deftest collection-lookup
  (testing "by name and by index"
    (is (= "Lamp" (b/get (b/lookup (b/root) "objects" "Lamp") "name")))
    (is (= "Cube" (b/get (b/lookup (b/root) "objects" 0) "name")))
    (is (nil? (b/lookup (b/root) "objects" "Missing")))))

(deftest bulk-foreach
  (let [mesh (b/get (cube) "data")]
    (testing "foreach-get reads every item's attribute"
      (is (= [0.0 0.0 0.0 1.0 0.0 0.0 0.0 1.0 0.0]
             (vec (b/foreach-get mesh "vertices" "co" 9)))))
    (testing "foreach-set! writes them"
      (b/foreach-set! mesh "vertices" "co" [1 1 1 2 2 2 3 3 3])
      (is (= [1.0 1.0 1.0 2.0 2.0 2.0 3.0 3.0 3.0]
             (vec (b/foreach-get mesh "vertices" "co" 9)))))
    (testing "a length mismatch is an error"
      (is (thrown? Exception (b/foreach-get mesh "vertices" "co" 4))))))

(deftest int-and-bool-arrays
  (testing "bool arrays read and write as vectors"
    (is (= [false false false] (b/get (cube) "lock_location")))
    (b/set! (cube) "lock_location" [true false true])
    (is (= [true false true] (b/get (cube) "lock_location")))))

(deftest enums-by-identifier
  (testing "set! takes an enum identifier"
    (b/set! (cube) "mode" "SCULPT")
    (is (= {:value 2 :idname "SCULPT"} (b/get (cube) "mode")))
    (b/set! (cube) "mode" :OBJECT)
    (is (= "OBJECT" (:idname (b/get (cube) "mode")))))
  (testing "an unknown identifier is reported"
    (is (thrown-with-msg? Exception #"no enum item NOPE"
          (b/set! (cube) "mode" "NOPE")))))

(ffi/defcfn bump-generation! "ujs_test_bump_generation" [] :void)

(deftest stale-pointers
  (testing "a pointer from before a file load / undo / game run is refused"
    (let [cube (b/lookup (b/root) "objects" "Cube")]
      (is (= "Cube" (b/get cube "name")))
      (bump-generation!)
      (is (thrown-with-msg? Exception #"stale pointer"
            (b/get cube "name")))
      (testing "looking it up again works"
        (is (= "Cube" (b/get (b/lookup (b/root) "objects" "Cube") "name")))))))
