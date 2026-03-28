# Time Rules

## Purpose

时间规则用于把用户的周报请求解析为一个明确的绝对时间范围。

## Semantic Rules

- 用户明确说“本周周报”：
  - 取本周周一到当前日期
- 用户没有明确说“本周”：
  - 默认取上周周一到周日
- 用户明确给出某个日期所在周：
  - 取该日期所属自然周的周一到周日

## Required Behavior

- 开始采集前，必须先输出绝对日期范围
- 不要只保留“本周”或“上周”这样的相对描述

## Script

如需稳定计算周范围，优先使用：

- `scripts/resolve_week_range.py`

示例：

```bash
python3 scripts/resolve_week_range.py --mode previous_week
python3 scripts/resolve_week_range.py --mode this_week_to_date
```
