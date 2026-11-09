(ns upbge-jolt.boot
  "Host startup, called by the C++ hook (uj_host_start, through the uj_boot
  export) once the runtime and the nREPL server are up. Replaces the old
  Python bootstraps. Modes come from UPBGE_JOLT:

    editor    the live viewport: EEVEE Rendered through the scene camera
              (UPBGE_JOLT_VIEW = RENDERED, MATERIAL, SOLID or KEEP)
    play      the editor setup, then the game starts
    headless  nothing to set up (no window)

  Work that needs the window drawn first is deferred a few ticks.

  A watchdog keeps a heavy scene from locking up the editor: the REPL is
  served between viewport frames, so when Rendered frames repeatedly take
  seconds (outside a game) it drops the viewport to Solid and says why.
  UPBGE_JOLT_WATCHDOG=0 turns it off."
  (:require [upbge-jolt.bridge :as b]
            [upbge-jolt.bpy :as bpy]
            [upbge-jolt.core :as core]
            [upbge-jolt.game :as game]))

(defn- report [what t]
  (binding [*out* *err*]
    (println "upbge-jolt.boot:" what "failed:" (ex-message t))))

(defn live-view!
  "Show REPL work as it happens: the configured shading, through the scene
  camera when there is one."
  []
  (let [shading (or (System/getenv "UPBGE_JOLT_VIEW") "RENDERED")]
    (when-not (= "KEEP" shading)
      (bpy/viewport! (cond-> {:shading shading}
                       (b/get (b/root) "camera") (assoc :camera? true))))))

;; --- live-view watchdog ------------------------------------------------------

(def slow-frame-ns
  "An editor frame slower than this counts as slow (2 s)."
  2e9)

(def slow-frames-to-fallback 3)

(def watchdog-init {:slow 0 :tripped false})

(defn watchdog-step
  "Pure watchdog transition: given the state, the gap since the last tick in
  ns and whether a game is running, returns [state' action], action being
  :fallback (once) or nil."
  [{:keys [slow tripped] :as st} gap-ns in-game?]
  (cond
    (or tripped in-game?) [(assoc st :slow 0) nil]
    (< gap-ns slow-frame-ns) [(assoc st :slow 0) nil]
    (< (inc slow) slow-frames-to-fallback) [(assoc st :slow (inc slow)) nil]
    :else [{:slow 0 :tripped true} :fallback]))

(defn- fallback! [gap-ns]
  (binding [*out* *err*]
    (println (format (str "upbge-jolt: the viewport took %.1fs per frame, so it was switched to Solid "
                          "to keep the editor and REPL responsive. The scene is GPU-heavy (large shadowed "
                          "or displaced surfaces, high taa_samples, ray tracing). Switch back with "
                          "(upbge-jolt.bpy/viewport! {:shading \"RENDERED\"}).")
                     (/ gap-ns 1e9))))
  (bpy/viewport! {:shading "SOLID"}))

(defn- start-watchdog! []
  (let [st (atom watchdog-init)
        last (atom (System/nanoTime))]
    (core/after-ticks! 1
      (fn watch []
        (let [now (System/nanoTime)
              gap (- now @last)
              [st' act] (watchdog-step @st gap (game/running?))]
          (reset! last now)
          (reset! st st')
          (when (= act :fallback)
            (try (fallback! gap) (catch Throwable t (report "watchdog" t))))
          (core/after-ticks! 1 watch))))))

(defn boot!
  "Set up the host for `mode` (\"editor\", \"play\" or \"headless\"). Returns
  immediately; the setup runs on the following ticks."
  [mode]
  (when (#{"editor" "play"} mode)
    (core/after-ticks! 6 #(try (live-view!) (catch Throwable t (report "live view" t))))
    (when-not (= "0" (System/getenv "UPBGE_JOLT_WATCHDOG"))
      ;; right after the live view is applied: on a heavy scene each tick is
      ;; a slow frame, so a long grace period would take minutes
      (core/after-ticks! 7 start-watchdog!))
    (when (= "play" mode)
      ;; after the first full redraw, or the window flickers between a
      ;; buffer with the UI and one without
      (core/after-ticks! 30 #(try (game/start!) (catch Throwable t (report "game start" t))))))
  nil)
