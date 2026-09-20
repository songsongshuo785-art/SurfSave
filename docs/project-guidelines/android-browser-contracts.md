# Android Browser Contracts

本文件记录当前 App 的浏览器壳、WebView、标签页、检测浮窗、搜索和首页的长期实现契约。它不是通用 UI 建议，而是本项目已经通过调试和讨论沉淀出的防返工规则。

## 适用范围

修改以下区域前必须阅读本文件：

- `BrowserFragment`、`WebTabFragment`、`BaseWebTabFragment`、`WebTabViewModel`、`WebTab`、`WebTabsAdapter`。
- 首页、地址栏、标签页列表、底部下载/视频入口、刷新/下拉刷新、返回键。
- 视频检测浮窗、检测结果入口、下载入口。
- `MediaRequestInspector`、`UrlInputNormalizer`、`SharedPrefHelper` 中会影响网页兼容、搜索和检测的逻辑。

## 设计不确定时的取经流程

浏览器交互不确定时，不允许凭感觉直接做。先按下面顺序确认：

1. 查当前代码和用户已经明确确认的方向。
2. 对照成熟移动浏览器做法，例如 Chrome、Kiwi、Samsung Internet。
3. 需要外部经验时，到 Linux.do 检索 Android WebView、浏览器壳、移动端标签页、地址栏、视频播放兼容等相关讨论。

研究结论不能只留在聊天里。复杂任务要形成可追踪的书面研究记录，至少记录：

- 问题是什么。
- 参考了哪些成熟做法或 Linux.do 讨论。
- 采用哪种方案。
- 放弃哪种方案，以及为什么不适合本 App。

Linux.do 只能作为取经来源，不能压过本项目目标。最终优先级是：视频检测可用、网页浏览舒适、下载入口清楚、后续维护容易。

## 禁止便利贴式叠加 UI

新增设计不能像贴便利贴一样压在旧 UI 上。凡是属于地址栏、底部工具栏、首页、标签页或检测浮窗的功能，都必须进入对应组件自己的布局、状态和事件体系。

Wrong:

```text
旧地址栏保持不动，再在上面覆盖一个刷新按钮。
旧首页快捷宫格保持不动，再贴一个新搜索面板。
WebView 上固定盖一个检测窗口，挡住网页内容。
旧检测按钮不删，又新增一套圆角方形按钮。
```

Correct:

```text
找到原组件职责边界。
把新状态合并进原状态机。
删除或降级已经被替代的旧入口。
让新增功能成为原布局的一部分，而不是覆盖物。
```

质量检查时如果看到“旧 UI + 新浮层/新卡片/新按钮”同时存在，默认判定为需要返工，除非这是明确的系统弹窗、BottomSheet 或用户确认过的临时调试入口。

## WebView 生命周期契约

WebView 是标签页状态本体，不是普通的 Fragment view。普通视图销毁、切换标签、回到首页、进入设置页时，不能无故重载网页。

契约：

- 切换标签页时优先复用已有 WebView。
- Fragment view destroy 时优先保存 WebView state 并 detach WebView，不直接 destroy。
- 只有关闭标签、标签缓存淘汰、Activity 真实结束等明确生命周期边界才 destroy WebView。
- 新建 WebView 时先尝试 restore state；只有没有 saved state 时才 `loadUrl`。
- detach 时清理旧的 `WebViewClient` / `WebChromeClient` 对 Fragment 或 binding 的引用，避免泄漏。
- 不用 `LOAD_NO_CACHE` 作为默认浏览策略；默认浏览应允许 WebView 使用正常缓存。
- `onRenderProcessGone` 必须在标签层处理：确认丢失的是当前标签的 WebView 后，销毁坏实例、清理 saved state，并按当前 URL 重建 WebView；不要把崩溃交回系统导致整个 App 退出。
- AndroidX WebKit 的可选能力必须先用 `WebViewFeature.isFeatureSupported(...)` 检查，再调用对应 API。`ScriptHandler.remove()` 和 document-start script 同属 `DOCUMENT_START_SCRIPT` 能力边界。
- 诊断信息读取 WebView 包版本时使用 `WebViewCompat.getCurrentWebViewPackage(context)`，不要直接调用平台 `WebView.getCurrentWebViewPackage()` 再靠 API 版本分支补洞。

Good/Base/Bad:

- Good: 切换 5 个标签后回到原标签，页面位置、播放区域和历史栈尽量保持。
- Base: 内存压力导致老标签被淘汰时，能从 saved state 或 URL 恢复，且用户能理解这是恢复而不是无提示跳页。
- Bad: 每次切换标签、退出再进、打开标签页列表后网页都重新加载。

## 导航、链接和返回键

浏览器行为要贴近用户对普通浏览器的预期。

- 网页内点击普通链接，默认在当前标签继续浏览。
- 只有网页明确请求新窗口、用户明确选择新标签、或下载/检测流程需要隔离时，才创建新标签。
- 返回键优先走当前 WebView 历史。
- 当前标签没有 WebView 历史时，再按浏览器层级处理：关闭当前标签或回到首页。
- 首页按返回应退出 App，不能表现为无效。
- 标签根部返回到主页和退出 App 的边界必须可预测，不能让用户陷入“按了没反应”。

## 刷新与加载反馈

刷新能力必须同时满足“看得见”和“不打扰”：

- 浏览器页必须有可见刷新按钮。
- 下拉刷新可以保留，但不能成为唯一刷新入口。
- 刷新按钮、停止加载、横向进度条、SwipeRefreshLayout 需要归属同一个加载状态。
- 同一时刻只能有一个主视觉加载反馈，避免顶部出现两个明显加载条。
- 加载反馈不能遮挡网页主要内容。

## 地址栏和搜索

地址栏必须区分浏览态和编辑态：

- 浏览态显示页面标题 + host，或只显示 host；不要把长 URL 的中间片段作为主识别信息。
- 点击地址栏后进入编辑态，编辑态显示完整 URL 或当前搜索词。
- 进入编辑态时选中文本、弹出输入法，并给出清楚的可编辑视觉。
- 搜索引擎必须通过统一偏好设置读取，不允许在多个调用点硬编码。
- 默认搜索引擎应适合中文用户直接使用，当前新用户默认百度；已有用户明确保存的 Bing、Google 或 DuckDuckGo 选择必须保留。
- 首页搜索必须同时提供输入法搜索动作和可见的 48dp 以上搜索按钮，两者复用同一提交函数；粘贴链接只能作为次级操作，不能在视觉上伪装成搜索提交。
- 搜索建议或搜索结果不能依赖必须使用 VPN 才稳定可用的服务。

## 首页结构

首页是浏览起点，不是功能大厅。

