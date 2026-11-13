#!/usr/bin/env bash
# SPDX-FileCopyrightText: Bruno Pacheco
#
# SPDX-License-Identifier: Apache-2.0

# Installs the Gradle that runs the Plugin Portal build (praecepta-gradle-plugin/portal) and puts it on the
# PATH of the following steps. The distribution is checked against its SHA-256, published at
# https://services.gradle.org/distributions/gradle-<version>-bin.zip.sha256.
set -euo pipefail

GRADLE_VERSION=9.8.2
GRADLE_SHA256=21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607

dir="${RUNNER_TEMP:?}/gradle"
mkdir -p "$dir"
curl --proto '=https' --proto-redir '=https' --tlsv1.2 -fsSL -o "$dir/gradle.zip" \
  "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
echo "${GRADLE_SHA256}  $dir/gradle.zip" | sha256sum -c -
unzip -q "$dir/gradle.zip" -d "$dir"
echo "$dir/gradle-${GRADLE_VERSION}/bin" >> "${GITHUB_PATH:?}"
