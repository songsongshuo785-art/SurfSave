# Quality Guidelines

记录当前项目的质量门槛。

迁移到新项目后，至少补充：

- 必跑的 lint、type-check、单元测试、集成测试或脚本校验。
- 变更范围较小时的最低验证要求。
- 高风险变更的额外验证要求和回退方式。
- 项目禁止的偷懒实现模式。

## Android Browser UI Regression Checks

浏览器壳、标签页、检测浮窗和首页属于同一条用户主流程。修改这些区域前先读 `android-browser-contracts.md`；修改后至少核对以下可执行项：

- OkHttp 代理客户端缓存必须用完整代理配置判断是否刷新，不要直接依赖 `Proxy.equals()`。当前 `Proxy.equals()` 只比较 `host` 和 `port`，无法覆盖 `user`、`password`、`type` 变化；修改 `OkHttpProxyClient` 时至少保留“无代理直连、配置不变复用、仅 host 变化、仅 port 变化、仅凭证变化”的单元测试。
- 首页默认以搜索栏和标签页主入口为核心；允许保留一个轻量“添加书签”入口，但不要重新堆叠粘贴链接、继续会话、下载、视频库、历史、工具、设置等快捷贴片。
- 首页内容必须可滚动，不能出现下方内容被截断但无法滑动。
- 新增 UI 不能像便利贴一样叠在旧 UI 上；刷新、Home、检测、下载、视频等入口必须进入所属工具栏、底部栏、标签页或浮窗的布局和状态体系。
- 浏览网页时底部下载和视频入口要可见或明确可达，不能被顶部改版、诊断入口或临时调试入口挤掉。
- WebView 切换标签、退出再进入、打开标签页列表后不能无故重新加载；普通 Fragment view destroy 应保存/脱离 WebView，关闭标签或缓存淘汰才 destroy。
- 网页中普通链接默认在当前标签页继续浏览，除非用户明确选择新标签或网页明确请求新窗口。
- 返回键必须优先走 WebView 历史；标签根部再处理关闭/回首页；首页返回应退出 App，不能无效。
- 刷新按钮必须可见，下拉刷新可以保留但不能成为唯一刷新入口。
- 标签页预览不能只依赖 WebView 截图识别页面。Android WebView 截图可能拿不到硬件视频帧，播放器区域会是黑块；标签卡片必须同时保留标题、host/domain、当前标签状态和缩略图。
- 标签缩略图应优先使用 `fitCenter` / `CENTER_INSIDE` 这类完整显示方式，避免对网页截图二次 `centerCrop` 后只剩页面局部。
- 浏览器地址栏需要区分浏览态和编辑态：浏览态显示标题 + host 或 host，编辑态才显示完整 URL，避免把长 URL 的中间/尾部片段当作主识别信息。
- 加载反馈只能有一个主视觉。下拉刷新、横向进度条、地址栏 stop/refresh 状态同时存在时，必须确认不会出现两个明显加载条。
- 视频检测浮窗必须保持单一外形和单一点击语义；加载态、可下载态、重新检测态不能在同一位置叠出圆形和圆角方形两套控件。长按拖动后要保存位置。
- 诊断报告可以保留内部方法，但普通用户公开动作面板不能暴露诊断入口；设置入口不能被诊断入口替代。
- 首页和浏览器工具栏入口需要保留可见的标签数量提示，用户进入标签页前应能知道当前打开数量。
- 默认搜索引擎必须来自统一设置，中文用户不使用 VPN 也应能直接测试；用户可见按钮和菜单不能大量漏翻成英文。
- 广告过滤必须经 `ContentBlockCoordinator` + 可替换 `ContentBlockEngine`；质量检查搜索旧 `AdBlockManager`、宽泛 DOM 删除、每请求文件读取、远程任意 JS 和站点 host 分支。确认主文档放行、allow 优先、明确广告规则可拦媒体、普通媒体保护、规则更新原子替换、Cosmetic generation/预算/站点关闭和可信 scriptlet 白名单。WebView/ServiceWorker 的 block 必须复用同一资源类型并由 `BlockedResourceResponseFactory` 返回类型匹配响应；selector-based Cosmetic 先保留几何，再分类折叠，保护元素用 selector 排除而不是强制 visible。ServiceWorker 只能使用 context-free engine snapshot，不借活动标签的 page allow、站点开关或 third-party。
- 浏览器请求链顺序固定为 Content Blocking -> MediaRequestInspector -> VideoDetector；WebView 与 ServiceWorker 共用 request context/coordinator，媒体分类仍为单一事实源。质量检查搜索 `VideoUtils.getContentTypeByUrlPath` 和媒体检测开关读取，确认没有在新拦截点复制判断逻辑。
- AndroidX WebKit 可选 API 必须有 `WebViewFeature.isFeatureSupported(...)` 或兼容 API 包装；Media3 `UnstableApi` 使用点应采用项目既有的 `androidx.annotation.OptIn` 写法，避免 Kotlin `@OptIn` 过编译但不过 Android lint。
- 新增字符串要补齐当前维护的完整 locale（默认英文 + `values-zh-rCN`）或显式标为不可翻译；不要新增半成品 `values-*` 目录来制造 `MissingTranslation` 和半翻译界面。
- `GradleDependency` / `NewerVersionAvailable` / `AndroidGradlePluginVersion` 不作为普通 lint 修补项盲升。Kotlin/KSP/OkHttp/WebKit 等版本升级必须作为单独迁移任务处理，先读版本约束、已知注释和官方文档，再跑完整构建回归。
- 清 `UnusedResources` 前必须先搜 `getIdentifier`、`R.<type>.<name>` 和 `@<type>/<name>`；确认无动态引用后，默认资源、`drawable-night` 等 qualifier 覆盖和对应中英文字符串要一起清，不能只删默认文件留下夜间/本地化残片。
- 清 `IconLocation` 时优先把仍在 UI 使用的 24dp 位图改成 vector；启动图标走 `mipmap` 自适应图标体系，不要把 legacy `drawable/*.png` 留给 splash 继续触发 launcher shape/density warning。
- `UseKtx` 建议按项目已接入的 AndroidX KTX 等价 API 落地，例如 `toUri()`、`SharedPreferences.edit {}`、`Bitmap.scale()`、`createBitmap()`；除非行为会变化，不要用 suppress 掩盖机械迁移。
- `SetJavaScriptEnabled` 只能在浏览器 WebView 这类明确需要 JS 的边界内保留。临时/隐藏 popup WebView 要继承父 WebView 设置，并用最小作用域 `@SuppressLint("SetJavaScriptEnabled")` 写清理由，不能给任意 WebView 默认开 JS。
- Data Binding 自定义 `@BindingAdapter` 注解只写属性名，例如 `@BindingAdapter("items")`，不要写 `@BindingAdapter("app:items")`；Data Binding 生成器会忽略 namespace 并产生 KAPT 警告。不要覆盖内置 `android:src` adapter，除非有真实 XML 使用和明确行为差异。
- 修改 Gradle/KSP/lint 相关配置后必须用 `.\gradlew.bat --console=plain -PSKIP_GO_BUILD=true clean assembleDiagnostic lintDiagnostic` 至少跑一次 clean 验证；增量 lint 可能保留已删除 resource locale 的模型缓存。
- 把任务输出目录注册进 Android `sourceSets` 时，除 merge/package 任务外还要让 Lint 模型生成、分析等整个 Lint 任务族显式依赖生成任务；否则 clean lint 会因隐式读取生成目录而失败，增量构建可能掩盖这个问题。
- 若浏览器设计不确定，需要先参考成熟浏览器；需要外部经验时到 Linux.do 检索并把结论形成可追踪的书面研究记录。

