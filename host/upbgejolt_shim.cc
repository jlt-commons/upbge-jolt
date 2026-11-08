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
#include "RNA_prototypes.hh"
#include "RNA_types.hh"

#include "DNA_collection_types.h"
#include "DNA_mesh_types.h"
#include "DNA_meshdata_types.h"
#include "DNA_object_types.h"
#include "DNA_scene_types.h"

#include "BKE_collection.hh"
#include "BKE_global.hh"
#include "BKE_idtype.hh"
#include "BKE_lib_id.hh"
#include "BKE_main.hh"
#include "BKE_material.hh"
#include "BKE_object.hh"
#include "BKE_mesh.h"
#include "BKE_mesh.hh"

#include "DEG_depsgraph.hh"
#include "DEG_depsgraph_build.hh"

#include "WM_api.hh"
#include "bpy_capi_utils.hh"

#include "EXP_ListValue.h"
#include "KX_GameObject.h"
#include "KX_Globals.h"
#include "KX_Scene.h"
#include "MT_Vector3.h"

#include "BLI_math_vector_types.hh"
#include "BLI_span.hh"

#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <optional>
#include <string>

/* Blender 4.x moved the RNA/DNA types into `namespace blender`. */
using namespace blender;

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
 * reject it regardless of whether data is set. */
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

/* Run the property's RNA update after a write, as bpy does: tags the
 * depsgraph and sends notifiers, so the viewport, the properties editor and
 * the evaluated scene see the change. Without a context (headless startup)
 * fall back to the Main-only update. */
static void prop_updated(PointerRNA &ptr, PropertyRNA *prop)
{
  bContext *C = BPY_context_get();
  if (C != nullptr) {
    RNA_property_update(C, &ptr, prop);
  }
  else {
    RNA_property_update_main(G_MAIN, nullptr, &ptr, prop);
  }
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
  PointerRNA ptr = as_rna(owner);
  desc_of(ptr, prop, out);
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
  /* new, not calloc: PointerRNA holds a Vector, so its members must be
   * constructed before the assignment below. */
  props_iter *it = new props_iter();
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
    delete it;
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
  delete it;
}

/* ---- typed get --------------------------------------------------------------- */

extern "C" bool ujs_get_bool(ujs_pointer owner, const char *name, bool *out)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_BOOLEAN);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  *out = RNA_property_boolean_get(&ptr, prop);
  return true;
}

extern "C" bool ujs_get_int(ujs_pointer owner, const char *name, int64_t *out)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_INT);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  *out = RNA_property_int_get(&ptr, prop);
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
  PointerRNA ptr = as_rna(owner);
  std::string s = RNA_property_string_get(&ptr, prop);
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
  PointerRNA ptr = as_rna(owner);
  PointerRNA tgt = RNA_property_pointer_get(&ptr, prop);
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

/* ---- typed set ----------------------------------------------------------------
 *
 * Every write runs the property's RNA update (prop_updated), the same path
 * bpy takes, so dependents re-evaluate and editors redraw. */

extern "C" bool ujs_set_bool(ujs_pointer owner, const char *name, bool v)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_BOOLEAN);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  RNA_property_boolean_set(&ptr, prop, v);
  prop_updated(ptr, prop);
  return true;
}

extern "C" bool ujs_set_int(ujs_pointer owner, const char *name, int64_t v)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_INT);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  RNA_property_int_set(&ptr, prop, (int)v);
  prop_updated(ptr, prop);
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
  prop_updated(ptr, prop);
  return true;
}

extern "C" bool ujs_set_string(ujs_pointer owner, const char *name, const char *v)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_STRING);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  RNA_property_string_set(&ptr, prop, v);
  prop_updated(ptr, prop);
  return true;
}

extern "C" bool ujs_set_enum(ujs_pointer owner, const char *name, int64_t v)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_ENUM);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  RNA_property_enum_set(&ptr, prop, (int)v);
  prop_updated(ptr, prop);
  return true;
}

extern "C" bool ujs_set_pointer(ujs_pointer owner, const char *name, ujs_pointer v)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_POINTER);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  PointerRNA tgt = as_rna(v);
  RNA_property_pointer_set(&ptr, prop, tgt, nullptr);
  prop_updated(ptr, prop);
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
  free(tmp);
  prop_updated(ptr, prop);
  return true;
}

/* ---- collections ------------------------------------------------------------------ */

extern "C" bool ujs_coll_len(ujs_pointer owner, const char *name, int64_t *out)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_COLLECTION);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  *out = RNA_property_collection_length(&ptr, prop);
  return true;
}

struct coll_iter {
  CollectionPropertyIterator rna;
};

