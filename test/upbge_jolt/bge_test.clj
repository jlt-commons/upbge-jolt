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
          (bge/add-filter! 0 :glow))))
  (testing "motion blur is not offered: UPBGE has no motion-blur filter"
    (is (thrown-with-msg? Exception #"unknown filter type"
          (bge/add-filter! 0 :motion-blur)))))

(deftest mouse-look-step
  (let [step (fn [st pos warped?] (bge/mouse-look-step st pos warped?))]
    (testing "the first frame only establishes a reference"
      (is (= [0.0 0.0] (second (step nil [0.7 0.4] true)))))
    (testing "after a warp that took effect, movement is measured from the centre"
      (let [[st _] (step nil [0.5 0.5] true)
            [st d] (step st [0.6 0.5] true)]
        (is (= [0.09999999999999998 0.0] d))
        (testing "and a still mouse reads as no movement"
          (is (= [0.0 0.0] (second (step st [0.5 0.5] true)))))))
    (testing "when warps are ignored (window not focused) a still mouse does not spin"
      (let [[st _] (step nil [0.8 0.5] false)
            [st d1] (step st [0.8 0.5] false)
            [_ d2] (step st [0.8 0.5] false)]
        (is (= [0.0 0.0] d1))
        (is (= [0.0 0.0] d2))))
    (testing "real movement still registers when warps are ignored"
      (let [[st _] (step nil [0.8 0.5] false)
            [_ d] (step st [0.85 0.5] false)]
        (is (< 0.049 (first d) 0.051))))))
