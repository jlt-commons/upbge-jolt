/* Mock host shim: implements native/upbge_shim.h's ujs_* surface over plain
 * C state, so upbge-jolt's runtime logic (property proxy, collections,
 * marshalling) is testable headlessly, without a UPBGE build. Same symbol
 * names as the real shim so the jolt-side defcfn bindings resolve either
 * from this dylib (declared in :jolt/native) or from the host process.
 *
 * State: one "Scene" with a collection of two member "Objects", each with a
 * few typed properties; the Cube holds a nested pointer to a "Mesh" whose
 * float array is dynamic-length. ujs_test_reset restores all state. */

#include "upbge_shim.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define PROP_MAX 16
#define OBJ_MAX 32

typedef struct {
  char name[64];
  int type;
  int array_len;
  char item_type[64];
  union {
    bool b;
    int64_t i;
    double f;
    double fa[16];   /* UJS_PROP_FLOAT, array_len > 0 */
    double *dyn;     /* UJS_PROP_FLOAT, array_len == -1: malloc'd */
    char s[128];
    int64_t e;
    int obj_index; /* UJS_PROP_POINTER: index into objects, -1 = null */
  } v;
  int dyn_len; /* element count of v.dyn */
  char enum_id[64];
} mock_prop;

typedef struct {
  char name[64];
  mock_prop props[PROP_MAX];
  int nprops;
  int coll_members[OBJ_MAX];
  int ncoll;
} mock_obj;

static mock_obj g_objects[OBJ_MAX];
static int g_nobjects = 0;
static char g_last_error[256];
static char g_str_buf[128]; /* returned strings, valid until the next call */

/* toy KX (running-game) objects: a separate table from the RNA objects */
typedef struct {
  char name[64];
  double pos[3];
} mock_kx_obj;

static mock_kx_obj g_kx[8];
static int g_nkx = 0;

static void set_err(const char *msg) {
  snprintf(g_last_error, sizeof g_last_error, "%s", msg);
}

static mock_obj *obj_of(ujs_pointer p) {
  /* owner indexes the object table; data must match its address */
  if (!p.data || p.owner < 0 || p.owner >= g_nobjects) return NULL;
  if ((void *)&g_objects[p.owner] != p.data) return NULL;
  return &g_objects[p.owner];
}

static mock_prop *prop_find(mock_obj *o, const char *name) {
  for (int i = 0; i < o->nprops; i++)
    if (strcmp(o->props[i].name, name) == 0) return &o->props[i];
  return NULL;
}

static mock_obj *add_obj(const char *name) {
  mock_obj *o = &g_objects[g_nobjects];
  memset(o, 0, sizeof *o);
  snprintf(o->name, sizeof o->name, "%s", name);
  return &g_objects[g_nobjects++];
}

static mock_prop *add_prop(mock_obj *o, const char *name, int type, int array_len, const char *item_type) {
  mock_prop *p = &o->props[o->nprops];
  memset(p, 0, sizeof *p);
  snprintf(p->name, sizeof p->name, "%s", name);
  p->type = type;
  p->array_len = array_len;
  if (item_type) snprintf(p->item_type, sizeof p->item_type, "%s", item_type);
  o->nprops++;
  return p;
}

static void desc_of(const mock_prop *p, ujs_prop_desc *out) {
  snprintf(out->name, sizeof out->name, "%s", p->name);
  out->type = p->type;
  out->array_len = p->array_len;
  snprintf(out->item_type, sizeof out->item_type, "%s", p->item_type);
}

