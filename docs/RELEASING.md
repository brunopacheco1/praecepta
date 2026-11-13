<!--
SPDX-FileCopyrightText: Bruno Pacheco

SPDX-License-Identifier: Apache-2.0
-->

# Releasing

Pushing a signed tag `vX.Y.Z` runs [`.github/workflows/release.yml`](../.github/workflows/release.yml),
which:
1. checks the tag is annotated, signed by the maintainer (keys from `github.com/<owner>.gpg`) and
   listed in `CHANGELOG.md`;
2. sets the version from the tag, then builds and runs the tests;
3. signs every artifact with the **release key**;
4. publishes every module but the benchmarks, the TCK runner and the test projects that opt out
   (`skipPublishing`; see [CONTRIBUTING.md](../CONTRIBUTING.md#adding-a-module)) to Maven Central,
   waiting until they are live. No list of modules is kept: the build deploys every module that does not
   opt out, and the workflow finds the published jars from their signatures
   (`.github/scripts/published_modules.py`), so a new module needs no change here. As of 0.7.0 they are
   the parent POM (`praecepta`), `praecepta-engine`, `praecepta-loader` and `praecepta-maven-plugin`,
   and, since 0.5.0, `praecepta-compiler`, `praecepta-integration`, `praecepta-gradle-plugin` with its
   plugin marker, `praecepta-spring-boot-starter`, `praecepta-micronaut`, `praecepta-quarkus` and
   `praecepta-quarkus-deployment`;
5. records build provenance for the jars (GitHub artifact attestations);
6. creates a GitHub release with the changelog section, jars, signatures, SBOMs, the provenance
   bundle (`praecepta-X.Y.Z.intoto.jsonl`) and `THIRD-PARTY-NOTICES.txt`;
7. in a job of its own, publishes the Gradle plugin to the Gradle Plugin Portal (since 0.6.0): it fetches
   the plugin's jars and POM from Maven Central and uploads them unchanged with
   `praecepta-gradle-plugin/portal`, a Gradle build that compiles nothing, so the Portal serves the files
   Reproducible Central rebuilds. The Portal writes the plugin marker itself.

## One-time setup (owner)

### 1. Maven Central namespace
1. Sign in at <https://central.sonatype.com> with GitHub. The namespace `io.github.brunopacheco1`
   is verified automatically for that GitHub account (check **Namespaces**).
2. **View Account → Generate User Token**. Keep the username and password it shows; they are the
   publishing credentials, not your login.

### 2. Release signing key
A dedicated key, so your personal key (`E7CEC6DF14F7B2C7`) never leaves your machine:

```bash
gpg --quick-gen-key "Praecepta Releases <4491850+brunopacheco1@users.noreply.github.com>" ed25519 sign 2y
gpg --list-keys "Praecepta Releases"                                  # note the fingerprint
gpg --keyserver hkps://keys.openpgp.org --send-keys <FINGERPRINT>     # Maven Central checks signatures against public keyservers
gpg --keyserver hkps://keyserver.ubuntu.com --send-keys <FINGERPRINT>
gpg --armor --export-secret-keys <FINGERPRINT> > release-key.asc      # for the secret below; delete the file afterwards
```

If `gpg` reports `keyserver send failed: No route to host` (its network helper, `dirmngr`, fails on
some networks even over HTTPS), upload the public key over HTTPS directly; it sends only the
public part:

```bash
gpg --armor --export <FINGERPRINT> | curl --proto '=https' -fsS --data-urlencode keytext@- https://keyserver.ubuntu.com/pks/add
```

For keys.openpgp.org, paste the output of `gpg --armor --export <FINGERPRINT>` into the form at
<https://keys.openpgp.org/upload>.

Check it is served: `curl -fsS "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x<FINGERPRINT>"`.
keys.openpgp.org only publishes a key's name and email after the address is verified by email (the
GitHub noreply address cannot receive it); Maven Central needs only the key itself.

Optionally certify it with your personal key (`gpg --local-user E7CEC6DF14F7B2C7 --sign-key <FINGERPRINT>`),
then upload again, so the release key is visibly vouched for.

The Gradle plugin marker (`praecepta-gradle-plugin-marker`, a POM) has the coordinates Gradle looks up
for the plugin id, `io.github.brunopacheco1.praecepta:io.github.brunopacheco1.praecepta.gradle.plugin`.
Its groupId is a sub-namespace of `io.github.brunopacheco1`, which Maven Central accepts under the
verified namespace; check that **Namespaces** lists it as covered before the first release that
publishes it (0.5.0).

### 3. Gradle Plugin Portal
Sign in at <https://plugins.gradle.org> with the GitHub account, so the Portal accepts plugin ids under
`io.github.brunopacheco1`, then create an API key and secret under **API Keys**. The Portal reviews a
plugin's first version by hand, which takes a few days; later versions are published at once, unless the
plugin id or group changes. The Portal's `website` (in `praecepta-gradle-plugin/portal/build.gradle.kts`) must be
a page documenting the plugin itself, `site/gradle-plugin/`: the Portal rejected 0.6.0 when it pointed at the
project's home page. A cancelled submission may be published again under the same version, from the
maintainer's machine with the same command without `--validate-only`. To check a publication without
publishing, run, with the jars and POM in
place (as the release job fetches them) and the key and secret in `GRADLE_PUBLISH_KEY` and
`GRADLE_PUBLISH_SECRET`:

```bash
gradle -p praecepta-gradle-plugin/portal publishPlugins --validate-only -Ppraecepta.version=X.Y.Z
```

### 4. GitHub environment and secrets
**Settings → Environments → New environment** `release`. Restrict it to tags matching `v*`
(**Deployment branches and tags → Selected → Add rule → Tag `v*`**). Add these environment secrets:

| Secret | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | user token username (step 1) |
| `MAVEN_CENTRAL_PASSWORD` | user token password (step 1) |
| `RELEASE_GPG_KEY` | contents of `release-key.asc` (step 2) |
| `RELEASE_GPG_PASSPHRASE` | the release key's passphrase |
| `GRADLE_PUBLISH_KEY` | Plugin Portal API key (step 3) |
| `GRADLE_PUBLISH_SECRET` | Plugin Portal API secret (step 3) |

Then delete `release-key.asc`. Optionally require yourself as a reviewer on the environment, so
every release waits for a click.

## Releasing a version
1. In a pull request, rename `## [Unreleased]` in `CHANGELOG.md` to `## [X.Y.Z] - YYYY-MM-DD`,
   add a fresh empty `## [Unreleased]` above it, update the version links at the bottom, and merge.
2. Tag the merge commit with your personal key and push the tag:
   ```bash
   git switch main && git pull
   git tag -s vX.Y.Z -m "Praecepta X.Y.Z"
   git push origin vX.Y.Z
   ```
3. Watch **Actions → Release**. When it is green, the artifacts are on Maven Central (search can
   take a few hours to show them), the GitHub release is published and the Gradle plugin is on the
   Plugin Portal. If only the **Publish to the Gradle Plugin Portal** job fails, fix the cause and
   re-run that job alone (**Re-run failed jobs**): it needs nothing from the other job but Maven
   Central's files.
4. Set the development version for the next release (`mvn versions:set -DnewVersion=X.Y.(Z+1)-SNAPSHOT
   -DgenerateBackupPoms=false -DprocessAllModules`) in a pull request. This also refreshes
   `project.build.outputTimestamp`, which the next release keeps (see *Rebuilding a release*).

## Checking a release
- Signatures: download a jar and its `.asc`, then `gpg --verify praecepta-engine-X.Y.Z.jar.asc`.
- Provenance: `gh attestation verify praecepta-engine-X.Y.Z.jar --repo brunopacheco1/praecepta`, or
  offline against the release's `praecepta-X.Y.Z.intoto.jsonl` bundle (`--bundle`).
- SBOM: `praecepta-engine-X.Y.Z-cyclonedx.json` on the release (and on Maven Central).
- Reproducibility: see the next section.

## Rebuilding a release
Builds are reproducible: the same sources, JDK major version and build timestamp give
bit-for-bit identical artifacts. The release workflow changes only the version: it runs
`versions:set` with `-DupdateBuildOutputTimestampPolicy=never`, so `project.build.outputTimestamp`
stays as committed in the tag. To rebuild release X.Y.Z from its tag, with the JDK major version
it was built with (in the jar manifests' `Build-Jdk-Spec`; 25 for the current workflow):

```bash
git checkout vX.Y.Z
./mvnw versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false -DprocessAllModules -DupdateBuildOutputTimestampPolicy=never
./mvnw -Prelease clean package -pl '!praecepta-benchmarks,!praecepta-tck' -DskipTests -Dinvoker.skip -Dgpg.skip
```

Releases up to 0.4.0 were built with `-pl praecepta-engine,praecepta-loader,praecepta-maven-plugin -am`
instead; rebuild those with that list. From 0.5.0 the command names only the modules left out, so a new
published module changes nothing here, and the rebuild covers every published module, the Gradle plugin
marker included. Every module is a directory directly under the root (the Gradle plugin marker too,
though its groupId differs), so the version rewrite below (`pom.xml */pom.xml`) reaches all of them.

The release workflow builds with the Maven Wrapper (`./mvnw`), which pins the Maven version in
`.mvn/wrapper/maven-wrapper.properties` (3.9.15, checked by its SHA-256). Releases before 0.4.0 used
the runner's Maven (3.9.15 for 0.3.0). If you change the wrapper's Maven version, the next release's
`.buildspec` needs `tool=mvn-<version>` to match.

[Reproducible Central](https://github.com/jvm-repo-rebuild/reproducible-central) rebuilds releases
independently and compares them with Maven Central. Its `.buildspec` for a release uses the same
command and replaces the version with
`execBefore="perl -pi -e 's|<version>${version}-SNAPSHOT</version>|<version>${version}</version>|' pom.xml */pom.xml"`,
where `${version}` is the buildspec's own `version=`. Written that way, the buildspec does not name
its release, so Reproducible Central's tooling can add the next release on its own: it notices new
versions on Maven Central and copies the previous buildspec, changing only `version=` (and `jdk=` if
the jars name another JDK). Nothing needs doing per release unless the rebuild command changes; a
pull request adding `praecepta-X.Y.Z.buildspec` there is not wanted. When the command changes, a pull
request there must change the `command=` of the latest release's buildspec, so that the next release copies
it. The command last changed in 0.5.0, but Reproducible Central added 0.5.0 on its own by copying 0.4.0's
buildspec, so it rebuilds only the engine, the loader, the Maven plugin and the modules they need (22
artifacts); its `command=` needs the one above, which rebuilds all 44 of 0.5.0's artifacts identically. 0.2.0 is
the exception: its workflow still stamped the time of the release run, so its `.buildspec` names 0.2.0
and also sets `project.build.outputTimestamp` to `2026-11-08T20:08:06Z`.

## If publishing fails
If publishing fails after the tag is pushed, fix the cause, delete the tag (locally and with
`git push origin :refs/tags/vX.Y.Z`), and tag again. Maven Central never accepts the same version
twice, so a version that did reach Central needs a new patch number.
