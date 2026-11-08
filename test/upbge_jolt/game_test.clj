(ns upbge-jolt.game-test
  "Game sessions: per-frame handlers, start/stop hooks, error isolation,
  against the mock shim's game-running flag."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [jolt.ffi :as ffi]
            [upbge-jolt.core :as core]
            [upbge-jolt.game :as game]))

(ffi/defcfn set-running! "ujs_test_set_kx_running" [:bool] :void)

(defn- tick-n [n] (dotimes [_ n] (core/tick)))

(defn- fresh [f]
  (game/install!)
  (set-running! false)
  (tick-n 1)
  (game/clear-errors!)
  (f)
  (set-running! false)
  (tick-n 1))

(use-fixtures :each fresh)

(deftest frame-handlers-run-while-running
  (let [dts (atom [])]
    (set-running! true)
    (tick-n 1)
    (is (game/running?) "the session starts on the first tick that sees a game")
    (game/on-frame! :t (fn [dt] (swap! dts conj dt)))
    (tick-n 3)
    (is (= 3 (count @dts)))
    (is (every? #(and (number? %) (>= % 0)) @dts) "handlers get a delta time")
    (testing "ending the game drops frame handlers"
      (set-running! false)
      (tick-n 1)
      (is (not (game/running?)))
      (set-running! true)
      (tick-n 2)
      (is (= 3 (count @dts)) "the old handler does not survive into a new run"))))

(deftest start-and-stop-hooks
  (let [log (atom [])]
    (game/on-start! :log #(swap! log conj :start))
    (game/on-stop! :log #(swap! log conj :stop))
    (set-running! true) (tick-n 2)
    (set-running! false) (tick-n 2)
    (set-running! true) (tick-n 1)
    (is (= [:start :stop :start] @log))
    (swap! @#'game/state update :start dissoc :log)
    (swap! @#'game/state update :stop dissoc :log)))

(deftest failing-handler-is-isolated
  (let [ok (atom 0)]
    (set-running! true) (tick-n 1)
    (game/on-frame! :bad (fn [_] (throw (ex-info "boom" {}))))
    (game/on-frame! :good (fn [_] (swap! ok inc)))
    (tick-n 3)
    (is (= 3 @ok) "other handlers keep running")
    (is (= [:bad] (mapv :key (game/errors))) "the failure is recorded once")
    (is (= "boom" (:message (first (game/errors)))))))
