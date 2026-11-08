(ns upbge-jolt.bge-test
  "The KX (running-game) surface against the mock shim."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [upbge-jolt.bge :as bge]
            [upbge-jolt.bridge :as b]
            [upbge-jolt.tick-pump :as pump]))

(defn reset-mock [f]
  (b/reset-host!)
  (f))

(use-fixtures :once pump/fixture)
(use-fixtures :each reset-mock)

(deftest kx-objects
  (testing "objects lists the running game's KX objects by name"
    (is (= ["GameCube" "GameSphere"] (map bge/name (bge/objects)))))
  (testing "object lookup by name"
    (is (= "GameCube" (bge/name (bge/object "GameCube"))))
    (is (nil? (bge/object "Nope")))))

(deftest kx-transforms
  (let [cube (bge/object "GameCube")]
    (testing "world position"
      (is (= [1.0 2.0 3.0] (bge/position cube))))
    (testing "move! round-trips"
      (bge/move! cube [9 8 7])
      (is (= [9.0 8.0 7.0] (bge/position cube))))))

(deftest filter-type-validation
  (testing "an unknown filter type is rejected before reaching the engine"
    (is (thrown-with-msg? Exception #"unknown filter type"
          (bge/add-filter! 0 :glow)))))