- 默认只保留搜索栏和标签页入口。
- 不再默认堆叠粘贴链接、继续会话、下载、视频库、书签、历史、工具、设置等快捷入口。
- 首页可以保留一个轻量“添加书签”入口，满足用户手动添加快捷书签的需求；它必须放在搜索/标签页入口之后，不能扩展成下载、视频库、历史、工具、设置等功能大厅。
- 书签列表、历史、设置、工具可以存在于菜单、底部导航或设置层级，但不能抢占首页第一视觉。
- 首页内容如果超出屏幕必须可滚动，不能出现下方内容被截断却无法滚动。
- 用户进入标签页前必须能看到当前标签数量。

## 检测结果详情

- 检测结果面板的首屏目标是让标题、在线播放、下载和当前格式无需拖动即可发现；分享和更多格式属于次级信息。
- 小屏、多视频或大字体下允许单一外层纵向滚动，不强求所有内容永不滚动，也不得通过过小文字或小于 48dp 的触控区域硬塞。
- 格式横向列表不能和外层纵向滚动形成重复的纵向手势竞争。

## 标签页契约

标签页是用户识别上下文的地方，不只是截图容器。

- 标签上限按已确认方向支持到 100。
- 标签入口和标签列表必须显示标签数量。
- 标签页卡片要尽量展示“离开标签前的可见页面画面”的完整画幅，不允许 `centerCrop` 后只剩页面局部。
- Android WebView 截图可能截不到硬件视频帧，播放器区域可能是黑块；因此必须同时保留标题、host/domain、当前标签边框和缩略图。
- 当前标签要有清楚边框或状态标识。
- “关闭其他”和“全部关闭”容易误触，不能作为高频主入口；如保留，必须移到低优先级菜单并加确认或撤销。

## 底部下载和视频入口

下载和视频检测是本 App 的核心差异点，不能因为 UI 改版被隐藏。

- 浏览网页时，底部下载和视频入口应保持可见或以明确方式可达。
- 这些入口不能和诊断功能、临时测试功能混在一起。
- 布局紧凑可以做，但不能牺牲核心入口的可发现性。

## 视频检测浮窗

检测浮窗必须小、稳定、可移动。

- 默认是一个小检测图标，不是大检测窗口。
- 必须是单一视觉容器和单一点击语义，不能同一位置同时出现圆形和圆角方形两套控件。
- 支持长按自由拖动，并保存相对位置。
- 不默认强制吸边，除非用户之后明确要求。
- 拖动要有阈值，避免普通点击被误判为拖动。
- 检测浮窗不能遮挡网页主要内容；位置冲突时用户可以自行挪开。

## 视频播放器兼容

网页播放器灰屏、初始化失败或视频区域空白时，不允许只看“检测到了视频”就判断功能完成。需要优先排查：

- WebView JavaScript、DOM storage、media playback、mixed content、third-party cookie、user agent、硬件加速。
- 广告过滤不得用宽泛选择器或站点硬编码任意修改页面 DOM；只有下面“规则驱动 Content Blocking”场景规定的受预算 Cosmetic/可信 scriptlet 可以注入。没有高置信广告证据时不得仅因资源是第三方媒体、短视频或先播放就拦截。
- 页面是否需要用户手势后才能播放。
- 是否是站点安全策略、地域策略或挑战页导致播放器无法初始化。

检测功能和网页播放是两条链路：检测到视频不等于页面播放器已经可用。

## Scenario: 网页视频在线播放目标选择

### 1. Scope / Trigger

- Trigger: 修改检测详情页的“在线播放”按钮、外部播放器调用、默认播放器记忆或 Android 系统 Chooser 时必须读取本节。
- 目标是让日常播放保持一次点击，同时允许用户只保存自己实际使用过的外部播放器；任何具体第三方播放器都只是普通目标，不得写死为产品依赖。

### 2. Signatures

- `PlaybackTargetStore.rememberAndSetDefault(componentName: ComponentName)`
- `PlaybackTargetStore.remove(componentName: ComponentName)`
- `PlaybackTargetResolver.availableTargets(context, store, builtInLabel): List<PlaybackTarget>`
- `ExternalPlaybackIntentFactory.createPlaybackIntent(mediaUrl, title, componentName?): Intent`
- `Intent.createChooser(target, title, chosenComponentSender)`，选择结果通过 `Intent.EXTRA_CHOSEN_COMPONENT` 进入私有 `PlaybackTargetChosenReceiver`。

### 3. Contracts

- 按钮是同一个圆角容器内的两个 48dp 以上触控区：左侧使用当前默认目标直接播放；右侧只打开已记忆目标和固定末项“播放器新增”。
- 新安装或默认组件为空时使用 SurfSave 内置播放器。点击菜单中的任一目标会立即播放并把它设为当前默认。
- 不预扫描并缓存手机上的全部播放器；“播放器新增”调用系统 Chooser，只有系统实际返回的精确 `ComponentName` 才进入记忆列表。
- 目标列表按当前 locale 的应用显示名、不区分大小写排序；“播放器新增”固定在最后，不参与排序。
- 长按外部目标只删除 SurfSave 的记忆，不卸载应用；内置播放器不可删除。删除当前默认项后删除默认组件键，回到内置播放器。
- Chooser 回调使用显式、`exported=false` 的接收器和 one-shot `PendingIntent`；Android 12 以上必须为 mutable，使系统能追加 `EXTRA_CHOSEN_COMPONENT`。回调可能因用户取消而不发生，不能据此伪造选择。
- 外部 `ACTION_VIEW` 只发送媒体 URI、`video/*` 和公开标题，不发送 Cookie、Authorization 或完整内部请求头；需要网页登录头、401/403 刷新或重新解析的媒体继续由内置播放器负责。

### 4. Validation & Error Matrix

- 默认组件未安装、disabled、non-exported 或 Activity 已改名 -> 移除记忆和默认键，提示用户并使用内置播放器。
- 系统 Chooser 没有兼容 Activity -> 保持当前默认不变并显示“没有可用播放器”。
- Chooser 被取消或回调没有 `EXTRA_CHOSEN_COMPONENT` -> 不新增、不改变默认目标。
- 显式启动外部 Activity 抛 `ActivityNotFoundException` / `SecurityException` -> 记录非敏感组件名、移除目标、提示并回退内置播放器。
- 删除非默认外部目标 -> 其他目标和默认值保持不变；删除当前默认外部目标 -> 内置播放器成为默认。

### 5. Good / Base / Bad Cases

- Good: 初始菜单只有 SurfSave 内置播放器和“播放器新增”；用户从系统栏选择任意播放器后，下次菜单按名称显示并可直接使用或长按删除。
- Base: 系统未返回选择回调时，本次仍由系统打开播放器，但 SurfSave 不把未知目标写入列表。
- Bad: 在代码或资源里固定加入 mpvEx、VLC、MX Player 等包名；启动时枚举全部应用；把网页 Cookie 放入外部 Intent；删除列表项时尝试卸载应用。

