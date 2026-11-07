#!/bin/bash -eu
# SPDX-FileCopyrightText: Bruno Pacheco
#
# SPDX-License-Identifier: Apache-2.0
#
# Builds the fuzz targets for ClusterFuzzLite, inside the image from the Dockerfile. The engine has
# no dependencies, so javac is enough: the engine and the targets (shared with the JUnit fuzz tests)
# go into one jar, and each target gets a launcher script that runs Jazzer on the bundled JDK.

engine=praecepta-engine/src
classes=$WORK/classes
mkdir -p "$classes"

"$SRC/jdk/bin/javac" --release 21 -nowarn -d "$classes" -cp "$SRC/jazzer-api.jar" \
  $(find "$engine/main/java" -name '*.java' ! -name module-info.java) \
  "$engine/test/java/io/github/brunopacheco1/praecepta/engine/feel/CellParserFuzzer.java" \
  "$engine/test/java/io/github/brunopacheco1/praecepta/engine/dmn/DmnReaderFuzzer.java"
"$SRC/jdk/bin/jar" --create --file "$OUT/praecepta-fuzz.jar" -C "$classes" .
cp "$SRC/jazzer.jar" "$SRC/jazzer-api.jar" "$OUT/"
cp -r "$SRC/jdk" "$OUT/jdk"

# fuzzer name, target class, directory of the JUnit test's saved inputs (the seed corpus)
targets=(
  "cell_parser_fuzzer io.github.brunopacheco1.praecepta.engine.feel.CellParserFuzzer feel/CellParserFuzzTestInputs"
  "dmn_reader_fuzzer io.github.brunopacheco1.praecepta.engine.dmn.DmnReaderFuzzer dmn/DmnReaderFuzzTestInputs"
)
for target in "${targets[@]}"; do
  read -r name class inputs <<< "$target"
  cat > "$OUT/$name" <<SCRIPT
#!/bin/bash
# LLVMFuzzerTestOneInput: marks this script as a fuzz target for ClusterFuzzLite.
here=\$(dirname "\$0")
exec "\$here/jdk/bin/java" -Xmx1g \\
  -cp "\$here/jazzer.jar:\$here/jazzer-api.jar:\$here/praecepta-fuzz.jar" \\
  com.code_intelligence.jazzer.Jazzer --target_class=$class \\
  --instrumentation_includes='io.github.brunopacheco1.praecepta.**' "\$@"
SCRIPT
  chmod +x "$OUT/$name"
  find "$engine/test/resources/io/github/brunopacheco1/praecepta/engine/$inputs" -type f \
    -exec zip -q -j "$OUT/${name}_seed_corpus.zip" {} +
done
