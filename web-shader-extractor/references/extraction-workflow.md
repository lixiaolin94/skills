# 提取工作流详细步骤

## Step 1: 获取页面并识别渲染技术

```bash
# 1. 获取 HTML
curl -s -L --compressed '<URL>' > /tmp/page.html

# 2. 首先检查 canvas 元素（最快的技术栈线索）
grep -oE '<canvas[^>]*>' /tmp/page.html
# <canvas data-engine="three.js r167"> → 直接确认 Three.js + 版本号
# <canvas data-engine="Babylon.js vX.X"> → Babylon.js

# 3. 从 HTML 提取 JS/CSS URL（注意 base href）
grep -oE '(src|href)="[^"]*\.(js|css)"' /tmp/page.html

# 4. 下载所有 JS（拼上 base URL）
curl -s -L --compressed '<BASE_URL>/main-XXXX.js' > /tmp/main.js
curl -s -L --compressed '<BASE_URL>/chunk-XXXX.js' > /tmp/chunk.js
```

**常见坑**：
- 不加 `--compressed` 会得到乱码（brotli/gzip）
- SPA 页面的真正内容在 JS bundle 里，HTML 只有 `<app-root>` 之类的空壳
- 有些站点会拆分多个 chunk，都要下载
- `<canvas data-engine="...">` 是 React Three Fiber / Three.js 自动添加的属性，能一步确认引擎 + 版本

## Step 2: 快速扫描

```bash
bash ~/.claude/skills/web-shader-extractor/scripts/scan-bundle.sh /tmp/main.js
```

根据输出判断：
- 大量 `uniform/varying/precision` → 有自定义 shader
- `ShaderMaterial/RenderTarget` → Three.js + GPGPU
- `gl.bindBuffer/gl.drawArrays` → Raw WebGL
- `PIXI.` → PixiJS 2D
- `snoise/simplex` → 使用 simplex noise

## Step 3: Task Agent 深度提取

启动 Task agent（subagent_type: general-purpose）分析 bundle，prompt 模板：

```
分析文件 /tmp/main.js（约 X MB 的 minified JS bundle），提取所有与视觉特效相关的代码。

需要提取的内容：

1. **GLSL Shader 源码**：搜索包含 "precision", "uniform", "void main",
   "gl_FragColor", "gl_Position" 的字符串。它们通常是模板字符串或字符串变量。
   提取完整的 vertex shader 和 fragment shader。

2. **渲染相关 JS 类**：
   - 创建 canvas/renderer 的类
   - 管理粒子/几何体的类
   - 动画循环（requestAnimationFrame）相关代码
   - 鼠标交互代码

3. **符号映射表**：识别 minified 变量名对应的原始含义，例如：
   - 哪些变量是 THREE.Vector2, THREE.Color, THREE.Scene 等
   - 哪些变量是自定义类（噪声、粒子系统等）

4. **配置和参数**：默认颜色、尺寸、密度等可调参数

将所有提取的代码保存到 /tmp/extracted-effects.txt，按功能分段标注。
```

**关键**：Task agent 能看到完整文件，主上下文放不下 1MB+ 的 bundle。

## Step 4: 识别 Minified 符号

Three.js minified 常见模式：

```javascript
// 构造器调用模式
new Xx(...)          // 大写开头 = 类
Xx.yyy               // 静态属性/方法

// 通过参数推断
new ??(40, w/h, 0.1, 1000)       → PerspectiveCamera(fov, aspect, near, far)
new ??(-1, 1, 1, -1, 0, 1)      → OrthographicCamera
new ??({canvas, antialias, ...}) → WebGLRenderer
new ??(2, 2)                     → PlaneGeometry(w, h)
new ??(data, w, h, fmt, type)    → DataTexture
new ??(w, h, {minFilter, ...})   → WebGLRenderTarget

// 通过方法调用推断
??.setRenderTarget()  → renderer
??.setFromCamera()    → raycaster
??.intersectObject()  → raycaster
??.getElapsedTime()   → clock
??.setAttribute()     → bufferGeometry
```

## Step 5: 反混淆规则

1. **类名**：根据构造参数和方法调用推断原始类名
2. **变量名**：根据用途命名（`ringPos`, `particleScale`, `simMaterial`）
3. **Shader 变量**：uniform/varying 名通常未被 minify（`uTime`, `vPosition`）
4. **保留原始 GLSL**：shader 代码通常是完整字符串，直接提取即可
5. **字符串注入**：`${someVar.noise}` 这种模式表示噪声库被注入到 shader 中

## Step 6: 项目输出

CDN 依赖选择（优先 jsdelivr，需验证可用性）：

| 库 | CDN URL 模板 |
|---|---|
| Three.js | `https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.js` |
| GSAP | `https://cdn.jsdelivr.net/npm/gsap@3.12.0/dist/gsap.min.js` |
| PixiJS | `https://cdn.jsdelivr.net/npm/pixi.js@7.3.0/dist/pixi.min.mjs` |

验证方式：`curl -sI '<URL>' | head -3` 确认 200。

importmap 模板：
```html
<script type="importmap">
{ "imports": { "three": "<cdn-url>" } }
</script>
<script type="module" src="js/main.js"></script>
```
