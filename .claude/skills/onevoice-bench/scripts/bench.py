#!/usr/bin/env python3
"""Drive a OneVoice benchmark run on a connected device and emit CSV + JSON.

Design notes that matter for reading the output:

* Fixtures, not the microphone. Every iteration must see identical audio or the
  percentiles describe the room instead of the pipeline.
* Cohort order is randomised and cohorts are separated by a cooldown, so a
  thermal trend cannot masquerade as a difference between them.
* Profiling is a cohort of its own. A QNN profile handle costs real time inside
  graphExecute, so latency gates are read from the unprofiled cohort and
  accelerator evidence from the profiled one.
* No mean is reported anywhere; see gates.summarize.
"""
from __future__ import annotations

import argparse
import csv
import json
import random
import re
import sys
import time
from dataclasses import dataclass, asdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from device import BENCH_DIR, Device, DeviceError, PACKAGE  # noqa: E402
import gates  # noqa: E402

SKILL_ROOT = Path(__file__).resolve().parent.parent
FIXTURES = SKILL_ROOT / "fixtures"

BENCH_ROW = re.compile(r"OneVoiceBench:\s+bench row:\s+(?P<row>.+)$")
QNN_ROW = re.compile(
    r"qnn stage=(?P<stage>\S+)\s+wall_us=(?P<wall>\d+)\s+execute_us=(?P<exec>\d+)\s+"
    r"accel_us=(?P<accel>\d+)\s+queue_wait_us=(?P<queue>\d+)\s+"
    r"host_overhead_us=(?P<host>\d+)")
MEMORY_ROW = re.compile(r"OneVoiceBenchCtl:\s+memory\s+(?P<kv>.+)$")
LOWMEM_ROW = re.compile(r"lowmem killed_previously=(?P<killed>\w+)")

CSV_COLUMNS = [
    "cohort", "iteration", "fixture",
    "direction", "route_source", "speaker_language",
    "speech_duration_s", "forced_boundary", "execution_path",
    "asr_ms", "mt_ms", "tts_ms", "queue_wait_ms",
    "ttfa_ms", "e2e_ms",
    "hangover_ms", "echo_guard_ms", "e2e_minus_hangover_ms",
    "mt_output_tokens", "tts_output_chars", "tts_output_audio_s",
    "asr_rtf", "tts_rtf",
    "thermal_status", "power_mode",
    "underruns", "frames_requested", "frames_written",
    # Energy stays in the schema so Phase B needs no migration, and stays empty
    # so nobody mistakes a charging artefact for a measurement.
    "energy_valid", "charge_counter_delta_uah", "avg_power_w",
    "energy_j", "energy_per_utterance_j", "energy_per_audio_s_j",
]

# Matches UtteranceMetrics.CSV_HEADER on the device side, minus the three
# columns bench.py prepends.
DEVICE_FIELDS = CSV_COLUMNS[3:28]


@dataclass
class Cohort:
    name: str
    profiling: bool
    power_mode: str


def parse_bench_row(line: str) -> dict[str, str] | None:
    match = BENCH_ROW.search(line)
    if not match:
        return None
    parts = [p.strip() for p in match.group("row").split(",")]
    if len(parts) != len(DEVICE_FIELDS):
        return None
    return dict(zip(DEVICE_FIELDS, parts))


def parse_memory(line: str) -> dict[str, int]:
    match = MEMORY_ROW.search(line)
    if not match:
        return {}
    out: dict[str, int] = {}
    for token in match.group("kv").split():
        key, _, value = token.partition("=")
        if value.lstrip("-").isdigit():
            out[key] = int(value)
    return out