实机回归样例：

- `https://rule34video.com/video/4346854/mercy-fucked-mercilessly-bbc/`
- `https://www.reddit.com/r/TikTokCringe/comments/1t4k03l/its_implied_right/`

最低验证命令：

```powershell
.\gradlew.bat -PSKIP_GO_BUILD=true assembleDiagnostic
.\gradlew.bat -PSKIP_GO_BUILD=true lintDiagnostic
```

若全量 Go 依赖下载不是本次变更目标，允许使用 `SKIP_GO_BUILD=true` 跳过 Go shared library build，但最终说明中必须标明使用了该参数。

## Scenario: Open-source Release Readiness

### 1. Scope / Trigger

- Trigger: 任何准备公开仓库、发布源码、调整 CI/CD、修改 README/隐私/安全/贡献文档、清理本地调试产物或发布 APK 的任务。
- 目标：公开入口、CI、发布文档、敏感信息边界和构建验证必须一起收口，不能只改 README 就宣称可发布。

### 2. Signatures

- 快速验证命令：
  ```powershell
  .\gradlew.bat --console=plain -PSKIP_GO_BUILD=true testDiagnosticUnitTest assembleDiagnostic lintDiagnostic
  ```
- 完整诊断导出命令：
  ```powershell
  .\gradlew.bat --console=plain exportDiagnosticApks
  ```
