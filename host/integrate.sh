#!/bin/bash
# Integrate upbge-jolt into a cloned UPBGE tree, configure, and build.
#
# Prereqs: native/upbge is a clone of https://github.com/UPBGE/upbge.git with
# `make update` run (prebuilt libs harvested into lib/macos_arm64).
#
# This drops the shim + host integration into the tree, registers the module,
# links and force-loads it into the blender binary, applies the two build
# fixes UPBGE needs to compile on current macOS (clang 17 `[[deprecated]]`
# on a type; the game engine rasterizer/videotexture epoxy dependency gated
# behind WITH_OPENGL_BACKEND which is forced off on Apple), then configures
# and builds. Idempotent.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UPBGE="$ROOT/native/upbge"
SRC="$UPBGE/source/blender/upbge_jolt"

[ -d "$UPBGE/source" ] || { echo "native/upbge not cloned; run jolt upbge-deps first" >&2; exit 1; }

# --- module files -----------------------------------------------------------
mkdir -p "$SRC"
cp "$ROOT/host/upbgejolt_shim.cc" "$SRC/upbgejolt_shim.cc"
cp "$ROOT/host/upbge_jolt.cc" "$SRC/upbge_jolt.cc"
cp "$ROOT/native/upbge_shim.h" "$SRC/upbge_shim.h"

cat > "$SRC/CMakeLists.txt" <<'CMAKE'
# SPDX-License-Identifier: GPL-2.0-or-later
set(INC
  .
  ../blenkernel
  ../depsgraph
  ../blenlib
  ../makesdna
  ../makesrna
  ../python/intern
  ../windowmanager
  ../../gameengine/Common
  ../../gameengine/Converter
  ../../gameengine/Device
  ../../gameengine/Expressions
  ../../gameengine/GameLogic
  ../../gameengine/Ketsji
  ../../gameengine/Launcher
  ../../gameengine/Physics/Common
  ../../gameengine/Rasterizer
  ../../gameengine/Rasterizer/RAS_OpenGLRasterizer
  ../../gameengine/SceneGraph
  ../draw/intern
  ../gpu
  ../gpu/intern
  ../../../intern/ghost
  ../../../intern/guardedalloc
  ../../../intern/termcolor
  ${PYTHON_INCLUDE_DIR}
  ${CMAKE_BINARY_DIR}/source/blender/makesrna
)
set(INC_SYS
  ../../../intern/moto/include
)
set(SRC
  upbgejolt_shim.cc
  upbge_jolt.cc
)
set(LIB
  PRIVATE bf::blenkernel
  PRIVATE bf::blenlib
  PRIVATE bf::dna
  PRIVATE bf::gpu
  bf_python
  bf_rna
  bf_windowmanager
)
# The shim calls into KX classes whose layout and vtables depend on these
# (EXP_Value has WITH_PYTHON-only virtuals), so it must see the same defines
# the gameengine compiles with, or virtual calls dispatch to the wrong slot.
if(WITH_PYTHON)
  add_definitions(-DWITH_PYTHON)
endif()
if(WITH_GAMEENGINE)
  add_definitions(-DWITH_GAMEENGINE)
endif()
if(WITH_BULLET)
  list(APPEND INC ../../gameengine/Physics/Bullet)
  list(APPEND INC_SYS ${BULLET_INCLUDE_DIRS})
  add_definitions(-DWITH_BULLET)
endif()
if(WITH_AUDASPACE)
  list(APPEND INC_SYS ${AUDASPACE_C_INCLUDE_DIRS})
  list(APPEND LIB ${AUDASPACE_C_LIBRARIES})
  add_definitions(-DWITH_AUDASPACE)
endif()
if(WITH_SDL)
  list(APPEND INC_SYS ${SDL_INCLUDE_DIR})
  add_definitions(-DWITH_SDL)
endif()
if(WITH_CODEC_FFMPEG)
  add_definitions(-DWITH_FFMPEG)
