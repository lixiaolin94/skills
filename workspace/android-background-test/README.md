# Android 后台测试开发工程

当前 0.2.0-dev：后台屏会话、截图、点击/滑动/按键、中文文字输入、打断与询问流程已实现，在 nubia NX888J（Android 17）和 OPPO PLG110（ColorOS，Android 17）上验证。预览和人工接管未实现。

## 仓库布局

```text
skills/                             Git 仓库
  android-background-test/          唯一可安装 Skill 输出
    SKILL.md
    references/safety.md
    scripts/android.py              控制入口（唯一源码，直接维护）
    runtime/server.jar              生成的 DEX JAR
    manifest.json                   生成的协议、能力与 runtime 哈希
  workspace/android-background-test/
    android/                        Java 源码
    scripts/build.py                构建工具
    tests/                          离线测试（含模拟 adb server）
    experiments/                    真机实验探针与场景，不进安装包
    docs/                           设计、评审与验证
    work/                           缓存与本地测试，不提交
```

仅 Android 工程从 workspace 的忽略规则中放行，用于公开源码、审计与重建。其他 workspace 项目的忽略状态不变。工程没有嵌套 Git 仓库，由上层 skills 仓库管理。

包内无工程目录外链。SKILL.md、references、scripts/android.py 直接在根目录包维护，修改无需重新构建；只有 runtime/server.jar 和 manifest.json 由构建产生。workspace 内不保留重复 SKILL.md，避免安装器误发现。构建中间文件放系统临时目录。

0.2.0-dev 起 SKILL.md 不再带 `metadata.internal`，出现在公开安装列表。

## 构建与测试

从 skills 仓库根目录运行：

```sh
python3 workspace/android-background-test/scripts/build.py
python3 -m unittest discover -s workspace/android-background-test/tests -v
python3 android-background-test/scripts/android.py verify
python3 android-background-test/scripts/android.py doctor
```

维护者需要 JDK、Android SDK Build Tools 的 d8 和任一 SDK platform 的 android.jar（默认取最新）；支持 JAVA_HOME、ANDROID_SDK_ROOT/ANDROID_HOME、--java-home、--d8 和 --android-jar。无需 Gradle、Android Studio 或 pip 包。Python 当前使用 Path.is_relative_to，需要 Python 3.9 或更新版本；实际测试版本见验证记录。

## 安装验证

本地只发现，不安装：

```sh
INSTALL_INTERNAL_SKILLS=1 npx skills add /absolute/path/to/skills --list
```

明确选择这个 Skill，避免安装整个合集：

```sh
npx skills add /absolute/path/to/skills --skill android-background-test -a codex --copy
```

未来发布到已有远端后才可使用：

```sh
npx skills add lixiaolin94/skills --skill android-background-test
```

目前新文件未提交、未推送，不能认为远端命令已经可用。npx 的 Node/npm 只是安装器依赖，不是运行依赖。

## 安全与发布

用户运行依赖为 ADB + Python 标准库。手机端 DEX JAR 由 app_process 加载，不安装为应用；不规避 ROM 权限或安全告警。verify 和 doctor 不执行 adb。

发布需同时包含根目录包与 workspace 工程源，排除缓存、临时安装、凭据和构建中间文件；更新根目录技能目录表前先确认真正可用。

详细设计见 [architecture.md](docs/architecture.md)，设计缺陷与待决策见 [design-review.md](docs/design-review.md)，验证见 [validation.md](docs/validation.md)。
