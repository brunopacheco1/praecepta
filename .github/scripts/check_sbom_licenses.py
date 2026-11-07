#!/usr/bin/env python3
# SPDX-FileCopyrightText: Bruno Pacheco
#
# SPDX-License-Identifier: Apache-2.0
"""Fails if a published module depends on a licence outside the allowlist.

Reads CycloneDX SBOMs (target/bom.json, written by cyclonedx-maven-plugin) and checks every
component's licences. A component passes if any of its licences is allowed (dual licensing).
Components without licence information fail too, so they get a conscious decision.

Usage: check_sbom_licenses.py praecepta-engine/target/bom.json praecepta-loader/target/bom.json ...
"""
import json
import sys
from pathlib import Path

# Licences compatible with distributing the project under Apache-2.0 (permissive).
ALLOWED = {
    "Apache-2.0", "MIT", "MIT-0", "BSD-2-Clause", "BSD-3-Clause", "ISC", "0BSD",
    "Unlicense", "CC0-1.0", "Zlib", "BSL-1.0", "EDL-1.0",
}
# Our own modules: covered by this repository's REUSE check.
OWN_GROUP = "io.github.brunopacheco1"


def licences(component):
    found = []
    for entry in component.get("licenses", []):
        if "expression" in entry:
            found.append(entry["expression"])
        else:
            licence = entry.get("license", {})
            found.append(licence.get("id") or licence.get("name") or "?")
    return found


def load_bom(path, root):
    """The SBOM at path, or None if it is not a .json file inside root (SBOMs come from this build)."""
    resolved = (root / path).resolve()
    if not resolved.is_relative_to(root) or resolved.suffix != ".json":
        return None
    with resolved.open(encoding="utf-8") as file:
        return json.load(file)


def check_bom(bom, path):
    """Prints one line per third-party component; returns how many are not allowed."""
    module = bom.get("metadata", {}).get("component", {}).get("name", path)
    problems = 0
    for component in bom.get("components", []):
        if component.get("group") == OWN_GROUP:
            continue
        ids = licences(component)
        coordinates = f"{component.get('group', '')}:{component['name']}:{component.get('version', '')}"
        if any(licence in ALLOWED for licence in ids):
            print(f"ok           {module}: {coordinates} {ids}")
        else:
            problems += 1
            print(f"NOT ALLOWED  {module}: {coordinates} {ids or ['no licence information']}")
    return problems


def main(paths):
    if not paths:
        print(__doc__)
        return 2
    root = Path.cwd().resolve()
    problems = 0
    for path in paths:
        bom = load_bom(path, root)
        if bom is None:
            print(f"Refusing {path}: expected a .json file inside {root}")
            return 2
        problems += check_bom(bom, path)
    if problems:
        print(f"\n{problems} component(s) need review: allow their licence above, or drop the dependency.")
        return 1
    print("\nAll dependency licences are allowed.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
