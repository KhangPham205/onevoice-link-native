# Gemini Deep Research prompt — Section 5 (Hardware & Device Concept)

> **Cách dùng:** Upload file `section5_hardware_device_concept.md` lên Gemini Deep Research,
> rồi paste toàn bộ nội dung trong khối dưới đây làm prompt.

---

## ROLE

You are a **senior edge-AI hardware systems architect and technical due-diligence analyst**. You are reviewing a hardware section of a competition proposal that will be judged by a panel that includes **two engineers from Qualcomm**. Your job is not to praise the document — it is to **verify every factual claim, destroy any number that cannot be sourced, close the research gaps, and return a stronger, defensible version.**

## CONTEXT

**The competition.** OneVoice AI Challenge 2026 (Saigon AI Hub + Qualcomm), May–Nov 2026. Teams must build a **real-time, fully on-device (no cloud, no internet at runtime — internet use at runtime = disqualification) speech-to-speech translation device** for use by factory/construction/logistics workers. Scoring: Technical Performance 50 %, Innovation 25 %, Business Viability 25 %. The attached document is **Section 5 "Hardware & Device Concept", which carries 25 % of the proposal score**.

**The product.** "OneVoice Link" — a bidirectional speech-to-speech interpreter. Cascaded on-device pipeline: **ASR → Machine Translation → TTS**, running on the Qualcomm Hexagon NPU via the QNN / Qualcomm AI Engine Direct runtime. Language pairs: **VI↔EN, VI↔KO, VI↔ZH**. Target latency: user-perceived latency < 1.5 s.

**Target users and environment (this drives every hardware decision).** Vietnamese line workers and Korean/Taiwanese supervisors inside **Korean/Taiwanese FDI factories in Vietnam** (Samsung, LG, Foxconn — industrial parks in Bắc Ninh, Bắc Giang, Bình Dương). Conditions: **ambient noise up to ~95 dB(A)**, workers wearing **gloves**, hands frequently occupied, unreliable or prohibited Wi-Fi, minimal tolerance for training.

**Decisions already locked by the team** (do not propose reversing these unless you find *strong, sourced* evidence that one is unworkable — in which case flag it explicitly as a RED FLAG rather than silently rewriting):
1. Compute platform = **commercial Android flagship phone on Snapdragon 8 Elite (SM8750)**, in a rugged industrial shell — **not** a custom PCB, not a dev kit, not Snapdragon X Elite.
2. Qualcomm silicon and the **QNN + Qualcomm AI Hub** toolchain (aligns with the judging panel and enables device-farm profiling).
3. Cascaded ASR→MT→TTS architecture (not end-to-end).
4. Current development hardware: Samsung Galaxy S23 (Snapdragon 8 Gen 2) + Android emulator; production target is 8 Elite.

## THE ATTACHED DOCUMENT

`section5_hardware_device_concept.md` is our current draft. It uses an evidence legend that you must respect and extend:
- ✅ = vendor-published fact
- 📐 = **engineering estimate, not yet validated** ← *these are your primary targets*
- 🔬 = measured/built by our team (treat as given; do not re-verify)

## YOUR OBJECTIVES, IN PRIORITY ORDER

### 1. VERIFY every ✅ claim against primary sources
Confirm or correct each vendor-published figure. **Prefer primary sources** (Qualcomm/NVIDIA product briefs, datasheets, official documentation, IEEE/ACM papers, standards bodies) over blogs, benchmark aggregators, or press summaries. Where a widely-circulated number turns out to be unofficial or wrong, say so explicitly.

### 2. VALIDATE or REPLACE every 📐 estimate
For each estimate below, find real published measurements or well-founded engineering data. If no credible source exists, say **"no credible public source — must be measured"** rather than inventing a number. Specific targets:

