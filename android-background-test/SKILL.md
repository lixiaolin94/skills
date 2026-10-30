---
name: android-background-test
description: >
  在通过 ADB 连接的 Android 真机上测试或操作 App：启动、截图查看界面、点击、滑动、按键、输入中文。
  全程在用户看不到的后台屏进行，用户可以继续正常用手机。用户提到“用手机/真机测一下”“adb 截图/点击”
  “Android UI 自动化”“真机复现 bug”“看看 App 在手机上的效果”时使用；优先于直接用 adb shell
  input/screencap 操作主屏。Use when testing or driving an Android app on a real device over ADB
  (launch, screenshot, tap, swipe, keys, type text) without taking over the phone's screen.
  需要手机亮屏并已解锁。
---

# Android 后台测试

在手机上创建一块用户看不到的后台屏，把被测应用开在上面操作，主屏保持用户自己的使用。依赖只有 `adb` 和 Python 3.9+ 标准库，不安装 APK。

脚本：`python3 <本目录>/scripts/android.py <命令>`。每条命令输出一行 JSON。

| 退出码 | 含义 | 你要做的 |
| --- | --- | --- |
| 0 | 成功 | 继续 |
| 3 | 会话被打断 | 按“打断处理”询问用户 |
| 4 | 被拒绝或无法执行 | 读 `error` 和 `detail`，向用户说明，不要换方式硬做；`no_task` 例外，见下 |
| 5 | 设备或传输错误；`session_lost` 表示会话已结束 | 报告，必要时重新 open |
| 1 / 2 | 内部错误 / 用法错误 | 报告 |

## 开始前

1. `verify`（只校验包）、`doctor`（只查本机）。
2. 用 `adb devices` 确认 serial。多台设备时必须问用户用哪台，不要猜。
3. 告诉用户：测试期间手机需要**亮屏并已解锁**；锁屏会暂停测试，60 秒内不解锁会话就结束。

## 流程

```sh
S=<serial>; W=<工作目录>; A="python3 <本目录>/scripts/android.py"
$A open --serial $S --component 包名/Activity --workdir $W   # 记下 sessionId
$A screenshot --workdir $W --session <id>                     # 返回 PNG 路径，查看后再操作
$A tap --workdir $W --session <id> X Y                        # 坐标是会话屏像素，默认 720x1600
$A swipe --workdir $W --session <id> X1 Y1 X2 Y2 --duration 300
$A key --workdir $W --session <id> BACK                       # BACK、ENTER、HOME 等
$A text --workdir $W --session <id> --value "中文内容"         # 先点输入框
$A status --workdir $W --session <id>
$A close --workdir $W --session <id>                          # 用完必须关闭
```

- 每次操作后截图确认结果，不要凭坐标连续盲点。
- `text` 会**替换**输入框全部内容，不是追加；需要追加就自己拼好完整文本。
- `text` 期间会短暂连接 UiAutomation，应用能察觉到无障碍服务开启；在银行、支付类应用里使用前先告诉用户。被其他工具占用时返回 `uiautomation_busy`，不要抢。
- 应用找不到入口 Activity 时，用 `adb -s <serial> shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER <包名>` 查询。
- 应用被关掉后（BACK 退出、崩溃），截图返回 `no_task`，用 `launch` 在会话屏上重新启动。
- 返回中的 `appLabel` 是应用名。向用户提到应用时写成“应用名（包名）”，例如“天气（com.coloros.weather2）”；读不到应用名时只写包名。
- 会话空闲 10 分钟会自动结束。

## 打断处理（退出码 3）

返回中的 `interruption` 包含 `reason`、`detail`、`askUser`、`conflictId`、`grantable`、`expiresInSeconds`。

1. 停止操作。用 `detail` 告诉用户发生了什么，用 `askUser` 询问用户。
2. **只有用户在对话中明确同意**，才能调用 `resolve --conflict <id> --grant`；用户拒绝就 `--deny`。不得替用户同意，不得因为测试没完成就默认同意。
3. 只有入口 Activity 是 singleTask/singleInstance 的应用才会出现 `existing_task`。standard 应用直接另开一个任务，不询问，用户原来的任务不受影响，这是正常行为。
4. 超时（`expiresInSeconds` 归零）、用户拒绝、或者用户正在手机上使用这个应用时，会话会自动结束，这是预期行为。

| reason | 情况 | 能否 grant |
| --- | --- | --- |
| `existing_task` | 用户手机上已有这个应用的任务，接管会把它移到后台屏；关闭会话时该任务会被移除 | 能，需用户同意 |
| `user_opened_app` | 用户在手机上打开了正在测试的应用，任务已交给用户 | 能；用户还在用时会失败并结束 |
| `device_locked` | 手机锁屏或息屏，后台屏停止 | 不能；请用户解锁，解锁后自动继续 |

## 硬边界

- 每条设备命令都有明确 serial；不重启 ADB，不操作主屏，不清理不属于自己的会话。
- 同一应用同一时间只能有一个会话（`package_busy`），不要关掉别人的会话来抢。
- 不关闭安全设置、不绕过锁屏、不安装 APK、不改系统设置。拒绝或黑屏就如实报告。
- 不承诺应用识别不到自动化。

遇到权限、告警或兼容问题时读 [安全边界](references/safety.md)。

## 汇报

说明测试了什么、关键截图路径、遇到的打断和用户的答复、会话是否已关闭。已验证设备：nubia NX888J（Android 17）、OPPO PLG110（ColorOS，Android 17）；其他机型首次使用时如实说明“未验证”。
