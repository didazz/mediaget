#!/usr/bin/env python3
"""Copy verified dependency resources into a build-only directory, preserving package paths."""
import pathlib
import sys
import zipfile
destination=pathlib.Path(sys.argv[1])
for name in sys.argv[2:]:
    with zipfile.ZipFile(name) as jar:
        for entry in jar.infolist():
            relative=pathlib.PurePosixPath(entry.filename)
            if entry.is_dir() or entry.filename.endswith((".class",".java")):
                continue
            if entry.filename.startswith("META-INF/") or entry.filename in ("LICENSE","LICENSE.txt","NOTICE"):
                continue
            if relative.is_absolute() or ".." in relative.parts:
                raise ValueError("Unsafe JAR resource path")
            target=destination.joinpath(*relative.parts)
            content=jar.read(entry)
            if target.exists() and target.read_bytes()!=content:
                raise ValueError("Conflicting dependency resource: "+entry.filename)
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(content)
