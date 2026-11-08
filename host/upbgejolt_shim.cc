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
#include "WM_types.hh"

#include "BKE_context.hh"
#include "MEM_guardedalloc.h"
#include "BKE_report.hh"
#include "BKE_screen.hh"
#include "DNA_screen_types.h"
#include "DNA_space_types.h"
#include "DNA_windowmanager_types.h"
#include "RNA_enum_types.hh"
#include "bpy_capi_utils.hh"

#include "EXP_ListValue.h"
#include "KX_GameObject.h"
#include "KX_Globals.h"
#include "KX_Scene.h"
#include "KX_Camera.h"
#include "KX_FontObject.h"
#include "RAS_2DFilter.h"
#include "RAS_2DFilterManager.h"
#include "RAS_Shader.h"
#include "BKE_sound.hh"

#include "Exception.h"
#include "devices/IHandle.h"
#include "devices/IDevice.h"
#include "file/File.h"
#include "fx/Highpass.h"
#include "fx/Lowpass.h"
#include "fx/Volume.h"
#include "generator/Sawtooth.h"
#include "generator/Sine.h"
#include "generator/Square.h"
#include "generator/Triangle.h"
#include "sequence/Superpose.h"
#include "util/Buffer.h"
#include "util/StreamBuffer.h"
#include "KX_ClientObjectInfo.h"
#include "KX_KetsjiEngine.h"
#include "KX_RayCast.h"
#include "PHY_IPhysicsController.h"
#include "RAS_ICanvas.h"
#include "SCA_IInputDevice.h"
#include "SCA_InputEvent.h"
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
static char g_str_buf[4096];  /* short string out-params; lifetime: next ujs_* call */
static std::string g_long_str; /* unbounded string results, same lifetime */

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
  g_long_str = RNA_property_string_get(&ptr, prop);
  *out = g_long_str.c_str();
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

/* KX_GetActiveScene() is not cleared when a game ends, so it cannot say
 * whether one is running. The host ticks through uj_host_game_tick from the
 * game loop and through uj_host_tick otherwise (a WM timer cannot fire while
 * the game loop owns the main thread), which tracks it exactly. */
static bool g_in_game = false;

extern "C" void uj_shim_set_in_game(bool in_game)
{
  g_in_game = in_game;
}

static KX_Scene *kx_scene()
{
  return g_in_game ? KX_GetActiveScene() : nullptr;
}

/* A KX object pointer, only if it is still in the running scene (active or
 * inactive list); nullptr + error otherwise. */
static KX_GameObject *kx_obj(ujs_pointer obj)
{
  KX_Scene *scene = kx_scene();
  if (scene == nullptr) {
    set_err("no game running");
    return nullptr;
  }
  if (obj.data == nullptr) {
    set_err("invalid kx object");
    return nullptr;
  }
  for (EXP_ListValue<KX_GameObject> *list : {scene->GetObjectList(), scene->GetInactiveList()}) {
    for (int i = 0; i < list->GetCount(); i++) {
      if (list->GetValue(i) == obj.data) return list->GetValue(i);
    }
  }
  set_err("stale kx object (removed, or from an earlier game run)");
  return nullptr;
}

static ujs_pointer kx_ptr(KX_GameObject *o)
{
  ujs_pointer p = {0, 0, o};
  return p;
}

extern "C" bool ujs_kx_object_count(int64_t *out)
{
  KX_Scene *scene = kx_scene();
  if (scene == nullptr) {
    set_err("no game running");
    return false;
  }
  *out = scene->GetObjectList()->GetCount();
  return true;
}