class Harness:
    def __init__(self, device: Device, args: argparse.Namespace) -> None:
        self.device = device
        self.args = args
        self.rows: list[dict[str, object]] = []
        self.qnn_samples: list[dict[str, object]] = []
        self.rss_series: list[float] = []
        self.memory_before: dict[str, int] = {}
        self.memory_after: dict[str, int] = {}
        self.low_memory_kill: bool | None = None
        self.energy_valid = False
        self.energy_reason = ""
        self.soak_windows: list[float] = []
        self.fixture_seconds: dict[str, float] = {}
        self.paging_before: dict[str, int] = {}
        self.paging_after: dict[str, int] = {}

    # ------------------------------------------------------------- preflight
    def preflight(self) -> int:
        soc = self.device.soc_model()
        if soc != "SM8550":
            raise DeviceError(
                f"expected SM8550 (Hexagon v73); got {soc!r}. The context "
                "binaries are AOT-compiled for v73 and will not load."
            )
        pid = self.device.app_pid()
        if pid is None:
            raise DeviceError(
                f"{PACKAGE} is not running. Launch it and load the Hybrid "
                "pipeline before benchmarking."
            )
        ready = self.device.wait_for_log(pid, r"Auto Hybrid pipeline ready", timeout=3)
        if ready is None:
            print("  ! no 'pipeline ready' line in the recent log; "
                  "assuming it was loaded earlier in this process")
        self._classify_energy()
        return pid

    def _classify_energy(self) -> None:
        """
        Decide whether an energy measurement could mean anything, and record why.

        Plugged in, the PMIC supplies the system rail straight from VBUS. The
        current through the cell then reflects charging, not workload, so the
        honest outcome is no number at all rather than a number with a caveat.
        """
        battery = self.device.battery()
        plugged = battery.get("AC_powered") or battery.get("USB_powered") or 0
        current = battery.get("current_now_ua", 0)
        if plugged:
            self.energy_reason = "device is plugged in (Phase A)"
        elif current > 0:
            self.energy_reason = f"battery current is positive ({current} uA): charging"
        else:
            # Even unplugged, Phase A does not claim energy numbers: the sign
            # and unit of CURRENT_NOW must be calibrated per device first.
            self.energy_reason = "Phase A does not collect energy; see SKILL.md"
        self.energy_valid = False

    # ------------------------------------------------------------- execution
    def stage_fixtures(self) -> list[tuple[str, Path]]:
        chosen: list[tuple[str, Path]] = []
        for path in sorted(FIXTURES.glob("*.f32")):
            # A Vietnamese fixture drives the vi-en branch and vice versa: the
            # filename names the language spoken, the direction names where it
            # is translated to.
            if self.args.direction == "vi-en" and not path.name.startswith("vi_"):
                continue
            if self.args.direction == "en-vi" and not path.name.startswith("en_"):
                continue
            # Float32 at 16 kHz. Recorded so run_one can match a bench row to
            # the fixture that produced it rather than to whichever row appears
            # next in the log.
            self.fixture_seconds[path.name] = (
                path.stat().st_size / 4 / 16000.0)
            remote = f"{BENCH_DIR}/{path.name}"
            self.device._run(["push", str(path), f"/data/local/tmp/{path.name}"])
            self.device.shell(
                f"run-as {PACKAGE} cp /data/local/tmp/{path.name} "
                f"files/bench/{path.name}")
            chosen.append((path.name, Path(remote)))
        if not chosen:
            raise DeviceError(f"no fixtures matched direction={self.args.direction}")
        return chosen

    def wait_until_idle(self, pid: int, timeout: float = 40.0) -> None:
        """
        Block until playback has finished and the queue has drained.

        AudioTrack keeps the speaker busy after the bench row is written, and
        the capture-side queue holds two utterances. Feeding into either state
        overlaps utterances and corrupts both queue_wait_ms and tts_ms.
        """
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            recent = self.device.logcat_since(
                pid, r"AudioTrack (stereo )?(start|stop)", lines=40)
            if not recent or " stop " in recent[-1]:
                # Small settle so the track is released, not merely stopped.
                time.sleep(0.4)
                return
            time.sleep(0.4)
        print("    ! pipeline still busy after wait_until_idle timeout")

    def apply_cohort(self, cohort: Cohort) -> None:
        self.device.bench(cmd="power", mode=cohort.power_mode,
                          latency_us=self.args.rpc_latency_us,
                          polling_us=self.args.rpc_polling_us)
        time.sleep(1.0)
        self.device.bench(cmd="profiling", on=cohort.profiling)
        time.sleep(1.0)

    def run_one(self, pid: int, remote: Path, cohort: Cohort,
                iteration: int, fixture: str) -> dict[str, object] | None:
        """
        Feed one fixture and collect exactly its row.

        Two hazards this has to avoid, both observed during bring-up:

        * The inference queue holds two utterances. Feeding without waiting for
          the previous one to finish overlaps them, and the queue wait then
          lands inside the next row's timings — a smoke run produced
          queue_wait_ms=25007 and tts_ms=81496 that way.
        * Playback continues after the row is logged. Starting the next feed
          during it makes AudioTrack contention part of the measurement.

        So: drain first, feed, then match the row *by the fixture's own sample
        count* rather than taking whatever bench row appears next.
        """
        expected_seconds = self.fixture_seconds.get(fixture)
        self.wait_until_idle(pid)
        self.device.clear_log()
        # "auto" sends the fixture through the production router, so a run
        # also measures dual-ASR latency and can be scored for misroutes.
        # Otherwise the filename's language forces its own branch.
        if self.args.routing == "auto":
            direction = "auto"
        else:
            direction = "vi-en" if fixture.startswith("vi_") else "en-vi"
        self.device.bench(cmd="feed", path=str(remote), direction=direction)

        deadline = time.monotonic() + self.args.utterance_timeout
        parsed: dict[str, str] | None = None
        while time.monotonic() < deadline:
            for line in self.device.logcat_since(pid, r"OneVoiceBench:\s+bench row:"):
                candidate = parse_bench_row(line)
                if candidate is None:
                    continue
                if expected_seconds is None:
                    parsed = candidate
                    break
                try:
                    measured = float(candidate["speech_duration_s"])
                except (KeyError, ValueError):
                    continue
                # 20 ms of slack covers the frame quantisation in the capture
                # path; anything further apart is a different utterance.
                if abs(measured - expected_seconds) <= 0.02:
                    parsed = candidate
                    break
            if parsed:
                break
            time.sleep(0.3)

        if parsed is None:
            print(f"    ! no matching bench row for {fixture} "
                  f"(expected {expected_seconds}s, timeout)")
            return None
        row: dict[str, object] = {
            "cohort": cohort.name, "iteration": iteration, "fixture": fixture,
            **parsed,
            "energy_valid": "false",
            "charge_counter_delta_uah": "", "avg_power_w": "",
            "energy_j": "", "energy_per_utterance_j": "", "energy_per_audio_s_j": "",
        }
        if cohort.profiling:
            self.device.bench(cmd="qnn")
            time.sleep(0.6)
            for log_line in self.device.logcat_since(pid, r"qnn stage="):
                match = QNN_ROW.search(log_line)
                if match:
                    self.qnn_samples.append({
                        "cohort": cohort.name, "iteration": iteration,
                        "stage": match.group("stage"),
                        "wall_us": int(match.group("wall")),
                        "execute_us": int(match.group("exec")),
                        "accelerator_us": int(match.group("accel")),
                        "queue_wait_us": int(match.group("queue")),
                        "host_overhead_us": int(match.group("host")),
                    })
        return row

    def sample_memory(self, pid: int) -> dict[str, int]:
        self.device.clear_log()
        self.device.bench(cmd="memory")
        time.sleep(1.0)
        for line in self.device.logcat_since(pid, r"OneVoiceBenchCtl:\s+memory"):
            values = parse_memory(line)
            if values:
                return values
        return {}

    def check_low_memory(self, pid: int) -> None:
        self.device.clear_log()
        self.device.bench(cmd="lowmem")
        time.sleep(1.0)
        for line in self.device.logcat_since(pid, r"lowmem killed_previously="):
            match = LOWMEM_ROW.search(line)
            if match:
                self.low_memory_kill = match.group("killed") == "true"
                return

    # ------------------------------------------------------------------ soak
    def assert_soak_preconditions(self) -> None:
        """
        Refuse a soak the battery would contaminate.

        Charging heat and workload heat are indistinguishable in the p95 drift
        this gate measures, so a run started while the cell is still absorbing
        current cannot answer the question it was started to answer. Abort with
        the reason rather than produce a number nobody can defend.
        """
        battery = self.device.battery()
        level = battery.get("level", 0)
        current = abs(battery.get("current_now_ua", 0))
        problems = []
        if level < 99:
            problems.append(f"battery at {level}% (need ≥99%)")
        if current > 50_000:  # 50 mA
            problems.append(f"charge current {current / 1000:.0f} mA has not settled")
        if problems:
            raise DeviceError(
                "soak refused: " + "; ".join(problems) +
                ". Charging heat is indistinguishable from workload heat, so "
                "the drift measurement would be meaningless."
            )

    def run_soak(self, pid: int, fixtures: list[tuple[str, Path]]) -> list[float]:
        """Run for --soak minutes; return per-window p95 of ttfa_ms."""
        self.assert_soak_preconditions()
        cohort = Cohort("soak", profiling=False, power_mode=self.args.power_mode)
        self.apply_cohort(cohort)
        print(f"  warm-up x{self.args.warmup}")
        for i in range(self.args.warmup):
            name, remote = fixtures[i % len(fixtures)]
            self.run_one(pid, remote, cohort, -1, name)

        window_s = 600.0  # 10 minutes, per §5.1 item 4
        deadline = time.monotonic() + self.args.soak * 60.0
        windows: list[float] = []
        current: list[float] = []
        window_end = time.monotonic() + window_s
        iteration = 0
        while time.monotonic() < deadline:
            name, remote = fixtures[iteration % len(fixtures)]
            row = self.run_one(pid, remote, cohort, iteration, name)
            if row:
                self.rows.append(row)
                try:
                    current.append(float(row["ttfa_ms"]))
                except (KeyError, ValueError):
                    pass
            iteration += 1
            if iteration % 5 == 0:
                memory = self.sample_memory(pid)
                if "rss_kb" in memory:
                    self.rss_series.append(memory["rss_kb"] / 1024.0)
            if time.monotonic() >= window_end:
                if current:
                    p95 = gates.percentile(current, 95)
                    windows.append(p95)
                    zones = self.device.thermal_zones()
                    hottest = max(zones.values()) / 1000.0 if zones else float("nan")
                    print(f"  window {len(windows)}: p95 {p95:.0f} ms "
                          f"(n={len(current)}, hottest zone {hottest:.1f}°C)")
                current = []
                window_end = time.monotonic() + window_s
        if current:
            windows.append(gates.percentile(current, 95))
        return windows

    # ------------------------------------------------------------------ main
    def run(self) -> None:
        pid = self.preflight()
        print(f"device {self.device.serial} pid={pid} soc=SM8550")
        print(f"energy: disabled — {self.energy_reason}")

        fixtures = self.stage_fixtures()
        print(f"fixtures: {', '.join(name for name, _ in fixtures)}")

        self.check_low_memory(pid)
        self.memory_before = self.device.meminfo()
        self.paging_before = self.device.paging(pid)

        if self.args.soak:
            print(f"\n=== soak {self.args.soak} min "
                  f"(10-minute p95 windows) ===")
            self.soak_windows = self.run_soak(pid, fixtures)
            self.memory_after = self.device.meminfo()
            self.write(self.sample_memory(pid))
            return

        cohorts = [
            Cohort("baseline", profiling=False, power_mode=self.args.power_mode),
            Cohort("profiled", profiling=True, power_mode=self.args.power_mode),
        ]
        if self.args.no_profile_cohort:
            cohorts = cohorts[:1]
        random.Random(self.args.seed).shuffle(cohorts)

        for cohort in cohorts:
            print(f"\n=== cohort {cohort.name} "
                  f"(profiling={cohort.profiling}, power={cohort.power_mode}) ===")
            self.apply_cohort(cohort)

            # Ten, not three: the HTP governor ramps over the first several
            # executions, and a short warm-up leaves that ramp inside the
            # measured population.
            print(f"  warm-up x{self.args.warmup}")
            for i in range(self.args.warmup):
                name, remote = fixtures[i % len(fixtures)]
                self.run_one(pid, remote, cohort, -1, name)

            print(f"  measuring x{self.args.iterations}")
            for iteration in range(self.args.iterations):
                name, remote = fixtures[iteration % len(fixtures)]
                row = self.run_one(pid, remote, cohort, iteration, name)
                if row:
                    self.rows.append(row)
                if iteration % 5 == 0:
                    memory = self.sample_memory(pid)
                    if "rss_kb" in memory:
                        self.rss_series.append(memory["rss_kb"] / 1024.0)
            print(f"  cooldown {self.args.cooldown}s")
            time.sleep(self.args.cooldown)

        self.memory_after = self.device.meminfo()
        self.paging_after = self.device.paging(pid)
        final_memory = self.sample_memory(pid)
        self.write(final_memory)

    def write(self, final_memory: dict[str, int]) -> None:
        out = Path(self.args.out)
        out.mkdir(parents=True, exist_ok=True)

        csv_path = out / "bench.csv"
        with csv_path.open("w", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=CSV_COLUMNS)
            writer.writeheader()
            for row in self.rows:
                writer.writerow({key: row.get(key, "") for key in CSV_COLUMNS})

        baseline = [r for r in self.rows if r["cohort"] == "baseline"] or self.rows
        results = gates.evaluate(
            rows=baseline,
            qnn_samples=self.qnn_samples,
            memory=final_memory,
            low_memory_kill=self.low_memory_kill,
            rss_series=self.rss_series,
            soak_windows=self.soak_windows,
        )

        dmabuf_before = self.memory_before.get("dmabuf_kb")
        dmabuf_after = self.memory_after.get("dmabuf_kb")
        # The fixture filename names the language actually spoken, so a routed
        # run doubles as a misroute check. Lost rows (router rejections) are
        # reported by run_one and are not counted as misroutes here.
        routed = [r for r in self.rows if r.get("route_source") != "explicit"]
        misroutes = [r for r in routed
                     if not str(r["fixture"]).startswith(f"{r.get('speaker_language')}_")]
        summary = {
            "device": self.device.serial,
            "routing": {
                "mode": self.args.routing,
                "routed_rows": len(routed),
                "misroutes": len(misroutes),
                "misrouted_fixtures": sorted({str(r["fixture"]) for r in misroutes}),
                "reasons": {reason: sum(1 for r in routed if r.get("route_source") == reason)
                            for reason in sorted({str(r.get("route_source")) for r in routed})},
            },
            "soc": "SM8550",
            "iterations": self.args.iterations,
            "warmup": self.args.warmup,
            "power_mode": self.args.power_mode,
            "energy": {"valid": False, "reason": self.energy_reason},
            "latency": {
                column: gates.summarize(
                    [float(r[column]) for r in baseline
                     if str(r.get(column, "")).replace(".", "", 1).isdigit()])
                for column in ("asr_ms", "mt_ms", "tts_ms",
                               "ttfa_ms", "e2e_ms", "queue_wait_ms")
            },
            "memory": final_memory,
            "dmabuf_kb_before": dmabuf_before,
            "dmabuf_kb_after": dmabuf_after,
            "dmabuf_delta_kb": (
                None if dmabuf_before is None or dmabuf_after is None
                else dmabuf_after - dmabuf_before),
            "qnn_samples": len(self.qnn_samples),
            "paging_before": self.paging_before,
            "paging_after": self.paging_after,
            "majflt_delta": (
                self.paging_after.get("majflt", 0)
                - self.paging_before.get("majflt", 0)),
            "soak_windows": self.soak_windows,
            "gates": [asdict(r) | {"verdict": r.verdict} for r in results],
        }
        (out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        if self.qnn_samples:
            with (out / "qnn.csv").open("w", newline="") as handle:
                writer = csv.DictWriter(handle, fieldnames=list(self.qnn_samples[0]))
                writer.writeheader()
                writer.writerows(self.qnn_samples)

        passed, failed, skipped = gates.overall(results)
        print(f"\nwrote {csv_path} ({len(self.rows)} rows)")
        print(f"gates: {passed} pass, {failed} fail, {skipped} not evaluated")
        for result in results:
            print(f"  [{result.verdict:4}] {result.gate} {result.title}: "
                  f"{result.observed} (need {result.threshold})")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=None)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--iterations", type=int, default=30)
    parser.add_argument("--warmup", type=int, default=10)
    parser.add_argument("--cooldown", type=float, default=20.0)
    parser.add_argument("--power-mode", default="burst",
                        choices=["burst", "sustained_high_performance", "balanced"])
    parser.add_argument("--rpc-latency-us", type=int, default=100)
    parser.add_argument("--rpc-polling-us", type=int, default=9999)
    parser.add_argument("--direction", default="both",
                        choices=["both", "vi-en", "en-vi"])
    parser.add_argument("--utterance-timeout", type=float, default=40.0)
    parser.add_argument("--routing", choices=["auto", "explicit"], default="auto",
                        help="auto = production router picks the branch; "
                             "explicit = force the fixture language's branch")
    parser.add_argument("--no-profile-cohort", action="store_true",
                        help="skip the profiled cohort (faster, no G3 evidence)")
    parser.add_argument("--seed", type=int, default=1234,
                        help="cohort shuffle seed; fixed so a run is reproducible")
    parser.add_argument("--soak", type=float, default=0,
                        help="run a soak for N minutes instead of cohorts "
                             "(requires a settled battery)")
    parser.add_argument("--out", default="bench-results")
    args = parser.parse_args()

    try:
        device = Device.sole_device(adb=args.adb, serial=args.serial)
        Harness(device, args).run()
    except DeviceError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("\ninterrupted", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
