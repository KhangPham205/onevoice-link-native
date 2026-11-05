#!/usr/bin/env python3
"""Synthesize the English recording scripts with accented neural TTS voices.

A stand-in for real English speakers: each script in recording_scripts.py is
read by a Microsoft Edge neural voice with a different English accent (and one
Vietnamese voice reading English, the accent Parakeet was chosen for), then
post-processed for its recording condition:
  fast        +20% speaking rate
  soft        -15 dB
  noise       pink (fan-like) noise mixed at --noise-snr-db
  continuous  pauses longer than 150 ms shortened to 150 ms
Every other file has its pauses capped at --max-pause-ms: Edge inserts
0.8-1.1 s of digital silence at sentence ends, which ends a turn (1 s
hangover) where a person's breath and room noise would not.
Every file gets a quiet noise floor and 1.2 s / 1.5 s lead and tail, like a
phone recording, so no frame is digital silence.

TTS speech is cleaner and more regular than a person, so this checks
mechanics and accents; it is not real-speaker held-out evidence. The script
text is sent to the Edge TTS service. Output: <out>/enXX.wav (16 kHz mono)
plus notes.txt naming the voice, ready for `recording_scripts.py prepare`.

Requires: pip install edge-tts numpy; ffmpeg on PATH.
"""
from __future__ import annotations

import argparse
import asyncio
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from recording_scripts import SCRIPTS  # noqa: E402

RATE = 16_000

# One accent per script; vi-VN-NamMinh reads English with a Vietnamese accent
# (vi-VN-HoaiMy refuses English text).
VOICES = {
    "en01": "en-US-GuyNeural",
    "en02": "vi-VN-NamMinhNeural",
    "en03": "en-GB-SoniaNeural",
    "en04": "en-IN-NeerjaNeural",
    "en05": "en-AU-NatashaNeural",
    "en06": "vi-VN-NamMinhNeural",
    "en07": "en-SG-WayneNeural",
    "en08": "en-PH-RosaNeural",
    "en09": "en-NG-AbeoNeural",
    "en10": "en-ZA-LukeNeural",
    "en11": "vi-VN-NamMinhNeural",
    "en12": "en-HK-YanNeural",
}


async def synthesize(text: str, voice: str, rate: str, path: Path) -> None:
    import edge_tts

    # The service intermittently returns no audio (seen most with the vi-VN
    # voice reading English); the same request succeeds on a retry.
    for attempt in range(6):
        try:
            await edge_tts.Communicate(text, voice, rate=rate).save(str(path))
            return
        except edge_tts.exceptions.NoAudioReceived:
            if attempt == 5:
                raise
            await asyncio.sleep(2 * (attempt + 1))


def decode(path: Path) -> np.ndarray:
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(path), "-ac", "1", "-ar", str(RATE),
                          "-f", "s16le", "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.int16).astype(np.float32) / 32768.0


def pink_noise(samples: int, rng: np.random.Generator) -> np.ndarray:
    spectrum = np.fft.rfft(rng.standard_normal(samples))
    freqs = np.fft.rfftfreq(samples, 1.0 / RATE)
    spectrum /= np.sqrt(np.maximum(freqs, 20.0))
    noise = np.fft.irfft(spectrum, samples)
    return noise / np.sqrt(np.mean(noise ** 2))



def shorten_pauses(x: np.ndarray, keep_ms: int = 150, threshold_db: float = -45.0) -> np.ndarray:
    frame = RATE // 100
    frames = len(x) // frame
    level = 20 * np.log10(np.sqrt(np.mean(x[:frames * frame].reshape(frames, frame) ** 2, axis=1)) + 1e-9)
    quiet = level < threshold_db
    keep = keep_ms // 10
    out, run = [], 0
    for index in range(frames):
        run = run + 1 if quiet[index] else 0
        if run <= keep:
            out.append(x[index * frame:(index + 1) * frame])
    return np.concatenate(out)


def speech_rms(x: np.ndarray) -> float:
    frame = RATE // 50
    frames = x[:len(x) // frame * frame].reshape(-1, frame)
    levels = np.sqrt(np.mean(frames ** 2, axis=1))
    return float(np.sqrt(np.mean(levels[levels > np.percentile(levels, 50)] ** 2)))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--noise-snr-db", type=float, default=10.0)
    parser.add_argument("--max-pause-ms", type=int, default=600)
    parser.add_argument("--ids", nargs="*")
    parser.add_argument("--seed", type=int, default=20261105)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(args.seed)
    notes = []
    for script in SCRIPTS:
        script_id, condition = script["id"], script["condition"]
        if script["language"] != "en" or (args.ids and script_id not in args.ids):
            continue
        voice = VOICES[script_id]
        mp3 = args.out / f"{script_id}.mp3"
        asyncio.run(synthesize(script["text"], voice, "+20%" if condition == "fast" else "+0%", mp3))
        speech = decode(mp3)
        mp3.unlink()
        speech = shorten_pauses(speech, 150 if condition == "continuous" else args.max_pause_ms)
        if condition == "soft":
            speech *= 10 ** (-15 / 20)
        signal = np.concatenate([np.zeros(int(1.2 * RATE)), speech, np.zeros(int(1.5 * RATE))])
        floor = pink_noise(len(signal), rng) * speech_rms(speech) * 10 ** (-50 / 20)
        if condition == "noise":
            floor = pink_noise(len(signal), rng) * speech_rms(speech) * 10 ** (-args.noise_snr_db / 20)
        mixed = signal + floor
        peak = np.max(np.abs(mixed))
        if peak > 0.89:
            mixed *= 0.89 / peak
        with wave.open(str(args.out / f"{script_id}.wav"), "wb") as handle:
            handle.setnchannels(1)
            handle.setsampwidth(2)
            handle.setframerate(RATE)
            handle.writeframes((np.clip(mixed, -1, 1) * 32767).astype(np.int16).tobytes())
        detail = f"edge-tts {voice}, {condition}" + (f", SNR {args.noise_snr_db:g} dB" if condition == "noise" else "")
        notes.append(f"{script_id}: {detail}")
        print(f"{script_id}: {len(mixed) / RATE:.1f} s  {detail}")
    (args.out / "notes.txt").write_text("\n".join(notes) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
