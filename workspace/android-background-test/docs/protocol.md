# 会话协议规格（v2）

状态：0.2.0-dev 已实现并在 nubia NX888J 验证，差异见 §10。依据见文末“数值与依据”；没有标 SOURCE 的机制都要先在授权真机上验证，才能写进实现。

## 1. 组成与角色

```text
Agent ──命令行──▶ android.py（电脑端，每条命令一个进程，无常驻）
                    │ adb server 协议：host:transport:<serial> + localabstract:<socket>
                    │ （仅接受 shell uid）
                    ▼
               协调进程（每台设备一个，有会话才存在）
                    ├─ 会话 A：虚拟屏 + 包占用 + 租约 + 观察
                    └─ 会话 B：……
```

- **电脑端不常驻**：所有状态都在手机端协调进程里。电脑端只在调用方工作目录保存会话凭据和截图。
- **不用 adb forward**：android.py 用 Python 标准库 socket 直接按 adb server 协议打开 `localabstract:` 流，每条命令一条连接，用完即关。不占电脑端口，也不留转发条目。SOURCE：android-use 实测 forward 会泄漏（OPPO 上积累 40+ 条）；NX888J 实测直连往返 3–40ms，而 exec-out 冷启动客户端约 1.24s（validation.md 第 1 轮）。
- 只有启动协调进程和推送 jar 时才调用 adb 命令行。
- 预览属于后续阶段，会额外需要流式通道，不在 v1 范围内。

## 2. 协调进程

### 启动与单实例

1. 推送 jar 到 `/data/local/tmp/android-background-test/<sha256>.jar`（按内容寻址）。目录 chmod 0700、文件 chmod 0600，因为设备默认会建成所有人可写（SOURCE NX888J）。每次启动前在手机端核对哈希。
2. 客户端连接 socket `android-background-test`，先握手。
3. 连不上就后台启动协调进程。进程名用 `--nice-name=android-background-test`（参数要放在 cmd-dir 之后），不伪装。NX888J 实测 nohup 启动后可脱离 adb shell 存活（SOURCE）。
4. 单实例靠 abstract socket 的 bind 原子性保证：抢到 bind 的进程才是协调进程，其余直接退出。

### 握手

客户端发送协议号和 jar 哈希，协调进程返回自己的协议号、版本、哈希和会话数。

| 情况 | 处理 |
| --- | --- |
| 协议号一致 | 继续 |
| 协议号不一致，会话数为 0 | 请求旧进程退出，再启动新版 |
| 协议号不一致，仍有会话 | 拒绝，返回 `coordinator_incompatible`，不强行替换 |
| 哈希不同、协议号相同 | 允许，结果中报告版本差异 |

### 访问控制

- 只接受 peer uid = 2000（shell）的连接。abstract socket 对手机上所有应用可见，不校验 uid 的话，任何应用都能借 shell 身份注入输入。
- 会话操作还要校验会话令牌，防止同一台手机上的不同 Agent 互相操作。
- `list` 只返回会话摘要，不返回令牌。

### 退出

- 最后一个会话结束后立即退出。
- 启动后一段时间内没有建立任何会话也退出（GUESS 30 秒）。
- 崩溃时，虚拟屏随进程销毁；有 DESTROY_CONTENT_ON_REMOVAL 时，应用任务不会弹到主屏（SOURCE E9'）。重启后不恢复旧会话，电脑端收到 `session_lost`。

## 3. 会话

### 创建：open

| 参数 | 说明 |
| --- | --- |
| `--serial` | 必填 |
| `--component` | 必填，`包名/Activity` |
| `--workdir` | 必填，电脑端凭据与截图目录 |
| `--user` | 可选。默认当前前台用户，解析后的值在结果中报告；其他用户或工作资料必须显式指定 |
| `--width` | 可选。截图与坐标宽度，默认 720，高按主屏比例。会话屏本身按主屏逻辑尺寸与密度创建（尊重 wm size/density 覆盖），不可调：密度不同时，按 Application 资源取密度的 App 会整体放大（KB android-use commit 8cd7be8，PLG110 设置/天气约 1.5 倍） |