endif()
blender_add_lib(bf_upbge_jolt "${SRC}" "${INC}" "${INC_SYS}" "${LIB}")
CMAKE

# --- register the module ----------------------------------------------------
grep -q "add_subdirectory(upbge_jolt)" "$UPBGE/source/blender/CMakeLists.txt" || \
  sed -i '' 's|^add_subdirectory(makesrna)$|add_subdirectory(makesrna)\nadd_subdirectory(upbge_jolt)|' \
  "$UPBGE/source/blender/CMakeLists.txt"

# --- link + force-load into the blender binary ------------------------------
grep -q "bf_upbge_jolt" "$UPBGE/source/creator/CMakeLists.txt" || \
  sed -i '' 's|^  PRIVATE bf::windowmanager$|  PRIVATE bf::windowmanager\n  bf_upbge_jolt|' \
  "$UPBGE/source/creator/CMakeLists.txt"

grep -q "force_load,\$<TARGET_FILE:bf_upbge_jolt>" "$UPBGE/source/creator/CMakeLists.txt" || \
  sed -i '' 's|^setup_platform_linker_flags(blender)$|if(APPLE)\n  target_link_libraries(blender PRIVATE "-Wl,-force_load,$<TARGET_FILE:bf_upbge_jolt>")\nelse()\n  target_link_libraries(blender PRIVATE "-Wl,--whole-archive" bf_upbge_jolt "-Wl,--no-whole-archive")\nendif()\n\nsetup_platform_linker_flags(blender)|' \
  "$UPBGE/source/creator/CMakeLists.txt"

# --- clang 17: [[deprecated]] on a type is only legal in C++23 --------------
grep -q '#    define DNA_DEPRECATED \[\[deprecated\]\]' "$UPBGE/source/blender/makesdna/DNA_defs.h" && \
  sed -i '' 's|#    define DNA_DEPRECATED \[\[deprecated\]\]|#    define DNA_DEPRECATED __attribute__((deprecated))|' \
  "$UPBGE/source/blender/makesdna/DNA_defs.h"

# --- game engine needs epoxy on macOS (WITH_OPENGL_BACKEND forced off) ------
# Move the epoxy link out of the WITH_OPENGL_BACKEND guard. Exact-block
# replacement, tagged with a marker so a rerun leaves a patched file alone.
for f in \
  source/gameengine/Rasterizer/RAS_OpenGLRasterizer/CMakeLists.txt \
  source/gameengine/VideoTexture/CMakeLists.txt; do
  grep -q "upbge-jolt: epoxy" "$UPBGE/$f" || python3 - "$UPBGE/$f" <<'PY'
import sys
path = sys.argv[1]
old = """if(WITH_OPENGL_BACKEND)
  list(APPEND LIB
    PRIVATE bf::dependencies::epoxy
  )
  add_definitions(-DWITH_OPENGL_BACKEND)
endif()
"""
new = """if(WITH_OPENGL_BACKEND)
  add_definitions(-DWITH_OPENGL_BACKEND)
endif()

# upbge-jolt: epoxy is used unconditionally, even on macOS where the UI
# runs on Metal and WITH_OPENGL_BACKEND is forced off.
list(APPEND LIB
  PRIVATE bf::dependencies::epoxy
)
"""
src = open(path).read()
if old not in src:
    sys.exit(f"integrate.sh: epoxy block not found in {path}")
open(path, "w").write(src.replace(old, new, 1))
PY
done

# --- blenderplayer references a removed extern_draco target -----------------
grep -q "if(WITH_DRACO AND TARGET extern_draco)" "$UPBGE/source/blenderplayer/CMakeLists.txt" || \
  sed -i '' 's|if(WITH_DRACO)|if(WITH_DRACO AND TARGET extern_draco)|' \
  "$UPBGE/source/blenderplayer/CMakeLists.txt"

