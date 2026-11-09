/* upbge-jolt: flat C ABI between the jolt runtime (built as a jolt
 * `build --library` shared object) and a UPBGE/Blender host.
 *
 * The contract is this list of ujs_* C functions. Two implementations exist:
 *
 *  - the REAL shim: a small C++ file compiled into the UPBGE build
 *    (host/upbgejolt_shim.cc in this repo, dropped into the UPBGE tree and
 *    linked into the blender binary, symbols exported with -export_dynamic
 *    / -rdynamic). It implements ujs_* over RNA (makesrna) and the Ketsji
 *    engine. The jolt runtime binds these symbols out of the process
 *    namespace (jolt.ffi :process / defcfn fallback).
 *
 *  - the MOCK shim (native/mock_shim.c): same ujs_* symbol names over plain
 *    C state, built as native/libupbge_shim.dylib and declared in deps.edn
 *    :jolt/native. defcfn resolves registered libraries first, so the tests
 *    exercise the real binding path headlessly, and the same runtime code
 *    runs unchanged against the real host when the mock dylib is absent.
 *
 * ujs_pointer carries an RNA data pointer plus the metadata needed to
 * revalidate it. The jolt side never dereferences .data; it hands it back
 * verbatim. Strings returned by ujs_* point at host memory valid until the
 * next ujs_* call.
 *
 * Every function answers bool (or fills an out-param and answers bool);
 * false means failure, with a message available from ujs_last_error().
 */

#ifndef UPBGE_JOLT_SHIM_H
#define UPBGE_JOLT_SHIM_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ---- property type tags (mirror RNA PropertyType) ---- */
enum {
  UJS_PROP_BOOL = 0,
  UJS_PROP_INT,
  UJS_PROP_FLOAT,
  UJS_PROP_STRING,
  UJS_PROP_ENUM,
  UJS_PROP_POINTER,
  UJS_PROP_COLLECTION,
};

/* RNA pointer passed by value: (owner, type, data). data is host-owned and
 * opaque to the runtime. owner is a host object address usable as identity
 * for revalidation; type is the StructRNA* (0 when unknown). */
typedef struct {
  int64_t owner;
  int64_t type;
  void *data;
} ujs_pointer;

/* property descriptor (fixed size so it crosses as one out-param fill) */
typedef struct {
  char name[64];      /* RNA property identifier */
  int32_t type;       /* UJS_PROP_* */
  int32_t array_len;  /* 0 = scalar, >0 = fixed array, -1 = dynamic */
  char item_type[64]; /* StructRNA id of pointer target / collection item */
} ujs_prop_desc;

/* opaque iterator handles; 0 means exhausted/absent/failed */
typedef int64_t ujs_iter_t;

/* A tagged value for RNA function arguments and results. `type` is a
 * UJS_PROP_* tag, or UJS_VAL_NONE. Bool/int/enum-value use `i`; float uses
 * `f`; string and enum identifiers use `s`; float arrays use `fa` + `len`;
 * pointers use `p`. Results that carry `s` or `fa` point into shim-owned
 * memory valid until the next ujs_* call. 64 bytes, 8-aligned. */
enum { UJS_VAL_NONE = -1 };
typedef struct {
  int32_t type;
  int32_t len;
  int64_t i;
  double f;
  const char *s;
  const double *fa;
  ujs_pointer p;
} ujs_value;

/* ---- lifecycle / introspection ---- */

/* last error message from a failed call, or NULL */
const char *ujs_last_error(void);

/* the root pointer for editor-style access. Real shim: the RNA pointer for
 * bpy.context's scene data (or Main). Mock: the mock Scene. */
bool ujs_root(ujs_pointer *out);

/* bpy.data (the Main database) and bpy.context. ujs_root is the context's
 * scene, resolved on every call so it stays valid across file loads. */
bool ujs_data_root(ujs_pointer *out);

/* The data generation, read in place: bumped whenever loading a file,
 * undo/redo or a game ending (UPBGE restores the pre-game state) frees the
 * datablocks RNA pointers point into. Pointers from an older generation must
 * not be used; the bridge stamps and checks them. */
const int64_t *ujs_generation_ptr(void);
bool ujs_context_root(ujs_pointer *out);

/* find property `name` on the pointer's type; fills *out */
bool ujs_prop_find(ujs_pointer owner, const char *name, ujs_prop_desc *out);

/* iterate the type's properties: iter==0 starts (returns handle); each next
 * writes one desc and answers true; false = done. free releases it. */
