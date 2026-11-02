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
PROTOCOL = 2
DEVICE_CAPABILITY = "device_session"
SOCKET_NAME = "android-background-test"
REMOTE_DIR = "/data/local/tmp/android-background-test"
ENTRYPOINT = "dev.androidbackgroundtest.Server"
START_TIMEOUT = 8.0

REFUSED = {"package_busy", "display_limit", "coordinator_incompatible", "device_locked", "user_active",
           "user_unsupported", "not_supported", "stale_conflict", "uiautomation_busy", "no_focused_editable",
           "set_text_failed", "session_ended", "out_of_bounds", "input_rejected", "launch_failed",
           "display_permission_denied", "display_create_failed", "no_frame", "no_task", "busy", "forbidden",
           "text_unsupported", "sensitive_app", "stale_observation", "unknown_ref", "element_not_found",
           "element_ambiguous", "accessibility_disabled", "layout_unavailable"}
DEVICE_ERRORS = {"adb_missing", "device_offline", "transport", "session_lost", "coordinator_start_failed",
                 "deploy_failed", "coordinator_unreachable"}


# 已知会识别自动化、并可能对账号做风控的应用（人工整理，不完整）。命中时 open 先让用户确认风险。
SENSITIVE_PACKAGES = {
    "com.tencent.mm": "社交与支付", "com.tencent.mobileqq": "社交", "com.tencent.wework": "社交",
    "com.eg.android.AlipayGphone": "支付", "com.unionpay": "支付", "com.huawei.wallet": "支付",
    "com.taobao.taobao": "电商", "com.tmall.wireless": "电商", "com.jingdong.app.mall": "电商",
    "com.xunmeng.pinduoduo": "电商", "com.sankuai.meituan": "电商", "com.sankuai.meituan.takeoutnew": "电商",
    "me.ele": "电商", "com.ss.android.ugc.aweme": "社交", "com.sina.weibo": "社交",
    "com.chinamworld.main": "银行", "com.android.bankabc": "银行", "com.icbc": "银行",
    "com.chinamworld.bocmbci": "银行", "cmb.pb": "银行", "com.bankcomm.Bankcomm": "银行",
    "com.yitong.mbank.psbc": "银行", "com.ecitic.bank.mobile": "银行", "com.cib.cibmb": "银行",
    "com.spdbccc.app": "银行", "com.webank.wemoney": "银行", "com.tencent.qqstock": "证券",
    "com.hexin.plat.android": "证券", "com.eastmoney.android.berlin": "证券", "com.antfortune.wealth": "理财",
}
SENSITIVE_SEGMENTS = ("bank", "securities", "wallet", "alipay", "unionpay")

# 应用拦截或风控时常见的提示语（人工整理，只作提示，需看截图确认）。
RISK_KEYWORDS = ("环境异常", "登录环境", "安全风险", "存在风险", "风险提示", "安全提示", "请关闭", "关闭无障碍",
                 "关闭辅助", "无障碍服务已开启", "检测到", "自动化", "模拟点击", "脚本", "外挂", "插件", "非官方",
                 "被迫下线", "下线通知", "已下线", "账号异常", "账号存在", "异常登录", "操作频繁", "设备异常",
                 "录屏", "屏幕共享", "暂不支持", "无法使用")

# 每个错误码给用户看的中文说明：发生了什么、为什么、可以怎么做。Agent 可直接转述。
USER_MESSAGES = {
    "sensitive_app": "目标属于会识别自动化的敏感应用，继续操作可能导致账号被风控，需要你明确同意。",
    "package_busy": "这个应用正被另一个测试会话占用，为避免互相干扰没有启动。",
    "display_limit": "手机上同时进行的测试会话已达上限，请先结束不用的会话。",
    "coordinator_incompatible": "手机上正在运行旧版本的测试服务且仍有会话，为不打断它们没有替换。",
    "device_locked": "手机已锁屏或息屏，后台屏停止渲染；请解锁并保持亮屏。",
    "user_active": "你正在手机上使用这个应用，Agent 不会从你手里拿走，会话已结束。",
    "user_unsupported": "只支持当前前台用户，不能在其他用户或工作资料里测试。",
    "not_supported": "这种打断无法由 Agent 处理。",
    "stale_conflict": "这次询问已失效（可能已超时或已处理）。",
    "uiautomation_busy": "系统无障碍接口正被其他工具占用，为不打断它没有抢占，所以无法读取界面或输入文字。",
    "no_focused_editable": "没有找到获得焦点的输入框，需要先点中输入框。",
    "set_text_failed": "输入框拒绝写入文字；可能是应用的安全键盘或防自动化保护（常见于密码、支付输入框）。",
    "session_ended": "会话已经结束。",
    "out_of_bounds": "坐标超出后台屏范围。",
    "input_rejected": "系统拒绝了注入的输入事件。",
    "launch_failed": "应用启动失败，可能是入口不对、应用拒绝在后台屏运行或已被系统禁止。",
    "display_permission_denied": "系统不允许创建测试用的后台屏，这台手机暂不支持。",
    "display_create_failed": "创建后台屏失败。",
    "no_frame": "后台屏 3 秒内没有画面；可能是应用卡住、手机息屏，或应用拒绝在后台屏渲染。",
    "no_task": "后台屏上没有应用了（应用已退出或被关闭），可以重新 launch。",
    "busy": "手机上仍有测试会话在运行。",
    "forbidden": "身份校验失败，拒绝操作。",
    "text_unsupported": "这台手机的系统不支持后台写入文字。",
    "stale_observation": "界面在上次查看后可能已经变化，为避免点错没有执行；需要重新截图或读取界面。",
    "unknown_ref": "引用的界面元素不在最近一次读取的结果里。",
    "element_not_found": "界面上没有找到要操作的元素。",
    "element_ambiguous": "界面上有多个元素符合条件，为避免点错没有执行。",
    "accessibility_disabled": "本会话按你的要求关闭了无障碍连接，无法读取控件或输入文字，只能截图和点击坐标。",
    "layout_unavailable": "读不到后台屏的界面结构；应用可能屏蔽了无障碍读取，可以改用截图。",
    "adb_missing": "电脑上找不到 adb。",
    "device_offline": "手机未连接或未授权 USB 调试。",
    "transport": "与手机的连接出错。",
    "session_lost": "会话已不存在（已关闭、超时或手机端服务已退出）。",
    "coordinator_start_failed": "手机端测试服务没有启动成功。",
    "deploy_failed": "手机端测试服务的文件校验失败，没有启动。",
    "coordinator_unreachable": "连不上手机端测试服务。",
    "interrupted": "会话被打断，需要先询问你。",
}


