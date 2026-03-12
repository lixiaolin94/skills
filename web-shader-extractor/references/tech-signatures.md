# 技术栈识别特征

## Three.js

**未混淆标志**：`THREE.`, `WebGLRenderer`, `ShaderMaterial`, `BufferGeometry`

**混淆后识别**（通过构造参数和方法调用链推断）：

| 调用模式 | 原始类 |
|---------|--------|
| `new X({canvas, antialias, alpha, powerPreference})` | `WebGLRenderer` |
| `new X(fov, aspect, near, far)` — 4个数字参数 | `PerspectiveCamera` |
| `new X(-1, 1, 1, -1, 0, 1)` — 6个参数 | `OrthographicCamera` |
| `new X(w, h, {wrapS, minFilter, format, type})` | `WebGLRenderTarget` |
| `new X(data, w, h, format, type)` — Float32Array + 4参数 | `DataTexture` |
| `new X(2, 2)` + 作为 Mesh 几何体 | `PlaneGeometry` |
| `new X()` + `.setAttribute("position", ...)` | `BufferGeometry` |
| `new X(geometry, material)` + 渲染点 | `Points` |
| `new X(geometry, material)` + 渲染面 | `Mesh` |
| `new X({uniforms, vertexShader, fragmentShader})` | `ShaderMaterial` |
| `X.setFromCamera()` + `.intersectObject()` | `Raycaster` |
| `X.getElapsedTime()` | `Clock` |
| `new X(r, g, b)` 或 `new X("#hex")` + color 上下文 | `Color` |
| `new X(x, y)` 普遍用于 2D | `Vector2` |
| `new X(x, y, z)` 普遍用于 3D | `Vector3` |

**常量映射**：

| 值/模式 | 原始常量 |
|--------|---------|
| wrapping 参数 | `ClampToEdgeWrapping`, `RepeatWrapping` |
| filter 参数中 nearest | `NearestFilter` |
| format 参数 RGBA | `RGBAFormat` |
| type 参数 float | `FloatType` |
| side 参数 double | `DoubleSide` |
| `X.enabled = false` + color 上下文 | `ColorManagement` |

## Raw WebGL

直接调用 `gl.*` 方法：
```javascript
gl.createShader(gl.VERTEX_SHADER)
gl.shaderSource(shader, source)
gl.compileShader(shader)
gl.createProgram()
gl.attachShader(program, shader)
gl.linkProgram(program)
gl.useProgram(program)
gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
gl.bindFramebuffer(gl.FRAMEBUFFER, fb)
```

## PixiJS

`PIXI.Application`, `PIXI.Container`, `PIXI.Filter`, `PIXI.Shader`
自定义 filter 的 shader 在 `new PIXI.Filter(vertSrc, fragSrc, uniforms)`

## Babylon.js

`BABYLON.Engine`, `BABYLON.Scene`, `BABYLON.ShaderMaterial`
shader 通过 `BABYLON.Effect.ShadersStore` 注册

## GPGPU 模式识别

特征组合：
1. 两个 `WebGLRenderTarget`（ping-pong）
2. `OrthographicCamera(-1,1,1,-1,0,1)` + `PlaneGeometry(2,2)` = 全屏四边形
3. `setRenderTarget(rt)` → `render(simScene, simCamera)` → `setRenderTarget(null)` 循环
4. `DataTexture` 存储初始粒子位置
5. 片元着色器中 `texture2D(uPosition, texCoord)` 读取位置

## 常见噪声库

| 函数名 | 类型 |
|--------|------|
| `snoise(vec2/vec3/vec4)` | Simplex noise (Ashima Arts) |
| `cnoise(vec3)` | Classic Perlin noise |
| `pnoise(vec3, vec3)` | Periodic Perlin noise |
| `cellular(vec3)` | Worley/Voronoi noise |
| `fbm(vec3)` | Fractal Brownian Motion（多层 noise 叠加）|

这些通常作为字符串变量存储，通过模板字符串 `${noiseLib}` 注入到 shader 中。
