(ns upbge-jolt.core-test
  "Op queue, tick, eval, and nREPL lifecycle."
  (:require [clojure.test :refer [deftest is testing]]
            [ebb.core :as m]
            [upbge-jolt.core :as c]
            [upbge-jolt.tick-pump :as pump]))

(deftest queue-and-tick
  (testing "an enqueued op runs on tick"
    (let [a (atom nil)]
      (c/enqueue! #(reset! a :ran))
      (is (nil? @a) "not yet")
      (c/tick)
      (is (= :ran @a))))
  (testing "tick drains everything queued before it"
    (let [a (atom [])]
      (c/enqueue! #(swap! a conj 1))
      (c/enqueue! #(swap! a conj 2))
      (c/tick)
      (is (= [1 2] @a))))
  (testing "ops queued during a tick drain within the same tick (frame-loop
  semantics: the frame's own enqueues run before the frame ends)"
    (let [a (atom [])]
      (c/enqueue! (fn [] (swap! a conj :first) (c/enqueue! #(swap! a conj :second))))
      (c/tick)
      (is (= [:first :second] @a))))
  (testing "a throwing op does not kill the tick or the queue"
    (let [a (atom nil)]
      (c/enqueue! (fn [] (throw (ex-info "boom" {}))))
      (c/enqueue! #(reset! a :survived))
      (c/tick)
      (is (= :survived @a)))))

(deftest eval-string
  (testing "eval-string evaluates and returns the printed value"
    (is (= "3" (c/eval-string "(+ 1 2)")))
    (is (= "nil" (c/eval-string "(println :hi)"))))
  (testing "eval-string reports errors as text, does not throw"
    (is (re-find #"unable to resolve|no such var|Unable to resolve"
                 (c/eval-string "no-such-fn-here")))))

(deftest marshal
  (pump/with-tick-pump
    (testing "marshal runs on the tick thread and returns its value"
      (is (= 42 (c/marshal (fn [] 42)))))
    (testing "on-host-thread? is true inside a marshalled op, false outside"
      (is (true? (c/marshal c/on-host-thread?)))
      (is (false? (c/on-host-thread?))))
    (testing "marshal is reentrant: a marshalled op that marshals again runs inline"
      (is (= :nested (c/marshal (fn [] (c/marshal (fn [] :nested)))))))
    (testing "errors propagate to the caller"
      (is (thrown-with-msg? Exception #"boom"
            (c/marshal (fn [] (throw (ex-info "boom" {})))))))))

(deftest frame-flow
  (let [acc (atom [])
        cancel ((m/reduce (fn [_ n] (swap! acc conj n) nil) nil (c/frame-flow))
                (fn [_] nil) (fn [_] nil))]
    (c/tick)
    (c/tick)
    (c/tick)
    (is (= 3 (count @acc)) "each tick emits one frame event")
    (is (= [1 2 3] (map #(- % (dec (first @acc))) @acc)) "values are consecutive tick counts")
    (cancel)))

(deftest repl-lifecycle
  (let [stop (c/start-repl! 0)]
    (testing "stop fn carries the bound port, no port file in embedded mode"
      (is (fn? stop))
      (is (pos? (:port (meta stop))))
      (is (nil? (:port-file (meta stop)))))
    (testing "an editor-style client can eval through the socket"
      (let [res (c/eval-via-socket (:port (meta stop)) "(+ 1 2)")]
        (is (= [3] res))))
    (testing "the runtime is reachable from the session"
      (let [res (c/eval-via-socket (:port (meta stop)) "(upbge-jolt.core/pending-ops)")]
        (is (= [0] res))))
    (stop)
    (testing "port is free after stop; a new server can bind it"
      (let [stop2 (c/start-repl! (:port (meta stop)))]
        (is (= (:port (meta stop)) (:port (meta stop2))))
        (stop2)))))
