# Channel Contract

## Purpose

`Channel Contract` 用来规范一个新平台如何接入周报 skill。

一个新通道只要满足这里的约束，就可以加入主流水线，而不需要重写主文档。

## Minimum Declaration

每个通道至少应声明：

- `channel_family`
- `channel_name`
- `available_capabilities`
- `auth_requirements` 或权限说明
- `failure_mode`

## Capability Output Requirements

如果某个通道声明支持以下能力，则输出结果至少应满足对应字段要求：

### `calendar`

- 时间
- 标题
- 参与者或组织者
- 事件链接或唯一标识

### `meeting_notes`

- 标题
- 摘要
- 待办或关键结论
- 纪要链接或唯一标识

### `docs_search`

- 文档标题
- 文档标识
- 简短摘要或命中片段

### `docs_fetch`

- 文档标题
- 正文或正文摘要
- 链接或路径

### `message_search`

- 消息时间
- 发送人
- 消息内容摘要
- 会话或消息链接

### `git_history`

- 仓库路径或仓库标识
- commit hash
- commit title
- commit time

### `agent_history`

- 会话时间
- 任务目标
- 项目路径
- 关键执行内容摘要

### `file_metadata`

- 标题
- 路径或链接
- 更新时间
- 所有者或编辑者

## Degrade Behavior

若一个通道存在但只支持部分能力：

- 采集其可用能力
- 明确记录能力缺口
- 不阻断其他通道继续执行
