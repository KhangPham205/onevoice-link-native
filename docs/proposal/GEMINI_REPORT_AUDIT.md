# Audit of the Gemini Deep Research report

> Reviewed: `Hardware Review for Competition Proposal.pdf` (30 pages, 27 Jul 2026).
> Verdict: **~70 % genuinely useful — keep it, but do not paste it as-is.**
> Several citations are misattributed or circular, one measurement is falsely
> credited to our team, and one product name is wrong in a way a Qualcomm judge
> would catch immediately.

---

## 1. What the report got RIGHT (keep this work)

| Finding | Assessment |
|---|---|
| **Red Flag 1 — phones/cameras banned on FDI factory floors** | **The single most valuable output of the whole report.** This is a genuine deployment blocker for our Option A, and the Knox Mobile Enrollment + kernel-level camera disablement + camera-obscuring shell mitigation is a credible, specific answer. Adopt in full. |
| **Red Flag 2 — mandatory hearing protection** | Correct and important. Vietnamese workers at ≥ 85 dBA TWA must wear HPDs, which makes open-air speakers and open-ear bone conduction useless. The dual-pathway answer (bone conduction for moderate zones, comms-earmuffs for high zones, occlusion effect when earplugs are worn) is sound engineering. Adopt. |
| **Power budget arithmetic** | **I verified every sum and division — all correct.** 4.20+0.40+0.50+0.015+0.05+0.40 = 5.565 W ✔ · 0.35+0.05+0.015+0.01 = 0.425 W ✔ · (0.30×5.565)+(0.70×0.425) = 1.967 W ✔ · 19.4/1.967 = 9.86 h ✔ · 5,000 mAh × 3.88 V = 19.4 Wh ✔. The two-state model (active/armed-idle) is better than my single-state version. Adopt the structure. |
| **Sensitivity analysis** (15 % / 30 % / 50 % / 100 % duty) | Excellent addition — pre-empts the obvious judge question "what if they talk more?". All four calculations verified correct. Adopt. |
| **Corrected component power figures** | Our mic 0.10 W → 0.015 W and UFS 0.25 W → 0.01–0.05 W corrections are **right**; MEMS mics really are ~1–2 mW/channel and storage is asleep once models are RAM-resident. Adopt. |
| **Sector-by-sector noise mapping** | Very useful — shows 95 dBA is conservative for stamping/textile but *over*stated for SMT lines. Adopt, with sourcing softened. |
| **ESD / IEC 61340-5-1 and QCVN 118:2018/BTTTT EMC inheritance** | Two points we completely missed. ESD control is real in PCB/semiconductor plants, and "COTS handset inherits existing Vietnamese type approval" is a genuinely strong business argument. Adopt. |
| **Fleet ops** (Knox provisioning, 10-bay docks, IPA cleaning, hot-spare + NFC profile transfer) | Fills the "completeness" gap I flagged. Adopt. |
| **Part E measurement methodologies** | The IEC 60318-4 ear-simulator test and the 26-drop MIL-STD sequence are properly specified. Adopt. |

## 2. What must be FIXED before use

| # | Problem | Severity | Fix applied in v2 |
|---|---|---|---|
| 1 | **"Snapdragon 8 Gen 5"** used in the component table and diagrams. **No such product exists** — it is *Snapdragon 8 Elite Gen 5*. | **Critical** — instant credibility loss with Qualcomm judges | Renamed everywhere to **Snapdragon 8 Elite Gen 5 (SM8850)** |
| 2 | **Circular citation.** Source `[3]` is *our own uploaded draft* (`section5_hardware_device_concept.md`). Dozens of claims marked CONFIRMED / [OFFICIAL] are "verified" by citing our own unverified estimates. Worst case: the **NVIDIA Jetson datasheet** is cited as `URL: uploaded:section5_hardware_device_concept.md`. | **Critical** — this is not verification, it is an echo | Every `[3]`-backed claim demoted to 📐 estimate or re-sourced |
| 3 | **Falsely attributed measurement.** The Class-D amplifier row is tagged 🔬 "Measured during active speech playback at 90 dBA". **We never measured this.** 🔬 in our legend means *our team measured it*. | **Critical** — a judge asking "show me the measurement" would expose it | Demoted to 📐 |
| 4 | **Misattributed sources.** QCVN 24:2016/BYT (Vietnamese law) cited with an **osha.gov** URL · "Acoustical Society of America" cited with a **noisyworld.org** consumer blog · "Bluetooth SIG" cited with a **Jabra** vendor whitepaper · "AES/INTERSPEECH: acoustic capture in high-noise industrial environments" is actually the paper *"Robust Self-Supervised Audio-Visual Speech Recognition"* — a different topic (lip-reading). | High | Tier labels corrected; claims kept only where independently plausible, flagged where not |
| 5 | **Internal contradiction on display power.** Part A "CONFIRMS" our 0.90 W @ 50 %; Part D silently uses **0.40 W** — and quietly changes the spec from 120 Hz to 60 Hz + dark UI. | High | Resolved explicitly: 0.40 W *stated with its conditions* (60 Hz, dark UI, 30 % APL), 0.90 W given as the bright/120 Hz case |
| 6 | **Model footprint numbers are wrong for our stack.** Report claims Whisper-Small 300 MB + NLLB 600 MB + Kokoro 150 MB + "1.5 GB Android/QNN overhead" = 2.55 GB. Counting OS overhead inside a *model* budget is wrong, and **we have real measured artifacts** that differ. | High | Replaced with our **actually measured** 🔬 sizes (see v2 §5.2) |
| 7 | **Platform silently switched** from Snapdragon 8 Elite → 8 Elite Gen 5, and device → Galaxy S26, without a RED FLAG block (the prompt required one for locked-decision changes). | Medium | **Accepted on the merits** — our own spec says "8 Elite *trở lên*" (or newer), so Gen 5 is compatible — but stated explicitly as a decision, with 8 Elite retained as validated fallback |
| 8 | **Competitor specs sourced from listicles** ([UNVERIFIED] blogs). "Vasco V4 has no offline mode" and "Timekettle needs a phone for offline packs" are asserted confidently but weakly sourced. | Medium | Kept the comparison but softened the wording and marked the tier honestly |
| 9 | **WER-vs-SNR table** attributed to a benchmarking blog, and it is **Whisper-Small English** — our Vietnamese ASR is PhoWhisper. | Medium | Kept (directionally sound and very persuasive) but labelled as indicative, English-model data, with our own validation planned |
| 10 | Miscellaneous unsupported specifics: Jetson "carrier board adds 3–5 W" cited to our doc; "wireless = exactly 0.00 W"; ESD resistivity given as an exact value cited to our doc. | Low | Softened to ranges or marked 📐 |

## 3. Net assessment

Gemini did what deep research is good at: it found **two deployment blockers we had missed** (factory phone bans, hearing protection) and produced a **cleaner power model** than mine. It did what LLM research is bad at: it **manufactured citation authority** — dressing our own estimates and consumer blogs up as `[OFFICIAL]` and `CONFIRMED`, and inventing a measurement we never took.

The corrected merge is in **`section5_v2_FINAL.md`**. Every number there is now traceable to one of: a vendor document, an independent measurement, our own build 🔬, or an explicitly-labelled estimate 📐.
