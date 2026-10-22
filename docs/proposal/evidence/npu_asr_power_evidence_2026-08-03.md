# NPU, ASR, Power and Thermal Evidence — 3 August 2026

## Scope and evidence rules

This audit supports the five exact-hosted phone candidates in OneVoice
Challenge proposal §5.1: Galaxy S22 5G, S23, S24, S25 and S26. It records the
source configuration and limitations behind the concise comparison in the
proposal.

- **Official platform claims** are taken from Qualcomm product pages. They are
  relative, workload-dependent vendor claims, not absolute TOPS or watts.
- **ASR results** are taken from Qualcomm's version-pinned AI Hub Models data.
  They are real-device model profiles, but not OneVoice end-to-end results.
- **Independent NPU checks** are individual public Geekbench AI submissions.
  They are useful screening evidence, not controlled acceptance tests.
- **Power results** are Notebookcheck's original whole-phone measurements. They
  are not SoC TDP, NPU-only power or the energy of the OneVoice pipeline.

Qualcomm does not publish a directly comparable absolute mobile NPU TOPS value
or SoC TDP for these five platforms. Third-party pages that merely repeat an
untraceable TOPS/TDP number are therefore excluded. The relative Qualcomm
percentages below must not be multiplied into a synthetic cross-generation
index: the workload, precision and baseline can change between announcements.

## 1. Official Qualcomm NPU evidence

