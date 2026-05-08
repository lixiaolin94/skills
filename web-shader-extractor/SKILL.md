---
name: web-shader-extractor
description: |
  从网页中提取 WebGL/Canvas/Shader 视觉特效代码，反混淆后移植为独立原生 JS 项目。
  触发条件：用户提供网址并要求提取 shader、提取特效、提取动画效果、提取 canvas 效果、
  复刻某网站的视觉效果、"把这个网站的背景效果扒下来" 等。
---

# Web Shader Extractor

从网页提取 WebGL/Canvas/Shader 特效，反混淆并移植为独立项目。

核心原则：
- **先 1:1 复刻，确认正确后再考虑简化框架**
- **全程自主执行，不中断用户** — 提取是只读操作，安全性无风险。除 Phase 3 简化提议外，所有步骤自动完成。遇到问题自行判断最佳方案继续推进，只在需要用户做产品决策时才询问。

## 前置条件：确认 Chrome DevTools MCP 可用

**开始任何提取工作前，必须先确认 Chrome DevTools MCP 已加载。**

用 ToolSearch 检索关键字 `chrome devtools navigate evaluate`：
- 若搜索结果包含 `mcp__chrome-devtools__navigate_page` → 可以开始
- 若结果中无任何 `mcp__chrome-devtools__*` 工具 → **立即提示用户安装后再继续**：

```
Chrome DevTools MCP 未安装，请运行：
  claude mcp add chrome-devtools --scope user npx chrome-devtools-mcp@latest
安装后重启 Claude Code，再重新触发本 skill。
```

不要绕过此检查降级到 Playwright 继续提取 — Playwright headless 在复杂 WebGL 站点下无法捕获 shader 源码和渲染管线，会导致提取不完整。

工具优先级：
1. **Chrome DevTools MCP**（必须）— `navigate_page` + `initScript` 注入 WebGL 拦截器，`evaluate_script` 查询运行时状态，`list_network_requests` 捕获资源。
2. **Playwright**（仅限 Phase 3 验证）— headless 截图对比移植效果，不用于侦察阶段。
3. **curl**（静态补充）— 获取原始 HTML，提取内嵌配置和密钥。

## Phase 1: 侦察

目标：一次页面加载，最大化采集所有运行时数据。

### 1.1 WebGL 拦截注入

在页面脚本执行前注入拦截器，捕获：
- `gl.shaderSource()` → 所有 shader 源码（vertex + fragment）
- `gl.bindFramebuffer()` → FBO 切换序列（即渲染管线拓扑）
- `gl.uniform*()` → uniform 名称与初始值
- `gl.drawArrays() / drawElements()` → draw call 顺序与参数

同时也拦截 2D Canvas 的 `getContext('2d')` 以覆盖纯 Canvas 特效。

### 1.2 运行时查询

页面加载稳定后，主动查询：
- 框架版本：`window.THREE?.REVISION`、`window.BABYLON?.Engine?.Version` 等
- GL 能力：`gl.getSupportedExtensions()`、renderer info
- 全局配置：`__NEXT_DATA__`、`__NUXT__`、`window` 上的自定义全局变量
- Canvas 元素的 `data-engine` 属性

### 1.3 静态资源采集

并行用 curl 获取原始 HTML，与运行时数据交叉提取：
- 网络请求中的 JS bundle URL → 批量下载到 /tmp/
- HTML 内嵌的 JSON 配置、API 密钥
- 框架特征速查 → references/tech-signatures.md

### 1.4 路由判断

根据 URL、HTML 特征、运行时数据判断是否匹配已知平台：

| 匹配条件 | 跳转 |
|----------|------|
| unicorn.studio 域名或 embed 特征 | → references/unicorn-studio.md |
| shaders.com 域名或 Nuxt+TSL 特征 | → references/shaders-com.md |
| 无匹配 | → 继续 Phase 2 通用流程 |

### 1.5 深度分析（按需）

如果拦截器已捕获完整 shader 源码，可跳过 bundle 分析。仅在以下情况启动 Agent 分析 JS bundle：
- 拦截器未能捕获 shader（如 TSL 节点系统不经过 shaderSource）
- 需要理解混淆后的配置编码逻辑
- 需要还原 onBeforeCompile 注入的 GLSL 片段

Agent 分析规则 → references/extraction-workflow.md

## Phase 2: 移植

目标：将提取的 shader/配置/渲染管线重建为可独立运行的项目。

### 框架选择原则

根据提取结果判断最合适的移植目标：
- 纯 2D 全屏 shader → 原生 WebGL2（零依赖）
- 纯 2D Canvas 特效 → Vanilla JS（零依赖）
- 3D / PBR / GPGPU / onBeforeCompile → 保留原始框架（CDN importmap）
- 不确定 → 先用原始框架，Phase 3 再评估简化

详细策略 → references/porting-strategy.md

### 关键约束

- **参数严格对齐**：从源站提取的值直接使用，不手动调参补偿视觉差异（视觉差异说明有 root cause 未解决）
- **色彩空间一致**：全链路 linear，仅最终输出做 linear→sRGB
- **时间基准对齐**：确认原站用秒还是帧累加，移植后保持一致

## Phase 3: 交付

### 3.1 验证

打开移植后的页面，截图与原站对比。重点检查：
- 色彩/亮度是否一致
- 动画节奏是否匹配
- 多 pass 渲染的合成顺序是否正确

如发现差异，优先排查 root cause（色彩空间、时间基准、FBO 顺序），不要通过调参修补。

### 3.2 简化提议（询问用户）

效果验证正确后，如果存在简化空间（如可以剥离框架改用原生 WebGL），向用户提议简化方案，由用户决定是否执行。

### 3.3 提取报告（询问用户）

询问用户是否生成 `EXTRACTION-REPORT.md`。报告内容：
- 来源/作者/平台/时间
- 目标效果描述
- 提取思路与迭代时间线
- 场景结构 / 渲染管线 / 关键资源
- 发现的关键经验
- 剩余已知差异
- 技术栈对比（原始 vs 移植）

## Reference 索引

| 需要时 | 读取 |
|--------|------|
| 识别框架特征 | references/tech-signatures.md |
| Agent 提取 prompt + 反混淆规则 | references/extraction-workflow.md |
| 获取配置参数 | references/config-extraction.md |
| Three.js TSL 节点 shader 重建 | references/tsl-extraction.md |
| 编码/加密配置解码 | references/encoded-definitions.md |
| onBeforeCompile GLSL 注入陷阱 | references/shader-injection.md |
| 移植框架选择 + 项目结构 | references/porting-strategy.md |
| Unicorn Studio 专用流程 | references/unicorn-studio.md |
| shaders.com 专用流程 | references/shaders-com.md |
