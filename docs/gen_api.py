#!/usr/bin/env python3
"""Regenerate docs/api.md from the public functions and docstrings in src/.

Run from the project root:  jolt docs   (or python3 docs/gen_api.py)
"""
import io
import contextlib
import re, sys, textwrap
def balanced(s,i,o,c):
    d=0
    for j in range(i,len(s)):
        if s[j]=='"':  # skip strings
            k=j+1
            while s[k]!='"': k+= 2 if s[k]=='\\' else 1
            continue
        if s[j]==o: d+=1
        elif s[j]==c:
            d-=1
            if d==0: return s[i:j+1]
    return s[i:]
def dedent_doc(d):
    d=d.replace('\\"','"')
    first,_,rest=d.partition("\n")
    return (first+"\n"+textwrap.dedent(rest)).strip()
def parse(src):
    out=[]
    for m in re.finditer(r'\n\(defn ([^\s\[]+)',src):
        name=m.group(1); i=m.end()
        rest=src[i:]
        dm=re.match(r'\s*"((?:[^"\\]|\\.)*)"',rest,re.S)
        doc=None
        if dm: doc=dm.group(1); rest=rest[dm.end():]
        rest=rest.lstrip()
        arities=[]
        if rest.startswith("["):
            arities=[balanced(rest,0,"[","]")]
        elif rest.startswith("("):
            k=0
            while k<len(rest) and rest[k]=="(":
                form=balanced(rest,k,"(",")")
                arities.append(balanced(form,form.index("["),"[","]"))
                k+=len(form)
                while k<len(rest) and rest[k] in " \n\t": k+=1
        out.append((name,doc,arities))
    return out

NAMESPACES = [
    ("src/upbge_jolt/bpy.clj", "upbge-jolt.bpy"),
    ("src/upbge_jolt/bridge.clj", "upbge-jolt.bridge"),
    ("src/upbge_jolt/nodes.clj", "upbge-jolt.nodes"),
    ("src/upbge_jolt/game.clj", "upbge-jolt.game"),
    ("src/upbge_jolt/bge.clj", "upbge-jolt.bge"),
    ("src/upbge_jolt/audio.clj", "upbge-jolt.audio"),
    ("src/upbge_jolt/core.clj", "upbge-jolt.core"),
]

HEADER = """# API reference

Generated from the source docstrings by `docs/gen_api.py` (`jolt docs`); do
not edit by hand. Guides: [getting started](getting-started.md),
[building scenes](scenes.md), [game logic](game-logic.md),
[releasing](releasing.md), [architecture](architecture.md),
[troubleshooting](troubleshooting.md).

Functions that touch Blender or the game (`bpy`, `bridge`, `nodes`, `game`,
`bge`, `audio`) marshal onto the host thread themselves, so they can be
called from any nREPL session and from game frame handlers alike.

Namespaces: """ + ", ".join(f"[`{ns}`](#{ns.replace('.', '')})" for _, ns in NAMESPACES) + "\n"


def render():
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        print(HEADER)
        for path, ns in NAMESPACES:
            src = open(path).read()
            print(f"\n## {ns}\n")
            nsdoc = re.search(r'\(ns \S+\s*\n\s*"((?:[^"\\]|\\.)*)"', src)
            if nsdoc:
                print(dedent_doc(nsdoc.group(1)) + "\n")
            for name, doc, ar in parse(src):
                sig = " ".join("`(" + name + (" " + x[1:-1] if x[1:-1] else "") + ")`" for x in ar)
                print(f"### `{name}`\n\n{sig}\n")
                if doc:
                    print(dedent_doc(doc) + "\n")
    return buf.getvalue().rstrip() + "\n"


if __name__ == "__main__":
    open("docs/api.md", "w").write(render())
    print("wrote docs/api.md")
