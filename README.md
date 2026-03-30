[中文版](README_CN.md)

# Skills

A small collection of reusable skills for AI coding agents.

## Included Skills

| Skill | Description |
|-------|-------------|
| **[weekly-report](weekly-report/)** | Generate structured weekly reports from multiple evidence channels such as collaboration platforms, Git history, agent sessions, and local documents. |
| **[web-shader-extractor](web-shader-extractor/)** | Extract WebGL/Canvas/Shader effects from websites and port them into standalone native JS projects. |

## Install

Install the whole repository:

```bash
npx skills add https://github.com/lixiaolin94/skills
```

Install a specific skill:

```bash
npx skills add https://github.com/lixiaolin94/skills --skill weekly-report
```

```bash
npx skills add https://github.com/lixiaolin94/skills --skill web-shader-extractor
```

## Acknowledgments

| Contributor | Contributions |
|-------------|---------------|
| [Huazi](https://github.com/HeyHuazi) | **web-shader-extractor** — 2D Canvas extraction report that led to 2D Canvas identification, porting strategy, and framework shell-removal rules |

## License

MIT