返回 `sessionId`、`displayId`、`state`。令牌只写入 `<workdir>/<sessionId>/session.json`，文件权限 0600，不输出到 stdout。

### 虚拟屏

- 普通虚拟屏，带 OWN_CONTENT_ONLY、OWN_FOCUS、STEAL_TOP_FOCUS_DISABLED、DESTROY_CONTENT_ON_REMOVAL。
  - 不加 PUBLIC、PRESENTATION 和系统装饰。SOURCE：android-use ADR D32。公开屏可以被任何应用枚举，并叠加 Presentation 窗口诱导 Agent。
  - STEAL_TOP_FOCUS_DISABLED 是“不打扰主屏焦点”的关键（SOURCE：PLG110/NX888J 对照实测）。
  - flag 常量值已在 NX888J 运行时 dump（validation.md 第 1 轮）；组合效果以真机为准。
- 闲置时挂占位 Surface，截图时临时换成全尺寸 ImageReader，截完换回，不新建屏。SOURCE：android-use m3a，720x1568 约 212ms。
- 创建可信屏被拒（P1-6）时返回 `display_permission_denied`，不悄悄降级为非可信屏。
- 单进程最多 32 块虚拟屏（SOURCE）。协调进程直接拒绝第 33 个会话，返回 `display_limit`，不等系统静默失败。

### 状态机

```text
            open
              │
   ┌──────────┴───────────┐
   ▼                      ▼
running ◀──grant── interrupted ──deny / 超时 / 用户在用──▶ ended
   │                      ▲
   └──检测到冲突/打断─────┘
running ──close / 租约到期 / 协调进程退出──▶ ended
```

- **running**：接受截图和输入。
- **interrupted**：截图仍可用（便于说明情况），输入一律拒绝。每条命令返回中断信息，退出码 3。
- **ended**：清理已完成，只能查询结束原因。

### 租约

- 每条会话命令都续期。空闲超过租约时长（GUESS 10 分钟）即结束会话并清理。
- 断线不立即结束会话，由租约兜底。这样 USB 短暂断开不会丢掉会话，Agent 失联也不会永久占着资源。
- 不提供无限期会话。

### 关闭：close

1. 停止输入。
2. 对**仍在测试屏上**的每个任务调用 removeTask，已离开测试屏的任务归用户，不碰。只释放屏的话，最近任务列表会留下记录并排在第一（SOURCE NX888J）。
3. 释放虚拟屏。
4. 解除包占用。

关闭过程中，任何任务都不能落到主屏前台。默认不 force-stop 应用进程。

## 4. 冲突与打断（已定原则的实现）

### 中断信息

```json
{
  "state": "interrupted",
  "reason": "user_opened_app",
  "conflictId": "c-…",
  "detail": "用户在手机上打开了示例（com.example.app），系统已把测试中的任务交给用户",
  "askUser": "是否允许 Agent 接管示例（com.example.app）？只有你已离开该应用时才能接管。",
  "expiresInSeconds": 60
}
```

### 询问规则

- `askUser` 是给 Agent 的提示语，由 Agent 原样或改写后在对话里问用户。
- 提示语里的应用写成“应用名（包名）”。应用名按手机当前语言从 APK 资源读取，读不到时只写包名；会话状态里的 `appLabel` 字段是同一个值。
- **只有用户在对话中明确同意**，Agent 才能调用 `resolve --grant`；Agent 不得自己替用户同意。
- 计时从该中断**第一次报告给电脑端**时开始（GUESS 60 秒）。超时就结束会话。
- 还没报告之前不计时，但空闲租约照常计时。

### 各类原因

| reason | 检测时机 | 立即动作 | grant 后 | 自动结束条件 |
| --- | --- | --- | --- | --- |
| `existing_task` | open 时发现用户已有同包任务（前台、后台、最近任务） | 不启动，用户任务保持不动 | standard 应用另建任务，用户任务不动；singleTask 应用把用户任务移入测试屏 | 超时或拒绝 |
| `user_opened_app` | 本会话任务离开了测试屏（nubia：系统把任务搬到主屏），或测试屏出现由主屏启动的同包任务（ColorOS） | 停止输入；若任务仍在测试屏则还给主屏；已归用户的任务不再移除 | 用户已离开该应用才能移回测试屏，否则 grant 失败并结束 | 用户仍在前台使用、超时或拒绝 |
| `device_locked` | 主屏息屏或停在锁屏，测试屏停帧（NX888J：两种情况都零帧） | 停止输入 | 普通屏路线无可行动作（见下） | 超时或拒绝 |
| `foreign_task` | 测试屏上出现不是本会话启动的任务 | 移回主屏后台，停止输入 | 继续测试，外来任务不再拉回 | 超时或拒绝 |

