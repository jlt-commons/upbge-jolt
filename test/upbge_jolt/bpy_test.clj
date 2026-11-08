(ns upbge-jolt.bpy-test
  "The ergonomic REPL surface over the bridge: keyword access, keyword
  lookup, threading helpers. Against the mock shim."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [upbge-jolt.bpy :as bpy]
            [upbge-jolt.bridge :as b]
            [upbge-jolt.tick-pump :as pump]))

(defn reset-mock [f]
  (b/reset-host!)
  (f))

(use-fixtures :once pump/fixture)
(use-fixtures :each reset-mock)

(deftest objects-and-scene
  (testing "scene objects list by name"
    (is (= ["Cube" "Lamp"] (map bpy/name-of (bpy/objects)))))
  (testing "object lookup by name"
    (is (= "Cube" (bpy/name-of (bpy/object "Cube"))))
    (is (nil? (bpy/object "Nope"))))
  (testing "active-object style accessors"
    (is (map? (bpy/scene)))
    (is (= ["Cube" "Lamp"] (mapv bpy/name-of (bpy/objects))))))

(deftest keyword-property-access
  (let [cube (bpy/object "Cube")]
    (testing "get by keyword"
      (is (= "Cube" (bpy/get cube :name)))
      (is (= [1.0 2.0 3.0] (bpy/get cube :location)))
      (is (= 7 (bpy/get cube :pass_index))))
    (testing "set by keyword"
      (bpy/put! cube :location [9 8 7])
      (is (= [9.0 8.0 7.0] (bpy/get cube :location))))
    (testing "nested pointer traversal"
      (is (= 9 (count (bpy/get-in cube [:data :vertices])))))))

(deftest transform-helpers
  (let [cube (bpy/object "Cube")]
    (testing "world position vector"
      (is (= [1.0 2.0 3.0] (bpy/pos cube))))
    (testing "move! and its round-trip"
      (bpy/move! cube [5 6 7])
      (is (= [5.0 6.0 7.0] (bpy/pos cube))))
    (testing "translate! adds"
      (bpy/translate! cube [1 1 1])
      (is (= [6.0 7.0 8.0] (bpy/pos cube))))))
