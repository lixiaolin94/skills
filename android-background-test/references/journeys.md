# Journeys：按自然语言步骤跑用例

格式沿用 Google Android CLI 的 Journeys：同一份 XML 既能交给 Android CLI 在主屏上跑，也能用本 Skill 在后台屏上跑。

```xml
<journey name="登录后查看订单">
  <description>验证登录后能进入订单列表</description>
  <actions>
    <action>点击“我的”</action>
    <action>点击“登录”，在手机号输入框输入 13800000000</action>
    <action>verify 页面出现“验证码”输入框</action>
  </actions>
</journey>
```

## 执行规则

1. 先 `open`。目标是敏感应用时，先按 SKILL.md 取得用户同意。
2. 按顺序逐条执行 `<action>`：
   - 以 check、verify、检查、确认开头的步骤**只观察**（layout 或截图），不做任何输入。
   - 其他步骤**照字面执行**，不揣测意图，不自作主张补步骤。一条里有多个动作就按顺序拆开做。
   - 优先用 `--target` 或 `--ref` 操作，加 `--settle` 拿到变化，再判断这一步成没成。
3. 以下情况本步判为失败，并停止后面的步骤：应用崩溃（`no_task`）、卡死（`no_frame`）、找不到目标元素、出现风控提示。
4. 步骤里写到支付、转账、验证码时，按硬边界停下，记为“需用户操作”，不算失败。
5. 结束后 `close`，写报告。

## 报告模板

```markdown
# Journey：<name>
设备：<型号>（<serial>）  应用：<应用名（包名）>  结果：✅ 通过 / ❌ 失败 / ⏸ 需用户操作

| # | 步骤 | 结果 | 执行的命令 | 截图 | 备注 |
| --- | --- | --- | --- | --- | --- |
| 1 | 点击“我的” | ✅ | tap --target "text=我的" --settle | shot-…png | |
| 2 | … | ❌ | … | … | element_not_found，屏上可见：… |
```

报告文件和截图都放在会话 workdir 下。
