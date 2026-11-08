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
#define OBJ_MAX 8

typedef struct {
  char name[64];
  int type;
  int array_len;
  char item_type[64];
  union {
    bool b;
    int64_t i;
    double f;
    double fa[16];
    char s[128];
    int64_t e;
    int obj_index; /* UJS_PROP_POINTER: index into objects, -1 = null */
  } v;
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
  /* three vertices, xyz interleaved */
  verts->v.fa[0] = 0; verts->v.fa[1] = 0; verts->v.fa[2] = 0;
  verts->v.fa[3] = 1; verts->v.fa[4] = 0; verts->v.fa[5] = 0;
  verts->v.fa[6] = 0; verts->v.fa[7] = 1; verts->v.fa[8] = 0;
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
  return p->array_len > 0 ? p->array_len : 9;
}

bool ujs_get_float_array(ujs_pointer owner, const char *name, double *out, int32_t len) {
  mock_obj *o = obj_of(owner);
  if (!o) { set_err("invalid pointer"); return false; }
  mock_prop *p = prop_find(o, name);
  if (!p) { set_err("no such property"); return false; }
  if (p->type != UJS_PROP_FLOAT || p->array_len == 0) { set_err("not a float array property"); return false; }
  int n = dyn_len(p);
  if (len != n) { set_err("array length mismatch"); return false; }
  memcpy(out, p->v.fa, n * sizeof(double));
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
  memcpy(d, p->v.fa, n * sizeof(double));
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
  memcpy(p->v.fa, v, n * sizeof(double));
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