void ujs_test_reset(void) {
  /* free dynamic float arrays before rebuilding the table */
  for (int i = 0; i < g_nobjects; i++) {
    mock_obj *o = &g_objects[i];
    for (int j = 0; j < o->nprops; j++)
      if (o->props[j].type == UJS_PROP_FLOAT && o->props[j].array_len == -1)
        free(o->props[j].v.dyn);
  }
  g_nobjects = 0;
  g_last_error[0] = 0;

  mock_obj *scene = add_obj("Scene");
  scene->coll_members[0] = 1; /* Cube */
  scene->coll_members[1] = 2; /* Lamp */
  scene->ncoll = 2;

  mock_obj *cube = add_obj("Cube");
  mock_prop *name_p = add_prop(cube, "name", UJS_PROP_STRING, 0, NULL);
  snprintf(name_p->v.s, sizeof name_p->v.s, "Cube");
  mock_prop *loc = add_prop(cube, "location", UJS_PROP_FLOAT, 3, NULL);
  loc->v.fa[0] = 1.0; loc->v.fa[1] = 2.0; loc->v.fa[2] = 3.0;
  mock_prop *hide = add_prop(cube, "hide_viewport", UJS_PROP_BOOL, 0, NULL);
  hide->v.b = false;
  mock_prop *pass = add_prop(cube, "pass_index", UJS_PROP_INT, 0, NULL);
  pass->v.i = 7;
  mock_prop *mode = add_prop(cube, "mode", UJS_PROP_ENUM, 0, NULL);
  mode->v.e = 1;
  snprintf(mode->enum_id, sizeof mode->enum_id, "EDIT");
  mock_prop *data = add_prop(cube, "data", UJS_PROP_POINTER, 0, "Mesh");
  data->v.obj_index = 3;

  mock_obj *lamp = add_obj("Lamp");
  mock_prop *lname = add_prop(lamp, "name", UJS_PROP_STRING, 0, NULL);
  snprintf(lname->v.s, sizeof lname->v.s, "Lamp");
  mock_prop *energy = add_prop(lamp, "energy", UJS_PROP_FLOAT, 0, NULL);
  energy->v.f = 100.0;

  mock_obj *mesh = add_obj("Mesh");
  mock_prop *verts = add_prop(mesh, "vertices", UJS_PROP_FLOAT, -1, NULL);
  /* three vertices, xyz interleaved, dynamic-length */
  verts->dyn_len = 9;
  verts->v.dyn = malloc(9 * sizeof(double));
  verts->v.dyn[0] = 0; verts->v.dyn[1] = 0; verts->v.dyn[2] = 0;
  verts->v.dyn[3] = 1; verts->v.dyn[4] = 0; verts->v.dyn[5] = 0;
  verts->v.dyn[6] = 0; verts->v.dyn[7] = 1; verts->v.dyn[8] = 0;

  /* two toy KX objects in the "running game" */
  g_nkx = 0;
  snprintf(g_kx[g_nkx].name, sizeof g_kx[g_nkx].name, "GameCube");
  g_kx[g_nkx].pos[0] = 1; g_kx[g_nkx].pos[1] = 2; g_kx[g_nkx].pos[2] = 3;
  g_nkx++;
  snprintf(g_kx[g_nkx].name, sizeof g_kx[g_nkx].name, "GameSphere");
  g_kx[g_nkx].pos[0] = 4; g_kx[g_nkx].pos[1] = 5; g_kx[g_nkx].pos[2] = 6;
  g_nkx++;
}

/* --- error / root --------------------------------------------------------- */

const char *ujs_last_error(void) { return g_last_error[0] ? g_last_error : NULL; }

bool ujs_root(ujs_pointer *out) {
  if (g_nobjects == 0) { set_err("no state; call ujs_test_reset"); return false; }
  out->owner = 0;
  out->type = 0;
  out->data = &g_objects[0];
  return true;
}

/* --- introspection -------------------------------------------------------- */

bool ujs_prop_find(ujs_pointer owner, const char *name, ujs_prop_desc *out) {
  mock_obj *o = obj_of(owner);
  if (!o) { set_err("invalid pointer"); return false; }
  mock_prop *p = prop_find(o, name);
  if (!p) { set_err("no such property"); return false; }
  desc_of(p, out);
  return true;
}

typedef struct { mock_obj *o; int idx; } props_iter;

ujs_iter_t ujs_props_iter(ujs_pointer owner) {
  mock_obj *o = obj_of(owner);
  if (!o) return 0;
  props_iter *it = calloc(1, sizeof *it);
  it->o = o;
  return (ujs_iter_t)(intptr_t)it;
}

bool ujs_props_next(ujs_iter_t h, ujs_prop_desc *out) {
  props_iter *it = (props_iter *)(intptr_t)h;
  if (!it || it->idx >= it->o->nprops) return false;
  desc_of(&it->o->props[it->idx++], out);
  return true;
}

void ujs_props_iter_free(ujs_iter_t h) { free((void *)(intptr_t)h); }

/* --- get ------------------------------------------------------------------ */