extern "C" ujs_iter_t ujs_coll_iter(ujs_pointer owner, const char *name)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_COLLECTION);
  if (prop == nullptr) return 0;
  PointerRNA ptr = as_rna(owner);
  /* new, not calloc: CollectionPropertyIterator holds PointerRNA members with
   * Vectors that must be constructed before RNA_property_collection_begin's
   * `*iter = {}` assignment destroys and reassigns them. */
  coll_iter *it = new coll_iter();
  RNA_property_collection_begin(&ptr, prop, &it->rna);
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
  delete it;
}

/* ---- creation / structural mutation ------------------------------------------ */

extern "C" bool ujs_id_new(const char *type_name, const char *name, ujs_pointer *out)
{
  short idcode = BKE_idtype_idcode_from_name(type_name);
  if (idcode == 0) {
    set_err("unknown id type: %s", type_name);
    return false;
  }
  ID *id = static_cast<ID *>(BKE_id_new(G_MAIN, idcode, name));
  if (id == nullptr) {
    set_err("failed to create id: %s", name);
    return false;
  }
  DEG_relations_tag_update(G_MAIN);
  WM_main_add_notifier(NC_ID | NA_ADDED, nullptr);
  *out = as_ujs(RNA_id_pointer_create(id));
  return true;
}

/* bpy.data.objects.new: the object's type comes from its data (nullptr
 * data = Empty). An Empty ignores a later RNA data assignment, which is why
 * ujs_id_new("Object", ...) cannot build a mesh object. */
extern "C" bool ujs_object_new(const char *name, ujs_pointer data, ujs_pointer *out)
{
  ID *id = static_cast<ID *>(data.data);
  int type = OB_EMPTY;
  if (id != nullptr) {
    if (id->tag & ID_TAG_NO_MAIN) {
      set_err("cannot create an object from evaluated data");
      return false;
    }
    type = BKE_object_obdata_to_type(id);
    if (type == -1) {
      set_err("ID type is not valid object data: %s", id->name);
      return false;
    }
    id_us_plus(id);
  }
  Object *ob = BKE_object_add_only_object(G_MAIN, ObjectType(type), name);
  ob->data = id;
  BKE_object_materials_sync_length(G_MAIN, ob, id);
  DEG_relations_tag_update(G_MAIN);
  WM_main_add_notifier(NC_ID | NA_ADDED, nullptr);
  *out = as_ujs(RNA_id_pointer_create(&ob->id));
  return true;
}

extern "C" bool ujs_mesh_from_pydata(ujs_pointer mesh,
                                     const double *verts, int32_t nverts,
                                     const int32_t *face_offsets, int32_t nfaces,
                                     const int32_t *face_indices, int32_t nindices)
{
  PointerRNA ptr = as_rna(mesh);
  if (ptr.type == nullptr || ptr.data == nullptr) {
    set_err("invalid mesh");
    return false;
  }
  if (nfaces > 0 &&
      (face_offsets[0] != 0 || face_offsets[nfaces] != nindices)) {
    set_err("bad face offsets");
    return false;
  }
  for (int i = 0; i < nindices; i++) {
    if (face_indices[i] < 0 || face_indices[i] >= nverts) {
      set_err("face index out of range");
      return false;
    }
  }

  Mesh *dst = static_cast<Mesh *>(ptr.data);
  Mesh *me = BKE_mesh_new_nomain(nverts, 0, nfaces, nindices);
  MutableSpan<float3> positions = me->vert_positions_for_write();
  MutableSpan<int> out_offsets = me->face_offsets_for_write();
  MutableSpan<int> corner_verts = me->corner_verts_for_write();
  for (int i = 0; i < nverts; i++) {
    positions[i] = float3((float)verts[i * 3], (float)verts[i * 3 + 1], (float)verts[i * 3 + 2]);
  }
  for (int f = 0; f <= nfaces; f++) {
    out_offsets[f] = face_offsets[f];
  }
  for (int i = 0; i < nindices; i++) {
    corner_verts[i] = face_indices[i];
  }
  bke::mesh_calc_edges(*me, false, false);
  BKE_mesh_nomain_to_mesh(me, dst, nullptr);
  DEG_id_tag_update(&dst->id, ID_RECALC_GEOMETRY);
  WM_main_add_notifier(NC_GEOM | ND_DATA, &dst->id);
  return true;
}

/* resolve the collection a collection property names. The mock and the scene
 * graph agree on "objects" being Scene.objects (the master collection's
 * linked objects). */
static Collection *coll_of(ujs_pointer coll, const char *name)
{
  PointerRNA ptr = as_rna(coll);
  if (ptr.type == nullptr || ptr.data == nullptr) return nullptr;
  if (strcmp(name, "objects") == 0 &&
      RNA_struct_is_a(ptr.type, RNA_Scene)) {
    return static_cast<Scene *>(ptr.data)->master_collection;
  }
  return nullptr;
}

