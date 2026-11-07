# SPDX-FileCopyrightText: Bruno Pacheco
#
# SPDX-License-Identifier: Apache-2.0

# ClusterFuzzLite builds the fuzz targets in this image (see build.sh). The image's own Java is 17,
# older than Praecepta's Java 21, so the targets get their own JDK and the Jazzer release the
# project's fuzz tests use (keep JAZZER_VERSION equal to jazzer.version in the root pom.xml).
# Every download is checked against its SHA-256.
FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:bd13a763de1a0fb22099442094e7e0049657a8c47f01e9d7af8bb849e0ad4699

ARG JDK_URL=https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jdk_x64_linux_hotspot_25.0.5_8.tar.gz
ARG JDK_SHA256=68982529869257d0ee6b824ec5aa270ff8b118bb438670da0b97ebdab178d7fb
ARG JAZZER_VERSION=0.30.0
ARG JAZZER_SHA256=8bdeac017bcd3d9473c9772fac62111c4df830188571def1d001a1b743a62b2f
ARG JAZZER_API_SHA256=d36a725cfedcb7f3590206866cc2836f84d12afccf7c98912f5c720e4d2208d7

RUN curl -fsSL -o /tmp/jdk.tar.gz "$JDK_URL" \
    && echo "$JDK_SHA256  /tmp/jdk.tar.gz" | sha256sum -c - \
    && mkdir "$SRC/jdk" \
    && tar -xzf /tmp/jdk.tar.gz -C "$SRC/jdk" --strip-components=1 \
    && rm /tmp/jdk.tar.gz \
    && curl -fsSL -o "$SRC/jazzer.jar" \
        "https://repo1.maven.org/maven2/com/code-intelligence/jazzer/$JAZZER_VERSION/jazzer-$JAZZER_VERSION.jar" \
    && echo "$JAZZER_SHA256  $SRC/jazzer.jar" | sha256sum -c - \
    && curl -fsSL -o "$SRC/jazzer-api.jar" \
        "https://repo1.maven.org/maven2/com/code-intelligence/jazzer-api/$JAZZER_VERSION/jazzer-api-$JAZZER_VERSION.jar" \
    && echo "$JAZZER_API_SHA256  $SRC/jazzer-api.jar" | sha256sum -c -

COPY . $SRC/praecepta
WORKDIR $SRC/praecepta
COPY .clusterfuzzlite/build.sh $SRC/