### 6. Tests Required

- `PlaybackTargetsTest`：默认内置、组件记忆 round-trip、删除当前默认回内置、不可用组件清理、字母排序、Chooser 包装和回调记忆。
- Intent 隐私断言：外部 Intent 只有公开标题元数据，不含 Cookie、Authorization 或自定义 headers Bundle。
- 编译/Lint：Data Binding 的 `onChoosePlayer` 新签名、分体按钮资源、私有 Receiver manifest 均通过。
- 手动：初始菜单、系统新增、两个以上播放器排序、左侧默认直达、长按删除、卸载默认播放器后的回退。

### 7. Wrong vs Correct

#### Wrong

```kotlin
val targets = listOf("mpvEx", "VLC", "MX Player")
externalIntent.putExtra("headers", headersIncludingCookie)
```

#### Correct

```kotlin
val chooser = Intent.createChooser(target, title, chosenComponentSender)
// Receiver persists only Intent.EXTRA_CHOSEN_COMPONENT.
val externalIntent = ExternalPlaybackIntentFactory.createPlaybackIntent(url, title)
```

## Scenario: 网页媒体交接原生或外部播放器

### 1. Scope / Trigger

- Trigger: 修改检测视频的内置播放、系统 Chooser、外部播放器启动或 WebView 媒体暂停/恢复时必须遵守本节。
- 目标是建立单一播放所有权，避免目标播放器已经开播但原网页仍发声。

### 2. Signatures

- `WebViewMediaController.pauseBeforeExternalPlayback(webView, onSuspended)`
- `WebViewMediaController.pause(webView)`
- `WebViewMediaController.resume(webView)`
- `ExoPlayer.Builder.setAudioAttributes(audioAttributes, handleAudioFocus = true)`

### 3. Contracts

- 目标播放器必须在 WebView 暂停 JavaScript 返回确认后启动；确认丢失时使用短超时继续，回调和超时竞争只能完成一次。
- 暂停状态不只是一次 `media.pause()`：页面处于后台播放交接期间要捕获 `<video>/<audio>` 的 `play`/`playing`，阻止页面脚本重新开播，并通过 document-start 注入的消息监听覆盖子 frame。
- `resume()` 先恢复 WebView 生命周期，再解除媒体阻止；解除时不得主动调用 `play()`，是否恢复由用户或网页后续操作决定。
- 内置 Media3 播放器必须让 ExoPlayer 自动管理音频焦点，作为 WebView/站点不完全遵守暂停时的第二层保障。
- 不得使用 `WebView.pauseTimers()`；它影响同进程所有 WebView。不得为解决双重播放销毁或重载当前标签。

### 4. Validation & Error Matrix

- JavaScript 正常确认 -> 暂停 WebView 生命周期并立即启动目标播放器。
- JavaScript 不回调 -> 超时后暂停生命周期并启动一次，不能无限等待或双启动。
- `evaluateJavascript` 抛异常 -> 记录非敏感错误，暂停生命周期并继续启动目标播放器。
- Fragment 在等待期间已分离 -> 取消目标 Activity 启动，不操作失效视图。
- 返回浏览器 -> 解除阻止但保持媒体暂停；用户可手动恢复。

### 5. Good / Base / Bad Cases

- Good: 网页视频有声播放时点击“在线播放”，原网页先静音/暂停，内置播放器普通和全屏状态都只有一份声音。
- Base: 页面 renderer 忙碌不返回确认，最多经历短延迟后仍能打开目标播放器，音频焦点继续兜底。
- Bad: `evaluateJavascript(..., null)` 后立即 `startActivity()`；只调用一次 `pause()` 却允许站点后台重新 `play()`；用全局 `pauseTimers()` 掩盖问题。

### 6. Tests Required

- 暂停确认前不得调用启动回调，确认后恰好调用一次并执行 `WebView.onPause()`。
- 超时先发生、随后迟到确认时，启动回调和 `onPause()` 仍各一次。
- 脚本合同断言包含播放阻止状态、捕获式 `play` 监听、frame pause 消息和 resume 解除逻辑。
- 完整门禁至少运行 `testDiagnosticUnitTest assembleDiagnostic lintDiagnostic`；实机同时验证内置普通/全屏、Chooser/外部播放器和返回网页后的手动恢复。

### 7. Wrong vs Correct

#### Wrong

```kotlin
webView.evaluateJavascript("document.querySelector('video')?.pause()", null)
startActivity(playerIntent)
```

#### Correct

```kotlin
WebViewMediaController.pauseBeforeExternalPlayback(webView) {
    startActivity(playerIntent)
}
```

## Scenario: 规则驱动 Content Blocking

### 1. Scope / Trigger

- Trigger: 修改广告/跟踪请求过滤、`shouldInterceptRequest`、ServiceWorker、规则订阅、Cosmetic、scriptlet、弹窗策略、广告开关或站点例外时必须读取本节。
- 目标是以 SurfSave 自有 Content Blocking 架构统一网络、页面和弹窗策略；底层规则引擎可替换，站点定制必须是规则数据，不得写成 WebView 核心里的 host 分支。

### 2. Signatures

- `ContentBlockCoordinator.evaluate(request: ContentBlockRequest): ContentBlockDecision`
- `ContentBlockEngine.evaluate(request: ContentBlockRequest): ContentBlockDecision`
- `BrowserResourceTypeResolver.resolve(url, headers): BrowserResourceType`
- `BlockedResourceResponseFactory.create(resourceType): WebResourceResponse`
- `ContentBlockWebController.attach(webView)` / `injectCurrentDocument(webView)`
- `ExtendedCosmeticSelectorParser.partition(selectors): Result`
- `ProceduralCosmeticSanitizer.sanitize(rules): List<SafeProceduralRule>`
- `ContentBlockEngine.cosmeticResources(url): CosmeticResources`
- `FilterSubscriptionRepository.update(): FilterUpdateResult`
- 底层生产引擎为 SurfSave 自建 JNI 包装的 `adblock-rust`；现有 Kotlin `BrowserAdFilter` 只作为开发期故障基线和明确回退，不作为长期领域接口。

### 3. Contracts

