# Data Migration

本文件记录当前项目在“旧应用身份 -> 新应用身份”迁移上的硬约束，避免后续版本因为改包名、改目录或偷懒跨包读沙盒而丢数据。

## 适用范围

- `applicationId` / product flavor / launcher identity 变更
- 旧版数据导出、迁移包、导入、迁移中心 UI
- 下载目录、私有目录、标签页缩略图、书签/历史/设置/本地视频元数据迁移

## 身份切换原则

- 不允许直接把现有 App 一步改成新 `applicationId` 后假设旧数据会自动保留。
- 跨包数据迁移必须走显式导出/导入闭环；历史桥接阶段已完成后，正式包只保留从共享迁移包导入和当前应用导出备份的能力。
- 不能恢复“新包直接读取旧包私有目录/私有数据库/私有 SharedPreferences”的隐式迁移方案。

## 当前标准迁移包方案

- 桥接构建期已结束，当前正式 `applicationId` 固定为 `com.surfsave.browser`。
- 不再构建旧身份 `bridge` flavor；旧身份只作为历史导出来源存在。
- 正式包必须继续保留“数据迁移与备份”入口，承载导入、导出、手动选包和删除本地备份包。
- 正式包在完成导入后也保留导出能力，方便用户直接从当前新身份继续导出备份，不再要求回到旧包才能备份。
- 迁移包写入共享下载目录 `Downloads/SurfSave/surfsave-migration-package.zip`
- 迁移包至少包含：
  - `settings_prefs`
  - `playback_state_prefs`
  - 书签 `PageInfo`
  - 历史 `HistoryItem`
  - 视频元数据 `VideoInfo`
  - 已完成下载的 `ProgressInfo`
  - 浏览器 session
  - 标签页缩略图
  - Cookie profile 元数据；Cookie 内容仅在用户显式选择时包含

## 明确不承诺迁移的内容

- 不承诺 WebView 登录态 / Cookie 迁移
- 不迁移进行中的下载任务状态
- 不依赖“新包直接读取旧包私有目录/私有数据库/私有 SharedPreferences”

## Cookie profile 备份边界

- Cookie profile 元数据可以进入普通备份报告，用于说明当前备份中存在多少 profile。
- Cookie profile 内容包含认证信息，默认不得写入普通迁移包；只有用户在迁移中心显式勾选“包含 Cookie profile 内容”时才允许导出。
- 导入时只恢复带 `content` 的 Cookie profile；只有元数据、没有内容的 profile 不应生成空 cookie 文件。
- 不迁移 WebView 登录态，也不把 WebView cookie 数据库打包进迁移 zip。

## 本地视频与私有目录

- 如果用户还有应用私有目录里的视频，正式包仍保留“迁移到公共下载目录”能力。
- 私有视频迁移完成前，不能把“安装新包即可一切正常”作为承诺。
- 如果视频已迁到公共下载目录，新身份包必须具备对应 Android 版本的公共媒体读取权限（`READ_EXTERNAL_STORAGE` 或 `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO`），否则本地视频页会出现“文件仍在，但列表不可见”。
- 导入成功后不自动删除迁移包，也不自动清理旧版数据；用户先核对，再手动执行后续动作。

## 浏览器会话特殊规则

- 浏览器 session 可以迁，但标签缩略图路径不能原样照抄。
- 导入时必须把缩略图复制到新包自己的 `files/browser_tab_thumbnails/`，并重写 `thumbnailPath`。

## UI 流程约束

- 正式包显示“数据迁移与备份”入口：
  - 首次启动未导入时自动打开迁移中心
  - 自动发现不到共享迁移包时，必须提供“手动选择迁移包”的兜底入口
  - 导入成功后保留“删除迁移包 / 重新导入 / 查看报告”，并允许继续导出当前备份
  - 不自动弹清理，不自动删除迁移包
  - 如仍有应用私有视频，继续提供“迁移到公共下载目录”能力
- 设置页中的普通用户入口统一命名为“数据迁移与备份”，页面内同时承载导入、导出、手动选包和删除本地备份包；不要把 `bridge` / `newidentity` 直接暴露成两套用户功能名。

## Scenario: Single-identity backup and restore

### 1. Scope / Trigger

- Trigger: 修改 `MigrationCenter` UI、设置页迁移入口、`MIGRATION_EXPORT_ENABLED` / `MIGRATION_IMPORT_ENABLED` 行为、`applicationId`、导出 APK 任务，或“手动选包后再次导入”的链路时，必须按本节执行。

### 2. Signatures

