/* upbge-jolt host integration: loads libjolt_upbge.dylib into the running
 * UPBGE/Blender process, initializes the jolt runtime, starts the nREPL
 * server, and points the shim's root at the active scene.
 *
 * Two `extern "C"` entry points are exposed on the blender binary (so a
 * Python bootstrap can reach them via ctypes.CDLL(None)):
 *
 *   int uj_host_init()  -- dlopen + jolt_library_init + set root + start the
 *                          nREPL server; returns the bound port, or -1.
 *   int uj_host_tick()  -- drain the jolt op queue (call from the main thread
 *                          every frame; the bridge marshals here).
 *
 * Both run on Blender's main thread (the Python interpreter and
 * bpy.app.timers callbacks run there), which is exactly the thread the
 * embedded jolt runtime owns and the one RNA must be touched on.
 */

#include "upbge_shim.h"

#include "DNA_ID.h"
#include "RNA_access.hh"
#include "RNA_prototypes.hh"
#include "RNA_types.hh"

#include "BKE_global.hh"
#include "BKE_main.hh"

#include "DNA_scene_types.h"

#include <dlfcn.h>
#include <cstdio>
#include <cstdlib>

using namespace blender;

/* ---- jolt runtime ABI ------------------------------------------------------ */

typedef int (*uj_jolt_library_init_fn)(int argc, const char **argv);
typedef void *(*uj_jolt_lookup_fn)(const char *name);
typedef int (*uj_tick_fn)(void);
typedef int (*uj_start_repl_fn)(int port);
typedef void (*uj_set_root_fn)(ujs_pointer);

static void *g_lib = nullptr;
static uj_tick_fn g_tick = nullptr;
static int g_started = 0;

static void *lookup(const char *name)
{
  uj_jolt_lookup_fn f = (uj_jolt_lookup_fn)dlsym(g_lib, "jolt_lookup");
  return f ? f(name) : nullptr;
}

extern "C" int uj_host_init()
{
  if (g_started) return 0;

  const char *path = getenv("UPBGE_JOLT_LIB");
  if (path == nullptr) path = "libjolt_upbge.dylib";
  g_lib = dlopen(path, RTLD_NOW);
  if (g_lib == nullptr) {
    fprintf(stderr, "upbge-jolt: dlopen(%s) failed: %s\n", path, dlerror());
    return -1;
  }
  uj_jolt_library_init_fn init = (uj_jolt_library_init_fn)dlsym(g_lib, "jolt_library_init");
  if (init == nullptr) {
    fprintf(stderr, "upbge-jolt: jolt_library_init not found\n");
    return -1;
  }
  const char *argv[] = {"upbge-jolt", nullptr};
  init(1, argv);

  g_tick = (uj_tick_fn)lookup("uj_tick");

  /* point the shim's root at the active scene. uj_set_root is a symbol in the
   * blender binary (the shim), not the jolt runtime, so resolve it from the
   * process namespace rather than via jolt_lookup. */
  Scene *scene = static_cast<Scene *>(G_MAIN->scenes.first());
  if (scene != nullptr) {
    uj_set_root_fn set_root = (uj_set_root_fn)dlsym(RTLD_DEFAULT, "uj_set_root");
    if (set_root != nullptr) {
      PointerRNA ptr = RNA_id_pointer_create(&scene->id);
      ujs_pointer root;
      root.owner = reinterpret_cast<int64_t>(ptr.owner_id);
      root.type = reinterpret_cast<int64_t>(ptr.type);
      root.data = ptr.data;
      set_root(root);
    }
  }

  uj_start_repl_fn start_repl = (uj_start_repl_fn)lookup("uj_start_repl");
  int port = -1;
  if (start_repl != nullptr) {
    port = start_repl(0);
  }
  fprintf(stderr, "upbge-jolt: nREPL listening on port %d\n", port);

  g_started = 1;
  return port;
}

extern "C" int uj_host_tick()
{
  if (g_tick != nullptr) {
    return g_tick();
  }
  return 0;
}
