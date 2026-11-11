# SPDX-FileCopyrightText: Bruno Pacheco
#
# SPDX-License-Identifier: Apache-2.0
"""Summarises a run of run-benchmarks.sh: reads the JMH JSON files of a results directory and writes
summary.md (tables per suite) and results.csv (one row per measurement) into it.

    python3 praecepta-benchmarks/summarise.py <results directory>
"""

import csv
import json
import sys
from pathlib import Path

ENGINES = ["praecepta", "camunda", "drools"]
EVALUATION_SUITES = [
    ("scale", "Latency by table size",
     "One decision on one thread, mean (± 99.9% confidence interval). `synthetic` tables have string columns, "
     "`mixed` ones string, number and date columns, `lookup` ones string columns with many values (a code per "
     "rule); all COLLECT. `real` is the 119-rule FIRST table."),
    ("columns", "Latency by number of columns", "1,000 rules; `x<n>` is the number of input columns."),
    ("policies", "Latency by hit policy",
     "The same 1,000 mixed rules under FIRST, PRIORITY and RULE ORDER (COLLECT is in the size table), and "
     "1,000 non-overlapping rules under UNIQUE."),
    ("nomatch", "Latency when no rule matches", "Inputs with values the table does not cover."),
]
LIFECYCLE_SUITES = [
    ("build", "Building the engine from DMN",
     "Read the DMN document and build a ready engine, in a warm JVM. Milliseconds."),
    ("first-decision", "First decision in a fresh JVM",
     "Read the DMN document, build the engine and evaluate one input, in a new JVM with nothing loaded. "
     "Milliseconds, mean over the forks."),
]
TO_MICROSECONDS = {"ns/op": 0.001, "us/op": 1, "ms/op": 1000}
TO_MILLISECONDS = {"us/op": 0.001, "ms/op": 1, "s/op": 1000}


def load(directory, suite):
    path = directory / f"{suite}.json"
    return json.loads(path.read_text()) if path.exists() and path.stat().st_size else []


def number(value):
    if value >= 100:
        return f"{value:,.0f}"
    if value >= 10:
        return f"{value:.1f}"
    return f"{value:.2f}"


def measured(entry, units):
    """Score ± error in the unit of `units`; no error with a single measurement (JMH reports NaN)."""
    if not entry:
        return "–"
    metric = entry["primaryMetric"]
    scale = units[metric["scoreUnit"]]
    error = metric["scoreError"] * scale
    return number(metric["score"] * scale) + ("" if error != error else f" ± {number(error)}")


def allocation(entry):
    metric = entry.get("secondaryMetrics", {}).get("gc.alloc.rate.norm") if entry else None
    return f"{metric['score']:,.0f}" if metric else "–"


def ratio(engines, engine):
    fast, slow = engines.get("praecepta"), engines.get(engine)
    if not fast or not slow:
        return "–"
    return f"{slow['primaryMetric']['score'] / fast['primaryMetric']['score']:,.0f}×"


def by_table(entries):
    tables = {}
    for entry in entries:
        params = entry.get("params", {})
        tables.setdefault(params.get("table"), {})[params.get("engine")] = entry
    return tables


def grid(header, rows, text_columns=1):
    """A Markdown table; the first `text_columns` columns are left-aligned, the others right-aligned."""
    alignment = "|" + "---|" * text_columns + "---:|" * (len(header) - text_columns)
    return ["| " + " | ".join(header) + " |", alignment] + ["| " + " | ".join(row) + " |" for row in rows]


def evaluation_section(entries, title, description):
    tables = by_table(entries)
    latency = [[f"`{table}`"] + [measured(engines.get(e), TO_MICROSECONDS) for e in ENGINES]
               + [ratio(engines, "camunda"), ratio(engines, "drools")]
               for table, engines in tables.items()]
    memory = [[f"`{table}`"] + [allocation(engines.get(e)) for e in ENGINES] for table, engines in tables.items()]
    return ([f"## {title}", "", description, "", "Latency in µs:", ""]
            + grid(["Table"] + ENGINES + ["Camunda / Praecepta", "Drools / Praecepta"], latency)
            + ["", "Memory allocated per decision, in bytes:", ""]
            + grid(["Table"] + ENGINES, memory) + [""])


