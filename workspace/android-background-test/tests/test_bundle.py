"""离线测试：只验证分发包与控制脚本，不连接设备。设备协议用本地模拟的 adb server 测试。"""

import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[1]
SKILL = ROOT.parents[1] / "android-background-test"
SCRIPT = SKILL / "scripts/android.py"
spec = importlib.util.spec_from_file_location("android_client", SCRIPT)
client = importlib.util.module_from_spec(spec)
spec.loader.exec_module(client)


def run(script, *args, **kwargs):
    return subprocess.run([sys.executable, str(script), *args], capture_output=True, text=True, **kwargs)


class BundleTest(unittest.TestCase):
    def copy_bundle(self, folder):
        target = Path(folder) / "relocated"
        shutil.copytree(SKILL, target, ignore=shutil.ignore_patterns("__pycache__"))
        return target

    def edit_manifest(self, target, change):
        path = target / "manifest.json"
        data = json.loads(path.read_text())
        change(data)
        path.write_text(json.dumps(data))

    def test_valid_bundle_declares_device_capability(self):
        manifest = client.verify(SKILL)
        self.assertIn(client.DEVICE_CAPABILITY, manifest["capabilities"])

    def test_relocated_bundle_runs_without_workspace(self):
        with tempfile.TemporaryDirectory() as folder:
            target = self.copy_bundle(folder)
            result = run(target / "scripts/android.py", "doctor", cwd=folder, check=True)
            self.assertFalse(json.loads(result.stdout)["deviceTouched"])

    def test_doc_edits_do_not_require_rebuild(self):
        with tempfile.TemporaryDirectory() as folder:
            target = self.copy_bundle(folder)
            with (target / "SKILL.md").open("a") as file:
                file.write("\n<!-- edited -->\n")
            client.verify(target)

    def test_runtime_tamper_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            target = self.copy_bundle(folder)
            with (target / "runtime/server.jar").open("ab") as file:
                file.write(b"tamper")
            with self.assertRaisesRegex(ValueError, "hash mismatch"):
                client.verify(target)

    def test_missing_runtime_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            target = self.copy_bundle(folder)
            (target / "runtime/server.jar").unlink()
            with self.assertRaises(OSError):
                client.verify(target)

    def test_protocol_mismatch_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            target = self.copy_bundle(folder)
            self.edit_manifest(target, lambda d: d.update(protocol=client.PROTOCOL + 1))
            with self.assertRaisesRegex(ValueError, "protocol mismatch"):
                client.verify(target)

    def test_runtime_path_traversal_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            target = self.copy_bundle(folder)
            self.edit_manifest(target, lambda d: d["runtime"].update(path="../outside.jar"))
            with self.assertRaisesRegex(ValueError, "unsafe runtime path"):
                client.verify(target)

    def test_doctor_never_executes_adb(self):
        with tempfile.TemporaryDirectory() as folder:
            fake = Path(folder) / "adb"
            touched = Path(folder) / "adb-was-executed"
            fake.write_text("#!/bin/sh\n/usr/bin/touch \"$ADB_TEST_MARKER\"\nexit 99\n")
            fake.chmod(0o755)
            env = dict(os.environ, PATH=folder, ADB_TEST_MARKER=str(touched))
            run(SCRIPT, "doctor", env=env)
            run(SCRIPT, "verify", env=env)
            self.assertFalse(touched.exists())

    def test_adb_failure_is_a_device_error(self):
        with tempfile.TemporaryDirectory() as folder:
            fake = Path(folder) / "adb"
            fake.write_text("#!/bin/sh\necho 'device offline-test not found' >&2\nexit 1\n")
            fake.chmod(0o755)
            env = dict(os.environ, PATH=folder, ANDROID_ADB_SERVER_PORT="1")
            result = run(SCRIPT, "open", "--serial", "offline-test", "--component", "a/.B",
                         "--workdir", folder, env=env, timeout=30)
            self.assertEqual(result.returncode, 5)
            self.assertEqual(json.loads(result.stdout)["error"], "device_offline")

    def test_serial_required(self):
        result = run(SCRIPT, "open", "--component", "example/.Main", "--workdir", "/tmp")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--serial", result.stderr)

    def test_open_refused_without_device_capability(self):
        with tempfile.TemporaryDirectory() as folder:
            target = self.copy_bundle(folder)
            self.edit_manifest(target, lambda d: d["capabilities"].remove(client.DEVICE_CAPABILITY))
            result = run(target / "scripts/android.py", "open", "--serial", "offline-test",
                         "--component", "example/.Main", "--workdir", folder)
            self.assertEqual(result.returncode, 2)
            body = json.loads(result.stdout)
            self.assertEqual(body["error"], "not_implemented")
            self.assertFalse(body["deviceTouched"])


