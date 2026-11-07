# Benchmark results

```
date: 2026-11-07T13:02:19Z
profile: full (-f 3 -wi 5 -w 2s -i 5 -r 3s)
commit: cb0ab86
cpu: Apple M2 Pro
cores: 12 (8 performance)
memory: 16 GiB
os: macOS 26.7.1
java: 27.0.1.0.2 (OpenJDK 64-Bit Server VM)
praecepta: 0.2.0-SNAPSHOT
camunda dmn-scala: 1.12.6
drools (kie-dmn-core): 10.2.0
jmh: 1.37
```

## Latency by table size

One decision on one thread, mean (± 99.9% confidence interval). `synthetic` tables have string columns, `mixed` ones string, number and date columns; both COLLECT. `real` is the 119-rule FIRST table.

Latency in µs:

| Table | praecepta | camunda | drools | Camunda / Praecepta | Drools / Praecepta |
|---|---:|---:|---:|---:|---:|
| `real` | 0.19 ± 0.01 | 19.3 ± 2.60 | 43.5 ± 7.40 | 104× | 233× |
| `synthetic-10` | 0.15 ± 0.01 | 4.20 ± 0.07 | 5.89 ± 0.29 | 27× | 38× |
| `synthetic-100` | 0.16 ± 0.00 | 19.1 ± 0.39 | 14.5 ± 0.17 | 119× | 91× |
| `synthetic-1000` | 0.25 ± 0.04 | 146 ± 15.4 | 126 ± 11.5 | 588× | 506× |
| `synthetic-10000` | 0.67 ± 0.01 | 1,333 ± 132 | 1,419 ± 467 | 1,989× | 2,117× |
| `mixed-10` | 0.31 ± 0.00 | 7.05 ± 0.32 | 7.95 ± 0.12 | 23× | 26× |
| `mixed-100` | 0.44 ± 0.01 | 33.2 ± 0.20 | 31.3 ± 0.45 | 76× | 72× |
| `mixed-1000` | 0.64 ± 0.00 | 263 ± 18.3 | 256 ± 9.61 | 413× | 402× |
| `mixed-10000` | 1.51 ± 0.01 | 3,118 ± 353 | 3,122 ± 244 | 2,069× | 2,071× |

Memory allocated per decision, in bytes:

| Table | praecepta | camunda | drools |
|---|---:|---:|---:|
| `real` | 32 | 64,230 | 58,933 |
| `synthetic-10` | 56 | 21,604 | 15,769 |
| `synthetic-100` | 64 | 83,722 | 25,261 |
| `synthetic-1000` | 178 | 651,575 | 172,906 |
| `synthetic-10000` | 1,334 | 6,203,941 | 2,014,481 |
| `mixed-10` | 56 | 27,506 | 18,171 |
| `mixed-100` | 67 | 120,545 | 48,537 |
| `mixed-1000` | 248 | 964,505 | 369,442 |
| `mixed-10000` | 1,752 | 9,635,820 | 3,826,488 |

## Latency by number of columns

1,000 rules; `x<n>` is the number of input columns.

Latency in µs:

| Table | praecepta | camunda | drools | Camunda / Praecepta | Drools / Praecepta |
|---|---:|---:|---:|---:|---:|
| `synthetic-1000x5` | 0.25 ± 0.00 | 99.7 ± 15.8 | 183 ± 1.34 | 403× | 741× |
| `synthetic-1000x20` | 0.39 ± 0.00 | 200 ± 3.33 | 143 ± 2.31 | 506× | 363× |
| `mixed-1000x6` | 0.62 ± 0.01 | 237 ± 16.4 | 248 ± 4.86 | 382× | 399× |
| `mixed-1000x18` | 1.12 ± 0.06 | 388 ± 45.4 | 263 ± 2.32 | 347× | 235× |

Memory allocated per decision, in bytes:

| Table | praecepta | camunda | drools |
|---|---:|---:|---:|
| `synthetic-1000x5` | 297 | 474,305 | 196,922 |
| `synthetic-1000x20` | 176 | 1,095,271 | 201,914 |
| `mixed-1000x6` | 466 | 885,900 | 370,845 |
| `mixed-1000x18` | 176 | 1,466,175 | 375,223 |

## Latency by hit policy

