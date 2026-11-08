/* upbge-jolt real shim: implements the ujs_* C ABI (native/upbge_shim.h)
 * over RNA (Blender's reflective data API) inside a UPBGE/Blender build.
 *
 * Integration: this file is dropped into the UPBGE source tree and linked
 * into the blender binary with symbols exported (-export_dynamic /
 * -rdynamic). The jolt runtime (libjolt_upbge.dylib, `jolt task lib`)
 * resolves ujs_* from the host process namespace via jolt.ffi defcfn
 * fallback — no dlopen of the host by the runtime.
 *
 * Pointer identity: ujs_pointer.owner is the RNA owner_id (an ID*) cast to
 * int64 — stable while the data lives, so the jolt side can compare
 * pointers for identity. .type is the StructRNA* as int64. .data is the
 * RNA data pointer verbatim. The jolt side never dereferences .data; it
 * hands it back through ujs_* calls.
 *
 * Strings: RNA_property_string_get's std::string overload returns a
 * temporary, so every string out-param copies into a static buffer —
 * "valid until the next ujs_* call" per the ABI.
 *
 * Threading: every ujs_* call arrives on the host's main thread (the jolt
 * runtime marshals via its op queue — see upbge-jolt.core). No locking
 * here; RNA is not thread-safe.
 */

#include "upbge_shim.h"

#include "DNA_ID.h"
#include "RNA_access.hh"
#include "RNA_types.hh"

#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <optional>
#include <string>

/* ---- error surface -------------------------------------------------------- */

static char g_last_error[512];
static char g_str_buf[4096];  /* string out-param lifetime: next ujs_* call */

static void set_err(const char *fmt, ...)
{
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(g_last_error, sizeof g_last_error, fmt, ap);
  va_end(ap);
}

extern "C" const char *ujs_last_error(void)
{
  return g_last_error[0] ? g_last_error : nullptr;
}

/* ---- PointerRNA <-> ujs_pointer ------------------------------------------- */

static PointerRNA as_rna(const ujs_pointer &up)
{
  PointerRNA ptr;
  ptr.owner_id = reinterpret_cast<ID *>(up.owner);
  ptr.data = up.data;
  ptr.type = reinterpret_cast<StructRNA *>(static_cast<intptr_t>(up.type));
  return ptr;
}

static ujs_pointer as_ujs(const PointerRNA &ptr)
{
  ujs_pointer up;
  up.owner = reinterpret_cast<int64_t>(ptr.owner_id);
  up.type = reinterpret_cast<int64_t>(ptr.type);
  up.data = ptr.data;
  return up;
}

/* find a property; nullptr + set_err on miss. An RNA pointer without a struct
 * type is invalid — RNA_struct_find_property would deref the null type — so
 * reject it regardless of whether data is set (a typeless pointer with data is
 * the malformed case the old both-null check let through). */
static PropertyRNA *find_prop(ujs_pointer owner, const char *name)
{
  PointerRNA ptr = as_rna(owner);
  if (ptr.type == nullptr) {
    set_err("invalid pointer");
    return nullptr;
  }
  PropertyRNA *prop = RNA_struct_find_property(&ptr, name);
  if (prop == nullptr) {
    set_err("no such property: %s", name);
    return nullptr;
  }
  return prop;
}

/* find a property AND check its type; error message names the wanted type */
static PropertyRNA *find_prop_typed(ujs_pointer owner, const char *name, PropertyType want)
{
  PropertyRNA *prop = find_prop(owner, name);
  if (prop == nullptr) return nullptr;
  if (RNA_property_type(prop) != want) {
    static const char *names[] = {"bool", "int", "float", "string", "enum", "pointer", "collection"};
    set_err("not a %s property: %s", names[want], name);
    return nullptr;
  }
  return prop;
}

static int ujs_type_of(PropertyRNA *prop)
{
  switch (RNA_property_type(prop)) {
    case PROP_BOOLEAN:    return UJS_PROP_BOOL;
    case PROP_INT:        return UJS_PROP_INT;
    case PROP_FLOAT:      return UJS_PROP_FLOAT;
    case PROP_STRING:     return UJS_PROP_STRING;
    case PROP_ENUM:       return UJS_PROP_ENUM;
    case PROP_POINTER:    return UJS_PROP_POINTER;
    case PROP_COLLECTION: return UJS_PROP_COLLECTION;
  }
  return UJS_PROP_BOOL;
}