- `BuildConfig.MIGRATION_EXPORT_ENABLED: Boolean`
- `BuildConfig.MIGRATION_IMPORT_ENABLED: Boolean`
- `BuildConfig.MIGRATION_ROLE: String = "new_identity"`
- `MigrationManager.exportMigrationPackage(): MigrationReport`
- `MigrationManager.importMigrationPackage(packageUri: Uri? = null): MigrationReport`
- `MigrationManager.deleteMigrationPackage(): Boolean`
- `MigrationCenterFragment.importFromAutoOrPicker(): Unit`

### 3. Contracts

- 正式包必须允许“导入 + 导出”同时存在，方便用户在完成身份切换后继续从当前应用导出备份。
- 用户可见设置入口与页面标题统一使用“数据迁移与备份”语义，不直接暴露 `bridge` / `newidentity` 角色词。
- 当 `overview.packageInfo` 来自之前手动选择的 zip 时，点击“导入/重新导入”必须直接使用该 `Uri`，不能重新退回仅靠 MediaStore 自动扫描。
- 删除本地备份包成功后，必须同步清掉最近一次报告里缓存的 `packageInfo`，避免 UI 继续显示已经删掉的包路径。
- 若用户已经处于 `IMPORTED` 状态，再从正式包导出当前备份时，状态应继续保持 `IMPORTED`，不能因为导出一次就把“数据已在当前应用内”改回未导入语义。
- 不再新增或恢复旧身份 `bridge` flavor；若确实需要给历史用户单独补桥接包，必须作为独立迁移任务重新评估。

### 4. Validation & Error Matrix

- `overview.packageInfo != null` 且用户点击导入 -> 使用 `packageInfo.uriString` 导入。
- 自动发现失败且没有 `overview.packageInfo` -> 打开手动选包流程。
- 删除备份包成功 -> 包信息区显示“未找到备份包”，删除按钮隐藏。
- 正式包已导入后再导出 -> 状态仍显示当前应用已保存数据，同时可见导出入口。

### 5. Good / Base / Bad Cases

- Good: 新身份包导入成功后，页面显示“数据已保存到当前应用”，并允许继续导出当前备份。
- Base: 没有旧迁移包时，页面仍可作为当前应用备份中心使用，允许导出当前备份。
- Bad: 用户界面继续直写 `bridge` / `newidentity`；页面上明明显示了 zip 路径但点“导入”还是只走自动扫描；删除备份包后页面还显示旧路径。

### 6. Tests Required

- `.\gradlew.bat --console=plain -PSKIP_GO_BUILD=true clean testDiagnosticUnitTest assembleDiagnostic lintDiagnostic`
- 手动选择 zip 后返回页面，再次点击“导入/重新导入”，应直接复用已选 `Uri`。
- 在正式包完成导入后执行一次导出，状态文案仍应保留“数据已保存到当前应用”。

### 7. Wrong vs Correct

#### Wrong

- 正式包只允许导入，不允许导出，逼用户重新装回旧包或寻找旧桥接构建才能做后续备份。
- UI 文案统一叫“数据迁移与备份”，但内部按钮行为仍按 `bridge/newidentity` 硬分裂，导致显示一个包路径却无法复用它导入。

#### Correct

- 单一正式身份 `com.surfsave.browser` 下，同一设置入口里就能完成导入、导出、手动选包和删除本地备份包。
- 只要页面已经持有可用 `packageInfo`，导入动作就直接按这条记录执行，而不是要求用户重复选择或依赖自动扫描。

## 最低验证

- `.\gradlew.bat --console=plain -PSKIP_GO_BUILD=true clean testDiagnosticUnitTest assembleDiagnostic lintDiagnostic`
- `.\gradlew.bat --console=plain -PSKIP_GO_BUILD=true exportDiagnosticApks`
- 至少确认导出包只生成当前 SurfSave 身份 APK，不再生成 `legacy` / `bridge` APK。

## Scenario: Strict versioned migration archive

### 1. Scope / Trigger

- Trigger: 修改迁移 zip 格式、导入/导出条目、Cookie profile、数据库替换、缩略图恢复或迁移包 MediaStore 发布时必须读取本节。

### 2. Signatures

- `MigrationArchiveCodec.write(...): Manifest`
- `MigrationArchiveCodec.read(packageFile: File): ValidatedMigrationPackage`
- `MigrationManager.importMigrationPackage(packageUri: Uri?): MigrationReport`
- `MigrationManager.replaceDatabase(snapshot: DatabaseSnapshot)`
- `ProgressInfoMigrationNormalizer.normalize(progress: ProgressInfo): ProgressInfo`
- `MigrationImportJournal.rollback()`

### 3. Contracts