The same 1,000 mixed rules under FIRST, PRIORITY and RULE ORDER (COLLECT is in the size table), and 1,000 non-overlapping rules under UNIQUE.

Latency in µs:

| Table | praecepta | camunda | drools | Camunda / Praecepta | Drools / Praecepta |
|---|---:|---:|---:|---:|---:|
| `mixed-1000-first` | 0.53 ± 0.00 | 49.6 ± 2.83 | 248 ± 2.45 | 94× | 473× |
| `mixed-1000-priority` | 0.55 ± 0.02 | 287 ± 5.65 | 252 ± 3.27 | 524× | 460× |
| `mixed-1000-ruleorder` | 0.64 ± 0.00 | 285 ± 5.45 | 257 ± 24.4 | 446× | 402× |
| `unique-1000` | 0.23 ± 0.04 | 161 ± 18.7 | 128 ± 25.7 | 695× | 554× |

Memory allocated per decision, in bytes:

| Table | praecepta | camunda | drools |
|---|---:|---:|---:|
| `mixed-1000-first` | 144 | 187,644 | 367,303 |
| `mixed-1000-priority` | 144 | 1,027,676 | 373,204 |
| `mixed-1000-ruleorder` | 248 | 1,007,514 | 369,502 |
| `unique-1000` | 144 | 679,289 | 159,856 |

## Latency when no rule matches

Inputs with values the table does not cover.

Latency in µs:

| Table | praecepta | camunda | drools | Camunda / Praecepta | Drools / Praecepta |
|---|---:|---:|---:|---:|---:|
| `synthetic-1000` | 0.23 ± 0.03 | 141 ± 4.74 | 135 ± 1.47 | 616× | 588× |
| `mixed-100` | 0.45 ± 0.02 | 30.5 ± 0.63 | 29.5 ± 1.68 | 68× | 66× |
| `mixed-1000` | 0.32 ± 0.01 | 261 ± 2.92 | 197 ± 2.83 | 814× | 616× |
| `unique-1000` | 0.13 ± 0.00 | 124 ± 2.27 | 111 ± 0.98 | 930× | 829× |

Memory allocated per decision, in bytes:

| Table | praecepta | camunda | drools |
|---|---:|---:|---:|
| `synthetic-1000` | 144 | 621,307 | 184,534 |
| `mixed-100` | 32 | 114,767 | 43,683 |
| `mixed-1000` | 144 | 1,019,376 | 281,536 |
| `unique-1000` | 144 | 597,196 | 156,153 |

## Latency distribution

One decision on one thread, in µs.

| Table | Engine | p50 | p90 | p99 | p99.9 | max |
|---|---|---:|---:|---:|---:|---:|
| `real` | praecepta | 0.17 | 0.21 | 0.29 | 0.54 | 85.9 |
| `mixed-1000` | praecepta | 0.67 | 0.79 | 1.00 | 2.57 | 1,794 |
| `unique-1000` | praecepta | 0.21 | 0.25 | 0.33 | 1.00 | 154 |
| `real` | camunda | 17.7 | 26.4 | 32.4 | 41.1 | 1,593 |
| `mixed-1000` | camunda | 250 | 286 | 323 | 444 | 1,962 |
| `unique-1000` | camunda | 162 | 167 | 186 | 273 | 1,544 |
| `real` | drools | 51.8 | 63.6 | 69.8 | 85.5 | 4,669 |
| `mixed-1000` | drools | 260 | 289 | 309 | 386 | 4,628 |
| `unique-1000` | drools | 112 | 118 | 127 | 152 | 4,243 |

## Throughput with a shared engine

Decisions per millisecond, all threads together, one engine instance shared by every thread.

| Table | Engine | 1 thread | 4 threads | 8 threads |
|---|---|---:|---:|---:|
| `real` | praecepta | 5,374 | 21,158 | 33,796 |
| `mixed-1000` | praecepta | 1,557 | 5,990 | 11,721 |
| `real` | camunda | 61.0 | 231 | 451 |
| `mixed-1000` | camunda | 3.86 | 13.0 | 29.3 |
| `real` | drools | 20.9 | 78.6 | 146 |
| `mixed-1000` | drools | 3.73 | 15.0 | 22.7 |

## Building the engine from DMN

Read the DMN document and build a ready engine, in a warm JVM. Milliseconds.