几点说明：

- **`device_locked` 的自动恢复**：用户在超时前自己解锁了手机，打断自动解除，会话继续在后台运行。
- **`device_locked` 的 grant 待重新决定**：点亮屏幕没用，锁屏下照样零帧；ALWAYS_UNLOCKED 对普通屏无效（SOURCE NX888J）。普通屏路线下 Agent 没有办法自行恢复，见 design-review.md P0-1。
- **`user_opened_app` 的处理**：grant 之前先检查该应用是否还在主屏前台。用户仍在用，就不能从用户手里拿走，这对应原则中的“用户聚焦就结束”。
- **同意只对一次冲突有效**：同一会话再出现冲突，会生成新的 conflictId，重新询问。
- **Agent 之间的占用不进入询问流程**：包已被其他会话占用时，直接拒绝，返回 `package_busy`，不提供抢占其他 Agent 会话的选项。
- **检测机制待验证**：任务观察用 TaskStackListener 还是轮询，以及 shell 是否有对应权限，都是 PARTIAL。没有 ActivityController 时，检测存在延迟，延迟值待测（GUESS）。“用户可能先看到短暂无响应”这一点要写进用户说明。

## 5. 命令

所有命令都输出一行 JSON。`open`、`list` 必须带 `--serial`；会话命令带 `--workdir --session`，serial 取自 open 时写入的 session.json。

| 命令 | 作用 | 备注 |
| --- | --- | --- |
| `verify` / `doctor` | 离线校验、本机检查 | 已实现 |
| `open` | 创建会话 | 见 §3 |
| `status` | 会话状态与中断信息 | 只读 |
| `screenshot` | 截图存入 `<workdir>/<sessionId>/`，返回路径 | interrupted 时可用 |
| `tap` / `swipe` / `key` | 注入输入，带 displayId | 只在 running 时可用 |
| `text --value` | 写入当前焦点输入框 | 见 §6 |
| `resolve --conflict ID --grant/--deny` | 回应中断 | 只能在用户明确表态后调用 |
| `launch` | 在会话屏上重新启动应用 | 同样做已有任务检查 |
| `close` | 结束会话 | 幂等 |
| `list` | 列出本设备会话摘要 | 不含令牌 |
| `layout` | 会话屏控件树 | v2，见 §11.2 |
| `scroll` | 慢速滚动，可滚到目标出现 | v2，见 §11.4 |

v2 起，`tap`、`text` 支持 `--ref`、`--target`；输入命令支持 `--observation`、`--settle`；`screenshot` 支持 `--annotate`、`--format`、`--clip`；`open` 支持 `--accept-risk`、`--no-accessibility`；`close` 支持 `--reason`。

同一会话内的命令串行执行，不同会话之间并行。

## 6. 文本输入（UiAutomation 按需）

1. 找到测试屏上的焦点可编辑节点。`getWindowsOnAllDisplays` 需要 API 30+，PARTIAL。
2. 连接 UiAutomation，必须带 FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES，并在 serviceInfo 里加上 FLAG_RETRIEVE_INTERACTIVE_WINDOWS（不加则看不到测试屏窗口，SOURCE NX888J）。不带这个 flag，会暂停用户已开启的无障碍服务（读屏、密码管理器等），违反不打扰原则。
3. 执行 ACTION_SET_TEXT，然后立即断开。

注意事项：

- **会替换整个输入框的内容，不是追加**。调用方需要追加时，自己拼好完整文本。
- **UiAutomation 被其他工具占用时**：返回 `uiautomation_busy`，不抢占。
- **没有焦点输入框、或写入失败时**：返回 `no_focused_editable` 或 `set_text_failed`，不改用剪贴板或输入法。
- **只支持 ASCII 时可退回按键注入**：仅限调用方显式指定 `--via keys`。
- **连接期间应用能察觉到无障碍服务已开启**：这一点写进用户说明。

