(ns upbge-jolt.packaging-test
  "Guards on how the runtime is packaged for the real host."
  (:require [clojure.test :refer [deftest is]]))

(deftest mock-shim-not-a-project-native
  ;; A declared :jolt/native resolves its symbols ahead of the process
  ;; namespace, so declaring the mock shim project-wide makes the library
  ;; built for UPBGE bind the mock's ujs_* instead of the real RNA shim
  ;; compiled into the Blender binary. The tests load the mock themselves.
  (let [deps (read-string (slurp "deps.edn"))]
    (is (not-any? #(re-find #"libupbge_shim" (pr-str %))
                  (:jolt/native deps)))))