bool ujs_get_bool(ujs_pointer owner, const char *name, bool *out) {
  mock_obj *o = obj_of(owner);
  if (!o) { set_err("invalid pointer"); return false; }
  mock_prop *p = prop_find(o, name);
  if (!p) { set_err("no such property"); return false; }
  if (p->type != UJS_PROP_BOOL) { set_err("not a bool property"); return false; }
  *out = p->v.b;
  return true;
}

bool ujs_get_int(ujs_pointer owner, const char *name, int64_t *out) {
  mock_obj *o = obj_of(owner);
  if (!o) { set_err("invalid pointer"); return false; }
  mock_prop *p = prop_find(o, name);
  if (!p) { set_err("no such property"); return false; }
  if (p->type != UJS_PROP_INT) { set_err("not an int property"); return false; }
  *out = p->v.i;
  return true;
}

bool ujs_get_float(ujs_pointer owner, const char *name, double *out) {
  mock_obj *o = obj_of(owner);
  if (!o) { set_err("invalid pointer"); return false; }
  mock_prop *p = prop_find(o, name);
  if (!p) { set_err("no such property"); return false; }
  if (p->type != UJS_PROP_FLOAT || p->array_len != 0) { set_err("not a float property"); return false; }
  *out = p->v.f;
  return true;
}

bool ujs_get_string(ujs_pointer owner, const char *name, const char **out) {
  mock_obj *o = obj_of(owner);
  if (!o) { set_err("invalid pointer"); return false; }
  mock_prop *p = prop_find(o, name);
  if (!p) { set_err("no such property"); return false; }
  if (p->type != UJS_PROP_STRING) { set_err("not a string property"); return false; }
  snprintf(g_str_buf, sizeof g_str_buf, "%s", p->v.s);
  *out = g_str_buf;
  return true;
}

bool ujs_get_enum(ujs_pointer owner, const char *name, int64_t *out, const char **idname_out) {
  mock_obj *o = obj_of(owner);
  if (!o) { set_err("invalid pointer"); return false; }
  mock_prop *p = prop_find(o, name);
  if (!p) { set_err("no such property"); return false; }
  if (p->type != UJS_PROP_ENUM) { set_err("not an enum property"); return false; }
  *out = p->v.e;
  if (idname_out) {
    snprintf(g_str_buf, sizeof g_str_buf, "%s", p->enum_id);
    *idname_out = g_str_buf;
  }
  return true;
}

bool ujs_get_pointer(ujs_pointer owner, const char *name, ujs_pointer *out) {
  mock_obj *o = obj_of(owner);
  if (!o) { set_err("invalid pointer"); return false; }
  mock_prop *p = prop_find(o, name);
  if (!p) { set_err("no such property"); return false; }
  if (p->type != UJS_PROP_POINTER) { set_err("not a pointer property"); return false; }
  if (p->v.obj_index < 0 || p->v.obj_index >= g_nobjects) {
    out->owner = 0; out->type = 0; out->data = NULL;
    return true;
  }
  out->owner = p->v.obj_index;
  out->type = 0;
  out->data = &g_objects[p->v.obj_index];
  return true;
}

static int dyn_len(const mock_prop *p) {
  return p->array_len > 0 ? p->array_len : p->dyn_len;
}

static double *dyn_data(const mock_prop *p) {
  return p->array_len > 0 ? p->v.fa : p->v.dyn;
}

bool ujs_get_float_array(ujs_pointer owner, const char *name, double *out, int32_t len) {
  mock_obj *o = obj_of(owner);
  if (!o) { set_err("invalid pointer"); return false; }
  mock_prop *p = prop_find(o, name);
  if (!p) { set_err("no such property"); return false; }
  if (p->type != UJS_PROP_FLOAT || p->array_len == 0) { set_err("not a float array property"); return false; }
  int n = dyn_len(p);
  if (len != n) { set_err("array length mismatch"); return false; }
  memcpy(out, dyn_data(p), n * sizeof(double));
  return true;
}

bool ujs_get_float_array_n(ujs_pointer owner, const char *name, double **out_data, int32_t *out_len) {
  mock_obj *o = obj_of(owner);
  if (!o) { set_err("invalid pointer"); return false; }
  mock_prop *p = prop_find(o, name);
  if (!p) { set_err("no such property"); return false; }
  if (p->type != UJS_PROP_FLOAT || p->array_len == 0) { set_err("not a float array property"); return false; }
  int n = dyn_len(p);
  double *d = malloc(n * sizeof(double));
  memcpy(d, dyn_data(p), n * sizeof(double));
  *out_data = d;
  *out_len = n;
  return true;
}

