(ns upbge-jolt.bridge-test
  "Property/collection marshalling against the mock host shim
  (native/mock_shim.c, loaded as native/libupbge_shim.dylib)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
    (testing "enum set with a non-number throws a jolt-side error"
      (is (thrown-with-msg?
            Exception #"numeric value"
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
        (is (= 6 (count props)))
        (is (= :string (:type (by-name "name"))))
        (is (= :pointer (:type (by-name "data"))))
        (is (= "Mesh" (:item-type (by-name "data"))))))))
