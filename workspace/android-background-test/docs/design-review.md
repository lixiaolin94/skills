# 设计评审（2026-10-29）

评审对象：docs/architecture.md 的当前决策。证据来自个人知识库 `notes/projects/android-use.md`（下称 KB）和 android-use 仓库 evidence，均为 OPPO PLG110 / Android 17 / shell 身份实测，除非另注。**本项目尚未在任何设备上复测**，结论不能直接推广到其他 ROM。

## 已定原则（2026-10-29 用户确认）

默认后台、不打扰手机。遇到冲突或打断时：

1. 停止 Agent 输入，报告具体原因（息屏、用户打开了同一应用、已有同包任务等）。
2. 在 Agent 对话中询问用户是否允许 Agent 抢占焦点接管。
3. 用户拒绝、未回应、超时，或用户已在手机上操作该应用 → 结束会话并清理，手机上的任务归用户。
4. 用户明确同意 → 继续；同意只对本次冲突有效，不延续到后续冲突。

询问超时 60 秒：GUESS（用户接受的默认值，非实测）。询问走 Agent 对话而不是手机弹窗，弹窗需要额外 APK 或悬浮窗权限。
P0-1/2/3 按此原则处理，不再需要单独决策；VDM 仍不默认启用。

## P0：与“后台测试、不打扰手机”的产品承诺直接冲突

### 1. 普通虚拟屏随主屏息屏而停止

- SOURCE（本项目 NX888J 实测，validation.md 第 1 轮）：**不只是息屏，主屏亮着但停在锁屏时也零帧**；+OWN_DISPLAY_GROUP、+ALWAYS_UNLOCKED 都无效。普通屏路线下“询问是否点亮屏幕”没有意义。**已定（方案 A）**：锁屏/息屏即中断，60 秒内解锁自动继续，否则结束；VDM 暂不做。

- SOURCE：普通 VirtualDisplay 与主屏同电源组，息屏即停帧；VDM + ALWAYS_UNLOCKED 在息屏锁屏时仍持续运行（KB §4 E3'，commit 8e3b22b）。
- SOURCE：android-use VdRoute 的已知限制同上（evidence/2026-10-22-m3d/result.txt）。
- 影响：用户锁屏离开后 Agent 测试会暂停。当前架构只选了普通屏路线，相当于把“后台”限定为“手机亮屏期间”。
- 选项：
  - A. 接受限制：息屏或锁屏时返回 `device_locked`，不唤醒主屏。实现最简单，但必须写进能力声明。
  - B. VDM 路线：需要给 shell 建 CDM 关联，会留下持久系统状态，依赖可能被收紧的系统口子（KB 风险 R2）；必须由用户显式同意，不能静默启用。
  - C. 保持亮屏：会打扰用户，不推荐。
- 已定：默认 A，按“已定原则”询问是否允许点亮屏幕；显示路线保持可替换，B 不默认启用。

### 2. 用户从桌面打开被 Agent 占用的应用，手机上无反应

- SOURCE（NX888J 实测）：nubia 的行为相反，会把 Agent 的任务整体搬到主屏，用户看到的是 Agent 测试中的页面。两种行为都要检测。

- SOURCE：ColorOS 不遵守 RECENTS 策略；用户从桌面启动被 Agent 占用的 App 时，会被路由到虚拟屏，手机上看不到（KB §4 E12b）。android-use 用 ActivityController（LaunchGuard）拦截后在主屏另开（E12c）。
- 影响：当前设计禁用了全局 LaunchGuard，同包冲突也只在 Agent 启动时检查。用户在会话期间自己打开同一应用，这个场景没有覆盖，直接违背“不影响前台使用”。
- 选项：
  - 手机端观察到用户发起的启动落到会话屏时，把任务移回主屏，会话标记为让渡（displaced），Agent 停止输入。没有 ActivityController 只能靠轮询，用户可能先看到一段时间的无响应；延迟待测（GUESS）。
  - 系统中没有其他 ActivityController（uiautomator、monkey 等）时，可选启用 LaunchGuard；有冲突时自动退回轮询。
- 已定：立即把任务还给用户并停止输入，按“已定原则”询问是否接管。

### 3. 启动时会搬走用户已有任务