def tails_section(entries):
    rows = []
    for entry in entries:
        percentiles = entry["primaryMetric"]["scorePercentiles"]
        rows.append([f"`{entry['params']['table']}`", entry["params"]["engine"]]
                    + [number(percentiles[q]) for q in ("50.0", "90.0", "99.0", "99.9", "100.0")])
    return (["## Latency distribution", "", "One decision on one thread, in µs.", ""]
            + grid(["Table", "Engine", "p50", "p90", "p99", "p99.9", "max"], rows, 2) + [""])


def throughput_section(runs):
    """`runs`: (table, engine) -> {threads: entry}."""
    threads = sorted({t for values in runs.values() for t in values})
    rows = [[f"`{table}`", engine]
            + [number(values[t]["primaryMetric"]["score"]) if t in values else "–" for t in threads]
            for (table, engine), values in runs.items()]
    return (["## Throughput with a shared engine", "",
             "Decisions per millisecond, all threads together, one engine instance shared by every thread.", ""]
            + grid(["Table", "Engine"] + [f"{t} thread{'s' if t > 1 else ''}" for t in threads], rows, 2) + [""])


def lifecycle_section(entries, title, description):
    rows = [[f"`{table}`"] + [measured(engines.get(e), TO_MILLISECONDS) for e in ENGINES]
            for table, engines in by_table(entries).items()]
    return [f"## {title}", "", description, ""] + grid(["Table"] + ENGINES, rows) + [""]


def write_csv(path, measurements):
    with open(path, "w", newline="") as file:
        writer = csv.writer(file)
        writer.writerow(["suite", "benchmark", "mode", "threads", "engine", "table", "workload",
                         "score", "error", "unit", "alloc_bytes_per_op"])
        for suite, entry in measurements:
            params, metric = entry.get("params", {}), entry["primaryMetric"]
            alloc = entry.get("secondaryMetrics", {}).get("gc.alloc.rate.norm")
            writer.writerow([suite, entry["benchmark"].rsplit(".", 2)[-2], entry["mode"], entry["threads"],
                             params.get("engine"), params.get("table"), params.get("workload", ""),
                             metric["score"], metric["scoreError"], metric["scoreUnit"],
                             alloc["score"] if alloc else ""])


def main(directory):
    out = ["# Benchmark results", ""]
    measurements = []
    environment = directory / "environment.txt"
    if environment.exists():
        out += ["```", environment.read_text().strip(), "```", ""]

    for suite, title, description in EVALUATION_SUITES:
        entries = load(directory, suite)
        if entries:
            out += evaluation_section(entries, title, description)
            measurements += [(suite, e) for e in entries]

    entries = load(directory, "tails")
    if entries:
        out += tails_section(entries)
        measurements += [("tails", e) for e in entries]

    runs = {}
    for path in sorted(directory.glob("throughput-*.json"), key=lambda p: int(p.stem.split("-")[1])):
        for entry in load(directory, path.stem):
            runs.setdefault((entry["params"]["table"], entry["params"]["engine"]), {})[entry["threads"]] = entry
            measurements.append((path.stem, entry))
    if runs:
        out += throughput_section(runs)

    for suite, title, description in LIFECYCLE_SUITES:
        entries = load(directory, suite)
        if entries:
            out += lifecycle_section(entries, title, description)
            measurements += [(suite, e) for e in entries]

    footprint = directory / "footprint.txt"
    if footprint.exists():
        out += ["## Retained heap and build time", "",
                "Heap retained by one built engine, and the time to build it once in a fresh JVM.", "",
                "```", footprint.read_text().strip(), "```", ""]

    (directory / "summary.md").write_text("\n".join(out))
    write_csv(directory / "results.csv", measurements)
    print(f"Wrote {directory / 'summary.md'} and {directory / 'results.csv'}")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
