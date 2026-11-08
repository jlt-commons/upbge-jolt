# upbge-jolt documentation

upbge-jolt embeds a [jolt](https://github.com/jolt-lang/jolt) runtime (Clojure
on Chez Scheme, no JVM) in UPBGE, the Blender game engine fork, and exposes it
through an nREPL server. Scenes are built, games are written and the running
game is inspected by evaluating Clojure in the live engine, from an editor or
an AI agent.

- [Getting started](getting-started.md): build, run modes, connecting a REPL
- [Building scenes](scenes.md): objects, meshes, materials and node trees,
  operators, the world, saving
- [Game logic](game-logic.md): `main.clj`, frame handlers, the `bge` runtime
  API, input, physics, audio, shaders driven from game code
- [Releasing](releasing.md): packaging a game as a standalone app
- [Architecture](architecture.md): host, shim, runtime, threading, pointer
  safety
- [Troubleshooting](troubleshooting.md): known limitations and common errors
- [API reference](api.md): every public function (generated: `jolt docs`)

For AI agents, [`skills/upbge-jolt/SKILL.md`](../skills/upbge-jolt/SKILL.md)
is a condensed working guide.