- WebView 和 ServiceWorker 必须共用 `ContentBlockCoordinator`，广告判断发生在媒体检测副作用之前；`MediaRequestInspector` 只保留媒体检查职责，不得继续膨胀成广告/弹窗/Cosmetic 集合体。
- 决策顺序固定为：主文档放行 -> 站点总开关/明确 allow -> 高置信 block -> 未命中广告证据的媒体保护 -> 其他网络规则。明确 block 可阻止 MP4/HLS/DASH/分片；普通媒体不因类型本身被判广告。
- `BrowserResourceType` 由 `Sec-Fetch-Dest`、`Accept`、URL 扩展名和媒体分类器保守推断，不得伪装成 WebView 的绝对资源类型；明确媒体/脚本/图片等信号优先，Chromium 的 `Sec-Fetch-Dest: empty` 在无更强信号时映射为 `XML_HTTP_REQUEST`，完全无信号才保持 `UNKNOWN`。
- 被阻止请求必须由 `BlockedResourceResponseFactory` 按同一份 `BrowserResourceType` 生成中性响应：script/css/image/subdocument 使用可解析的匹配 MIME，媒体、XHR、font、ping 等无替代语义的资源使用 204；WebView 与 ServiceWorker 不得各自复制 `text/plain` 空响应。主文档仍由决策层放行，类型工厂不能成为伪造错误页的第二套路由。
- ServiceWorker 没有 WebView/Tab 身份，只能使用单独的 context-free Engine snapshot，不执行 document domain、third-party、per-site 开关、page allow 或 Tab 临时状态；Referer/Origin 也不能被当作可靠 Tab 身份。
- `shouldInterceptRequest` 只能直接判断请求，不能旁观最终响应正文。不得为 VAST、`$removeparam`、CSP 或响应改写引入全量 OkHttp 代理；不支持的规则在预处理阶段剔除并计数，不能误当空响应。
- redirect 只允许映射到 APK 内置可信 replacement；远程规则不能提供任意重定向数据或 JavaScript 源码。
- `EXT-X-DATERANGE` 和 `EXT-X-DISCONTINUITY` 本身不是广告证据；没有明确 SCTE/Interstitial 广告语义时不得删除 HLS 分片，SSAI 跳过不属于当前网络过滤器。
- 请求热路径只读取不可变引擎快照，不逐请求读文件、解析规则或获取长写锁；规则更新后台编译并原子替换，失败保留 last-known-good。旧代次失败时只能用 CAS 移除自身，不得用无条件 `getAndSet(null)` 清掉并发安装的新代次。
- JNI 若用 `catch_unwind` 将 Rust panic 转成 Java 异常，生产 profile 必须使用 `panic = "unwind"`；`panic = "abort"` 会绕过捕获并终止 Android 进程。
- 内置 EasyList/EasyPrivacy/SurfSave 规则必须记录版本、许可证和 SHA-256；站点定制写规则/unbreak，不在 WebViewClient、Fragment 或 inspector 增加站点 if/else。
- 新安装和从未保存过开关的升级安装默认开启 Content Blocking；用户显式保存的 `false` 必须保留。默认开关、Manager runtime 状态和设置 UI 必须来自同一 SharedPreferences 键。
- Cosmetic 使用 document-start 能力检测、navigation generation 隔离、选择器/CSS/耗时预算和按站点关闭；禁止无限 MutationObserver 全 DOM 重扫。Manager 从 INITIALIZING 进入 BUNDLED/UP_TO_DATE/STALE 等可用状态后，已附着 WebView 必须撤销旧代并补一次 bootstrap；关闭时撤销当前页可逆修改，不强制 reload 全部标签。
- 不得假设 `adblock-rust.url_cosmetic_resources()` 会把全部 EasyList 扩展选择器放进 `procedural_actions`。Rust 结构化 action 要作为 JSON 对象传递；仍留在 `hide_selectors` 的通用 `:has-text`、`:-abp-contains`、`:-abp-properties`/`:properties` 必须由独立适配层转成结构操作。native `:has` 必须走带 DOM 结构保护的程序化路径；没有明确基础选择器的 properties 规则不得近似成 `body * + getComputedStyle`，因为这既不等价于样式表属性语义，也会按尺寸/浮动误伤正常布局。
- 程序化 Cosmetic 进入 JavaScript 前必须在 Kotlin 二次校验：只接受已知 operator/action、规则/字符/操作数预算和安全 style/name；不执行 injected_script 原始源码。页面只保留一个 MutationObserver，单轮有规则数、元素数、操作数和 wall-clock 截止，达到预算停止而不是无限补扫；remove 采用可撤销隐藏，style/attr/class 修改必须能在代次切换或关闭时撤销。
- 静态隐藏至少排除 `html/body/main/nav/header`、滚动根和 `main/navigation/tablist` 语义节点；程序化修改还必须保护包含主要 landmark、位于正常导航内或具有多个正常交互项且没有广告语义的结构。该保护是通用 DOM 合同，不得写站点 host、强制 `position: static` 或删除网站规则来修兼容。
- selector-based Cosmetic 先用 `visibility:hidden + pointer-events:none` 保留几何，再由受预算 DOM 分类器决定：主要语义结构退出 SurfSave selector，sticky/fixed/table/flex/grid/交互组保持几何隐藏，只有独立广告容器升级为可逆 `display:none`。受保护元素通过 marker 加到 selector 的 `:not(...)` 排除条件，禁止用 `visibility:visible!important` / `pointer-events:auto!important` 覆盖网站自身隐藏状态；伪元素选择器保持伪元素位于选择器末尾，不追加宿主 marker。
- document-start 可能在顶层页与多个 iframe 同时执行。Bridge 状态必须按有界 document token 隔离，不能用单个 `currentPage/currentExceptions/pageDynamicCalls` 让子 frame 覆盖顶层页；控制器级总预算仍保留，防止恶意 frame 无限占用。
- scriptlet 只能调用 APK 内置名称白名单中的可信实现，远程任意 JavaScript 必须拒绝。
- 弹窗默认策略为“用户手势正常允许、无手势或命中广告规则阻止、站点可例外”，并保留现有 `target=_blank` 标签语义。
- 不记录完整请求 URL/query、Cookie、Authorization 或订阅凭据；规则更新错误必须可见但脱敏。

### 4. Validation & Error Matrix

