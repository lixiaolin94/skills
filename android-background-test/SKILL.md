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

脚本：`python3 <本目录>/scripts/android.py <命令>`。每条命令输出一行 JSON；出错时带 `userMessage`（给用户看的中文说明）。

| 退出码 | 含义 | 你要做的 |
| --- | --- | --- |
| 0 | 成功 | 继续；有 `riskSignals` 时先按“应用拦截与风控”处理 |
| 3 | 会话被打断 | 按“打断处理”询问用户 |
| 4 | 被拒绝或无法执行 | 用 `userMessage` 向用户说明，不要换方式硬做；`sensitive_app`、`no_task`、`stale_observation` 见下 |
| 5 | 设备或传输错误；`session_lost` 表示会话已结束 | 报告，必要时重新 open |
| 1 / 2 | 内部错误 / 用法错误 | 报告 |

## 开始前

1. `verify`（只校验包）、`doctor`（只查本机）。
2. 用 `adb devices` 确认 serial。多台设备时必须问用户用哪台，不要猜。
3. 告诉用户：测试期间手机需要**亮屏并已解锁**；锁屏会暂停测试，60 秒内不解锁会话就结束。

## 敏感应用（退出码 4，`sensitive_app`）

微信、支付宝、银行、证券、电商这类应用常会识别自动化操作，轻则拒绝运行，重则对**用户的真实账号**风控（强制下线、限制功能、封号），Agent 无法撤销。`open` 遇到这类应用会先拒绝，并返回 `askUser`。

1. 把 `askUser` 转述给用户，等用户答复。**用户明确同意前，不得加 `--accept-risk`**。
2. 用户同意后重新 open，加 `--accept-risk`；建议同时加 `--no-accessibility`。这个模式下不能读控件树、不能写文字，只能截图和点坐标，被察觉的面最小。
3. 判定名单不完整。名单外的应用如果明显涉及钱或账号，同样先提醒用户。

## 流程

```sh
S=<serial>; W=<工作目录>; A="python3 <本目录>/scripts/android.py"
$A open --serial $S --component 包名/Activity --workdir $W   # 记下 sessionId
$A layout --workdir $W --session <id>                         # 控件树：ref、文字、坐标、可执行动作
$A tap --workdir $W --session <id> --target "text=登录"        # 按控件点；也可 --ref e3 或 X Y
$A text --workdir $W --session <id> --target "text=手机号" --value "中文内容"
$A scroll --workdir $W --session <id> down --until "text=关于"
$A screenshot --workdir $W --session <id>                     # 返回图片路径，查看后再操作
$A key --workdir $W --session <id> BACK                       # BACK、ENTER 等
$A close --workdir $W --session <id>                          # 用完必须关闭；可加 --reason
```

- **控件树为主，截图为辅。** 先用 `layout` 找元素，用 `--target` 或 `--ref` 操作；WebView、游戏、图片内容，或者 `layout` 返回 `layout_unavailable` 时，再看截图、点坐标。坐标按截图像素计算，宽 720，高按手机屏比例。
- **选择器**：`text=…`（同时匹配文字、提示和子元素文字）、`id=…`、`desc=…`、`class=…`，可以用逗号组合。命中多个返回 `element_ambiguous`，附带候选和 ref，改用 `--ref`；没命中返回 `element_not_found`，附带屏上的文字。
- **防止点错**：
  - ref 只属于最近一次 `layout`（或 `screenshot --annotate`）。任何一次输入之后，ref 就失效，返回 `stale_observation`，需要重新 `layout`。
  - 点坐标时可以带 `--observation oN`（截图或 layout 返回的），界面变过就不执行。
- **看变化**：输入命令加 `--settle`，会等界面稳定，再返回新增和消失的元素（新增的带 ref），省一次 layout。
- **交互规则**：
  - 写文字前确认目标是输入框（`actions` 含 `edit`）。
  - 找不到元素先 `scroll`，不要猜坐标。
  - 内容还在加载时等一两秒再 `layout`。
  - 截图后先看图再决定，不要凭坐标连续盲点。
- `text` 会**替换**输入框全部内容，不是追加。
- `layout`、`--target`、`--settle`、`text` 和标注截图都会短暂连接无障碍，应用能察觉到；被其他工具占用时返回 `uiautomation_busy`，不要抢。
- 截图选项：
  - `--annotate`：给可交互元素画框、标 ref。
  - `--format jpeg --quality 70`、`--clip x,y,w,h`：减小图片体积。
  - 返回 `blankFrame: "dark"` 多半是应用开启了防截屏保护，如实告诉用户。
- 应用找不到入口 Activity 时，用 `adb -s <serial> shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER <包名>` 查询。
- 应用被关掉后（BACK 退出、崩溃），截图返回 `no_task`，用 `launch` 在会话屏上重新启动。
- 向用户提到应用时写成“应用名（包名）”，例如“天气（com.coloros.weather2）”，应用名取返回里的 `appLabel`；读不到时只写包名。
- 会话空闲 10 分钟会自动结束。
- 按自然语言步骤写的用例（Journeys）怎么在后台屏上跑、怎么出报告，见 [Journeys](references/journeys.md)。

## 应用拦截与风控

返回里有 `riskSignals`，或者截图里出现“环境异常”“请关闭无障碍/辅助功能”“检测到自动化”“被迫下线”“账号异常”这类提示时：

1. **立即停止操作**。不重试、不换方式绕过，重试只会加重账号风险。
2. `close --reason app_blocked` 关闭会话。
3. 按下面的模板告诉用户，让用户知道发生了什么、对账号有什么影响：

```text
测试没能继续：<应用名（包名）> 拦截了自动化操作。
- 停在哪一步：<步骤>
- 看到了什么：“<提示原文>”（截图：<路径>）
- 判断：<确定 / 可能> 是应用识别到了 <无障碍 / 模拟点击 / 后台屏>；<依据>
- 已做的处理：会话已关闭，没有重试，也没有尝试绕过
- 对你账号的影响：<有没有被下线 / 看不出影响>；建议 <检查账号状态 / 改用测试账号或测试机 / 这一步手动完成>
```

`set_text_failed`、`layout_unavailable`、截图全黑也可能是应用的防护造成的。遇到时用 `userMessage` 加上你看到的情况说明原因，不要只报错误码。

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
- **支付、转账、下单确认、登录验证、验证码、实名认证**这些步骤不替用户做。到了这些步骤就停下，用 `close --reason sensitive_step` 关闭会话，或者请用户在手机上自己完成。
- 不关闭安全设置、不绕过锁屏、不安装 APK、不改系统设置。拒绝或黑屏就如实报告。
- 不做反检测，不承诺应用识别不到自动化。

遇到权限、告警或兼容问题时读 [安全边界](references/safety.md)。

## 汇报

说明测试了什么、关键截图路径、遇到的打断和用户的答复、有没有被应用拦截、会话是否已关闭。已验证设备：nubia NX888J（Android 17）、OPPO PLG110（ColorOS，Android 17）；其他机型首次使用时如实说明“未验证”。`layout`、选择器、`--settle`、标注截图、敏感应用确认这些能力还没有在真机上验证过，第一次用时如实说明。
