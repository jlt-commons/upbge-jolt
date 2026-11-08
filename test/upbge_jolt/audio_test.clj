(ns upbge-jolt.audio-test
  "The audio surface against the mock shim (which plays nothing but tracks
  handle state)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [jolt.ffi :as ffi]
            [upbge-jolt.audio :as audio]
            [upbge-jolt.tick-pump :as pump]))

(use-fixtures :once pump/fixture)

(ffi/defcfn handle-state* "ujs_test_handle_state" [:pointer :pointer] :void)

(defn- handle-state [h]
  (let [out (ffi/alloc 24)]
    (try (handle-state* (::audio/handle h) out)
         (mapv #(ffi/read out :double (* 8 %)) (range 3))
         (finally (ffi/free out)))))

(deftest build-and-drive-a-sound
  (let [s (audio/lowpass (audio/tone :sawtooth 55) 500 1)
        h (audio/play s {:loop? true :volume 0.3})]
    (testing "play applies loop and initial volume"
      (is (= [1.0 0.3 -1.0] (handle-state h))))
    (testing "pitch and volume are driven per call"
      (audio/pitch! h 1.8)
      (audio/volume! h 0.5)
      (is (= [1.8 0.5 -1.0] (handle-state h))))
    (audio/stop! h)
    (audio/free! s)))

(deftest errors-are-reported
  (testing "an unknown wave names the choices"
    (is (thrown-with-msg? Exception #"unknown wave buzz"
          (audio/tone :buzz 100))))
  (testing "noise needs a positive length"
    (is (thrown-with-msg? Exception #"positive"
          (audio/noise 0)))))
