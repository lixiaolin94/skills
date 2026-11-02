# 打断处理（退出码 3）

返回中的 `interruption` 包含 `reason`、`detail`、`askUser`、`conflictId`、`grantable`、`expiresInSeconds`。

1. 停止操作。用 `detail` 告诉用户发生了什么，用 `askUser` 询问用户。
2. 只有用户在对话中明确同意，才能调用 `resolve --conflict <id> --grant`；用户拒绝就用 `--deny`。不得替用户同意，也不得因为测试还没完成就默认同意。
3. 超时（`expiresInSeconds` 归零）、用户拒绝，或者用户正在手机上使用这个应用时，会话会自动结束，这是预期行为。

| reason | 情况 | 能否 grant |
| --- | --- | --- |
| `existing_task` | 用户手机上已有这个应用的任务，接管会把它移到后台屏，关闭会话时该任务会被移除。只有入口是 singleTask/singleInstance 的应用会这样；standard 应用直接另开一个任务，不会询问 | 能，需用户同意 |
| `user_opened_app` | 用户在手机上打开了正在测试的应用，任务已交给用户 | 能；用户还在用时会失败并结束 |
| `device_locked` | 手机锁屏或息屏，后台屏停止渲染 | 不能；请用户解锁，解锁后自动继续 |