def sensitive_category(package):
    if package in SENSITIVE_PACKAGES:
        return SENSITIVE_PACKAGES[package]
    for segment in package.lower().split("."):
        if any(word in segment for word in SENSITIVE_SEGMENTS):
            return "金融"
    return None


def parse_selector(text):
    """把 "text=确定,class=Button" 解析为 dict；键只能是 text、id、desc、class。"""
    selector = {}
    for part in text.split(","):
        key, sep, value = part.partition("=")
        key = key.strip()
        if not sep or key not in ("text", "id", "desc", "class") or not value.strip():
            raise Failure("bad_request", "selector must look like text=确定 or id=email,class=EditText")
        selector[key] = value.strip()
    return selector


def risk_signals(response):
    """在返回的界面文字里查找风控提示语。"""
    texts = []

    def gather(node):
        for key in ("text", "desc", "label", "hint"):
            if isinstance(node.get(key), str):
                texts.append(node[key])

    for node in response.get("nodes") or []:
        gather(node)
    settle = response.get("settle") or {}
    for node in settle.get("nodes") or (settle.get("changes") or {}).get("added") or []:
        gather(node)
    for key in ("target",):
        if isinstance(response.get(key), dict):
            gather(response[key])
    hits = []
    for text in texts:
        for word in RISK_KEYWORDS:
            if word in text and len(hits) < 10:
                hits.append({"keyword": word, "text": text[:120]})
                break
    return hits


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
    category = sensitive_category(args.component.split("/")[0])
    if category:
        request["sensitive"] = category
    if args.accept_risk:
        request["acceptRisk"] = True
    if args.no_accessibility:
        request["noAccessibility"] = True
    if args.width:
        request["width"] = args.width
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
    fields = {}
    if args.annotate:
        fields["annotate"] = True
    if args.format != "png":
        fields["format"] = args.format
        fields["quality"] = args.quality
    if args.clip:
        try:
            fields["clip"] = [int(v) for v in args.clip.split(",")]
        except ValueError:
            raise Failure("bad_request", "--clip is x,y,w,h")
    record, response, payload = session_call(args, "screenshot", **fields)
    if response.get("ok") and payload is not None:
        if args.output:
            output = Path(args.output).expanduser().resolve()
        else:
            stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f")[:-3]
            extension = "jpg" if response.get("format") == "jpeg" else "png"
            output = session_dir(args.workdir, record["sessionId"]) / "screenshots" / ("shot-%s.%s" % (stamp, extension))
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(payload)
        response["path"] = str(output)
    return response


