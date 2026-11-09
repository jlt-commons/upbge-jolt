(ns upbge-jolt.library
  "The C-callable surface of the runtime, for `jolt build --library`. The
  host dlopens libjolt_upbge, calls jolt_library_init, resolves these via
  jolt_lookup, and drives the runtime from its frame loop.

  Threading contract (per jolt's embedding ABI): after init, only the init
  thread may call into the runtime unless an export is :collect-safe. The
  frame-loop pattern — one thread calling uj_tick every frame — reaches a
  safe point on every tick, which is what keeps the collector happy. The
  nREPL server's accept and session threads are jolt's own; scene-touching
  work marshals onto the host's tick thread through core/marshal (an op-queue
  round-trip blocked on an ebb dataflow variable), drained by uj_tick."
  (:require [upbge-jolt.core :as core]
            [upbge-jolt.bridge]     ; bake the bridge into the runtime
            [upbge-jolt.bpy]        ; and the ergonomic REPL layer
            [upbge-jolt.bge]        ; and the game-engine (KX) layer
            [upbge-jolt.game :as game] ; game sessions, per-frame logic, main.clj
            [upbge-jolt.audio]      ; procedural/file audio
            [upbge-jolt.nodes]      ; node-tree building
            [upbge-jolt.boot :as boot] ; host startup (the C++ hook calls uj_boot)
            [jolt.nrepl]            ; static so --library bakes it in
            [jolt.ffi :as ffi]))

;; uj_tick is the frame-loop heartbeat. :collect-safe because the host may
;; call it from the thread that ran jolt_library_init while jolt threads are
;; active; the tick body itself reaches collection safe points.
(ffi/export! "uj_tick" core/tick [] :void :collect-safe)

;; drive game sessions (main.clj autoload, on-frame! handlers) from the tick
(game/install!)

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

;; host startup: the C++ hook (uj_host_start) calls this once the runtime and
;; the server are up, with the UPBGE_JOLT mode ("editor", "play", "headless")
(ffi/export! "uj_boot"
  (fn [mode]
    (try (boot/boot! (ffi/ptr->string mode)) 0
         (catch Throwable t
           (binding [*out* *err*] (println "uj_boot failed:" (ex-message t)))
           -1)))
  ;; :collect-safe: the host calls it from the init thread after
  ;; jolt_library_release_thread, like uj_tick
  [:pointer] :int :collect-safe)

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
