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

(deftest runtime-library-lives-under-native
  ;; `jolt lib` writes the runtime (and jolt's .build dir beside it) into
  ;; native/, and the launch and release scripts look for it there.
  (let [lib-task (pr-str (get-in (read-string (slurp "deps.edn")) [:tasks 'lib]))]
    (is (re-find #"\"native/libjolt_upbge\"" lib-task))
    (is (not (re-find #"\"libjolt_upbge\"" lib-task))))
  (doseq [script ["host/run.sh" "host/release.sh"]]
    (is (re-find #"native/libjolt_upbge" (slurp script)) script)))
