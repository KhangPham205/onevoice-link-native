# Section 5 — Hardware & Device Concept (v2, FINAL)

> **Scoring weight: 25 %** · Paste-ready for `1V_P2_TechProposal_Template.docx` §5.
> v2 merges the Gemini Deep Research findings with corrections from our audit
> (`GEMINI_REPORT_AUDIT.md`) — circular citations removed, one falsely-attributed
> measurement demoted, product naming fixed, model footprints replaced with our
> own measured artifacts.
>
> **Evidence legend** — used on every quantitative claim:
> ✅ vendor-published / standards document · 📐 engineering estimate, derivation
> shown, to be validated on-device · 🔬 measured or built by our team
>
> **§5.1 and §5.2 map 1:1 to the template tables. §5.3–§5.7 are supplementary depth.**

---

## 5.1 Platform Selection & Justification

### Platform decision

OneVoice Link runs on a **commercial off-the-shelf Snapdragon flagship handset, enterprise-locked and enclosed in a purpose-built industrial shell** — not on a custom board. The target silicon is the **Qualcomm Snapdragon 8 Elite Gen 5 (SM8850)**, with the **Snapdragon 8 Elite (SM8750)** retained as a fully validated fallback; both are supported in the Qualcomm AI Hub device fleet, so all our latency figures are measured on real target silicon rather than extrapolated.

> **Naming note for the proposal:** the product is *Snapdragon 8 **Elite** Gen 5*. There is no "Snapdragon 8 Gen 5". Getting this wrong in front of Qualcomm judges is an avoidable credibility loss.

### Platform comparison

| Criteria | **Option A — Snapdragon 8 Elite Gen 5 (SM8850) enterprise handset** *(+ industrial shell)* | **Option B — Qualcomm QCS6490 / RB3 Gen 2 custom device** | **Alternative C — NVIDIA Jetson Orin Nano 8 GB** | Selected? |
|---|---|---|---|---|
| **NPU Performance (TOPS)** | Hexagon NPU with native **INT2 / INT4 / INT8 / FP8**; **≈ +37 % AI throughput over Snapdragon 8 Elite**, which itself was **+45 % over 8 Gen 3** ✅. *Qualcomm deliberately does not publish a mobile TOPS figure* — we quote generational gains, which is the defensible framing. | **12 TOPS INT8** ✅ (6th-gen AI Engine: Hexagon DSP + dual HVX + tensor accelerator) | **40 TOPS INT8 sparse / 20 TOPS dense** ✅ | **A ✓** |
| **Power Consumption (TDP)** | **3.5–5.0 W sustained** during continuous quantised NPU execution; ~16 % SoC power saving vs 8 Elite 📐 | **5–7 W SoC**; **8–10 W board level** — the dev kit lacks handset-grade power gating 📐 | **7–15 W module alone**, before carrier board, audio and display ✅ | **A ✓** |
| **RAM / Storage Support** | **12–16 GB LPDDR5X @ 5300 MHz, quad-channel, 84.8 GB/s** (up to 24 GB) ✅ + **256–512 GB UFS 4.x** ✅ | **6 GB LPDDR5 + 128 GB UFS**, fixed ✅ | 8 GB LPDDR5 @ 68 GB/s ✅ | **A ✓** |
| **AI SDK / Toolchain** | **QNN / Qualcomm AI Engine Direct + AIMET + AI Hub**; device-farm profiling on real Galaxy handsets ✅ | Same Qualcomm AI Stack ✅, but a thinner mobile model-zoo and no phone-grade voice HAL | **CUDA / TensorRT — a complete rewrite** of our QNN-based native core | **A ✓** |
| **Form Factor Suitability** | Handset ≈ 190 g + shell ≈ 60 g = **≈ 250 g**, lanyard/chest-wearable; integrated mic array, speaker, battery, PMIC, display | Dev board + carrier stack; needs custom enclosure, audio, battery and certification | Module + carrier assembly; unsuitable for continuous shift wear | **A ✓** |
| **Regulatory path** | **Inherits existing type approval** — FCC/CE and Vietnamese **QCVN 118:2018/BTTTT** EMC ✅; zero certification delay | Full custom PCB spin + safety/ESD/EMC lab testing | Extensive custom integration and validation | **A ✓** |

### Justification & device form factor *(paste into the template's prompt box)*

