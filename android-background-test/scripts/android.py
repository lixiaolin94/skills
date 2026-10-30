#!/usr/bin/env python3
"""android-background-test 控制入口：在 Android 真机的独立后台屏上测试应用。

每条命令输出一行 JSON。退出码：0 成功，1 内部错误，2 用法错误，3 会话被打断需询问用户，
4 被拒绝，5 设备或传输错误。只使用 Python 标准库和 adb。
"""

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import zipfile

NAME = "android-background-test"
# 脚本与手机端服务之间的协议版本；不一致说明脚本和 runtime 来自不同构建。
PROTOCOL = 1
DEVICE_CAPABILITY = "device_session"
SOCKET_NAME = "android-background-test"
REMOTE_DIR = "/data/local/tmp/android-background-test"
ENTRYPOINT = "dev.androidbackgroundtest.Server"
START_TIMEOUT = 8.0

REFUSED = {"package_busy", "display_limit", "coordinator_incompatible", "device_locked", "user_active",
           "user_unsupported", "not_supported", "stale_conflict", "uiautomation_busy", "no_focused_editable",
           "set_text_failed", "session_ended", "out_of_bounds", "input_rejected", "launch_failed",
           "display_permission_denied", "display_create_failed", "no_frame", "no_task", "busy", "forbidden",
           "text_unsupported"}
DEVICE_ERRORS = {"adb_missing", "device_offline", "transport", "session_lost", "coordinator_start_failed",
                 "deploy_failed", "coordinator_unreachable"}


class Failure(Exception):
    def __init__(self, code, detail=None):
        super().__init__(detail or code)
        self.code = code
        self.detail = detail


def verify(root):
    root = root.resolve()
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    if not isinstance(manifest, dict) or manifest.get("name") != NAME:
        raise ValueError("unexpected package identity")
    if manifest.get("protocol") != PROTOCOL:
        raise ValueError("protocol mismatch: script %s, runtime %r" % (PROTOCOL, manifest.get("protocol")))
    capabilities = manifest.get("capabilities")
    if not isinstance(capabilities, list) or not all(isinstance(c, str) for c in capabilities):
        raise ValueError("invalid capability list")
    runtime = manifest.get("runtime")
    if not isinstance(runtime, dict):
        raise ValueError("missing runtime entry")
    relative = runtime.get("path")
    if not isinstance(relative, str) or Path(relative).is_absolute() or ".." in Path(relative).parts \
            or "\\" in relative:
        raise ValueError("unsafe runtime path")
    path = root / relative
    current = path
    while current != root:
        if current.is_symlink():
            raise ValueError("symlinked runtime is not allowed")
        current = current.parent
    if not path.resolve().is_relative_to(root):
        raise ValueError("runtime escapes skill directory")
    if hashlib.sha256(path.read_bytes()).hexdigest() != runtime.get("sha256"):
        raise ValueError("runtime hash mismatch")
    with zipfile.ZipFile(path) as archive:
        if archive.namelist() != ["classes.dex"]:
            raise ValueError("unexpected runtime archive contents")
        if not archive.read("classes.dex").startswith(b"dex\n"):
            raise ValueError("runtime is not a DEX artifact")
    return manifest


# --- adb ---------------------------------------------------------------------------------------