## 7. 退出码与错误

| 退出码 | 含义 |
| --- | --- |
| 0 | 成功 |
| 1 | 内部错误 |
| 2 | 用法错误或能力未实现 |
| 3 | 会话 interrupted，需要询问用户 |
| 4 | 被拒绝或无法执行：`package_busy`、`display_limit`、`coordinator_incompatible`、`no_task`（会话屏上没有应用，需 `launch`）、`no_frame`（有应用但 3 秒内没出帧）等 |
| 5 | 设备或传输错误：adb 不可用、设备离线、`session_lost` |

错误统一输出 `{"ok": false, "error": "<code>", "detail": "…"}`。出错后不自动重试会改变设备状态的操作。

## 8. 对 SKILL.md 的要求（设备能力上线时）

- 退出码为 3 时，必须把 `askUser` 转述给用户并等待答复。用户没有明确同意，不得调用 grant。
- 汇报中说明会话是否被打断及原因。
- 文本输入会让应用察觉到无障碍服务，涉及银行、支付类应用时先提醒用户。

## 9. 数值与依据

| 标签 | 数值/行为 | 依据 |
| --- | --- | --- |
| SOURCE | 单进程最多 32 块虚拟屏，第 33 块静默返回 null | KB android-use §5 |
| SOURCE | adb forward 会泄漏积累（40+ 条） | KB android-use m3e |
| SOURCE | adb 直连 localabstract 往返 3–40ms；exec-out 冷启动约 1.24s；nohup 进程脱离 adb 存活；peer uid=2000 | validation.md 第 1 轮（NX888J） |
| SOURCE | DESTROY_CONTENT_ON_REMOVAL 让屏销毁时任务不弹回主屏前台 | KB android-use E9'，commit 02e208a |
| SOURCE | STEAL_TOP_FOCUS_DISABLED 是不抢主屏焦点的关键 | KB android-use，commit 0341342 / 248eee9 |
| SOURCE | 去掉 PUBLIC/PRESENTATION/系统装饰后功能正常 | KB android-use ADR D32 |
| SOURCE | 换 Surface 截图，720x1568 约 212ms | KB android-use m3a |
| SOURCE | SET_TEXT 在原生和 WebView 中均可输入中文 | KB android-use E7' |
| SOURCE | 测试屏点击生效且主屏焦点不变；SET_TEXT 写中文约 150ms；两种截图方式均可用 | validation.md 第 1 轮（NX888J，亮屏解锁） |
| SOURCE | standard 应用用 MULTIPLE_TASK\|NEW_DOCUMENT 另建任务；singleTask 应用仍会被搬走 | KB android-use E12 |
| PARTIAL | 任务观察机制、保持亮屏机制 | 名称或机制已知，本项目未实测 |
| PARTIAL | FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES 不会暂停已开启的无障碍服务 | Android 公开 API 文档描述，未实测 |
| GUESS | 询问超时 60 秒 | 用户接受的默认值 |
| GUESS | 空闲租约 10 分钟、协调进程无会话 30 秒退出 | 自定义策略 |
| GUESS | 退出码 0–5、错误码、socket 名、jar 路径 | 项目协议设计，不是系统真值 |
| GUESS | 冲突检测延迟 | 未测 |
| GUESS | layout 最多 1500 节点、label 80 字、settle 300ms 间隔且最长 3 秒、scroll 500ms 滑屏高/宽 50% 且默认最多 8 次、纯色判定亮度标准差 < 2 | v2 自定义策略，未实测 |
| GUESS | 敏感应用名单、应用名关键词、风控提示词 | 人工整理，不完整 |
| PARTIAL | FLAG_SECURE 内容在非安全虚拟屏上显示为黑 | Android 公开行为描述，本项目未实测 |
| PARTIAL | getBoundsInScreen 在虚拟屏上返回该屏坐标 | 未实测；坐标错位时先查这里 |

## 10. 实现说明（0.2.0-dev）