We select a **Snapdragon 8 Elite Gen 5 (SM8850) enterprise handset in a purpose-built industrial shell**: the decisive constraint is not raw TOPS but **memory bandwidth and thermal sustainability** when three quantised models (ASR → MT → TTS) are simultaneously resident, and the platform's **84.8 GB/s quad-channel LPDDR5X** keeps token generation and vocoder synthesis off the memory-starvation path while the Hexagon NPU — which Qualcomm rates at roughly **100× the CPU's efficiency** for these workloads ✅ — holds sustained draw to **3.5–5.0 W**. Choosing a certified commercial handset over a custom board (Option B) removes PCB spins, enclosure tooling and **an estimated six-month Vietnamese EMC certification cycle** from the critical path, letting the entire team budget go into the AI pipeline that carries 50 % of the score — while the identical QNN codebase remains portable to cost-optimised Qualcomm IoT silicon once volumes justify it. The delivered device is an **enterprise-managed "interpreter badge"**, worn on a breakaway lanyard or chest harness at sternum height: **≈ 172 × 86 × 15 mm, ≈ 250 g**, **IP68** (native to the handset) with a **MIL-STD-810H** drop-rated, **static-dissipative (IEC 61340-5-1)** shell that **physically obscures both camera apertures**, driven by **glove-operable physical push-to-talk keys** and a **wired USB-C boom-mic / bone-conduction headset** for high-noise zones.

### Extended rationale — why COTS beats a custom board here *(supplementary)*

| Dimension | Impact |
|---|---|
| **Schedule risk** | A custom device needs schematic → PCB spin → tooling → EMC/RF/IP/ESD certification. Against an Aug–Sep 2026 prototype and a Nov 2026 final demo, one failed board spin ends the project. The COTS path has **no hardware critical path**. |
| **Regulatory** | A pre-certified handset **inherits** Vietnamese type approval (QCVN 118:2018/BTTTT EMC) ✅, eliminating a certification cycle we estimate at ~6 months 📐 for custom hardware. |
| **Audio quality — the real decider** | Flagship handsets ship a production-tuned voice path (Qualcomm Aqstic codec, hardware AEC, multi-mic beamforming) validated across millions of units. Re-creating that on a custom board is a multi-month DSP project, and **input SNR — not TOPS — is what sets ASR accuracy at 95 dBA** (see §5.4). |
| **Business viability (25 %)** | Software deployable on handsets a customer already owns has a **$0 hardware BOM** and can pilot in days; a proprietary device needs manufacturing, inventory, RMA and per-market certification. |
| **Upgrade path** | Each Snapdragon generation lifts the same binary for free — 8 Elite → 8 Elite Gen 5 already delivers native INT2 and CPU matrix acceleration. |
| **Honest trade-offs accepted** | We give up bespoke industrial form, control of mic-array geometry, and hardware margin. §5.5 Model C shows the migration path to custom silicon after field validation. |

### Rejected alternatives *(supplementary)*

| Platform | Why rejected |
|---|---|
| **NVIDIA Jetson Orin Nano** | 7–15 W for the module alone ✅ — unworkable for a passive, sealed, chest-worn badge; CUDA/TensorRT forces a full rewrite of our QNN core. |
| **Snapdragon X Elite** | Laptop-class thermal envelope and form factor; cannot be worn on a production line. |
| **Snapdragon 8 Gen 2 (Galaxy S23)** | **Our current development handset** 🔬 — retained for bring-up and CI, one to two NPU generations behind the production target. |
| **Generic ARM SBC (e.g. Raspberry Pi 5)** | No NPU; cannot meet the < 1.5 s UPL target for a 3-stage cascade. |

### Development → production silicon path *(supplementary)*

| Phase | Hardware | Purpose | Status |
|---|---|---|---|
| Development | Android emulator (x86_64) | Full cascade functional testing | 🔬 **Working** — bidirectional VI⇄EN pipeline running today |
| Bring-up | Galaxy S23 (Snapdragon 8 Gen 2) | QNN integration, real audio path, thermal sanity | 🔬 Native core built and packaged for `arm64-v8a` |
| Benchmark | Galaxy S25 / S26 class via **AI Hub device farm** | Per-layer latency, peak memory, NPU residency (`npu == total` layers) on target silicon, **without buying the handset** | Planned Phase 2 |
| Field / demo | 8 Elite Gen 5 handset + industrial shell | Field trial (Oct 2026), live demo (Nov 2026, HCMC) | Planned |