- SOURCE：普通启动会把用户同包任务整个搬进虚拟屏；standard 应用可用 MULTIPLE_TASK|NEW_DOCUMENT 另建任务，singleTask 应用仍会被搬走（KB §4 E12）。
- 影响：同包冲突检查必须覆盖**后台和最近任务**，不能只看前台；singleTask 类应用只要用户已有任务就必须拒绝或排队。
- 状态：已有“同包冲突拒绝”的方向，但检查范围要写进协议规格。

## P1：会影响可用性或要修订的决策

### 4. 没有中文文本输入通道

- 首版排除了 UiAutomation 和输入法，按键注入无法输入中文。
- SOURCE：可选通道及代价——SET_TEXT 需要 UiAutomation（全系统单例，运行时 ACCESSIBILITY_ENABLED 对应用可见）；粘贴会污染用户剪贴板、WebView 无效；自带输入法需要安装 APK（KB §4 E7'，android-use 设计 v0.4 §9）。
- 建议：按需连接 UiAutomation、空闲断开，被其他工具占用时明确报错；由调用方显式请求，不默认开启。**已定。**

### 5. “每会话独立手机进程”无法可靠实施全局约束

- 包锁目前放在电脑端共享登记目录，覆盖不了多台电脑、多个安装版本或其他工具。
- SOURCE：单个进程最多 32 块虚拟屏，第 33 块静默返回 null（KB §5）；跨进程总上限未知。720p 编码约 10 路，第 11 路失败（KB §4，PLG110）——预览需要全局编码预算。
- 每个会话各自轮询任务和仲裁接管，会重复，也容易互相矛盾。
- 建议改为**设备级协调进程**：一台设备一个守护进程，统一负责包锁、屏/编码预算和任务观察；会话在进程内独立异常边界。没有会话时退出，不常驻。代价是进程崩溃会影响全部会话，需要单会话隔离兜底。协议不兼容的旧守护进程仍有会话时，拒绝启动新版而不是强行替换。
- **已定**，architecture.md 已改写。

### 6. 可信虚拟屏依赖 shell 权限，可能被收回

- SOURCE：Android 15 QPR2 beta 曾移除 shell 的 ADD_TRUSTED_DISPLAY，16 beta3 恢复（KB §2，scrcpy issue）。
- 影响：设备是否允许 shell 创建所需类型的屏，必须逐设备探测并在 doctor 中报告；不能只按 SDK 版本推断。去掉 trusted 后焦点、输入法等行为会如何变化：PARTIAL，待测。

## P2：本轮已修复的工程问题

| 问题 | 处理 |
| --- | --- |
| 控制脚本有源码和包内两份，构建时复制，容易不一致 | 包内 `scripts/android.py` 为唯一源码，构建不再复制 |
| manifest 给 SKILL.md 等文档算哈希，改文档就得装 JDK 重新构建 | manifest 只记录 runtime 哈希；协议号只在脚本中定义，构建时读取并嵌入服务 |
| 客户端写死 `status == bootstrap`，未来版本会被自己拒绝 | 改为协议版本 + 能力列表；`deviceTestingReady` 由能力推导 |
| bootstrap 会出现在公开安装列表，用户装了却什么都做不了 | SKILL.md 加 `metadata.internal: true`。SOURCE：skills CLI 默认隐藏 internal 技能，`INSTALL_INTERNAL_SKILLS=1` 或显式 `--skill` 仍可安装（vercel-labs/skills `src/skills.ts` parseSkillMd，本机 1.7.4 实测）。具备设备能力后移除（已移除） |
| 构建中间目录 `.build/` 放在工程内 | 改用系统临时目录，去掉相关忽略规则和清理参数 |

## 没有问题、保持不变的决策

- 用户依赖 ADB + Python 标准库；DEX JAR + app_process，不安装 APK。
- Skill 为主，MCP 作为以后共用同一核心的可选入口。
- 按需截图不走视频；预览和接管复用同一会话。
- 失败即停止，不回退主屏、不重启 ADB、不清理归属不明的资源。

## 下一步

P0/P1 决策已定，会话协议规格草案见 protocol.md。下一步按其中 PARTIAL 项安排授权真机验证，再进入手机端实现；P1-6 在首次授权真机测试时探测。
