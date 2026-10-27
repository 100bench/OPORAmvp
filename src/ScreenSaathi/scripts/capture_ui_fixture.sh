#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
python3 - "$SCRIPT_DIR/.." "$@" <<'PY'
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile


def fail():
    # Never echo arguments or adb diagnostics: labels may have been supplied incorrectly.
    sys.exit("Fixture capture failed; check the debug app, accessibility service and destination.")


try:
    module = Path(sys.argv[1]).resolve()
    args = sys.argv[2:]
    dry_run = "--dry-run" in args
    args = [arg for arg in args if arg != "--dry-run"]
    if len(args) < 2:
        sys.exit("Usage: capture_ui_fixture.sh [--dry-run] <name> <destination> [approved-label ...]")
    name, destination, *labels = args
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", name):
        fail()
    if len(labels) > 100 or any(len(label) > 200 for label in labels):
        fail()
    destination = Path(destination).resolve()
    allowed = (module / "app/src/test/resources/ui_trees/real").resolve()
    if not dry_run and (allowed not in destination.parents or destination.suffix != ".json"):
        fail()
    if dry_run:
        print("Dry run: valid fixture name; no adb commands or file writes performed.")
        sys.exit(0)

    def adb(*arguments):
        return subprocess.run(["adb", *arguments], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout

    def shell(*arguments):
        return adb("shell", shlex.join(arguments))

    relative = f"files/fixtures/{name}.json"
    # Prevent a failed broadcast from silently returning a previous capture.
    shell("run-as", "com.screensaathi", "rm", "-f", relative)
    result = shell("am", "broadcast", "-n", "com.screensaathi/.debug.FixtureExportReceiver",
                   "-a", "com.screensaathi.EXPORT_UI_FIXTURE", "--es", "name", name,
                   "--es", "approved_labels", json.dumps(labels, ensure_ascii=False)).decode()
    if not re.search(r"\bresult=-1\b", result):
        fail()
    # run-as can access only the debug app's private, already-sanitized file.
    sanitized = adb("exec-out", "run-as", "com.screensaathi", "cat", relative)
    fixture = json.loads(sanitized)
    if fixture.get("schemaVersion") != 1 or fixture.get("captureKind") != "sanitized_accessibility":
        fail()
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as output:
        temporary = Path(output.name)
        try:
            output.write(sanitized)
            output.close()
            os.replace(temporary, destination)
        finally:
            temporary.unlink(missing_ok=True)
    print(f"Captured sanitized fixture; elements={len(fixture['elements'])}. Review before staging.")
except (OSError, ValueError, KeyError, subprocess.SubprocessError):
    fail()
PY