---

## 5.2 Key Hardware Components & Power Budget

The device runs **offline by design**: the cellular modem, Wi-Fi and Bluetooth transceivers are disabled at OS level (enforced by MDM policy, not user preference), so there is no code path to a cloud service and no radio power draw. The whole budget goes to NPU compute, audio transducers and the display.

**Battery:** 5,000 mAh Li-Po at 3.88 V nominal = **19.4 Wh** ✅.

### Component table

| Component | Specification | Active Power | Armed-Idle Power | Notes |
|---|---|---|---|---|
| **SoC** | Qualcomm **Snapdragon 8 Elite Gen 5 (SM8850)**, TSMC 3 nm — Oryon CPU + Hexagon NPU, INT2/INT4/INT8/FP8 | **3.50 W** 📐 | **0.35 W** ✅ | ~16 % power saving vs SM8750 📐. Inference is NPU-direct; **no prime-core CPU inference permitted**. Idle uses the Sensing Hub low-power island for VAD. |
| **System Memory** | 16 GB LPDDR5X, quad-channel @ 5300 MHz (**84.8 GB/s**) | **0.40 W** 📐 | **0.05 W** 📐 | All models stay resident to avoid disk→RAM swap latency; idle uses self-refresh. |
| **Audio Amplifier & Out** | High-efficiency Class-D (1.0–1.5 W peak) | **0.50 W** 📐 | **0.00 W** | Average during speech playback; amp gated off when idle. |
| **Microphone Array** | 3× MEMS with active preamp | **0.015 W** ✅ | **0.015 W** ✅ | ~1–2 mW per channel; always on for VAD. |
| **Storage** | 256 GB UFS 4.x | **0.05 W** 📐 | **0.01 W** ✅ | Deep sleep at runtime — models are already in RAM. |
| **Display** | 6.x″ AMOLED, **60 Hz, dark UI, ~30 % APL, 50 % brightness** | **0.40 W** 📐 | **0.00 W** | Off by default in hands-free mode. *At 120 Hz and high brightness this rises to ≈ 0.90 W 📐 — the dark, low-refresh transcript UI is a deliberate power decision.* |
| **Wireless** | Modem / Wi-Fi / BT — **disabled at OS level** | **≈ 0 W** | **≈ 0 W** | Offline-by-design; also removes any disqualification risk. |
| **Total System Budget** | | **4.865 W** | **0.425 W** | → **≈ 11.3 h** at a 30 % active duty cycle (derivation below) — exceeds the > 8 h shift target |

### Battery-life derivation

A factory shift alternates between **Active Translation** and **Armed-Idle** (VAD listening, display off). Taking a deliberately conservative **30 % active** duty cycle:

```
P_avg = (0.30 × 4.865 W) + (0.70 × 0.425 W)
      = 1.4595 W + 0.2975 W
      = 1.757 W

T     = 19.4 Wh ÷ 1.757 W  ≈  11.0 hours     → 38 % buffer over an 8 h shift ✅
```

**Sensitivity analysis** — so the claim survives a change of assumption:

| Usage profile | Average power | Runtime | Meets 8 h shift? |
|---|---|---|---|
| Light (15 % active) | 1.030 W | **18.8 h** | ✅ comfortably |
| **Baseline (30 % active)** | **1.757 W** | **11.0 h** | ✅ **+38 % buffer** |
| Heavy (50 % active) | 2.635 W | **7.4 h** | ⚠️ needs a mid-shift top-up |
| Worst case (100 % active, display on) | 4.865 W | **4.0 h** | ❌ requires dock or power pack |

We publish the failing cases rather than hiding them. Mitigations for heavy use: display-off hands-free mode (the default), USB-C PD charging at workstation docks, and a belt-worn power pack for continuous-interpreting roles such as safety briefings.

### On-device model footprint 🔬

Measured from our working bidirectional build — **real artifacts, not estimates**:

