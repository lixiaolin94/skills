---
name: android-background-test
description: >
  在 ADB 连接的 Android 真机上测试或操作 App：启动、截图、读控件树、按元素或坐标点击、滑动、按键、输入中文、
  按步骤跑用例（Journeys）。全程在用户看不到的后台屏进行，用户可以照常用手机。用户提到“用手机/真机测一下”
  “adb 截图/点击”“dump UI 树/找到某个按钮点一下”“Android UI 自动化”“真机复现 bug”“看看 App 在手机上的效果”
  时使用；优先于直接用 adb shell input/screencap/uiautomator 操作主屏。Use when testing or driving an Android
  app on a real device over ADB (screenshot, UI tree, tap by element, type text) without taking over the screen.
---

# Android 后台测试

在手机上创建一块用户看不到的后台屏，被测应用开在上面，主屏留给用户。依赖只有 `adb` 和 Python 3.9+ 标准库，手机上不安装任何辅助程序。不适用于：iOS、通知栏和快捷设置这类只在主屏上的界面、模拟器（未验证）。

每条命令输出一行 JSON。出错时带 `userMessage`，这是给用户看的中文说明，向用户解释时以它为准。各命令的参数见 `<命令> --help`。

| 退出码 | 含义 | 你要做的 |
| --- | --- | --- |
| 0 | 成功 | 继续；带 `riskSignals` 时按“必须遵守”第 2 条处理 |
| 3 | 会话被打断 | 按“必须遵守”第 3 条询问用户 |
| 4 | 被拒绝或无法执行 | 用 `userMessage` 向用户说明，不要换方式硬做。例外：`sensitive_app` 见第 1 条；`no_task` 用 `launch`；`stale_observation` 重新 `layout` |
| 5 | 设备或传输错误；`session_lost` 表示会话已结束 | 报告，必要时重新 open |
| 1 / 2 | 内部错误 / 用法错误 | 报告 |

## 开始前

1. 运行 `verify`（只校验包）和 `doctor`（只查本机）。
2. 用 `adb devices` 确认 serial。有多台设备时问用户用哪台，不要猜。
3. 被测应用还没装时，可以按用户要求 `adb -s <serial> install` 用户自己的构建。入口 Activity 用 `adb -s <serial> shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER <包名>` 查。
4. 告诉用户：测试期间手机要**亮屏并已解锁**。锁屏会暂停测试，60 秒内不解锁，会话就结束。

## 核心循环

下面的 `abt` 只是写法上的简称。zsh 不会把变量里的命令拆开，所以每条都要写成完整命令：`python3 <本目录>/scripts/android.py …`，也可以先定义成 shell 函数。

```sh
abt open --serial <serial> --component 包名/Activity --workdir <目录>   # 记下 sessionId
abt layout --workdir <目录> --session <id>            # 每行一个元素：ref 类名 "文字" #id (x,y) 动作 [状态]
abt tap --workdir <目录> --session <id> --target "text=登录" --settle   # 或 --ref e3，或 X Y
abt text --workdir <目录> --session <id> --target "text=手机号" --value "中文内容"
abt scroll --workdir <目录> --session <id> down --until "text=关于"
abt screenshot --workdir <目录> --session <id>        # 返回图片路径，先看图再决定
abt close --workdir <目录> --session <id>             # 用完必须关闭
```

- **看界面、操作、确认，一步一循环。** 先用 `layout` 找元素，再用 `--target` 或 `--ref` 操作，加上 `--settle` 直接拿到界面变化。WebView、游戏、图片内容，或者 layout 返回 `layout_unavailable` 时，看截图点坐标。坐标按截图像素算，宽 720，高按手机屏比例。
- **新设备先核对一次**：按控件操作只在 NX888J 上验证过。其他设备第一次用时，先 `screenshot --annotate`，看框和编号（编号 12 即 e12）有没有对准元素；对不上就改用截图加坐标，并告诉用户。
- **ref 只对最近一次观察有效。** 任何输入之后都要重新 `layout`。
- 写文字前确认目标能编辑（动作里有 `edit`）。`text` 会**替换**输入框里的全部内容，不是追加。
- 找不到元素就 `scroll --until`，不要猜坐标。内容还在加载时等一两秒再看。
- 应用退出后截图会返回 `no_task`，用 `launch` 重新启动。
- 向用户提到应用时写成“应用名（包名）”，例如“天气（com.coloros.weather2）”，应用名取返回里的 `appLabel`。
- 按自然语言步骤写的用例，见 [Journeys](references/journeys.md)。

## 必须遵守

1. **敏感应用要用户同意。** 支付、银行、证券、社交、电商这类应用，open 会返回 `sensitive_app` 和 `askUser`。把 `askUser` 转述给用户；**用户明确同意前，不得加 `--accept-risk`**。处理方式见 [应用拦截与风控](references/risk.md)。
2. **看到风控提示立即停。** 返回里有 `riskSignals`，或者截图里出现“环境异常”“请关闭无障碍”“被迫下线”这类提示时：不重试，也不换方式绕过；用 `close --reason app_blocked` 关闭会话，再按 [risk.md](references/risk.md) 的模板告诉用户原因和对账号的影响。
3. **打断必须问用户。** 退出码 3 时，把 `interruption.detail` 和 `askUser` 转述给用户。**用户明确同意才能 `resolve --grant`**，拒绝就 `--deny`，不得替用户做主。各种打断的含义见 [打断处理](references/interruptions.md)。
4. **不替用户做这些步骤**：支付、转账、下单确认、登录验证、验证码、实名认证。到了就停下，用 `close --reason sensitive_step` 关闭，请用户自己在手机上完成。
5. **硬边界**：
   - 每条设备命令都写明 serial。
   - 不重启 ADB，不操作主屏。
   - 不关闭、不抢占别人的会话（`package_busy`、`uiautomation_busy`）。
   - 不关闭安全设置，不绕过锁屏，不改系统设置。
   - 不做反检测，也不承诺应用识别不到自动化。
   - 遇到权限、黑屏、兼容问题，读 [安全边界](references/safety.md)。

## 汇报

说明测了什么、关键截图路径、遇到的打断和用户的答复、有没有被应用拦截、会话是否已关闭。

已验证设备：nubia NX888J（Android 17）、OPPO PLG110（ColorOS，Android 17）。其他机型第一次用时如实说明“未验证”。控件树、按控件操作、`--settle`、标注截图只在 NX888J 上验证过；`scroll --until` 和敏感应用确认还没在真机上验证。
