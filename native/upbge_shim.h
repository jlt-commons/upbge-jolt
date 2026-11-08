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

/* ---- lifecycle / introspection ---- */

/* last error message from a failed call, or NULL */
const char *ujs_last_error(void);

/* the root pointer for editor-style access. Real shim: the RNA pointer for
 * bpy.context's scene data (or Main). Mock: the mock Scene. */
bool ujs_root(ujs_pointer *out);

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

/* ---- test-only hooks (mock shim; never called against a real host) ---- */

void ujs_test_reset(void);

/* ---- memory ---------------------------------------------------------------- */

/* Free a pointer this shim handed out (e.g. the *out_data of
 * ujs_get_float_array_n). The caller must release host allocations through
 * here, not through its own allocator: on platforms where the runtime and
 * the host link different heaps (Windows CRT), a cross-heap free corrupts
 * memory. */
void ujs_free(void *ptr);

#ifdef __cplusplus
}
#endif

#endif /* UPBGE_JOLT_SHIM_H */
