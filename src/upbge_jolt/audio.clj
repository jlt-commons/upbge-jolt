(ns upbge-jolt.audio
  "Procedural and file audio on Blender's output device (audaspace).

  Sounds are recipes: build them from tones, noise or files, shape them with
  filters, and `play` one to get a handle whose pitch and volume can be driven
  every frame (an engine note rising with RPM, water rush growing with
  speed). Sounds and handles own native memory; `free!` them when done.

    (def engine (audio/play (audio/lowpass (audio/tone :sawtooth 55) 400 1) {:loop? true}))
    (audio/pitch! engine 1.8)
    (audio/stop! engine)"
  (:refer-clojure :exclude [mix])
  (:require [jolt.ffi :as ffi]
            [upbge-jolt.bridge :as b]
            [upbge-jolt.core :as core]))

(ffi/defcfn snd-tone "ujs_snd_tone" [:string :double :double] :pointer)
(ffi/defcfn snd-noise "ujs_snd_noise" [:double :double] :pointer)
(ffi/defcfn snd-file "ujs_snd_file" [:string] :pointer)
(ffi/defcfn snd-filter "ujs_snd_filter" [:pointer :string :double :double] :pointer)
(ffi/defcfn snd-mix "ujs_snd_mix" [:pointer :pointer] :pointer)
(ffi/defcfn snd-free "ujs_snd_free" [:pointer] :void)
(ffi/defcfn snd-play "ujs_snd_play" [:pointer :int32] :pointer)
(ffi/defcfn snd-control "ujs_snd_control" [:pointer :string :double] :bool)
(ffi/defcfn snd-handle-free "ujs_snd_handle_free" [:pointer] :void)
(ffi/defcfn last-error "ujs_last_error" [] :string)

(def rate 48000.0)

(defn- made [what p]
  (if (ffi/null? p)
    (throw (ex-info (or (last-error) (str what " failed")) {:what what}))
    {::sound p}))

(defn- ptr [s] (or (::sound s) (throw (ex-info "not a sound" {:value s}))))

(defn tone
  "An endless tone: wave is :sine :sawtooth :square or :triangle."
  [wave freq]
  (core/marshal #(made :tone (snd-tone (name wave) (double freq) rate))))

(defn noise
  "White noise, `seconds` long (loop it with {:loop? true})."
  ([] (noise 2.0))
  ([seconds] (core/marshal #(made :noise (snd-noise (double seconds) rate)))))

(defn file
  "A sound file (wav, ogg, flac, mp3)."
  [path]
  (core/marshal #(made :file (snd-file path))))

(defn lowpass [s freq q] (core/marshal #(made :lowpass (snd-filter (ptr s) "lowpass" (double freq) (double q)))))
(defn highpass [s freq q] (core/marshal #(made :highpass (snd-filter (ptr s) "highpass" (double freq) (double q)))))
(defn volume [s v] (core/marshal #(made :volume (snd-filter (ptr s) "volume" (double v) 0.0))))
(defn mix [a c] (core/marshal #(made :mix (snd-mix (ptr a) (ptr c)))))

(defn free!
  "Release a sound (playing handles keep their own reference)."
  [s]
  (core/marshal #(snd-free (ptr s)))
  nil)

(defn play
  "Play `s`; {:loop? true} loops forever. Returns a handle."
  ([s] (play s {}))
  ([s {:keys [loop? volume pitch]}]
   (let [h (core/marshal
            #(let [p (snd-play (ptr s) (if loop? -1 0))]
               (if (ffi/null? p)
                 (throw (ex-info (or (last-error) "play failed") {}))
                 {::handle p})))]
     (when volume (core/marshal #(snd-control (::handle h) "volume" (double volume))))
     (when pitch (core/marshal #(snd-control (::handle h) "pitch" (double pitch))))
     h)))

(defn- control! [h what v]
  (core/marshal
   #(or (snd-control (::handle h) what (double v))
        (throw (ex-info (or (last-error) (str what " failed")) {:what what})))))

(defn pitch! "Playback speed factor (1 = original)." [h v] (control! h "pitch" v))
(defn volume! "Gain (1 = original)." [h v] (control! h "volume" v))
(defn pause! [h] (control! h "pause" 0))
(defn resume! [h] (control! h "resume" 0))

(defn stop!
  "Stop and release a playing handle."
  [h]
  (core/marshal #(do (snd-control (::handle h) "stop" 0.0)
                     (snd-handle-free (::handle h))))
  nil)
