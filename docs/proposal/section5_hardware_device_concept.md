# Section 5 — Hardware & Device Concept

> **Scoring weight: 25%**
> Paste-ready content for `1V_P2_TechProposal_Template.docx` §5.
> **§5.1 and §5.2 map 1:1 to the template tables.** §5.3–§5.7 are supplementary
> depth (use as appendix, speaker notes, or to expand the section if space allows).
>
> **Evidence legend:** ✅ vendor-published · 📐 engineering estimate (to be validated
> on-device in Phase 3) · 🔬 measured by our team.
>
> **Anchor decisions this section encodes** (from `onevoice_technical_spec_outline.md`):
> commercial Android flagship on Snapdragon 8 Elite — *not* a custom board — because
> it minimises BOM and time-to-market and keeps the entire budget on the AI pipeline;
> target niche is Korean/Taiwanese FDI factories in Vietnam (VI↔KO, VI↔ZH, VI↔EN).

---

## 5.1 Platform Selection & Justification

### Platform comparison

*Compare at least one SoC / compute platform option and justify your choice.*

| Criteria | **Option A — Snapdragon 8 Elite (SM8750) Android flagship**<br>*COTS phone + rugged industrial shell* | **Option B — Qualcomm QCS6490 (RB3 Gen 2) custom device**<br>*purpose-built embedded hardware* | Selected? |
|---|---|---|---|
| **NPU Performance (TOPS)** | Hexagon NPU, **+45% AI performance and +45% perf/W vs Snapdragon 8 Gen 3** ✅ (Qualcomm publishes % gains, not TOPS, for mobile Hexagon; independent estimates place it in the **60–80 TOPS INT8** class 📐). Native **INT4 / INT8 / INT16 / FP16** ✅ | **12 TOPS** ✅ (6th-gen AI Engine: Hexagon DSP + dual HVX + Tensor accelerator) | **A ✓** / B — |
| **Power Consumption (TDP)** | **~5–7 W** sustained SoC in a phone thermal envelope 📐; whole-device duty-cycled average **~1.9 W** (see §5.2) | **~5–7 W SoC**, but **board level materially higher** — 12 V DC-powered carrier with discrete PMIC, no phone-grade power management 📐 | **A ✓** / B — |
| **RAM / Storage Support** | **12–16 GB LPDDR5X @ 5300 MHz (84.8 GB/s, quad-channel 16-bit; up to 24 GB)** ✅ + **256–512 GB UFS 4.0** ✅ | **6 GB LPDDR5 + 128 GB UFS** ✅ (fixed on the dev kit) | **A ✓** / B — |
| **AI SDK / Toolchain** | **QNN / Qualcomm AI Engine Direct**, AIMET quantisation, **Qualcomm AI Hub** — and the AI Hub **device farm already exposes Galaxy S25 (8 Elite)** for profiling on our exact target silicon ✅ | Same QNN/AI Hub family ✅, but a **smaller Android/Linux BSP surface** and no phone-grade audio HAL (AEC/NS) out of the box | **A ✓** / B — |
| **Form Factor Suitability** | **Handheld / lanyard-worn**, IP68 + MIL-STD-810H rugged shell; integrated mic array, loudspeaker, battery, display, PMIC, BLE — **zero custom electronics** | **Portable enclosure** — requires custom industrial design, audio codec, battery/charging, certification (IP, EMC, RF) before it can go on a factory floor | **A ✓** / B — |

### Justification & device form factor *(paste into the template's prompt box)*

We select the **Snapdragon 8 Elite (SM8750) Android flagship** as the compute platform: its Hexagon NPU natively executes our INT8/INT16 cascade (ASR → MT → TTS) entirely on-device with **45 % better performance-per-watt than the previous generation**, it ships with **84.8 GB/s LPDDR5X bandwidth** — the real constraint when three models are resident simultaneously — and it is **directly profilable on Qualcomm AI Hub's device farm (Galaxy S25 / 8 Elite)**, so every latency figure we report is measured on the exact target silicon rather than extrapolated. Choosing commercial phone hardware over a custom board (Option B) removes electrical design, enclosure tooling, and IP/EMC certification from the critical path, letting the whole team budget go into the AI pipeline — which carries 50 % of the scoring weight — while still delivering a device a factory worker can actually wear. The delivered device is a **lanyard-worn / handheld "interpreter badge"**: a Snapdragon 8 Elite handset in a rugged industrial shell, **≈ 172 × 86 × 15 mm, ≈ 250 g**, **IP68** ingress and **MIL-STD-810H** drop-rated, with a **glove-operable physical push-to-talk key**, dual noise-cancelling MEMS microphones (hardware AEC + beamforming), a 1 W front-firing loudspeaker, and an optional **wired USB-C bone-conduction headset** for ≥ 90 dB(A) environments.

