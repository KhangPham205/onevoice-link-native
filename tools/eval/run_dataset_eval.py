#!/usr/bin/env python3
"""Run the organisers' sample dataset through the on-device pipeline, offline.

Every wav goes through the production dual-ASR router (``direction=auto``),
the selected Marian branch and the APK's TTS (Kokoro EN; VieNeu VI by
default), exactly as a live turn would, except
that the PCM comes from the dataset instead of the microphone. The app writes
one JSONL row per item (routing scores, stage timings, transcript,
translation) plus the 24 kHz target waveform; this script collects them with
raw logcat, memory samples, model sizes and proof of network disconnection.

Scoring is a separate step (score_dataset_eval.py) so a run on the device is
never repeated just to change a metric.

Prerequisites: debuggable build installed, models pushed, Hybrid pipeline
loaded in the UI. Usage::

    python3 tools/eval/run_dataset_eval.py --serial R5CW72TP4EP \
        --dataset "Vie-Eng dataset" --out eval-results/run1
"""
from __future__ import annotations

import argparse
import io
import json
import os
import re
import subprocess
import sys
import tarfile
import time
import wave
from datetime import datetime
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from dataset import load_items  # noqa: E402

# ONEVOICE_PACKAGE selects a side-by-side debug install (-PdebugAppIdSuffix);
# class names keep the base package, so components are spelled out in full.
PACKAGE = os.environ.get("ONEVOICE_PACKAGE", "com.onevoice.link")
RECEIVER = f"{PACKAGE}/com.onevoice.link.core.BenchControlReceiver"
ACTIVITY = f"{PACKAGE}/com.onevoice.link.MainActivity"
ACTION = "com.onevoice.link.BENCH"
ADB = os.environ.get("ADB", "adb")
LOG_TAGS = ("OneVoiceRouting OneVoiceNpu OneVoiceNpuPipeline OneVoiceBench OneVoiceBenchCtl "
            "OneVoiceHalfDuplex OneVoiceQNN OneVoiceCore OneVoiceSession "
            "OneVoiceVieNeu OneVoiceSeaG2p").split()


class Adb:
    def __init__(self, serial: str) -> None:
        self.serial = serial

    def run(self, *args: str, timeout: float = 120, check: bool = True,
            binary: bool = False) -> str | bytes:
        proc = subprocess.run([ADB, "-s", self.serial, *args], capture_output=True,
                              timeout=timeout, stdin=subprocess.DEVNULL)
        if check and proc.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)}: {proc.stderr.decode(errors='replace')}")
        return proc.stdout if binary else proc.stdout.decode(errors="replace")

    def shell(self, command: str, **kw) -> str:
        return self.run("shell", command, **kw)

    def run_as(self, command: str, **kw) -> str:
        return self.shell(f"run-as {PACKAGE} sh -c '{command}'", **kw)

    def bench(self, **extras: str | bool) -> None:
        parts = [f"am broadcast -a {ACTION} -n {RECEIVER}"]
        for key, value in extras.items():
            if isinstance(value, bool):
                parts.append(f"--ez {key} {'true' if value else 'false'}")
            else:
                parts.append(f"--es {key} '{value}'")
        self.shell(" ".join(parts))


def wav_to_f32(path: Path) -> bytes:
    """16-bit PCM wav -> little-endian float32, the capture loop's representation."""
    with wave.open(str(path)) as w:
        if (w.getframerate(), w.getnchannels(), w.getsampwidth()) != (16000, 1, 2):
            raise ValueError(f"{path}: expected 16 kHz mono 16-bit PCM")
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")
    return (pcm.astype(np.float32) / 32768.0).astype("<f4").tobytes()


def offline_evidence(adb: Adb) -> dict[str, str]:
    """Network state as the OS reports it, kept verbatim for the submission."""
    def safe(cmd: str) -> str:
        try:
            return adb.shell(cmd, timeout=30).strip()
        except Exception as exc:  # evidence collection must never abort the run
            return f"<error: {exc}>"
    return {
        "timestamp": datetime.now().isoformat(timespec="seconds"),
        "airplane_mode_on": safe("settings get global airplane_mode_on"),
        "wifi_status": safe("cmd wifi status | head -3"),
        "active_network": safe("dumpsys connectivity | grep -m3 -iE 'Active default network|NetworkAgentInfo'"),
        "ping_8_8_8_8": safe("ping -c 1 -W 2 8.8.8.8 2>&1 | tail -2"),
        "apk_internet_permission": safe(
            # grep -c exits 1 on zero matches; zero is the expected answer.
            f"dumpsys package {PACKAGE} | grep -c 'android.permission.INTERNET' || true"),
    }