def adb(serial, *args, timeout=60):
    executable = shutil.which("adb")
    if not executable:
        raise Failure("adb_missing", "adb not found on PATH")
    try:
        done = subprocess.run([executable, "-s", serial, *args], capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise Failure("transport", "adb %s timed out" % args[0])
    if done.returncode != 0:
        raise Failure("device_offline" if "not found" in done.stderr or "offline" in done.stderr else "transport",
                      (done.stderr or done.stdout).strip())
    return done.stdout


def adb_stream(serial, service):
    """Opens a stream to a device service through the adb server, without creating a forward."""
    port = int(os.environ.get("ANDROID_ADB_SERVER_PORT", "5037"))
    try:
        conn = socket.create_connection(("127.0.0.1", port), timeout=10)
    except OSError as error:
        raise Failure("transport", "adb server unreachable: %s" % error)
    for message in ("host:transport:" + serial, service):
        data = message.encode()
        conn.sendall(b"%04x" % len(data) + data)
        status = read_exact(conn, 4)
        if status != b"OKAY":
            length = read_exact(conn, 4)
            detail = read_exact(conn, int(length, 16)).decode(errors="replace") if length else ""
            conn.close()
            if message.startswith("localabstract:"):
                raise Failure("coordinator_unreachable", "no coordinator is listening on the device")
            raise Failure("transport", "%s: %s" % (message.split(":")[0], detail))
    return conn


def read_exact(conn, size):
    data = b""
    while len(data) < size:
        chunk = conn.recv(size - len(data))
        if not chunk:
            break
        data += chunk
    return data


def call(serial, request, timeout=60):
    """Sends one request to the coordinator. Returns (response, payload bytes or None)."""
    conn = adb_stream(serial, "localabstract:" + SOCKET_NAME)
    conn.settimeout(timeout)
    try:
        conn.sendall((json.dumps(request, ensure_ascii=False) + "\n").encode("utf-8"))
        line = b""
        while not line.endswith(b"\n"):
            chunk = conn.recv(1)
            if not chunk:
                raise Failure("transport", "coordinator closed the connection")
            line += chunk
        response = json.loads(line.decode("utf-8"))
        payload = None
        if "payloadBytes" in response:
            payload = read_exact(conn, response.pop("payloadBytes"))
        return response, payload
    except socket.timeout:
        raise Failure("transport", "coordinator did not answer in %ss" % timeout)
    finally:
        conn.close()


def try_hello(serial):
    try:
        response, _ = call(serial, {"op": "hello"}, timeout=10)
        return response
    except Failure:
        return None


def ensure_coordinator(serial, root, manifest):
    if adb(serial, "get-state").strip() != "device":
        raise Failure("device_offline", "device is not ready")
    hello = try_hello(serial)
    if hello and hello.get("protocol") == PROTOCOL:
        return hello
    if hello:
        if hello.get("liveSessions"):
            raise Failure("coordinator_incompatible",
                          "running coordinator speaks protocol %s and still has sessions" % hello.get("protocol"))
        call(serial, {"op": "shutdown"})
        deadline = time.monotonic() + START_TIMEOUT
        while try_hello(serial) and time.monotonic() < deadline:
            time.sleep(0.2)
    sha = manifest["runtime"]["sha256"]
    remote = "%s/%s.jar" % (REMOTE_DIR, sha)
    check = "[ \"$(sha256sum %s 2>/dev/null | cut -d' ' -f1)\" = %s ]" % (remote, sha)
    present = adb(serial, "shell", "mkdir -p %s && chmod 700 %s && if %s; then echo present; fi"
                  % (REMOTE_DIR, REMOTE_DIR, check)).strip()
    if present != "present":
        adb(serial, "push", str(root / manifest["runtime"]["path"]), remote, timeout=120)
        adb(serial, "shell", "chmod 600 %s" % remote)
    started = adb(serial, "shell",
                  "cd %s && for f in *.jar; do [ \"$f\" = %s.jar ] || rm -f \"$f\"; done; "
                  "if %s; then CLASSPATH=%s nohup app_process / --nice-name=%s %s coordinator "
                  "</dev/null >/dev/null 2>&1 & echo started; else echo hash_mismatch; fi"
                  % (REMOTE_DIR, sha, check, remote, NAME, ENTRYPOINT)).strip()
    if started != "started":
        raise Failure("deploy_failed", "runtime hash on device does not match the bundle")
    deadline = time.monotonic() + START_TIMEOUT
    while time.monotonic() < deadline:
        hello = try_hello(serial)
        if hello:
            if hello.get("protocol") != PROTOCOL:
                raise Failure("coordinator_incompatible", "unexpected protocol %s" % hello.get("protocol"))
            return hello
        time.sleep(0.2)
    raise Failure("coordinator_start_failed", "coordinator did not answer within %ss" % START_TIMEOUT)


# --- sessions ----------------------------------------------------------------------------------

def session_dir(workdir, session_id):
    return Path(workdir).expanduser().resolve() / session_id


def save_session(workdir, record):
    folder = session_dir(workdir, record["sessionId"])
    folder.mkdir(parents=True, exist_ok=True)
    folder.chmod(0o700)
    path = folder / "session.json"
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w") as file:
        json.dump(record, file)
    return folder


def load_session(workdir, session_id):
    path = session_dir(workdir, session_id) / "session.json"
    try:
        return json.loads(path.read_text())
    except FileNotFoundError:
        raise Failure("session_lost", "no session.json at %s (closed or wrong workdir)" % path)


def session_call(args, op, **fields):
    record = load_session(args.workdir, args.session)
    request = {"op": op, "sessionId": record["sessionId"], "token": record["token"], **fields}
    try:
        response, payload = call(record["serial"], request, timeout=120)
    except Failure as failure:
        if failure.code != "coordinator_unreachable":
            raise
        response, payload = {"ok": False, "error": "session_lost",
                             "detail": "coordinator is not running; the session has ended"}, None
    return record, response, payload


# --- commands ----------------------------------------------------------------------------------

def command_open(args, root, manifest):
    hello = ensure_coordinator(args.serial, root, manifest)
    request = {"op": "open", "component": args.component}
    if args.size:
        width, _, height = args.size.partition("x")
        request.update(width=int(width), height=int(height))
    if args.density:
        request["density"] = args.density
    if args.user is not None:
        request["user"] = args.user
    response, _ = call(args.serial, request, timeout=120)
    if response.get("ok"):
        token = response.pop("token")
        folder = save_session(args.workdir, {"serial": args.serial, "sessionId": response["sessionId"],
                                             "token": token, "component": args.component})
        response["workdir"] = str(folder)
        if hello.get("sourceSha256") != manifest["build"]["sourceSha256"]:
            response["coordinatorVersion"] = hello.get("version")
    return response


def command_screenshot(args):
    record, response, payload = session_call(args, "screenshot")
    if response.get("ok") and payload is not None:
        if args.output:
            output = Path(args.output).expanduser().resolve()
        else:
            stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f")[:-3]
            output = session_dir(args.workdir, record["sessionId"]) / "screenshots" / ("shot-%s.png" % stamp)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(payload)
        response["path"] = str(output)
    return response


def command_close(args):
    record, response, _ = session_call(args, "close")
    if response.get("ok") or response.get("error") == "session_lost":
        (session_dir(args.workdir, record["sessionId"]) / "session.json").unlink(missing_ok=True)
    return response


def command_list(args):
    if adb(args.serial, "get-state").strip() != "device":
        raise Failure("device_offline", "device is not ready")
    if not try_hello(args.serial):
        return {"ok": True, "sessions": [], "coordinator": False}
    response, _ = call(args.serial, {"op": "list"})
    response["coordinator"] = True
    return response


def exit_code(response):
    if response.get("ok"):
        return 3 if response.get("state") == "interrupted" else 0
    error = response.get("error")
    if error == "interrupted":
        return 3
    if error in REFUSED:
        return 4
    if error in DEVICE_ERRORS:
        return 5
    if error in ("bad_request", "not_implemented", "invalid_bundle"):
        return 2
    return 1


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("verify", help="仅校验分发文件")
    commands.add_parser("doctor", help="仅检查本机，不调用 ADB")

    listing = commands.add_parser("list", help="列出设备上的会话摘要")
    listing.add_argument("--serial", required=True)

    opening = commands.add_parser("open", help="创建后台测试会话并启动应用")
    opening.add_argument("--serial", required=True)
    opening.add_argument("--component", required=True, help="包名/Activity")
    opening.add_argument("--workdir", required=True, help="保存会话凭据与截图的目录")
    opening.add_argument("--size", help="宽x高，默认 720x1600")
    opening.add_argument("--density", type=int, help="默认 320")
    opening.add_argument("--user", type=int, help="仅支持当前前台用户")

    def session_command(name, help_text):
        sub = commands.add_parser(name, help=help_text)
        sub.add_argument("--workdir", required=True)
        sub.add_argument("--session", required=True)
        return sub

    session_command("status", "查询会话状态与打断信息")
    session_command("launch", "在会话屏上重新启动应用")
    session_command("close", "结束会话并清理")
    shot = session_command("screenshot", "截图保存为 PNG")
    shot.add_argument("--output")
    tap = session_command("tap", "点击")
    tap.add_argument("x", type=float)
    tap.add_argument("y", type=float)
    swipe = session_command("swipe", "滑动")
    for axis in ("x1", "y1", "x2", "y2"):
        swipe.add_argument(axis, type=float)
    swipe.add_argument("--duration", type=int, default=300, help="毫秒")
    key = session_command("key", "按键，如 BACK、ENTER")
    key.add_argument("key")
    text = session_command("text", "替换当前焦点输入框的全部文字")
    text.add_argument("--value", required=True)
    resolve = session_command("resolve", "转达用户对打断的答复")
    resolve.add_argument("--conflict", required=True)
    decision = resolve.add_mutually_exclusive_group(required=True)
    decision.add_argument("--grant", action="store_true", help="用户明确同意接管")
    decision.add_argument("--deny", action="store_true")
    return parser


def run(args, root, manifest):
    ready = DEVICE_CAPABILITY in manifest["capabilities"]
    if args.command in ("verify", "doctor"):
        result = {"ok": True, "bundleValid": True, "version": manifest.get("version"),
                  "capabilities": manifest["capabilities"], "deviceTestingReady": ready, "deviceTouched": False}
        if args.command == "doctor":
            result["python"] = sys.version.split()[0]
            result["adb"] = shutil.which("adb")
            result["dependenciesAvailable"] = result["adb"] is not None
        return result
    if not ready:
        return {"ok": False, "error": "not_implemented", "deviceTouched": False}
    if args.command == "open":
        return command_open(args, root, manifest)
    if args.command == "list":
        return command_list(args)
    if args.command == "screenshot":
        return command_screenshot(args)
    if args.command == "close":
        return command_close(args)
    fields = {}
    if args.command == "tap":
        fields = {"x": args.x, "y": args.y}
    elif args.command == "swipe":
        fields = {"x1": args.x1, "y1": args.y1, "x2": args.x2, "y2": args.y2, "durationMs": args.duration}
    elif args.command == "key":
        fields = {"key": args.key}
    elif args.command == "text":
        fields = {"value": args.value}
    elif args.command == "resolve":
        fields = {"conflictId": args.conflict, "grant": bool(args.grant)}
    return session_call(args, args.command, **fields)[1]


def main(argv=None):
    args = build_parser().parse_args(argv)
    root = Path(__file__).resolve().parents[1]
    try:
        manifest = verify(root)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        result = {"ok": False, "error": "invalid_bundle", "detail": str(error)}
    else:
        try:
            result = run(args, root, manifest)
        except Failure as failure:
            result = {"ok": False, "error": failure.code, "detail": failure.detail}
    print(json.dumps(result, ensure_ascii=False))
    return exit_code(result)


if __name__ == "__main__":
    sys.exit(main())