/* --- set ------------------------------------------------------------------ */

bool ujs_set_bool(ujs_pointer owner, const char *name, bool v) {
  mock_obj *o = obj_of(owner);
  if (!o) { set_err("invalid pointer"); return false; }
  mock_prop *p = prop_find(o, name);
  if (!p) { set_err("no such property"); return false; }
  if (p->type != UJS_PROP_BOOL) { set_err("not a bool property"); return false; }
  p->v.b = v;
  return true;
}

bool ujs_set_int(ujs_pointer owner, const char *name, int64_t v) {
  mock_obj *o = obj_of(owner);
  if (!o) { set_err("invalid pointer"); return false; }
  mock_prop *p = prop_find(o, name);
  if (!p) { set_err("no such property"); return false; }
  if (p->type != UJS_PROP_INT) { set_err("not an int property"); return false; }
  p->v.i = v;
  return true;
}

bool ujs_set_float(ujs_pointer owner, const char *name, double v) {
  mock_obj *o = obj_of(owner);
  if (!o) { set_err("invalid pointer"); return false; }
  mock_prop *p = prop_find(o, name);
  if (!p) { set_err("no such property"); return false; }
  if (p->type != UJS_PROP_FLOAT || p->array_len != 0) { set_err("not a float property"); return false; }
  p->v.f = v;
  return true;
}

bool ujs_set_string(ujs_pointer owner, const char *name, const char *v) {
  mock_obj *o = obj_of(owner);
  if (!o) { set_err("invalid pointer"); return false; }
  mock_prop *p = prop_find(o, name);
  if (!p) { set_err("no such property"); return false; }
  if (p->type != UJS_PROP_STRING) { set_err("not a string property"); return false; }
  snprintf(p->v.s, sizeof p->v.s, "%s", v);
  return true;
}

bool ujs_set_enum(ujs_pointer owner, const char *name, int64_t v) {
  mock_obj *o = obj_of(owner);
  if (!o) { set_err("invalid pointer"); return false; }
  mock_prop *p = prop_find(o, name);
  if (!p) { set_err("no such property"); return false; }
  if (p->type != UJS_PROP_ENUM) { set_err("not an enum property"); return false; }
  p->v.e = v;
  snprintf(p->enum_id, sizeof p->enum_id, "ENUM_%lld", (long long)v);
  return true;
}

bool ujs_set_pointer(ujs_pointer owner, const char *name, ujs_pointer v) {
  mock_obj *o = obj_of(owner);
  if (!o) { set_err("invalid pointer"); return false; }
  mock_prop *p = prop_find(o, name);
  if (!p) { set_err("no such property"); return false; }
  if (p->type != UJS_PROP_POINTER) { set_err("not a pointer property"); return false; }
  /* Blender ignores a data assignment on an Empty (rna_Object_data_set) */
  mock_prop *ty = prop_find(o, "type");
  if (strcmp(name, "data") == 0 && ty && strcmp(ty->v.s, "EMPTY") == 0) return true;
  p->v.obj_index = v.data ? (int)(intptr_t)v.owner : -1;
  return true;
}

bool ujs_set_float_array(ujs_pointer owner, const char *name, const double *v, int32_t len) {
  mock_obj *o = obj_of(owner);
  if (!o) { set_err("invalid pointer"); return false; }
  mock_prop *p = prop_find(o, name);
  if (!p) { set_err("no such property"); return false; }
  if (p->type != UJS_PROP_FLOAT || p->array_len == 0) { set_err("not a float array property"); return false; }
  int n = dyn_len(p);
  if (len != n) { set_err("array length mismatch"); return false; }
  memcpy(dyn_data(p), v, n * sizeof(double));
  return true;
}

/* --- collections ---------------------------------------------------------- */

bool ujs_coll_len(ujs_pointer owner, const char *name, int64_t *out) {
  mock_obj *o = obj_of(owner);
  if (!o) { set_err("invalid pointer"); return false; }
  /* the mock exposes one collection: Scene.objects */
  if (strcmp(o->name, "Scene") != 0 || strcmp(name, "objects") != 0) {
    set_err("not a collection property");
    return false;
  }
  *out = o->ncoll;
  return true;
}

typedef struct { mock_obj *o; int idx; } coll_iter;