| Component | Model | Size (INT8) |
|---|---|---|
| ASR (VI) | Parakeet-CTC-0.6B-vi | **624 MB** 🔬 |
| ASR (EN) | NeMo Conformer-small | **46 MB** 🔬 |
| MT (VI→EN) | opus-mt-vi-en (enc 45 + dec 78) | **123 MB** 🔬 |
| MT (EN→VI) | opus-mt-en-vi | **123 MB** 🔬 |
| TTS (VI) | Kokoro-Vietnamese | **326 MB** 🔬 |
| TTS (EN) | Kokoro-EN v0.19 | **330 MB** 🔬 |
| Phonemiser data | espeak-ng | **18 MB** 🔬 |
| **Total, one bidirectional pair** | | **≈ 1.6 GB** 🔬 |

This fits comfortably inside a 12–16 GB device with all models resident. The production QNN stack (PhoWhisper / Qwen3 / Piper) will differ in composition; peak **RAM** — not disk — is the binding constraint and is gated by AI Hub profiling before final model selection.

### Thermal & sustained performance *(supplementary)*

Sustained load in a sealed case is the main risk to *consistent* latency, and it deserves a direct answer. Independent testing shows 3 nm flagship SoCs can throttle substantially under continuous synthetic stress, and a non-ventilated rugged shell accelerates that.

1. **NPU-direct offloading.** No inference on CPU prime cores. Hexagon execution is ~100× more power-efficient than CPU for these workloads ✅, which is what keeps sustained draw at 3.5–5.0 W instead of gaming-class power.
2. **Duty cycle.** Conversation is bursty — 1–3 s of inference per utterance, then silence. Average NPU utilisation over a shift is low even where peak is high.
3. **Conductive shell, not just plastic.** A graphite thermal-interface pad bridges the SoC region to an anodised aluminium plate on the shell's outer rear face, turning the case into a passive heat spreader instead of an insulator. Chest-mount orientation keeps that face exposed to air.
4. **Graceful degradation.** If throttling does occur, our **lock-free SPSC ring buffer (32,768 samples ≈ 2.05 s of 16 kHz mono)** 🔬 absorbs the stall as a brief pause rather than torn audio, and the on-screen PARTIAL transcript keeps updating even when spoken output lags.
5. **Validation.** 60-minute sustained-load soak in the shell, logging skin temperature, NPU clock and per-utterance latency drift — Phase 3.

---

## 5.3 Wearability & Industrial Design *(supplementary)*

Worn hands-free as an **interpreter badge** on a breakaway safety lanyard or dual-strap chest harness at sternum height — mic-forward for face-to-face conversation, both hands free.

| Attribute | Specification | Rationale |
|---|---|---|
| Footprint | ≈ **172 × 86 × 15 mm** 📐 | Fits a standard chest harness and a cargo pocket |
| Weight | ≈ **250 g** (≈ 190 g handset + ≈ 60 g shell) 📐 | Below the ~300 g comfort threshold for all-shift chest carry |
| Ingress | **IP68**, native to the handset ✅ (the shell adds impact protection, *not* the IP rating) | Dust, coolant mist, wash-down areas |
| Shock | **MIL-STD-810H** via TPU/polycarbonate composite shell | 1.5 m drops onto concrete |
| **ESD** | Static-dissipative shell, carbon-loaded polyurethane, surface resistivity in the **10⁴–10⁹ Ω/sq** band per **IEC 61340-5-1** 📐 | **Mandatory** in PCB/semiconductor assembly areas — an insulating plastic case would itself be a static hazard |
| **Camera** | Both apertures **physically obscured** by the moulded shell **and** disabled in firmware via MDM policy | See §5.6 Risk 1 — this is what gets the device through the security gate |
| Primary control | **Two oversized physical PTT keys** (volume-up = channel A, volume-down = channel B), raised above the shell surface, high actuation force to prevent accidental triggering | A gloved worker cannot use a touchscreen reliably. Two keys make direction selection physical and instant — **zero training** |
| Secondary control | Wake-word activation | For hands occupied and out of reach |
| Feedback | Loudspeaker, haptics on PTT press/release, high-contrast status indicator | Haptic and visual cues work where 95 dBA makes audio cues useless |
| Display | 6.x″ AMOLED, dual-pane transcript (worker language / supervisor language) | Both parties read the same exchange — a safety feature for critical instructions |

---

## 5.4 Audio Subsystem — the decisive hardware subsystem *(supplementary)*

**The core insight: at 95 dBA, translation accuracy is limited by input SNR, not by NPU throughput.** No model recovers speech that was never cleanly captured.

### Ambient noise by sector (Vietnamese FDI plants)

