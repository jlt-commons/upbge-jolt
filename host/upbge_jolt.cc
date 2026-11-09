/* upbge-jolt host integration: loads libjolt_upbge.dylib into the running
 * UPBGE/Blender process, initializes the jolt runtime, starts the nREPL
 * server, and points the shim's root at the active scene.
 *
 * `extern "C"` entry points, called from the hooks integrate.sh adds to
 * Blender's main, the game loop and the player:
 *
 *   int uj_host_init()  -- dlopen + jolt_library_init + set root + start the
 *                          nREPL server; returns the bound port, or -1.
 *   int uj_host_init_player() -- the same for the standalone player, without
 *                          the server unless UPBGE_JOLT_REPL is set.
 *   int uj_host_tick()  -- drain the jolt op queue (call from the main thread
 *                          every frame; the bridge marshals here).
 *   int uj_host_game_tick() -- the same, from the game loop; marks a game as
 *                          running so the KX calls know the scene is live.
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
#include "BLI_timer.hh"

#include "DNA_scene_types.h"

#include <csignal>
#include <dlfcn.h>
#include <string>
#include <unistd.h>
#ifdef __APPLE__
#  include <mach-o/dyld.h>
#endif
#include <cstdio>
#include <cstdlib>
#include <cstring>

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
static int g_port = -1;

static void *lookup(const char *name)
{
  uj_jolt_lookup_fn f = (uj_jolt_lookup_fn)dlsym(g_lib, "jolt_lookup");
  return f ? f(name) : nullptr;
}

#ifdef __APPLE__
static const char *LIB_NAME = "libjolt_upbge.dylib";
#else
static const char *LIB_NAME = "libjolt_upbge.so";
#endif

/* Directory of the running executable, with a trailing slash ("" if unknown). */
static std::string exe_dir()
{
  char buf[4096];
#ifdef __APPLE__
  uint32_t size = sizeof buf;
  if (_NSGetExecutablePath(buf, &size) != 0) return "";
#else
  ssize_t n = readlink("/proc/self/exe", buf, sizeof buf - 1);
  if (n <= 0) return "";
  buf[n] = '\0';
#endif
  std::string p(buf);
  size_t slash = p.rfind('/');
  return slash == std::string::npos ? "" : p.substr(0, slash + 1);
}

/* UPBGE_JOLT_LIB, else beside the executable, else in a macOS bundle's
 * Resources, else the loader's search path. */
static void *open_runtime()
{
  const char *env = getenv("UPBGE_JOLT_LIB");
  if (env != nullptr) {
    void *h = dlopen(env, RTLD_NOW);
    if (h == nullptr) fprintf(stderr, "upbge-jolt: dlopen(%s) failed: %s\n", env, dlerror());
    return h;
  }
  std::string dir = exe_dir();
  for (std::string candidate : {dir + LIB_NAME, dir + "../Resources/" + LIB_NAME, std::string(LIB_NAME)}) {
    void *h = dlopen(candidate.c_str(), RTLD_NOW);
    if (h != nullptr) return h;
  }
  fprintf(stderr, "upbge-jolt: %s not found beside the executable or in Resources: %s\n",
          LIB_NAME, dlerror());
  return nullptr;
}

