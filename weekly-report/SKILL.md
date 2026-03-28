---
name: weekly-report
description: |
  当用户询问周报、周总结、工作汇总时，围绕目标时间范围收集多通道证据，
  归并为工作主题，并生成结构化周报。适用于协作平台、Git、Agent、本地文档等
  多种信息来源并存的场景。Trigger phrases: "周报", "周总结", "工作汇总",
  "上周工作", "周工作", "weekly report", "work summary".
user-invokable: true
args:
  - name: week
    description: 指定哪一周（如 "上周"、"本周"、"3月24日那周"），不填时默认按“上周（周一到周日）”解析
    required: false
---

# Weekly Report

## Purpose

这个 skill 用于生成一份可直接发送的工作周报。

周报的核心目标是：

1. 说明目标时间范围内的核心工作主题
2. 说明每项工作对应的证据来源
3. 区分完成、推进中、待排期、仅讨论
4. 还原协作沟通重点
5. 给出与事实一致的下周计划

## Success Criteria

一份合格周报必须满足：

1. 时间范围明确且正确
2. 周报主体按“工作主题”组织
3. 每项核心工作有至少两类证据支撑，或明确标注证据不足
4. 事实内容与推断内容分开表达
5. 用户可以直接发送，不需要再手工补大段背景

如果做不到以上几点，这份周报就不算完成。

## Time Semantics

### Default Rule

- 用户明确说“本周周报”：
  - 取本周周一到当前日期
- 用户没有明确说“本周”：
  - 默认取上周周一到周日

### Explicit Rule

- `本周`：本周周一到当前日期
- `上周`：上周周一到周日
- `3月24日那周`：包含该日期的自然周，范围为周一到周日

### Required Behavior

开始采集前，必须先输出绝对日期范围，不能只说“本周”或“上周”。

示例：

```text
本次周报范围：2026-03-16 00:00:00 +08:00 ~ 2026-03-22 23:59:59 +08:00
覆盖日期：2026-03-16 ~ 2026-03-22
```

## Core Model

### Channel

`Channel` 表示一个可独立接入的信息来源。

每个通道应归属于一个 `channel_family`：

- `collab_platform`
- `git`
- `agent`
- `local_docs`

每个通道只声明自己提供哪些能力，不负责决定周报结构。

### Capability

`Capability` 表示一个通道能够提供的具体采集能力。

推荐使用这些能力名：

- `calendar`
- `meeting_notes`
- `docs_search`
- `docs_fetch`
- `message_search`
- `git_history`
- `agent_history`
- `file_metadata`

### Evidence

`Evidence` 表示一条已归一化的证据对象。

每条证据至少应尽量包含：

- `source_family`
- `source_channel`
- `evidence_type`
- `title`
- `summary`
- `timestamp` 或时间范围
- `actors`
- `project_or_topic`
- `url_or_path`
- `confidence`

常用 `evidence_type`：

- `meeting`
- `meeting_notes`
- `doc`
- `message`
- `commit`
- `agent_session`
- `file_metadata`

### Work Topic

`Work Topic` 表示一组围绕同一项目、功能、专项或目标归并后的工作主题。

每个工作主题至少要回答：

- 这项工作是什么
- 当前状态是什么
- 有哪些关键结论
- 由哪些证据支撑
- 下周是否继续推进

## Pipeline

### Step 1: Resolve Time Range

先解析时间范围，再开始采集。

如需稳定计算周范围，优先使用：

- `scripts/resolve_week_range.py`

脚本说明见：

- `references/time.md`

### Step 2: Discover Channels

检查当前环境有哪些通道可用：

1. 协作平台通道
2. Git 通道
3. Agent 通道
4. 本地文档通道

只对已发现的通道继续采集。

### Step 3: Inspect Capabilities

对每个已发现通道，确认其可用能力。

例如：

- 协作平台可能支持 `calendar`、`docs_search`、`message_search`
- Git 通道可能支持 `git_history`
- Agent 通道可能支持 `agent_history`

通道只具备部分能力时，也允许继续执行。

### Step 4: Collect Raw Facts

按通道并行采集原始信息，优先采集：

1. 协作平台中的会议、纪要、文档、消息
2. Git 历史
3. Agent 历史
4. 本地文档与导出文件

### Step 5: Normalize Evidence

把原始结果整理为统一的 `Evidence`。

归一化后，至少应支持按以下维度聚合：

- 时间
- 项目 / 主题
- 人
- 证据类型
- 来源通道

### Step 6: Extract Candidate Topics

优先从高信号内容提取候选工作主题：

- Git 提交主题
- 文档标题与摘要
- 会议标题与会议纪要总结
- 消息中的高频关键词
- Agent 任务目标

### Step 7: Merge Into Work Topics

按主题将证据归并成 `Work Topic`，并判断：

- 这些证据是否指向同一项工作
- 当前状态是已完成、推进中、待排期还是仅讨论
- 哪些结论可以进入周报正文

### Step 8: Validate Topics

每个工作主题都应尽量完成跨证据验证。

优先验证的组合：

1. `meeting` + `doc`
2. `doc` + `commit`
3. `commit` + `agent_session`
4. `message` + `meeting` / `doc`

若某项工作仅命中单一证据来源：

- 先尝试补证
- 若无法补证，则降低置信度
- 在结果中明确说明证据不足

### Step 9: Write The Report

按工作主题组织最终周报，不按平台或通道罗列原始数据。

### Implementation References

平台或通道实现细节不要堆在本文件中，按需读取：

- 协作平台 Lark / 飞书：`references/lark.md`
- Git 通道：`references/git.md`
- Agent 通道：`references/agent.md`
- 本地文档通道：`references/local-docs.md`

## Channel Contract

`Channel Contract` 用来规范一个新平台如何接入本 skill。

主文档只保留契约入口，具体字段要求与降级行为见：

- `references/channel-contract.md`

## Evidence Rules

Evidence 规则用于定义证据优先级、置信度判断和写作边界。

具体规则见：

- `references/evidence.md`

## Degrade And Stop Rules

### Degrade

以下情况允许降级执行：

- 缺少消息能力
- 缺少 Agent 通道
- 缺少部分文档元数据
- 某个通道只支持部分能力

降级执行时必须明确说明：

- 缺了哪些能力
- 可能丢失哪类信息

### Stop

只有在无法支撑基本事实判断时才停止执行。

例如：

- 没有有效时间范围
- 没有任何可用通道
- 所有通道都无法提供可读证据

停止时必须明确说明：

- 当前缺少哪些通道或能力
- 为什么结果不可靠
- 若要继续，需要补什么环境或权限

## Output Spec

输出结构与模板见：

- `references/output.md`
- `assets/report-template.md`

## Completion Checklist

- [ ] 日期范围明确且正确
- [ ] 至少两个通道族已采集，或明确说明为什么只能单通道执行
- [ ] 已完成证据归一化
- [ ] 已提取工作主题
- [ ] 每个核心工作都有至少两类证据支撑，或明确标注证据不足
- [ ] 若存在消息能力，已尝试搜索；若失败已说明原因
- [ ] 结果可以直接发送，不需要用户再手工补大段内容