- 主文档、非 HTTP(S) -> 放行；document URL 无法解析时只允许 context-free 规则参与，不猜测 third-party。
- direct media、manifest、segment、subtitle 或媒体 Accept 命中明确广告规则 -> 阻止；未命中广告证据 -> 放行并可进入媒体检测。
- 更新下载超时、超限、哈希不符、解析失败、序列化不兼容或 native 初始化失败 -> 保留 bundled/last-known-good，状态显示真实失败；不得静默宣称更新成功。
- Cosmetic API 不支持、导航代次已经变化或预算超限 -> 停止本轮注入并保留网页，不向新页面注入旧结果。
- 资源类型为 script/style/image/subdocument -> 返回匹配 MIME 的空脚本、空样式、透明图或空 HTML；媒体/XHR/font/unknown -> 204；不得统一返回成功的 `text/plain` 让 Chromium 走错误的解析生命周期。
- Cosmetic 命中 sticky/fixed、表格单元、主要导航或多交互结构 -> 保持原几何或退出 SurfSave selector；分类失败按保护方向降级，不能强制显示、改成 static 或立即移除节点。
- 引擎 document-start 时仍 INITIALIZING -> 返回 inactive；状态变为可用后强制撤销旧 state 并重新 bootstrap 当前文档，不能被“同 URL 已注入”短路。
- 扩展 selector 语法畸形、operator/action 未知、style 含 `url()`/`expression()`/脚本值、任一预算超限 -> 拒绝整条结构规则；不能部分执行或退回任意 JavaScript。
- 无基础选择器的 properties 规则 -> 作为不安全近似拒绝；带明确基础选择器的规则 -> 在元素/操作/时间预算和结构保护下执行。
- iframe 在顶层文档之后 bootstrap -> 两份 token 状态独立；任一 frame 的 dynamic lookup 只能读取自己的 URL、例外和调用次数。
- Manager 关闭或用户关闭开关 -> 断开 Observer、清定时器、移除注入 CSS并逆序撤销已记录 DOM 修改；不得销毁或重载 WebView。
- scriptlet 名称不在 APK 白名单、参数超限或包含远程源码 -> 拒绝该 scriptlet，不影响其他网络规则。
- ServiceWorker 请求即使有 Referer/Origin 也不得冒充 Tab；page allow、站点开关和 third-party 规则不参与。

### 5. Good / Base / Bad Cases

- Good: 开启后网络广告和已确认广告媒体被阻止、广告容器由规则 Cosmetic 隐藏，真实播放器/搜索/挑战页/检测入口正常，规则更新不阻塞首屏。
- Good: `Sec-Fetch-Dest: empty` 的 VAST/fetch 请求能命中 `$xmlhttprequest`，`:has-text`/properties 和 Rust action 走同一结构化执行器，动态新增广告受有限 Observer 补处理。
- Good: 被阻止脚本得到可解析空 JavaScript、被阻止图片得到透明图；普通广告块最终折叠，而网站 sticky/tab/table 结构只被隐藏内容且不因广告消失改变跟随滚动行为。
- Base: 某站依赖被过滤资源时，通过 allow/unbreak 或站点开关修复，无需修改浏览器控制流；更新失败继续使用 last-known-good。
- Bad: 用 `url.contains("ad")`、宽泛 `[class*=ad]` 全页隐藏、每请求读取 EasyList、执行远程任意 JS、把所有媒体当广告、静默丢弃 procedural_actions，或在 WebViewClient 写站点 host 分支。

### 6. Tests Required

- 网络：ABP block/exception、first/third-party、资源类型、媒体广告、未知类型、redirect replacement 和 unsupported modifier 均有直接测试。
- ServiceWorker：只命中 context-free snapshot；活动标签、page allow、站点开关和 third-party 不得串入。
- 更新：ETag/未修改、断网、超限、哈希/解析/缓存失败、原子替换和 last-known-good 均有测试。
- 页面：Cosmetic generation、预算、能力降级、可信 scriptlet 白名单、弹窗手势/例外均有测试。
- 页面结构化规则：Rust payload 必须返回对象数组且不含 injected_script；Kotlin 覆盖 `:has-text`、嵌套 has-text、带作用域 properties、无作用域 properties 拒绝、native `:has` 保护性分流、语义 DOM 结构保护、顶层/iframe token 隔离、未知操作/危险 style 整条拒绝、单 Observer/12ms/操作数/总轮次预算和旧代先撤销。
- 类型响应与 selector 分类：逐一断言 script/css/image/subdocument 的 MIME/200 与 media/XHR/font/unknown 的 204；脚本合同断言第一阶段不直接 `display:none`、protected marker 进入 selector 排除而不是强制 visible、伪元素语法保持合法、sticky/table-cell/导航交互结构受保护，以及 rAF/单帧4ms/1000元素/24轮硬预算。
- 偏好与类型：无偏好默认开启、显式 false 保留；`Sec-Fetch-Dest: empty` 大小写兼容映射 XHR，明确媒体 Accept 优先，完全无信号仍 UNKNOWN。
- JNI/构建：Rust 单测、JNI 集成、Android 7、四 ABI `.so` 与五 APK 打包检查；APK 校验必须核对 ELF class、endianness 和 `e_machine`，不能只检查路径与 ELF 魔数；最低 Gradle 门禁为 `testDiagnosticUnitTest assembleDiagnostic lintDiagnostic`。

### 7. Wrong vs Correct

#### Wrong

```kotlin
if (url.contains("ad")) return WebResourceResponse("text/plain", "utf-8", emptyStream)
webView.evaluateJavascript("document.querySelectorAll('[class*=ad]').forEach(e => e.remove())", null)
val css = native.hideSelectors.joinToString("\n") // assumes every entry is browser-native CSS
```

#### Correct

```kotlin
val requestContext = contextFactory.from(view, request)
when (val decision = contentBlockCoordinator.evaluate(requestContext)) {
    is ContentBlockDecision.Block -> return BlockedResourceResponseFactory.create(
        requestContext.resourceType
    )
    ContentBlockDecision.Allow -> Unit
}
val inspection = mediaRequestInspector.inspect(requestContext)
if (inspection.shouldInspectMedia) detectMedia(inspection)

val split = ExtendedCosmeticSelectorParser.partition(resources.hideSelectors)
val safeRules = ProceduralCosmeticSanitizer.sanitize(
    resources.proceduralRules + split.proceduralRules
)
```

## 请求检测单点化

媒体请求判断必须有单一事实源，不能在 `CustomWebViewClient.shouldInterceptRequest`、`ServiceWorkerClient.shouldInterceptRequest` 或其他拦截点里各写一套；Content Blocking 先完成决策，只有 Allow 请求才进入媒体检查。

契约：

- Content Blocking 统一通过 `ContentBlockCoordinator.evaluate(requestContext)`；媒体判断统一通过职责收窄后的 `MediaRequestInspector.inspect(requestContext)`。
- 媒体 inspection 至少包含：规范化 URL、`ContentType`、是否检查流媒体、是否检查普通视频/音频、是否中断资源。
- WebView 和 ServiceWorker 不重复读取媒体设置或调用 `VideoUtils.getContentTypeByUrlPath`；新增请求来源先建立 request context，再依次经过 blocker 和 media inspector。

Wrong:

```kotlin
// 两个拦截点各自判断 m3u8、mp4、audio，后续开关变化会漂移。
val type = VideoUtils.getContentTypeByUrlPath(url)
```

Correct:

```kotlin
if (contentBlockCoordinator.evaluate(context) is ContentBlockDecision.Block) return blockedResponse()
val inspection = mediaRequestInspector.inspect(context)
if (inspection.shouldInspectMedia) detectMedia(inspection)
```

## 旧粗暴广告拦截链路不得恢复