/* ---- introspection --------------------------------------------------------- */

static void desc_of(PointerRNA &ptr, PropertyRNA *prop, ujs_prop_desc *out)
{
  snprintf(out->name, sizeof out->name, "%s", RNA_property_identifier(prop));
  out->type = ujs_type_of(prop);
  out->array_len = RNA_property_array_length(&ptr, prop);
  out->item_type[0] = '\0';
  if (out->type == UJS_PROP_POINTER) {
    StructRNA *srna = RNA_property_pointer_type(&ptr, prop);
    if (srna != nullptr) {
      snprintf(out->item_type, sizeof out->item_type, "%s", RNA_struct_identifier(srna));
    }
  }
  else if (out->type == UJS_PROP_COLLECTION) {
    std::optional<PointerRNA> it = RNA_property_collection_type_get(&ptr, prop);
    if (it.has_value() && it->type != nullptr) {
      snprintf(out->item_type, sizeof out->item_type, "%s", RNA_struct_identifier(it->type));
    }
  }
}

extern "C" bool ujs_prop_find(ujs_pointer owner, const char *name, ujs_prop_desc *out)
{
  PropertyRNA *prop = find_prop(owner, name);
  if (prop == nullptr) return false;
  desc_of(as_rna(owner), prop, out);
  return true;
}

/* property enumeration: snapshot the identifiers once (strdup), walk on
 * next(). Written as an explicit loop rather than the RNA_STRUCT_BEGIN
 * macro so the iteration state is ours to keep. */
struct props_iter {
  PointerRNA ptr;
  char **names;
  int count;
  int idx;
};

extern "C" ujs_iter_t ujs_props_iter(ujs_pointer owner)
{
  PointerRNA ptr = as_rna(owner);
  if (ptr.type == nullptr) return 0;
  props_iter *it = (props_iter *)calloc(1, sizeof *it);
  it->ptr = ptr;
  int cap = 16;
  it->names = (char **)malloc(cap * sizeof(char *));
  /* mirror of the RNA_STRUCT_BEGIN macro: iterate the type's property
   * collection, where each item's .data is the PropertyRNA itself */
  CollectionPropertyIterator rna{};
  RNA_property_collection_begin(&ptr, RNA_struct_iterator_property(ptr.type), &rna);
  for (int i = 0; rna.valid; i++, RNA_property_collection_next(&rna)) {
    PropertyRNA *prop = (PropertyRNA *)rna.ptr.data;
    if (prop == nullptr) continue;
    if (i >= cap) {
      cap *= 2;
      it->names = (char **)realloc(it->names, cap * sizeof(char *));
    }
    it->names[it->count++] = strdup(RNA_property_identifier(prop));
  }
  RNA_property_collection_end(&rna);
  if (it->count == 0) {
    free(it->names);
    free(it);
    return 0;
  }
  return (ujs_iter_t)(intptr_t)it;
}

extern "C" bool ujs_props_next(ujs_iter_t h, ujs_prop_desc *out)
{
  props_iter *it = (props_iter *)(intptr_t)h;
  if (it == nullptr || it->idx >= it->count) return false;
  PropertyRNA *prop = RNA_struct_find_property(&it->ptr, it->names[it->idx++]);
  if (prop == nullptr) return ujs_props_next(h, out);  /* vanished: skip */
  desc_of(it->ptr, prop, out);
  return true;
}

extern "C" void ujs_props_iter_free(ujs_iter_t h)
{
  props_iter *it = (props_iter *)(intptr_t)h;
  if (it == nullptr) return;
  for (int i = 0; i < it->count; i++) free(it->names[i]);
  free(it->names);
  free(it);
}

/* ---- typed get --------------------------------------------------------------- */

extern "C" bool ujs_get_bool(ujs_pointer owner, const char *name, bool *out)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_BOOLEAN);
  if (prop == nullptr) return false;
  *out = RNA_property_boolean_get(&as_rna(owner), prop);
  return true;
}