- 线路协议：一条连接一个请求。请求是一行 JSON（含 `op`、`sessionId`、`token`）；响应是一行 JSON，截图响应带 `payloadBytes`，其后紧跟 PNG 原始字节。
- 已有任务检查只针对会复用任务的启动模式（singleTask、singleInstance 等，读 ActivityInfo.launchMode；读不到按会复用处理）。standard 应用一律用 NEW_DOCUMENT|MULTIPLE_TASK 另建任务，不触发询问。
- 关闭时移除“在会话屏上出现过且没有被用户拿走”的全部任务，包括应用自己退出后只剩最近任务记录的任务。
- 协调进程把这些任务 id 写到 `owned-tasks`（0600）。被强制杀死后，下次启动只移除已经不存在的任务的最近任务记录，仍然存活的任务可能已归用户，不碰。未捕获异常时先结束全部会话再退出。
- 打断检测每 500ms 一次（GUESS）。`user_opened_app` 只实现了“任务离开会话屏”式检测，NX888J 和 PLG110（ColorOS 17.0.0.105）都是这种行为；KB E12b 记录的 ColorOS 式（任务留在会话屏、用户看不到）在本项目的屏配置下没有复现，原因未确认（可能是屏 flag 或 ROM 版本差异），其检测和 `foreign_task` 尚未实现。
- 截图前先刷新会话任务；会话屏上没有任务时返回 `no_task`，不再等 3 秒后报 `no_frame`。
- 应用名不能用 `PackageManager.loadLabel`：app_process 自建的 ActivityThread 没有 ConfigurationController，加载其他应用资源会 NPE，`loadLabel` 随即退回 Application 类名（SOURCE PLG110 logcat）。改为 AssetManager 加载 APK + 系统全局 Configuration。
- `probe` 命令未实现；兼容性问题在 `open` 时以错误码返回。

## 11. v2：控件树、按控件操作、防过期操作、敏感应用（待真机验证）

状态：已编码并通过离线测试，**没有在真机上跑过**。下面所有设备行为都是 PARTIAL，验证前不写进"已验证"。协议号升到 2：旧协调进程没有会话时自动替换，有会话时返回 `coordinator_incompatible`（规则见 §2）。

### 11.1 敏感应用：先告知风险，再同意

- 先例：豆包手机助手（2025-12）因为注入输入和读屏，触发了微信风控，导致用户掉线甚至封号；银行、支付宝、淘宝也拒绝它运行（新闻报道，见知识库调研笔记 §9）。本项目同样会注入输入、连接无障碍、运行在虚拟屏上，**不做任何反检测**。
- `open` 前先判定目标应用是否敏感，任一命中即算：
  - 电脑端：包名在 android.py 的 `SENSITIVE_PACKAGES` 中，或包名某段含 `bank`、`securities`、`wallet`、`alipay`、`unionpay`（GUESS：人工整理，不完整）。
  - 手机端：应用名含 银行、证券、支付、钱包、保险、基金、信用卡、理财（GUESS）。
- 命中且没带 `--accept-risk` 时，不建屏、不启动，返回 `sensitive_app`（退出码 4），附 `category`、`appLabel`、`askUser`。Agent 必须把 `askUser` 转述给用户；用户在对话里明确同意后，才能加 `--accept-risk` 重新 open。
- 同意后建议同时加 `--no-accessibility`：会话内不再连接无障碍，`layout`、按控件操作、`text` 和标注截图都会返回 `accessibility_disabled`。

### 11.2 控件树：layout

- 每次调用都单独连接 UiAutomation（flag 同 §6），读完立即断开，不常驻。读取范围只有会话屏的窗口（`getWindowsOnAllDisplays().get(displayId)`）。
- 默认只返回屏幕上可见、并且有文字/描述或可交互的节点；`--full` 返回全部节点，看不见的标 `offscreen`。最多 1500 个节点（GUESS）。
- 节点字段：`ref`（e1…）、`class`（简名）、`text`、`desc`、`id`（去掉 `包名:id/`）、`hint`、`label`（自身没有文字的可点击节点，取子节点文字拼接，最长 80 字）、`bounds` [l,t,r,b]、`center` [x,y]、`actions`（click/longClick/scroll/edit/check）、`state`（checked/focused/selected/disabled/password）。
- 坐标换算到截图坐标系，和 `tap` 通用。