旧 `AdBlockManager`、宽泛 DOM hiding 和站点硬编码链路已经移除；这些实现曾与播放器、搜索栏、挑战页、初始化脚本和检测入口冲突。用户已明确批准新的规则驱动 Cosmetic，但它必须服从上面的引擎、generation、预算、可信 scriptlet 和站点开关合同，不能复活旧式任意 DOM 修改。

契约：

- 不恢复旧 `AdBlockManager`、宽泛元素选择器、任意 DOM 删除或 WebView 核心站点分支。
- Cosmetic 只能来自已验证规则引擎，CSS/scriptlet 有硬预算且能按站点关闭；播放器、全屏、PiP 和检测浮窗属于回归保护对象。
- `shouldInterceptRequest` 与 ServiceWorker 只消费 `ContentBlockCoordinator`，ServiceWorker 遵守无 Tab 上下文边界。
- 规则状态、按站点开关和拦截计数必须显示真实状态，不用未接线 UI 或静默 fallback 冒充完成。

回归样例至少覆盖：

- `rule34video.com`：搜索栏、播放器区域、检测结果都要正常；未命中 hard 广告规则的媒体不能被 soft 规则误伤。
- `reddit.com`：挑战页、嵌入视频、脚本加载和检测入口要可用。

## 诊断和测试入口

可以保留方便开发者测试的能力，但不能暴露给普通用户。

- 诊断入口只能放在 debug/diagnostic 构建、内部菜单或开发者路径。
- 诊断不能顶替设置入口。
- 普通用户动作面板不能出现“诊断报告”作为主功能。
- 为 AI/开发者新增测试辅助时，要写清触发方式和关闭方式。

## 本地化和用户可见文案

当前 App 面向中文用户，用户可见 UI 不能大量出现英文按钮。

- 新增按钮、菜单、空状态、错误提示要进入正式字符串资源。
- 中文文案要短，适合移动端窄屏。
- 当前维护的应用内语言包只有默认英文 `values/` 和完整中文 `values-zh-rCN/`；不要新增或保留只翻译一部分的 `values-*` 目录。
- 新增默认 `values/strings.xml` 字符串时，要同步补齐 `values-zh-rCN/strings.xml`；如果确实不应翻译，默认值标 `translatable="false"`，并确认没有 locale 覆盖项。
- 不要用机器批量翻译来填充半成品 locale。未完整维护的语言包会让 Android 按设备语言选中半翻译 UI，比默认英文回退更差。
- 不允许靠临时硬贴文字解释功能；功能应该通过布局、图标和状态自然可理解。

## 最低验证

浏览器壳相关实现完成后，至少验证：

- 首页可滚动，首页只有搜索和标签页主入口。
- 打开网页后刷新按钮可见，下拉刷新可用，没有双加载条。
- 点击地址栏进入编辑态，浏览态不显示长 URL 中间片段。
- 网页内普通链接在当前标签继续浏览。
- WebView 切换标签、退出再进不无故重载。
- 返回键按 WebView 历史、标签根部、首页退出的顺序工作。
- 标签页数量可见，标签预览不只显示局部截图，当前标签可识别。
- 底部下载和视频入口在浏览网页时可见。
- 检测浮窗是单一按钮，可长按拖动并保存位置。
- 诊断入口不在普通用户动作面板中。
- 默认搜索引擎来自统一设置，中文用户可直接使用。
- `rule34video.com` 和 `reddit.com` 回归站点不被广告规则误伤；当前网络过滤不得注入 DOM/CSS 改变页面布局。

## Scenario: Authenticated media detection and page generations

### 1. Scope / Trigger

- Trigger: 修改 Cookie、重定向、Basic Auth、ServiceWorker、`.txt` HLS 候选、标签检测状态或 VideoRepository 缓存时必须读取本节。

### 2. Signatures

- `MediaRequestInspector.inspect(url, pageUrl): MediaRequestInspection`
- `BrowserRequestInspection.shouldProbeAsM3u8: Boolean`
- `BrowserRequestInspection.shouldBlockStreamRequest: Boolean`
- `RedirectResolver.resolve(request, ...): Result`
- `ServiceWorkerDetectionContext(tabId, pageUrl, generation)`
- `VideoRepository.getVideoInfo(request, ...): VideoInfo?`
- `VideoRepository.getVideoInfoBySuperXDetector(request, ...): VideoInfo?`

### 3. Contracts

- Cookie 只能按当前网络请求的目标 URL 获取；目标没有 Cookie 时不得回退 `pageUrl` Cookie，也不得把主站 Cookie 手工白名单复制给 CDN。
- 每次 redirect 按新 URL 重算 Cookie；跨 origin 清除固定 `Cookie`、`Authorization` 和其他 origin-bound 认证头。
- IP、localhost、单标签内网域和空 host 不进入 public-suffix 查询；空 host 不查询全部 Cookie。
- `.txt` 只表示需要探测的 HLS 候选；真实响应去 BOM/前导空白后必须以 `#EXTM3U` 开头才发布 HLS，确认前不得返回 `emptyResponse` 阻断普通文本。
- ServiceWorker 检测结果必须携带 tab/page/generation；导航、关闭标签或新代次后，旧异步结果和 loading 清理不得写入当前页面。
- Basic Auth `proceed()` 后立即结束回调，不能继续进入父类 cancel。
- Regular detector 的实际 Cookie 在执行阶段动态读取，不能用不含认证快照的缓存复用；SuperX 只在显式 Request headers、flags、proxy 全部进入 key 时缓存。
- 缓存写入和命中都返回 `VideoInfo` 深拷贝，包括 downloadUrls/headers/formats/httpHeaders，页面修改 title 不得污染其他消费者。

### 4. Validation & Error Matrix

- 目标 URL 无 Cookie -> 不发送 Cookie；不得使用页面 URL 兜底。
- redirect 跨 origin -> 清认证头后继续；同 origin 可保留非 Cookie 请求语义。
- `.txt` 响应不是 `#EXTM3U` -> 返回非媒体候选且网页资源继续加载。
- 页面 generation 已变化 -> 丢弃旧结果并只结束旧 loading token。
- Regular 连续相同 URL 但 Cookie/profile 变化 -> 每次访问远端，不命中旧认证缓存。
- SuperX request body 非空或关键 headers/flags/proxy 不同 -> 绕过或生成不同缓存 key。

### 5. Good / Base / Bad Cases

- Good: 登录页跳到跨域 CDN 后只发送 CDN 自己可读取的 Cookie，Authorization 不跨 origin 泄漏。
- Good: 任意站点 `.txt` 内容确认为 `#EXTM3U` 后检测；普通文本不被中断设置破坏。
- Base: 同页面同代次多个 ServiceWorker 请求共享检测上下文，关闭标签后全部旧回调失效。
- Bad: `getCookie(targetUrl) ?: getCookie(pageUrl)`，把页面域 Cookie 发给媒体目标。
- Bad: 候选 URL 以 `.txt` 结尾就立即返回空响应，网页配置/字幕/普通文本因此损坏。

