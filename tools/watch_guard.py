#!/usr/bin/env python3
"""Bounded, read-only log/exit watcher for explicitly named package processes."""
import argparse
import datetime
import json
from pathlib import Path
import re
import shlex
import subprocess
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--seconds", type=int, default=120)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("packages", nargs="+")
    args = parser.parse_args()
    if not 1 <= args.seconds <= 3600:
        parser.error("seconds must be between 1 and 3600")
    args.output.mkdir(parents=True, exist_ok=True)

    def root(command):
        return subprocess.check_output(
            ["adb", "-s", args.serial, "shell", "su -c " + shlex.quote(command)],
            text=True, stderr=subprocess.STDOUT, timeout=10,
        )

    def processes():
        selected = {}
        for line in root("ps -A -o PID,ARGS").splitlines():
            fields = line.split()
            if len(fields) < 2 or not fields[0].isdigit():
                continue
            name = fields[1]
            if any(name == p or name.startswith(p + ":") for p in args.packages):
                selected[int(fields[0])] = name
        return selected

    pattern = re.compile(r"^\d\d-\d\d\s+\S+\s+(\d+)\s+\d+\s+\S\s+([^:]+):")
    known = {}
    seen = set()
    findings = []
    initial = None
    start = time.monotonic()
    begin_utc = datetime.datetime.now(datetime.timezone.utc).isoformat()
    with (args.output / "events.jsonl").open("w") as events, (args.output / "runtime.log").open("w") as logs:
        def event(kind, **fields):
            row = {"elapsed_s": round(time.monotonic() - start, 2), "kind": kind, **fields}
            events.write(json.dumps(row, ensure_ascii=False) + "\n")
            events.flush()
            print(json.dumps(row, ensure_ascii=False), flush=True)

        while True:
            try:
                current = processes()
                known.update(current)
                # Keep old PIDs so a crash just after exit remains attributable.
                data = root("logcat -d -v threadtime -b main -b crash -b system -s SystemHelperGuard:I AndroidRuntime:E ActivityManager:W ActivityTaskManager:E libc:F DEBUG:F '*:S'")
                if initial is None:
                    initial = current
                    previous = current
                    seen.update(data.splitlines())
                    event("baseline", processes=current)
                else:
                    if current != previous:
                        event("process_change", before=previous, after=current)
                        previous = current
                    lines = data.splitlines()
                    # Attribute short-lived processes before classifying their
                    # earlier FATAL line in the same batch.
                    for line in lines:
                        if line in seen:
                            continue
                        match = pattern.match(line)
                        if not match:
                            continue
                        name = re.search(r"(?:Process: |process=|Cmdline: )(\S+)", line)
                        if name:
                            process = name[1].rstrip(",")
                            if any(process == p or process.startswith(p + ":") for p in args.packages):
                                known[int(match[1])] = process
                        native = re.search(r"pid:\s*(\d+).*>>>\s*(\S+)\s*<<<", line)
                        if native and any(native[2] == p or native[2].startswith(p + ":") for p in args.packages):
                            known[int(native[1])] = native[2]
                    for line in lines:
                        if line in seen:
                            continue
                        seen.add(line)
                        match = pattern.match(line)
                        if not match:
                            continue
                        pid, tag = int(match[1]), match[2].strip()
                        # Native debugger lines originate from another PID;
                        # retain only lines that explicitly name a selected app.
                        relevant = pid in known or any(p in line for p in args.packages)
                        if not relevant:
                            continue
                        logs.write(line + "\n")
                        if "FATAL EXCEPTION" in line or "Fatal signal" in line or "ANR in" in line:
                            findings.append(line)
                            event("failure", line=line)
                        elif tag == "SystemHelperGuard":
                            event("module_log", line=line)
                    logs.flush()
            except (subprocess.SubprocessError, OSError) as error:
                event("collection_error", error=str(error))
                raise
            remaining = args.seconds - (time.monotonic() - start)
            if remaining <= 0:
                break
            time.sleep(min(15, remaining))
        # Process changes do not establish why a process exited. Background
        # freezing/killing must not be mislabeled as an app crash.
        result = {
            "begin_utc": begin_utc,
            "duration_s": round(time.monotonic() - start, 2),
            "initial": initial, "final": current, "failure_lines": findings,
            "scope": "Named processes and module/runtime fatal logs; no claim of complete ANR coverage.",
        }
        (args.output / "summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        event("complete", **result)


if __name__ == "__main__":
    main()
