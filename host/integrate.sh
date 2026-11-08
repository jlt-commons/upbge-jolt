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
  ../blenlib
  ../makesdna
  ../makesrna
  ../windowmanager
  ../../../intern/guardedalloc
  ${CMAKE_BINARY_DIR}/source/blender/makesrna
)
set(INC_SYS)
set(SRC
  upbgejolt_shim.cc
  upbge_jolt.cc
)
set(LIB
  PRIVATE bf::blenkernel
  PRIVATE bf::blenlib
  PRIVATE bf::dna
  bf_rna
  bf_windowmanager
)
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
sed -i '' 's|#    define DNA_DEPRECATED \[\[deprecated\]\]|#    define DNA_DEPRECATED __attribute__((deprecated))|' \
  "$UPBGE/source/blender/makesdna/DNA_defs.h"

# --- game engine needs epoxy on macOS (WITH_OPENGL_BACKEND forced off) ------
for f in \
  source/gameengine/Rasterizer/RAS_OpenGLRasterizer/CMakeLists.txt \
  source/gameengine/VideoTexture/CMakeLists.txt; do
  grep -q "unconditionally, even on macOS" "$UPBGE/$f" || \
    sed -i '' 's|^if(WITH_OPENGL_BACKEND)$|if(WITH_OPENGL_BACKEND)\n  add_definitions(-DWITH_OPENGL_BACKEND)\nendif()\n\nlist(APPEND LIB\n  PRIVATE bf::dependencies::epoxy\n)|' "$UPBGE/$f"
done

# --- configure + build ------------------------------------------------------
NPROC="$(sysctl -n hw.ncpu 2>/dev/null || nproc)"
env CMAKE_POLICY_VERSION_MINIMUM=3.5 \
  cmake -S "$UPBGE" -B "$UPBGE/build_darwin" \
  -DCMAKE_BUILD_TYPE_INIT:STRING=Release -DWITH_PLAYER=OFF

make -C "$UPBGE/build_darwin" -j "$NPROC" install

echo "built: $UPBGE/build_darwin/bin/Blender.app/Contents/MacOS/Blender"
