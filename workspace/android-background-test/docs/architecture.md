# 架构与实施计划

## 决策

- 名称强调用途：Android 后台测试；独立屏只是实现机制。
- 用户依赖 ADB + Python 标准库，npx 的 Node 安装依赖单列。
- 手机端临时 DEX JAR，不通过 PackageManager 安装 APK。
- 唯一 Python 脚本按需执行；后台截图没有电脑端 supervisor。
- 可选预览由同一脚本提供回环服务，网页用已有浏览器；暂不添加空白预览页。
- 按需图片不走视频编码；连续预览优先 MediaCodec + 浏览器解码，宿主需实测。
- 每台设备一个协调进程：首个会话启动、最后一个会话结束即退出，不常驻；统一管理包占用、屏/编码预算和任务观察，会话在进程内独立异常边界。协议不兼容的旧进程仍有会话时拒绝启动新版。不连接旧项目实例。
- 默认后台、不打扰手机。冲突或打断（息屏、用户打开被占用应用、已有同包任务等）时停止输入、报告原因，在 Agent 对话中询问是否允许抢占焦点接管；拒绝、60 秒无回应或用户已在操作则结束会话，任务归用户；同意只对本次冲突有效。
- 中文/文本输入按需连接 UiAutomation，用完断开；被其他工具占用时报错，不抢占；调用方显式请求才连接。
- 普通虚拟屏路线；VDM 不默认启用。NX888J 实测普通屏只在主屏亮屏且解锁时渲染：锁屏或息屏即中断（`device_locked`），60 秒内用户解锁则自动继续，否则结束会话；Agent 不尝试点亮或解锁（2026-10-30 用户确认，方案 A）。
- 手机端租约区分传输健康和任务有效期，不无限隐式续期。
- 输入队列、Agent/人工仲裁在手机端，不给网页全局输入能力。

## 构建交付

- 仓库根目录 `android-background-test/` 是唯一安装包；`workspace/android-background-test/` 是开发工程，只放手机端源码、构建脚本、测试和文档。
- 包内 SKILL.md、references、scripts/android.py 直接维护，修改不需要构建。
- `scripts/build.py` 只生成 runtime/server.jar 和 manifest.json；中间文件在系统临时目录。
- manifest 只记录 runtime 哈希、协议版本和能力列表。协议号只在 android.py 定义，构建时读取并嵌入服务；脚本与 runtime 协议不一致即拒绝。
- manifest 最后写入：构建中断时新 jar 与旧 manifest 哈希不符，会被 verify 发现。
- 具备设备能力之前 SKILL.md 带 `metadata.internal: true`；设备会话可用后已移除，公开发布。

编译需要 SDK platform 的 android.jar（只作编译依赖，不打包）；隐藏 API 通过反射调用。不需要 Gradle/Android Studio。DEX min-api 31。

## 安全

无 APK 安装不等于无执行风险。未来服务限制 shell 身份，授权限本设备/会话。不降低 SELinux、安全扫描或 USB 调试防护，不隐藏进程、不伪装可信应用。

DEX JAR 不走安装签名校验；分发信任由发布渠道承担，哈希用于完整性。清单不是数字签名。若以后需要安装式能力，另作显式功能而非兼容回退。

## 数值与依据

当前没有复制逆向实现、系统 flags 或设备参数，没有复用旧工程产物。

| 标签 | 项目 | 依据 |
| --- | --- | --- |
| GUESS（自定义策略） | 0.2.0-dev | 开发版本，不表示稳定性或兼容范围 |
| GUESS（自定义构建策略） | DEX min-api 31、Java release 8 | 用到 AttributionSource（API 31）与 getWindowsOnAllDisplays（API 30）；只在 API 37 验证 |
| SOURCE（格式约束） | ZIP 固定时间 1980-01-01 | Python zipfile/ZIP 时间范围，用于消除构建时间差异 |
| GUESS（自定义策略） | 文件权限 0644 | 显式 Python 读取，不直接执行分发脚本 |
| SOURCE（API 约束） | Python 3.9 起支持 Path.is_relative_to | https://docs.python.org/3/library/pathlib.html#pathlib.PurePath.is_relative_to |

退出码、协议字段与能力布尔值是项目协议设计，不冒充系统真值。引入平台常量或兼容结论前建立 SOURCE/PARTIAL/GUESS 出处，不能跨过缺口叠加行为。

## 阶段

### 工程初始化（完成）

DEX 构建、离线校验、本机依赖检查、npx skills 发现与复制。

### 后台核心（0.2.0-dev 已实现，NX888J、PLG110 验证）

接口与状态机见 [protocol.md](protocol.md)。

身份、shell 环境、socket、租约、包冲突锁、独立屏、启动归属、截图、单指输入、幂等清理。先覆盖中断/断线/崩溃，再进入授权真机测试。

外来任务侵入与租约到期同时发生时的退出策略是发布阻塞项。主屏不受影响、息屏和同包竞态不能只靠静态代码证明。

### 预览与接管（未实现）

原会话增加预览，不创建第二套服务、不改屏几何。人工接管原子冻结 Agent 并取消旧请求，交还后重新观察。浏览器退出不自动交还或销毁屏。

### 发布（未执行）

确定源码许可/第三方来源、可信版本与哈希，验证复制后的完整性以及宿主/ROM 兼容。格式校验不能替代运行验收。

## 安装规则参考

- https://github.com/vercel-labs/skills/blob/main/docs/creating-skills.md
- https://github.com/vercel-labs/skills/blob/main/docs/commands.md
- https://github.com/vercel-labs/skills/blob/main/src/skills.ts

采用仓库根目录 `<name>/SKILL.md` 布局，以实际安装器发现与隔离复制测试为证，不假设远端 main 永久不变。
