#!/usr/bin/env python3
# SPDX-FileCopyrightText: Bruno Pacheco
#
# SPDX-License-Identifier: Apache-2.0
"""Writes a third-party notices file from CycloneDX SBOMs: every external component the published
modules depend on, with its licence and project page. Attached to each GitHub release.

Usage: third_party_notices.py VERSION praecepta-loader/target/bom.json ... > THIRD-PARTY-NOTICES.txt
"""
import json
import sys
from pathlib import Path

OWN_GROUP = "io.github.brunopacheco1"


def load_bom(path, root):
    resolved = (root / path).resolve()
    if not resolved.is_relative_to(root) or resolved.suffix != ".json":
        raise SystemExit(f"Refusing {path}: expected a .json file inside {root}")
    with resolved.open(encoding="utf-8") as file:
        return json.load(file)


def describe(component):
    licences = []
    for entry in component.get("licenses", []):
        licence = entry.get("license", {})
        licences.append(entry.get("expression") or licence.get("id") or licence.get("name") or "unknown")
    pages = [ref["url"] for ref in component.get("externalReferences", []) if ref.get("type") in ("website", "vcs")]
    return " OR ".join(licences) or "unknown", pages[0] if pages else ""


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    version, paths = argv[0], argv[1:]
    root = Path.cwd().resolve()
    components = {}
    for path in paths:
        bom = load_bom(path, root)
        module = bom.get("metadata", {}).get("component", {}).get("name", path)
        for component in bom.get("components", []):
            if component.get("group") == OWN_GROUP:
                continue
            key = f"{component.get('group', '')}:{component['name']}:{component.get('version', '')}"
            entry = components.setdefault(key, {"component": component, "modules": set()})
            entry["modules"].add(module)
    print(f"Praecepta {version} - third-party components")
    print()
    print("The published Praecepta modules depend on the following components (they are not bundled;")
    print("Maven downloads them). praecepta-engine has no dependencies.")
    print()
    for key in sorted(components):
        licence, page = describe(components[key]["component"])
        print(key)
        print(f"    Licence: {licence}")
        if page:
            print(f"    Project: {page}")
        print(f"    Used by: {', '.join(sorted(components[key]['modules']))}")
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