### 6. Tests Required

- Cookie/domain：IP、localhost、空 host、Netscape 子域匹配、临时文件清理。
- Redirect：每跳 Cookie 重算、同/跨 origin header 行为、最终 URL 相对解析。
- Inspector/ServiceWorker：`.txt` 候选内容确认、普通 `.txt` 不阻断、tab/page/generation 隔离。
- Cache：Regular 绕过、SuperX headers/flags/proxy/body 隔离、header 规范化、两轮嵌套 mutation 不污染。
- 手动：登录站点播放后检测、导航/关标签时无旧结果串页、Basic Auth 成功后不弹取消。

### 7. Wrong vs Correct

#### Wrong

```kotlin
val cookie = cookieManager.getCookie(targetUrl) ?: cookieManager.getCookie(pageUrl)
if (inspection.shouldProbeAsM3u8) return emptyResponse()
```

#### Correct

```kotlin
val cookie = cookieManager.getCookie(targetUrl)
if (inspection.shouldBlockStreamRequest) return emptyResponse()
// .txt candidates continue loading until response content confirms #EXTM3U.
```

## Scenario: Popup navigation, back dispatch and tab media lifecycle

### 1. Scope / Trigger

- Trigger: 修改 `WebChromeClient.onCreateWindow`、Activity 返回分发、标签切换/关闭、WebView 媒体生命周期或标签撤回时必须读取本节。
- 目标：新窗口链接进入真实目标页；返回键只消费当前最高层状态；非当前标签不继续播放；误关标签可恢复但不保留后台活 WebView。

### 2. Signatures

- `BrowserBackPolicy.resolveWebTabAction(isDetectedVideosVisible, isAddressEditorOpen, canGoBack): WebTabBackAction`
- `BrowserTabIndexPolicy.selectedIndexAfterClose(currentIndex, closedIndex, remainingTabCount): Int`
- `BrowserTabIndexPolicy.restoredInsertionIndex(originalIndex, currentTabCount): Int`
- `BrowserTabUndoPolicy.DURATION_MS: Int`
- `BrowserThumbnailQuality.isUsable(bitmap): Boolean`
- `WebTabThumbnailCapture.capture(window, webView, onComplete)`
- `WebViewMediaController.pause(webView)` / `resume(webView)`
- `CustomWebChromeClient.onCreateWindow(view, isDialog, isUserGesture, resultMsg): Boolean`

### 3. Contracts

- `onCreateWindow` 不得把 `hitTestResult.extra` 当成新窗口真实目标；图片锚点的 extra 可能是 `<img src>`。必须把临时 WebView 写入 `WebViewTransport`，从该 Popup 的首次真实导航取得 URL，再重定向到父标签。
- Popup 只把首个 `http/https` 导航载入父 WebView；`about:blank` 等待后续真实导航；未知 scheme 结束临时 Popup，不得重复重定向。
- 返回优先级固定为：退出网页全屏视频 → 关闭检测结果层 → 关闭地址编辑态 → `WebView.goBack()` → 关闭当前标签 → 首页二次返回退出 App。
- 标签切换必须由标签管理层显式暂停所有非当前 WebView，并恢复当前 WebView，不能只等待 Fragment `onPause()`。暂停同时覆盖顶层 `video/audio`、可协作 iframe 的 `postMessage` 和 `WebView.onPause()`。
- JavaScript 暂停注入和 `WebView.onPause()` 是两个独立兜底；脚本注入异常时仍必须调用生命周期暂停。
- 关闭标签前保存 WebView state，随后立即暂停并 destroy WebView。撤回期间只保留 URL、标题、缩略图和 saved state；恢复时分配新 tab id、按原位置插入，避免旧 Fragment 或异步检测结果复活。
- 同一时间只保留一个可撤回快照；新关闭动作会先最终清理旧快照。撤回超时后删除旧缩略图和 saved state。
- 标签缩略图在 Android 8.0 及以上优先使用 `PixelCopy` 捕获窗口内 WebView 区域，失败、超时或低版本回退到 `WebView.draw()`；异步捕获必须有超时且回调只完成一次，迟到结果不得写入已导航或已关闭的标签。
- 缩略图保存、加载和标签列表展示共用 `BrowserThumbnailQuality`；近黑、纯白、纯灰或低信息 JPEG 不得覆盖已有有效缩略图，加载到历史无效缓存时只删除该文件并回退到占位图。视频硬件层仍可能无法捕获时，必须保留标题、域名和标签状态信息。
- 关闭标签的撤回 Snackbar 使用固定 `15_000ms`，只延长可见时间，不保留活动 WebView；超时清理和新关闭替换快照的语义不变。
- 多层返回异常优先记录 `copyBackForwardList().size/currentIndex`；没有复现证据时不得自建与 WebView 竞争的影子历史栈。

### 4. Validation & Error Matrix

- 点击 `target=_blank` 的图片锚点 -> 打开 `<a href>` 对应页面，不打开封面 `img src`。
- Popup 先到 `about:blank` 再导航 -> 等待真实 URL，只重定向一次。
- 检测详情层可见时按返回 -> 只关闭详情层，WebView URL/历史索引不变。
- 地址编辑态与历史同时存在 -> 先退出编辑态，下一次返回才使用网页历史。
- 切到另一标签或首页 -> 原标签 `video/audio` 暂停；当前标签 WebView 恢复。
- JavaScript 注入失败 -> 记录错误且仍调用 `WebView.onPause()`。
- 关闭当前根标签 -> 选择前一个有效标签；关闭当前索引之前的标签 -> 当前索引左移一位。
- 撤回关闭 -> 标签回到合法原位置；原位置已越界时夹到末尾，但不能替换 Home tab。
- WebView 硬件视频层导致 `PixelCopy` 失败或返回近黑画面 -> 不覆盖最后有效缩略图，标签列表仍显示标题/域名/占位图。
- 关闭标签后 15 秒内点击撤回 -> 页面状态可恢复；超过 15 秒 -> saved state 和缩略图按清理策略释放。

### 5. Good / Base / Bad Cases

- Good: 点击视频封面打开视频详情页；详情层先退；标签切换无后台声音；误关后能恢复页面状态。
- Base: 跨域 iframe 不响应暂停消息时，顶层媒体和 WebView 生命周期仍暂停；站点 iframe 自身限制需要实机记录。
- Bad: 使用 `hitTestResult.extra` 打开图片、多个 Activity 返回 callback 同时长期 enabled、仅依赖 Fragment 生命周期暂停、为撤回长期保留活 WebView。

### 6. Tests Required