# --- EEVEE shader uses `kernel`, a Metal reserved word -----------------------
grep -q 'const float kernel\[9\]' "$UPBGE/source/blender/draw/engines/eevee/shaders/eevee_shadow_tracing.bsl.hh" && \
  sed -i '' 's/const float kernel\[9\]/const float gaussian_kernel[9]/; s/const float kernel_sum/const float gaussian_kernel_sum/; s/vis \* kernel\[ki++\]/vis * gaussian_kernel[ki++]/; s/vis_sum \/ kernel_sum/vis_sum \/ gaussian_kernel_sum/' \
  "$UPBGE/source/blender/draw/engines/eevee/shaders/eevee_shadow_tracing.bsl.hh"

# --- game-side tick: drain the jolt op queue from the game loop -------------
# uj_host_game_tick (not uj_host_tick) so the shim knows a game is running.
KXE="$UPBGE/source/gameengine/Ketsji/KX_KetsjiEngine.cpp"
grep -q "uj_host_game_tick" "$KXE" || {
  if grep -q "uj_host_tick()" "$KXE"; then
    sed -i '' 's/uj_host_tick()/uj_host_game_tick()/g' "$KXE"
  else
    sed -i '' 's|^using namespace blender;$|using namespace blender;\n\nextern "C" int uj_host_game_tick();|' "$KXE"
    sed -i '' 's|^  return m_doRender;$|  uj_host_game_tick();\n\n  return m_doRender;|' "$KXE"
  fi
}

# --- mark the game stopped before its scenes are freed -----------------------
grep -q "uj_shim_set_in_game" "$KXE" || python3 - "$KXE" <<'PY'
import sys
path = sys.argv[1]
src = open(path).read()
old = "void KX_KetsjiEngine::StopEngine()\n{\n"
if old not in src:
    sys.exit("integrate.sh: StopEngine not found in " + path)
src = src.replace(old, old + "  uj_shim_set_in_game(false); /* upbge-jolt: KX scenes die below */\n", 1)
src = src.replace('extern "C" int uj_host_game_tick();',
                  'extern "C" int uj_host_game_tick();\nextern "C" void uj_shim_set_in_game(bool in_game);', 1)
open(path, "w").write(src)
PY

# --- 2D filters: the fullscreen vertex shader reads gl_VertexID, which the
# Metal backend only provides when the create-info asks for it ---------------
RSH="$UPBGE/source/gameengine/Rasterizer/RAS_Shader.cpp"
grep -q "upbge-jolt: VERTEX_ID" "$RSH" || python3 - "$RSH" <<'PY'
import sys
path = sys.argv[1]
src = open(path).read()
old = '  info.fragment_out(0, Type::float4_t, "fragColor");\n'
if old not in src:
    sys.exit("integrate.sh: fragment_out not found in " + path)
src = src.replace(old, old + "  info.builtins(BuiltinBits::VERTEX_ID); /* upbge-jolt: VERTEX_ID for Metal */\n", 1)
open(path, "w").write(src)
PY

# --- fixed frame rate: sleep between frames instead of spinning -------------
# UPBGE commented the sleep out, so a game busy-waits a whole core between
# frames. Restore it: sleep until 2ms before the next frame, spin the rest.
grep -q "upbge-jolt: frame sleep" "$KXE" || python3 - "$KXE" <<'PY'
import sys
path = sys.argv[1]
src = open(path).read()
old = """  //// Else in case of fixed framerate, try to sleep until the next frame.
  // else if (m_flags & FIXED_FRAMERATE) {
  //  const double sleeptime = timestep - dt - 1.0e-3;
  //  /* If the remaining time is greater than 1ms (sleep resolution) sleep this thread.
  //   * The other 1ms will be busy wait.
  //   */
  //  if (sleeptime > 0.0) {
  //    std::this_thread::sleep_for(std::chrono::nanoseconds((long)(sleeptime * 1.0e9)));
  //  }
  //}
"""
new = """  /* upbge-jolt: frame sleep. Else in case of fixed framerate, sleep until
   * 2ms before the next frame (macOS sleeps overshoot) and busy-wait the rest. */
  else if (m_flags & FIXED_FRAMERATE) {
    const double sleeptime = timestep - dt - 2.0e-3;
    if (sleeptime > 0.0) {
      std::this_thread::sleep_for(std::chrono::nanoseconds((long)(sleeptime * 1.0e9)));
    }
  }
"""
if old not in src:
    sys.exit("integrate.sh: frame sleep block not found in " + path)