extern "C" bool ujs_get_int(ujs_pointer owner, const char *name, int64_t *out)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_INT);
  if (prop == nullptr) return false;
  *out = RNA_property_int_get(&as_rna(owner), prop);
  return true;
}

extern "C" bool ujs_get_float(ujs_pointer owner, const char *name, double *out)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_FLOAT);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  if (RNA_property_array_check(prop)) {
    set_err("not a float property: %s", name);
    return false;
  }
  *out = RNA_property_float_get(&ptr, prop);
  return true;
}

extern "C" bool ujs_get_string(ujs_pointer owner, const char *name, const char **out)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_STRING);
  if (prop == nullptr) return false;
  std::string s = RNA_property_string_get(&as_rna(owner), prop);
  snprintf(g_str_buf, sizeof g_str_buf, "%s", s.c_str());
  *out = g_str_buf;
  return true;
}

extern "C" bool ujs_get_enum(ujs_pointer owner, const char *name, int64_t *out, const char **idname_out)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_ENUM);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  int value = RNA_property_enum_get(&ptr, prop);
  *out = value;
  if (idname_out != nullptr) {
    const char *idname = nullptr;
    if (RNA_property_enum_identifier(nullptr, &ptr, prop, value, &idname)) {
      snprintf(g_str_buf, sizeof g_str_buf, "%s", idname != nullptr ? idname : "");
      *idname_out = g_str_buf;
    }
    else {
      *idname_out = nullptr;
    }
  }
  return true;
}

extern "C" bool ujs_get_pointer(ujs_pointer owner, const char *name, ujs_pointer *out)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_POINTER);
  if (prop == nullptr) return false;
  PointerRNA tgt = RNA_property_pointer_get(&as_rna(owner), prop);
  if (tgt.data == nullptr) {
    out->owner = 0;
    out->type = 0;
    out->data = nullptr;
    return true;
  }
  *out = as_ujs(tgt);
  return true;
}

/* RNA floats are 32-bit; jolt gets doubles. Small arrays go through a stack
 * buffer, large ones a malloc. */
extern "C" bool ujs_get_float_array(ujs_pointer owner, const char *name, double *out, int32_t len)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_FLOAT);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  if (!RNA_property_array_check(prop)) {
    set_err("not a float array property: %s", name);
    return false;
  }
  int n = RNA_property_array_length(&ptr, prop);
  if (len != n) {
    set_err("array length mismatch: property has %d, caller sent %d", n, len);
    return false;
  }
  float tmp[64];
  float *src = (n <= 64) ? tmp : (float *)malloc(n * sizeof(float));
  RNA_property_float_get_array(&ptr, prop, src);
  for (int i = 0; i < n; i++) out[i] = src[i];
  if (src != tmp) free(src);
  return true;
}

extern "C" bool ujs_get_float_array_n(ujs_pointer owner, const char *name, double **out_data, int32_t *out_len)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_FLOAT);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  if (!RNA_property_array_check(prop)) {
    set_err("not a float array property: %s", name);
    return false;
  }
  int n = RNA_property_array_length(&ptr, prop);
  float *tmp = (float *)malloc(n * sizeof(float));
  RNA_property_float_get_array(&ptr, prop, tmp);
  double *d = (double *)malloc(n * sizeof(double));
  for (int i = 0; i < n; i++) d[i] = tmp[i];
  free(tmp);
  *out_data = d;
  *out_len = n;
  return true;
}

/* ---- typed set ---------------------------------------------------------------- */

extern "C" bool ujs_set_bool(ujs_pointer owner, const char *name, bool v)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_BOOLEAN);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  RNA_property_boolean_set(&ptr, prop, v);
  RNA_property_update_auto(&ptr, prop);
  return true;
}

extern "C" bool ujs_set_int(ujs_pointer owner, const char *name, int64_t v)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_INT);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  RNA_property_int_set(&ptr, prop, (int)v);
  RNA_property_update_auto(&ptr, prop);
  return true;
}

