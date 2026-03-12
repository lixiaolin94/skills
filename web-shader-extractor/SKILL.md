---
name: web-shader-extractor
description: |
  从网页中提取 WebGL/Canvas/Shader 视觉特效代码，反混淆后移植为独立原生 JS 项目。
  触发条件：用户提供网址并要求提取 shader、提取特效、提取动画效果、提取 canvas 效果、
  复刻某网站的视觉效果、"把这个网站的背景效果扒下来" 等。
---

# Web Shader Extractor

从网页提取 WebGL/Canvas/Shader 特效，反混淆并移植为独立项目。

## 工作流程

### Phase 1: 获取源码

```bash
# 必须用 --compressed，很多站点返回 br/gzip
curl -s -L --compressed '<URL>' > /tmp/page.html
```

从 HTML 中提取所有 `<script src="...">` 和 `<link href="...css">` 的 URL（注意相对路径要拼上 base URL），下载全部 JS/CSS 资源到 /tmp/。

### Phase 2: 技术栈识别

**优先检查 Canvas 元素**（最快速的线索）：
```bash
# 从 HTML 中查找 canvas 标签，data-engine 属性直接暴露引擎和版本
grep -oE '<canvas[^>]*>' /tmp/page.html
# 例: <canvas data-engine="three.js r167"> → 直接确认 Three.js + 版本号
```

`<canvas>` 属性速查：
- `data-engine="three.js rXXX"` → Three.js，版本号用于匹配 CDN 和函数签名
- `data-engine="Babylon.js vX.X"` → Babylon.js
- `class="__next-canvas"` 或被 R3F 包裹 → React Three Fiber
- 无特殊属性但有 `webgl2`/`webgl` context → 可能是原生 WebGL 或 PixiJS

然后运行扫描脚本确认细节：

```bash
bash ~/.claude/skills/web-shader-extractor/scripts/scan-bundle.sh /tmp/main*.js
```

根据输出判断技术栈 → 读取 `references/tech-signatures.md` 确认。

### Phase 3: 深度提取

用 **Task agent** 分析 JS bundle（通常 1MB+，不适合主上下文）。提取目标见 `references/extraction-workflow.md`。

### Phase 4: 移植

在用户当前目录创建项目，结构：

```
<project-name>/
├── index.html          # importmap 加载依赖
├── js/
│   ├── main.js         # 入口 + 配置
│   ├── shaders/        # GLSL 源码（.glsl.js）
│   └── ...             # 反混淆后的功能模块
└── README.md           # 技术原理说明
```

规则：
- 依赖通过 CDN importmap 加载，零安装即可运行
- 所有 JS 使用 ES Module（`import/export`）
- minified 变量名替换为有意义的名称
- README 包含效果说明、技术原理、可调参数

## 关键注意事项

- **WebFetch 不可靠**：动态渲染页面用 WebFetch 只能拿到部分内容，始终用 `curl --compressed` 获取原始 HTML
- **Bundle 太大**：JS bundle 通常 1MB+，先用 `grep` 定位关键区域，再用 Task agent 提取完整代码块
- **Shader 嵌入形式**：模板字符串（`` `...${noise}...` ``）、字符串拼接（`"precision" + ...`）、或独立 .glsl 文件
- **符号映射**：必须建立 minified→原始名 的映射表（如 `Rr→ShaderMaterial`），否则无法理解代码
- **CDN 版本**：验证依赖的 CDN URL 是否可访问（`curl -sI <url> | head -3`），旧版格式可能不可用
- **必须提取真实配置**：不要猜测参数值！详见 `references/config-extraction.md`
- **懒加载 chunk**：Three.js 代码可能不在初始 HTML 的 JS 中，详见 `references/lazy-chunks.md`
- **onBeforeCompile 陷阱**：替换 Three.js shader chunk 时详见 `references/shader-injection.md`

## References

- `references/extraction-workflow.md` — Task agent 提取的详细步骤和 prompt 模板
- `references/tech-signatures.md` — WebGL 框架识别特征和 minified 符号映射模式
- `references/config-extraction.md` — 从页面提取真实配置参数的方法
- `references/lazy-chunks.md` — webpack/Next.js 懒加载 chunk 的发现与下载
- `references/shader-injection.md` — onBeforeCompile 注入 GLSL 的陷阱与最佳实践
- `scripts/scan-bundle.sh` — 快速扫描 JS 文件中的 shader/WebGL 关键词统计