- 归档 manifest 必须包含明确主版本、条目路径、长度和校验值；未知主版本、重复条目、缺失必需条目或未声明文件整体拒绝。
- zip 条目规范化后必须仍位于 staging 根目录；绝对路径、`..`、分隔符混淆和目录/文件冲突均拒绝。
- 默认导出只含 Cookie profile 元数据，不含 Cookie、token 或凭证内容；只有用户显式选择时才写内容并在 manifest 声明。
- 导出先写同目录 staging，完整关闭并重新读取校验后再发布；新包可用前不得破坏旧包。
- 导入先完整解密、解析和验证到内存/staging，再写任何现有数据；应用阶段用 journal/备份逐层回滚数据库、prefs、Cookie 文件和缩略图。
- v10 数据库写入前统一规范化历史字段；空 `downloadFingerprint` 必须由 `DownloadFingerprint.fromVideoInfo()` 回填，导入和回滚都不能绕过。
- Android 10+ 迁移包发布使用 MediaStore pending 行；发布失败删除新行并保留 staging/旧包用于恢复。

### 4. Validation & Error Matrix

- manifest 未知版本、JSON 非法、校验和/长度不符 -> 导入失败，现有数据逐字节不变。
- 重复路径、路径穿越、未声明条目、解密失败 -> 在应用阶段前整体拒绝。
- 数据库替换或第 N 个 prefs/Cookie/缩略图步骤失败 -> journal 逆序恢复已应用步骤，再返回失败。
- MediaStore `insert/update` 行数异常或最终包无法重新读取 -> 删除 pending 行，不替换旧备份。
- 旧 ProgressInfo fingerprint 为空 -> 在最终 DAO insert 前回填；已有非空值保持。

### 5. Good / Base / Bad Cases

- Good: 完整包通过二次读取校验后发布，导入失败可恢复到操作前数据库、设置和文件。
- Base: 普通备份包含 Cookie profile 名称/域名元数据，但不包含认证内容。
- Bad: 解压时边读边清空数据库，后续一个 JSON 失败后留下部分新数据。
- Bad: 新包写到一半先删除旧包，断电后两个备份都不可用。

### 6. Tests Required

- Codec：未知版本、缺项、重复项、未声明项、路径穿越、长度/校验错误、加密错误。
- Privacy：默认脱敏、显式 Cookie 内容开关、manifest 声明一致。
- Transaction：数据库/prefs/Cookie/缩略图每个故障点均触发逆序回滚。
- Compatibility：legacy 字段、空 fingerprint、手动 content URI、MediaStore pending 发布。
- 构建：`.\gradlew.bat --console=plain -PSKIP_GO_BUILD=true testDiagnosticUnitTest assembleDiagnostic lintDiagnostic`。

### 7. Wrong vs Correct

#### Wrong

```kotlin
database.clearAllTables()
archive.entries.forEach(::applyEntry)
```

#### Correct

```kotlin
val validated = archiveCodec.read(stagedPackage)
val journal = MigrationImportJournal.captureCurrentState()
try {
    applyValidatedPackage(validated)
} catch (error: Throwable) {
    journal.rollback()
    throw error
}
```

## Scenario: Public media compatibility and verified private-file migration

### 1. Scope / Trigger

- Trigger: 修改公共下载目录、视频库枚举、下载重名判断、公共媒体重命名/删除、旧 `SuperX` 私有目录迁移或 `FileUtil.listFiles` 返回结构时，必须按本节执行。
- 目标：新版本可以收拢后续写入位置，但升级后仍能看到旧版本已经下载的媒体；任何私有文件迁移都不能在目标未验证时删除源文件。

### 2. Signatures

- `FileUtil.PUBLIC_RELATIVE_PATH: String = "Download/SurfSave/"`
- `FileUtil.LEGACY_PUBLIC_RELATIVE_PATH: String = "Download/"`
- `FileUtil.listFiles: List<FileUtil.MediaEntry>`
- `FileUtil.MediaEntry(id: Long, displayName: String, uri: Uri, storageClass: MediaStorageClass)`
- `FileUtil.isSharedPublicMedia(context: Context, uri: Uri): Boolean`
- `FileUtil.renameMedia(context: Context, from: Uri, newName: String): RenameMediaResult`
- `FileUtil.deleteMedia(context: Context, uri: Uri): DeleteMediaResult`
- `internal fun FileUtil.migratePrivateDirectory(legacy: File, current: File, moveOperation: (File, File) -> Boolean, copyOperation: (File, File) -> Boolean): Unit`

### 3. Contracts

