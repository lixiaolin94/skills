# 初始化验证记录

本次仅本机构建与临时目录安装测试，没有执行 ADB 或操作设备。

- SOURCE：JDK javac 17.0.14、D8 构建成功，实际工具身份保存在 Skill manifest.json。
- SOURCE：Python 3.15.2 下运行 unittest，通过 10 项测试：完整包、独立复制、改文档无需重建、篡改、缺失产物、协议不一致、路径越界、serial 必填、open 拒绝、假 adb 哨兵验证未执行设备命令。
- SOURCE：同源码与同工具链连续构建的所有分发文件哈希一致；不推断跨工具链可复现。
- SOURCE：npx skills@1.7.4 默认列表不显示 android-background-test（internal）；INSTALL_INTERNAL_SKILLS=1 时可发现。
- SOURCE：显式 --skill android-background-test --copy 安装到系统临时目录，脚本、文档、manifest、DEX JAR 全部复制，无源码外链；复制后 verify 成功。
- SOURCE：当前 bootstrap server.jar 为 1824 字节；不是完整服务的体积预测。
- SOURCE：Skill Creator 校验脚本依赖 PyYAML，本机默认 Python 未配置；未安装额外 Python 包。YAML 发现由实际 skills CLI 验证，另检查相对文档链接。

## 未验证

DEX 在 Android 上运行、shell 身份初始化、独立屏、截图、输入、租约、清理、预览、接管和 ROM 兼容全部未验证或未实现。正式用户技能目录未安装；没有 Git 提交或远端发布。

## 真机验证第 1 轮（2026-10-30，nubia NX888J）

设备：nubia NX888J，Android 17（SDK 37），build 2.7.0.0-ut test-keys，incremental 20261029.040529。用户已授权测试。测试版系统，结论不能直接推广到零售 ROM。

工具：`experiments/probe/`（实验探针，不进安装包），原始日志在 `work/evidence/`（不提交）。

- SOURCE（dumpsys package）：shell 已授予 ADD_TRUSTED_DISPLAY、ADD_ALWAYS_UNLOCKED_DISPLAY、INJECT_EVENTS、MANAGE_ACTIVITY_TASKS、REAL_GET_TASKS、DEVICE_POWER、WAKE_LOCK、CAPTURE_VIDEO_OUTPUT；CREATE_VIRTUAL_DEVICE 为 GRANTED_BY_ROLE。
- SOURCE（运行时反射 dump）：VIRTUAL_DISPLAY_FLAG_* 取值 PUBLIC=0x1、PRESENTATION=0x2、SECURE=0x4、OWN_CONTENT_ONLY=0x8、AUTO_MIRROR=0x10、CAN_SHOW_WITH_INSECURE_KEYGUARD=0x20、SUPPORTS_TOUCH=0x40、ROTATES_WITH_CONTENT=0x80、DESTROY_CONTENT_ON_REMOVAL=0x100、SHOULD_SHOW_SYSTEM_DECORATIONS=0x200、TRUSTED=0x400、OWN_DISPLAY_GROUP=0x800、ALWAYS_UNLOCKED=0x1000、TOUCH_FEEDBACK_DISABLED=0x2000、OWN_FOCUS=0x4000、DEVICE_DISPLAY_GROUP=0x8000、STEAL_TOP_FOCUS_DISABLED=0x10000、ALLOWS_CONTENT_MODE_SWITCH=0x20000、MIRROR_ANIMATION=0x20000000、SKIP_COLOR_TRANSFORM=0x40000000。
- SOURCE：shell 身份 app_process 用系统 Context + DisplayManager 创建可信虚拟屏成功（请求 0x16548，耗时 5–14ms），`am start --display` 冷启动计算器约 0.5s，`input -d` 注入返回成功；全程 mTopFocusedDisplayId=0。
- SOURCE：`--nice-name` 必须放在 app_process 的 cmd-dir（`/`）之后，放在前面时进程无输出直接退出。
- SOURCE：**进程退出或释放屏后，任务界面销毁，但最近任务列表留下记录且排第一**。关闭时先对屏上每个任务 removeTask 再释放屏，最近任务列表恢复原样（removeTask 返回 true）。→ protocol.md 关闭流程必须包含移除任务。
- SOURCE：**主屏 Dozing 时测试屏零帧**，任务 visibleRequested=false。分别测试普通屏、+OWN_DISPLAY_GROUP（屏状态 OFF）、+ALWAYS_UNLOCKED、+两者，均为零帧。
- SOURCE：**主屏亮屏但停在锁屏界面时，普通屏同样零帧**（isKeyguardShowing=true，visibleRequested=false）。亮屏 + 锁屏 + ALWAYS_UNLOCKED 的组合因锁屏约 10 秒后自动熄灭，这次实际是在 Dozing 下测的，无效，待重测。
- 结论：这台设备上普通虚拟屏只在**主屏亮屏且已解锁**时渲染。P0-1 的影响比原先估计大：不只是息屏会打断，锁屏也会。

