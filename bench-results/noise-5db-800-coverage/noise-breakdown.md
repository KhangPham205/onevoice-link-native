# Factory noise (SNR 5 dB) — per-noise breakdown

Source: `bench-results\noise-5db-800-coverage\bench.csv`, baseline cohort, 793 rows, 7 lost of 800.

| speech | noise | n | lost | misroute | misroute % | TTFA p50 | TTFA p95 | ASR p50 | MT p50 | TTS p50 | route reasons |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| English (EV) | sewing | 99 | 1 | 1 | 1.0 | 2999 | 5566 | 256 | 221 | 2543 | coverage 70, coverage_tie 2, margin 27 |
| English (EV) | loom | 97 | 3 | 4 | 4.1 | 3079 | 5657 | 260 | 232 | 2580 | coverage 66, coverage_tie 2, margin 28, prior 1 |
| English (EV) | workshop | 99 | 1 | 4 | 4.0 | 3137 | 6011 | 259 | 230 | 2668 | coverage 64, coverage_tie 2, margin 30, prior 3 |
| English (EV) | cutting_steam | 98 | 2 | 0 | 0.0 | 3168 | 6163 | 260 | 232 | 2684 | coverage 68, coverage_tie 2, margin 28 |
| English (EV) | ALL | 393 | 7 | 9 | 2.3 | 3094 | 5966 | 258 | 230 | 2607 | coverage 268, coverage_tie 8, margin 113, prior 4 |
| Vietnamese (VE) | sewing | 100 | 0 | 0 | 0.0 | 2980 | 4411 | 216 | 122 | 2636 | coverage 30, coverage_tie 1, margin 69 |
| Vietnamese (VE) | loom | 100 | 0 | 0 | 0.0 | 2920 | 4483 | 216 | 122 | 2583 | coverage 36, margin 64 |
| Vietnamese (VE) | workshop | 100 | 0 | 0 | 0.0 | 2814 | 4406 | 216 | 121 | 2476 | coverage 23, margin 77 |
| Vietnamese (VE) | cutting_steam | 100 | 0 | 0 | 0.0 | 2853 | 4907 | 216 | 120 | 2538 | coverage 33, margin 67 |
| Vietnamese (VE) | ALL | 400 | 0 | 0 | 0.0 | 2888 | 4641 | 216 | 121 | 2562 | coverage 122, coverage_tie 1, margin 277 |
| all | sewing | 199 | 1 | 1 | 0.5 | 2987 | 5316 | 226 | 152 | 2603 | coverage 100, coverage_tie 3, margin 96 |
| all | loom | 197 | 3 | 4 | 2.0 | 3014 | 5294 | 228 | 154 | 2582 | coverage 102, coverage_tie 2, margin 92, prior 1 |
| all | workshop | 199 | 1 | 4 | 2.0 | 2898 | 5798 | 231 | 155 | 2512 | coverage 87, coverage_tie 2, margin 107, prior 3 |
| all | cutting_steam | 198 | 2 | 0 | 0.0 | 2977 | 5610 | 230 | 154 | 2580 | coverage 101, coverage_tie 2, margin 95 |
| all | ALL | 793 | 7 | 9 | 1.1 | 2967 | 5376 | 229 | 154 | 2580 | coverage 390, coverage_tie 9, margin 390, prior 4 |

Clips longer than 10 s (ASR sees only the first 10 s): 0 rows, 0 misrouted.

## Lost clips (no bench row: rejected by the router or timed out)

- `en_EV-2748__cutting_steam.f32`
- `en_EV-2748__loom.f32`
- `en_EV-2748__sewing.f32`
- `en_EV-2748__workshop.f32`
- `en_EV-6960__cutting_steam.f32`
- `en_EV-6960__loom.f32`
- `en_EV-7690__loom.f32`

## Misrouted clips

- `en_EV-0250__loom.f32`
- `en_EV-0523__loom.f32`
- `en_EV-0523__workshop.f32`
- `en_EV-1829__sewing.f32`
- `en_EV-1829__workshop.f32`
- `en_EV-1863__loom.f32`
- `en_EV-2401__loom.f32`
- `en_EV-6618__workshop.f32`
- `en_EV-8596__workshop.f32`
