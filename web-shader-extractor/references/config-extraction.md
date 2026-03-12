# 从页面提取真实配置参数

## 为什么不能猜配置

猜测参数值会导致效果完全不对。实际案例：
- 颜色猜 `{255,100,50}` 实际 `{244,254,255}` — 暖橙 vs 近白
- shader size 猜 `4.0` 实际 `0.99` — 完全不同的图案
- 色差猜 `0.05` 实际 `3` — 60x 差异
- 后处理猜 `enabled:true` 实际 `false` — 多余的渲染 pass

## 配置来源（按优先级）

### 1. Next.js App Router (RSC Payload)

App Router 不使用 `__NEXT_DATA__`，配置嵌入在 RSC 流中：

```bash
# RSC payload 通常是 HTML 中以 $L 开头的序列化数据
# 搜索配置关键字定位
grep -o '"cameraZ":[0-9.]*' /tmp/page.html
grep -o '"cubeShader":{[^}]*}' /tmp/page.html

# 或搜索已知组件 prop 名称
grep -oE '"(scene|glass|cylinder|cubeShader|postProcessing)":\{' /tmp/page.html
```

### 2. Next.js Pages Router (`__NEXT_DATA__`)

```bash
# 提取 JSON 块
grep -o '<script id="__NEXT_DATA__"[^>]*>[^<]*' /tmp/page.html | sed 's/.*>//'
```

### 3. 内联 JSON / window 全局变量

```bash
grep -oE 'window\.__CONFIG__\s*=\s*\{[^;]+' /tmp/page.html
grep -oE 'data-config="[^"]*"' /tmp/page.html
```

### 4. JS Bundle 中的默认值

如果页面没有序列化配置（纯客户端），在入口模块中搜索配置对象：

```bash
grep -oE '(config|options|settings)\s*=\s*\{' /tmp/entry-chunk.js
```

## Task Agent 提取 Prompt

```
在 /tmp/page.html 中搜索传给 Three.js/WebGL 组件的配置数据。
搜索策略：
1. 搜索 "cameraZ", "cubeShader", "postProcessing" 等已知字段名
2. 搜索 RSC payload（$L 前缀的序列化数据）
3. 搜索 __NEXT_DATA__ JSON 块
4. 搜索 window.* 全局配置变量

输出完整的配置 JSON 对象。
```

## 验证配置

提取后与代码逻辑交叉验证：
- 颜色值范围：0-1 还是 0-255？
- resolution：像素值（1024）还是比例系数（0.5）？
- 布尔值：`enabled: false` 是否意味着整个功能不渲染？
