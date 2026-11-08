(ns upbge-jolt.game
  "Game sessions and per-frame logic, and the game's code stored in the
  .blend file.

  A game's jolt code lives in a Text datablock named \"main.clj\" inside the
  .blend, so saving the scene saves its logic. When a game starts (from the
  viewport, the REPL, or `blender file.blend --python
  host/upbge_jolt_bootstrap_game.py`), main.clj is evaluated before the first
  frame's handlers run. Develop it at the REPL, then store it with
  `set-main!` (or `set-main-file!`) and save the file.

  Handlers registered with `on-frame!` run every game frame on the host
  thread, with the frame's delta time in seconds, and are dropped when the
  game ends, so nothing outlives the KX objects it captured. A handler that
  throws is removed and its error kept in `errors`."
  (:require [ebb.core :as m]
            [upbge-jolt.bridge :as b]
            [upbge-jolt.core :as core]))

(def main-text-name "main.clj")

(defonce ^:private state
  (atom {:running? false
         :frame {}     ; key -> (fn [dt]) while the game runs
         :start {}     ; key -> (fn []) on every game start
         :stop {}      ; key -> (fn []) when the game ends (KX objects are gone)
         :errors []
         :last-ns nil}))

(defn running?
  "Whether a game session is in progress (as seen at the last tick)."
  []
  (:running? @state))

(defn on-frame!
  "Run (f dt) every frame of the current game under `k` (replacing any handler
  with that key). Cleared when the game ends."
  [k f]
  (swap! state assoc-in [:frame k] f)
  k)

(defn off-frame!
  [k]
  (swap! state update :frame dissoc k)
  nil)

(defn on-start!
  "Run (f) when each game starts, after main.clj loads. Persists across runs."
  [k f]
  (swap! state assoc-in [:start k] f)
  k)

(defn on-stop!
  "Run (f) after each game ends. KX objects are gone by then; use it to reset
  jolt-side state."
  [k f]
  (swap! state assoc-in [:stop k] f)
  k)

(defn errors
  "Errors from game handlers and main.clj, oldest first."
  []
  (:errors @state))

(defn clear-errors! [] (swap! state assoc :errors []) nil)

(defn- report! [where k t]
  (binding [*out* *err*]
    (println "upbge-jolt.game:" where k "failed:" (ex-message t)))
  (swap! state update :errors conj {:where where :key k :message (ex-message t) :error t}))

;; --- main.clj in the .blend --------------------------------------------------

(defn main-text
  "The \"main.clj\" Text datablock, or nil."
  []
  (b/lookup (b/data) "texts" main-text-name))

(defn main-source
  "The game's stored code, or nil."
  []
  (some-> (main-text) (b/call "as_string")))

(defn set-main!
  "Store `code` as the game's main.clj (creating the Text if needed). Save the
  .blend to keep it."
  [code]
  (let [t (or (main-text)
              (b/call (b/get (b/data) "texts") "new" {:name main-text-name}))]
    (b/call t "from_string" {:text code})
    t))

(defn set-main-file!
  "Store the contents of the source file at `path` as main.clj."
  [path]
  (set-main! (slurp path)))

(defn load-main!
  "Evaluate the stored main.clj, if any. Returns the last value."
  []
  (when-let [src (main-source)]
    (binding [*ns* (create-ns 'user)]
      (load-string src))))

;; --- control -----------------------------------------------------------------

(defn start!
  "Start the game in the 3D viewport. Returns immediately; the game runs on
  the host thread until it ends (Esc, or `stop!`)."
  []
  (core/enqueue! #(b/op! "view3d.game_start" {} {:area "VIEW_3D"}))
  nil)

(defn stop!
  "End the running game (as Esc does)."
  []
  (when (b/kx-running?)
    (core/enqueue! #(b/kx-end-game!)))
  nil)

;; --- the per-tick driver -----------------------------------------------------

(defn- run-each! [where m & args]
  (doseq [[k f] m]
    (try (apply f args)
         (catch Throwable t
           (report! where k t)
           (when (= where :frame) (off-frame! k))))))

(defn- game-started! []
  (swap! state assoc :running? true :last-ns (System/nanoTime))
  (try (load-main!)
       (catch Throwable t (report! :main main-text-name t)))
  (run-each! :start (:start @state)))

(defn- game-ended! []
  (swap! state assoc :running? false :frame {})
  (run-each! :stop (:stop @state)))

(defn- step!
  "Called every host tick, on the host thread."
  []
  (let [now-running (b/kx-running?)
        was (:running? @state)]
    (cond
      (and now-running (not was)) (game-started!)
      (and was (not now-running)) (game-ended!))
    (when (and now-running (:running? @state))
      (let [now (System/nanoTime)
            dt (/ (- now (:last-ns @state)) 1e9)]
        (swap! state assoc :last-ns now)
        (run-each! :frame (:frame @state) dt)))))

(defonce ^:private driver (atom nil))

(defn install!
  "Hook the game driver into the host tick (idempotent). The library does
  this at startup."
  []
  (when-not @driver
    (reset! driver
            ((m/reduce (fn [_ _] (step!) nil) nil (core/frame-flow))
             (fn [_] nil)
             (fn [t] (report! :driver :frame-flow t)))))
  nil)
