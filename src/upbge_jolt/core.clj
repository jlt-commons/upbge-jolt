(ns upbge-jolt.core
  "The embedded runtime's own machinery: the op queue the host drains from
  its frame loop (uj_tick), and the nREPL server lifecycle (uj_start_repl /
  uj_stop_repl). This namespace must be loadable with NO host shim present —
  it is   what jolt_library_init brings up first."
  (:require [ebb.core :as m]))

;; --- op queue ----------------------------------------------------------------
;;
;; The host calls uj_tick from its frame loop. Ops enqueued from nREPL
;; sessions (jolt threads) run on the host's thread inside tick, in FIFO
;; order. An op that throws is reported and dropped; the queue survives.

(def ^:private queue (atom clojure.lang.PersistentQueue/EMPTY))
(def ^:private ticks (atom 0))
(def ^:private frame-emitters (atom #{}))

(defn enqueue!
  "Queue `f` to run on the host thread at the next tick. Safe from any
  thread."
  [f]
  (swap! queue conj f)
  nil)

(defn- run-op!
  "Run one queued op; a throwing op is reported to stderr and dropped — the
  tick loop and the queue survive it."
  [f]
  (try
    (f)
    (catch Throwable t
      (binding [*out* *err*]
        (println "upbge-jolt: tick op failed:" (ex-message t))))))

(def ^:dynamic *in-tick*
  "True while draining the op queue on the host's tick/frame thread. The host
  calls uj_tick on its own thread; every enqueued op runs under this binding,
  so code that must not marshal again (the bridge's FFI calls) can see it is
  already on the host thread and run inline."
  false)

(defn tick
  "Drain the op queue. Runs on the host's calling thread — the only consumer,
  so peek-then-pop needs no lock (enqueue! is many-producer, tick is
  one-consumer). Ops enqueued during a tick wait for the next one. After the
  drain, notifies every frame emitter with the new tick count."
  []
  (swap! ticks inc)
  (binding [*in-tick* true]
    (loop []
      (when-let [f (peek @queue)]
        (swap! queue pop)
        (run-op! f)
        (recur))))
  (doseq [emit @frame-emitters]
    (emit @ticks))
  nil)

(defn on-host-thread?
  "Whether the current thread is the host's tick/frame thread (inside a
  tick-drained op)."
  []
  *in-tick*)

(defn marshal
  "Run `f` on the host's tick thread and return its value, blocking the caller
  until it runs. Called from the tick thread itself (reentrantly) it runs
  inline. Errors thrown by `f` are re-thrown on the calling thread.

  Contract: the host must be ticking — draining the op queue — for a blocking
  caller to return. If the host is not ticking, the caller blocks until it
  resumes (the editor-side WM timer and the game-side frame loop are the two
  pumps that guarantee this)."
  [f]
  (if *in-tick*
    (f)
    (let [d (m/dfv)]
      (enqueue! (fn []
                  (try (d [:ok (f)])
                       (catch Throwable t (d [:err t])))))
      (let [[tag v] (m/? d)]
        (if (= :ok tag) v (throw v))))))

(defn tick-count
  "Number of ticks run. Exposed for tests and for repl sessions to poll."
  []
  @ticks)

(defn frame-flow
  "An ebb discrete flow emitting the tick count each time `tick` runs (every
  host frame). Each subscription registers an emitter that tick notifies; the
  returned cleanup (run by ebb on cancellation) deregisters it. This is the
  engine-layer seam: drive per-frame logic from the REPL with
  `(m/reduce f nil (frame-flow))`."
  []
  (m/observe
   (fn [emit]
     (swap! frame-emitters conj emit)
     (fn [] (swap! frame-emitters disj emit)))))

(defn pending-ops
  "Ops currently queued. For tests."
  []
  (count @queue))

;; --- eval --------------------------------------------------------------------
;;
;; eval-string is the REPL-side face of the runtime: evaluate a form in the
;; user namespace, return the printed result (or the printed error). Used by
;; the socket-test helper and available to hosts that want a one-shot eval
;; without a full nREPL server.

(defn eval-string
  "Evaluate `s` and return its printed value, or the printed error. Never
  throws."
  [s]
  (try
    (let [v (eval (read-string s))]
      (pr-str v))
    (catch Throwable t
      (str t))))

;; --- nREPL lifecycle -----------------------------------------------------------

(defonce ^:private repl-state (atom nil))

(defn start-repl!
  "Start the embedded nREPL server. `port` 0 asks the OS for a free one.
  Returns the stop fn from jolt.nrepl/start (its meta carries :port). No
  port file is written — embedded mode; the host decides where to surface
  the port."
  ([port] (start-repl! port nil))
  ([port middleware]
   (when-let [running @repl-state]
     (running))
   (require 'jolt.nrepl)
   (let [stop ((ns-resolve 'jolt.nrepl 'start) port middleware {:port-file nil})]
     (reset! repl-state stop)
     stop)))

(defn stop-repl!
  "Stop the running nREPL server, if any."
  []
  (when-let [running @repl-state]
    (running)
    (reset! repl-state nil))
  nil)

(defn eval-via-socket
  "Eval `code` against the running server as an editor client would — an
  nREPL client connect, session, eval, response-values. Test helper."
  [port code]
  (require 'nrepl.core)
  (let [connect (ns-resolve 'nrepl.core 'connect)
        new-session (ns-resolve 'nrepl.core 'new-session)
        message (ns-resolve 'nrepl.core 'message)
        response-values (ns-resolve 'nrepl.core 'response-values)
        close (ns-resolve 'nrepl.core 'close)
        t (connect "127.0.0.1" port)
        s (new-session t)
        resp (message t {:op "eval" :code code :session s})
        vals (response-values resp)]
    (close t)
    vals))
