(ns upbge-jolt.tick-pump
  "A background thread that drains the op queue, standing in for the host's
  frame loop. The bridge marshals its RNA work onto this thread, so the full
  marshal -> tick -> ffi -> result path is exercised headlessly against the
  mock shim."
  (:require [upbge-jolt.core :as c]))

(defn start!
  "Start a tick-pump thread; returns a stop fn that stops it and joins."
  []
  (let [stop (atom false)
        t    (Thread. (fn [] (while (not @stop)
                               (c/tick)
                               (Thread/sleep 1))))]
    (.start t)
    (fn [] (reset! stop true) (.join t))))

(defmacro with-tick-pump
  "Run `body` with a tick-pump thread draining the op queue for its duration."
  [& body]
  `(let [stop# (start!)]
     (try ~@body
          (finally (stop#)))))

(defn fixture
  "clojure.test fixture: run `f` with a tick-pump thread."
  [f]
  (let [stop (start!)]
    (try (f) (finally (stop)))))