- 正式候选构建与本地下载命令：
  ```powershell
  .\scripts\Build-ReleaseCandidate.ps1 -Tag vX.Y.Z
  ```
- 已验收候选的原样晋级命令：
  ```powershell
  .\scripts\Publish-ReleaseCandidate.ps1 -ConfirmTag vX.Y.Z
  ```
- GitHub 普通 CI 应使用无密钥 diagnostic 验证；只有手动 `release-candidate.yml` 允许读取签名 secrets。
- `promote-release.yml` 只能按候选 run ID 下载同一 artifact，不得运行 Gradle、Go、打包或签名。

### 3. Contracts

- 应用身份：
  - `applicationId = "com.surfsave.browser"`
  - `namespace = "com.myAllVideoBrowser"`，除非专项 namespace 迁移，否则不改。
- 普通 CI 不得依赖 `KEYSTORE_PATH`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`、`KEYSTORE_BASE64`。
- 手动 candidate job 可以依赖签名 secrets；promotion job 不得依赖私钥或签名 secrets。
- 标签 push 不得触发 APK 构建。最终注释 tag 由通过验证的 promotion job 创建或核对，并必须指向候选清单的 source SHA。
- 候选固定包含五个 Release APK 与 `release-candidate-manifest.json`，清单绑定 repository、workflow path/run ID、source SHA/ref、tag/version、applicationId、证书指纹、APK 文件名/大小/versionCode/SHA-256。
- 本地候选 APK 必须下载到既有 `app/build/outputs/apk/release/`；不得另建仓库外候选目录或把本地 Diagnostic APK 当正式候选。
- `app/build/outputs/apk/release/app-arm64-v8a-release.apk` 是唯一标准手机测试包；`app/build/outputs/apk/diagnostic/` 中的任何 APK 都不是等价入口，不得通过复制或改名冒充 release candidate。
- `exportDiagnosticApks` 的输出文件名必须显式包含 `INTERNAL-DIAGNOSTIC`，控制台同时提示不得用于用户验收或发布；release candidate workflow 必须拒绝 `application-debuggable` 和缺少 V2 签名的 APK。
- promotion 只能上传清单列出的五个 APK；公开前后都必须用 GitHub Release asset digest 与清单 SHA-256 比对。公开 Release 禁止覆盖，失败修复使用更高版本。
- 公开文档至少覆盖 `README.md`、`PRIVACY.md`、`SECURITY.md`、`CONTRIBUTING.md`、`CHANGELOG.md` 和第三方声明。
- `.gitignore` 必须覆盖本地 AI 文件、构建缓存、APK/AAB、签名文件、crash/replay 日志和 debug 截图/日志。

### 4. Validation & Error Matrix

- README 指向旧包名或旧 F-Droid 包 -> 阻止发布，改成当前 SurfSave 身份或移除过期渠道。
- 源码出现真实 `token=`、`js_challenge=`、私钥、keystore 或 cookie 值 -> 阻止发布，删除或脱敏。
- 普通 push/PR CI 需要签名 secrets -> 阻止发布，拆成无密钥 verify job 和手动 candidate job。
- tag push 包含 `assembleRelease` / Go / signing -> 阻止发布；这会在用户验收后重新生成第二套字节。
- promotion workflow 出现 `gradlew`、`assembleRelease`、`setup-go`、keystore decode 或签名 secret -> 阻止发布；promotion 必须是纯制品晋级。
- candidate run/repository/workflow/default branch/source SHA/tag/version/证书/五 APK 集/size/hash 任一不符 -> 拒绝下载或晋级。
- Release 已公开 -> 拒绝覆盖；同 tag draft 可在 tag source SHA 和候选来源全部一致时清理残留 asset 后续传。
- 根目录存在 `debug-*`、`hs_err_pid*.log`、`replay_pid*.log`、`tmp/`、`.claude/`、`.kotlin/` 等本地产物 -> 删除或确认被 ignore；含真实 token 的日志必须删除。
- `git status`/`git check-ignore` 因不是 Git 仓库无法执行 -> 说明限制，并用目录扫描与 `.gitignore` 内容做替代检查。

### 5. Good/Base/Bad Cases

- Good: push/PR 只跑无密钥诊断门；手动 candidate 完整构建并签名一次，用户安装 `app/build/outputs/apk/release/` 中同一 APK，promotion 按 run ID 和 SHA-256 原样发布；README、隐私、安全、贡献和第三方声明与当前 app 身份一致。
- Base: 本地保留 `local.properties` 和 `app/build/`，但二者被 `.gitignore` 覆盖，并在最终说明中标明不应发布裸 workspace 压缩包。
- Bad: 用户测试 Diagnostic 或本地临时包，随后 tag workflow 重新 build 正式包；promotion 可访问 keystore；README 仍展示旧身份；源码或 debug 日志中残留真实凭据。

### 6. Tests Required

- 必跑 Gradle 快速验证命令，记录 tests/failures/errors/skipped 与 lint issue 数。
- 复扫敏感词，至少覆盖 `token=`、`js_challenge`、`Authorization:`、`Cookie:`、`BEGIN PRIVATE`、`KEYSTORE_PASSWORD`、`KEY_PASSWORD`、`client_secret`、`api_key`。
- YAML 修改后尽量用本地 YAML parser 解析 GitHub/GitLab CI；如果工具不可用，最终说明必须标明未验证。
- 发布 workflow 修改后运行 `actionlint`、PowerShell AST parser 和 `.github/scripts/test_release_*.py`；测试必须明确断言 tag 不构建、candidate 是唯一 Release builder、promotion 无构建/签名命令、本地路径仍为既有 release 输出目录。
- 候选下载脚本必须验证 manifest 中的五 APK 集、大小和 SHA-256 后直接保留在 canonical release 输出目录，不再复制到 Gradle 自己管理的 diagnostic 输出文件名。
- 候选清单至少覆盖正常 round-trip、APK 字节被改、tag 不符和 Release asset digest 不符回归。
- 新流程首次真实发版时必须记录 candidate run ID、本地清单 SHA、promotion run ID、tag/source SHA 与五个公开 asset digest；不能只看到 workflow success 就宣称同制品。
- 删除本地目录前必须 Resolve-Path，并确认目标路径位于当前 workspace 内。

### 7. Wrong vs Correct

#### Wrong

```yaml
jobs:
  build:
    env:
      KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
    steps:
      - run: ./gradlew assembleRelease
