# 懒加载 Chunk 发现与下载

## 问题

Three.js/WebGL 代码常通过 `next/dynamic` 或 `React.lazy` 懒加载，不在初始 HTML 的 `<script>` 标签中。
扫描初始 chunk 会发现 0 个 shader 关键词，误以为页面没有 WebGL 内容。

**信号**：用户确认页面有 `<canvas>` 但扫描无结果 → 几乎一定是懒加载。

## Webpack Runtime 解析

webpack runtime 文件（通常 `webpack-<hash>.js`）包含所有 chunk 的 URL 映射。

### 关键函数：`r.u`（chunk URL 生成器）

```javascript
r.u = e =>
  8334===e ? "static/chunks/8334-c2f73d26b0d2223a.js" :     // 特殊 chunk
  "static/chunks/" +
    (({2274:"51749ec1", 6413:"f6211eb1"})[e] || e) +          // 第一部分（可选映射）
    "." +                                                       // 注意：是点号不是破折号！
    (({483:"d93b52f83c78da7b", 2274:"6a4c591c5e893540"})[e]) + // 第二部分
    ".js"
```

### URL 格式陷阱

| 类型 | 格式 | 示例 |
|------|------|------|
| HTML 内 preload | `id-hash.js` | `1356-cfbb7e663130940c.js` |
| 特殊 chunk | `id-hash.js` | `8334-c2f73d26b0d2223a.js` |
| **懒加载 chunk** | `firstMap.secondMap.js` | `51749ec1.6a4c591c5e893540.js` |

**关键区别**：懒加载 chunk 使用 `.`（点号）分隔两个 hash，不是 `-`（破折号）！
错用 `-` 会 404，浪费大量时间。

### 解析步骤

```bash
# 1. 找到 webpack runtime
grep -l 'webpackChunk' /tmp/chunks/*.js | xargs wc -c | sort -n

# 2. 提取 r.u 函数中的映射
cat webpack.js | sed 's/,/\n/g' | grep -E '^\s*\d+:'

# 3. 构造正确 URL
# chunk 2274: firstMap[2274]="51749ec1", secondMap[2274]="6a4c591c5e893540"
# URL = _next/static/chunks/51749ec1.6a4c591c5e893540.js

# 4. 验证后批量下载
curl -s -w "%{http_code}" -o /dev/null '<URL>'  # 先验证
```

## 找到 Three.js chunk 的策略

1. 在 marketing page / entry chunk 中搜索 `next/dynamic` 或 `loadable`
2. 找到 `Promise.all([s.e(9367), s.e(4664), ...]).then(s.bind(s, 65621))` 模式
3. 这些数字是 chunk ID → 用 webpack runtime 的映射构造完整 URL
4. 被 Sentry ErrorBoundary 包裹的组件很可能是 WebGL（容易崩溃所以加保护）
5. 接收 `config` prop 的 SSR:false 组件通常是可视化组件

## Next.js App Router 特殊路径

```
chunks/app/(marketing)/page-<hash>.js    # 首页 marketing 页面
chunks/app/layout-<hash>.js              # 根布局
chunks/main-app-<hash>.js                # 主应用入口
```

这些文件中的 `next/dynamic` 调用会引用实际的 Three.js chunk。