ujs_iter_t ujs_coll_iter(ujs_pointer owner, const char *name) {
  mock_obj *o = obj_of(owner);
  if (!o) return 0;
  if (strcmp(o->name, "Scene") != 0 || strcmp(name, "objects") != 0) return 0;
  coll_iter *it = calloc(1, sizeof *it);
  it->o = o;
  return (ujs_iter_t)(intptr_t)it;
}

bool ujs_coll_next(ujs_iter_t h, ujs_pointer *out) {
  coll_iter *it = (coll_iter *)(intptr_t)h;
  if (!it || it->idx >= it->o->ncoll) return false;
  int member = it->o->coll_members[it->idx++];
  out->owner = member;
  out->type = 0;
  out->data = &g_objects[member];
  return true;
}

void ujs_coll_iter_free(ujs_iter_t h) { free((void *)(intptr_t)h); }

/* --- creation / structural mutation --------------------------------------- */

bool ujs_id_new(const char *type_name, const char *name, ujs_pointer *out) {
  bool is_object = strcmp(type_name, "Object") == 0;
  bool is_mesh   = strcmp(type_name, "Mesh") == 0;
  if (!is_object && !is_mesh) { set_err("unknown id type"); return false; }
  if (g_nobjects >= OBJ_MAX) { set_err("object table full"); return false; }
  int idx = g_nobjects;
  mock_obj *o = add_obj(name);
  mock_prop *np = add_prop(o, "name", UJS_PROP_STRING, 0, NULL);
  snprintf(np->v.s, sizeof np->v.s, "%s", name);
  if (is_object) {
    mock_prop *loc = add_prop(o, "location", UJS_PROP_FLOAT, 3, NULL);
    loc->v.fa[0] = 0; loc->v.fa[1] = 0; loc->v.fa[2] = 0;
    add_prop(o, "data", UJS_PROP_POINTER, 0, "Mesh")->v.obj_index = -1;
    snprintf(add_prop(o, "type", UJS_PROP_STRING, 0, NULL)->v.s, 64, "EMPTY");
  } else {
    add_prop(o, "vertices", UJS_PROP_FLOAT, -1, NULL);
  }
  out->owner = idx;
  out->type = 0;
  out->data = &g_objects[idx];
  return true;
}

bool ujs_object_new(const char *name, ujs_pointer data, ujs_pointer *out) {
  if (!ujs_id_new("Object", name, out)) return false;
  mock_obj *o = obj_of(*out);
  if (data.data) {
    prop_find(o, "data")->v.obj_index = (int)(intptr_t)data.owner;
    snprintf(prop_find(o, "type")->v.s, 64, "MESH");
  }
  return true;
}

bool ujs_mesh_from_pydata(ujs_pointer mesh,
                          const double *verts, int32_t nverts,
                          const int32_t *face_offsets, int32_t nfaces,
                          const int32_t *face_indices, int32_t nindices) {
  mock_obj *o = obj_of(mesh);
  if (!o) { set_err("invalid pointer"); return false; }
  mock_prop *p = prop_find(o, "vertices");
  if (!p || p->type != UJS_PROP_FLOAT || p->array_len != -1) {
    set_err("not a mesh (no dynamic vertices property)"); return false;
  }
  /* validate the face structure: offsets monotonic and in range, indices in
   * [0, nverts) -- the mock stores vertices only but still checks the input */
  if (nfaces > 0) {
    if (face_offsets[0] != 0 || face_offsets[nfaces] != nindices) {
      set_err("bad face offsets"); return false;
    }
    for (int i = 0; i < nfaces; i++)
      if (face_offsets[i] > face_offsets[i + 1]) { set_err("bad face offsets"); return false; }
    for (int i = 0; i < nindices; i++)
      if (face_indices[i] < 0 || face_indices[i] >= nverts) { set_err("face index out of range"); return false; }
  }
  int n = nverts * 3;
  double *d = malloc(n * sizeof(double));
  memcpy(d, verts, n * sizeof(double));
  free(p->v.dyn);
  p->v.dyn = d;
  p->dyn_len = n;
  return true;
}

bool ujs_coll_add(ujs_pointer coll, const char *name, ujs_pointer member) {
  mock_obj *o = obj_of(coll);
  if (!o) { set_err("invalid pointer"); return false; }
  if (strcmp(o->name, "Scene") != 0 || strcmp(name, "objects") != 0) {
    set_err("not a collection property"); return false;
  }
  if (!obj_of(member)) { set_err("invalid member"); return false; }
  for (int i = 0; i < o->ncoll; i++)
    if (o->coll_members[i] == (int)member.owner) return true; /* already linked */
  if (o->ncoll >= OBJ_MAX) { set_err("collection full"); return false; }
  o->coll_members[o->ncoll++] = (int)member.owner;
  return true;
}