- `BrowserBackPolicyTest`：详情层/地址栏/历史/根标签四级优先级。
- 标签索引策略：关闭当前、关闭当前之前/之后、撤回位置下界和上界。
- `WebViewMediaControllerTest`：注入暂停脚本、调用 `onPause/onResume`、脚本异常时生命周期仍暂停、iframe 消息字符串存在。
- `BrowserThumbnailQualityTest`：全黑、近黑控件、纯白/纯灰拒绝，含真实结构和颜色的画面通过。
- `BrowserThumbnailStoreTest`：无效图不保存，历史无效 JPEG 加载时删除，有效图可保存并加载。
- 手动：图片锚点新窗口、检测详情返回、多层历史返回、三标签切换播放、根标签关闭、Snackbar 撤回和超时清理。

### 7. Wrong vs Correct

#### Wrong

```kotlin
val url = view.hitTestResult?.extra
view.loadUrl(url)
// Switching tabs relies only on Fragment.onPause().
```

#### Correct

```kotlin
transport.webView = popupWebView // observe the popup's real first navigation
WebViewMediaController.pause(inactiveWebView)
val action = BrowserBackPolicy.resolveWebTabAction(
    isDetectedVideosVisible,
    isAddressEditorOpen,
    webView.canGoBack()
)
```

## Scenario: Telegram 公开帖子媒体导入

### 1. Scope / Trigger

- Trigger: 修改 Android 文本分享、首页“粘贴并打开”、Telegram 单帖解析、导入详情自动打开、Telegram 候选播放或下载时必须读取本节。
- 目标是在普通浏览器标签中支持公开单帖和一帖多视频，同时不引入登录协议、频道历史抓取、临时 CDN 持久下载或旁路下载器。

### 2. Signatures

- `TelegramPostUrl.parse(rawUrl: String?): TelegramPostUrl?`
- `TelegramPostResolver.resolve(post: TelegramPostUrl): TelegramPostResolution`
- `TelegramImportSession.beginPage(generation: Long, pageUrl: String)`
- `TelegramImportSession.startResolution(generation: Long, pageUrl: String): ResolutionToken?`
- `TelegramImportSession.publishIfCurrent(token, generation, pageUrl): Boolean`
- `TelegramDownloadPolicy.prepareFormatForQueue(originalUrl, format): VideoFormatEntity`
- `WebTab.navigationPurpose: WebTabNavigationPurpose`，只允许 `NORMAL_BROWSE` / `MEDIA_IMPORT`。

### 3. Contracts

- 只接受 `t.me/{publicChannel}/{numericMessageId}`、`telegram.me/...` 和 `t.me/s/...`；拒绝 `/c/...`、邀请链接、频道根页和非数字消息 ID。
- yt-dlp `--dump-single-json --skip-download --no-warnings` 是主解析；Jsoup 只读取确定单帖 embed 页作为预览/失败兜底，不扫描频道历史。
- 模型显式区分 `PLAYABLE` 与 `POSTER_ONLY`；仅封面项目不得用空 URL 伪造播放或下载候选。
- 一帖多视频保持解析器原始顺序。每条 `VideoInfo.originalUrl` 必须是该条目的 `?single=1` 帖子 URL；当前 CDN URL 和格式请求头只服务当前会话播放。
- 点击下载时保留 formatId/清晰度/编码，使用 `TelegramDownloadPolicy` 清除临时 URL、manifest、音视频直链和请求头，再进入 `DownloadQueueManager.enqueue()`；Worker 按 `originalUrl` 重新解析。
- `MEDIA_IMPORT` 只驻留内存：WebView 标题/图标/页面更新替换同一 `WebTab` 时必须复制，自动打开详情消费后必须清回 `NORMAL_BROWSE`；SharedPreferences 会话恢复、关闭标签快照和撤回不得复活导入目的。
- 解析 token 同时绑定 tab 当前页面 generation 与 canonical URL；导航、关闭标签或销毁 view 后，阻塞解析即使晚返回也不得发布到新页面。

### 4. Validation & Error Matrix

- URL 不受支持 -> 保持普通网页浏览，不启动 Telegram 专用解析。
- yt-dlp 失败但 embed 有公开预览 -> 显示可播放或仅封面兜底结果；fallback 格式选择器使用合法 `best`。
- 两种解析都失败 -> 用户错误经 `UserFacingError` 展示；日志只记录非敏感异常类型，不落盘代理凭据或临时 URL 查询令牌。
- generation 或 canonical URL 已变化 -> 丢弃结果，不弹详情、不更新候选。
- 仅封面 -> 显示帖子摘要和“在 Telegram 中打开”，不显示虚假播放/下载入口。
- Telegram 候选与 WebView 临时候选重复 -> 保留既有 UI id，但下载来源、格式和引擎标记以 Telegram 权威解析结果为准。
- 普通站点下载 -> `TelegramDownloadPolicy` 返回原格式对象，不清除普通站点 URL 或请求头。

### 5. Good / Base / Bad Cases

- Good: 分享公开多视频帖后真实页面正常加载，详情只自动打开一次，候选按帖子顺序显示；选择第 2 项下载时队列只持久化第 2 项稳定帖子 URL 和 formatId。
- Base: yt-dlp 暂不可用但 embed 暴露公开视频地址或封面时，仍显示可理解的有限预览。
- Bad: 把临时 CDN URL 当作 `originalUrl`、把空 URL 塞进候选、对频道根页抓取历史、从 UI 直接启动 yt-dlp Worker、Fragment 重建后重复弹详情。

### 6. Tests Required

- URL：三种公开形式规范化，私有/邀请/根页/非数字 ID 拒绝。
- Mapper/HTML：单视频、多视频顺序、各自 `?single=1`、相对 URL、仅封面和合法 fallback `best`。
- Session：旧 generation 拒绝、canonical URL 隔离、导入只消费一次、普通浏览不自动打开。
- 合并：重复临时候选被权威 Telegram 格式替换，同时保留 UI id。
- 下载边界：Telegram 格式临时地址/请求头清空，formatId 与清晰度保留；普通媒体对象不变。
- 最低构建：`testDiagnosticUnitTest assembleDiagnostic lintDiagnostic`；发布前在能访问 Telegram 的手机网络验证单视频和官方多视频样例。

### 7. Wrong vs Correct

#### Wrong

```kotlin
val queued = detectedVideo.copy(originalUrl = temporaryCdnUrl)
YoutubeDlDownloader.startDownload(context, queued)
```

#### Correct

```kotlin
val queuedFormat = TelegramDownloadPolicy.prepareFormatForQueue(
    videoInfo.originalUrl,
    selectedFormat
)
// The normal UI -> DownloadQueueManager.enqueue() path persists the stable post URL;
// the Worker re-extracts the selected format instead of trusting the expired CDN URL.
```