extern "C" bool ujs_kx_object_at(int64_t index, ujs_pointer *out)
{
  KX_Scene *scene = kx_scene();
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
  KX_Scene *scene = kx_scene();
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

/* ---- KX runtime (round 3) ------------------------------------------------------ */

extern "C" bool ujs_kx_running(void)
{
  return kx_scene() != nullptr;
}

extern "C" bool ujs_kx_end_game(void)
{
  KX_KetsjiEngine *engine = KX_GetActiveEngine();
  if (engine == nullptr || kx_scene() == nullptr) {
    set_err("no game running");
    return false;
  }
  engine->RequestExit(KX_ExitRequest::QUIT_GAME);
  return true;
}

extern "C" bool ujs_kx_get_vec(ujs_pointer obj, const char *what, double *out)
{
  KX_GameObject *o = kx_obj(obj);
  if (o == nullptr) return false;
  MT_Vector3 v;
  if (strcmp(what, "position") == 0) v = o->NodeGetWorldPosition();
  else if (strcmp(what, "scale") == 0) v = o->NodeGetWorldScaling();
  else if (strcmp(what, "linear_velocity") == 0) v = o->GetLinearVelocity(false);
  else if (strcmp(what, "angular_velocity") == 0) v = o->GetAngularVelocity(false);
  else if (strcmp(what, "local_linear_velocity") == 0) v = o->GetLinearVelocity(true);
  else if (strcmp(what, "local_angular_velocity") == 0) v = o->GetAngularVelocity(true);
  else {
    set_err("unknown vector: %s", what);
    return false;
  }
  out[0] = v.x(); out[1] = v.y(); out[2] = v.z();
  return true;
}

extern "C" bool ujs_kx_set_vec(ujs_pointer obj, const char *what, const double *in)
{
  KX_GameObject *o = kx_obj(obj);
  if (o == nullptr) return false;
  MT_Vector3 v(in[0], in[1], in[2]);
  if (strcmp(what, "position") == 0) o->NodeSetWorldPosition(v);
  else if (strcmp(what, "scale") == 0) o->NodeSetWorldScale(v);
  else if (strcmp(what, "linear_velocity") == 0) o->setLinearVelocity(v, false);
  else if (strcmp(what, "angular_velocity") == 0) o->setAngularVelocity(v, false);
  else if (strcmp(what, "local_linear_velocity") == 0) o->setLinearVelocity(v, true);
  else if (strcmp(what, "local_angular_velocity") == 0) o->setAngularVelocity(v, true);
  else {
    set_err("unknown vector: %s", what);
    return false;
  }
  o->NodeUpdateGS(0.0);
  return true;
}

extern "C" bool ujs_kx_get_orientation(ujs_pointer obj, double *m9)
{
  KX_GameObject *o = kx_obj(obj);
  if (o == nullptr) return false;
  const MT_Matrix3x3 &m = o->NodeGetWorldOrientation();
  for (int r = 0; r < 3; r++)
    for (int c = 0; c < 3; c++) m9[r * 3 + c] = m[r][c];
  return true;
}

extern "C" bool ujs_kx_set_orientation(ujs_pointer obj, const double *m9)
{
  KX_GameObject *o = kx_obj(obj);
  if (o == nullptr) return false;
  MT_Matrix3x3 m(m9[0], m9[1], m9[2], m9[3], m9[4], m9[5], m9[6], m9[7], m9[8]);
  o->NodeSetGlobalOrientation(m);
  o->NodeUpdateGS(0.0);
  return true;
}

static PHY_IPhysicsController *kx_phys(KX_GameObject *o)
{
  PHY_IPhysicsController *pc = o->GetPhysicsController();
  if (pc == nullptr) set_err("%s has no physics", o->GetName().c_str());
  return pc;
}

extern "C" bool ujs_kx_apply(ujs_pointer obj, const char *what, const double *v, const double *at, bool local)
{
  KX_GameObject *o = kx_obj(obj);
  if (o == nullptr) return false;
  MT_Vector3 vec(v[0], v[1], v[2]);
  if (strcmp(what, "force") == 0) {
    o->ApplyForce(vec, local);
  }
  else if (strcmp(what, "torque") == 0) {
    o->ApplyTorque(vec, local);
  }
  else if (strcmp(what, "impulse") == 0) {
    PHY_IPhysicsController *pc = kx_phys(o);
    if (pc == nullptr) return false;
    MT_Vector3 point = at ? MT_Vector3(at[0], at[1], at[2]) : o->NodeGetWorldPosition();
    pc->ApplyImpulse(point, vec, local);
  }
  else {
    set_err("unknown apply: %s", what);
    return false;
  }
  return true;
}

extern "C" bool ujs_kx_get_mass(ujs_pointer obj, double *out)
{
  KX_GameObject *o = kx_obj(obj);
  if (o == nullptr) return false;
  *out = o->GetMass();
  return true;
}

extern "C" bool ujs_kx_set_mass(ujs_pointer obj, double mass)
{
  KX_GameObject *o = kx_obj(obj);
  if (o == nullptr) return false;
  PHY_IPhysicsController *pc = kx_phys(o);
  if (pc == nullptr) return false;
  pc->SetMass(mass);
  return true;
}

extern "C" bool ujs_kx_set_damping(ujs_pointer obj, double linear, double angular)
{
  KX_GameObject *o = kx_obj(obj);
  if (o == nullptr) return false;
  if (kx_phys(o) == nullptr) return false;
  o->setDamping((float)linear, (float)angular);
  return true;
}

extern "C" bool ujs_kx_suspend_dynamics(ujs_pointer obj, bool suspend)
{
  KX_GameObject *o = kx_obj(obj);
  if (o == nullptr) return false;
  PHY_IPhysicsController *pc = kx_phys(o);
  if (pc == nullptr) return false;
  if (suspend) pc->SuspendDynamics(false);
  else pc->RestoreDynamics();
  return true;
}

/* KX_RayCast::Callback target: first hit, skipping one object */
struct ShimRay {
  KX_GameObject *ignore = nullptr;
  KX_GameObject *hit = nullptr;
  bool RayHit(KX_ClientObjectInfo *client, KX_RayCast * /*result*/, void * /*data*/)
  {
    hit = client->m_gameobject;
    return true;
  }
  bool NeedRayCast(KX_ClientObjectInfo *client, void * /*data*/)
  {
    return client->m_gameobject != ignore;
  }
};

extern "C" bool ujs_kx_raycast(const double *from, const double *to, ujs_pointer ignore,
                               ujs_pointer *hit, double *point, double *normal)
{
  KX_Scene *scene = kx_scene();
  if (scene == nullptr) {
    set_err("no game running");
    return false;
  }
  ShimRay ray;
  PHY_IPhysicsController *ignore_pc = nullptr;
  if (ignore.data != nullptr) {
    ray.ignore = kx_obj(ignore);
    if (ray.ignore == nullptr) return false;
    ignore_pc = ray.ignore->GetPhysicsController();
  }
  KX_RayCast::Callback<ShimRay, void> callback(&ray, ignore_pc, nullptr);
  MT_Vector3 f(from[0], from[1], from[2]), t(to[0], to[1], to[2]);
  hit->owner = 0; hit->type = 0; hit->data = nullptr;
  if (KX_RayCast::RayTest(scene->GetPhysicsEnvironment(), f, t, callback) && ray.hit) {
    *hit = kx_ptr(ray.hit);
    for (int i = 0; i < 3; i++) {
      point[i] = callback.m_hitPoint[i];
      normal[i] = callback.m_hitNormal[i];
    }
  }
  return true; /* a miss is not an error: hit->data stays null */
}

extern "C" bool ujs_kx_get_parent(ujs_pointer obj, ujs_pointer *out)
{
  KX_GameObject *o = kx_obj(obj);
  if (o == nullptr) return false;
  *out = kx_ptr(o->GetParent());
  return true;
}

extern "C" bool ujs_kx_set_parent(ujs_pointer obj, ujs_pointer parent)
{
  KX_GameObject *o = kx_obj(obj);
  if (o == nullptr) return false;
  if (parent.data == nullptr) {
    o->RemoveParent();
    return true;
  }
  KX_GameObject *p = kx_obj(parent);
  if (p == nullptr) return false;
  o->SetParent(p, true, true);
  return true;
}

extern "C" bool ujs_kx_spawn(const char *name, ujs_pointer at, double lifespan, ujs_pointer *out)
{
  KX_Scene *scene = kx_scene();
  if (scene == nullptr) {
    set_err("no game running");
    return false;
  }
  KX_GameObject *original = nullptr;
  for (EXP_ListValue<KX_GameObject> *list : {scene->GetInactiveList(), scene->GetObjectList()}) {
    for (int i = 0; i < list->GetCount() && original == nullptr; i++) {
      if (list->GetValue(i)->GetName() == name) original = list->GetValue(i);
    }
  }
  if (original == nullptr) {
    set_err("no object named %s to spawn", name);
    return false;
  }
  KX_GameObject *ref = nullptr;
  if (at.data != nullptr) {
    ref = kx_obj(at);
    if (ref == nullptr) return false;
  }
  KX_GameObject *made = scene->AddReplicaObject(original, ref ? ref : original, (float)lifespan);
  if (made == nullptr) {
    set_err("could not spawn %s", name);
    return false;
  }
  *out = kx_ptr(made);
  return true;
}

extern "C" bool ujs_kx_remove(ujs_pointer obj)
{
  KX_GameObject *o = kx_obj(obj);
  if (o == nullptr) return false;
  kx_scene()->DelayedRemoveObject(o);
  return true;
}

extern "C" bool ujs_kx_set_camera(ujs_pointer cam)
{
  KX_GameObject *o = kx_obj(cam);
  if (o == nullptr) return false;
  KX_Scene *scene = kx_scene();
  EXP_ListValue<KX_Camera> *cams = scene->GetCameraList();
  for (int i = 0; i < cams->GetCount(); i++) {
    if (static_cast<KX_GameObject *>(cams->GetValue(i)) == o) {
      scene->SetActiveCamera(cams->GetValue(i));
      return true;
    }
  }
  set_err("%s is not a camera", o->GetName().c_str());
  return false;
}

extern "C" bool ujs_kx_set_visible(ujs_pointer obj, bool visible, bool recursive)
{
  KX_GameObject *o = kx_obj(obj);
  if (o == nullptr) return false;
  o->SetVisible(visible, recursive);
  return true;
}

extern "C" bool ujs_kx_blender_object(ujs_pointer obj, ujs_pointer *out)
{
  KX_GameObject *o = kx_obj(obj);
  if (o == nullptr) return false;
  Object *ob = o->GetBlenderObject();
  if (ob == nullptr) {
    set_err("%s has no Blender object", o->GetName().c_str());
    return false;
  }
  *out = as_ujs(RNA_id_pointer_create(&ob->id));
  return true;
}

struct KxInputName {
  const char *name;
  SCA_IInputDevice::SCA_EnumInputs code;
};
#define KXI(n) {#n, SCA_IInputDevice::n}
static const KxInputName kx_inputs[] = {
    KXI(RETKEY), KXI(SPACEKEY), KXI(PADASTERKEY), KXI(COMMAKEY), KXI(MINUSKEY), KXI(PERIODKEY),
    KXI(ZEROKEY), KXI(ONEKEY), KXI(TWOKEY), KXI(THREEKEY), KXI(FOURKEY), KXI(FIVEKEY),
    KXI(SIXKEY), KXI(SEVENKEY), KXI(EIGHTKEY), KXI(NINEKEY), KXI(AKEY), KXI(BKEY), KXI(CKEY),
    KXI(DKEY), KXI(EKEY), KXI(FKEY), KXI(GKEY), {"HKEY", SCA_IInputDevice::HKEY_}, KXI(IKEY),
    KXI(JKEY), KXI(KKEY), KXI(LKEY), KXI(MKEY), KXI(NKEY), KXI(OKEY), KXI(PKEY), KXI(QKEY),
    KXI(RKEY), KXI(SKEY), KXI(TKEY), KXI(UKEY), KXI(VKEY), KXI(WKEY), KXI(XKEY), KXI(YKEY),
    KXI(ZKEY), KXI(CAPSLOCKKEY), KXI(LEFTCTRLKEY), KXI(LEFTALTKEY), KXI(RIGHTALTKEY),
    KXI(RIGHTCTRLKEY), KXI(RIGHTSHIFTKEY), KXI(LEFTSHIFTKEY), KXI(ESCKEY), KXI(TABKEY),
    KXI(LINEFEEDKEY), KXI(BACKSPACEKEY), KXI(DELKEY), KXI(SEMICOLONKEY), KXI(QUOTEKEY),
    KXI(ACCENTGRAVEKEY), KXI(SLASHKEY), KXI(BACKSLASHKEY), KXI(EQUALKEY), KXI(LEFTBRACKETKEY),
    KXI(RIGHTBRACKETKEY), KXI(LEFTARROWKEY), KXI(DOWNARROWKEY), KXI(RIGHTARROWKEY),
    KXI(UPARROWKEY), KXI(PAD0), KXI(PAD1), KXI(PAD2), KXI(PAD3), KXI(PAD4), KXI(PAD5), KXI(PAD6),
    KXI(PAD7), KXI(PAD8), KXI(PAD9), KXI(PADPERIOD), KXI(PADSLASHKEY), KXI(PADMINUS),
    KXI(PADENTER), KXI(PADPLUSKEY), KXI(F1KEY), KXI(F2KEY), KXI(F3KEY), KXI(F4KEY), KXI(F5KEY),
    KXI(F6KEY), KXI(F7KEY), KXI(F8KEY), KXI(F9KEY), KXI(F10KEY), KXI(F11KEY), KXI(F12KEY),
    KXI(PAUSEKEY), KXI(INSERTKEY), KXI(HOMEKEY), KXI(PAGEUPKEY), KXI(PAGEDOWNKEY), KXI(ENDKEY),
    KXI(LEFTMOUSE), KXI(MIDDLEMOUSE), KXI(RIGHTMOUSE), KXI(BUTTON4MOUSE), KXI(BUTTON5MOUSE),
    KXI(WHEELUPMOUSE), KXI(WHEELDOWNMOUSE),
};
#undef KXI

extern "C" bool ujs_kx_input(const char *name, int32_t *status)
{
  KX_KetsjiEngine *engine = KX_GetActiveEngine();
  if (engine == nullptr || kx_scene() == nullptr) {
    set_err("no game running");
    return false;
  }
  for (const KxInputName &k : kx_inputs) {
    if (strcmp(k.name, name) != 0) continue;
    SCA_InputEvent &ev = engine->GetInputDevice()->GetInput(k.code);
    int32_t s = 0;
    if (ev.Find(SCA_InputEvent::ACTIVE) || ev.Find(SCA_InputEvent::JUSTACTIVATED)) s |= 1;
    if (ev.Find(SCA_InputEvent::JUSTACTIVATED)) s |= 2;
    if (ev.Find(SCA_InputEvent::JUSTRELEASED)) s |= 4;
    *status = s;
    return true;
  }
  set_err("unknown input: %s", name);
  return false;
}

static RAS_ICanvas *kx_canvas()
{
  KX_KetsjiEngine *engine = KX_GetActiveEngine();
  RAS_ICanvas *canvas = (engine && kx_scene()) ? engine->GetCanvas() : nullptr;
  if (canvas == nullptr) set_err("no game running");
  return canvas;
}

extern "C" bool ujs_kx_mouse(double *xy)
{
  RAS_ICanvas *canvas = kx_canvas();
  if (canvas == nullptr) return false;
  SCA_IInputDevice *dev = KX_GetActiveEngine()->GetInputDevice();
  const SCA_InputEvent &x = dev->GetInput(SCA_IInputDevice::MOUSEX);
  const SCA_InputEvent &y = dev->GetInput(SCA_IInputDevice::MOUSEY);
  xy[0] = x.m_values.empty() ? 0.5 : canvas->GetMouseNormalizedX(x.m_values.back());
  xy[1] = y.m_values.empty() ? 0.5 : canvas->GetMouseNormalizedY(y.m_values.back());
  return true;
}

extern "C" bool ujs_kx_set_mouse(double x, double y)
{
  RAS_ICanvas *canvas = kx_canvas();
  if (canvas == nullptr) return false;
  canvas->SetMousePosition((int)(x * canvas->GetWidth()), (int)(y * canvas->GetHeight()));
  return true;
}

extern "C" bool ujs_kx_show_mouse(bool visible)
{
  RAS_ICanvas *canvas = kx_canvas();
  if (canvas == nullptr) return false;
  canvas->SetMouseState(visible ? RAS_ICanvas::MOUSE_NORMAL : RAS_ICanvas::MOUSE_INVISIBLE);
  return true;
}

extern "C" bool ujs_kx_screenshot(const char *path)
{
  RAS_ICanvas *canvas = kx_canvas();
  if (canvas == nullptr) return false;
  canvas->MakeScreenShot(path);
  return true;
}

extern "C" bool ujs_kx_frame_info(double *out3)
{
  KX_KetsjiEngine *engine = KX_GetActiveEngine();
  if (engine == nullptr || kx_scene() == nullptr) {
    set_err("no game running");
    return false;
  }
  out3[0] = engine->GetAverageFrameRate();
  out3[1] = engine->GetClockTime();
  out3[2] = engine->GetFrameTime();
  return true;
}

extern "C" bool ujs_kx_canvas_size(int32_t *w, int32_t *h)
{
  RAS_ICanvas *canvas = kx_canvas();
  if (canvas == nullptr) return false;
  *w = canvas->GetWidth();
  *h = canvas->GetHeight();
  return true;
}

/* ---- media and overlays (round 4) --------------------------------------------- */

extern "C" bool ujs_kx_set_text(ujs_pointer obj, const char *text)
{
  KX_GameObject *o = kx_obj(obj);
  if (o == nullptr) return false;
  KX_FontObject *font = dynamic_cast<KX_FontObject *>(o);
  if (font == nullptr) {
    set_err("%s is not a text object", o->GetName().c_str());
    return false;
  }
  font->SetText(text ? text : "");
  return true;
}

static RAS_2DFilterManager *kx_filters()
{
  KX_Scene *scene = kx_scene();
  if (scene == nullptr) {
    set_err("no game running");
    return nullptr;
  }
  return scene->Get2DFilterManager();
}

extern "C" bool ujs_kx_add_filter(int32_t index, int32_t type, const char *glsl)
{
  RAS_2DFilterManager *fm = kx_filters();
  if (fm == nullptr) return false;
  if (fm->GetFilterPass(index)) {
    set_err("a filter already uses pass %d", index);
    return false;
  }
  if (type < RAS_2DFilterManager::FILTER_BLUR || type > RAS_2DFilterManager::FILTER_CUSTOMFILTER) {
    set_err("invalid filter type %d", type);
    return false;
  }
  RAS_2DFilterData data;
  data.filterPassIndex = index;
  data.filterMode = type;
  data.shaderText = glsl ? std::string(glsl) : std::string();
  RAS_2DFilter *f = fm->AddFilter(data);
  if (f == nullptr || !f->Ok()) {
    fm->RemoveFilterPass(index);
    set_err("filter %d failed to compile (GLSL error, see the console)", index);
    return false;
  }
  return true;
}

extern "C" bool ujs_kx_remove_filter(int32_t index)
{
  RAS_2DFilterManager *fm = kx_filters();
  if (fm == nullptr) return false;
  fm->RemoveFilterPass(index);
  return true;
}

extern "C" bool ujs_kx_filter_uniform(int32_t index, const char *name, const double *v, int32_t n)
{
  RAS_2DFilterManager *fm = kx_filters();
  if (fm == nullptr) return false;
  RAS_2DFilter *f = fm->GetFilterPass(index);
  if (f == nullptr || !f->Ok()) {
    set_err("no working filter in pass %d", index);
    return false;
  }
  if (n < 1 || n > 4) {
    set_err("uniform %s: 1-4 floats, got %d", name, n);
    return false;
  }
  int loc = f->GetUniformLocation(name, false);
  if (loc == -1) {
    set_err("filter %d has no uniform %s (unused uniforms are optimized out)", index, name);
    return false;
  }
  float fv[4];
  for (int i = 0; i < n; i++) fv[i] = (float)v[i];
  static const int types[] = {RAS_Shader::RAS_Uniform::UNI_FLOAT, RAS_Shader::RAS_Uniform::UNI_FLOAT2,
                              RAS_Shader::RAS_Uniform::UNI_FLOAT3, RAS_Shader::RAS_Uniform::UNI_FLOAT4};
  f->SetUniformfv(loc, types[n - 1], fv, sizeof(float) * n, 1);
  return true;
}

/* Audio over audaspace's C++ API (Blender builds it without the C
 * bindings). Sounds and handles cross as heap shared_ptrs, freed by
 * ujs_snd_free / ujs_snd_handle_free. Playback goes to Blender's device. */

using UjsSound = std::shared_ptr<aud::ISound>;
using UjsHandle = std::shared_ptr<aud::IHandle>;

static void *snd_new(aud::ISound *raw)
{
  return new UjsSound(raw);
}

static UjsSound *snd(void *p)
{
  if (p == nullptr) set_err("null sound");
  return static_cast<UjsSound *>(p);
}

extern "C" void *ujs_snd_tone(const char *wave, double frequency, double rate)
{
  try {
    if (strcmp(wave, "sine") == 0) return snd_new(new aud::Sine((float)frequency, rate));
    if (strcmp(wave, "sawtooth") == 0) return snd_new(new aud::Sawtooth((float)frequency, rate));
    if (strcmp(wave, "square") == 0) return snd_new(new aud::Square((float)frequency, rate));
    if (strcmp(wave, "triangle") == 0) return snd_new(new aud::Triangle((float)frequency, rate));
  }
  catch (aud::Exception &e) {
    set_err("tone: %s", e.getMessage().c_str());
    return nullptr;
  }
  set_err("unknown wave %s (sine, sawtooth, square, triangle)", wave);
  return nullptr;
}

extern "C" void *ujs_snd_noise(double seconds, double rate)
{
  int n = (int)(seconds * rate);
  if (n <= 0) {
    set_err("noise length must be positive");
    return nullptr;
  }
  auto buffer = std::make_shared<aud::Buffer>(n * (int)sizeof(aud::sample_t));
  aud::sample_t *data = buffer->getBuffer();
  uint32_t x = 2463534242u; /* xorshift: fast, repeatable */
  for (int i = 0; i < n; i++) {
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    data[i] = (aud::sample_t)((x / 4294967295.0) * 2.0 - 1.0);
  }
  aud::Specs specs;
  specs.rate = rate;
  specs.channels = aud::CHANNELS_MONO;
  try {
    return snd_new(new aud::StreamBuffer(buffer, specs));
  }
  catch (aud::Exception &e) {
    set_err("noise: %s", e.getMessage().c_str());
    return nullptr;
  }
}

extern "C" void *ujs_snd_file(const char *path)
{
  try {
    return snd_new(new aud::File(path));
  }
  catch (aud::Exception &e) {
    set_err("file %s: %s", path, e.getMessage().c_str());
    return nullptr;
  }
}

/* kind: "lowpass" "highpass" (a = frequency, b = Q), "volume" (a) */
extern "C" void *ujs_snd_filter(void *sound, const char *kind, double a, double b)
{
  UjsSound *s = snd(sound);
  if (s == nullptr) return nullptr;
  try {
    if (strcmp(kind, "lowpass") == 0) return snd_new(new aud::Lowpass(*s, (float)a, (float)b));
    if (strcmp(kind, "highpass") == 0) return snd_new(new aud::Highpass(*s, (float)a, (float)b));
    if (strcmp(kind, "volume") == 0) return snd_new(new aud::Volume(*s, (float)a));
  }
  catch (aud::Exception &e) {
    set_err("%s: %s", kind, e.getMessage().c_str());
    return nullptr;
  }
  set_err("unknown filter %s (lowpass, highpass, volume)", kind);
  return nullptr;
}

extern "C" void *ujs_snd_mix(void *a, void *b)
{
  UjsSound *x = snd(a), *y = snd(b);
  if (x == nullptr || y == nullptr) return nullptr;
  try {
    return snd_new(new aud::Superpose(*x, *y));
  }
  catch (aud::Exception &e) {
    set_err("mix: %s", e.getMessage().c_str());
    return nullptr;
  }
}

extern "C" void ujs_snd_free(void *sound)
{
  delete static_cast<UjsSound *>(sound);
}

/* play on Blender's device; loop = -1 loops forever */
extern "C" void *ujs_snd_play(void *sound, int32_t loop)
{
  UjsSound *s = snd(sound);
  if (s == nullptr) return nullptr;
  /* Blender opens its device on demand; hold one use for the session */
  static bool in_use = false;
  if (!in_use) {
    BKE_sound_use_begin();
    in_use = true;
  }
  AUD_Device device = BKE_sound_get_device();
  if (!device) {
    set_err("no audio device (sound disabled in preferences?)");
    return nullptr;
  }
  try {
    UjsHandle h = device->play(*s, true);
    if (!h) {
      set_err("device refused to play");
      return nullptr;
    }
    h->setLoopCount(loop);
    return new UjsHandle(h);
  }
  catch (aud::Exception &e) {
    set_err("play: %s", e.getMessage().c_str());
    return nullptr;
  }
}

/* what: "pitch" "volume"; or "stop" "pause" "resume" (value ignored) */
extern "C" bool ujs_snd_control(void *handle, const char *what, double value)
{
  if (handle == nullptr) {
    set_err("null handle");
    return false;
  }
  UjsHandle &h = *static_cast<UjsHandle *>(handle);
  bool ok;
  if (strcmp(what, "pitch") == 0) ok = h->setPitch((float)value);
  else if (strcmp(what, "volume") == 0) ok = h->setVolume((float)value);
  else if (strcmp(what, "stop") == 0) ok = h->stop();
  else if (strcmp(what, "pause") == 0) ok = h->pause();
  else if (strcmp(what, "resume") == 0) ok = h->resume();
  else {
    set_err("unknown control %s", what);
    return false;
  }
  if (!ok) set_err("%s failed (handle stopped?)", what);
  return ok;
}

extern "C" void ujs_snd_handle_free(void *handle)
{
  delete static_cast<UjsHandle *>(handle);
}

/* ---- roots ------------------------------------------------------------------------- */

/* Roots resolve on every call, never cached: loading a file frees the old
 * Main and its scenes, and a cached pointer would dangle. The scene is the
 * context's (falling back to the first scene with no window context);
 * uj_set_root is kept for ABI compatibility and is now a no-op. */

extern "C" void uj_set_root(ujs_pointer /*root*/) {}

static bContext *ctx()
{
  return BPY_context_get();
}

extern "C" bool ujs_root(ujs_pointer *out)
{
  bContext *C = ctx();
  Scene *scene = C ? CTX_data_scene(C) : nullptr;
  if (scene == nullptr && G_MAIN != nullptr) {
    scene = static_cast<Scene *>(G_MAIN->scenes.first());
  }
  if (scene == nullptr) {
    set_err("no scene");
    return false;
  }
  *out = as_ujs(RNA_id_pointer_create(&scene->id));
  return true;
}

extern "C" bool ujs_data_root(ujs_pointer *out)
{
  if (G_MAIN == nullptr) {
    set_err("no main database");
    return false;
  }
  *out = as_ujs(RNA_main_pointer_create(G_MAIN));
  return true;
}

extern "C" bool ujs_context_root(ujs_pointer *out)
{
  bContext *C = ctx();
  if (C == nullptr) {
    set_err("no context");
    return false;
  }
  *out = as_ujs(RNA_pointer_create_discrete(nullptr, RNA_Context, C));
  return true;
}

/* ---- reports ------------------------------------------------------------------------ */

/* Move error reports into the last-error buffer; true when there were any. */
static bool take_reports(ReportList *reports, const char *what)
{
  char *msg = BKE_reports_string(reports, RPT_ERROR);
  bool had = msg != nullptr && msg[0] != '\0';
  if (had) {
    set_err("%s: %s", what, msg);
  }
  if (msg) MEM_delete(msg);
  BKE_reports_free(reports);
  return had;
}

/* ---- context override --------------------------------------------------------------- */

/* Point the context at the first area of `area_type` (an rna space-type
 * identifier like "VIEW_3D") and its main region, saving the old values. */
struct CtxOverride {
  bContext *C = nullptr;
  wmWindow *win = nullptr;
  bScreen *screen = nullptr;
  ScrArea *area = nullptr;
  ARegion *region = nullptr;
  bool active = false;
};

static bool ctx_override_begin(bContext *C, const char *area_type, CtxOverride &o)
{
  o.C = C;
  if (area_type == nullptr || area_type[0] == '\0') return true;
  int spacetype;
  if (!RNA_enum_value_from_identifier(rna_enum_space_type_items, area_type, &spacetype)) {
    set_err("unknown area type: %s", area_type);
    return false;
  }
  wmWindowManager *wm = CTX_wm_manager(C);
  if (wm == nullptr) {
    set_err("no window manager (running headless?)");
    return false;
  }
  for (wmWindow *win = static_cast<wmWindow *>(wm->windows.first()); win; win = win->next) {
    bScreen *screen = WM_window_get_active_screen(win);
    if (screen == nullptr) continue;
    for (ScrArea *area = static_cast<ScrArea *>(screen->areabase.first()); area; area = area->next) {
      if (area->spacetype != spacetype) continue;
      o.win = CTX_wm_window(C);
      o.screen = CTX_wm_screen(C);
      o.area = CTX_wm_area(C);
      o.region = CTX_wm_region(C);
      o.active = true;
      CTX_wm_window_set(C, win);
      CTX_wm_screen_set(C, screen);
      CTX_wm_area_set(C, area);
      CTX_wm_region_set(C, BKE_area_find_region_type(area, RGN_TYPE_WINDOW));
      return true;
    }
  }
  set_err("no %s area open", area_type);
  return false;
}

static void ctx_override_end(CtxOverride &o)
{
  if (!o.active) return;
  CTX_wm_window_set(o.C, o.win);
  CTX_wm_screen_set(o.C, o.screen);
  CTX_wm_area_set(o.C, o.area);
  CTX_wm_region_set(o.C, o.region);
}

/* ---- operators with arguments -------------------------------------------------------- */

/* Parse `val` into operator property `key` by its RNA type. */
static bool set_prop_from_string(bContext *C, PointerRNA *ptr, const char *key, const char *val)
{
  PropertyRNA *prop = RNA_struct_find_property(ptr, key);
  if (prop == nullptr) {
    set_err("no such property: %s", key);
    return false;
  }
  const bool is_array = RNA_property_array_check(prop);
  switch (RNA_property_type(prop)) {
    case PROP_BOOLEAN:
      if (is_array) {
        int n = RNA_property_array_length(ptr, prop);
        bool *b = (bool *)calloc(n, sizeof(bool));
        const char *c = val;
        for (int i = 0; i < n && c && *c; i++) {
          b[i] = strncmp(c, "true", 4) == 0 || *c == '1';
          c = strchr(c, ',');
          if (c) c++;
        }
        RNA_property_boolean_set_array(ptr, prop, b);
        free(b);
      }
      else {
        RNA_property_boolean_set(ptr, prop, strcmp(val, "true") == 0 || strcmp(val, "1") == 0);
      }
      return true;
    case PROP_INT:
    case PROP_FLOAT: {
      const bool is_int = RNA_property_type(prop) == PROP_INT;
      if (is_array) {
        int n = RNA_property_array_length(ptr, prop);
        double *d = (double *)calloc(n, sizeof(double));
        const char *c = val;
        int got = 0;
        for (; got < n && c && *c; got++) {
          d[got] = strtod(c, nullptr);
          c = strchr(c, ',');
          if (c) c++;
        }
        if (got != n) {
          free(d);
          set_err("%s wants %d values, got %d", key, n, got);
          return false;
        }
        if (is_int) {
          int *iv = (int *)malloc(n * sizeof(int));
          for (int i = 0; i < n; i++) iv[i] = (int)d[i];
          RNA_property_int_set_array(ptr, prop, iv);
          free(iv);
        }
        else {
          float *fv = (float *)malloc(n * sizeof(float));
          for (int i = 0; i < n; i++) fv[i] = (float)d[i];
          RNA_property_float_set_array(ptr, prop, fv);
          free(fv);
        }
        free(d);
      }
      else if (is_int) {
        RNA_property_int_set(ptr, prop, (int)strtoll(val, nullptr, 10));
      }
      else {
        RNA_property_float_set(ptr, prop, (float)strtod(val, nullptr));
      }
      return true;
    }
    case PROP_STRING:
      RNA_property_string_set(ptr, prop, val);
      return true;
    case PROP_ENUM: {
      /* flag enums take comma-separated identifiers */
      int acc = 0;
      std::string all(val);
      size_t start = 0;
      while (start <= all.size()) {
        size_t end = all.find(',', start);
        std::string id = all.substr(start, end == std::string::npos ? std::string::npos : end - start);
        if (!id.empty()) {
          int v;
          if (!RNA_property_enum_value(C, ptr, prop, id.c_str(), &v)) {
            set_err("%s: no enum item %s", key, id.c_str());
            return false;
          }
          acc |= v;
        }
        if (end == std::string::npos) break;
        start = end + 1;
      }
      RNA_property_enum_set(ptr, prop, acc);
      return true;
    }
    default:
      set_err("%s: pointer/collection operator properties are not supported", key);
      return false;
  }
}

extern "C" bool ujs_operator_call_ex(const char *opname, int32_t n, const char **keys,
                                     const char **vals, const char *area)
{
  bContext *C = ctx();
  if (C == nullptr) {
    set_err("no context");
    return false;
  }
  wmOperatorType *ot = WM_operatortype_find(opname, true);
  if (ot == nullptr) {
    set_err("unknown operator: %s", opname);
    return false;
  }
  PointerRNA props = WM_operator_properties_create_ptr(ot);
  for (int i = 0; i < n; i++) {
    if (!set_prop_from_string(C, &props, keys[i], vals[i])) {
      WM_operator_properties_free(&props);
      return false;
    }
  }
  CtxOverride o;
  if (!ctx_override_begin(C, area, o)) {
    WM_operator_properties_free(&props);
    return false;
  }
  bool ok = false;
  if (!WM_operator_poll(C, ot)) {
    const char *msg = CTX_wm_operator_poll_msg_get(C, nullptr);
    set_err("%s: poll failed%s%s", opname, msg ? ": " : " (wrong context?)", msg ? msg : "");
  }
  else {
    ReportList reports;
    BKE_reports_init(&reports, RPT_STORE);
    wmOperatorStatus status = WM_operator_call_py(
        C, ot, wm::OpCallContext::ExecDefault, &props, &reports, false);
    bool had_errors = take_reports(&reports, opname);
    ok = (status & OPERATOR_FINISHED) && !had_errors;
    if (!ok && !had_errors) {
      set_err("operator %s did not finish (%s)", opname,
              (status & OPERATOR_CANCELLED) ? "cancelled" : "running modal or passed through");
    }
  }
  ctx_override_end(o);
  WM_operator_properties_free(&props);
  return ok;
}

/* ---- RNA function calls -------------------------------------------------------------- */

static double *g_ret_floats = nullptr;

/* The raw slot of `parm` in `list` (for PROP_DYNAMIC params, the
 * ParameterDynAlloc itself, unlike RNA_parameter_get). */
static void *param_slot(ParameterList *list, PropertyRNA *parm)
{
  ParameterIterator iter;
  void *slot = nullptr;
  RNA_parameter_list_begin(list, &iter);
  for (; iter.valid; RNA_parameter_list_next(&iter)) {
    if (iter.parm == parm) {
      slot = iter.data;
      break;
    }
  }
  RNA_parameter_list_end(&iter);
  return slot;
}

/* Fill a dynamic (PROP_DYNAMIC) input: strings and int/bool/float arrays. */
static bool write_dynamic_param(PropertyRNA *parm, void *slot, const ujs_value &v)
{
  ParameterDynAlloc *alloc = static_cast<ParameterDynAlloc *>(slot);
  if (alloc->array) {
    MEM_delete_void(alloc->array);
    alloc->array = nullptr;
  }
  switch (RNA_property_type(parm)) {
    case PROP_STRING: {
      const char *str = v.s ? v.s : "";
      size_t n = strlen(str) + 1;
      char *buf = static_cast<char *>(MEM_new_uninitialized(n, __func__));
      memcpy(buf, str, n);
      alloc->array = buf;
      alloc->array_tot = (int)n - 1;
      return true;
    }
    case PROP_INT:
    case PROP_BOOLEAN: {
      int *buf = static_cast<int *>(MEM_new_uninitialized(sizeof(int) * (v.len > 0 ? v.len : 1), __func__));
      for (int i = 0; i < v.len; i++) buf[i] = (int)v.fa[i];
      alloc->array = buf;
      alloc->array_tot = v.len;
      return true;
    }
    case PROP_FLOAT: {
      float *buf = static_cast<float *>(MEM_new_uninitialized(sizeof(float) * (v.len > 0 ? v.len : 1), __func__));
      for (int i = 0; i < v.len; i++) buf[i] = (float)v.fa[i];
      alloc->array = buf;
      alloc->array_tot = v.len;
      return true;
    }
    default:
      set_err("%s: unsupported dynamic parameter", RNA_property_identifier(parm));
      return false;
  }
}

/* Write `v` into parameter slot `data` for parameter `parm`, as bpy does. */
static bool write_param(bContext *C, PointerRNA *self, PropertyRNA *parm, void *data, const ujs_value &v)
{
  const int flag = RNA_property_flag(parm);
  const int flag_parameter = RNA_parameter_flag(parm);
  const int len = RNA_property_array_check(parm) ? RNA_property_array_length(self, parm) : 0;
  const char *pname = RNA_property_identifier(parm);
  switch (RNA_property_type(parm)) {
    case PROP_BOOLEAN:
      if (len > 0) {
        for (int i = 0; i < len && i < v.len; i++) static_cast<bool *>(data)[i] = v.fa[i] != 0.0;
      }
      else {
        *static_cast<bool *>(data) = v.i != 0;
      }
      return true;
    case PROP_INT:
      if (len > 0) {
        if (v.len != len) { set_err("%s wants %d values, got %d", pname, len, v.len); return false; }
        for (int i = 0; i < len; i++) static_cast<int *>(data)[i] = (int)v.fa[i];
      }
      else {
        *static_cast<int *>(data) = v.type == UJS_PROP_FLOAT ? (int)v.f : (int)v.i;
      }
      return true;
    case PROP_FLOAT:
      if (len > 0) {
        if (v.len != len) { set_err("%s wants %d values, got %d", pname, len, v.len); return false; }
        for (int i = 0; i < len; i++) static_cast<float *>(data)[i] = (float)v.fa[i];
      }
      else {
        *static_cast<float *>(data) = v.type == UJS_PROP_INT ? (float)v.i : (float)v.f;
      }
      return true;
    case PROP_ENUM: {
      int value = (int)v.i;
      if (v.s != nullptr && !RNA_property_enum_value(C, self, parm, v.s, &value)) {
        set_err("%s: no enum item %s", pname, v.s);
        return false;
      }
      *static_cast<int *>(data) = value;
      return true;
    }
    case PROP_STRING: {
      const char *str = v.s ? v.s : "";
      if (flag & PROP_THICK_WRAP) {
        int maxlen = RNA_property_string_maxlength(parm);
        snprintf(static_cast<char *>(data), maxlen > 0 ? maxlen : 1024, "%s", str);
      }
      else {
        *static_cast<const char **>(data) = str;
      }
      return true;
    }
    case PROP_POINTER: {
      PointerRNA arg = as_rna(v.p);
      StructRNA *want = RNA_property_pointer_type(self, parm);
      if (v.p.data != nullptr && arg.type != nullptr && !RNA_struct_is_a(arg.type, want)) {
        set_err("%s: expected %s, got %s", pname, RNA_struct_identifier(want),
                RNA_struct_identifier(arg.type));
        return false;
      }
      if (flag_parameter & PARM_RNAPTR) {
        if (flag & PROP_THICK_WRAP) {
          *static_cast<PointerRNA *>(data) = v.p.data ? arg : PointerRNA{};
        }
        else {
          /* the callee reads through this pointer during the call only */
          static PointerRNA held[16];
          static int held_i = 0;
          PointerRNA *slot = &held[held_i++ % 16];
          *slot = arg;
          *static_cast<PointerRNA **>(data) = v.p.data ? slot : nullptr;
        }
      }
      else {
        *static_cast<void **>(data) = v.p.data;
      }
      return true;
    }
    default:
      set_err("%s: collection arguments are not supported", pname);
      return false;
  }
}

/* Read output parameter `parm` from slot `data` into *out. */
static void read_param(PointerRNA *self, PropertyRNA *parm, ParameterList *list, void *data, ujs_value *out)
{
  const int flag = RNA_property_flag(parm);
  const int flag_parameter = RNA_parameter_flag(parm);
  int len = 0;
  if (flag & PROP_DYNAMIC) {
    /* `data` is the ParameterDynAlloc: unwrap to the array it holds */
    ParameterDynAlloc *alloc = static_cast<ParameterDynAlloc *>(data);
    data = alloc->array;
    len = RNA_property_type(parm) == PROP_STRING ? 0 : alloc->array_tot;
    if (data == nullptr) {
      memset(out, 0, sizeof *out);
      out->type = UJS_VAL_NONE;
      return;
    }
  }
  else if (RNA_property_array_check(parm)) {
    len = RNA_property_array_length(self, parm);
  }
  memset(out, 0, sizeof *out);
  out->type = UJS_VAL_NONE;
  switch (RNA_property_type(parm)) {
    case PROP_BOOLEAN:
    case PROP_INT:
    case PROP_FLOAT:
      if (len > 0) {
        free(g_ret_floats);
        g_ret_floats = (double *)malloc(len * sizeof(double));
        for (int i = 0; i < len; i++) {
          switch (RNA_property_type(parm)) {
            case PROP_BOOLEAN: g_ret_floats[i] = static_cast<bool *>(data)[i]; break;
            case PROP_INT: g_ret_floats[i] = static_cast<int *>(data)[i]; break;
            default: g_ret_floats[i] = static_cast<float *>(data)[i]; break;
          }
        }
        out->type = UJS_PROP_FLOAT;
        out->fa = g_ret_floats;
        out->len = len;
      }
      else if (RNA_property_type(parm) == PROP_BOOLEAN) {
        out->type = UJS_PROP_BOOL;
        out->i = *static_cast<bool *>(data);
      }
      else if (RNA_property_type(parm) == PROP_INT) {
        out->type = UJS_PROP_INT;
        out->i = *static_cast<int *>(data);
      }
      else {
        out->type = UJS_PROP_FLOAT;
        out->f = *static_cast<float *>(data);
      }
      return;
    case PROP_ENUM: {
      out->type = UJS_PROP_ENUM;
      out->i = *static_cast<int *>(data);
      const char *id = nullptr;
      if (RNA_property_enum_identifier(nullptr, self, parm, (int)out->i, &id) && id) {
        snprintf(g_str_buf, sizeof g_str_buf, "%s", id);
        out->s = g_str_buf;
      }
      return;
    }
    case PROP_STRING: {
      const char *str = (flag & (PROP_THICK_WRAP | PROP_DYNAMIC)) ? static_cast<const char *>(data) :
                                                                    *static_cast<const char **>(data);
      g_long_str = str ? str : "";
      out->type = UJS_PROP_STRING;
      out->s = g_long_str.c_str();
      return;
    }
    case PROP_POINTER: {
      StructRNA *ptype = RNA_property_pointer_type(self, parm);
      PointerRNA res;
      if (flag_parameter & PARM_RNAPTR) {
        res = (flag & PROP_THICK_WRAP) ? *static_cast<PointerRNA *>(data) :
                                         **static_cast<PointerRNA **>(data);
      }
      else {
        void *p = *static_cast<void **>(data);
        if (p == nullptr) {
          res = PointerRNA{};
        }
        else if (RNA_struct_is_ID(ptype)) {
          res = RNA_id_pointer_create(static_cast<ID *>(p));
        }
        else {
          res = RNA_pointer_create_discrete(self->owner_id, ptype, p);
        }
      }
      out->type = UJS_PROP_POINTER;
      out->p = as_ujs(res);
      return;
    }
    default:
      return;
  }
}

extern "C" bool ujs_func_call(ujs_pointer self_p, const char *fname, int32_t n,
                              const char **names, const ujs_value *args, ujs_value *ret)
{
  bContext *C = ctx();
  PointerRNA self = as_rna(self_p);
  if (self.type == nullptr) {
    set_err("invalid pointer");
    return false;
  }
  FunctionRNA *func = RNA_struct_find_function(self.type, fname);
  if (func == nullptr) {
    set_err("%s has no function %s", RNA_struct_identifier(self.type), fname);
    return false;
  }
  ParameterList list;
  RNA_parameter_list_create(&list, &self, func);

  /* named args */
  for (int i = 0; i < n; i++) {
    PropertyRNA *parm = RNA_function_find_parameter(&self, func, names[i]);
    if (parm == nullptr || (RNA_parameter_flag(parm) & PARM_OUTPUT)) {
      set_err("%s.%s has no argument %s", RNA_struct_identifier(self.type), fname, names[i]);
      RNA_parameter_list_free(&list);
      return false;
    }
    void *slot = param_slot(&list, parm);
    bool wrote = (RNA_property_flag(parm) & PROP_DYNAMIC) ? write_dynamic_param(parm, slot, args[i]) :
                                                           write_param(C, &self, parm, slot, args[i]);
    if (!wrote) {
      RNA_parameter_list_free(&list);
      return false;
    }
  }
  /* required args present? */
  ParameterIterator iter;
  RNA_parameter_list_begin(&list, &iter);
  for (; iter.valid; RNA_parameter_list_next(&iter)) {
    PropertyRNA *parm = iter.parm;
    if (!(RNA_parameter_flag(parm) & PARM_REQUIRED) || (RNA_parameter_flag(parm) & PARM_OUTPUT)) {
      continue;
    }
    bool given = false;
    for (int i = 0; i < n; i++) {
      if (strcmp(names[i], RNA_property_identifier(parm)) == 0) given = true;
    }
    if (!given) {
      set_err("%s.%s: missing required argument %s", RNA_struct_identifier(self.type), fname,
              RNA_property_identifier(parm));
      RNA_parameter_list_end(&iter);
      RNA_parameter_list_free(&list);
      return false;
    }
  }
  RNA_parameter_list_end(&iter);

  ReportList reports;
  BKE_reports_init(&reports, RPT_STORE);
  RNA_function_call(C, &reports, &self, func, &list);
  if (take_reports(&reports, fname)) {
    RNA_parameter_list_free(&list);
    return false;
  }

  memset(ret, 0, sizeof *ret);
  ret->type = UJS_VAL_NONE;
  RNA_parameter_list_begin(&list, &iter);
  for (; iter.valid; RNA_parameter_list_next(&iter)) {
    if (RNA_parameter_flag(iter.parm) & PARM_OUTPUT) {
      read_param(&self, iter.parm, &list, iter.data, ret);
      break;
    }
  }
  RNA_parameter_list_end(&iter);
  RNA_parameter_list_free(&list);

  /* creation/removal through functions changes the scene graph */
  if (self.owner_id) DEG_id_tag_update(self.owner_id, ID_RECALC_SYNC_TO_EVAL);
  DEG_relations_tag_update(G_MAIN);
  WM_main_add_notifier(NC_WINDOW, nullptr);
  return true;
}

/* ---- collection lookup and bulk access ---------------------------------------------- */

extern "C" bool ujs_coll_ptr(ujs_pointer owner, const char *name, ujs_pointer *out)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_COLLECTION);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  std::optional<PointerRNA> c_ptr = RNA_property_collection_type_get(&ptr, prop);
  if (!c_ptr.has_value() || c_ptr->type == nullptr) {
    set_err("collection %s has no functions", name);
    return false;
  }
  *out = as_ujs(*c_ptr);
  return true;
}

