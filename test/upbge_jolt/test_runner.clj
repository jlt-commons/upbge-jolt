(ns upbge-jolt.test-runner
  "Entry point for `jolt -M:test`."
  (:require [clojure.test :as t]
            [upbge-jolt.core-test]
            [upbge-jolt.bridge-test]
            [upbge-jolt.bpy-test]
            [upbge-jolt.bge-test]))

(defn -main [& _]
  (let [results (t/run-tests 'upbge-jolt.core-test
                             'upbge-jolt.bridge-test
                             'upbge-jolt.bpy-test
                             'upbge-jolt.bge-test)
        failed (+ (:fail results 0) (:error results 0))]
    (println (format "\n%d tests, %d passed, %d failed"
                     (:test results 0) (:pass results 0) failed))
    (when (pos? failed) (System/exit 1))))