- SOURCE：`nohup app_process ... &` 启动的进程在 adb shell 退出后继续存活，PPID=1，进程名按 --nice-name 显示。
- SOURCE：LocalServerSocket 的 getPeerCredentials 对 shell 客户端和 adbd 直连都返回 uid 2000。
- SOURCE：每条命令走 `adb exec-out` 冷启动 app_process 客户端，单次约 1.24s（5 次 1.22–1.26s）；`adb exec-out true` 本身约 0.05s。
- SOURCE：电脑端 Python 直接按 adb 协议向 adb server 请求 `host:transport:<serial>` + `localabstract:abt-probe`，单次往返 2.9–39.6ms（首次最慢），不产生 forward 条目（前后 forward 列表不变）。→ 传输改为直连，替代 protocol.md 中的 exec-out 方案。

### 亮屏解锁后（00:09–00:11）

- SOURCE：可信普通屏 720x1600@320 正常渲染，任务 visibleRequested=true。
- SOURCE：截图两种方式都可用。① 建屏时挂全尺寸 ImageReader，保留最新帧：取帧 7–22ms，含 PNG 共 43–141ms。② 闲置挂 2x2 占位，截图时 setSurface 换全尺寸再换回：画面静止时也能出帧，取帧 32–43ms，含 PNG 约 130ms。第 1 轮的超时是息屏加探针队列竞态（SynchronousQueue 丢帧）共同造成的。
- SOURCE：`input -d <屏> tap` 在测试屏上生效（浏览器搜索框获得焦点、出现光标），主屏 mTopFocusedDisplayId 保持 0，用户前台应用（fluid.demo）焦点不变。
- SOURCE：测试屏输入框获得焦点后，没有在测试屏上弹出软键盘；InputMethod 窗口在 display 0 且 isOnScreen=false。
- SOURCE：UiAutomation（Context 构造 + connect(FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)）**必须再设 FLAG_RETRIEVE_INTERACTIVE_WINDOWS**，否则 getWindowsOnAllDisplays 拿不到测试屏窗口。设置后在浏览器搜索框 ACTION_SET_TEXT 写入“后台测试 abc 123”成功：连接 13ms，全程 147ms，截图可见文字。期间 enabled_accessibility_services 仍为 null。
- SOURCE：测试中途应用会对写入的文字做出反应（浏览器显示搜索建议），属于应用自身行为，汇报时要告知用户。
- SOURCE：所有场景关闭后，最近任务列表都恢复到测试前的样子。

### 用户参与的测试（00:12–00:16，用户同意）

- SOURCE：**用户从桌面点击被占用应用（浏览器）时，nubia 把 Agent 的任务 #60 整体从测试屏搬到主屏**（00:14:31 主屏在桌面，00:14:34 任务已在 display 0，visibleRequested=true）。用户看到的是 Agent 测试中的那个页面，测试屏变空。这与 ColorOS（E12b：留在虚拟屏、主屏无反应）相反，各 ROM 行为不同。
- SOURCE：熄屏后再点亮、停在锁屏（无安全锁：deviceLocked=0，locksettings disabled=false）时，普通屏和 +ALWAYS_UNLOCKED 都是零帧；建屏、启动应用都不会解除锁屏（对照组锁屏持续显示）。第一次锁屏测试中锁屏消失，复测无法重现，判断为用户手动解锁。
- 结论：**普通虚拟屏在这台设备上需要主屏亮屏且已解锁**；ALWAYS_UNLOCKED 标志对普通屏无效。有安全锁时的表现未测（不会在用户设备上设置密码）。
- SOURCE：探针目录 `/data/local/tmp/android-background-test` 推送后权限为 drwxrwxrwx，jar 为 rw-rw-rw-（设备 umask 导致）。→ 正式实现必须把目录设为 0700、文件设为 0600，并在每次启动前核对哈希。
- 测试结束已删除设备上的探针目录；无残留进程、虚拟屏、forward 条目。

未完成：任务观察机制（TaskStackListener 或轮询）及其延迟；有安全锁时的行为；FLAG_DONT_SUPPRESS 对已开启无障碍服务的效果（本机没有开启任何无障碍服务，无法观察）。

## 实现验证（2026-10-30，0.2.0-dev，NX888J，亮屏解锁）

- SOURCE：`open` 自动部署：目录 0700、jar 0600，旧版本 jar 自动删除，协调进程 PPID=1、进程名 android-background-test。
- SOURCE：单条命令含 Python 启动约 75–250ms（status 74ms、tap 149ms、screenshot 248ms）。
- SOURCE：点击、BACK、截图、中文 `text`（便签搜索框，“后台测试 中文输入”）在会话屏生效；主屏 mTopFocusedDisplayId 始终为 0；enabled_accessibility_services 保持 null。
- SOURCE：浏览器（会复用任务的启动模式，用户已有任务 #60）`open` 返回 existing_task、exit 3、未启动应用；`--deny` 后会话结束，用户任务留在主屏。
- SOURCE：发现并修复两个问题：① 应用被 BACK 退出后，关闭会话漏删最近任务记录 → 改为移除会话期间出现过的全部任务；② 截图回调拿帧超过 ImageReader 上限导致协调进程崩溃 → 只取一帧并捕获回调异常。
- SOURCE：`kill -9` 协调进程后残留的最近任务记录，在下次启动时被清理；旧会话返回 session_lost（exit 5）。
- SOURCE：离线测试 14 项全部通过（含模拟 adb server：令牌不出现在 stdout、session.json 为 0600、只用 host:transport 与 localabstract 服务、exit 3/4 映射）。