extern "C" bool ujs_set_enum_id(ujs_pointer owner, const char *name, const char *ids)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_ENUM);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  int acc = 0;
  std::string all(ids);
  size_t start = 0;
  while (true) {
    size_t end = all.find(',', start);
    std::string id = all.substr(start, end == std::string::npos ? std::string::npos : end - start);
    if (!id.empty()) {
      int v;
      if (!RNA_property_enum_value(ctx(), &ptr, prop, id.c_str(), &v)) {
        set_err("%s: no enum item %s", name, id.c_str());
        return false;
      }
      acc |= v;
    }
    if (end == std::string::npos) break;
    start = end + 1;
  }
  RNA_property_enum_set(&ptr, prop, acc);
  prop_updated(ptr, prop);
  return true;
}

extern "C" bool ujs_coll_lookup(ujs_pointer owner, const char *name, const char *key,
                                int64_t index, ujs_pointer *out)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_COLLECTION);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  PointerRNA item;
  bool found = key ? RNA_property_collection_lookup_string(&ptr, prop, key, &item) :
                     RNA_property_collection_lookup_int(&ptr, prop, (int)index, &item);
  if (!found) {
    if (key) set_err("%s has no item %s", name, key);
    else set_err("%s index %lld out of range", name, (long long)index);
    return false;
  }
  *out = as_ujs(item);
  return true;
}