def memory_sample(adb: Adb, pid: int, label: str) -> dict[str, object]:
    """
    dumpsys PSS/RSS plus /proc VmHWM and fault counters.

    dumpsys, not ActivityManager.getProcessMemoryInfo: the latter is rate
    limited for app callers and returned an unchanged PSS across a 1 GB RSS
    jump on this device. majflt shows whether latency is measuring paging.
    """
    sample: dict[str, object] = {"label": label, "t": time.time()}
    # Android 16 prints "TOTAL PSS: n  TOTAL RSS: n  TOTAL SWAP PSS: n" on one line.
    dump = adb.shell(f"dumpsys meminfo {PACKAGE}", timeout=90)
    for label, key in (("TOTAL PSS", "pss_kb"), ("TOTAL RSS", "rss_kb"),
                       ("TOTAL SWAP PSS", "swap_pss_kb")):
        m = re.search(rf"{label}:\s+(\d+)", dump)
        if m:
            sample[key] = int(m.group(1))
    status = adb.shell(f"cat /proc/{pid}/status")
    for key, field in (("vm_rss_kb", "VmRSS"), ("vm_hwm_kb", "VmHWM"), ("vm_swap_kb", "VmSwap")):
        m = re.search(rf"{field}:\s+(\d+)", status)
        if m:
            sample[key] = int(m.group(1))
    stat = adb.shell(f"cat /proc/{pid}/stat").split()
    if len(stat) > 11:
        sample["majflt"] = int(stat[11])
    meminfo = adb.shell("cat /proc/meminfo")
    for key, field in (("mem_total_kb", "MemTotal"), ("mem_available_kb", "MemAvailable")):
        m = re.search(rf"{field}:\s+(\d+)", meminfo)
        if m:
            sample[key] = int(m.group(1))
    return sample


def model_sizes(adb: Adb) -> list[dict[str, object]]:
    """Every staged model file with its size, as the app sees it."""
    out = adb.run_as("cd files/models && find . -type f -exec stat -c \"%s %n\" {} +")
    rows = []
    for line in out.splitlines():
        size, _, name = line.strip().partition(" ")
        if size.isdigit():
            rows.append({"path": name.removeprefix("./"), "bytes": int(size)})
    return sorted(rows, key=lambda r: r["path"])


def screenshot(adb: Adb, path: Path) -> None:
    """Main display only: a Flip has a cover screen and screencap needs -d."""
    displays = adb.shell("dumpsys SurfaceFlinger --display-id", check=False)
    match = re.search(r"Display (\d+)", displays)
    args = ["exec-out", "screencap", "-p"] + (["-d", match.group(1)] if match else [])
    path.write_bytes(adb.run(*args, binary=True))