```

#### Correct

```yaml
jobs:
  verify:
    steps:
      - run: ./gradlew --console=plain -PSKIP_GO_BUILD=true testDiagnosticUnitTest assembleDiagnostic lintDiagnostic

  candidate:
    if: github.event_name == 'workflow_dispatch'
    permissions:
      contents: read
    steps:
      - run: ./gradlew --console=plain assembleRelease

  promote:
    permissions:
      actions: read
      contents: write
    steps:
      - uses: actions/download-artifact@v5
        with:
          run-id: ${{ inputs.candidate_run_id }}
      - run: python3 .github/scripts/release_manifest.py verify-release ...
```

## Scenario: Diagnostic upgrade version-code policy

### 1. Scope / Trigger

- Trigger: 修改 `baseVersionCode`、ABI split、Diagnostic/Debug 版本码、`TEST_BUILD_CODE`，或生成需要覆盖已安装正式版的本地测试 APK。
- 目标：同 `applicationId`、同签名的 Diagnostic APK 必须能覆盖当前任意 Release 分包，不能依赖“等待时间戳自然追上正式版”。

### 2. Signatures

- `baseVersionCode: Int`
- `abiCodes: Map<String, Int>`
- `TEST_BUILD_CODE: Long?`（Gradle property 或环境变量）
- `verifyDiagnosticVersionCodePolicy`（Gradle verification task）

### 3. Contracts

- `releaseMax = baseVersionCode + max(abiCodes.values)`。
- `diagnosticBase = max(TEST_BUILD_CODE 或当前 Unix 秒, releaseMax + 1)`。
- 所有 Diagnostic output 继续在 `diagnosticBase` 上叠加同一份 `abiCodes`，不得复制第二套 ABI 最大偏移常量。
- `diagnosticBase + maxAbiOffset` 不得超过 Android `2_100_000_000` 上限。
- `preDiagnosticBuild` 必须依赖 `verifyDiagnosticVersionCodePolicy`，让普通 Diagnostic 构建自动执行合同检查。
- 该策略不改变 `applicationId`、证书、Release 固定版本码或既有公开 Release。

### 4. Validation & Error Matrix

- 当前时间戳或 `TEST_BUILD_CODE` 低于/等于 `releaseMax` -> 自动提升到 `releaseMax + 1`。
- `diagnosticBase + maxAbiOffset > 2_100_000_000` -> 配置阶段失败，不生成 APK。
- 最低 Diagnostic output 不高于最高 Release output -> `verifyDiagnosticVersionCodePolicy` 失败。
- 签名或 `applicationId` 与正式版不同 -> 版本码通过也不能视为可覆盖安装，必须单独用 `apksigner` / `aapt` 核对。

### 5. Good / Base / Bad Cases

- Good: 正式版版本码暂时领先系统时间，传入 `-PTEST_BUILD_CODE=1` 后，五个 Diagnostic APK 仍全部高于最高 Release 分包。
- Base: 系统时间戳已经高于安全下限，Diagnostic 继续按秒级时间戳递增。
- Bad: Diagnostic 直接使用当前秒数；刚发布一个“未来版本码”的正式版后，本地测试包被 Android 判定为降级。

### 6. Tests Required

- 低值回归：`.\gradlew.bat --console=plain -PSKIP_GO_BUILD=true -PTEST_BUILD_CODE=1 verifyDiagnosticVersionCodePolicy`，断言 `diagnosticBase = releaseMax + 1`。
- 正常路径：不传 `TEST_BUILD_CODE` 执行同一任务，断言时间戳高于下限时未被固定在 floor。
- Gradle 配置改动至少执行一次 `clean testDiagnosticUnitTest assembleDiagnostic lintDiagnostic`；读取 `output-metadata.json`，断言五个 Diagnostic `versionCode` 都大于 `releaseMax`。
- 对最终实测 APK运行 `aapt dump badging` 与 `apksigner verify --print-certs`，核对 `applicationId`、versionCode 和历史证书指纹。

### 7. Wrong vs Correct

#### Wrong

```kotlin
val testBuildVersionCode = (System.currentTimeMillis() / 1000).toInt()
```

#### Correct

```kotlin
val releaseMax = baseVersionCode.toLong() + abiCodes.values.max()
val diagnosticBase = maxOf(requestedOrEpochSeconds, releaseMax + 1L)
```