static bool foreach_raw(ujs_pointer owner, const char *name, const char *attr, double *buf,
                        int64_t len, bool set)
{
  PropertyRNA *prop = find_prop_typed(owner, name, PROP_COLLECTION);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  ReportList reports;
  BKE_reports_init(&reports, RPT_STORE);
  int ok = set ? RNA_property_collection_raw_set(&reports, &ptr, prop, attr, buf, PROP_RAW_DOUBLE, (int)len) :
                 RNA_property_collection_raw_get(&reports, &ptr, prop, attr, buf, PROP_RAW_DOUBLE, (int)len);
  if (take_reports(&reports, set ? "foreach_set" : "foreach_get")) return false;
  if (!ok) {
    set_err("foreach %s.%s failed (length %lld mismatch?)", name, attr, (long long)len);
    return false;
  }
  if (set && ptr.owner_id) {
    DEG_id_tag_update(ptr.owner_id, ID_RECALC_GEOMETRY);
    WM_main_add_notifier(NC_GEOM | ND_DATA, ptr.owner_id);
  }
  return true;
}

extern "C" bool ujs_coll_foreach_get(ujs_pointer owner, const char *name, const char *attr,
                                     double *buf, int64_t len)
{
  return foreach_raw(owner, name, attr, buf, len, false);
}

