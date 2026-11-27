#!/usr/bin/env python3
# SPDX-FileCopyrightText: Bruno Pacheco
#
# SPDX-License-Identifier: Apache-2.0
"""Runs a Maven build, and runs it once more if it failed because a download failed (#161).

Maven retries a request that fails on the network or gets 429 or 503 (.mvn/maven.config), but not one that
gets 404: to Maven, the artifact does not exist. Maven Central sometimes answers 404, for a few seconds, for
artifacts it has; the 0.12.0 release pull request failed so, on the BOMs of all three frameworks at once. Maven
also remembers the failure in the local repository (*.lastUpdated, resolver-status.properties) and does not
ask again, even in the next build.

So when a build fails and Maven's errors name an artifact it could not resolve, this forgets those failures and
runs the same build once more. A build that fails for any other reason, such as a test, is not run again, and
its exit status is returned unchanged. The errors of the test projects the invoker builds (logged with an
[INFO] prefix) count too: they have repositories of their own (*/target/it-repo).

Usage, from the repository root:
  retry_downloads.py mvn -B verify ...
"""
import re
import subprocess
import sys
from pathlib import Path

# Maven's own messages for an artifact it could not get, on an [ERROR] line (an invoker build's, too)
DOWNLOAD_FAILURE = re.compile(
    r"\[ERROR\].*("
    r"Could not transfer artifact"
    r"|Could not find artifact"
    r"|Non-resolvable (?:import|parent) POM"
    r"|The following artifacts could not be resolved"
    r"|was not found in \S+ during a previous attempt"
    r"|Failed to read artifact descriptor"
    r")")

# what the resolver writes to remember that a download failed
FAILURE_RECORDS = ("*.lastUpdated", "resolver-status.properties")


def is_download_failure(line):
    """True if a line of Maven's output says that an artifact could not be resolved."""
    return DOWNLOAD_FAILURE.search(line) is not None


def run(command):
    """Runs the command, copying its output as it comes; returns its exit status and whether a download failed."""
    failed_download = False
    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT) as process:
        for raw in process.stdout:
            sys.stdout.buffer.write(raw)
            sys.stdout.buffer.flush()
            if not failed_download and is_download_failure(raw.decode("utf-8", errors="replace")):
                failed_download = True
    return process.returncode, failed_download


def local_repositories(root, home):
    """The local repositories a build resolves into: the user's, and those of the invoker's test projects."""
    repositories = [home / ".m2" / "repository"]
    repositories.extend(sorted(root.glob("*/target/it-repo")))
    return [repository for repository in repositories if repository.is_dir()]


def forget_failures(repositories):
    """Deletes the resolver's records of failed downloads, so the next build asks again; returns how many."""
    deleted = 0
    for repository in repositories:
        for pattern in FAILURE_RECORDS:
            for record in repository.rglob(pattern):
                record.unlink()
                deleted += 1
    return deleted


def main(command, root=None, home=None, runner=run):
    status, failed_download = runner(command)
    if status == 0 or not failed_download:
        return status
    deleted = forget_failures(local_repositories(root or Path.cwd(), home or Path.home()))
    print(f"::warning title=Download failed::Maven could not download an artifact; running the build once more "
          f"(forgot {deleted} recorded failures)", flush=True)
    status, _ = runner(command)
    return status


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1:]))