### 11.3 观察与过期操作

- `screenshot` 和 `layout` 每次都会生成新的观察 `observation`（o1、o2…）。refs 只属于最近一次 layout，或标注截图的那次观察。
- **任何输入**（tap/swipe/key/text/scroll/launch）、打断恢复，都会让当前观察失效。
- `--ref eN` 只能对仍然有效的观察使用，失效返回 `stale_observation`，ref 不存在返回 `unknown_ref`。
- 坐标操作可以带 `--observation oN`。带了就校验：不是最新观察，或者观察之后已经有过输入，都返回 `stale_observation`。不带不校验，兼容旧用法。

### 11.4 按控件操作

- 选择器写法：`--target "text=确定"`，可以组合，如 `id=email,class=EditText`。键有 text、id、desc、class；text 同时匹配 text、hint 和 label。先找完全相等的，找不到再找包含的。
- `tap --target`：内部先做一次 layout（生成新观察）。只命中一个就点中心；没有命中返回 `element_not_found`，附最多 15 个候选文字；命中多个返回 `element_ambiguous`，附候选节点（带这次观察的 ref），Agent 可以改用 `--ref`。
- `text --target`：只在可编辑节点里找，先 ACTION_FOCUS，再 ACTION_SET_TEXT。`text --ref`：先点 ref 中心，再写当前焦点输入框。
- `scroll up|down|left|right [--until 选择器] [--max N]`：在会话屏中部慢速滑动 500ms，距离为屏高或屏宽的 50%（GUESS）。带 `--until` 时每滑一次查一次控件树，出现目标就停下并返回该节点，最多 N 次（默认 8），找不到返回 `element_not_found`。

### 11.5 settle 与变化

- 输入命令加 `--settle`：动作后每 300ms 读一次控件树，连续两次一样就算稳定，最长 3 秒（GUESS）。
- 返回 `settled`、新的 `observation`，以及和上一次控件树相比的 `changes.added`（节点，带 ref，最多 40 个）和 `changes.removed`（文字，最多 40 个）。之前没有读过控件树时，直接返回完整 `nodes`。
- 稳定判断用的是控件树，不是画面，所以动画和视频这类变化看不出来。

### 11.6 截图选项

- `--annotate`：先读控件树，再在截图上给可交互节点画框，标上 ref 编号；同时返回节点列表，refs 绑定这次观察。会连接无障碍。
- `--format jpeg --quality 1..100`、`--clip x,y,w,h`（截图坐标）。裁剪后坐标仍然用整屏坐标系，返回里带 `clip` 方便换算。
- 每张截图都会算 `blankFrame`：32×32 采样亮度标准差 < 2 即为纯色（GUESS）。纯黑通常说明应用开了防截屏保护（FLAG_SECURE 内容在非安全虚拟屏上显示为黑，PARTIAL），也可能只是还在加载。

### 11.7 风险信号与无法继续时的说明

- 电脑端扫描 layout、settle、标注截图返回的文字，命中风控提示词（"环境异常""请关闭无障碍""检测到""被迫下线"等，见 android.py `RISK_KEYWORDS`，GUESS）时，加 `riskSignals`（命中词和原文）和 `riskNotice`。这只是提示，不改变退出码，Agent 必须看截图确认。
- 所有错误返回都带中文 `userMessage`，说明发生了什么、为什么、用户能做什么，Agent 可以直接转述。
- `close --reason app_blocked|sensitive_step|user_stopped|done`：记录结束原因，`status` 和 `list` 里能看到。
- 发现应用拦截或风控时，Agent 必须：停止操作（不重试、不换方式绕过）→ 用 `--reason app_blocked` 关闭会话 → 按 SKILL.md 的"无法继续时的汇报"模板告诉用户。

### 11.8 不做的

- 预览：需要新增流式通道，超出 v1 范围。
- 应用静音：shell 能用的办法只有改该应用的 appops（PLAY_AUDIO），这会改应用设置，而且被强制结束时设置无法恢复，和"不改系统设置"冲突。暂不实现。
- 反检测：伪装注入事件、隐藏无障碍、隐藏虚拟屏，都不做。
