"""Attach only to an explicit PID; keep intercepted metadata as JSON lines."""
import argparse
import json
from pathlib import Path
import threading
import time

import frida

parser = argparse.ArgumentParser()
parser.add_argument("--pid", type=int, required=True)
parser.add_argument("--seconds", type=int, default=60)
parser.add_argument("--endpoint", default="127.0.0.1:27052")
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--token-file", type=Path)
args = parser.parse_args()
detached = threading.Event()
with args.output.open("w") as output:
    def record(kind, **data):
        output.write(json.dumps({"host_time": time.time(), "kind": kind, **data}) + "\n")
        output.flush()

    options = {"token": args.token_file.read_text().strip()} if args.token_file else {}
    device = frida.get_device_manager().add_remote_device(args.endpoint, **options)
    session = device.attach(args.pid)

    def on_detached(reason, crash=None):
        record("session_detached", reason=reason, crash=str(crash) if crash else None)
        detached.set()

    session.on("detached", on_detached)
    script = session.create_script(Path("tools/native_agent.js").read_text())
    script.on("message", lambda message, data: record("agent_message", message=message))
    try:
        script.load()
        record("capture_start", pid=args.pid, seconds=args.seconds)
        print(f"Attached PID {args.pid}; collecting metadata for {args.seconds}s", flush=True)
        detached.wait(args.seconds)
    finally:
        if not detached.is_set():
            script.unload()
            session.detach()
        record("capture_end")