src = src.replace(old, new, 1)
if "#include <thread>" not in src:
    src = src.replace("#include <", "#include <chrono>\n#include <thread>\n#include <", 1)
open(path, "w").write(src)
PY

# --- Metal: don't spin a core while waiting for the GPU ----------------------
# wait_until_active_command_buffers_complete yields in a tight loop, so every
# GPU readback (EEVEE's shadow module does one per game frame) busy-waits a
# core for as long as the GPU is busy. Yield briefly, then sleep 100us.
MTLH="$UPBGE/source/blender/gpu/metal/mtl_context.hh"
grep -q "upbge-jolt: gpu wait" "$MTLH" || python3 - "$MTLH" <<'PY'
import sys
path = sys.argv[1]
src = open(path).read()
old = """    while (get_active_command_buffer_count()) {
      std::this_thread::yield();
    }"""
new = """    /* upbge-jolt: gpu wait. Yield briefly, then sleep, rather than spin; and
     * give up after 10s: a buffer waiting on an event that only work submitted
     * after this wait can signal never completes, and waiting forever
     * deadlocks the main thread (EEVEE's shadow readback hits this). */
    for (int spins = 0; get_active_command_buffer_count(); spins++) {
      if (spins < 64) {
        std::this_thread::yield();
      }
      else if (spins < 64 + 100000) {
        std::this_thread::sleep_for(std::chrono::microseconds(100));
      }
      else {
        fprintf(stderr, "upbge-jolt: gave up waiting for %d GPU command buffer(s)\\n",
                int(get_active_command_buffer_count()));
        break;
      }
    }"""
if old not in src:
    sys.exit("integrate.sh: Metal wait loop not found in " + path)
open(path, "w").write(src.replace(old, new, 1))
PY

# --- startup hook: Blender's main starts the runtime (no Python) -------------
CRE="$UPBGE/source/creator/creator.cc"
grep -q "uj_host_start" "$CRE" || python3 - "$CRE" <<'PY'
import sys
path = sys.argv[1]
src = open(path).read()
inc = '#include "creator_intern.h" /* Own include. */'
call_old = """#ifndef WITH_PYTHON_MODULE
  if (G.background) {
    int exit_code;"""
splash_old = """    /* Shows the splash as needed. */
    WM_init_splash_on_startup(C);"""
for old in (inc, call_old, splash_old):
    if old not in src:
        sys.exit("integrate.sh: creator.cc anchor not found: " + old.splitlines()[0])
src = src.replace(inc, inc + '\n\n/* upbge-jolt: startup hook, see host/upbge_jolt.cc */\nextern "C" int uj_host_start(blender::bContext *C);', 1)
src = src.replace(call_old, """#ifndef WITH_PYTHON_MODULE
  /* upbge-jolt: start the embedded runtime when UPBGE_JOLT names a mode. */
  const bool uj_active = uj_host_start(C) != 0;
  if (G.background) {
    int exit_code;""", 1)
src = src.replace(splash_old, """    /* Shows the splash as needed (not under upbge-jolt). */
    if (!uj_active) {
      WM_init_splash_on_startup(C);
    }""", 1)
open(path, "w").write(src)
PY