extern "C" bool ujs_coll_add(ujs_pointer coll, const char *name, ujs_pointer member)
{
  Collection *col = coll_of(coll, name);
  PointerRNA mptr = as_rna(member);
  if (col == nullptr || mptr.data == nullptr) {
    set_err("not a collection property");
    return false;
  }
  BKE_collection_object_add(G_MAIN, col, static_cast<Object *>(mptr.data));
  DEG_id_tag_update(&col->id, ID_RECALC_SYNC_TO_EVAL);
  DEG_relations_tag_update(G_MAIN);
  WM_main_add_notifier(NC_SCENE | ND_LAYER_CONTENT, nullptr);
  return true;
}

extern "C" bool ujs_coll_remove(ujs_pointer coll, const char *name, ujs_pointer member)
{
  Collection *col = coll_of(coll, name);
  PointerRNA mptr = as_rna(member);
  if (col == nullptr || mptr.data == nullptr) {
    set_err("not a collection property");
    return false;
  }
  /* Scene.objects lists objects in every collection of the scene, not just
   * the master one, so unlinking from it must remove from all of them. */
  BKE_scene_collections_object_remove(G_MAIN, static_cast<Scene *>(as_rna(coll).data),
                                      static_cast<Object *>(mptr.data), false);
  DEG_id_tag_update(&col->id, ID_RECALC_SYNC_TO_EVAL);
  DEG_relations_tag_update(G_MAIN);
  WM_main_add_notifier(NC_SCENE | ND_LAYER_CONTENT, nullptr);
  return true;
}

/* ---- operators ---------------------------------------------------------------- */

extern "C" bool ujs_operator_call(const char *opname)
{
  bContext *C = BPY_context_get();
  if (C == nullptr) {
    set_err("no context");
    return false;
  }
  if (WM_operatortype_find(opname, true) == nullptr) {
    set_err("unknown operator: %s", opname);
    return false;
  }
  wmOperatorStatus status = WM_operator_name_call(
      C, opname, wm::OpCallContext::ExecDefault, nullptr, nullptr);
  if (!(status & OPERATOR_FINISHED)) {
    set_err("operator %s did not finish (%s)", opname,
            (status & OPERATOR_CANCELLED) ? "cancelled: poll failed or needs arguments" :
                                            "running modal or passed through");
    return false;
  }
  return true;
}

/* ---- KX engine (the running game) -------------------------------------------- */

static KX_GameObject *kx_obj(ujs_pointer obj)
{
  return static_cast<KX_GameObject *>(obj.data);
}

extern "C" bool ujs_kx_object_count(int64_t *out)
{
  KX_Scene *scene = KX_GetActiveScene();
  if (scene == nullptr) {
    set_err("no game running");
    return false;
  }
  *out = scene->GetObjectList()->GetCount();
  return true;
}

extern "C" bool ujs_kx_object_at(int64_t index, ujs_pointer *out)
{
  KX_Scene *scene = KX_GetActiveScene();
  if (scene == nullptr) {
    set_err("no game running");
    return false;
  }
  EXP_ListValue<KX_GameObject> *list = scene->GetObjectList();
  if (index < 0 || index >= list->GetCount()) {
    set_err("index out of range");
    return false;
  }
  out->owner = 0;
  out->type = 0;
  out->data = list->GetValue(index);
  return true;
}

extern "C" bool ujs_kx_object_find(const char *name, ujs_pointer *out)
{
  KX_Scene *scene = KX_GetActiveScene();
  if (scene == nullptr) {
    set_err("no game running");
    return false;
  }
  EXP_ListValue<KX_GameObject> *list = scene->GetObjectList();
  for (int i = 0; i < list->GetCount(); i++) {
    KX_GameObject *obj = list->GetValue(i);
    if (obj->GetName() == name) {
      out->owner = 0;
      out->type = 0;
      out->data = obj;
      return true;
    }
  }
  set_err("no such object");
  return false;
}

extern "C" bool ujs_kx_name(ujs_pointer obj, const char **out)
{
  KX_GameObject *o = kx_obj(obj);
  if (o == nullptr) {
    set_err("invalid kx object");
    return false;
  }
  snprintf(g_str_buf, sizeof g_str_buf, "%s", o->GetName().c_str());
  *out = g_str_buf;
  return true;
}

extern "C" bool ujs_kx_world_position(ujs_pointer obj, double *xyz)
{
  KX_GameObject *o = kx_obj(obj);
  if (o == nullptr) {
    set_err("invalid kx object");
    return false;
  }
  const MT_Vector3 &pos = o->NodeGetWorldPosition();
  xyz[0] = pos.x();
  xyz[1] = pos.y();
  xyz[2] = pos.z();
  return true;
}

extern "C" bool ujs_kx_set_world_position(ujs_pointer obj, const double *xyz)
{
  KX_GameObject *o = kx_obj(obj);
  if (o == nullptr) {
    set_err("invalid kx object");
    return false;
  }
  o->NodeSetLocalPosition(MT_Vector3(xyz[0], xyz[1], xyz[2]));
  return true;
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
