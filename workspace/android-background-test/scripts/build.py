#!/usr/bin/env python3
"""构建手机端 DEX JAR 并写入 Skill 包；不安装软件、不执行 ADB。

只生成 runtime/server.jar 与 manifest.json。SKILL.md、references、scripts/android.py
直接在 Skill 包内维护，修改它们不需要重新构建。
"""

import argparse
import ast
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SKILL = ROOT.parents[1] / "android-background-test"
VERSION = "0.2.0-dev"
MIN_API = 31
CAPABILITIES = ["offline_verify", "local_doctor", "device_session", "screenshot", "input", "text"]
# 真机验证记录见 docs/validation.md；未列出的机型都按未验证处理。
TESTED_DEVICES = [
    {"model": "nubia NX888J", "android": "17", "build": "2.7.0.0-ut test-keys"},
    {"model": "OPPO PLG110", "android": "17", "build": "PLG110_17.0.0.105(CN01)"},
]


def client_protocol():
    """协议号只在控制脚本里定义一次，构建时读取并嵌入服务。"""
    tree = ast.parse((SKILL / "scripts/android.py").read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(getattr(t, "id", None) == "PROTOCOL" for t in node.targets):
            return ast.literal_eval(node.value)
    raise RuntimeError("PROTOCOL not found in android.py")


def source_hash(protocol):
    digest = hashlib.sha256(("protocol=%d\0" % protocol).encode())
    for path in sorted((ROOT / "android").rglob("*.java")) + [Path(__file__).resolve()]:
        digest.update(path.relative_to(ROOT).as_posix().encode() + b"\0")
        digest.update(path.read_bytes() + b"\0")
    return digest.hexdigest()


def find_android_jar(args, sdk):
    if args.android_jar:
        return Path(args.android_jar).resolve()
    candidates = []
    for path in (sdk / "platforms").glob("android-*/android.jar"):
        version = path.parent.name[len("android-"):]
        if re.fullmatch(r"\d+(\.\d+)?", version):
            candidates.append((tuple(map(int, version.split("."))), path))
    if not candidates:
        raise RuntimeError("android.jar not found; install an SDK platform or pass --android-jar")
    return max(candidates)[1]


def find_tools(args):
    home = args.java_home or os.environ.get("JAVA_HOME")
    if not home and Path("/usr/libexec/java_home").exists():
        home = subprocess.check_output(["/usr/libexec/java_home"], text=True).strip()
    javac = str(Path(home) / "bin/javac") if home else shutil.which("javac")
    if not javac or not Path(javac).is_file():
        raise RuntimeError("JDK not found; set JAVA_HOME")
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
               or Path.home() / "Library/Android/sdk")
    if args.d8:
        d8 = Path(args.d8).resolve()
    else:
        candidates = [p for p in (sdk / "build-tools").glob("*/d8")
                      if re.fullmatch(r"\d+\.\d+\.\d+", p.parent.name)]
        if not candidates:
            raise RuntimeError("d8 not found; provide --d8 or ANDROID_SDK_ROOT")
        d8 = max(candidates, key=lambda p: tuple(map(int, p.parent.name.split("."))))
    if not d8.is_file():
        raise RuntimeError("d8 executable not found")
    android_jar = find_android_jar(args, sdk)
    if not android_jar.is_file():
        raise RuntimeError("android.jar not found: %s" % android_jar)
    env = dict(os.environ)
    if home:
        env["JAVA_HOME"] = home
    return javac, d8, android_jar, env


def write_atomic(target, data):
    if target.is_symlink():
        raise RuntimeError("refusing symlinked output: %s" % target)
    staged = target.with_name("." + target.name + ".tmp")
    staged.write_bytes(data)
    staged.replace(target)


def build(args):
    javac, d8, android_jar, env = find_tools(args)
    protocol = client_protocol()
    identity = source_hash(protocol)
    with tempfile.TemporaryDirectory(prefix="abt-build-") as temporary:
        stage = Path(temporary)
        classes, dex = stage / "classes", stage / "dex"
        classes.mkdir()
        dex.mkdir()
        info = stage / "BuildInfo.java"
        info.write_text(
            "package dev.androidbackgroundtest;\n"
            "final class BuildInfo {\n"
            '  static final String VERSION = "%s";\n'
            "  static final int PROTOCOL = %d;\n"
            '  static final String SOURCE_SHA256 = "%s";\n'
            "}\n" % (VERSION, protocol, identity), encoding="utf-8")
        sources = sorted((ROOT / "android/src").rglob("*.java"))
        subprocess.run([javac, "--release", "8", "-Xlint:-options", "-encoding", "UTF-8",
                        "-classpath", str(android_jar), "-d", str(classes),
                        *map(str, sources), str(info)], env=env, check=True)
        subprocess.run([str(d8), "--min-api", str(MIN_API), "--lib", str(android_jar), "--output", str(dex),
                        *map(str, sorted(classes.rglob("*.class")))], env=env, check=True)
        jar = stage / "server.jar"
        with zipfile.ZipFile(jar, "w", compression=zipfile.ZIP_STORED) as archive:
            entry = zipfile.ZipInfo("classes.dex", date_time=(1980, 1, 1, 0, 0, 0))
            entry.external_attr = 0o644 << 16
            archive.writestr(entry, (dex / "classes.dex").read_bytes())
        data = jar.read_bytes()
    (SKILL / "runtime").mkdir(exist_ok=True)
    write_atomic(SKILL / "runtime/server.jar", data)
    manifest = {
        "name": "android-background-test", "version": VERSION, "protocol": protocol,
        "capabilities": CAPABILITIES,
        "runtime": {"path": "runtime/server.jar", "sha256": hashlib.sha256(data).hexdigest(),
                    "format": "dex-jar", "entrypoint": "dev.androidbackgroundtest.Server",
                    "requiresPackageInstall": False, "dexMinApi": MIN_API},
        "build": {"sourceSha256": identity, "androidJar": android_jar.parent.name,
                  "javac": subprocess.check_output([javac, "-version"], env=env, text=True).strip(),
                  "d8": subprocess.check_output([str(d8), "--version"], env=env, text=True).strip()},
        "testedDevices": TESTED_DEVICES,
    }
    # manifest 最后写：中途失败时旧 manifest 与新 jar 哈希不符，verify 会发现。
    write_atomic(SKILL / "manifest.json",
                 (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    print("Built runtime into", SKILL)
    print("No device was contacted.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home")
    parser.add_argument("--d8")
    parser.add_argument("--android-jar")
    build(parser.parse_args())


if __name__ == "__main__":
    main()
