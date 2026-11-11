#!/usr/bin/env python3
# SPDX-FileCopyrightText: Bruno Pacheco
#
# SPDX-License-Identifier: Apache-2.0
"""Finds the published modules of a build, so the workflows need no list of them.

A module is published unless its POM says otherwise: modules that are never published (the benchmarks,
the TCK runner, test projects) set cyclonedx.skip, skipPublishing, maven.deploy.skip and
maven.install.skip (docs/INTEGRATIONS.md, "Registering a module"). So after `mvn verify`, every
module directory with a target/bom.json is a published module, and after the release build, every one
whose signed main jar is in target/ is a published jar module. Test projects copied under a target/
directory (maven-invoker-plugin's target/it) are never counted.

Usage, from the repository root:
  published_modules.py sboms              the published modules' SBOMs, one path per line
  published_modules.py merge-sboms OUT    writes one CycloneDX SBOM with every published module's components
  published_modules.py jars VERSION       "<module directory> <artifactId>" for every signed main jar
"""
import json
import os
import sys
from pathlib import Path

SKIPPED_DIRECTORIES = {".git", ".github", ".mvn", "node_modules", "src", "target"}


def module_directories(root):
    """Every directory below root with a pom.xml, not looking inside target/, src/ or hidden directories."""
    for directory, subdirectories, files in os.walk(root):
        subdirectories[:] = sorted(d for d in subdirectories if d not in SKIPPED_DIRECTORIES and not d.startswith("."))
        if "pom.xml" in files:
            yield Path(directory)


def sboms(root):
    return [module / "target" / "bom.json" for module in module_directories(root) if (module / "target" / "bom.json").is_file()]


def merge_sboms(root, out):
    """The union of the components of the published SBOMs, by package URL, as one CycloneDX document."""
    components = {}
    spec_version = "1.6"
    for path in sboms(root):
        with path.open(encoding="utf-8") as file:
            bom = json.load(file)
        spec_version = bom.get("specVersion", spec_version)
        for component in bom.get("components", []):
            key = component.get("purl") or component.get("bom-ref") or json.dumps(component, sort_keys=True)
            components.setdefault(key, component)
    merged = {
        "bomFormat": "CycloneDX",
        "specVersion": spec_version,
        "version": 1,
        "metadata": {"component": {"type": "library", "name": "praecepta (published modules)"}},
        "components": [components[key] for key in sorted(components)],
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as file:
        json.dump(merged, file, indent=2)
        file.write("\n")
    return len(components)


def jars(root, version):
    """(module directory, artifactId) for every module whose signed main jar of this version is in target/."""
    found = []
    suffix = f"-{version}.jar"
    for module in module_directories(root):
        target = module / "target"
        if not target.is_dir():
            continue
        for jar in sorted(target.glob(f"*{suffix}")):
            if (target / (jar.name + ".asc")).is_file():
                found.append((module.relative_to(root), jar.name[: -len(suffix)]))
    return found


def main(argv):
    root = Path.cwd().resolve()
    if argv[:1] == ["sboms"] and len(argv) == 1:
        for path in sboms(root):
            print(path.relative_to(root))
        return 0
    if argv[:1] == ["merge-sboms"] and len(argv) == 2:
        out = (root / argv[1]).resolve()
        if not out.is_relative_to(root):
            raise SystemExit(f"Refusing {argv[1]}: expected a path inside {root}")
        count = merge_sboms(root, out)
        print(f"{count} components in {out.relative_to(root)}", file=sys.stderr)
        return 0
    if argv[:1] == ["jars"] and len(argv) == 2:
        for module, artifact in jars(root, argv[1]):
            print(f"{module} {artifact}")
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