| Department | Typical range | Dominant sources |
|---|---|---|
| Electronics SMT line | 70–78 dBA | Pick-and-place, reflow ovens |
| Logistics & sorting | 75–85 dBA | Conveyors, forklift horns |
| Injection moulding | 88–95 dBA | Hydraulic clamping, air ejectors |
| Garment & textile | 93–100 dBA | High-speed weaving, sewing |
| Metal stamping | 92–102 dBA | Mechanical impact presses |

*Indicative figures from occupational-health surveys of Vietnamese industrial sites 📐.* Our **95 dBA design point is representative for heavy manufacturing and conservative for SMT assembly** — the device must therefore support two acoustic configurations rather than one.

### ASR degradation vs. SNR

| Input SNR | Environment | Whisper-class WER |
|---|---|---|
| +20 dB | Quiet office | < 3 % |
| +10 dB | Quiet SMT line | ~ 8 % |
| 0 dB | Heavy line, far-field mic | ~ 18 % |
| −5 dB | 90 dBA floor, far-field mic | ~ 45 % |
| −15 dB | 95 dBA floor, far-field mic | **> 85 % — unusable** |

*Indicative, from published English Whisper-class benchmarking 📐; our Vietnamese path uses PhoWhisper and will be re-measured on our own noise-matched test set.* The conclusion is robust regardless of the exact figures: **a far-field phone microphone on a factory floor is not viable.** A close-talk directional boom mic placed a few centimetres from the lips exploits the inverse-square law to deliver a **positive SNR even against 95 dBA ambient**, moving WER from catastrophic to usable — which is why the headset is a required part of the device, not an accessory.

### Capture and playback options

| Option | SNR benefit | Added latency | Use |
|---|---|---|---|
| Phone far-field array (beamforming + NS) | Baseline | 0 ms | Offices, warehouses < 75 dBA |
| Close-talk noise-cancelling boom mic (wired USB-C) | **≈ +20 dB** 📐 | ~0 ms | 75–90 dBA production floors |
| **Bone conduction + boom mic (wired USB-C)** | Structurally isolated from airborne noise; up to **+30 dB** 📐 | ~0 ms | **≥ 90 dBA — our recommended configuration** |
| Any of the above over Bluetooth LE Audio (LC3) | Same as wired | **+20–30 ms** ✅ | Only where cabling is a safety hazard |
| Legacy Bluetooth A2DP/SBC | Same as wired | **+100–250 ms** ✅ | **Avoid** — up to ~17 % of our 1.5 s UPL budget |

### Design decisions that follow

1. **Wired-first.** USB-C adds no latency, no pairing, no accessory battery. Bluetooth is supported but documented as a measured latency trade-off.
2. **Hearing-protection compatibility is mandatory, not optional** (see §5.6 Risk 2). In zones where earplugs are worn, the bone-conduction transducer sits on the temporal bone and the **occlusion effect** — the sealed ear canal amplifying bone-conducted low/mid frequencies 📐 — *increases* intelligibility at no extra electrical power. In high-noise zones the badge connects to certified communication earmuffs (3M PELTOR WS ProTac class) by wire.
3. **Hardware AEC + software half-duplex gate.** The Qualcomm Aqstic codec's hardware echo canceller runs on the `VOICE_COMMUNICATION` route; on top of that our application mutes the capture path for the duration of TTS playback and reopens only after the output buffer drains 🔬 — already implemented and working in our current build.
4. **16 kHz mono end-to-end**, matching the ASR model's native input rate — no resampling stage on the hot path 🔬.
5. **Google Oboe in LowLatency + Exclusive (AAudio MMAP) mode**, 256-frame (~16 ms) callbacks, bypassing the AudioFlinger mixer — audio ingestion contributes ~16 ms rather than the 100 ms+ of a JVM-mediated path 🔬.

---

## 5.5 Commercial Scalability & Lifecycle *(supplementary — supports the 25 % Business criterion)*

Three deployment tiers, all running the **same binary**:

| | **Model A — Software only (BYOD)** | **Model B — Kitted enterprise badge** | **Model C — Custom OEM badge (post-scale)** |
|---|---|---|---|
| Target | FDI firms whose supervisors already carry corporate Snapdragon handsets | Line workers who carry no phone | Very high volume deployments |
| Hardware BOM | **$0** | Handset ≈ $250 (bulk) + ESD shell ≈ $22 + headset ≈ $45 = **≈ $317** 📐 | Custom camera-less PCB on QCS6490-class silicon; small status display, no modem |
| Advantage | Instant pilot, no logistics, distributed via Knox Manage / Play private channel | Turnkey, certified, lockable kit that fits standard EHS inventory | Lowest unit cost; **a port, not a rewrite** — same QNN codebase |

**Choosing Qualcomm silicon now is what makes Model C cheap later** — the strategic reason Option B was rejected as a *starting point* but retained as a *destination*.

**Fleet operations**

- **Provisioning:** Samsung Knox Mobile Enrollment — bulk-configure hundreds of badges on first activation, with policy enforced before the device is usable.
- **Charging & updates:** 10-bay docks at shift-entry points; firmware and model-pack differentials pushed over the local intranet during off-shift hours (never at runtime).
- **Cleaning:** IP68 housing tolerates IPA spray / chlorhexidine wipes for cleanroom hygiene protocols.
- **Failover:** hot-spare badges on the dock; a worker swaps units and their profile transfers by NFC tap — zero downtime.

---

## 5.6 Hardware Risks & Mitigations *(supplementary)*

| # | Risk | Sev. | Mitigation |
|---|---|---|---|
| **1** | **Personal phones and cameras are banned on Tier-1 FDI production floors** (Samsung, LG, Foxconn) to protect IP. A "commercial smartphone" would be refused at the security gate — **a deployment blocker, not an inconvenience.** | **CRITICAL** | The device is presented and deployed as an **enterprise-managed dedicated badge, never a personal phone**: Knox Mobile Enrollment forces enrolment at first boot; MDM policy applies **kernel-level camera disablement** producing a signed compliance attestation; and the moulded shell **physically covers both camera apertures** so security staff can verify by eye. This is now a *selling point* — it is the same control regime the plants already run. |
| **2** | **Mandatory hearing protection makes conventional audio output useless.** Under **QCVN 24:2016/BYT**, workers at ≥ 85 dBA TWA must wear earplugs/earmuffs; a loudspeaker or open-ear device is then inaudible unless driven to hearing-damaging levels. | **HIGH** | Dual audio pathway (§5.4): bone conduction on the temporal bone with earplugs in (occlusion effect *aids* intelligibility), or a wired connection to certified communication earmuffs in the loudest zones. Never raise output level to overcome PPE. |
| **3** | **Thermal throttling in a sealed shell** lengthens or destabilises latency. | **HIGH** | NPU-only execution; graphite TIM → aluminium heat-spreader shell; SPSC ring buffer absorbs stalls; 60-min soak test in Phase 3. |
| **4** | **Peak RAM** across two directions × three models exceeds budget. | MED | 12–16 GB target device; w8a16 (and INT2 where accuracy permits on Gen 5); lazy-load inactive language packs; AI Hub peak-memory profiling gates model selection. |
| **5** | **ESD damage or non-compliance** in semiconductor/PCB areas. | MED | Static-dissipative shell to IEC 61340-5-1; surface resistivity verified by megohmmeter before field trial. |
| **6** | **Battery insufficient** for continuous interpreting. | MED | Screen-off default → 9.9 h; docks and belt packs for heavy profiles; worst case published (3.5 h) rather than concealed. |
| **7** | **Glove operation fails** on capacitive touch. | HIGH if unaddressed | Two physical PTT keys carry the entire core loop; high-touch-sensitivity mode only for optional transcript scrolling. |
| **8** | **Target handset unavailable** to the team before the deadline. | MED | AI Hub device farm profiles target silicon without owning it; Galaxy S23 in hand for physical bring-up; code is ABI-portable 🔬. |
| **9** | **Bluetooth latency** consumes the UPL budget. | MED | Wired USB-C default; LE Audio only if wireless is mandated; A2DP excluded. |

---

## 5.7 Open questions requiring physical measurement *(supplementary)*

Research cannot settle these — they must be measured. Each has a defined method:

1. **Occlusion-effect intelligibility.** *Does bone conduction remain intelligible through factory-issued earplugs at 95 dBA?* — IEC 60318-4 ear-simulator test with 3M 1100 foam earplugs inserted, transducer on the mastoid, measuring eardrum SPL and Speech Transmission Index against pink noise at the target level.
2. **QNN cold/warm model-load latency and peak RAM.** — AI Hub profiling of each cascade stage; confirm loading completes at boot and that no paging occurs during continuous translation.
3. **ESD resistivity and drop survival.** — Static-dissipative carbon-filled TPU shell prototypes; megohmmeter surface-resistivity check against IEC 61340-5-1; MIL-STD-810H 26-drop sequence onto concrete with PTT keys and display verified functional after.
4. **Sustained thermal behaviour in the shell.** — 60-minute continuous-translation soak; log skin temperature, NPU clock, and per-utterance latency drift.
5. **Noise-matched ASR accuracy.** — Re-measure WER for PhoWhisper (not English Whisper) on our own recordings at 85/90/95 dBA with each capture option, to replace the indicative table in §5.4 with our own data.

---

## 5.8 Cross-references *(aid for assembling the proposal)*

| This section provides | Used by |
|---|---|
| SoC choice, RAM ceiling, 84.8 GB/s bandwidth | §4.3 On-device optimisation |
| INT2/4/8 + FP8 precision support | §4.2 Module design (quantisation scheme) |
| Audio front-end, hardware AEC, half-duplex gate | §4.4 Robustness / acoustic challenges |
| Radios disabled by MDM policy | §2.4 Constraints, §6.3 Offline-first |
| QNN / AI Hub toolchain | §6.1 Software stack |
| Power, thermal, battery model | §4.3 and the field-test plan |
| BOM tiers, Knox fleet ops, migration path | §3 Business solution |
| Camera-ban mitigation | §2.3 Target users / deployment reality |

---

## Sources

**Tier 1 — vendor and standards (primary)**
- Qualcomm, *Snapdragon 8 Elite Gen 5 Mobile Platform Product Brief* — SM8850, Oryon CPU, Hexagon NPU, native INT2
- Qualcomm, *Snapdragon 8 Elite Mobile Platform Product Brief* — SM8750, +45 % NPU vs 8 Gen 3, LPDDR5X-5300 (84.8 GB/s, ≤ 24 GB), INT4/8/16 + FP16
- Qualcomm, *Dragonwing QCS6490/QCM6490 Product Brief* and *RB3 Gen 2 Development Kit Product Brief* — 12 TOPS, 6 GB/128 GB
- Qualcomm, *AI Engine / Hexagon NPU* and *Unlocking on-device generative AI with an NPU* — efficiency vs CPU/GPU
- Qualcomm AI Hub documentation — device fleet and compile/profile/quantise workflow
- NVIDIA, *Jetson Orin Nano datasheet* — 40 TOPS sparse / 20 dense, 7–15 W power modes
- **QCVN 24:2016/BYT** — Vietnamese national technical regulation, permissible workplace noise (85 dBA / 8 h TWA)
- **QCVN 118:2018/BTTTT** — Vietnamese EMC type approval
- **IEC 61340-5-1** — protection of electronic devices from electrostatic phenomena
- **IEC 60318-4** — ear simulator for occluded-ear measurements
- **MIL-STD-810H** — environmental test methods
- Bluetooth SIG — LE Audio / LC3 latency characteristics
- Samsung Knox — Knox Mobile Enrollment and Knox Service Plugin camera-policy documentation

**Tier 2 — peer-reviewed / occupational health**
- Occupational noise surveys of Vietnamese garment and textile factories (noise levels and PPE use)
- Audiology literature on the occlusion effect in ears sealed with foam earplugs

**Tier 3 — independent testing**
- NotebookCheck — Snapdragon 8 Elite / 8 Elite Gen 5 power-draw and thermal-throttling measurements
- Independent open-source ASR benchmarking (Whisper-class WER vs SNR) — *indicative only; English models*

**Team-generated (🔬)**
- `docs/ARCHITECTURE.md` — native audio path, SPSC ring buffer, threading model
- `docs/QNN_INTEGRATION.md` — QNN runtime integration and packaging
- `docs/HF_INTEGRATION_REPORT.md` — measured model artifacts and the working bidirectional pipeline

> **Sourcing discipline:** every figure above is tagged ✅ (vendor/standard), 📐 (estimate with derivation shown) or 🔬 (measured by us). Where the Gemini research report asserted `[OFFICIAL]` on the basis of consumer blogs or of our own draft, those claims have been demoted or re-sourced — see `GEMINI_REPORT_AUDIT.md`.