- 新下载只写入 `Download/SurfSave/`；兼容读取必须同时包含 `Download/SurfSave/` 和旧 `Download/` 根目录。写入边界和升级兼容读取边界不能共用一个路径常量代替。
- 两个公共目录都只枚举直接子文件，不递归扫描 `Download/` 的其他子目录；Android 10+ 查询必须带 `IS_PENDING = 0`，且两条路径都只接受项目支持的媒体扩展名。
- `MediaEntry.uri` 是媒体身份来源；列表和缓存不得以 `displayName` 作为唯一键。两个目录里的同名文件必须同时显示，并具有不同稳定 ID。
- `storageClass` 必须区分 `MANAGED_PUBLIC`、`LEGACY_PUBLIC`、`EXTERNAL_PRIVATE`、`INTERNAL_PRIVATE`。公共根目录旧媒体仍属于共享公共媒体，不能被 UI 当成私有/隐藏目录媒体。
- 下载重名检测覆盖两个可见公共目录；重命名冲突只检查源媒体真实 `RELATIVE_PATH` 中的同名项，不能因另一目录同名而误报。
- Android 10+ 对旧公共媒体的删除和重命名继续使用真实 content URI；无直接写权限时返回 `RecoverableSecurityException` / `MediaStore.createDeleteRequest` 对应的用户授权流程，不能改成裸文件路径操作。
- 私有目录迁移只有在复制返回成功，并且目标递归结构、每个文件长度和 SHA-256 内容都与源一致后，才允许删除源。复制或校验失败时保留源并清理本轮不完整目标；目标清理失败必须记录可见错误，但仍不得删除源。

### 4. Validation & Error Matrix

- 旧媒体位于 `Download/` 根目录 -> 视频库显示为 `LEGACY_PUBLIC`，新下载目标仍为 `Download/SurfSave/`。
- 同名媒体分别位于两条公共路径 -> 返回两条不同 URI/ID 的记录，不覆盖、不合并。
- 媒体位于 `Download/Other/`、仍为 pending 或扩展名不受支持 -> 不显示。
- 重命名目标仅在另一条公共路径同名 -> 允许；源媒体所在路径已经同名 -> 返回冲突。
- 删除/重命名他人创建的 MediaStore 行缺少权限 -> 发起系统授权；用户拒绝 -> 保留原媒体并报告取消/失败。
- `copyDirectory` 返回 false、抛异常、目标缺项、结构不同、长度不同或同长度内容不同 -> 保留完整源，删除不完整目标，不报告迁移成功。
- 复制树逐项验证成功，但源删除失败 -> 保留已验证目标并记录源清理警告，不能反向删除正确目标。

### 5. Good / Base / Bad Cases

- Good: 用户覆盖安装后，同时看到旧 `Download/video.mp4` 和新 `Download/SurfSave/video.mp4`，两项可分别播放和操作。
- Base: 全新安装只有 `Download/SurfSave/` 媒体，行为与单目录版本一致，不额外扫描用户其他下载子目录。
- Bad: 把 `PUBLIC_RELATIVE_PATH` 同时用于写入和唯一查询，目录升级后旧文件仍在磁盘却从应用列表消失。
- Bad: `files.associateBy { it.displayName }` 覆盖跨目录同名媒体。
- Bad: `copyRecursively(...)` 返回 false 后仍执行 `source.deleteRecursively()`。

### 6. Tests Required

- MediaStore：两条精确路径均可见、`IS_PENDING = 0`、其他子目录/不支持扩展名不可见、同名 URI/ID 不同。
- Legacy filesystem：Android 9 及以下仅非递归枚举两个公共目录。
- Operations：旧公共媒体分类正确；重命名按实际路径检查冲突；删除/重命名授权成功、拒绝和失败均保留正确状态。
- Private migration：复制失败保源、同长度不同内容保源、结构缺失保源、验证成功后才删源。
- 构建：`.\gradlew.bat --console=plain -PSKIP_GO_BUILD=true testDiagnosticUnitTest assembleDiagnostic lintDiagnostic`。

### 7. Wrong vs Correct

#### Wrong

```kotlin
val filesByName = query(PUBLIC_RELATIVE_PATH).associateBy { it.displayName }
source.copyRecursively(target)
source.deleteRecursively()
```

#### Correct

```kotlin
val visible = queryExactPaths(
    listOf(PUBLIC_RELATIVE_PATH, LEGACY_PUBLIC_RELATIVE_PATH),
    includePending = false
)
val entries = visible.map(::mediaEntryWithUriIdentity)

val copied = copyDirectory(source, target)
if (copied && copiedTreeMatches(source, target)) {
    source.deleteRecursively()
} else {
    target.deleteRecursively()
}
```