bool ujs_coll_remove(ujs_pointer coll, const char *name, ujs_pointer member) {
  mock_obj *o = obj_of(coll);
  if (!o) { set_err("invalid pointer"); return false; }
  if (strcmp(o->name, "Scene") != 0 || strcmp(name, "objects") != 0) {
    set_err("not a collection property"); return false;
  }
  for (int i = 0; i < o->ncoll; i++) {
    if (o->coll_members[i] == (int)member.owner) {
      for (int j = i; j < o->ncoll - 1; j++) o->coll_members[j] = o->coll_members[j + 1];
      o->ncoll--;
      return true;
    }
  }
  return true; /* not present: removal is a no-op */
}

/* --- operators ------------------------------------------------------------ */

bool ujs_operator_call(const char *opname) {
  if (strcmp(opname, "mesh.primitive_cube_add") == 0) {
    /* a unit cube: 8 vertices, 6 quad faces (24 corner indices) */
    static const double verts[24] = {
      -1, -1, -1,   1, -1, -1,   1, 1, -1,  -1, 1, -1,
      -1, -1,  1,   1, -1,  1,   1, 1,  1,  -1, 1,  1,
    };
    static const int32_t face_offsets[7] = {0, 4, 8, 12, 16, 20, 24};
    static const int32_t face_indices[24] = {
      0, 1, 2, 3,  4, 5, 6, 7,  0, 1, 5, 4,  2, 3, 7, 6,  0, 4, 7, 3,  1, 5, 6, 2,
    };
    ujs_pointer mesh, obj, scene;
    if (!ujs_id_new("Mesh", "Cube", &mesh)) return false;
    if (!ujs_mesh_from_pydata(mesh, verts, 8, face_offsets, 6, face_indices, 24)) return false;
    if (!ujs_object_new("Cube", mesh, &obj)) return false;
    if (!ujs_root(&scene)) return false;
    return ujs_coll_add(scene, "objects", obj);
  }
  if (strcmp(opname, "object.select_all") == 0) {
    return true; /* no-op in the toy scene */
  }
  set_err("unknown operator");
  return false;
}

/* --- KX engine (running game) -------------------------------------------- */

static mock_kx_obj *kx_of(ujs_pointer p) {
  if (!p.data) return NULL;
  for (int i = 0; i < g_nkx; i++)
    if (&g_kx[i] == p.data) return &g_kx[i];
  return NULL;
}

bool ujs_kx_object_count(int64_t *out) {
  *out = g_nkx;
  return true;
}

bool ujs_kx_object_at(int64_t index, ujs_pointer *out) {
  if (index < 0 || index >= g_nkx) { set_err("index out of range"); return false; }
  out->owner = 0; out->type = 0; out->data = &g_kx[index];
  return true;
}

bool ujs_kx_object_find(const char *name, ujs_pointer *out) {
  for (int i = 0; i < g_nkx; i++) {
    if (strcmp(g_kx[i].name, name) == 0) {
      out->owner = 0; out->type = 0; out->data = &g_kx[i];
      return true;
    }
  }
  set_err("no such object");
  return false;
}

bool ujs_kx_name(ujs_pointer obj, const char **out) {
  mock_kx_obj *o = kx_of(obj);
  if (!o) { set_err("invalid kx object"); return false; }
  snprintf(g_str_buf, sizeof g_str_buf, "%s", o->name);
  *out = g_str_buf;
  return true;
}

bool ujs_kx_world_position(ujs_pointer obj, double *xyz) {
  mock_kx_obj *o = kx_of(obj);
  if (!o) { set_err("invalid kx object"); return false; }
  memcpy(xyz, o->pos, 3 * sizeof(double));
  return true;
}

bool ujs_kx_set_world_position(ujs_pointer obj, const double *xyz) {
  mock_kx_obj *o = kx_of(obj);
  if (!o) { set_err("invalid kx object"); return false; }
  memcpy(o->pos, xyz, 3 * sizeof(double));
  return true;
}

/* free memory this shim handed out (ujs_get_float_array_n) */
void ujs_free(void *ptr) { free(ptr); }