### Extended rationale — why a commercial phone beats a custom board *(supplementary)*

| Dimension | Impact of choosing the COTS-phone path |
|---|---|
| **Schedule risk** | Custom hardware needs schematic → PCB spin → enclosure tooling → EMC/RF/IP certification. Against a **Aug–Sep 2026 prototype and Nov 2026 final demo**, a single failed board spin would end the project. The phone path has **zero hardware critical path**. |
| **Engineering focus** | Technical Performance is **50 %** of the score and lives entirely in the AI pipeline. Every week spent on power supplies or audio codecs is a week not spent on WER/BLEU/latency. |
| **Audio quality — the decisive factor** | A flagship phone ships a **tuned voice pipeline** (Qualcomm Fluence/Aqstic: multi-mic beamforming, hardware AEC, noise suppression) that has been validated across millions of units. Reproducing that on a custom board is a multi-month DSP project in itself — and audio SNR, not TOPS, is what determines ASR accuracy at 95 dB. |
| **Business viability (25 %)** | A software product deployable on **any** Snapdragon 8-series handset the customer already owns has a near-zero hardware BOM and instant scalability; a proprietary device requires manufacturing, inventory, RMA, and per-market certification. Enterprise buyers can also pilot with **10 phones tomorrow**, not a 12-week hardware lead time. |
| **Upgrade path** | Each Snapdragon generation (8 Elite → 8 Elite Gen 5, which claims up to **100 TOPS** with native INT2 ✅) delivers a free performance uplift to the same codebase — "develop once, deploy everywhere". |
| **Honest trade-off accepted** | We give up (a) a bespoke industrial form factor, (b) hardware-level control of the mic array geometry, and (c) per-unit margin on hardware. We judge all three to be worth trading for schedule certainty and audio quality. Section 5.5 describes the path to a dedicated device once the software is proven. |

### Other platforms evaluated and rejected *(supplementary)*

| Platform | Headline spec | Why rejected |
|---|---|---|
| **NVIDIA Jetson Orin Nano 8 GB** | 40 TOPS INT8 (67 TOPS "Super"), 8 GB LPDDR5 @ 68 GB/s, **7–15 W module alone** ✅ | 2–3× the power for the *module only*, before carrier board, audio and display; **CUDA/TensorRT stack** would force a full rewrite of our QNN-based native core; not body-wearable. |
| **Snapdragon X Elite** | Laptop-class NPU (45 TOPS) ✅ | Designed for laptop/tablet thermal envelopes and form factors — cannot be lanyard-worn by a worker on a line. Explicitly rejected in our technical spec. |
| **Snapdragon 8 Gen 2 (SM8550)** | Our **current development device** (Galaxy S23) 🔬 | Fully capable for development and the emulator/handset bring-up we have already completed, but one NPU generation behind; retained as the **dev/fallback** device, not the production target. |
| **Raspberry Pi 5 / generic ARM SBC** | No NPU (CPU inference only) | Cannot meet the sub-1.5 s user-perceived latency target for a 3-stage cascade; no integrated voice DSP; not portable without significant work. |

### Development vs. production silicon *(supplementary — shows the plan is real)*

| Phase | Device | Silicon | Purpose | Status |
|---|---|---|---|---|
| Development | Android Studio emulator (x86_64) | — | Full cascade functional testing without hardware | 🔬 **Working today** — bidirectional VI⇄EN pipeline running |
| Bring-up | Samsung Galaxy S23 | Snapdragon 8 Gen 2 | On-device QNN integration, real audio path, thermal sanity | Native core built and packaged for `arm64-v8a` 🔬 |
| Benchmark | Galaxy S25 via **Qualcomm AI Hub device farm** | **Snapdragon 8 Elite** | Per-layer latency, peak memory, NPU-residency (`npu == total` layers) on the exact target chip — **without purchasing the handset** | Planned Phase 2 (per `QUALCOMM_AI_HUB_BRIEF.md` §3.5) |
| Final demo | Snapdragon 8 Elite handset + rugged shell | **Snapdragon 8 Elite** | Field trial (Oct 2026) and live demo (Nov 2026, HCMC) | Planned |