extern "C" bool ujs_set_float(ujs_pointer owner, const char *name, double v)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_FLOAT);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  if (RNA_property_array_check(prop)) {
    set_err("not a float property: %s", name);
    return false;
  }
  RNA_property_float_set(&ptr, prop, (float)v);
  RNA_property_update_auto(&ptr, prop);
  return true;
}

extern "C" bool ujs_set_string(ujs_pointer owner, const char *name, const char *v)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_STRING);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  RNA_property_string_set(&ptr, prop, v);
  RNA_property_update_auto(&ptr, prop);
  return true;
}

extern "C" bool ujs_set_enum(ujs_pointer owner, const char *name, int64_t v)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_ENUM);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  RNA_property_enum_set(&ptr, prop, (int)v);
  RNA_property_update_auto(&ptr, prop);
  return true;
}

extern "C" bool ujs_set_pointer(ujs_pointer owner, const char *name, ujs_pointer v)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_POINTER);
  if (prop == nullptr) return false;
  PointerRNA tgt = as_rna(v);
  RNA_property_pointer_set(&as_rna(owner), prop, tgt, nullptr);
  return true;
}

extern "C" bool ujs_set_float_array(ujs_pointer owner, const char *name, const double *v, int32_t len)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_FLOAT);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  if (!RNA_property_array_check(prop)) {
    set_err("not a float array property: %s", name);
    return false;
  }
  int n = RNA_property_array_length(&ptr, prop);
  if (len != n) {
    set_err("array length mismatch: property has %d, caller sent %d", n, len);
    return false;
  }
  float *tmp = (float *)malloc(n * sizeof(float));
  for (int i = 0; i < n; i++) tmp[i] = (float)v[i];
  RNA_property_float_set_array(&ptr, prop, tmp);
  RNA_property_update_auto(&ptr, prop);
  free(tmp);
  return true;
}

/* ---- collections ------------------------------------------------------------------ */

extern "C" bool ujs_coll_len(ujs_pointer owner, const char *name, int64_t *out)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_COLLECTION);
  if (prop == nullptr) return false;
  *out = RNA_property_collection_length(&as_rna(owner), prop);
  return true;
}

struct coll_iter {
  CollectionPropertyIterator rna;
};

extern "C" ujs_iter_t ujs_coll_iter(ujs_pointer owner, const char *name)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_COLLECTION);
  if (prop == nullptr) return 0;
  coll_iter *it = (coll_iter *)calloc(1, sizeof *it);
  RNA_property_collection_begin(&as_rna(owner), prop, &it->rna);
  return (ujs_iter_t)(intptr_t)it;
}

extern "C" bool ujs_coll_next(ujs_iter_t h, ujs_pointer *out)
{
  coll_iter *it = (coll_iter *)(intptr_t)h;
  if (it == nullptr || !it->rna.valid) return false;
  *out = as_ujs(it->rna.ptr);
  RNA_property_collection_next(&it->rna);
  return true;
}

extern "C" void ujs_coll_iter_free(ujs_iter_t h)
{
  coll_iter *it = (coll_iter *)(intptr_t)h;
  if (it == nullptr) return;
  RNA_property_collection_end(&it->rna);
  free(it);
}

/* ---- root -------------------------------------------------------------------------- */

/* The editor-side root: set by the host integration (the WM timer hook) to
 * the context's scene data. The bridge reads it only on the host's tick
 * thread (core/marshal marshals there), the same thread the integration runs
 * uj_set_root on before the frame loop starts, so there is no cross-thread
 * access to g_root: set it once, before the first uj_tick / uj_start_repl. */
static ujs_pointer g_root = {0, 0, nullptr};

extern "C" void uj_set_root(ujs_pointer root)
{
  g_root = root;
}

extern "C" bool ujs_root(ujs_pointer *out)
{
  if (g_root.data == nullptr) {
    set_err("no root set; the host integration must call uj_set_root");
    return false;
  }
  *out = g_root;
  return true;
}

extern "C" void ujs_test_reset(void) {}

/* free memory this shim handed out (ujs_get_float_array_n); the caller must
 * release through here, not its own allocator */
extern "C" void ujs_free(void *ptr) { free(ptr); }