| Platform | Qualcomm-published AI statement | Correct use in OneVoice |
|---|---|---|
| [Snapdragon 8 Gen 1](https://www.qualcomm.com/smartphones/products/8-series/snapdragon-8-gen-1-mobile-platform) | Seventh-generation AI Engine up to 4× faster than its predecessor; tensor accelerator up to 2× faster and shared memory 2× larger | Establishes generation capability only; no absolute TOPS/TDP and no Opus-MT result |
| [Snapdragon 8 Gen 2](https://www.qualcomm.com/smartphones/products/8-series/snapdragon-8-gen-2-mobile-platform) | Up to 4.35× faster AI than its predecessor for the cited model-dependent case; INT4 support with up to 60% better performance per watt | Supports the S23 feasibility hypothesis; does not mean OneVoice is 4.35× faster than S22 |
| [Snapdragon 8 Gen 3](https://www.qualcomm.com/smartphones/products/8-series/snapdragon-8-gen-3-mobile-platform) | Hexagon NPU up to 98% faster and 40% better performance per watt than the prior generation | Relative vendor claim only |
| [Snapdragon 8 Elite](https://www.qualcomm.com/smartphones/products/8-series/snapdragon-8-elite-mobile-platform) | Hexagon NPU up to 45% faster and 45% better performance per watt; up to 27% overall SoC power savings | Relative vendor claim; SoC savings are not whole-phone power |
| [Snapdragon 8 Elite Gen 5](https://www.qualcomm.com/smartphones/products/8-series/snapdragon-8-elite-gen-5) | Hexagon NPU up to 37% faster and 16% better performance per watt; up to 16% overall SoC power savings | Relative vendor claim; cannot be converted into TOPS or phone runtime |

## 2. Independent QNN NPU screening check

The rows below use Geekbench AI 1.7.0 for Android AArch64, Android 16,
TensorFlow Lite and the QNN backend. The overall half-precision and quantized
scores provide a same-version sanity check that is more defensible than an
unpublished TOPS estimate.

| Public result | Overall HP score | Overall quantized score | Machine Translation HP score / throughput | Limitation |
|---|---:|---:|---:|---|
| [Galaxy S22 / Snapdragon 8 Gen 1](https://browser.geekbench.com/ai/v1/453684) | 11,533 | 29,733 | 3,226 / 55.6 IPS | Individual public run |
| [Galaxy S23 / Snapdragon 8 Gen 2](https://browser.geekbench.com/ai/v1/511278) | 16,434 | 40,204 | 3,758 / 64.7 IPS | Individual public run; not OneVoice Opus-MT |
| [Galaxy S24 / Snapdragon 8 Gen 3](https://browser.geekbench.com/ai/v1/532146) | 22,716 | 55,988 | 4,798 / 82.6 IPS | Exact Snapdragon S24 result, but still an individual public run |
| [Galaxy S25 / Snapdragon 8 Elite](https://browser.geekbench.com/ai/v1/440160) | 28,780 | 71,800 | 6,718 / 115.7 IPS | Individual public run |
| [Galaxy S26 Ultra](https://browser.geekbench.com/ai/v1/447939) | 32,412 | 81,993 | 6,445 / 111.0 IPS | QNN result, but the AI-device field says only `Qualcomm ARMv8` and the phone is Ultra, not base S26; secondary evidence only |

These submissions are not a controlled laboratory series: temperature,
governor and battery state are unknown. Geekbench's machine-translation
workload is not OneVoice VI↔EN Opus-MT, and its accuracy field is not a
BLEU/COMET quality result. The non-monotonic S25-to-S26 MT row also demonstrates
why a generic NPU score cannot replace model-specific profiling.

## 3. ASR reference for all five candidates

### 3.1 Comparable current-release rows

The comparable rows come from
[Qualcomm AI Hub Models v0.59.0 `perf.yaml`](https://github.com/qualcomm/ai-hub-models/blob/v0.59.0/src/qai_hub_models/models/whisper_small_quantized/perf.yaml)
at commit `dcd5203c6b3fc0f9465000b479c85c743a37246c`:

- model: Whisper-Small-Quantized, W8A16;
- input: 80 × 3,000 mel features, representing up to 30 seconds of audio;
- maximum decoded sequence: 200 tokens;
- target/runtime: `precompiled_qnn_onnx`, ONNX Runtime 1.27.1,
  QAIRT `2.45.0.260326154327`, NPU primary compute;
- interpretation from the
  [official model card](https://aihub.qualcomm.com/models/whisper_small_quantized):
  encoder latency is time to first token and decoder latency is time for each
  additional token.

| Exact hosted phone | Encoder / time to first token | Decoder / each additional token | NPU residency | Comparability status |
|---|---:|---:|---:|---|
| Galaxy S22 5G / 8 Gen 1 | [305.200 ms](https://github.com/qualcomm/ai-hub-models/blob/v0.59.0/src/qai_hub_models/models/whisper_small_quantized/perf.yaml), job `j5m8n3kwp` | [10.081 ms/token](https://github.com/qualcomm/ai-hub-models/blob/v0.59.0/src/qai_hub_models/models/whisper_small_quantized/perf.yaml), job `jgnk63qrg` | 3,819/3,819 encoder layers; 2,853/2,853 decoder layers | Same release, precision and runtime; S22 compiler partition has a different reported encoder layer count |
| Galaxy S23 / 8 Gen 2 | **Not published in the v0.59.0 performance rows** | **Not published in the v0.59.0 performance rows** | The model card and `supported_devices` list the exact S23, but support is not a latency result | Data gap; do not interpolate between S22 and S24 |
| Galaxy S24 / 8 Gen 3 | [274.961 ms](https://github.com/qualcomm/ai-hub-models/blob/v0.59.0/src/qai_hub_models/models/whisper_small_quantized/perf.yaml), job `jg9dlwql5` | [6.172 ms/token](https://github.com/qualcomm/ai-hub-models/blob/v0.59.0/src/qai_hub_models/models/whisper_small_quantized/perf.yaml), job `jp164em25` | 2,014/2,014; 2,853/2,853 | Comparable screening row |
| Galaxy S25 / 8 Elite | [229.540 ms](https://github.com/qualcomm/ai-hub-models/blob/v0.59.0/src/qai_hub_models/models/whisper_small_quantized/perf.yaml), job `j5qv63d4g` | [4.700 ms/token](https://github.com/qualcomm/ai-hub-models/blob/v0.59.0/src/qai_hub_models/models/whisper_small_quantized/perf.yaml), job `jgllv3q8g` | 2,014/2,014; 2,853/2,853 | Comparable screening row |
| Galaxy S26 / 8 Elite Gen 5 | [174.716 ms](https://github.com/qualcomm/ai-hub-models/blob/v0.59.0/src/qai_hub_models/models/whisper_small_quantized/perf.yaml), job `jgk8e3w2g` | [3.937 ms/token](https://github.com/qualcomm/ai-hub-models/blob/v0.59.0/src/qai_hub_models/models/whisper_small_quantized/perf.yaml), job `j5qv63x4g` | 2,014/2,014; 2,853/2,853 | Comparable screening row |

The four numeric rows exclude audio capture, VAD/chunk waiting, feature
extraction, tokenizer/decoding control, Opus-MT, TTS, Bluetooth audio, thermal soak
and OneVoice orchestration. They must not be reported as end-to-end latency.

### 3.2 Exact-S23/S24 float bridge — separate from the W8A16 ranking

An older, commit-pinned Qualcomm **Whisper-Small v0.37** profile provides
exact-device evidence on Galaxy S23. It uses float precision,
`QNN_CONTEXT_BINARY` and QAIRT 2.37:

| Source/model | Exact phone | Encoder | Decoder | Runtime / compute | Use |
|---|---|---:|---:|---|---|
| [Qualcomm Whisper-Small at commit `00c8c7f`](https://huggingface.co/qualcomm/Whisper-Small/blob/00c8c7f90d36cbb19f92cd85c65f09d95e77cafa/README.md) | Galaxy S23 | 134.534 ms, job `jpyjx0drp` | 12.050 ms/token, job `jp02j7r25` | Float, QNN context binary, NPU | Confirms that an exact S23 executed both Whisper components on its NPU; **not comparable** with the v0.59.0 W8A16 rows |
| Same pinned Qualcomm profile | Galaxy S24 | 102.365 ms, job `jp8mxv7z5` | 9.634 ms/token, job `jgkq4myyg` | Same float/QNN-context/QAIRT configuration as the S23 row | Within this separate bridge only, S24 is 23.9% lower-latency for the encoder and 20.0% lower-latency per decoder step |

The matching versioned source is Qualcomm's
[Whisper-Small v0.37.0 `perf.yaml`](https://github.com/qualcomm/ai-hub-models/blob/v0.37.0/qai_hub_models/models/whisper_small/perf.yaml).

This bridge fills the feasibility evidence gap without fabricating a normalized
S23 number. It cannot be placed on the W8A16 trend line because model precision,
graph revision, QAIRT version and target artifact differ. Before hardware freeze,
the team must run the current OneVoice ASR export on the exact S23 using the same
pinned QAIRT/runtime as the MT and TTS graphs. Public job pages can require
owner/share permission, so pinned performance files are the primary citations
and the job IDs above are retained for account-side audit. No Workbench token or
job quota was used to create this audit.

## 4. Whole-phone power and thermal evidence

Notebookcheck reports whole-device input measured at the primary side of the
power supply with a Metrahit meter in its
[test methodology](https://www.notebookcheck.net/How-does-Notebookcheck-test-laptops-and-smartphones-A-behind-the-scenes-look-into-our-review-process.15394.0.html).
The figures therefore include conversion losses; they are useful operating
envelopes, not battery-terminal power, NPU power or SoC TDP. Different review
years also do not necessarily use identical stress workloads.

| Candidate generation | Device measured | Idle / generic load | Additional measured envelope | Correct interpretation |
|---|---|---|---|---|
| 8 Gen 1 | [Xiaomi 12 reference inside the Galaxy S22 review](https://www.notebookcheck.net/Samsung-Galaxy-S22-review-One-of-the-best-compact-Android-smartphones.616787.0.html) | Idle average 1.18 W | Geekbench average 7.17 W; GFXBench average 9.02 W | Same-SoC proxy only; Notebookcheck's reviewed base S22 was Exynos, so these numbers are not relabelled as Galaxy S22 results |
| 8 Gen 2 | [Galaxy S23 exact phone](https://www.notebookcheck.net/Samsung-Galaxy-S23-review-The-small-S23-smartphone-makes-the-biggest-leap.700018.0.html) | Idle 0.57 / 0.74 / 0.83 W; load 4.26 / 6.16 W | Geekbench average 6.19 W; GFXBench average 11.8 W | Best external power reference for the selected phone; still not an AI-pipeline measurement |
| 8 Gen 3 | [Galaxy S24 Ultra](https://www.notebookcheck.net/Samsung-Galaxy-S24-Ultra-review-The-ultimate-smartphone-becomes-an-AI-smartphone.809187.0.html) | Different Ultra chassis | Geekbench average 7.63 W; GFXBench average 12.1 W | Platform proxy only, not base-S24 power |
| 8 Elite | [Galaxy S25 exact phone](https://www.notebookcheck.net/Samsung-Galaxy-S25-review-The-star-among-compact-smartphones-is-losing-ground.989246.0.html) | Idle 0.47 / 1.04 / 1.07 W; load 13.33 / 16.38 W | Geekbench average 7.30 W; GFXBench average 14.1 W | Exact-phone envelope, but methodology/workload differs from the older S23 review; no direct TDP comparison |
| 8 Elite Gen 5 | [Galaxy S26 Ultra](https://www.notebookcheck.net/Blocks-prying-eyes-Samsung-Galaxy-S26-Ultra-review.1278349.0.html) | Idle 0.56 / 1.18 / 1.33 W; load 14.91 / 16.58 W | Geekbench average 9.66 W; GFXBench average 12.9 W | Platform and larger-chassis proxy only, not base-S26 power |

The exact S23 review also reports substantial graphics-stress throttling despite
low surface temperatures. That does not prove the NPU cascade will throttle, but
it prevents a thermal-pass claim without OneVoice's planned 60-minute ASR→MT→TTS
soak.

## 5. Battery sensitivity for the selected S23

The proposal's planning model assumes a 3,900 mAh cell, 3.85 V nominal voltage,
a 10% usable-energy guard band, 90% state of health, 5.60 W active power and 20%
active duty. The external idle figures are screen-on/generic laboratory states,
whereas OneVoice plans screen-off capture with the Bluetooth communication
route held. They are
therefore sensitivity cases, not replacements for measured armed-idle power.

| Armed-idle assumption | Average power at 20% duty | Runtime at 90% SOH | Eight-hour status |
|---:|---:|---:|---|
| 0.43 W project budget | 1.464 W | 8.31 h | Nominal planning pass; 0.31 h margin |
| 0.57 W Notebookcheck minimum-idle sensitivity | 1.576 W | 7.72 h | Fails sensitivity case |
| 0.74 W Notebookcheck average-idle sensitivity | 1.712 W | 7.10 h | Fails sensitivity case |

At the 5.60 W active ceiling and 90% SOH, the maximum active duty is about
18.9% if armed idle is 0.57 W and 16.1% if armed idle is 0.74 W. Equivalently,
at 20% duty the measured armed-idle state must be about 0.50 W or lower to meet
eight hours under the current assumptions.

## 6. Decision impact

- **Compute feasibility:** supported. Exact-device QNN evidence and the lighter
  Opus-MT architecture make S23 a credible candidate; they do not yet constitute
  a complete OneVoice result.
- **ASR performance:** current normalized S23 latency is unresolved. The exact
  float profile proves NPU execution only; hardware freeze requires a current,
  same-version S23 job.
- **Power and thermal feasibility:** unresolved. The 5.60 W active budget sits
  within the scale of an external whole-phone load envelope, but the 0.43 W
  armed-idle budget and sustained NPU behavior require direct measurement.
- **Eight-hour operation:** high-risk acceptance gate. The nominal model passes
  narrowly, while reasonable external sensitivity cases fail.
- **Selection:** retain the base S23 only provisionally and only if it is the
  cheapest candidate passing exact ASR/MT/TTS, memory, thermal, battery and audio
  tests. If only energy fails, test the exact-hosted S23+ before paying for a
  newer NPU generation; if compute fails, move to the Snapdragon S24 SKU.
