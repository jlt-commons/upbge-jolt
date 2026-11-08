(ns upbge-jolt.core
  "The embedded runtime's own machinery: the op queue the host drains from
  its frame loop (uj_tick), and the nREPL server lifecycle (uj_start_repl /
  uj_stop_repl). This namespace must be loadable with NO host shim present —
  it is what jolt_library_init brings up first."
  (:require [clojure.string :as str]))

;; --- op queue ----------------------------------------------------------------
;;
;; The host calls uj_tick from its frame loop. Ops enqueued from nREPL
;; sessions (jolt threads) run on the host's thread inside tick, in FIFO
;; order. An op that throws is reported and dropped; the queue survives.

(def ^:private queue (atom clojure.lang.PersistentQueue/EMPTY))
(def ^:private ticks (atom 0))

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

(defn tick
  "Drain the op queue. Runs on the host's calling thread — the only consumer,
  so peek-then-pop needs no lock (enqueue! is many-producer, tick is
  one-consumer). Ops enqueued during a tick wait for the next one."
  []
  (swap! ticks inc)
  (loop []
    (when-let [f (peek @queue)]
      (swap! queue pop)
      (run-op! f)
      (recur)))
  nil)

(defn tick-count
  "Number of ticks run. Exposed for tests and for repl sessions to poll."
  []
  @ticks)

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
