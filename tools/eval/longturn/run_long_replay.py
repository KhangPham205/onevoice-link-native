#!/usr/bin/env python3
"""Replay long recordings through the on-device live capture path.

Each fixture in <fixtures>/manifest.json is pushed into the app's private
storage and replayed with `cmd=replay`: the file replaces the microphone, and
the production segmenter, long-turn coordinator, TTS and half-duplex playback
run unchanged. Every (fixture, baseline, repeat) is one run tagged
`<id>__<baseline>__r<n>`; runs are interleaved so no baseline always runs on a
cooler phone. Bench rows (files/bench/turns) and logcat are pulled to --out.

Baselines (docs/LONG_FORM_SPEECH_IMPLEMENTATION.md section 13.1):
  B0 legacy 10 s turns, B1 fixed windows + overlap with MT/TTS after the turn,
  B2 adaptive windows with MT/TTS after the turn, B3 adaptive + background MT/TTS.

Prerequisites: a debuggable build installed, models staged, files/bench
created, and the Hybrid pipeline loaded (--init taps "Load Hybrid Pipeline").
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

PACKAGE = "com.onevoice.link"
ACTION = "com.onevoice.link.BENCH"
RECEIVER = f"{PACKAGE}/.core.BenchControlReceiver"
DEVICE_DIR = f"/data/user/0/{PACKAGE}/files/bench/longturn"
LOG_TAGS = ["OneVoiceNpu", "OneVoiceRouting", "OneVoiceLongTurn", "OneVoiceHalfDuplex",
            "OneVoiceBenchCtl", "OneVoiceQNN", "AndroidRuntime"]


class Adb:
    def __init__(self, serial: str):
        self.serial = serial

    def run(self, *args: str, check: bool = True, timeout: float = 120) -> str:
        result = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, text=True,
                                timeout=timeout)
        if check and result.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.strip()}")
        return result.stdout

    def shell(self, command: str, check: bool = True) -> str:
        return self.run("shell", command, check=check)

    def bench(self, **extras: str) -> None:
        args = ["shell", "am", "broadcast", "-a", ACTION, "-n", RECEIVER]
        for key, value in extras.items():
            args += ["--es", key, str(value)]
        self.run(*args)

    def logcat_dump(self) -> str:
        return self.run("logcat", "-d", "-v", "threadtime", "-s", *[f"{tag}:*" for tag in LOG_TAGS],
                        check=False)


def push(adb: Adb, local: Path, name: str) -> None:
    adb.run("push", str(local), f"/data/local/tmp/{name}")
    adb.shell(f"cat /data/local/tmp/{name} | run-as {PACKAGE} sh -c 'cat > files/bench/longturn/{name}'")
    adb.shell(f"rm /data/local/tmp/{name}")


def tap_text(adb: Adb, text: str) -> bool:
    adb.shell("uiautomator dump /sdcard/onevoice_ui.xml", check=False)
    xml = adb.shell("cat /sdcard/onevoice_ui.xml", check=False)
    match = re.search(rf'text="{re.escape(text)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    if not match:
        return False
    x1, y1, x2, y2 = map(int, match.groups())
    adb.shell(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
    return True


def ensure_pipeline(adb: Adb, init: bool) -> None:
    # stayon only holds while charging; an unplugged phone that locks pauses
    # the activity, which stops the session (see the screen timeout note).
    adb.shell("svc power stayon true")
    adb.shell("input keyevent KEYCODE_WAKEUP")
    adb.shell("wm dismiss-keyguard", check=False)
    adb.shell(f"am start -n {PACKAGE}/.MainActivity")
    time.sleep(2)
    if not init:
        return
    adb.run("logcat", "-c", check=False)
    for _ in range(10):
        if tap_text(adb, "Load Hybrid Pipeline"):
            break
        time.sleep(2)
    else:
        print("Load button not found; assuming the pipeline is already loaded")
        return
    deadline = time.time() + 180
    while time.time() < deadline:
        log = adb.logcat_dump()
        if "Auto Hybrid pipeline ready" in log:
            return
        if "NPU pipeline init failed" in log:
            raise RuntimeError("pipeline init failed; see logcat")
        time.sleep(3)
    raise RuntimeError("pipeline did not become ready within 180 s")


def total_pss_kb(adb: Adb) -> int | None:
    """App PSS from dumpsys; DSP-private memory is not in it."""
    output = adb.shell(f"dumpsys meminfo {PACKAGE}", check=False)
    match = re.search(r"TOTAL PSS:\s+(\d+)", output) or re.search(r"^\s*TOTAL\s+(\d+)", output, re.M)
    return int(match.group(1)) if match else None


def wait_replay(adb: Adb, file_name: str, timeout_s: float) -> str | None:
    deadline = time.time() + timeout_s
    pattern = f"replay done file={file_name}"
    while time.time() < deadline:
        log = adb.logcat_dump()
        if pattern in log:
            return log
        if f"replay {file_name}" in log and "accepted=false" in log:
            return None
        time.sleep(3)
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--serial", required=True)
    parser.add_argument("--fixtures", type=Path, required=True, help="directory with manifest.json and wavs")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--baselines", nargs="+", default=["B1", "B3"])
    parser.add_argument("--mode", choices=["paced", "fast"], default="paced")
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--ids", nargs="*", help="subset of fixture ids")
    parser.add_argument("--init", action="store_true", help='tap "Load Hybrid Pipeline" first')
    parser.add_argument("--settle", type=float, default=3.0, help="seconds between runs")
    parser.add_argument("--soft-split-ms", type=int, help="override the soft split for this sweep")
    parser.add_argument("--unit-tokens", help='VI word-gap unit targets "first,later" (e.g. 24,40)')
    parser.add_argument("--run-id", default=time.strftime("%m%d%H%M"),
                        help="prefix of every tag, so rows of earlier runs never mix in")
    args = parser.parse_args()

    manifest = json.loads((args.fixtures / "manifest.json").read_text())
    items = [item for item in manifest["items"] if not args.ids or item["id"] in args.ids]
    if not items:
        print("no fixtures selected", file=sys.stderr)
        return 2
    adb = Adb(args.serial)
    out = args.out
    (out / "raw").mkdir(parents=True, exist_ok=True)
    adb.shell(f"run-as {PACKAGE} mkdir -p files/bench/longturn")
    for item in items:
        push(adb, args.fixtures / item["file"], item["file"])
    ensure_pipeline(adb, args.init)

    plan = []
    for repeat in range(args.repeats):
        for index, item in enumerate(items):
            # Rotate the baseline order per item and repeat (balanced order).
            shift = (index + repeat) % len(args.baselines)
            for baseline in args.baselines[shift:] + args.baselines[:shift]:
                plan.append((item, baseline, repeat))

    runs = []
    for number, (item, baseline, repeat) in enumerate(plan, 1):
        tag = f"{args.run_id}_{item['id']}__{baseline}__r{repeat}"
        adb.run("logcat", "-c", check=False)
        thermal = adb.shell("dumpsys thermalservice | grep -m1 'Thermal Status'", check=False).strip()
        pss_before = total_pss_kb(adb)
        started = time.time()
        extras = {"softSplitMs": args.soft_split_ms} if args.soft_split_ms else {}
        if args.unit_tokens:
            extras["unitTokens"] = args.unit_tokens
        adb.bench(cmd="replay", path=f"{DEVICE_DIR}/{item['file']}", mode=args.mode,
                  baseline=baseline, tag=tag, **extras)
        timeout = item["durationS"] * (2 if args.mode == "fast" else 5) + 180
        log = wait_replay(adb, item["file"], timeout)
        wall = time.time() - started
        status = "done" if log else "timeout_or_refused"
        if log is None:
            log = adb.logcat_dump()
            adb.bench(cmd="stop")
            time.sleep(5)
        (out / "raw" / f"{tag}.log").write_text(log)
        runs.append({"tag": tag, "id": item["id"], "baseline": baseline, "repeat": repeat,
                     "mode": args.mode, "status": status, "wallS": round(wall, 1),
                     "thermalBefore": thermal, "pssKbBefore": pss_before, "pssKbAfter": total_pss_kb(adb),
                     "thermalAfter": adb.shell("dumpsys thermalservice | grep -m1 'Thermal Status'",
                                               check=False).strip()})
        print(f"[{number}/{len(plan)}] {tag}: {status} in {wall:.0f} s")
        time.sleep(args.settle)

    # Bench rows: every replay turn, tagged <run tag>_t<turnId>.
    listing = adb.shell(f"run-as {PACKAGE} ls files/bench/turns", check=False).split()
    with open(out / "turns.jsonl", "w", encoding="utf-8") as sink:
        for name in listing:
            if name.endswith(".jsonl"):
                sink.write(adb.run("exec-out", "run-as", PACKAGE, "cat", f"files/bench/turns/{name}"))
    (out / "runs.json").write_text(json.dumps({"manifest": str(args.fixtures / "manifest.json"),
                                              "serial": args.serial, "runs": runs}, indent=2))
    print(f"wrote {out}/runs.json and {out}/turns.jsonl")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
