(ns upbge-jolt.bge
  "The running game engine (KX) surface — the `bge` equivalent. KX objects
  exist only while a game is running and carry runtime state (world
  transforms, physics, sensors). The RNA bridge (upbge-jolt.bpy) sees the
  editor-side scene instead."
  (:require [upbge-jolt.bridge :as b]))

(defn objects
  "All KX objects in the running game, as pointer maps."
  []
  (b/kx-objects))

(defn object
  "The KX object named `name`, or nil."
  [name]
  (b/kx-object name))

(defn name
  "The name of KX object `obj`."
  [obj]
  (b/kx-name obj))

(defn position
  "World position of KX object `obj`, as [x y z]."
  [obj]
  (b/kx-position obj))

(defn move!
  "Set the world position of KX object `obj`."
  [obj xyz]
  (b/kx-move! obj xyz))
