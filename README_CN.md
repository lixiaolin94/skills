[English](README.md)

# Skills

一组可复用的 AI 编程 Agent 技能。

## 包含的技能

| 技能 | 说明 |
|------|------|
| **[android-background-test](android-background-test/)** | 通过 ADB 在 Android 真机的隐藏后台屏上测试应用：启动、截图、点击、滑动、按键、中文输入，不打扰用户前台使用。 |
| **[web-shader-extractor](web-shader-extractor/)** | 从网页中提取 WebGL/Canvas/Shader 视觉特效代码，反混淆后移植为独立原生 JS 项目。 |

## 安装

安装整个仓库：

```bash
npx skills add https://github.com/lixiaolin94/skills
```

安装单个技能：

```bash
npx skills add https://github.com/lixiaolin94/skills --skill web-shader-extractor
npx skills add https://github.com/lixiaolin94/skills --skill android-background-test
```

## android-background-test 说明

**状态：测试版（`0.2.0-dev`）。** 已在 nubia NX888J（Android 17）和 OPPO PLG110（ColorOS，Android 17）上验证，其他机型和 ROM 未验证，欢迎反馈机型与系统版本。

使用前提：

- 电脑上有 `adb` 和 Python 3.9+（只用标准库）
- 手机开启 USB 调试，并已授权这台电脑
- 编译目标为 Android 12（API 31）及以上；实际可用的最低版本未测试
- 测试期间手机需亮屏并已解锁，锁屏会暂停会话
- 部分 ROM（如小米）可能要另开“USB 调试（安全设置）”才能点击，未实测

手机上不安装任何应用。`runtime/server.jar` 是由 [`workspace/android-background-test`](workspace/android-background-test/) 源码构建的 DEX，可以用 `python3 workspace/android-background-test/scripts/build.py` 自行重建，并与 `android-background-test/manifest.json` 中的 sha256 对照。

## 鸣谢

| 贡献者 | 贡献内容 |
|--------|----------|
| [Huazi](https://github.com/HeyHuazi) | **web-shader-extractor** — 提供 2D Canvas 提取报告，促成 2D Canvas 识别、移植策略和框架脱壳规则 |

## 许可证

MIT