- SOURCE：计算器与便签两个会话并行（display 26/27），同包重复 open 返回 package_busy（exit 4），两会话并发截图成功，关闭后最近任务干净。

- SOURCE：空闲租约。最后一次续期约 00:32:15，之后只用 `list` 和 dumpsys 观察（不续期）；00:42:16 会话结束，endReason=lease_expired，测试屏消失；协调进程 00:42:45 记录 idle, exiting 后退出；计算器不在最近任务中，owned-tasks 已清空。之后本地 `close` 返回 session_lost 并删除 session.json。
- 期间用户短暂锁屏一次，但 20 秒一次的采样没有捕捉到（采样时都是 Awake、未锁屏），所以不能作为 device_locked 的证据。

- SOURCE（用户配合，status 约每秒一次）：00:55:57 用户锁屏 → interrupted/device_locked（grantable=false，倒计时 60s）；00:56:17 已亮屏但仍锁屏，保持中断；00:56:19 解锁 → 自动恢复 running。
- SOURCE：00:56:25 用户从桌面点开计算器 → interrupted/user_opened_app，任务 #87 被系统移到 display 0，会话 tasks 变为空；无人答复，00:57:26（首次报告后 60s）结束，endReason=consent_timeout；任务 #87 保留在用户手里（未被移除）；协调进程随后退出。

未验证：grant 路径（existing_task 接管、user_opened_app 在用户离开后接管）；ColorOS 等其他 ROM。

## 真机验证第 2 轮（2026-10-30，OPPO PLG110，用户配合）

设备：OPPO PLG110（ColorOS），Android 17（SDK 37），build PLG110_17.0.0.105(CN01)，零售版。亮屏解锁，用户已授权测试。

- SOURCE：verify/doctor 通过；`open` 自动部署并启动协调进程；会话屏截图 720x1600；计算器点 AC、7、+、8、= 得到 15；swipe、status、key BACK、launch 正常；便签搜索框 `text` 写入“后台测试中文”，再写“替换abc”时整框替换；主屏 topResumedActivity 始终是桌面。
- SOURCE：`close` 后会话屏销毁；协调进程在最后一个会话结束后约 30 秒退出。
- SOURCE：BACK 让应用退出、会话 tasks 变空后，截图原先在 3 秒后返回 `no_frame`（exit 4），容易被理解成系统拒绝。→ 已修复：截图前刷新任务，没有任务时立即返回 `no_task`。
- SOURCE：**user_opened_app**：用户从桌面打开被测的计算器后，任务 t1777 从会话屏移到 display 0，用户看到正常界面（不是黑屏）。会话变为 interrupted/user_opened_app。用户还在计算器里时 grant，返回 `user_active`（exit 4），会话结束，t1777 留在用户手里。行为和 NX888J 一致；KB E12b 的“留在虚拟屏、用户看不到”在本项目的屏配置下没有复现。
- SOURCE：计算器入口是 standard（APK manifest launchMode=0 未声明），用户已有 t1777 时 `open` 不询问，另建 t1778，t1777 不受影响，符合设计。天气、时钟入口为 singleTask（launchMode=2）。
- SOURCE：**existing_task**：用户已有天气任务 t1780 时 `open` 返回 existing_task（exit 3），未启动；用户同意后 grant，t1780 移到会话屏，主屏仍是桌面，截图正常；close 后 t1780 从最近任务移除。另一次 deny 后会话结束（user_denied），用户的天气任务保留。
- SOURCE：**device_locked**：10:32:25 锁屏 → interrupted/device_locked（grantable=false）；10:32:40 解锁 → 自动恢复 running，截图正常。
- SOURCE：询问中原来只写包名。→ 已改为“应用名（包名）”，会话状态增加 `appLabel`。`PackageManager.loadLabel` 在 app_process 下返回 Application 类名（com.android.calculator2.CalculatorApplication）；`getResourcesForApplication` 抛 NPE（ConfigurationController 为 null，logcat）。改用 AssetManager 加载 APK + ActivityManager 全局 Configuration 后得到“计算器”“天气”“便签”。实测 askUser：“是否允许 Agent 接管天气（com.coloros.weather2）的现有任务？……测试结束时会被关闭。”
- SOURCE：离线测试 14 项通过。

未验证：有安全锁时的行为；ColorOS 其他版本；应用名在非中文系统语言下的表现。
