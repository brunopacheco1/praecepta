# Full profile on a GitHub Actions runner

This is a cross-check of [the Apple M2 Pro run](../2026-11-07-apple-m2-pro-full/) on a different
machine, operating system and Java version. It is the `Benchmarks` workflow run with the `full`
profile, on commit `a7faa28`:
[run 42412238795](https://github.com/brunopacheco1/praecepta/actions/runs/42412238795). The files
are the workflow's `benchmark-results` artifact, unchanged.

- **Machine:** a shared `ubuntu-latest` runner, AMD EPYC 7763 with 4 vCPUs and 15 GiB, Linux,
  Temurin 25.0.5 (see `environment.txt`).
- **Engine:** the version says 0.2.0-SNAPSHOT, but the engine's code differs from 0.1.0 only in code
  style, annotations and `serialVersionUID`s, so it behaves as the 0.1.0 release does.

Shared runners are noisy, so read the absolute numbers with care:

- each of the 63 latency results is 1.04 to 2.07 times the M2 Pro's, and Praecepta is the fastest on
  all 21 tables, as it is there (Camunda and Drools swap places on three of them);
- Praecepta's 99.9th percentiles are around 10 µs, against 0.5 to 2.6 µs on the M2 Pro: that is the
  runner, not the engine;
- the runner has 4 vCPUs, so throughput stops growing after 4 threads, and the 8-thread numbers
  cannot be compared with the M2 Pro's.
