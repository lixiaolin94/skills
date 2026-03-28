# Agent Channel

## Purpose

Agent 通道属于 `agent`，用于补充实际执行过程证据。

可映射的能力包括：

- `agent_history`

## Typical Sources

Agent 通道可以来自：

- `~/.claude/history.jsonl`
- `~/.codex/history.jsonl`
- `~/.codex/sessions/YYYY/MM/DD/*.jsonl`
- 其他本地 agent 的任务日志

## Evidence Guidance

Agent 通道重点回答：

- 实际执行了哪些任务
- 分布在哪些项目目录
- 是否和 Git、文档、会议形成闭环

归一化后，通常产生：

- `agent_session`

## Collection Guidance

Agent 采集时重点提炼：

- 任务目标
- 会话时间
- 项目路径
- 关键执行内容摘要

如果没有命中记录，可以继续生成周报，但不要把执行细节写成已确认事实。
