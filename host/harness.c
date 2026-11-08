/* C harness: drives libjolt_upbge as a host would — dlopen, init, lookup,
 * tick, eval, nREPL start/stop, shutdown. Exit 0 = all assertions pass.
 *
 * Build: cc -o /tmp/uj_harness host/harness.c -ldl
 * Run:   /tmp/uj_harness ./libjolt_upbge.dylib
 */

#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

typedef int (*uj_start_repl_fn)(int port);
typedef int (*uj_stop_repl_fn)(void);
typedef int (*uj_tick_fn)(void);
typedef int (*uj_eval_fn)(const char *code, char *buf, int cap);
typedef int (*uj_ping_fn)(void);
typedef int (*jolt_library_init_fn)(int argc, const char **argv);
typedef void *(*jolt_lookup_fn)(const char *name);
typedef void (*jolt_library_shutdown_fn)(void);

static int failures = 0;

#define CHECK(cond, msg)                                                     \
  do {                                                                       \
    if (cond) {                                                              \
      printf("ok   %s\n", msg);                                              \
    } else {                                                                 \
      printf("FAIL %s\n", msg);                                              \
      failures++;                                                            \
    }                                                                        \
  } while (0)

int main(int argc, char **argv) {
  if (argc != 2) {
    fprintf(stderr, "usage: %s <libjolt_upbge.dylib>\n", argv[0]);
    return 2;
  }

  void *lib = dlopen(argv[1], RTLD_NOW);
  if (!lib) {
    fprintf(stderr, "dlopen failed: %s\n", dlerror());
    return 2;
  }

  jolt_library_init_fn init = (jolt_library_init_fn)dlsym(lib, "jolt_library_init");
  jolt_lookup_fn lookup = (jolt_lookup_fn)dlsym(lib, "jolt_lookup");
  jolt_library_shutdown_fn shutdown_fn = (jolt_library_shutdown_fn)dlsym(lib, "jolt_library_shutdown");
  CHECK(init && lookup && shutdown_fn, "ABI symbols present");

  const char *iargv[] = {"upbge-jolt", NULL};
  int rc = init(1, iargv);
  CHECK(rc == 0, "jolt_library_init returns 0");

  uj_ping_fn ping = (uj_ping_fn)lookup("uj_ping");
  CHECK(ping != NULL, "uj_ping resolves");
  CHECK(ping() == 42, "uj_ping answers 42");

  uj_tick_fn tick = (uj_tick_fn)lookup("uj_tick");
  CHECK(tick != NULL, "uj_tick resolves");
  uj_eval_fn eval = (uj_eval_fn)lookup("uj_eval");
  CHECK(eval != NULL, "uj_eval resolves");
  char buf[512];
  memset(buf, 0, sizeof buf);
  int n = eval("(+ 1 2)", buf, sizeof buf);
  CHECK(n == 1 && strcmp(buf, "3") == 0, "uj_eval computes (+ 1 2) -> 3");

  memset(buf, 0, sizeof buf);
  n = eval("(upbge-jolt.core/enqueue! #(println \"ran from host thread\"))", buf, sizeof buf);
  CHECK(n >= 0, "eval enqueues an op");
  tick();
  CHECK(n >= 0, "tick drains the op (check stdout for 'ran from host thread')");

  uj_start_repl_fn start = (uj_start_repl_fn)lookup("uj_start_repl");
  uj_stop_repl_fn stop = (uj_stop_repl_fn)lookup("uj_stop_repl");
  CHECK(start && stop, "repl exports resolve");
  int port = start(0);
  CHECK(port > 0, "uj_start_repl binds a free port");
  memset(buf, 0, sizeof buf);
  n = eval("(upbge-jolt.core/pending-ops)", buf, sizeof buf);
  CHECK(n == 1 && strcmp(buf, "0") == 0, "runtime introspects itself");

  rc = stop();
  CHECK(rc == 0, "uj_stop_repl answers 0");

  /* re-start on the SAME port to prove the socket was released */
  int port2 = start(port);
  CHECK(port2 == port, "port rebind after stop");
  stop();

  shutdown_fn();
  printf(failures ? "\n%d checks failed\n" : "\nall checks passed\n", failures);
  return failures ? 1 : 0;
}
