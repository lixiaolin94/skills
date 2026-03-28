# Evidence Rules

## Evidence Priority

高优先级证据：

- `meeting_notes`
- `doc`
- `commit`

中高优先级证据：

- `meeting`
- `message`
- `agent_session`

中优先级证据：

- `file_metadata`

## Confidence Rules

高置信度主题通常满足：

- 同时命中代码证据和协作证据
- 或同时命中文档、会议纪要、消息中的至少两类

中置信度主题通常满足：

- 只命中两类证据，但结论尚不完整

低置信度主题通常满足：

- 只命中单一证据
- 或只有标题，没有有效正文或结论

## Writing Rules

- 已发生的事实写入 `本周成果`
- 来自待办、会后确认或合理推断的内容写入 `下周计划`
- 推断内容要与已确认事实明确区分
- 周报正文优先写“工作主题”，不要写成会议清单