# --- EEVEE: read shadow statistics from the oldest frame, never the newest --
# Once viewport sampling finishes (every frame in a game) EEVEE swapped through
# its 5-deep statistics swap-chain and read the newest buffer, a full CPU-GPU
# sync per frame that keeps them from overlapping. The statistics only feed a
# warning, so always read the oldest one.
EVS="$UPBGE/source/blender/draw/engines/eevee/eevee_shadow.cc"
grep -q "upbge-jolt: oldest statistics" "$EVS" || python3 - "$EVS" <<'PY'
import sys
path = sys.argv[1]
src = open(path).read()
old = """    if (inst_.sampling.finished_viewport()) {
      /* Swap enough to read the last one. */
      for (int i = 0; i < statistics_buf_.size(); i++) {
        statistics_buf_.swap();
      }
    }
    else {
      statistics_buf_.swap();
    }
    statistics_buf_.current().read();
    ShadowStatistics stats = statistics_buf_.current();
"""
new = """    /* upbge-jolt: oldest statistics. Always read the end of the swap-chain
     * (reading the newest forces a full GPU sync), and only once every 60
     * frames: on Metal any read waits for all in-flight GPU work, so a read
     * per frame keeps the CPU and GPU from ever overlapping. The statistics
     * only feed these warnings, which keep showing the last reading. */
    statistics_buf_.swap();
    static int frames_since_read = 60;
    static ShadowStatistics stats = {};
    if (++frames_since_read >= 60) {
      frames_since_read = 0;
      statistics_buf_.current().read();
      stats = statistics_buf_.current();
    }
"""
if old not in src:
    sys.exit("integrate.sh: shadow statistics swap not found in " + path)
open(path, "w").write(src.replace(old, new, 1))
PY

# --- standalone player (released games): link the shim, init the runtime ----
BPC="$UPBGE/source/blenderplayer/CMakeLists.txt"
grep -q "bf_upbge_jolt" "$BPC" || python3 - "$BPC" <<'PY'
import sys
path = sys.argv[1]
src = open(path).read()
old = "target_link_libraries(blenderplayer PRIVATE ${LIB})\n"
if old not in src:
    sys.exit("integrate.sh: blenderplayer link line not found")
src = src.replace(old, old + """# upbge-jolt: the shim and host integration, kept whole like in blender
if(APPLE)
  target_link_libraries(blenderplayer PRIVATE bf_upbge_jolt "-Wl,-force_load,$<TARGET_FILE:bf_upbge_jolt>")
else()
  target_link_libraries(blenderplayer PRIVATE "-Wl,--whole-archive" bf_upbge_jolt "-Wl,--no-whole-archive")
endif()
""", 1)
open(path, "w").write(src)
PY

GPG="$UPBGE/source/gameengine/GamePlayer/GPG_ghost.cpp"
grep -q "uj_host_init_player" "$GPG" || python3 - "$GPG" <<'PY'
import sys
path = sys.argv[1]
src = open(path).read()
old = "            LA_PlayerLauncher launcher(system,"
if old not in src:
    sys.exit("integrate.sh: LA_PlayerLauncher construction not found")
src = src.replace(old, "            uj_host_init_player(); /* upbge-jolt: runtime for the game's jolt code */\n" + old, 1)
src = src.replace('#include "LA_PlayerLauncher.h"', '#include "LA_PlayerLauncher.h"\n\nextern "C" int uj_host_init_player();', 1)
open(path, "w").write(src)
PY

# --- configure + build ------------------------------------------------------
# UPBGE_JOLT_TARGET picks what to build: install (default: blender and the
# player, installed into build_darwin/bin), or a single target such as
# blenderplayer (leaves a running Blender's files alone).
NPROC="$(sysctl -n hw.ncpu 2>/dev/null || nproc)"
env CMAKE_POLICY_VERSION_MINIMUM=3.5 \
  cmake -S "$UPBGE" -B "$UPBGE/build_darwin" \
  -DCMAKE_BUILD_TYPE_INIT:STRING=Release -DWITH_PLAYER=ON

make -C "$UPBGE/build_darwin" -j "$NPROC" "${UPBGE_JOLT_TARGET:-install}"

echo "built: $UPBGE/build_darwin/bin/Blender.app/Contents/MacOS/Blender"
