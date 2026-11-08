(ns upbge-jolt.mock-host
  "Loads the mock host shim (native/mock_shim.c) so the bridge's ujs_*
  bindings resolve in tests. Deliberately not a :jolt/native in deps.edn:
  that would also bind the mock inside the real host. `jolt native` builds it."
  (:require [jolt.ffi :as ffi]))

(ffi/load-library {:darwin "native/libupbge_shim.dylib"
                   :linux "native/libupbge_shim.so"})
