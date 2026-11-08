# Benchmark results

```
date: 2026-11-07T20:42:07Z
profile: full (-f 3 -wi 5 -w 2s -i 5 -r 3s)
commit: a7faa28
cpu: AMD EPYC 7763 64-Core Processor
cores: 4
memory: 15 GiB
os: Linux 7.0.0-1019-azure
java: 25.0.5+8-LTS (OpenJDK 64-Bit Server VM)
praecepta: 0.2.0-SNAPSHOT
camunda dmn-scala: 1.12.6
drools (kie-dmn-core): 10.2.0
jmh: 1.37
benchmarks.jar sha256: 455a52703c63b5ab06a1acea24c6969b26a3d74774c767c23868efe3c5b66781
```

## Latency by table size

One decision on one thread, mean (± 99.9% confidence interval). `synthetic` tables have string columns, `mixed` ones string, number and date columns; both COLLECT. `real` is the 119-rule FIRST table.

Latency in µs:

| Table | praecepta | camunda | drools | Camunda / Praecepta | Drools / Praecepta |
|---|---:|---:|---:|---:|---:|
| `real` | 0.33 ± 0.01 | 23.1 ± 1.49 | 63.1 ± 1.13 | 70× | 192× |
| `synthetic-10` | 0.21 ± 0.01 | 7.24 ± 0.92 | 8.19 ± 0.18 | 34× | 39× |
| `synthetic-100` | 0.24 ± 0.01 | 27.3 ± 0.74 | 20.8 ± 0.32 | 112× | 86× |
| `synthetic-1000` | 0.35 ± 0.01 | 212 ± 12.7 | 184 ± 5.96 | 612× | 529× |
| `synthetic-10000` | 0.82 ± 0.01 | 2,243 ± 44.9 | 2,053 ± 92.6 | 2,730× | 2,499× |
| `mixed-10` | 0.36 ± 0.01 | 13.2 ± 1.09 | 11.8 ± 0.28 | 37× | 33× |
| `mixed-100` | 0.61 ± 0.02 | 55.9 ± 3.42 | 43.5 ± 0.98 | 91× | 71× |
| `mixed-1000` | 0.92 ± 0.01 | 519 ± 26.6 | 378 ± 19.4 | 564× | 412× |
| `mixed-10000` | 2.13 ± 0.03 | 5,322 ± 510 | 6,157 ± 883 | 2,496× | 2,887× |

Memory allocated per decision, in bytes:

| Table | praecepta | camunda | drools |
|---|---:|---:|---:|
| `real` | 32 | 80,152 | 86,466 |
| `synthetic-10` | 72 | 25,188 | 18,605 |
| `synthetic-100` | 80 | 106,724 | 37,222 |
| `synthetic-1000` | 193 | 861,695 | 238,104 |
| `synthetic-10000` | 1,349 | 8,750,049 | 2,262,181 |
| `mixed-10` | 72 | 32,463 | 20,834 |
| `mixed-100` | 81 | 151,611 | 57,790 |
| `mixed-1000` | 264 | 1,262,907 | 443,603 |
| `mixed-10000` | 1,769 | 12,075,484 | 4,255,762 |

## Latency by number of columns

1,000 rules; `x<n>` is the number of input columns.

Latency in µs:

| Table | praecepta | camunda | drools | Camunda / Praecepta | Drools / Praecepta |
|---|---:|---:|---:|---:|---:|
| `synthetic-1000x5` | 0.34 ± 0.01 | 155 ± 9.42 | 190 ± 5.21 | 448× | 551× |
| `synthetic-1000x20` | 0.65 ± 0.02 | 361 ± 3.44 | 221 ± 25.7 | 552× | 337× |
| `mixed-1000x6` | 0.87 ± 0.01 | 438 ± 11.6 | 385 ± 4.39 | 504× | 443× |
| `mixed-1000x18` | 1.79 ± 0.10 | 695 ± 27.1 | 382 ± 3.32 | 389× | 214× |

Memory allocated per decision, in bytes:

| Table | praecepta | camunda | drools |
|---|---:|---:|---:|
| `synthetic-1000x5` | 313 | 622,449 | 243,362 |
| `synthetic-1000x20` | 192 | 1,467,933 | 272,540 |
| `mixed-1000x6` | 482 | 1,119,913 | 443,918 |
| `mixed-1000x18` | 192 | 1,852,407 | 449,787 |