ujs_iter_t ujs_props_iter(ujs_pointer owner);
bool ujs_props_next(ujs_iter_t it, ujs_prop_desc *out);
void ujs_props_iter_free(ujs_iter_t it);

/* ---- typed get. out-params are caller-allocated. ---- */

bool ujs_get_bool(ujs_pointer owner, const char *name, bool *out);
bool ujs_get_int(ujs_pointer owner, const char *name, int64_t *out);
bool ujs_get_float(ujs_pointer owner, const char *name, double *out);
bool ujs_get_string(ujs_pointer owner, const char *name, const char **out);
/* enum: value + the current idname (may be NULL if not wanted) */
bool ujs_get_enum(ujs_pointer owner, const char *name, int64_t *out, const char **idname_out);
bool ujs_get_pointer(ujs_pointer owner, const char *name, ujs_pointer *out);
/* fixed-length float array: out holds array_len doubles */
bool ujs_get_float_array(ujs_pointer owner, const char *name, double *out, int32_t len);
/* dynamic float array: caller frees *out_data with ujs_free (not the caller's
 * own allocator) */
bool ujs_get_float_array_n(ujs_pointer owner, const char *name, double **out_data, int32_t *out_len);

/* ---- typed set ---- */

bool ujs_set_bool(ujs_pointer owner, const char *name, bool v);
bool ujs_set_int(ujs_pointer owner, const char *name, int64_t v);
bool ujs_set_float(ujs_pointer owner, const char *name, double v);
bool ujs_set_string(ujs_pointer owner, const char *name, const char *v);
bool ujs_set_enum(ujs_pointer owner, const char *name, int64_t v);
bool ujs_set_pointer(ujs_pointer owner, const char *name, ujs_pointer v);
bool ujs_set_float_array(ujs_pointer owner, const char *name, const double *v, int32_t len);

/* ---- collections --- */

bool ujs_coll_len(ujs_pointer owner, const char *name, int64_t *out);
ujs_iter_t ujs_coll_iter(ujs_pointer owner, const char *name);
bool ujs_coll_next(ujs_iter_t it, ujs_pointer *out);
void ujs_coll_iter_free(ujs_iter_t it);

/* ---- creation / structural mutation ----------------------------------------
 *
 * The scene-graph editing surface: create ID datablocks (an Object, a Mesh),
 * build mesh geometry, and link/unlink collection members. These are the
 * bpy.data.*.new / Mesh.from_pydata / CollectionObjects.link counterparts.
 */

/* create a new ID datablock of the given RNA type (e.g. "Object", "Mesh"),
 * named `name`, and fill *out with its pointer. */
bool ujs_id_new(const char *type_name, const char *name, ujs_pointer *out);

/* Create an Object named `name` carrying `data` (a Mesh, Curve, Light, ...;
 * a null pointer makes an Empty), its type derived from the data, like
 * bpy.data.objects.new. ujs_id_new("Object", ...) makes an Empty, and an
 * Empty ignores a later data assignment, so data-carrying objects need this. */
bool ujs_object_new(const char *name, ujs_pointer data, ujs_pointer *out);

/* Replace `mesh`'s geometry from vertex positions and polygon indices, like
 * bpy's Mesh.from_pydata. `verts` holds nverts xyz triples. `face_offsets`
 * (nfaces+1 int32) are prefix offsets into `face_indices`; polygon i runs
 * [face_offsets[i], face_offsets[i+1]). */
bool ujs_mesh_from_pydata(ujs_pointer mesh,
                          const double *verts, int32_t nverts,
                          const int32_t *face_offsets, int32_t nfaces,
                          const int32_t *face_indices, int32_t nindices);

/* link/unlink `member` in collection property `name` on `coll` (e.g. the
 * Scene.objects collection). */
bool ujs_coll_add(ujs_pointer coll, const char *name, ujs_pointer member);
bool ujs_coll_remove(ujs_pointer coll, const char *name, ujs_pointer member);

/* ---- operators --------------------------------------------------------------
 *
 * The `bpy.ops` equivalent: run a named operator (e.g.
 * "mesh.primitive_cube_add") with its default arguments, on the host's main
 * thread. Returns true when the operator ran (finished). */

bool ujs_operator_call(const char *opname);