def has_row(adb: Adb, tag: str) -> bool:
    """
    Wait on the item's own tag, never on a line count: one late row (a 181 s
    TTS stall in the first run) otherwise shifts every later wait by one and
    the harness feeds the next item while the previous one still runs.
    """
    out = adb.run_as(f"grep -c \\\"tag\\\":\\\"{tag}\\\" files/bench/turns/*.jsonl 2>/dev/null",
                     check=False)
    return any(part.strip().split(":")[-1] not in ("", "0") for part in out.splitlines())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--serial", required=True)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=0, help="items per direction (0 = all)")
    parser.add_argument("--timeout", type=float, default=90.0, help="seconds per item")
    parser.add_argument("--straggler-timeout", type=float, default=300.0,
                        help="extra seconds to wait for a late row before aborting")
    parser.add_argument("--airplane", choices=["enable", "require", "ignore"], default="enable",
                        help="enable airplane mode, only verify it, or skip the check")
    parser.add_argument("--restore-network", action="store_true",
                        help="disable airplane mode again after the run")
    parser.add_argument("--allow-screen-off", action="store_true",
                        help="do not keep the display on (measures the dozing regime)")
    args = parser.parse_args()

    adb = Adb(args.serial)
    out = args.out
    (out / "raw").mkdir(parents=True, exist_ok=True)
    items = load_items(args.dataset, limit=args.limit)
    # Alternate VI and EN items: the product premise is two people taking
    # turns, and the router's soft prior expects the other language next. The
    # scorer also reports a score-only decision so the prior's help is visible.
    vi = [i for i in items if i.source_lang == "vi"]
    en = [i for i in items if i.source_lang == "en"]
    order = [x for pair in zip(vi, en) for x in pair] + vi[len(en):] + en[len(vi):]

    pid_text = adb.shell(f"pidof {PACKAGE}", check=False).strip()
    if not pid_text:
        print("app is not running: launch it and load the Hybrid pipeline first")
        return 2
    pid = int(pid_text.split()[0])
    adb.run_as("mkdir -p files/bench/dataset files/bench/turns files/bench/tts")

    # Stage every fixture in one transfer; run-as can read /data/local/tmp.
    print(f"staging {len(order)} fixtures")
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w") as tar:
        for item in order:
            data = wav_to_f32(item.wav)
            info = tarfile.TarInfo(f"{item.id}.f32")
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    local_tar = out / "raw" / "dataset_f32.tar"
    local_tar.write_bytes(buf.getvalue())
    adb.run("push", str(local_tar), "/data/local/tmp/onevoice_dataset.tar", timeout=300)
    adb.run_as("cd files/bench/dataset && tar xf /data/local/tmp/onevoice_dataset.tar")
    adb.shell("rm -f /data/local/tmp/onevoice_dataset.tar")
    local_tar.unlink()

    if args.airplane == "enable":
        adb.shell("cmd connectivity airplane-mode enable")
        time.sleep(5)
    evidence_before = offline_evidence(adb)
    print("offline evidence:", json.dumps(evidence_before, ensure_ascii=False))
    if args.airplane != "ignore" and evidence_before["airplane_mode_on"] != "1":
        print("airplane mode is not on; refusing to record an 'offline' run")
        return 3
    screenshot(adb, out / "raw" / "offline_before.png")

    # Keep the display on, as in a live demo. When the screen times out the
    # phone dozes, caps CPU frequency (1.55-1.98 GHz observed) and Kokoro ran
    # ~4x slower in the first run; that is a different operating condition,
    # measured only with --allow-screen-off.
    stay_on_before = adb.shell("settings get global stay_on_while_plugged_in").strip()
    if not args.allow_screen_off:
        # "true", not "usb": a USB-PD fast charger reports AC, and the usb-only
        # setting let the screen time out mid-run on this phone.
        adb.shell("svc power stayon true")
        adb.shell("input keyevent KEYCODE_WAKEUP")
        # Launcher intent, as a tap on the icon: it brings the existing task to
        # the front. `am start -n` created a second MainActivity, whose fresh
        # NpuEngine had no pipeline, and every feed was refused.
        adb.shell(f"monkey -p {PACKAGE} -c android.intent.category.LAUNCHER 1", check=False)
        time.sleep(2)

    # Fresh tagged log: close the writer first, then delete closed files.
    adb.bench(cmd="newlog")
    time.sleep(1)
    adb.run_as("rm -f files/bench/turns/*.jsonl files/bench/tts/*.f32")

    adb.run("logcat", "-c", check=False)
    logcat_file = open(out / "raw" / "logcat.txt", "w", encoding="utf-8")
    logcat = subprocess.Popen(
        [ADB, "-s", args.serial, "logcat", "-v", "threadtime", "-s",
         *[f"{t}:*" for t in LOG_TAGS]],
        stdout=logcat_file, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)

    memory = [memory_sample(adb, pid, "before")]
    thermal_before = adb.shell("dumpsys thermalservice | grep -m1 'Thermal Status'", check=False)
    started = time.time()
    lost: list[str] = []
    cpu_caps: list[str] = []
    try:
        for index, item in enumerate(order, 1):
            adb.bench(cmd="feed", path=f"/data/user/0/{PACKAGE}/files/bench/dataset/{item.id}.f32",
                      direction="auto", tag=item.id)
            deadline = time.monotonic() + args.timeout
            while time.monotonic() < deadline and not has_row(adb, item.id):
                time.sleep(0.5)
            if not has_row(adb, item.id):
                lost.append(item.id)
                print(f"  ! {item.id}: no row within {args.timeout:.0f}s")
                # Never overlap: wait a straggler out before the next feed, but
                # give up on a feed the app refused (pipeline not loaded).
                straggler_deadline = time.monotonic() + args.straggler_timeout
                while not has_row(adb, item.id) and time.monotonic() < straggler_deadline:
                    time.sleep(1.0)
                if not has_row(adb, item.id):
                    print(f"  ! {item.id}: still no row; is the Hybrid pipeline loaded? aborting")
                    break
            # The row is written after playback drains, so the speaker is free;
            # a short settle lets AudioTrack release before the next feed.
            time.sleep(0.5)
            if not args.allow_screen_off and index % 20 == 0:
                cpu_caps.append(adb.shell(
                    "cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_max_freq | tr '\\n' ' '",
                    check=False).strip())
            if index % 20 == 0 or index == len(order):
                memory.append(memory_sample(adb, pid, f"after_{index}"))
                elapsed = time.time() - started
                print(f"  {index}/{len(order)}  {elapsed / 60:.1f} min")
    finally:
        time.sleep(1)
        logcat.terminate()
        logcat_file.close()
        wakefulness = adb.shell("dumpsys power | grep -m1 mWakefulness=", check=False).strip()
        adb.shell(f"settings put global stay_on_while_plugged_in {stay_on_before or 0}", check=False)

    evidence_after = offline_evidence(adb)
    screenshot(adb, out / "raw" / "offline_after.png")
    thermal_after = adb.shell("dumpsys thermalservice | grep -m1 'Thermal Status'", check=False)

    # exec-out: `adb shell` on Windows turned every "\n" into "\r\r\n".
    turns = adb.run("exec-out", f"run-as {PACKAGE} sh -c 'cat files/bench/turns/*.jsonl'",
                    binary=True)
    (out / "turns.jsonl").write_bytes(turns)
    # exec-out, not shell: binary-safe, no pty line-ending translation.
    tts_tar = adb.run("exec-out", f"run-as {PACKAGE} sh -c 'cd files/bench/tts && tar cf - *.f32'",
                      binary=True, timeout=300)
    (out / "raw" / "tts_f32.tar").write_bytes(tts_tar)

    sizes = model_sizes(adb)
    apk = adb.shell(f"pm path {PACKAGE}").strip().removeprefix("package:")
    apk_bytes = int(adb.shell(f"stat -c %s {apk}").strip() or 0)
    meta = {
        "started": datetime.fromtimestamp(started).isoformat(timespec="seconds"),
        "wall_minutes": round((time.time() - started) / 60, 2),
        "items": len(order),
        "lost": lost,
        "order": "alternating vi/en",
        "screen_kept_on": not args.allow_screen_off,
        "wakefulness_at_end": wakefulness,
        "cpu_max_freq_khz_every_20": cpu_caps,
        "device": {
            "model": adb.shell("getprop ro.product.model").strip(),
            "soc": adb.shell("getprop ro.soc.model").strip(),
            "android": adb.shell("getprop ro.build.version.release").strip(),
            "serial": args.serial,
        },
        "app_version": re.search(r"versionName=(\S+)",
                                 adb.shell(f"dumpsys package {PACKAGE}")).group(1),
        "offline_before": evidence_before,
        "offline_after": evidence_after,
        "thermal_before": thermal_before.strip(),
        "thermal_after": thermal_after.strip(),
        "memory": memory,
        "apk_bytes": apk_bytes,
        "model_files": sizes,
    }
    (out / "run_meta.json").write_text(json.dumps(meta, indent=2, ensure_ascii=False),
                                       encoding="utf-8")
    if args.restore_network:
        adb.shell("cmd connectivity airplane-mode disable")
    print(f"done: {len(order) - len(lost)}/{len(order)} rows, lost={lost} -> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