## Latency by hit policy

The same 1,000 mixed rules under FIRST, PRIORITY and RULE ORDER (COLLECT is in the size table), and 1,000 non-overlapping rules under UNIQUE.

Latency in µs:

| Table | praecepta | camunda | drools | Camunda / Praecepta | Drools / Praecepta |
|---|---:|---:|---:|---:|---:|
| `mixed-1000-first` | 0.78 ± 0.04 | 103 ± 5.60 | 384 ± 7.75 | 131× | 490× |
| `mixed-1000-priority` | 0.79 ± 0.03 | 511 ± 10.6 | 398 ± 3.53 | 644× | 502× |
| `mixed-1000-ruleorder` | 0.93 ± 0.03 | 491 ± 5.66 | 389 ± 18.4 | 530× | 420× |
| `unique-1000` | 0.32 ± 0.00 | 200 ± 6.39 | 152 ± 4.40 | 632× | 480× |

Memory allocated per decision, in bytes:

| Table | praecepta | camunda | drools |
|---|---:|---:|---:|
| `mixed-1000-first` | 144 | 252,634 | 441,449 |
| `mixed-1000-priority` | 144 | 1,295,329 | 448,948 |
| `mixed-1000-ruleorder` | 264 | 1,261,417 | 444,193 |
| `unique-1000` | 144 | 809,695 | 199,047 |

## Latency when no rule matches

Inputs with values the table does not cover.

Latency in µs:

| Table | praecepta | camunda | drools | Camunda / Praecepta | Drools / Praecepta |
|---|---:|---:|---:|---:|---:|
| `synthetic-1000` | 0.34 ± 0.01 | 208 ± 4.16 | 188 ± 3.67 | 617× | 560× |
| `mixed-100` | 0.64 ± 0.01 | 47.5 ± 0.57 | 38.1 ± 0.71 | 75× | 60× |
| `mixed-1000` | 0.49 ± 0.01 | 452 ± 5.70 | 274 ± 6.13 | 915× | 554× |
| `unique-1000` | 0.23 ± 0.00 | 203 ± 7.71 | 149 ± 10.2 | 895× | 656× |

Memory allocated per decision, in bytes:

| Table | praecepta | camunda | drools |
|---|---:|---:|---:|
| `synthetic-1000` | 144 | 798,501 | 228,832 |
| `mixed-100` | 32 | 140,037 | 52,243 |
| `mixed-1000` | 144 | 1,235,869 | 337,859 |
| `unique-1000` | 144 | 791,044 | 194,243 |

## Latency distribution

One decision on one thread, in µs.

| Table | Engine | p50 | p90 | p99 | p99.9 | max |
|---|---|---:|---:|---:|---:|---:|
| `real` | praecepta | 0.35 | 0.38 | 0.57 | 9.36 | 324 |
| `mixed-1000` | praecepta | 0.96 | 1.10 | 1.46 | 10.7 | 3,990 |
| `unique-1000` | praecepta | 0.34 | 0.37 | 0.51 | 9.52 | 3,764 |
| `real` | camunda | 22.1 | 33.4 | 42.0 | 60.0 | 3,256 |
| `mixed-1000` | camunda | 484 | 551 | 629 | 1,187 | 1,894 |
| `unique-1000` | camunda | 188 | 204 | 225 | 358 | 3,883 |
| `real` | drools | 63.1 | 74.9 | 86.4 | 111 | 11,223 |
| `mixed-1000` | drools | 385 | 419 | 448 | 749 | 13,795 |
| `unique-1000` | drools | 152 | 161 | 179 | 287 | 12,468 |

## Throughput with a shared engine

Decisions per millisecond, all threads together, one engine instance shared by every thread.

| Table | Engine | 1 thread | 4 threads | 8 threads |
|---|---|---:|---:|---:|
| `real` | praecepta | 3,194 | 8,075 | 7,913 |
| `mixed-1000` | praecepta | 1,100 | 2,840 | 2,822 |
| `real` | camunda | 45.3 | 93.4 | 96.4 |
| `mixed-1000` | camunda | 2.11 | 4.45 | 4.79 |
| `real` | drools | 15.7 | 35.6 | 35.5 |
| `mixed-1000` | drools | 2.60 | 5.54 | 5.77 |

## Building the engine from DMN