/* Run operator `opname` with `n` properties given as identifier/value
 * strings, each parsed by the property's RNA type ("true"/"false", numbers,
 * comma-separated arrays, enum identifiers). `area` names an editor
 * ("VIEW_3D", "NODE_EDITOR", ...) whose area/region the operator runs in, or
 * NULL for the current context. On failure, ujs_last_error carries the
 * operator's reports or poll message. */
bool ujs_operator_call_ex(const char *opname, int32_t n, const char **keys,
                          const char **vals, const char *area);

/* Call RNA function `fname` on `self` (e.g. BlendDataMaterials.new,
 * NodeLinks.new, ObjectModifiers.new) with `n` named arguments. The first
 * output (the return value) goes to *ret (type UJS_VAL_NONE when the
 * function returns nothing). */
bool ujs_func_call(ujs_pointer self, const char *fname, int32_t n,
                   const char **names, const ujs_value *args, ujs_value *ret);

/* The collection property's function struct (BlendDataMaterials,
 * NodeLinks, ObjectModifiers, ...), the `self` for ujs_func_call on things
 * like bpy.data.materials.new. Fails when the collection has none. */
bool ujs_coll_ptr(ujs_pointer owner, const char *name, ujs_pointer *out);

/* Set an enum property by identifier ("SUN", "BLENDER_EEVEE"); flag enums
 * take comma-separated identifiers. */
bool ujs_set_enum_id(ujs_pointer owner, const char *name, const char *ids);

/* Collection item by string key (`key` non-NULL) or index. */
bool ujs_coll_lookup(ujs_pointer owner, const char *name, const char *key,
                     int64_t index, ujs_pointer *out);

/* Bulk read/write of attribute `attr` across every item of collection
 * `name` (foreach_get / foreach_set), `len` doubles, item-major. */
bool ujs_coll_foreach_get(ujs_pointer owner, const char *name, const char *attr,
                          double *buf, int64_t len);
bool ujs_coll_foreach_set(ujs_pointer owner, const char *name, const char *attr,
                          const double *buf, int64_t len);

/* Int/bool arrays (fixed or dynamic length) as int64. */
bool ujs_get_int_array(ujs_pointer owner, const char *name, int64_t **out, int32_t *len);
bool ujs_set_int_array(ujs_pointer owner, const char *name, const int64_t *v, int32_t len);

/* ---- KX engine (the running game) -------------------------------------------
 *
 * Access to the game engine's runtime object model (KX_GameObject). Distinct
 * from the RNA bridge, which sees the editor-side scene; KX objects exist only
 * while a game is running and carry runtime state (world transforms, physics,
 * sensors). A KX object crosses as a ujs_pointer whose .data is the
 * KX_GameObject*. All functions answer false (with ujs_last_error) when no
 * game is running. */

/* Number of KX objects in the active scene. */
bool ujs_kx_object_count(int64_t *out);

/* The KX object at `index`. */
bool ujs_kx_object_at(int64_t index, ujs_pointer *out);

/* The KX object named `name`, or false when absent. */
bool ujs_kx_object_find(const char *name, ujs_pointer *out);

/* The object's name (host memory, valid until the next ujs_* call). */
bool ujs_kx_name(ujs_pointer obj, const char **out);

/* World position, as 3 doubles [x y z]. */
bool ujs_kx_world_position(ujs_pointer obj, double *xyz);

/* Set the world position from 3 doubles [x y z]. */
bool ujs_kx_set_world_position(ujs_pointer obj, const double *xyz);

/* ---- test-only hooks (mock shim; never called against a real host) ---- */

void ujs_test_reset(void);

/* ---- memory ---------------------------------------------------------------- */

/* Free a pointer this shim handed out (e.g. the *out_data of
 * ujs_get_float_array_n). The caller must release host allocations through
 * here, not through its own allocator: on platforms where the runtime and
 * the host link different heaps (Windows CRT), a cross-heap free corrupts
 * memory. */
void ujs_free(void *ptr);

/* ---- KX runtime (round 3) -------------------------------------------------
 *
 * All take KX object pointers from ujs_kx_object_* and validate them against
 * the running scene: a stale pointer (object removed, game ended) fails with
 * an error instead of touching freed memory. Vectors are double[3],
 * orientations row-major double[9]. */

bool ujs_kx_running(void);
/* end the running game, as bge.logic.endGame() */
bool ujs_kx_end_game(void);
/* what: "position" "scale" "linear_velocity" "angular_velocity"
 * "local_linear_velocity" "local_angular_velocity" */
