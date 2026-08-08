# Deploying the NPU cascade to a device

How to get the QNN context binaries onto a Snapdragon 8 Gen 2 phone and confirm
they load on the Hexagon HTP. This is the **load-and-verify** path: it proves the
artifacts match the chip. It does not translate speech — see
[§ What this does not do](#what-this-does-not-do).

---

## 0. Prerequisites

| Thing | Requirement |
|---|---|
| Phone | Snapdragon 8 Gen 2 — `ro.soc.model` = `SM8550`, `ro.board.platform` = `kalama`, Hexagon **v73** |
| Build | the **debug** APK (the push script needs `run-as`) |
| Binaries | `onevoice-npu-bins/` at the repo root (~5.9 GB, delivered out of band, git-ignored) |
| SDK | QAIRT vendored at `native-core/third_party/qnn_sdk` (2.47.0 here; the `.bin` were built with 2.45.0) |
| Toolchain | Android NDK `27.2.12479018` + CMake `3.22.1` |

Verify the chip before anything else — a `.bin` compiled for another Hexagon
generation cannot load, and finding that out after pushing 2 GB is a waste:

```bash
adb shell getprop ro.soc.model      # expect SM8550
adb shell getprop ro.board.platform # expect kalama
```

`push-npu-models.sh` runs this check itself and refuses to continue on a
mismatch (override with `FORCE=1` only if you know why).

---

## 1. Build and install

```bash
cd android-app
./gradlew :app:assembleDebug
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

If Gradle picks a JRE without `jlink` (the VS Code Java extension ships one),
point it at a real JDK:

```bash
JAVA_HOME=/path/to/jdk-21 ./gradlew --no-daemon :app:assembleDebug
```

---

## 2. Push the model cascade

```bash
tools/models/push-npu-models.sh all     # or: vi-en / en-vi
```

Roughly 2 GB, a couple of minutes. Re-running skips files already present with a
matching size, so it is safe to repeat.

### Why the script does a two-step copy

It pushes to `/data/local/tmp` and then `run-as`-copies into the app's **internal**
`filesDir`, rather than pushing straight to external storage. That is not
belt-and-braces; external storage genuinely does not work here:

> On Android 11+ an app cannot see files under its own
> `Android/data/<pkg>/files/` that a different uid created. Measured on this
> device (Android 16 / One UI): after `adb push`, `adb shell` lists the `.bin`
> files, the path is byte-identical to the one the app computes, and the app
> still gets `exists() == false`. Chowning the whole tree to the app's uid does
> not help either.

The app looks in both locations (`NpuEngine.candidateRoots`) and uses whichever
actually holds models, so an external-storage layout still works on devices
where the OS permits it.

---

## 3. Load and verify

Open **OneVoice Link** → tap **Load Model Cascade**. Each stage reports
separately, because loading is three distinct checks per `.bin`:

1. `QnnSystemContext_getBinaryInfo` — parse the blob, read the graph name, the
   I/O contract and the `socModel` it was built for. No hardware involved.
2. `contextCreateFromBinary` — place the graphs on the HTP. **This is the step
   that fails on the wrong Hexagon arch.**
3. `graphRetrieve(graphName)` — confirm the graph identity.

Watch it from the host with:

```bash
adb logcat -s OneVoiceNpu:* OneVoiceQNN:* OneVoiceCore:*
```

### Graph names are not guessable

Qualcomm AI Hub generates them (`graph_08xlh5ri`, `opus_mt_encoder`,
`graph_5l17io1h`, …) and they are **not** recorded in the `metadata.json` shipped
beside each binary. `graphRetrieve` fails on any mismatch, so the app ships
`assets/npu_manifest.json`, generated from the binaries themselves:

```bash
python3 tools/models/gen_npu_manifest.py
```

Regenerate it whenever the model team delivers new `.bin` files.

---

## 4. Direction coverage

| Direction | Stages | Status |
|---|---|---|
| **VI → EN** | ASR + MT enc/dec + TTS enc/dec | 5 stages, ~1.53 GB — complete |
| **EN → VI** | MT enc/dec + TTS enc/dec | 4 stages, ~419 MB — **cannot run** |

EN→VI is disabled in the UI: the package ships **Vietnamese ASR only**, so there
is no English `.bin` to start that cascade (HANDOFF.md §4 says the same). Its
MT/TTS halves are still pushed and can be load-verified. When an English ASR
binary arrives, add it to `gen_npu_manifest.py`, regenerate the asset, and flip
`available` to `true`.

---

## Known blocker: the DSP will not open for an app on retail firmware

On a retail **SM-F731B** (SM8550 / Hexagon v73, One UI, Android 16) every stage
fails at `contextCreateFromBinary` with `0x36b1` = **14001** =
`QNN_MIN_ERROR_DEVICE + 1` — a *device-domain* error, so the DSP never came up.
The artifacts are fine; this is a platform policy.

`tools/models/smoke-test-npu.sh` isolates it by running Qualcomm's own
`qnn-net-run` twice on the same `.bin`, same libs, same unsigned skel, changing
only the uid:

| Run as | Result |
|---|---|
| `shell` | `Finished Executing Graphs` — 3 output tensors, sizes matching the contract (`[1,512]`, `[1,512,640]`, `[1,512,512]`) |
| app uid (`run-as`) | `Device Creation failure` |

So the chip, the skel, the QAIRT runtime and the binaries all work on this
phone — an **untrusted app** just cannot open the Hexagon DSP here. Supporting
evidence:

- `/dev/adsprpc-smd` is `crw-rw-r-- system:system u:object_r:vendor_qdsp_device`
  — an app uid gets read-only, and `untrusted_app` is not granted that type.
  SELinux is `Enforcing`; the denials are `dontaudit`'d, so logcat stays silent.
- The firmware ships `libSnpeHtpV73Skel.so` but no `libQnnHtpV73Skel.so`.
- Relaxing permissions along the app's skel path (`700`→`711`/`755`/`644`)
  changes nothing, so it is not a filesystem-permission problem.

Things that were ruled out along the way, all fixed in the code:

- `dlopen` by absolute path fails because `useLegacyPackaging = false` keeps the
  `.so` inside the APK — the runtime now opens by soname first.
- `deviceCreate` returning `0x36b1` is survivable; `contextCreateFromBinary`
  accepts a null device.
- The app cannot see `Android/data/<pkg>/files` content written by another uid,
  which is why models are staged into internal `filesDir`.

### Ways forward

1. **Signed Hexagon skel** — the vendored QAIRT SDK only carries
   `lib/hexagon-v73/unsigned/`. A skel signed for retail devices comes from
   Qualcomm under license; with it, an ordinary app can open a signed PD.
2. **A device that permits unsigned PD** — an engineering/`userdebug` build, or
   a dev board with the DSP unlocked.
3. **Run through a vendor-provided path** — e.g. ONNX Runtime's QNN EP as
   HANDOFF.md §6 option B, which some OEM images allow where raw QNN does not.
4. **Interim demo** — drive `qnn-net-run` from `adb shell` to show the models
   executing on the NPU, while the app shows load-and-verify status.

---

## What this does not do

`QnnRuntime::executeCascade()` returns `false` and the audio worker drops every
chunk. Running the pipeline needs host-glue that does not exist for Android yet:

- **ASR** — 80-mel fbank (16 kHz, win 400, hop 160, n_fft 512, preemph 0.97) then
  CTC greedy: argmax → collapse repeats → drop blank (id 1024) → detokenize.
- **MT** — SentencePiece, plus a hand-written autoregressive decode loop driving
  a 27-input / 13-output decoder and threading `present_self` → `past_self` each
  token until EOS.
- **TTS** — G2P, alignment scatter to `asr[1,512,25]`, F0/N, `compute_har` →
  `har[1,22,3001]`, style vector from a voicepack, then bucketed decoder calls
  and overlap-add.

Reference implementations exist only in Python on the model side (HANDOFF.md
§3–§5, §8). `Start Capture` therefore opens the microphone and produces no audio
output — deliberately, rather than emitting silence that looks like a working
pipeline.

---

## Troubleshooting

Postmortem cho sự cố `0x36b1` đã khắc phục:
[FastRPC và Android linker policy](incidents/2026-08-08-qnn-0x36b1-fastrpc-linker-policy.md).

| Symptom | Cause |
|---|---|
| `dlopen ... libQnnHtp.so not found` | `useLegacyPackaging = false` keeps the `.so` inside the APK, so absolute-path `dlopen` fails. The runtime opens by soname first — make sure you are on a current build. |
| `libcdsprpc.so ... not found` | App target API 31+ is missing `<uses-native-library android:name="libcdsprpc.so">`; the vendor FastRPC library is outside the app linker namespace by default. |
| `deviceCreate/contextCreateFromBinary failed (0x36b1)` | `QNN_DEVICE_ERROR_INVALID_CONFIG`. Inspect the preceding QNN/FastRPC lines; it can be a transport, PD config, skel lookup or version issue and does **not** alone prove that signing is required. |
| non-device-domain `contextCreateFromBinary failed` | Check Hexagon arch (for example an `8elite` binary on an 8 Gen 2) and whether the QAIRT runtime is older than the binary. |
| `graphRetrieve("…") failed; binary contains "…"` | Stale manifest — rerun `gen_npu_manifest.py`. |
| every stage `file not on device` | Models were pushed to external storage; see § 2. |
