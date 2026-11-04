# Dataset evaluation

Runs the organisers' Round 3 sample dataset (100 VI→EN + 100 EN→VI wavs)
through the real on-device pipeline with the network disabled, then scores it
on the workstation.

```bash
pip install -r tools/eval/requirements.txt   # see the torch note inside

# 1. Phone: debuggable build installed, models pushed, Hybrid loaded in the UI.
adb -s R5CW72TP4EP shell run-as com.onevoice.link mkdir -p files/bench

# 2. Device run (~30 min). Enables airplane mode and refuses to record an
#    "offline" run if it is not on; adb over USB keeps working.
python3 tools/eval/run_dataset_eval.py --serial R5CW72TP4EP \
    --dataset "Vie-Eng dataset" --out eval-results/<run>

# 3. Scoring (downloads COMET wmt22-comet-da and Whisper large-v3-turbo once).
python3 tools/eval/score_dataset_eval.py eval-results/<run> --dataset "Vie-Eng dataset"
```

Each item is fed with `direction=auto`, so the production dual-ASR router picks
the branch; items alternate VI/EN like two people taking turns, which is what
the router's soft turn prior expects (the scorer also reports a score-only
routing figure). The app writes one JSONL row per item (`files/bench/turns/`,
same row format as the Live demo session log) and the 24 kHz target waveform
(`files/bench/tts/<id>.f32`) only for tagged fixtures, after every timing is
final.

The run measures whatever the installed APK was built with: routing thresholds,
VAD and the Vietnamese TTS engine (`-PviTtsEngine`, VieNeu by default) are
recorded in the header row of `turns.jsonl`. To compare TTS engines, build and
run one APK per engine on the same dataset.

Output: `turns.jsonl` (device rows), `run_meta.json` (offline evidence, memory
samples, model file sizes, device info), `raw/logcat.txt`, `raw/offline_*.png`,
`raw/tts_f32.tar`, then `metrics.json` and `per_item.csv` from the scorer.
`score_dataset_eval.py`'s docstring defines every metric and the text
normalisation. The current reference results (routing, WER, BLEU/chrF++/COMET,
TTS intelligibility, latency, memory) are written up in
`docs/proposal/evidence/P3_ket-qua-do_2026-11-02.md`.