| # | Claim in the draft | What to find |
|---|---|---|
| 1 | Snapdragon 8 Elite ≈ 60–80 TOPS INT8 (est.) | Does Qualcomm publish *any* official TOPS/AI-throughput figure for mobile Hexagon? What is the most defensible way to state 8 Elite NPU performance in front of Qualcomm engineers? Include the official "+45 % vs 8 Gen 3" framing and any MLPerf Mobile / MLCommons results. |
| 2 | SoC ~4.5 W sustained, 6.5 W peak during NPU inference | Real measured power for sustained NPU inference (ASR / small LLM / TTS) on Snapdragon 8-series. Look for MLPerf Mobile power results, Qualcomm whitepapers, academic edge-AI power studies, and teardown/lab measurements. |
| 3 | Mic 0.10 W · speaker 0.70 W · UFS 4.0 0.25 W · AMOLED 0.90 W @ 50 % | Component-level power for a modern flagship: MEMS mic arrays, ~1 W speaker amp real draw, UFS 4.0 active/idle, 6.x″ 120 Hz AMOLED at typical brightness. |
| 4 | 30 % active / 70 % idle duty cycle for a factory shift | Any field study, ethnography, or industry data on **how much of a shift workers actually spend in verbal exchange** in manufacturing settings. If none exists, propose a defensible sensitivity analysis instead. |
| 5 | Bone-conduction / close-talk boom mic ≈ +20–25 dB effective SNR gain | Published measurements of noise-cancelling boom mics and bone-conduction mics in high-noise environments, and — importantly — **their impact on ASR word error rate**, not just SNR. |
| 6 | LE Audio adds ~20–30 ms; legacy A2DP 100–200 ms | Authoritative latency figures for Bluetooth LE Audio (LC3), aptX Adaptive/LL, and classic A2DP/SBC, ideally from the Bluetooth SIG or measured studies. |
| 7 | ~2.5 GB total for all quantised models (3 language pairs) | Realistic on-disk and **peak RAM** footprints for w8a16-quantised Whisper-class ASR, NLLB/Qwen-class MT, and Kokoro/Piper-class TTS. Peak RAM matters more than disk. |
| 8 | QCS6490 board-level power "materially higher" than a phone | Actual RB3 Gen 2 / QCS6490 power figures. Is there a published TDP or measured board draw? |
| 9 | Jetson Orin Nano 40 TOPS / 7–15 W (Super: 67 TOPS / 25 W) | Confirm against NVIDIA's current datasheet, including whether TOPS figures are sparse or dense INT8. |
| 10 | Hexagon NPU ≈ 100× CPU / ≈ 10× GPU efficiency | Locate the exact Qualcomm source and the conditions under which this holds; state it precisely or drop it. |
| 11 | Galaxy S25 (8 Elite) available in the Qualcomm AI Hub device farm | Confirm from Qualcomm AI Hub documentation which devices are actually available for profiling, and whether 8 Elite / 8 Elite Gen 5 handsets are in the fleet. |
| 12 | Rugged shell: IP68 + MIL-STD-810H, ≈ 250 g, ≈ 172 × 86 × 15 mm | Real specs of commercially available rugged phone cases/enclosures and their actual weight/thickness penalty. Note that IP68 belongs to the handset, not the case — is that framing correct? |

### 3. ANSWER these strategic research questions (highest value first)

**Q1 — THE CRITICAL ONE: Are personal smartphones actually permitted on the production floor of Korean/Taiwanese FDI electronics factories in Vietnam?**
Samsung, LG, Foxconn and similar plants are widely reported to enforce **camera and mobile-phone restrictions** on production floors for IP-protection reasons. If phones are banned or camera-restricted, the entire "commercial Android handset" strategy is at risk. Research: actual policies at Samsung Vietnam / LG / Foxconn Vietnam plants; how enterprise deployments work around this (MDM camera lockout, camera-less enterprise SKUs, factory-approved device whitelisting, physical camera seals); whether any translation/communication device is already approved for such floors. **If this is a real blocker, say so loudly and propose concrete mitigations** — this determines whether Section 5's core decision survives judge scrutiny.