---

## 5.2 Key Hardware Components & Power Budget

### Component table

| Component | Specification | Peak Power | Notes |
|---|---|---|---|
| **SoC Module** | Snapdragon 8 Elite (SM8750), TSMC 3 nm; Oryon CPU (2 × 4.32 GHz + 6 × 3.53 GHz); Hexagon NPU INT4/INT8/INT16/FP16 | **6.5 W** 📐 (burst, all three models active)<br>~4.5 W sustained during translation | NPU executes ASR + MT + TTS; CPU stays near-idle — the realtime audio path is native C++ (Oboe), never crossing into the JVM |
| **Microphone Array** | 2–3 × MEMS, 16 kHz mono capture path; Qualcomm Fluence beamforming + **hardware AEC/NS** via `VOICE_COMMUNICATION` audio route | **0.10 W** 📐 | Hardware AEC is what makes half-duplex conversation possible — TTS output is cancelled from the capture path 🔬 |
| **Speaker / Audio Out** | 1 W front-firing loudspeaker; USB-C wired headset (preferred); BLE Audio (LE Audio) optional | **0.70 W** 📐 (TTS playback only, ≈ 1/3 duty of active phase) | Wired is default: LE Audio adds ~20–30 ms, legacy A2DP 100–200 ms — up to 13 % of our 1.5 s UPL budget |
| **Battery** | 5,000 mAh Li-Po ≈ **19.4 Wh** @ 3.88 V | — | **> 8 h target met: ≈ 10.2 h** duty-cycled field use (derivation below); USB-C PD top-up + optional 10,000 mAh belt pack for double shifts |
| **Storage** | 256 GB UFS 4.0 | **0.25 W** 📐 (burst at model load) | All quantised models for 3 language pairs ≈ **2.5 GB** 📐; loaded once at start-up, then resident in RAM |
| **Display (optional)** | 6.x″ AMOLED 120 Hz — **off by default** in hands-free mode | **0.90 W** 📐 @ 50 % brightness, **0 W** screen-off | Dual-pane transcript (both speakers read simultaneously) — a safety feature, not a requirement for operation |
| **Radios** | 5G modem + Wi-Fi **disabled at runtime** (airplane mode) | **0 W** | Offline-by-design: with radios off there is no code path to a cloud service — this also removes any disqualification risk |
| **Total System Budget** | | **≈ 7.9 W peak burst**<br>**≈ 1.9 W duty-cycled average** 📐 | Sustains **≈ 10.2 h** continuous field use on 5,000 mAh — exceeds the > 8 h shift requirement |

### Power budget derivation *(supplementary — shows the number is not a guess)*

Three operating points, then a duty-cycle model of a real factory shift:

| Operating point | Composition | Draw |
|---|---|---|
| **P1 — Active translation** (speech in → NPU cascade → speech out) | SoC 4.5 W + mic 0.1 W + speaker 0.7 W (partial duty) | **≈ 5.0 W** |
| **P2 — Armed idle** (VAD listening, screen off, models resident in RAM) | Low-power DSP VAD + SoC in low state | **≈ 0.6 W** |
| **P3 — Display on** (transcript being read) | P1/P2 + AMOLED @ 50 % | **+ 0.9 W** |

A conversation-heavy shift is roughly **30 % active / 70 % armed idle** (workers talk in bursts, then work):

```
Average = 0.30 × 5.0 W  +  0.70 × 0.6 W  =  1.50 + 0.42  =  1.92 W
Runtime = 19.4 Wh ÷ 1.92 W  ≈  10.1 h        → exceeds the >8 h shift target ✅
```

**Worst case, stated honestly:** 100 % continuous translation with the display on draws ≈ 5.9 W → **≈ 3.3 h**. This is the number to plan around for a non-stop interpreting session (e.g. a safety briefing). Mitigations: screen-off hands-free mode (the default), USB-C PD charging at a workstation, or a belt-worn power pack. We will publish **measured** figures from Phase 3 on-device profiling in place of these estimates.

### Thermal & sustained performance *(supplementary)*