| Table | praecepta | camunda | drools |
|---|---:|---:|---:|
| `real` | 6.46 ± 0.81 | 22.6 ± 4.24 | 36.1 ± 3.08 |
| `mixed-100` | 7.59 ± 0.70 | 24.1 ± 2.28 | 57.8 ± 2.45 |
| `mixed-1000` | 44.8 ± 1.16 | 56.9 ± 4.41 | 437 ± 8.44 |

## First decision in a fresh JVM

Read the DMN document, build the engine and evaluate one input, in a new JVM with nothing loaded. Milliseconds, mean over the forks.

| Table | praecepta | camunda | drools |
|---|---:|---:|---:|
| `real` | 75.8 ± 0.66 | 469 ± 11.2 | 379 ± 7.66 |
| `mixed-100` | 77.7 ± 3.22 | 489 ± 4.95 | 437 ± 7.13 |
| `mixed-1000` | 240 ± 24.4 | 774 ± 29.2 | 1,005 ± 14.6 |

## Retained heap and build time

Heap retained by one built engine, and the time to build it once in a fresh JVM.

```
engine     table                 retained heap      build
praecepta  real                   -0.0 MB      74 ms
camunda    real                    4.5 MB     458 ms
drools     real                    6.3 MB     367 ms
praecepta  synthetic-10            0.5 MB      77 ms
camunda    synthetic-10            3.6 MB     462 ms
drools     synthetic-10            6.3 MB     363 ms
praecepta  synthetic-100           0.5 MB     112 ms
camunda    synthetic-100           4.9 MB     535 ms
drools     synthetic-100           7.1 MB     449 ms
praecepta  synthetic-1000          0.6 MB     237 ms
camunda    synthetic-1000         16.0 MB     767 ms
drools     synthetic-1000         14.0 MB     689 ms
praecepta  synthetic-10000         1.5 MB     568 ms
camunda    synthetic-10000       132.0 MB    1943 ms
drools     synthetic-10000        89.2 MB    1825 ms
praecepta  mixed-10                0.6 MB      88 ms
camunda    mixed-10                3.6 MB     486 ms
drools     mixed-10                6.6 MB     433 ms
praecepta  mixed-100               0.6 MB     147 ms
camunda    mixed-100               4.8 MB     573 ms
drools     mixed-100               7.4 MB     545 ms
praecepta  mixed-1000              1.1 MB     301 ms
camunda    mixed-1000             16.3 MB     871 ms
drools     mixed-1000             15.9 MB    1168 ms
praecepta  mixed-10000             5.0 MB    1136 ms
camunda    mixed-10000           133.0 MB    2078 ms
drools     mixed-10000           108.0 MB    5760 ms
praecepta  synthetic-1000x5         0.6 MB     173 ms
camunda    synthetic-1000x5        10.8 MB     669 ms
drools     synthetic-1000x5        10.9 MB     595 ms
praecepta  synthetic-1000x20         0.6 MB     269 ms
camunda    synthetic-1000x20        26.8 MB     839 ms
drools     synthetic-1000x20        19.9 MB     890 ms
praecepta  mixed-1000x6            1.0 MB     297 ms
camunda    mixed-1000x6           12.7 MB     823 ms
drools     mixed-1000x6           13.2 MB     933 ms
praecepta  mixed-1000x18           1.6 MB     397 ms
camunda    mixed-1000x18          27.0 MB    1003 ms
drools     mixed-1000x18          23.9 MB    1749 ms
praecepta  mixed-1000-first         1.7 MB    3641 ms
camunda    mixed-1000-first        16.3 MB     849 ms
drools     mixed-1000-first        16.0 MB    1154 ms
praecepta  mixed-1000-priority         1.7 MB   68694 ms
camunda    mixed-1000-priority        17.3 MB     820 ms
drools     mixed-1000-priority        16.5 MB    1116 ms
praecepta  mixed-1000-ruleorder         1.1 MB     270 ms
camunda    mixed-1000-ruleorder        16.4 MB     829 ms
drools     mixed-1000-ruleorder        16.0 MB    1070 ms
praecepta  unique-1000             0.6 MB     192 ms
camunda    unique-1000            16.1 MB     708 ms
drools     unique-1000            14.0 MB     651 ms
```
