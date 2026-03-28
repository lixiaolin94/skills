# Lark Channel

## Purpose

Lark 通道属于 `collab_platform`，用于提供在线协作证据。

可映射的能力包括：

- `calendar`
- `docs_search`
- `docs_fetch`
- `message_search`
- `meeting_notes`
- `file_metadata`

## Skill Mapping

如果当前环境存在 Lark 相关 skills，可优先按下面方式映射：

- 认证与补权
  - `lark-shared`
- `calendar`
  - `lark-calendar`
- `docs_search` / `docs_fetch`
  - `lark-doc`
- `message_search`
  - `lark-im`
- `meeting_notes`
  - `lark-vc`
- `file_metadata`
  - `lark-drive`

## Execution Rules

- 优先使用 Shortcut
- 其次使用已注册 API
- 只有在前两者不足时才使用 `lark-cli api`
- 优先通过 `lark-cli <service> --help` 和 `lark-cli schema ...` 确认能力与参数

## Permission Rules

如需读取对应能力，先确认相关 scope。

常见读取范围包括：

- 日历读取
- 文档搜索与正文读取
- 消息搜索
- 会议纪要读取
- 云空间元数据读取

如果缺少关键 scope：

- 先补权
- 若无法补权，则按主文档的降级规则执行

## Evidence Guidance

Lark 通道重点补充：

- 会议时间线
- 文档结论与待办
- 群聊中的会后拍板
- 会议纪要中的摘要和章节

## Output Hints

Lark 证据适合归一化为：

- `meeting`
- `meeting_notes`
- `doc`
- `message`
- `file_metadata`