Sustained NPU load in a sealed rugged case is the main hardware risk to *consistent* latency, and it deserves an explicit answer:

- **Headroom by design.** The cascade runs on the **NPU**, not the CPU/GPU. Qualcomm's Hexagon is roughly **100× more efficient than CPU** and **~10× more than GPU** for these workloads ✅, so the sustained thermal load is far below the SoC's gaming/camera worst case that phone thermal solutions are engineered for.
- **Duty cycle is our friend.** Conversation is bursty: ~1–3 s of inference per utterance followed by seconds of silence. Average NPU utilisation over a shift is low even though peak is high.
- **Rugged case mitigation.** A sealed case reduces convective cooling, so the industrial shell will use a **thermal pad bridging the SoC region to a metal backplate** acting as a heat spreader; the lanyard/chest mount keeps the back surface exposed to air rather than pressed against the body.
- **Graceful degradation, never failure.** If the platform does thermally throttle, our architecture degrades predictably rather than breaking: the **lock-free SPSC ring buffer** (32 768 samples ≈ 2.05 s) absorbs a slow inference pass as a momentary gap in the output rather than a torn audio stream 🔬, and the PARTIAL (on-screen text) branch continues to update even if the FINAL (spoken) branch lags.
- **Validation plan.** A 60-minute sustained-load soak test on the target handset in the rugged case, logging skin temperature, NPU clock, and per-utterance latency drift — scheduled for Phase 3 alongside field testing.

---

## 5.3 Device Form Factor & Industrial Design *(supplementary)*

**Concept: the "interpreter badge".** A Snapdragon 8 Elite handset in a purpose-designed industrial shell, worn on a lanyard or chest harness so it sits mic-forward at sternum height — the natural position for face-to-face conversation on a factory floor, and it keeps **both hands free**.

| Attribute | Specification | Rationale |
|---|---|---|
| Type | Lanyard-worn / handheld hybrid; detachable for handheld use | Workers move between hands-busy and hands-free tasks |
| Dimensions | ≈ **172 × 86 × 15 mm** (handset + shell) | Fits a standard chest harness and a cargo pocket |
| Weight | ≈ **250 g** (≈ 190 g handset + ≈ 60 g shell) | Below the ~300 g comfort threshold for all-shift chest carry |
| Ingress / durability | **IP68** (handset-native) + **MIL-STD-810H** drop protection from the shell | Dust, coolant mist, wash-down areas, 1.5 m concrete drops |
| Primary control | **Oversized physical push-to-talk key**, glove-operable (≥ 15 mm travel target, tactile detent) — implemented on a COTS phone by **remapping the volume-down hardware key**, with an accessory PTT button as the production path | The single most important UX decision: a worker in gloves cannot reliably use a touchscreen. A physical key needs **zero training**. |
| Secondary control | Wake-word activation for fully hands-free operation | Hands occupied *and* out of reach of the badge |
| Feedback | 1 W loudspeaker; haptic confirmation on PTT press/release; large high-contrast status LED/UI (listening / translating / speaking) | Haptics and light work where 95 dB noise makes audio cues useless |
| Display | 6.x″ AMOLED, dual-pane transcript (VI one way, EN/KO/ZH the other) | Both speakers read the same exchange — critical for safety-relevant instructions where mishearing is dangerous |
| High-noise option | Wired USB-C **bone-conduction headset** with boom mic | See §5.4 — this is the answer for ≥ 90 dB(A) environments |

---

## 5.4 Audio Subsystem — the real hardware differentiator *(supplementary)*

**The insight:** in a 95 dB(A) factory, translation accuracy is limited by **input SNR, not by NPU throughput.** No amount of model quality recovers speech that was never captured cleanly. This makes the audio front-end the most consequential hardware decision in the entire section.

| Capture option | Effective SNR gain | Latency added | Recommended use |
|---|---|---|---|
| Phone far-field mic array (beamforming + NS) | Baseline | 0 ms | Offices, warehouses, < 75 dB(A) |
| Close-talk **noise-cancelling boom mic** (wired USB-C) | **≈ +20–25 dB** 📐 | ~0 ms (wired) | Production floors, 75–90 dB(A) |
| **Bone-conduction** headset (wired USB-C) | Structurally immune to airborne noise | ~0 ms (wired) | Stamping/press shops, ≥ 90 dB(A) — **our recommendation for the target niche** |
| Any of the above over Bluetooth LE Audio | Same as wired | **+20–30 ms** (LE Audio) / **+100–200 ms** (legacy A2DP) | Only when cabling is a safety hazard |