static int host_init(bool start_server)
{
  if (g_started) return g_port; /* idempotent: report the running server */

  g_lib = open_runtime();
  if (g_lib == nullptr) {
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
  int port = 0;
  if (start_server) {
    port = start_repl != nullptr ? start_repl(0) : -1;
    fprintf(stderr, "upbge-jolt: nREPL listening on port %d\n", port);
  }

  /* This thread now goes back to Blender's own loop and only re-enters jolt
   * through uj_tick (:collect-safe). Release it, or every collection started
   * on an nREPL thread waits until the next tick for this thread to reach a
   * safe point -- and forever if Blender blocks (modal dialog, game paused). */
  typedef void (*uj_release_thread_fn)(void);
  uj_release_thread_fn release = (uj_release_thread_fn)dlsym(g_lib, "jolt_library_release_thread");
  if (release != nullptr) {
    release();
  }

  g_started = 1;
  g_port = port;
  return port;
}

/* Editor and headless hosts: runtime plus nREPL server; returns the port. */
extern "C" int uj_host_init()
{
  return host_init(true);
}

/* The standalone player (a released game): runtime only, unless
 * UPBGE_JOLT_REPL is set, which also starts the nREPL server and, with
 * UPBGE_JOLT_PORT_FILE, writes its port there. Returns 0 or the port, -1 on
 * failure. */
extern "C" int uj_host_init_player()
{
  bool repl = getenv("UPBGE_JOLT_REPL") != nullptr;
  int port = host_init(repl);
  const char *port_file = getenv("UPBGE_JOLT_PORT_FILE");
  if (repl && port > 0 && port_file != nullptr) {
    FILE *f = fopen(port_file, "w");
    if (f != nullptr) {
      fprintf(f, "%d", port);
      fclose(f);
    }
  }
  return port;
}

extern "C" void uj_shim_set_in_game(bool in_game);

/* Editor / headless pump: no game is running while this is called. */
extern "C" int uj_host_tick()
{
  uj_shim_set_in_game(false);
  if (g_tick != nullptr) {
    return g_tick();
  }
  return 0;
}

/* Game loop pump, called from KX_KetsjiEngine::NextFrame. */
extern "C" int uj_host_game_tick()
{
  uj_shim_set_in_game(true);
  if (g_tick != nullptr) {
    return g_tick();
  }
  return 0;
}

/* ---- startup hook --------------------------------------------------------------

 * Called from Blender's main() after the file loads, before the event loop.
 * Does nothing unless UPBGE_JOLT names a mode ("editor", "play",
 * "headless"); then starts the runtime and the nREPL server, writes the port
 * to UPBGE_JOLT_PORT_FILE (default ./.nrepl-port, removed at exit), hands the
 * window manager's context to the shim, pumps the runtime from a WM timer,
 * and lets the jolt side (upbge-jolt.boot) set up the mode. Headless mode
 * has no event loop, so it pumps from a loop of its own and never returns.
 * Returns 1 when active (the caller then skips the splash). */

extern "C" void uj_shim_set_context(bContext *C);
typedef int (*uj_boot_fn)(const char *mode);

static std::string g_port_file;

static void remove_port_file()
{
  if (g_port_file.empty()) return;
  FILE *f = fopen(g_port_file.c_str(), "r");
  if (f == nullptr) return;
  int port = 0;
  bool ours = fscanf(f, "%d", &port) == 1 && port == g_port;
  fclose(f);
  if (ours) remove(g_port_file.c_str());
}

/* atexit doesn't run when the process is killed by a signal, which would
 * leave a stale .nrepl-port pointing at a dead server: remove it on
 * SIGTERM/SIGINT too, then let the previous disposition take over. */
static void (*g_prev_term)(int) = SIG_DFL;
static void (*g_prev_int)(int) = SIG_DFL;

static void on_signal(int sig)
{
  remove_port_file();
  signal(sig, sig == SIGTERM ? g_prev_term : g_prev_int);
  raise(sig);
}

static void write_port_file(int port)
{
  const char *env = getenv("UPBGE_JOLT_PORT_FILE");
  std::string path = env != nullptr ? env : ".nrepl-port";
  if (!path.empty() && path[0] != '/') {
    char cwd[4096];
    if (getcwd(cwd, sizeof cwd) != nullptr) path = std::string(cwd) + "/" + path;
  }
  FILE *f = fopen(path.c_str(), "w");
  if (f == nullptr) {
    fprintf(stderr, "upbge-jolt: could not write %s\n", path.c_str());
    return;
  }
  fprintf(f, "%d", port);
  fclose(f);
  g_port_file = path;
  atexit(remove_port_file);
  g_prev_term = signal(SIGTERM, on_signal);
  g_prev_int = signal(SIGINT, on_signal);
  if (g_prev_term == SIG_ERR) g_prev_term = SIG_DFL;
  if (g_prev_int == SIG_ERR) g_prev_int = SIG_DFL;
  fprintf(stderr, "upbge-jolt: wrote %s\n", path.c_str());
}

static double pump_timer(uintptr_t /*uuid*/, void * /*user_data*/)
{
  uj_host_tick();
  return 1.0 / 60.0;
}

extern "C" int uj_host_start(bContext *C)
{
  const char *mode = getenv("UPBGE_JOLT");
  if (mode == nullptr || mode[0] == '\0') return 0;
  if (strcmp(mode, "editor") != 0 && strcmp(mode, "play") != 0 && strcmp(mode, "headless") != 0) {
    fprintf(stderr, "upbge-jolt: unknown UPBGE_JOLT mode %s (editor, play, headless)\n", mode);
    return 0;
  }
  uj_shim_set_context(C);
  int port = uj_host_init();
  if (port <= 0) {
    fprintf(stderr, "upbge-jolt: runtime failed to start\n");
    return 0;
  }
  write_port_file(port);
  uj_boot_fn boot = (uj_boot_fn)lookup("uj_boot");
  if (boot != nullptr) boot(mode);

  if (G.background) {
    /* no event loop in background mode: pump here until killed */
    for (;;) {
      uj_host_tick();
      usleep(20000);
    }
  }
  BLI_timer_register((uintptr_t)&pump_timer, pump_timer, nullptr, nullptr, 1.0 / 60.0, true);
  fprintf(stderr, "upbge-jolt: %s mode, runtime pumped at 60 Hz\n", mode);
  return 1;
}
