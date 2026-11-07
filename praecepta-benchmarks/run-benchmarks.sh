#!/usr/bin/env bash
# SPDX-FileCopyrightText: Bruno Pacheco
#
# SPDX-License-Identifier: Apache-2.0
#
# Runs the benchmark matrix (Praecepta, Camunda and Drools) and writes every raw result, the machine
# and versions it ran on, and a summary into one directory.
#
#   praecepta-benchmarks/run-benchmarks.sh [full|quick] [output directory]
#
# full  (default) every suite, 3 forks: about 2.5 hours. For publishing: use a quiet machine on
#       mains power, with nothing else running.
# quick a few tables, 1 short fork: about 15 minutes. For spotting large regressions, e.g. in CI.
#
# Build first: mvn -pl praecepta-benchmarks -am package -DskipTests
# The java on the PATH (or $JAVA) runs the benchmarks; its version is recorded.
set -euo pipefail

profile=${1:-full}
root=$(cd "$(dirname "$0")/.." && pwd)
jar=$root/praecepta-benchmarks/target/benchmarks.jar
java=${JAVA:-java}
[ -f "$jar" ] || { echo "No $jar: build it first (see the header of this script)" >&2; exit 1; }

cpu=$(sysctl -n machdep.cpu.brand_string 2>/dev/null || sed -n 's/^model name[[:space:]]*: //p' /proc/cpuinfo | head -n 1)
slug=$(printf '%s' "$cpu" | tr '[:upper:]' '[:lower:]' | sed -E 's/\((r|tm)\)//g; s/ cpu|@.*//g; s/[^a-z0-9]+/-/g; s/^-+|-+$//g')
out=${2:-$root/praecepta-benchmarks/results/$(date +%Y-%m-%d)-$slug-$profile}
mkdir -p "$out"

case "$profile" in
  full)
    runs=(-f 3 -wi 5 -w 2s -i 5 -r 3s)
    scale=real,synthetic-10,synthetic-100,synthetic-1000,synthetic-10000,mixed-10,mixed-100,mixed-1000,mixed-10000
    columns=synthetic-1000x5,synthetic-1000x20,mixed-1000x6,mixed-1000x18
    policies=mixed-1000-first,mixed-1000-priority,mixed-1000-ruleorder,unique-1000
    nomatch=synthetic-1000,mixed-100,mixed-1000,unique-1000
    tails=real,mixed-1000,unique-1000
    throughput=real,mixed-1000
    threads=(1 4 8)
    lifecycle=real,mixed-100,mixed-1000
    first_forks=10
    ;;
  quick)
    runs=(-f 1 -wi 2 -w 1s -i 3 -r 1s)
    scale=real,synthetic-1000,mixed-1000
    columns=
    policies=mixed-1000-first,unique-1000
    nomatch=mixed-1000
    tails=mixed-1000
    throughput=real
    threads=(1 4)
    lifecycle=real
    first_forks=3
    ;;
  *)
    echo "Unknown profile '$profile': use full or quick" >&2
    exit 1
    ;;
esac
engines=praecepta,camunda,drools

jmh() {
  local suite=$1
  shift
  echo "== $suite: $*"
  "$java" -jar "$jar" -foe true -rf json -rff "$out/$suite.json" "$@" > "$out/$suite.log" 2>&1 \
    || { echo "$suite failed: see $out/$suite.log" >&2; exit 1; }
}

property() {
  sed -n "s:.*<$1>\(.*\)</$1>.*:\1:p" "$root/pom.xml" "$root/praecepta-benchmarks/pom.xml" | head -n 1
}

{
  echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "profile: $profile (${runs[*]})"
  echo "commit: $(git -C "$root" rev-parse --short HEAD)$(git -C "$root" diff --quiet HEAD -- . || echo ' (with uncommitted changes)')"
  echo "cpu: $cpu"
  if [ "$(uname)" = Darwin ]; then
    echo "cores: $(sysctl -n hw.ncpu) ($(sysctl -n hw.perflevel0.physicalcpu 2>/dev/null || echo '?') performance)"
    echo "memory: $(( $(sysctl -n hw.memsize) / 1073741824 )) GiB"
    echo "os: macOS $(sw_vers -productVersion)"
  else
    echo "cores: $(nproc)"
    echo "memory: $(awk '/MemTotal/ {printf "%d GiB", $2 / 1048576}' /proc/meminfo)"
    echo "os: $(uname -sr)"
  fi
  echo "java: $("$java" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.runtime.version = //p') ($("$java" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.vm.name = //p'))"
  echo "praecepta: $(sed -n 's:^    <version>\(.*\)</version>:\1:p' "$root/pom.xml" | head -n 1)"
  echo "camunda dmn-scala: $(property camunda-dmn.version)"
  echo "drools (kie-dmn-core): $(property kie.version)"
  echo "jmh: $(property jmh.version)"
} > "$out/environment.txt"
cat "$out/environment.txt"

echo "== footprint"
{
  printf '%-10s %-20s %14s %10s\n' engine table "retained heap" build
  for table in ${scale//,/ } ${columns//,/ } ${policies//,/ }; do
    for engine in ${engines//,/ }; do
      "$java" -Xmx6g -cp "$jar" io.github.brunopacheco1.praecepta.benchmarks.FootprintReport "$engine" "$table" 2>/dev/null | tail -n 1
    done
  done
} > "$out/footprint.txt"

# latency on one thread, with the memory allocated per decision
jmh scale EvaluationBenchmark "${runs[@]}" -prof gc -p engine=$engines -p table=$scale
[ -z "$columns" ] || jmh columns EvaluationBenchmark "${runs[@]}" -prof gc -p engine=$engines -p table=$columns
jmh policies EvaluationBenchmark "${runs[@]}" -prof gc -p engine=$engines -p table=$policies
jmh nomatch EvaluationBenchmark "${runs[@]}" -prof gc -p engine=$engines -p table=$nomatch -p workload=nomatch
# latency distribution
jmh tails EvaluationBenchmark "${runs[@]}" -bm sample -tu us -p engine=$engines -p table=$tails
# one engine shared by several threads
for t in "${threads[@]}"; do
  jmh "throughput-$t" ThroughputBenchmark "${runs[@]}" -t "$t" -p engine=$engines -p table=$throughput
done
# reading the DMN and building the engine, warm and in a fresh JVM
jmh build LifecycleBenchmark -p engine=$engines -p table=$lifecycle
"$java" -cp "$jar" io.github.brunopacheco1.praecepta.benchmarks.FirstDecisionFixtures "$out/fixtures" ${lifecycle//,/ }
jmh first-decision FirstDecisionBenchmark -f "$first_forks" -jvmArgsAppend "-Dpraecepta.fixtures=$out/fixtures" \
  -p engine=$engines -p table=$lifecycle
rm -rf "$out/fixtures"

python3 "$root/praecepta-benchmarks/summarise.py" "$out"
echo "Results: $out"