extern "C" bool ujs_coll_foreach_set(ujs_pointer owner, const char *name, const char *attr,
                                     const double *buf, int64_t len)
{
  return foreach_raw(owner, name, attr, const_cast<double *>(buf), len, true);
}

/* ---- int/bool arrays ---------------------------------------------------------------- */

extern "C" bool ujs_get_int_array(ujs_pointer owner, const char *name, int64_t **out, int32_t *len)
{
  PropertyRNA *prop = find_prop(owner, name);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  PropertyType t = RNA_property_type(prop);
  if ((t != PROP_INT && t != PROP_BOOLEAN) || !RNA_property_array_check(prop)) {
    set_err("not an int/bool array property: %s", name);
    return false;
  }
  int n = RNA_property_array_length(&ptr, prop);
  int64_t *d = (int64_t *)malloc((n > 0 ? n : 1) * sizeof(int64_t));
  if (t == PROP_INT) {
    int *tmp = (int *)malloc((n > 0 ? n : 1) * sizeof(int));
    RNA_property_int_get_array(&ptr, prop, tmp);
    for (int i = 0; i < n; i++) d[i] = tmp[i];
    free(tmp);
  }
  else {
    bool *tmp = (bool *)malloc((n > 0 ? n : 1) * sizeof(bool));
    RNA_property_boolean_get_array(&ptr, prop, tmp);
    for (int i = 0; i < n; i++) d[i] = tmp[i];
    free(tmp);
  }
  *out = d;
  *len = n;
  return true;
}

