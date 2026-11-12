#!/usr/bin/env python3
# SPDX-FileCopyrightText: Bruno Pacheco
#
# SPDX-License-Identifier: Apache-2.0
"""Tests of published_modules.py on a made-up build tree, so its release-only paths (jars) are checked
before a release tag runs them.

Usage, from the repository root: python3 -m unittest discover -s .github/scripts -p 'test_*.py'
"""
import contextlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path

import published_modules

VERSION = "1.2.3"


def touch(path, content=""):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def bom(*purls):
    return json.dumps({"bomFormat": "CycloneDX", "specVersion": "1.6",
                       "components": [{"name": purl.split("/")[-1], "purl": purl} for purl in purls]})


class PublishedModulesTest(unittest.TestCase):

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name).resolve()
        self.root = root
        touch(root / "pom.xml")
        # a published jar module: SBOM, signed main jar, and the sources jar beside it
        touch(root / "engine" / "pom.xml")
        touch(root / "engine" / "target" / "bom.json", bom("pkg:maven/a/x@1", "pkg:maven/a/y@1"))
        touch(root / "engine" / "target" / f"engine-{VERSION}.jar")
        touch(root / "engine" / "target" / f"engine-{VERSION}.jar.asc")
        touch(root / "engine" / "target" / f"engine-{VERSION}-sources.jar")
        touch(root / "engine" / "target" / f"engine-{VERSION}-sources.jar.asc")
        # a published module whose groupId differs (the Gradle plugin's marker): a POM, no jar
        touch(root / "marker" / "pom.xml")
        touch(root / "marker" / "target" / "bom.json", bom("pkg:maven/a/y@1", "pkg:maven/b/z@2"))
        # a module that is never published: no SBOM, and an unsigned jar
        touch(root / "benchmarks" / "pom.xml")
        touch(root / "benchmarks" / "target" / f"benchmarks-{VERSION}.jar")
        # a published jar module whose signature is missing: not a release jar
        touch(root / "loader" / "pom.xml")
        touch(root / "loader" / "target" / "bom.json", bom())
        touch(root / "loader" / "target" / f"loader-{VERSION}.jar")
        # a test project the invoker copied under target/it, with its own SBOM and signed jar
        touch(root / "plugin" / "pom.xml")
        touch(root / "plugin" / "target" / "it" / "app" / "pom.xml")
        touch(root / "plugin" / "target" / "it" / "app" / "target" / "bom.json", bom("pkg:maven/c/never@1"))
        touch(root / "plugin" / "target" / "it" / "app" / "target" / f"app-{VERSION}.jar")
        touch(root / "plugin" / "target" / "it" / "app" / "target" / f"app-{VERSION}.jar.asc")
        # sources are never searched for modules
        touch(root / "engine" / "src" / "it" / "pom.xml")

    def tearDown(self):
        self.directory.cleanup()

    def run_main(self, *arguments):
        output = io.StringIO()
        cwd = os.getcwd()
        os.chdir(self.root)
        try:
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(io.StringIO()):
                status = published_modules.main(list(arguments))
        finally:
            os.chdir(cwd)
        return status, output.getvalue().splitlines()

    def test_sboms_are_those_of_published_modules_outside_target(self):
        status, lines = self.run_main("sboms")
        self.assertEqual(0, status)
        self.assertEqual(["engine/target/bom.json", "loader/target/bom.json", "marker/target/bom.json"], lines)

    def test_merged_sbom_holds_each_component_once(self):
        status, _ = self.run_main("merge-sboms", "target/merged/bom.json")
        self.assertEqual(0, status)
        merged = json.loads((self.root / "target" / "merged" / "bom.json").read_text(encoding="utf-8"))
        self.assertEqual(["pkg:maven/a/x@1", "pkg:maven/a/y@1", "pkg:maven/b/z@2"],
                         [component["purl"] for component in merged["components"]])

    def test_merged_sbom_must_be_written_inside_the_repository(self):
        with self.assertRaises(SystemExit):
            self.run_main("merge-sboms", "../outside.json")

    def test_jars_are_the_signed_main_jars_of_the_version(self):
        status, lines = self.run_main("jars", VERSION)
        self.assertEqual(0, status)
        # not the sources jar, the unsigned jars, the marker's POM or the invoker's test project
        self.assertEqual(["engine engine"], lines)

    def test_jars_of_another_version_are_not_found(self):
        self.assertEqual((0, []), self.run_main("jars", "9.9.9"))

    def test_unknown_commands_print_the_usage(self):
        status, lines = self.run_main("deploy")
        self.assertEqual(2, status)
        self.assertIn("Usage, from the repository root:", lines)


if __name__ == "__main__":
    unittest.main()