Read the DMN document and build a ready engine, in a warm JVM. Milliseconds.

| Table | praecepta | camunda | drools |
|---|---:|---:|---:|
| `real` | 19.3 ± 3.17 | 79.1 ± 12.2 | 93.8 ± 24.1 |
| `mixed-100` | 23.0 ± 4.21 | 82.2 ± 10.3 | 137 ± 47.0 |
| `mixed-1000` | 73.8 ± 7.31 | 114 ± 21.7 | 668 ± 10.4 |

## First decision in a fresh JVM

Read the DMN document, build the engine and evaluate one input, in a new JVM with nothing loaded. Milliseconds, mean over the forks.

| Table | praecepta | camunda | drools |
|---|---:|---:|---:|
| `real` | 207 ± 4.90 | 1,150 ± 34.8 | 1,029 ± 34.4 |
| `mixed-100` | 210 ± 10.9 | 1,198 ± 21.2 | 1,189 ± 37.8 |
| `mixed-1000` | 706 ± 87.0 | 2,054 ± 111 | 2,615 ± 223 |

## Retained heap and build time

Heap retained by one built engine, and the time to build it once in a fresh JVM.

```
engine     table                 retained heap      build
praecepta  real                    0.2 MB     174 ms
camunda    real                    5.0 MB    1080 ms
drools     real                    7.6 MB    1074 ms
praecepta  synthetic-10            0.4 MB     123 ms
camunda    synthetic-10            3.6 MB     953 ms
drools     synthetic-10            7.1 MB     760 ms
praecepta  synthetic-100           0.4 MB     198 ms
camunda    synthetic-100           4.9 MB    1116 ms
drools     synthetic-100           7.8 MB     999 ms
praecepta  synthetic-1000          0.5 MB     503 ms
camunda    synthetic-1000         17.5 MB    1857 ms
drools     synthetic-1000         15.1 MB    1805 ms
praecepta  synthetic-10000         1.5 MB    1374 ms
camunda    synthetic-10000       146.0 MB    4213 ms
drools     synthetic-10000        96.5 MB    4094 ms
praecepta  mixed-10                0.5 MB     152 ms
camunda    mixed-10                3.7 MB     976 ms
drools     mixed-10                7.3 MB     871 ms
praecepta  mixed-100               0.5 MB     271 ms
camunda    mixed-100               5.0 MB    1266 ms
drools     mixed-100               8.3 MB    1292 ms
praecepta  mixed-1000              1.0 MB     804 ms
camunda    mixed-1000             17.6 MB    2127 ms
drools     mixed-1000             17.6 MB    3094 ms
praecepta  mixed-10000             5.0 MB    2445 ms
camunda    mixed-10000           145.0 MB    5070 ms
drools     mixed-10000           116.4 MB   10651 ms
praecepta  synthetic-1000x5         0.5 MB     392 ms
camunda    synthetic-1000x5        11.6 MB    1650 ms
drools     synthetic-1000x5        12.1 MB    1514 ms
praecepta  synthetic-1000x20         0.5 MB     723 ms
camunda    synthetic-1000x20        29.2 MB    1939 ms
drools     synthetic-1000x20        21.9 MB    2880 ms
praecepta  mixed-1000x6            0.9 MB     737 ms
camunda    mixed-1000x6           13.7 MB    1976 ms
drools     mixed-1000x6           14.7 MB    2335 ms
praecepta  mixed-1000x18           1.5 MB    1103 ms
camunda    mixed-1000x18          29.1 MB    2540 ms
drools     mixed-1000x18          26.2 MB    3878 ms
praecepta  mixed-1000-first         1.7 MB    4144 ms
camunda    mixed-1000-first        17.6 MB    2217 ms
drools     mixed-1000-first        17.5 MB    2767 ms
praecepta  mixed-1000-priority         1.7 MB   79759 ms
camunda    mixed-1000-priority        18.7 MB    2255 ms
drools     mixed-1000-priority        18.3 MB    3066 ms
praecepta  mixed-1000-ruleorder         1.0 MB     794 ms
camunda    mixed-1000-ruleorder        17.6 MB    2204 ms
drools     mixed-1000-ruleorder        17.5 MB    2968 ms
praecepta  unique-1000             0.5 MB     562 ms
camunda    unique-1000            17.5 MB    1851 ms
drools     unique-1000            15.6 MB    1887 ms
```