**Design decisions that follow:**

1. **Wired-first.** The USB-C headset is the default: it adds no latency, no pairing step, and no battery to charge. Bluetooth is supported but documented as a latency trade-off — at a 1.5 s UPL budget, legacy A2DP alone would consume up to 13 % of it.
2. **Hardware AEC is load-bearing.** Because the device both listens and speaks, the platform's hardware acoustic echo canceller (engaged via the `VOICE_COMMUNICATION` audio route) prevents synthesized speech from being re-captured as input. We additionally implement a **software half-duplex gate** that closes the microphone for the duration of TTS playback 🔬 — belt and braces, already working in our current build.
3. **16 kHz mono throughout.** Matches the ASR model's native input rate exactly, avoiding a resampling stage and its latency on the hot path 🔬.
4. **Low-latency capture path.** Audio I/O uses **Google Oboe in `LowLatency` + `Exclusive` mode** (AAudio MMAP fast path), with a 256-frame (≈ 16 ms) callback, so the audio subsystem contributes ~16 ms rather than the 100 ms+ typical of a JVM-mediated path 🔬.

---

## 5.5 BOM & Commercial Scalability *(supplementary — supports the 25 % Business criterion)*

| Item | Prototype (2026) | Volume production path |
|---|---|---|
| Compute | COTS Snapdragon 8 Elite handset (~$800–1,000 retail) | Enterprise procurement, or **software-only licence on customer-owned handsets** (≈ $0 hardware BOM) |
| Rugged shell | 3D-printed / off-the-shelf rugged case (~$30–60) | Injection-moulded shell at scale (~$8–15/unit) |
| Headset | COTS bone-conduction USB-C headset (~$40–80) | OEM-sourced (~$15–25/unit) |
| Software | Our on-device stack | Per-seat or per-site licence — **the actual product** |

**Three deployment tiers the same binary supports:**

1. **BYOD / software-only** — the customer installs the app on Snapdragon handsets they already own. Zero hardware cost, instant pilot, fastest path to revenue.
2. **Kitted device** — handset + rugged shell + headset, sold as a managed "interpreter badge" for safety-critical roles.
3. **Dedicated hardware (post-validation)** — once the software is field-proven and volumes justify NRE, a **QCS6490-class purpose-built device** (Option B, revisited) becomes attractive: same QNN toolchain, same models, lower unit cost at scale. **Choosing Qualcomm silicon now makes this migration a port, not a rewrite** — the strategic reason Option B was rejected as a *starting point* rather than as a *destination*.

---

## 5.6 Hardware Risks & Mitigations *(supplementary)*

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| 1 | **Ambient noise degrades ASR** below usable WER at ≥ 90 dB(A) | High | High | Bone-conduction/boom mic as the standard accessory (§5.4); noise-augmented calibration data for quantisation; noise-matched test set in the evaluation plan |
| 2 | **Sustained thermal throttling** in a sealed case lengthens latency | Medium | Medium | NPU-resident (not CPU) execution; thermal pad + metal backplate; ring buffer absorbs stalls; 60-min soak test in Phase 3 |
| 3 | **RAM ceiling** — three models resident across two directions | Medium | High | 12–16 GB LPDDR5X target device; w8a16 quantisation; lazy loading of non-active language pairs; measured peak memory from AI Hub profiling gates the design |
| 4 | **Battery insufficient** for continuous interpreting | Low–Med | Medium | Screen-off hands-free default; duty-cycled 10.2 h; USB-C PD and belt pack for edge cases; honest 3.3 h worst-case figure published rather than hidden |
| 5 | **Target handset unavailable** for the team before the deadline | Medium | Medium | AI Hub device farm profiles Galaxy S25 (8 Elite) **without owning it**; Galaxy S23 (8 Gen 2) already in hand for physical bring-up; code is ABI-portable across both 🔬 |
| 6 | **Glove operation fails** on capacitive touch | High (if unaddressed) | High | Physical PTT key remapped from a hardware button + wake-word; no touchscreen interaction required for the core loop |
| 7 | **Accessory audio latency** (Bluetooth) eats the UPL budget | Medium | Medium | Wired USB-C default; BT documented and measured, LE Audio preferred over A2DP if wireless is mandatory |

