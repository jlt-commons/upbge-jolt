(ns upbge-jolt.boot-test
  "The live-view watchdog's decision logic (pure: no host needed)."
  (:require [clojure.test :refer [deftest is testing]]
            [upbge-jolt.boot :as boot]))

(def s 1e9) ; ns per second

(defn- run [gaps & {:keys [in-game?] :or {in-game? false}}]
  (reduce (fn [[st acts] gap]
            (let [[st' act] (boot/watchdog-step st gap in-game?)]
              [st' (conj acts act)]))
          [boot/watchdog-init []]
          gaps))

(deftest watchdog
  (testing "fast frames never trip it"
    (is (every? nil? (second (run (repeat 100 (* 0.016 s)))))))
  (testing "three slow frames in a row fall back, once"
    (let [[_ acts] (run [(* 3 s) (* 3 s) (* 3 s) (* 3 s)])]
      (is (= [nil nil :fallback nil] acts))))
  (testing "a fast frame resets the count (shader compiles at load are bursty)"
    (let [[_ acts] (run [(* 3 s) (* 3 s) (* 0.016 s) (* 3 s) (* 3 s)])]
      (is (every? nil? acts))))
  (testing "slow game frames are the game's business"
    (is (every? nil? (second (run (repeat 5 (* 3 s)) :in-game? true))))))