bool ujs_kx_get_vec(ujs_pointer obj, const char *what, double *out);
bool ujs_kx_set_vec(ujs_pointer obj, const char *what, const double *v);
bool ujs_kx_get_orientation(ujs_pointer obj, double *m9);
bool ujs_kx_set_orientation(ujs_pointer obj, const double *m9);
/* what: "force" "torque" "impulse" (impulse applies at world point `at`) */
bool ujs_kx_apply(ujs_pointer obj, const char *what, const double *v, const double *at, bool local);
bool ujs_kx_get_mass(ujs_pointer obj, double *out);
bool ujs_kx_set_mass(ujs_pointer obj, double mass);
bool ujs_kx_set_damping(ujs_pointer obj, double linear, double angular);
bool ujs_kx_suspend_dynamics(ujs_pointer obj, bool suspend);
/* first hit from `from` to `to`, skipping `ignore` (may be null) */
bool ujs_kx_raycast(const double *from, const double *to, ujs_pointer ignore,
                    ujs_pointer *hit, double *point, double *normal);
bool ujs_kx_get_parent(ujs_pointer obj, ujs_pointer *out);
bool ujs_kx_set_parent(ujs_pointer obj, ujs_pointer parent); /* null parent = remove */
/* spawn a copy of object `name` (an inactive/hidden object, or an active one)
 * at `at`'s transform (null = the original's), living `lifespan` seconds (0 = forever) */
bool ujs_kx_spawn(const char *name, ujs_pointer at, double lifespan, ujs_pointer *out);
bool ujs_kx_remove(ujs_pointer obj);
bool ujs_kx_set_camera(ujs_pointer cam);
bool ujs_kx_set_visible(ujs_pointer obj, bool visible, bool recursive);
/* the Blender Object an engine object was converted from, as an RNA pointer:
 * lights, materials and other datablocks are then reachable through the RNA
 * bridge while the game runs */
bool ujs_kx_blender_object(ujs_pointer obj, ujs_pointer *out);
/* input by bge event name ("WKEY", "LEFTSHIFTKEY", "LEFTMOUSE", ...):
 * bit 1 = active, 2 = just activated, 4 = just released */
bool ujs_kx_input(const char *name, int32_t *status);
bool ujs_kx_mouse(double *xy);            /* normalized 0..1 */
/* normalized; *applied says whether the warp took effect (it is ignored
 * while the game window is not the active window) */
bool ujs_kx_set_mouse(double x, double y, bool *applied);
bool ujs_kx_show_mouse(bool visible);
/* Pointer lock for mouse look: hide the cursor and grab it, so mouse
 * positions keep moving past the window edges (the host wraps the cursor
 * and accumulates the offset) and per-frame differences are pure mouse
 * motion. Locking needs a running game; unlocking works any time. */
bool ujs_kx_pointer_lock(bool lock);
bool ujs_kx_screenshot(const char *path);
/* [average fps, engine clock seconds, last frame timestep] */
bool ujs_kx_frame_info(double *out3);
bool ujs_kx_canvas_size(int32_t *w, int32_t *h);

/* ---- media and overlays (round 4) ------------------------------------------
 *
 * Text: set the string a game text object shows (HUD labels, instruments).
 * Filters: 2D post-process passes on the running scene (bge filterManager);
 * `type` is a RAS_2DFilterManager FILTER_* mode, 12 = custom GLSL.
 * Audio: sounds and handles are opaque heap objects, freed with
 * ujs_snd_free / ujs_snd_handle_free; playback goes to Blender's device. */

bool ujs_kx_set_text(ujs_pointer obj, const char *text);
bool ujs_kx_add_filter(int32_t index, int32_t type, const char *glsl);
bool ujs_kx_remove_filter(int32_t index);
bool ujs_kx_filter_uniform(int32_t index, const char *name, const double *v, int32_t n);

void *ujs_snd_tone(const char *wave, double frequency, double rate); /* sine sawtooth square triangle */
void *ujs_snd_noise(double seconds, double rate);
void *ujs_snd_file(const char *path);
void *ujs_snd_filter(void *sound, const char *kind, double a, double b); /* lowpass highpass volume */
void *ujs_snd_mix(void *a, void *b);
void ujs_snd_free(void *sound);
void *ujs_snd_play(void *sound, int32_t loop); /* loop -1 = forever */
bool ujs_snd_control(void *handle, const char *what, double value); /* pitch volume stop pause resume */
void ujs_snd_handle_free(void *handle);

#ifdef __cplusplus
}
#endif

#endif /* UPBGE_JOLT_SHIM_H */