---

## 5.7 How Section 5 connects to the rest of the proposal *(cross-reference aid)*

| This section provides | Consumed by |
|---|---|
| SoC choice + RAM ceiling (12–16 GB LPDDR5X, 84.8 GB/s) | §4.3 On-Device Optimisation — quantisation and memory strategy must fit this envelope |
| NPU precision support (INT4/8/16, FP16) | §4.2 Module design — justifies the w8a16 quantisation scheme |
| Audio front-end and hardware AEC | §4.4 Robustness — acoustic challenge handling |
| Offline-by-design (radios disabled) | §2.4 Constraints (zero internet dependency) and §6.3 Offline-first principles |
| QNN / AI Hub toolchain | §6.1 Software stack — AI Runtime layer |
| Power/thermal budget | §4.3 (sustained inference feasibility) and the field-test plan |
| BOM tiers and migration path | §3 Business Solution — commercialisation and scalability |

---

## Sources

**Qualcomm — platform and toolchain**
- [Snapdragon 8 Elite Platform Product Brief (SM8750)](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Snapdragon-8-Elite-Platform-Product-Brief.pdf) — CPU/NPU/memory specs, INT4/8/16 + FP16 support
- [Snapdragon 8 Elite Gen 5 Product Brief](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Snapdragon-8-Elite-Gen-5-product-brief.pdf) — next-generation uplift (native INT2, up to 100 TOPS)
- [Qualcomm Snapdragon 8 Elite — specs](https://nanoreview.net/en/soc/qualcomm-snapdragon-8-gen-4) · [Notebookcheck](https://www.notebookcheck.net/Qualcomm-Snapdragon-8-Elite-Processor-Benchmarks-and-Specs.908499.0.html) — LPDDR5X-5300, quad-channel 16-bit, 84.8 GB/s, ≤ 24 GB
- [Snapdragon 8 Elite official announcement](https://www.fonearena.com/blog/438507/qualcomm-snapdragon-8-elite-features.html) — +45 % NPU performance, +45 % perf/W vs 8 Gen 3
- [Qualcomm Dragonwing QCS6490/QCM6490 Product Brief](https://docs.qualcomm.com/doc/87-28733-1/87-28733-1_REV_F_QUALCOMM_QCS6490_QCM6490_Processors_Product_Brief.pdf) · [RB3 Gen 2 Dev Kit Product Brief](https://docs.qualcomm.com/doc/87-74789-1/87-74789-1_REV_A_Qualcomm_RB3_Gen_2_Development_Kit_Product_Brief.pdf) — 12 TOPS, 6 GB/128 GB, Kryo 670
- [Qualcomm AI Engine / Hexagon NPU](https://www.qualcomm.com/processors/ai-engine) · [Unlocking on-device generative AI with an NPU (whitepaper)](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Unlocking-on-device-generative-AI-with-an-NPU-and-heterogeneous-computing.pdf) — NPU ~100× CPU / ~10× GPU efficiency
- [Qualcomm AI Hub](https://aihub.qualcomm.com/get-started) — device farm (Galaxy S25 / 8 Elite), compile/profile/quantise workflow

**Comparison platforms**
- [NVIDIA Jetson Orin Nano](https://www.nvidia.com/en-us/autonomous-machines/embedded-systems/jetson-orin/) — 40 TOPS (67 "Super"), 7–15 W module power
- [Snapdragon X Elite Product Brief](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Product-Brief-Snapdragon-X-Elite.pdf) — 45 TOPS, laptop class

**Internal project documents**
- `docs/docs-main/literature/onevoice_ai_challenge_summary.md` — competition requirements, rubric weights, target environments
- `docs/docs-main/literature/onevoice_technical_spec_outline.md` — platform decision (8 Elite Android flagship), FDI-factory niche
- `docs/docs-main/literature/QUALCOMM_AI_HUB_BRIEF.md` — AI Hub workflow, device farm, quantisation/calibration
- `docs/docs-main/literature/onevoice_model_comparison.md` — per-direction model stack driving the RAM/compute budget
- `docs/ARCHITECTURE.md` — native audio path, SPSC ring buffer, threading model 🔬
- `docs/QNN_INTEGRATION.md` — QNN runtime integration and packaging 🔬
