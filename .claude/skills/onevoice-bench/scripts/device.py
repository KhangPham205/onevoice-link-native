"""adb plumbing shared by the benchmark scripts.

Everything that talks to the phone lives here so the orchestrator stays readable
and so the safety rules (no battery mocking, fail loudly on a missing device)
are stated exactly once.
"""
from __future__ import annotations

import re
import shutil
import subprocess
import time
from dataclasses import dataclass, field

PACKAGE = "com.onevoice.link"
RECEIVER = f"{PACKAGE}/.core.BenchControlReceiver"
ACTION = "com.onevoice.link.BENCH"
FILES_DIR = f"/data/user/0/{PACKAGE}/files"
BENCH_DIR = f"{FILES_DIR}/bench"

# `dumpsys battery unplug` only mocks the framework's view. The PMIC keeps
# charging and the fuel gauge keeps reporting a current that has nothing to do
# with system load, so any energy number derived after it is fabricated. The
# constant exists to be asserted against, never to be run.
FORBIDDEN_COMMANDS = ("battery unplug", "battery set", "battery reset")


class DeviceError(RuntimeError):
    """Raised for an unusable device, so callers fail fast instead of hanging."""


@dataclass
class Device:
    serial: str
    adb: str = "adb"
    _log_cursor: str = field(default="", repr=False)

    # ---------------------------------------------------------------- basics
    def __post_init__(self) -> None:
        if shutil.which(self.adb) is None:
            raise DeviceError(f"adb not found on PATH as {self.adb!r}")

    def _run(self, args: list[str], timeout: float = 60.0) -> str:
        joined = " ".join(args)
        for banned in FORBIDDEN_COMMANDS:
            if banned in joined:
                raise DeviceError(
                    f"refusing to run {banned!r}: it mocks the battery layer "
                    "while the hardware keeps charging, which produces "
                    "fabricated energy numbers"
                )
        proc = subprocess.run(
            [self.adb, "-s", self.serial, *args],
            capture_output=True, text=True, timeout=timeout,
            stdin=subprocess.DEVNULL,
        )
        if proc.returncode != 0:
            raise DeviceError(f"adb {joined} failed: {proc.stderr.strip()}")
        return proc.stdout

    def shell(self, command: str, timeout: float = 60.0) -> str:
        return self._run(["shell", command], timeout=timeout)

    @classmethod
    def sole_device(cls, adb: str = "adb", serial: str | None = None) -> "Device":
        """Resolve exactly one device, or explain why we cannot proceed."""
        if shutil.which(adb) is None:
            raise DeviceError(f"adb not found on PATH as {adb!r}")
        out = subprocess.run([adb, "devices"], capture_output=True, text=True,
                             timeout=30, stdin=subprocess.DEVNULL).stdout
        ready = [line.split()[0] for line in out.splitlines()[1:]
                 if line.strip().endswith("device")]
        if serial:
            if serial not in ready:
                raise DeviceError(
                    f"{serial} is not in 'device' state. Attached: {ready or 'none'}"
                )
            return cls(serial=serial, adb=adb)
        if not ready:
            raise DeviceError("no device in 'device' state — check USB/authorisation")
        if len(ready) > 1:
            raise DeviceError(f"multiple devices attached: {ready}; pass --serial")
        return cls(serial=ready[0], adb=adb)

    # ------------------------------------------------------------ app control
    def app_pid(self) -> int | None:
        out = self.shell(f"pidof {PACKAGE}").strip()
        return int(out.split()[0]) if out else None

    def bench(self, **extras: object) -> None:
        """Send one benchmark command; extras become typed intent extras."""
        parts = [f"am broadcast -a {ACTION} -n {RECEIVER}"]
        for key, value in extras.items():
            if isinstance(value, bool):
                parts.append(f"--ez {key} {'true' if value else 'false'}")
            elif isinstance(value, int):
                parts.append(f"--ei {key} {value}")
            else:
                parts.append(f"--es {key} '{value}'")
        self.shell(" ".join(parts))

    def logcat_since(self, pid: int, pattern: str, lines: int = 400) -> list[str]:
        """Recent log lines for one pid matching a regex."""
        raw = self.shell(f"logcat -d --pid={pid} -t {lines}", timeout=90)
        rx = re.compile(pattern)
        return [line for line in raw.splitlines() if rx.search(line)]

    def clear_log(self) -> None:
        try:
            self._run(["logcat", "-c"])
        except DeviceError:
            pass  # a full ring buffer is not a reason to abort a run

    # -------------------------------------------------------------- telemetry
    def soc_model(self) -> str:
        return self.shell("getprop ro.soc.model").strip()

    def battery(self) -> dict[str, int]:
        """Charging state and instantaneous current, for the energy gate."""
        out = self.shell("dumpsys battery")
        values: dict[str, int] = {}
        for line in out.splitlines():
            if ":" not in line:
                continue
            key, _, value = line.strip().partition(":")
            value = value.strip()
            if value.lstrip("-").isdigit():
                values[key.strip().replace(" ", "_")] = int(value)
        # Optional: SELinux denies the shell domain this node on some retail
        # builds, and adb reports that as a non-zero exit rather than an empty
        # read. Absence of the reading is not a reason to abort a latency run —
        # it only means the energy classifier falls back to the plugged flag.
        try:
            current = self.shell(
                "cat /sys/class/power_supply/battery/current_now"
            ).strip()
            if current.lstrip("-").isdigit():
                values["current_now_ua"] = int(current)
        except DeviceError:
            pass
        return values

    def thermal_zones(self) -> dict[str, int]:
        """
        Zone temperatures read over adb.

        The app cannot read these itself: SELinux denies the app domain access
        to /sys/class/thermal on retail Samsung firmware, while the shell domain
        is permitted. The in-app AThermal_* probe reports throttling *status*;
        this supplies the actual millidegrees.
        """
        out = self.shell(
            "for z in /sys/class/thermal/thermal_zone*; do "
            "echo \"$(cat $z/type 2>/dev/null) $(cat $z/temp 2>/dev/null)\"; done"
        )
        zones: dict[str, int] = {}
        for line in out.splitlines():
            parts = line.split()
            if len(parts) == 2 and parts[1].lstrip("-").isdigit():
                zones[parts[0]] = int(parts[1])
        return zones

    def meminfo(self) -> dict[str, int]:
        """
        `dumpsys meminfo -d` for the app, including the dmabuf line.

        dmabuf is the reason for the -d flag: DSP-side allocations never appear
        in PSS, so the delta across context loading is the only visible evidence
        of memory held on behalf of the HTP.
        """
        out = self.shell(f"dumpsys meminfo -d {PACKAGE}", timeout=90)
        result: dict[str, int] = {}
        for line in out.splitlines():
            stripped = line.strip()
            for label, key in (
                ("TOTAL PSS:", "pss_kb"),
                ("TOTAL RSS:", "rss_kb"),
                ("Native Heap:", "native_heap_kb"),
                ("Java Heap:", "java_heap_kb"),
            ):
                if stripped.startswith(label):
                    numbers = re.findall(r"\d+", stripped)
                    if numbers:
                        result[key] = int(numbers[0])
            if "dmabuf" in stripped.lower():
                numbers = re.findall(r"\d+", stripped)
                if numbers:
                    result["dmabuf_kb"] = max(int(n) for n in numbers)
        # Verified absent on this target (SM-F731B / Android 16): `dumpsys
        # meminfo -d` emits no dmabuf line at all, /sys/kernel/dmabuf/buffers is
        # SELinux-denied to the shell domain, and /sys/kernel/debug/dma_buf does
        # not exist. DSP-side memory therefore stays unquantified rather than
        # being substituted with a different number that would not mean the
        # same thing. The caller reports it as unavailable.
        return result

    def paging(self, pid: int) -> dict[str, int]:
        """
        Major faults and swap for the app process.

        This is the difference between "the model is slow" and "the model is
        being paged in from disk on every call". With both branches resident the
        app reaches ~1.3 GB PSS on a 7.3 GB device, and once the kernel starts
        evicting mmap'd weights a latency number measures storage, not compute.
        Field 12 of /proc/pid/stat is majflt.
        """
        out: dict[str, int] = {}
        try:
            stat = self.shell(f"cat /proc/{pid}/stat").split()
            if len(stat) > 11:
                out["minflt"] = int(stat[9])
                out["majflt"] = int(stat[11])
        except (DeviceError, ValueError):
            pass
        try:
            for line in self.shell(f"cat /proc/{pid}/status").splitlines():
                for label, key in (("VmSwap:", "swap_kb"), ("VmRSS:", "rss_kb"),
                                   ("VmHWM:", "peak_rss_kb")):
                    if line.startswith(label):
                        digits = "".join(c for c in line if c.isdigit())
                        if digits:
                            out[key] = int(digits)
        except DeviceError:
            pass
        return out

    def wait_for_log(self, pid: int, pattern: str, timeout: float,
                     poll: float = 0.5) -> str | None:
        """Block until a matching line appears, or give up and return None."""
        deadline = time.monotonic() + timeout
        rx = re.compile(pattern)
        while time.monotonic() < deadline:
            raw = self.shell(f"logcat -d --pid={pid} -t 200", timeout=60)
            for line in raw.splitlines():
                if rx.search(line):
                    return line
            time.sleep(poll)
        return None
