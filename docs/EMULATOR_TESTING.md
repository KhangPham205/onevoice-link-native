# Emulator testing (retired) and the HuggingFace CPU assets

> [!WARNING]
> **The app no longer runs on the Android emulator.** The APK is built for
> `arm64-v8a` only, the single user-facing backend is Hybrid (QNN on Hexagon
> v73, Snapdragon 8 Gen 2), and there is no backend selector or direction
> toggle. `hf/HfEngineBackend.kt`, the former CPU-only emulator backend, is
> still in the tree but nothing instantiates it. Test on an SM8550 device.
> Without a Bluetooth headset, pick the **Phone** audio route on the Dashboard
> (built-in mic and loudspeaker); it runs the full pipeline.

The HuggingFace models this rig was built around did not go away. They are now
**cold CPU recovery assets** and host-side files for the Hybrid pipeline, staged
by `tools/models/push-npu-models.sh` from `models-hf/`:

| `models-hf/` source | Staged as | Used for |
|---|---|---|
| `asr-vi/` (Parakeet-CTC-0.6B-Vietnamese, int8) | `vi-en/fallback/asr/` | CPU recovery ASR, VI |
| `asr-en/` (NeMo CTC conformer-small EN, int8) | `en-vi/fallback/asr/` | CPU recovery ASR, EN |
| `mt-vi-en/`, `mt-en-vi/` (opus-mt, ONNX int8) | `<dir>/fallback/mt/` and the Marian host tokenizers | CPU recovery MT; SentencePiece/vocab for the HTP Marian |
| `tts-en/` (kokoro-en-v0_19) | `vi-en/tts_{cpu.onnx,voices.bin,tokens.txt}` | Live Kokoro EN |
| `tts-vi/` (Kokoro-Vietnamese) | `en-vi/tts_{cpu.onnx,voices.bin}` | Kokoro VI when built with `-PviTtsEngine=KOKORO`; its token table is `tools/models/kokoro-vi-espeak-tokens.txt`, not the converted one |
| `espeak-ng-data/` | `<dir>/espeak-ng-data/` | Kokoro phonemizer |

CPU recovery runs only after a selected direction's HTP Marian path throws, and
it restarts ASR+MT from the original bounded PCM (`core/CpuFallbackBranch.kt`).
See `docs/ARCHITECTURE.md`.

## Producing `models-hf/`

Run once on the workstation; the results are git-ignored.

```powershell
# sherpa-onnx AAR (the app does not compile without it), espeak-ng data,
# ready-made asr-en and tts-en
powershell -ExecutionPolicy Bypass -File tools/models/fetch-sherpa-assets.ps1

# Source checkpoints into models-src/ (~3.4 GB)
$env:PYTHONUTF8=1; $env:HF_HOME="$PWD\models-src\.hf-cache"
huggingface-cli download nvidia/parakeet-ctc-0.6b-Vietnamese --local-dir models-src/parakeet-ctc-0.6b-vi --exclude "lm_4gram*"
huggingface-cli download Helsinki-NLP/opus-mt-vi-en --local-dir models-src/opus-mt-vi-en --exclude "tf_model.h5"
huggingface-cli download Helsinki-NLP/opus-mt-en-vi --local-dir models-src/opus-mt-en-vi --exclude "tf_model.h5"
huggingface-cli download contextboxai/Kokoro-Vietnamese --local-dir models-src/kokoro-vietnamese --exclude "kokoro_vi.pth"

# Conversion
powershell -ExecutionPolicy Bypass -File tools/models/setup-venv.ps1
tools\models\.venv\Scripts\python.exe tools\models\convert_marian.py --int8
tools\models\.venv\Scripts\python.exe tools\models\convert_marian.py --int8 --direction en-vi
tools\models\.venv\Scripts\python.exe tools\models\convert_kokoro.py

powershell -ExecutionPolicy Bypass -File tools/models/setup-venv.ps1 -WithNemo  # +nemo_toolkit, several GB
tools\models\.venv\Scripts\python.exe tools\models\convert_parakeet.py --int8
```

`convert_parakeet.py` writes `models-hf/asr/` and `convert_kokoro.py` writes
`models-hf/tts/`; the staging script reads `models-hf/asr-vi/` and
`models-hf/tts-vi/`, so rename those two directories after converting. Then
stage everything onto the phone with
`tools/models/push-npu-models.sh auto <serial>` (see `docs/NPU_DEPLOY.md`).

## Notes that still apply

- sherpa-onnx aborts the whole process (uncatchable from Kotlin) when an ONNX
  model lacks its `metadata_props`; the convert scripts write them
  (`convert_parakeet.py --meta-only` patches an existing export).
- The Parakeet 4-gram LM (`lm_4gram*`, 174 MB) is excluded; greedy CTC does not
  use it.
- `models-src/` and `models-hf/` are git-ignored; never commit them.
- `android:pageSizeCompat="enabled"` in the manifest dates from the x86_64
  emulator libs; the arm64-v8a libs are 16 KB aligned.
