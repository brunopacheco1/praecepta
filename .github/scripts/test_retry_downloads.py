#!/usr/bin/env python3
# SPDX-FileCopyrightText: Bruno Pacheco
#
# SPDX-License-Identifier: Apache-2.0
"""Tests of retry_downloads.py: which failures it runs a build again for, and what it forgets first.

Usage, from the repository root: python3 -m unittest discover -s .github/scripts -p 'test_*.py'
"""
import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path

import retry_downloads


def touch(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("", encoding="utf-8")


class DownloadFailureTest(unittest.TestCase):

    def test_maven_resolution_errors_are_download_failures(self):
        for line in [
            # the 0.12.0 release pull request (#160): Maven Central answered 404 for a BOM it has
            "[ERROR] Non-resolvable import POM: The following artifacts could not be resolved: "
            "org.springframework.boot:spring-boot-dependencies:pom:4.1.2 (absent): Could not find artifact "
            "org.springframework.boot:spring-boot-dependencies:pom:4.1.2 in central "
            "(https://repo.maven.apache.org/maven2) @ line 29, column 25",
            "[ERROR] Non-resolvable import POM: The following artifacts could not be resolved: "
            "io.quarkus:quarkus-bom:pom:3.40.1.2 (absent): io.quarkus:quarkus-bom:pom:3.40.1.2 was not found in "
            "https://repo.maven.apache.org/maven2 during a previous attempt.",
            "[ERROR] Failed to execute goal on project praecepta-engine: Could not resolve dependencies: "
            "Could not transfer artifact org.junit:junit-bom:pom:6.0.1 from/to central: Connection reset",
            "[ERROR] Non-resolvable parent POM for com.example:app:1.0: Could not find artifact "
            "org.springframework.boot:spring-boot-starter-parent:pom:4.1.2",
            # a test project the invoker builds
            "[INFO] [ERROR] Failed to read artifact descriptor for io.micronaut:micronaut-inject:jar:4.10.1",
        ]:
            with self.subTest(line=line):
                self.assertTrue(retry_downloads.is_download_failure(line))

    def test_other_errors_are_not(self):
        for line in [
            "[ERROR] Tests run: 12, Failures: 1, Errors: 0, Skipped: 0",
            "[ERROR]   DecisionGraphTest.cycle:42 expected: <1> but was: <2>",
            "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.14.0:compile",
            # the words alone, outside an error, in a test's output
            "[INFO] Could not find artifact in the model: that is what this test checks",
            "Downloading from central: https://repo.maven.apache.org/maven2/org/junit/junit-bom/6.0.1/junit-bom-6.0.1.pom",
        ]:
            with self.subTest(line=line):
                self.assertFalse(retry_downloads.is_download_failure(line))


class RetryTest(unittest.TestCase):

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name) / "project"
        self.home = Path(self.directory.name) / "home"
        user_repository = self.home / ".m2" / "repository" / "io" / "quarkus" / "quarkus-bom" / "3.40.1.2"
        touch(user_repository / "quarkus-bom-3.40.1.2.pom.lastUpdated")
        touch(user_repository / "resolver-status.properties")
        touch(user_repository / "quarkus-bom-3.40.1.2.jar")
        it_repository = self.root / "praecepta-native-tests" / "target" / "it-repo" / "org" / "example" / "1.0"
        touch(it_repository / "example-1.0.jar.lastUpdated")
        touch(it_repository / "example-1.0.jar")
        self.runs = 0

    def tearDown(self):
        self.directory.cleanup()

    def runner(self, *results):
        def run(command):
            self.assertEqual(["mvn", "-B", "verify"], command)
            result = results[self.runs]
            self.runs += 1
            return result
        return run

    def main(self, *results):
        with contextlib.redirect_stdout(io.StringIO()) as out:
            status = retry_downloads.main(["mvn", "-B", "verify"], self.root, self.home, self.runner(*results))
        return status, out.getvalue()

    def remaining(self):
        return sorted(path.name for path in Path(self.directory.name).rglob("*") if path.is_file())

    def test_a_build_that_passes_runs_once(self):
        self.assertEqual((0, ""), self.main((0, False)))
        self.assertEqual(1, self.runs)

    def test_a_build_that_fails_otherwise_runs_once_and_keeps_its_status(self):
        self.assertEqual(1, self.main((1, False))[0])
        self.assertEqual(1, self.runs)
        self.assertIn("quarkus-bom-3.40.1.2.pom.lastUpdated", self.remaining())

    def test_a_failed_download_forgets_the_failures_and_runs_again(self):
        status, out = self.main((1, True), (0, False))
        self.assertEqual(0, status)
        self.assertEqual(2, self.runs)
        self.assertIn("::warning title=Download failed::", out)
        self.assertIn("forgot 3 recorded failures", out)
        self.assertEqual(["example-1.0.jar", "quarkus-bom-3.40.1.2.jar"], self.remaining())

    def test_the_build_runs_again_once_only(self):
        self.assertEqual(1, self.main((1, True), (1, True))[0])
        self.assertEqual(2, self.runs)

    def test_run_copies_the_output_and_finds_the_failure(self):
        script = ("print('[INFO] Scanning for projects...'); "
                  "print('[ERROR] Non-resolvable import POM: Could not find artifact a:b:pom:1'); "
                  "raise SystemExit(1)")
        out = io.BytesIO()
        with contextlib.redirect_stdout(io.TextIOWrapper(out, encoding="utf-8")) as wrapper:
            status, failed_download = retry_downloads.run([sys.executable, "-c", script])
            wrapper.flush()
            self.assertEqual((1, True), (status, failed_download))
            self.assertIn(b"Scanning for projects", out.getvalue())


if __name__ == "__main__":
    unittest.main()
