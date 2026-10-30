#!/usr/bin/env python3
"""Runs a probe scenario on one explicit device and records an evidence log.

Each step line is either `probe <command>` (sent to the probe's stdin, waits for one reply line)
or `host <shell command>` (run through `adb -s SERIAL shell`). `{display}` expands to the latest
created display id. Experiment-only; never restarts adb and touches only the given serial.
"""
import argparse
import datetime
import pathlib
import re
import subprocess
import sys
import time

REMOTE_DIR = "/data/local/tmp/android-background-test"
REMOTE_JAR = REMOTE_DIR + "/probe.jar"
MAIN = "dev.androidbackgroundtest.probe.Probe"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("scenario", type=pathlib.Path)
    args = parser.parse_args()
    here = pathlib.Path(__file__).resolve().parent
    jar = here.parents[1] / "work/probe/probe.jar"
    evidence = here.parents[1] / "work/evidence"
    evidence.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    log_path = evidence / f"{stamp}-{args.scenario.stem}.txt"
    adb = ["adb", "-s", args.serial]
    subprocess.run(adb + ["shell", "mkdir", "-p", REMOTE_DIR], check=True)
    subprocess.run(adb + ["push", "-q", str(jar), REMOTE_JAR], check=True)
    probe = subprocess.Popen(
        adb + ["shell", f"CLASSPATH={REMOTE_JAR} app_process / --nice-name=android-background-test-probe {MAIN}"],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    display = None
    with log_path.open("w") as log:
        def record(text):
            line = f"[{time.strftime('%H:%M:%S')}] {text}"
            print(line)
            log.write(line + "\n")
            log.flush()

        record(f"serial={args.serial} scenario={args.scenario.name}")
        record(probe.stdout.readline().rstrip())
        for raw in args.scenario.read_text().splitlines():
            step = raw.strip()
            if not step or step.startswith("#"):
                continue
            kind, _, command = step.partition(" ")
            command = command.replace("{display}", str(display))
            record(f"> {kind} {command}")
            if kind == "probe":
                probe.stdin.write(command + "\n")
                probe.stdin.flush()
                reply = probe.stdout.readline().rstrip()
                record(reply)
                match = re.search(r"created displayId=(\d+)", reply)
                if match:
                    display = int(match.group(1))
            elif kind == "host":
                started = time.monotonic()
                done = subprocess.run(adb + ["shell", command], capture_output=True, text=True)
                output = (done.stdout + done.stderr).rstrip()
                record(f"exit={done.returncode} ms={int((time.monotonic() - started) * 1000)}\n{output}")
            elif kind == "pull":
                remote, local = command.split()
                done = subprocess.run(adb + ["pull", "-q", remote, str(evidence / f"{stamp}-{local}")],
                                      capture_output=True, text=True)
                record(f"pulled {stamp}-{local}" if done.returncode == 0 else f"pull failed {done.stderr.strip()}")
            elif kind == "wait":
                time.sleep(float(command))
            else:
                sys.exit(f"unknown step {step}")
        if probe.poll() is None:
            probe.stdin.write("quit\n")
            probe.stdin.flush()
        try:
            rest = probe.communicate(timeout=10)[0]
        except subprocess.TimeoutExpired:
            probe.kill()
            rest = probe.communicate()[0]
        if rest.strip():
            record(rest.rstrip())
        record(f"probe exit={probe.returncode}")
    print(log_path)


if __name__ == "__main__":
    main()
