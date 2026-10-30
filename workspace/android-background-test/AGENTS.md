# Android 后台测试

- 使用简体中文，先读 README.md 和 docs/architecture.md。
- 当前 0.2.0-dev：后台核心已实现，只在 nubia NX888J、OPPO PLG110 验证过；不要把未验证机型描述为可用。
- android/ 是手机端源码；仓库根目录 android-background-test/ 是唯一交付包，其中 SKILL.md、references、scripts/android.py 直接维护。
- runtime/server.jar 与 manifest.json 只由 python3 scripts/build.py 生成，不手改；协议号只在 android.py 定义。
- 构建中间文件在系统临时目录；未来截图、日志和凭据写调用方 workdir。
- 设计决策见 docs/architecture.md；手机端实现前先写会话协议规格。
- 不依赖或修改 android-use、AndroMeld，不复制闭源产物。
- 连接设备测试需要用户授权；实验探针在 experiments/，不进安装包。
- 设备命令必须显式 serial，不重启 ADB、不接管其他会话、不回退主屏。
- 不关闭安全设置、伪装服务或绕过 ROM 权限。
- 未经要求不 commit、push、发布或安装到用户技能目录。