**Q2 — Hearing protection compatibility.** Workers at ≥ 85 dB(A) are typically required to wear earmuffs or earplugs (OSHA/NIOSH and Vietnamese OSH regulations — verify what Vietnam's standard is). How does a translation device deliver audio to someone wearing hearing protection? Evaluate: bone conduction under earmuffs, earmuffs with integrated comms (e.g. 3M PELTOR WS/Lite-Com class), in-ear protective comms headsets. Which is realistically deployable, and at what cost? **This may be a more important design constraint than anything currently in the draft.**

**Q3 — Actual noise levels.** What are measured/typical dB(A) levels in Vietnamese electronics assembly, injection moulding, stamping, textile, and logistics environments? Is our "95 dB(A)" figure representative, conservative, or exaggerated? Cite occupational-health surveys where possible.

**Q4 — ASR degradation vs. noise.** Published data on WER degradation of Whisper-class ASR as a function of SNR, and the SNR that a close-talk or bone-conduction mic actually delivers at 85–95 dB(A) ambient. This converts our hardware argument from qualitative to quantitative — the single strongest upgrade available to this section.

**Q5 — Competitive hardware benchmark.** Compare our device concept against shipping dedicated translator hardware — **Timekettle (W4 Pro / X1 / WT2), Pocketalk S2, Vasco Translator V4/E1, Langogo, iFLYTEK translators** — on: form factor, weight, battery life, offline capability, microphone architecture, noise handling, price. Identify where a phone-based approach genuinely wins and where it genuinely loses. Be honest about the losses.

**Q6 — Thermal reality.** Evidence on sustained NPU workloads and thermal throttling on Snapdragon 8-series phones, and the additional thermal penalty of a sealed rugged case. Any measured data on skin-temperature limits and throttle onset time for continuous AI inference.

**Q7 — Glove operability.** Standards or published testing for touchscreen and physical-button operation with industrial gloves (nitrile, cut-resistant, leather). Are there minimum button size/force standards for gloved operation? Does Android support a "glove mode" (some OEMs, e.g. high-touch-sensitivity modes) and is it usable?

**Q8 — Should the target be 8 Elite or 8 Elite Gen 5?** The Gen 5 was released after our platform decision. Compare NPU capability (including native INT2), availability of handsets in Vietnam by Q4 2026, availability in the AI Hub device farm, and price. Recommend whether to re-target, keep 8 Elite, or state "8 Elite or newer".

**Q9 — Certification & safety.** Anything required to bring a battery-powered electronic device onto a Vietnamese factory floor: local certification, ESD requirements in electronics assembly (a real concern in semiconductor/PCB plants), intrinsic-safety/ATEX if any target sites are hazardous, and battery-safety rules.

### 4. OPTIMISE the section
After verifying, improve the draft on these axes:
- **Logical consistency** — do the power numbers, duty cycle, battery capacity and runtime claims actually reconcile arithmetically? Recompute and correct.
- **Argument strength** — is the Option A vs Option B comparison fair and convincing, or does it look like a rigged comparison? Suggest a better/fairer contender if QCS6490 is the wrong foil.
- **Judge-proofing** — for each major claim, ask "what would a Qualcomm engineer object to?" and pre-empt it.
- **Completeness** — what is *missing* from a hardware section that a 25 %-weighted rubric would expect? (e.g. mechanical mounting, charging/docking strategy, device management/fleet provisioning, water/dust cleaning procedure, accessibility, spare/failover device policy)
- **Concision** — flag anything that is padding and could be cut without losing marks.

## HARD CONSTRAINTS

- **Do not invent numbers.** Every quantitative claim in your output must be either (a) cited to a real, checkable source, (b) explicitly labelled as an estimate with the derivation shown, or (c) marked "must be measured — no public data".
- **Do not silently change the locked decisions** listed in CONTEXT. Challenge them only in an explicit "RED FLAG / RECOMMEND RECONSIDERING" block with sourced evidence.
- **Distinguish source tiers** in every citation: `[OFFICIAL]` vendor/standards, `[PEER-REVIEWED]`, `[THIRD-PARTY MEASURED]` (lab tests, teardowns, benchmark orgs), `[UNVERIFIED]` (blogs, forums, marketing).
- **Prefer 2024–2026 sources.** Mobile silicon and edge-AI figures age quickly; flag anything older that you rely on.
- The output will be pasted into a formal English proposal — write in **English**, precise and professional, no marketing fluff.

## REQUIRED OUTPUT FORMAT

Produce your report in exactly these parts:

**PART A — Verification ledger.** A table of every checkable claim in the draft:
`Claim | Draft value | Verified value | Verdict (CONFIRMED / CORRECTED / UNVERIFIABLE / WRONG) | Source + tier | Confidence (H/M/L)`

**PART B — Red flags.** Anything that could sink this section under expert scrutiny, ordered by severity, each with a concrete mitigation. Q1 (phones on factory floors) and Q2 (hearing protection) must appear here if your research confirms they are real issues.

**PART C — New findings the draft is missing.** Research results from Q1–Q9 that should be incorporated, with the specific insight and why it matters to the score.

**PART D — Rewritten Section 5.** A complete, improved, drop-in replacement in Markdown, preserving the template structure (5.1 Platform Selection & Justification with the comparison table + justification paragraph; 5.2 Key Hardware Components & Power Budget with the component table), keeping the ✅/📐/🔬 evidence legend, and integrating everything from Parts A–C. Corrected numbers only.

**PART E — Open questions for the team.** What could not be resolved by research and must be decided or measured internally, with a recommended method for each.

**PART F — Full source list.** Grouped by tier, with URLs and access dates.

Begin with PART A. Be rigorous and be blunt — a comfortable review that lets a wrong number through costs us 25 % of the score.
