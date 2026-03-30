[English](README.md)

# Skills

一组可复用的 AI 编程 Agent 技能。

## 包含的技能

| 技能 | 说明 |
|------|------|
| **[weekly-report](weekly-report/)** | 从协作平台、Git 历史、Agent 会话、本地文档等多通道采集证据，生成结构化周报。 |
| **[web-shader-extractor](web-shader-extractor/)** | 从网页中提取 WebGL/Canvas/Shader 视觉特效代码，反混淆后移植为独立原生 JS 项目。 |

## 安装

安装整个仓库：

```bash
npx skills add https://github.com/lixiaolin94/skills
```

安装单个技能：

```bash
npx skills add https://github.com/lixiaolin94/skills --skill weekly-report
```

```bash
npx skills add https://github.com/lixiaolin94/skills --skill web-shader-extractor
```

## 鸣谢

| 贡献者 | 贡献内容 |
|--------|----------|
| [Huazi](https://github.com/HeyHuazi) | **web-shader-extractor** — 提供 2D Canvas 提取报告，促成 2D Canvas 识别、移植策略和框架脱壳规则 |

## 许可证

MIT
