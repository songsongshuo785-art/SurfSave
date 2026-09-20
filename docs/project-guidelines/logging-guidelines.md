# Logging Guidelines

记录当前项目的日志约定。

迁移到新项目后，至少补充：

- 日志级别使用规则。
- 必须记录的关键字段。
- 禁止记录的敏感信息。
- 本地调试日志、生产日志和实验/批处理日志的边界。

## Scenario: 下载发布失败诊断日志

### 1. Scope / Trigger

- 修改下载完成后的文件移动、MediaStore 发布、任务错误日志、全局最近错误日志或日志脱敏规则时，必须按本节执行。
- 目标是在不丢失底层现场的前提下，让用户可在应用内复制/分享最近一次发布失败，同时不落盘认证信息。

### 2. Signatures

- `FileUtil.MoveResult(ok: Boolean, reason: String?, detail: String?)`
- `ErrorLogRecorder.recordPublicationFailure(engine, taskId, message, detail, taskLogger, throwable)`
- `DownloadTaskLogger.redact(input: String): String`
- `ErrorLogRecorder.readLatest(): String?`

### 3. Contracts

- `reason` 是进入任务终态的简洁错误；`detail` 是含阶段、URI、MediaStore 实际元数据、设备 API 和异常栈的完整现场。上层不得用笼统的 `Error moving file` 覆盖已有 `detail`。
- Regular、SuperX、yt-dlp 三种引擎的媒体发布失败统一经过 `recordPublicationFailure`，同一份 `detail` 同时进入当前任务日志和全局最近错误日志。
- 最近错误日志只保留最后一次，写入 `files/error_logs/latest_download_error.txt`；写入必须使用 `AtomicFile`，读写共享同一把锁，失败提交不得破坏上一份完整报告。
- 所有落盘内容必须先经过 `DownloadTaskLogger.redact()`。`Cookie`、`Set-Cookie`、`Authorization`、`X-Auth-Token` 从字段名到行尾全部视为敏感内容；不能只遮蔽第一个分号前的 Cookie。URL 中 token、signature、password 等敏感查询参数也必须逐项遮蔽。
- 诊断日志写入失败属于二级失败：记录到 `AppLogger`，但不得吞掉或改写原始下载失败。

### 4. Validation & Error Matrix

- MediaStore `insert` 返回 null、输出流不可用、pending/published 元数据不一致或 update 行数不是 1 -> 下载保持失败，`detail` 包含具体阶段和实际值，复制失败时保留源文件。
- 发布失败后的 pending 行删除不完整 -> `detail` 追加清理行数、URI 是否仍存在等信息，不能静默忽略。
- 多任务同时失败 -> 最终文件必须是其中一份完整报告，不能出现截断或跨任务拼接。
- `AtomicFile.finishWrite` 前异常 -> 恢复上一份完整报告，不残留 `.new` / `.bak`。
- 日志含多段 Cookie、Authorization 或签名参数 -> 落盘和分享文本均不得出现原始值。

### 5. Good / Base / Bad Cases

- Good: 任务详情显示简洁原因，最近错误日志同时包含 `insertedUri`、期望/实际 name/path/pending/size 和完整异常栈，且认证字段均为 `<redacted>`。
- Base: 尚未捕获发布失败时，入口显示明确空状态，不伪造日志。
- Bad: 底层已经返回结构化 `detail`，上层只写 `Error moving file`；或 `Cookie: a=secret; b=secret2` 只遮住 `a`。

### 6. Tests Required

- `ErrorLogRecorderTest`：覆盖读写、覆盖、异常栈、并发完整性、提交失败恢复、无临时文件残留和敏感字段脱敏。
- `FileUtilMediaStoreTest`：故障注入 insert null、output null、update 0、路径/文件名/size 异常、pending 清理失败和中文文件名成功发布，并断言源文件保留边界。
- `YoutubeDlMediaPublisherTest`：移动失败不误报成功、恢复路径正确、详细报告进入绑定任务日志。
- `DownloadTaskLoggerTest`：至少用带两个分号分隔值的 Cookie 行验证整行脱敏，并验证普通 quality 参数保留。
- 最低构建验证：`testDiagnosticUnitTest assembleDiagnostic lintDiagnostic`。

### 7. Wrong vs Correct

#### Wrong

```kotlin
if (!fileUtil.moveMedia(context, source, target)) {
    taskLogger.error(taskId, "Error moving file")
}
```

#### Correct

```kotlin
val result = fileUtil.moveMediaWithReason(context, source, target)
if (!result.ok) {
    ErrorLogRecorder.recordPublicationFailure(
        engine = "yt-dlp",
        taskId = taskId,
        message = result.reason ?: "Error moving file",
        detail = result.detail,
        taskLogger = taskLogger
    )
}
```