def command_close(args):
    record, response, _ = session_call(args, "close", reason=args.reason)
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
    opening.add_argument("--width", type=int, help="截图与坐标宽度，默认 720；高按主屏比例；后台屏本身与主屏同尺寸同密度")
    opening.add_argument("--user", type=int, help="仅支持当前前台用户")
    opening.add_argument("--accept-risk", action="store_true",
                         help="用户已明确同意敏感应用的账号风险（sensitive_app 之后才可使用）")
    opening.add_argument("--no-accessibility", action="store_true",
                         help="会话内不连接无障碍：只能截图和坐标操作，降低被应用察觉的面")

    def session_command(name, help_text):
        sub = commands.add_parser(name, help=help_text)
        sub.add_argument("--workdir", required=True)
        sub.add_argument("--session", required=True)
        return sub

    session_command("status", "查询会话状态与打断信息")
    session_command("launch", "在会话屏上重新启动应用")
    closing = session_command("close", "结束会话并清理")
    closing.add_argument("--reason", default="closed",
                         choices=["closed", "done", "app_blocked", "sensitive_step", "user_stopped"],
                         help="结束原因：app_blocked 应用拦截或风控，sensitive_step 到了需用户亲自操作的步骤")
    shot = session_command("screenshot", "截图")
    shot.add_argument("--output")
    shot.add_argument("--annotate", action="store_true", help="给可交互元素画框并标 ref（会连接无障碍）")
    shot.add_argument("--format", choices=["png", "jpeg"], default="png")
    shot.add_argument("--quality", type=int, default=85, help="JPEG 质量 1-100")
    shot.add_argument("--clip", help="x,y,w,h，截图坐标")
    layout = session_command("layout", "读取后台屏控件树（会短暂连接无障碍）")
    layout.add_argument("--full", action="store_true", help="返回全部节点，包括不可见的")

    def input_options(sub):
        sub.add_argument("--observation", help="只在界面仍是这次观察时执行")
        sub.add_argument("--settle", action="store_true", help="操作后等界面稳定并返回变化（会连接无障碍）")

    tap = session_command("tap", "点击坐标、ref 或选择器")
    tap.add_argument("x", type=float, nargs="?")
    tap.add_argument("y", type=float, nargs="?")
    tap.add_argument("--ref", help="layout 返回的 ref，如 e3")
    tap.add_argument("--target", help="选择器，如 text=确定 或 id=email,class=EditText")
    input_options(tap)
    swipe = session_command("swipe", "滑动")
    for axis in ("x1", "y1", "x2", "y2"):
        swipe.add_argument(axis, type=float)
    swipe.add_argument("--duration", type=int, default=300, help="毫秒")
    input_options(swipe)
    scroll = session_command("scroll", "在屏幕中部慢速滚动，可滚到目标出现")
    scroll.add_argument("direction", choices=["up", "down", "left", "right"])
    scroll.add_argument("--until", help="选择器，出现即停")
    scroll.add_argument("--max", type=int, default=8, help="最多滑动次数")
    input_options(scroll)
    key = session_command("key", "按键，如 BACK、ENTER")
    key.add_argument("key")
    input_options(key)
    text = session_command("text", "替换输入框的全部文字")
    text.add_argument("--value", required=True)
    text.add_argument("--ref", help="先点这个 ref 再写入")
    text.add_argument("--target", help="直接写入匹配的输入框，如 text=手机号")
    input_options(text)
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
        given = [args.x is not None, bool(args.ref), bool(args.target)]
        if given.count(True) != 1 or (args.x is not None) != (args.y is not None):
            raise Failure("bad_request", "tap needs exactly one of: X Y, --ref, --target")
        if args.ref:
            fields["ref"] = args.ref
        elif args.target:
            fields["target"] = parse_selector(args.target)
        else:
            fields = {"x": args.x, "y": args.y}
    elif args.command == "swipe":
        fields = {"x1": args.x1, "y1": args.y1, "x2": args.x2, "y2": args.y2, "durationMs": args.duration}
    elif args.command == "scroll":
        fields = {"direction": args.direction, "max": args.max}
        if args.until:
            fields["until"] = parse_selector(args.until)
    elif args.command == "key":
        fields = {"key": args.key}
    elif args.command == "text":
        if args.ref and args.target:
            raise Failure("bad_request", "use --ref or --target, not both")
        fields = {"value": args.value}
        if args.ref:
            fields["ref"] = args.ref
        if args.target:
            fields["target"] = parse_selector(args.target)
    elif args.command == "layout":
        fields = {"full": bool(args.full)}
    elif args.command == "resolve":
        fields = {"conflictId": args.conflict, "grant": bool(args.grant)}
    if getattr(args, "observation", None):
        fields["observation"] = args.observation
    if getattr(args, "settle", False):
        fields["settle"] = True
    return session_call(args, args.command, **fields)[1]


def explain(result):
    """补上给用户看的中文说明和风控提示。"""
    error = result.get("error")
    if error and "userMessage" not in result:
        result["userMessage"] = USER_MESSAGES.get(error, "操作没有完成：%s" % error)
    hits = risk_signals(result)
    if hits:
        result["riskSignals"] = hits
        result["riskNotice"] = ("界面上出现了疑似拦截或风控的提示。先看截图确认；如果确实是，立即停止操作，"
                                "用 close --reason app_blocked 关闭会话，并按 SKILL.md 的模板告诉用户。")
    if result.get("blankFrame") == "dark":
        result["blankNotice"] = "截图全黑：可能是应用开启了防截屏保护，也可能还在加载；稍后再截一次仍是黑屏就如实告诉用户。"
    return result


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
    print(json.dumps(explain(result), ensure_ascii=False))
    return exit_code(result)


if __name__ == "__main__":
    sys.exit(main())