class FakeDevice:
    """A local stand-in for the adb server plus the on-device coordinator."""

    def __init__(self, handler):
        self.handler = handler
        self.requests = []
        self.services = []
        self.server = socket.socket()
        self.server.bind(("127.0.0.1", 0))
        self.server.listen()
        self.port = self.server.getsockname()[1]
        threading.Thread(target=self.loop, daemon=True).start()

    def loop(self):
        while True:
            try:
                conn, _ = self.server.accept()
            except OSError:
                return
            with conn:
                for _ in range(2):
                    size = int(conn.recv(4), 16)
                    self.services.append(conn.recv(size).decode())
                    conn.sendall(b"OKAY")
                line = b""
                while not line.endswith(b"\n"):
                    line += conn.recv(1)
                request = json.loads(line)
                self.requests.append(request)
                response, payload = self.handler(request)
                if payload is not None:
                    response["payloadBytes"] = len(payload)
                conn.sendall((json.dumps(response) + "\n").encode() + (payload or b""))

    def close(self):
        self.server.close()


class ProtocolTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        fake = Path(self.folder.name) / "adb"
        fake.write_text("#!/bin/sh\n[ \"$3\" = get-state ] && echo device && exit 0\nexit 1\n")
        fake.chmod(0o755)
        self.workdir = Path(self.folder.name) / "work"

    def tearDown(self):
        self.folder.cleanup()

    def run_with(self, device, *args):
        env = dict(os.environ, PATH=self.folder.name, ANDROID_ADB_SERVER_PORT=str(device.port))
        return run(SCRIPT, *args, env=env, timeout=30)

    def coordinator(self, request):
        op = request["op"]
        if op == "hello":
            return {"ok": True, "protocol": client.PROTOCOL, "liveSessions": 0, "sourceSha256": "x"}, None
        if op == "open":
            return {"ok": True, "sessionId": "s1", "token": "secret-token", "state": "running"}, None
        if op == "screenshot":
            return {"ok": True, "sessionId": "s1", "state": "interrupted",
                    "interruption": {"conflictId": "c-1", "reason": "device_locked"}}, b"\x89PNGdata"
        return {"ok": False, "error": "package_busy"}, None

    def test_open_keeps_token_off_stdout_and_uses_no_forward(self):
        device = FakeDevice(self.coordinator)
        try:
            result = self.run_with(device, "open", "--serial", "SER", "--component", "a/.B",
                                   "--workdir", str(self.workdir))
        finally:
            device.close()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("secret-token", result.stdout)
        record = self.workdir / "s1/session.json"
        self.assertEqual(json.loads(record.read_text())["token"], "secret-token")
        self.assertEqual(record.stat().st_mode & 0o777, 0o600)
        self.assertEqual(set(device.services), {"host:transport:SER", "localabstract:android-background-test"})

    def test_interrupted_screenshot_is_saved_and_exits_3(self):
        device = FakeDevice(self.coordinator)
        try:
            self.run_with(device, "open", "--serial", "SER", "--component", "a/.B", "--workdir", str(self.workdir))
            output = Path(self.folder.name) / "shot.png"
            result = self.run_with(device, "screenshot", "--workdir", str(self.workdir), "--session", "s1",
                                   "--output", str(output))
        finally:
            device.close()
        self.assertEqual(result.returncode, 3)
        self.assertEqual(output.read_bytes(), b"\x89PNGdata")
        self.assertEqual(device.requests[-1]["token"], "secret-token")

    def test_refusal_exits_4(self):
        device = FakeDevice(self.coordinator)
        try:
            self.run_with(device, "open", "--serial", "SER", "--component", "a/.B", "--workdir", str(self.workdir))
            result = self.run_with(device, "tap", "--workdir", str(self.workdir), "--session", "s1", "1", "2")
        finally:
            device.close()
        self.assertEqual(result.returncode, 4)
        self.assertEqual(json.loads(result.stdout)["error"], "package_busy")


if __name__ == "__main__":
    unittest.main()
