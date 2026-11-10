# Factory noise (SNR 5 dB) — per-noise breakdown

Source: `bench-results\noise-5db-800\bench.csv`, baseline cohort, 800 rows, 0 lost of 800.

| speech | noise | n | lost | misroute | misroute % | TTFA p50 | TTFA p95 | ASR p50 | MT p50 | TTS p50 | route reasons |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| English (EV) | sewing | 100 | 0 | 13 | 13.0 | 3030 | 5576 | 263 | 214 | 2559 | margin 89, prior 11 |
| English (EV) | loom | 100 | 0 | 21 | 21.0 | 2862 | 5381 | 261 | 206 | 2414 | margin 87, prior 13 |
| English (EV) | workshop | 100 | 0 | 16 | 16.0 | 3068 | 5757 | 258 | 226 | 2578 | margin 89, prior 11 |
| English (EV) | cutting_steam | 100 | 0 | 14 | 14.0 | 3137 | 6317 | 264 | 226 | 2670 | margin 88, prior 12 |
| English (EV) | ALL | 400 | 0 | 64 | 16.0 | 3028 | 5881 | 260 | 217 | 2567 | margin 353, prior 47 |
| Vietnamese (VE) | sewing | 100 | 0 | 2 | 2.0 | 3021 | 4801 | 218 | 121 | 2684 | margin 99, prior 1 |
| Vietnamese (VE) | loom | 100 | 0 | 0 | 0.0 | 2998 | 4660 | 218 | 124 | 2660 | margin 100 |
| Vietnamese (VE) | workshop | 100 | 0 | 0 | 0.0 | 2984 | 4356 | 219 | 124 | 2648 | margin 99, prior 1 |
| Vietnamese (VE) | cutting_steam | 100 | 0 | 0 | 0.0 | 2980 | 4820 | 218 | 122 | 2644 | margin 100 |
| Vietnamese (VE) | ALL | 400 | 0 | 2 | 0.5 | 2998 | 4800 | 218 | 122 | 2660 | margin 398, prior 2 |
| all | sewing | 200 | 0 | 15 | 7.5 | 3030 | 5199 | 231 | 144 | 2628 | margin 188, prior 12 |
| all | loom | 200 | 0 | 21 | 10.5 | 2904 | 5246 | 230 | 143 | 2522 | margin 187, prior 13 |
| all | workshop | 200 | 0 | 16 | 8.0 | 3020 | 5660 | 231 | 143 | 2613 | margin 188, prior 12 |
| all | cutting_steam | 200 | 0 | 14 | 7.0 | 3062 | 5871 | 232 | 149 | 2664 | margin 188, prior 12 |
| all | ALL | 800 | 0 | 66 | 8.2 | 3014 | 5381 | 231 | 146 | 2602 | margin 751, prior 49 |

Clips longer than 10 s (ASR sees only the first 10 s): 0 rows, 0 misrouted.

## Lost clips (no bench row: rejected by the router or timed out)


## Misrouted clips

- `en_EV-0182__loom.f32`
- `en_EV-0228__cutting_steam.f32`
- `en_EV-0250__cutting_steam.f32`
- `en_EV-0250__loom.f32`
- `en_EV-0250__workshop.f32`
- `en_EV-0333__cutting_steam.f32`
- `en_EV-0333__loom.f32`
- `en_EV-0333__sewing.f32`
- `en_EV-0333__workshop.f32`
- `en_EV-0523__loom.f32`
- `en_EV-0523__sewing.f32`
- `en_EV-0523__workshop.f32`
- `en_EV-1212__sewing.f32`
- `en_EV-1212__workshop.f32`
- `en_EV-1508__loom.f32`
- `en_EV-1829__loom.f32`
- `en_EV-1829__sewing.f32`
- `en_EV-1829__workshop.f32`
- `en_EV-1863__loom.f32`
- `en_EV-2364__cutting_steam.f32`
- `en_EV-2401__loom.f32`
- `en_EV-2401__sewing.f32`
- `en_EV-2748__cutting_steam.f32`
- `en_EV-2748__loom.f32`
- `en_EV-2748__sewing.f32`
- `en_EV-2748__workshop.f32`
- `en_EV-3791__loom.f32`
- `en_EV-4177__cutting_steam.f32`
- `en_EV-4177__loom.f32`
- `en_EV-4177__workshop.f32`
- `en_EV-4250__loom.f32`
- `en_EV-4294__workshop.f32`
- `en_EV-4321__cutting_steam.f32`
- `en_EV-5269__loom.f32`
- `en_EV-5301__cutting_steam.f32`
- `en_EV-5600__loom.f32`
- `en_EV-5600__sewing.f32`
- `en_EV-5935__loom.f32`
- `en_EV-5935__sewing.f32`
- `en_EV-6054__loom.f32`
- `en_EV-6218__loom.f32`
- `en_EV-6239__cutting_steam.f32`
- `en_EV-6239__loom.f32`
- `en_EV-6239__workshop.f32`
- `en_EV-6502__sewing.f32`
- `en_EV-6602__sewing.f32`
- `en_EV-6618__cutting_steam.f32`
- `en_EV-6618__workshop.f32`
- `en_EV-6960__cutting_steam.f32`
- `en_EV-6960__loom.f32`
- `en_EV-6960__sewing.f32`
- `en_EV-6960__workshop.f32`
- `en_EV-7227__sewing.f32`
- `en_EV-7690__loom.f32`
- `en_EV-7690__workshop.f32`
- `en_EV-8596__cutting_steam.f32`
- `en_EV-8596__workshop.f32`
- `en_EV-8694__workshop.f32`
- `en_EV-9018__workshop.f32`
- `en_EV-9341__cutting_steam.f32`
- `en_EV-9341__loom.f32`
- `en_EV-9341__sewing.f32`
- `en_EV-9341__workshop.f32`
- `en_EV-9953__cutting_steam.f32`
- `vi_VE-5468__sewing.f32`
- `vi_VE-8860__sewing.f32`