extern "C" bool ujs_set_int_array(ujs_pointer owner, const char *name, const int64_t *v, int32_t len)
{
  PropertyRNA *prop = find_prop(owner, name);
  if (prop == nullptr) return false;
  PointerRNA ptr = as_rna(owner);
  PropertyType t = RNA_property_type(prop);
  if ((t != PROP_INT && t != PROP_BOOLEAN) || !RNA_property_array_check(prop)) {
    set_err("not an int/bool array property: %s", name);
    return false;
  }
  int n = RNA_property_array_length(&ptr, prop);
  if (n != len) {
    set_err("array length mismatch: property has %d, caller sent %d", n, len);
    return false;
  }
  if (t == PROP_INT) {
    int *tmp = (int *)malloc((n > 0 ? n : 1) * sizeof(int));
    for (int i = 0; i < n; i++) tmp[i] = (int)v[i];
    RNA_property_int_set_array(&ptr, prop, tmp);
    free(tmp);
  }
  else {
    bool *tmp = (bool *)malloc((n > 0 ? n : 1) * sizeof(bool));
    for (int i = 0; i < n; i++) tmp[i] = v[i] != 0;
    RNA_property_boolean_set_array(&ptr, prop, tmp);
    free(tmp);
  }
  prop_updated(ptr, prop);
  return true;
}

extern "C" void ujs_test_reset(void) {}

/* free memory this shim handed out (ujs_get_float_array_n); the caller must
 * release through here, not its own allocator */
extern "C" void ujs_free(void *ptr) { free(ptr); }
