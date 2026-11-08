(ns upbge-jolt.library
  "The C-callable surface of the runtime, for `jolt build --library`. The
  host dlopens libjolt_upbge, calls jolt_library_init, resolves these via
  jolt_lookup, and drives the runtime from its frame loop.

  Threading contract (per jolt's embedding ABI): after init, only the init
  thread may call into the runtime unless an export is :collect-safe. The
  frame-loop pattern — one thread calling uj_tick every frame — reaches a
  safe point on every tick, which is what keeps the collector happy. The
  nREPL server's accept and session threads are jolt's own; they coordinate
  through the op queue and jolt.host/call-on-main-thread."
  (:require [upbge-jolt.core :as core]
            [jolt.nrepl]            ; static so --library bakes it in
            [jolt.ffi :as ffi]))

;; uj_tick is the frame-loop heartbeat. :collect-safe because the host may
;; call it from the thread that ran jolt_library_init while jolt threads are
;; active; the tick body itself reaches collection safe points.
(ffi/export! "uj_tick" core/tick [] :void :collect-safe)

;; returns the port actually bound (the OS pick when passed 0), or -1 when
;; the server failed to start
(ffi/export! "uj_start_repl"
  (fn [port]
    (try
      (let [stop (core/start-repl! port)]
        (:port (meta stop)))
      (catch Throwable t
        (binding [*out* *err*]
          (println "uj_start_repl failed:" (ex-message t)))
        -1)))
  [:int] :int)

;; idempotent, callable whether or not a server ever started
(ffi/export! "uj_stop_repl" (fn [] (core/stop-repl!) 0) [] :int)

;; one-shot eval without a server. Evaluates `code` in the runtime, writes
;; the printed result (NUL-terminated) into `buf` when it fits `cap`, and
;; returns the byte count written (excluding the NUL); -1 = did not fit.
(ffi/export! "uj_eval"
  (fn [code buf cap]
    (let [s (core/eval-string (ffi/ptr->string code))]
      (if (>= (inc (count s)) cap)
        -1
        (do (ffi/write-bytes buf s)
            (ffi/write buf :byte 0 (count s))
            (count s)))))
  [:pointer :pointer :int] :int)

;; host pointer for bridge use: the real shim's ujs_* symbols resolve from
;; the host process at runtime; this export just proves the dylib is alive
(ffi/export! "uj_ping" (fn [] 42) [] :int)
