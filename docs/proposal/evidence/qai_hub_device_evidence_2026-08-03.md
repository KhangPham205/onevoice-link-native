# Qualcomm AI Hub Hosted-Device Evidence — 3 August 2026

## Evidence boundary

This note records the device rows relevant to OneVoice's phone shortlist from
the Hosted Devices text exported by the team from Qualcomm AI Hub Workbench. It
is a transcription audit of that supplied export, not a new live API result.

- Source attachment SHA-256:
  `9779bcaa4a0a5e3daab402e264b7b82cb435cc0d5e975233badba237c8571ab1`
- Official device-selection documentation:
  <https://workbench.aihub.qualcomm.com/docs/hub/devices.html>
- Qualcomm's rule: a device name without `(Family)` or `(Proxy)` runs on the
  exact hosted device. Family targets may run on any listed family variant;
  Proxy targets use substitute hardware and cannot provide exact-device
  performance evidence.

## Relevant exact commercial-phone rows

| Device name in supplied export | Chipset / model | OS | QNN | FP16 | HTP |
|---|---|---:|:---:|:---:|---:|
| Samsung Galaxy S21 | Snapdragon 888 / SM8350 | Android 11/12 | Yes | No | v68 |
| Samsung Galaxy S22 5G | Snapdragon 8 Gen 1 / SM8450 | Android 12/13 | Yes | Yes | v69 |
| Samsung Galaxy S23 | Snapdragon 8 Gen 2 / SM8550 | Android 13 | Yes | Yes | v73 |
| Samsung Galaxy S23+ | Snapdragon 8 Gen 2 / SM8550 | Android 13 | Yes | Yes | v73 |
| Samsung Galaxy S24 | Snapdragon 8 Gen 3 / SM8650 | Android 14 | Yes | Yes | v75 |
| Samsung Galaxy S25 | Snapdragon 8 Elite for Galaxy / SM8750-AC | Android 15 | Yes | Yes | v79 |
| Samsung Galaxy S26 | Snapdragon 8 Elite Gen 5 for Galaxy / SM8850-AD | Android 16 | Yes | Yes | v81 |

The supplied export also includes a Snapdragon 7 Gen 4 **QRD** row, but not a
vivo V60 or another exact commercial phone using that chipset. A Qualcomm
Reference Design is therefore excluded from the procurement shortlist and its
measurements must not be relabeled as results for a retail phone.

## Related model and power audit

Hosted-device availability does not imply that every model publishes a
performance row for that device. The version-pinned ASR coverage, NPU claims,
independent QNN checks and whole-phone power/thermal evidence are therefore kept
in the separate
[NPU/ASR/power audit](npu_asr_power_evidence_2026-08-03.md). In particular, the
current Whisper-Small-Quantized snapshot lists S23 as supported but does not
publish a normalized S23 performance row; the proposal does not interpolate
one.

## Reproducible refresh before submission

The catalog is mutable. Regenerate the list and save a dated output immediately
before the hardware freeze:

```bash
qai-hub list-devices
```

For the final benchmark, resolve the exact device name and OS rather than using
a family or proxy target:

```python
import qai_hub as hub

client = hub.Client()
devices = client.get_devices(name="Samsung Galaxy S23", os="13")
print(devices)
```

Do not place a Workbench API token in this evidence file, the repository, build
logs or the deployed APK. Store credentials only in the SDK's supported local
credential mechanism or a secret manager.
