# SurfSave UI/UX 重构

## 产品与完整路径
SurfSave 面向希望从网页发现、播放和保存媒体的 Android 用户。浏览器是发现入口，下载是进行中的任务，媒体库是可离线使用的内容。WebView、媒体检测、队列、Room、下载引擎、播放器与迁移逻辑继续沿用；本轮改变呈现、查找和操作入口。

## 现状与设计决策
- 浏览器已有独立标签历史与持久 WebView；保留其返回与生命周期合同，避免换页重载。
- 底部三入口保持稳定并始终显示名称：浏览 / 下载 / 媒体库。标签概览仍属于浏览器，不成为第四个顶层目的地。
- 首页以搜索和用户常用站点为主，压缩重复入口，避免功能卡片堆叠。
- 媒体详情使用内容优先的封面、可读标题和画质选择，动作顺序为查看内容 → 选择格式 → 播放或保存。保留外部播放器、重命名、分享链接、批量解析和 DRM/Telegram 特殊说明。
- 下载页区分媒体与文件，用直接操作与状态筛选降低查找和暂停成本；实际任务操作继续经既有 ViewModel/DownloadQueueManager。
- 媒体库加入本地搜索和类型筛选，封面保持 16:9，来源和文件信息从属于内容。空库与搜索无结果分别处理。
- 播放器采用单行返回/标题/更多，播放与进度控制集中在底部，显隐与 Media3 同步；倍速、比例、音轨/字幕、画中画保留在更多菜单。

## 统一视觉规范
品牌主色海水绿 #006A68，浅背景 #F4F8F7，白色内容面 #FFFFFF，墨色文字 #152D2D，次要文字 #536B69，选中浅青 #D4EFEB。深色使用 #101C1D 背景、#19292A 内容面、#7CD6CB 主色。语义错误/成功/警告独立配色。
字体使用 Android 原生 sans-serif；大标题 sans-serif-medium 28sp，内容标题 18sp，正文 14–16sp，辅助 12sp，数据开启 tabular figures。中文跟随系统字体与字号，无远程字体依赖。
间距 4/8/12/16/24/32dp；屏幕边距20dp，重复内容卡片8dp圆角，控件16dp圆角。核心操作以48dp触控区域为基准。组件依靠色面与细边界分层，避免厚描边、无意义渐变和叠加阴影。
品牌识别来自简洁波形标记与贯穿发现/保存/离线内容的海水绿色；网页和媒体画面是视觉主体。

## 技术与验证
保持 XML / Fragment / Material 3 1.13.0。Context7 查询失败时依据仓库及本地依赖资源核对接口，不升级第三方库。参考 Android 原生三目的地导航、标准返回栈、48dp触控和 Material 状态层。
保留全部现有业务修复。修改前版本逐文件保存在 app/build/ui-redesign/baseline；测试产物放 app/build/ui-redesign。
验证：testDiagnosticUnitTest、assembleDiagnostic、lintDiagnostic。Go/Xray 非本轮目标，构建使用 SKIP_GO_BUILD=true，复用仓库已有 Go 库。打包使用项目现有的独立构建目录选项，避免覆盖被 Windows 占用的旧 APK。

## 观察后的迭代
- 首页移除最初设计的大标题和描述段落，将品牌标记收进工具栏；搜索和常用网站更早进入视野。
- 媒体详情移除外层卡片，减少画质行中重复的分辨率和内部编号；文件大小随当前选中画质变化。
- 默认展示三项画质，选中其他画质后收起仍保留该项；媒体卡重新绑定时保留展开状态。
- 下载入口显示活动任务数，筛选覆盖收尾和停止中的状态；卡片直接暂停、继续、重试，仍通过原队列操作。
- 媒体库支持跨名称和来源的多个关键词、音视频筛选；保留原排序，区分空库和无搜索结果，元数据变化会刷新卡片。
- 空状态图形从120dp缩至64dp，内容区域可滚动，保证小屏和大字体下的操作可达。
- 播放器顶部工具随 Media3 控制器显隐，退出画中画后恢复完整控制器。

## 验证边界
`SurfLayoutRenderTest` 在 Robolectric 的 Android 35 原生图形运行时中，加载生产 XML、Material 控件及 Adapter，连接 Activity 并执行主线程布局回调。测试覆盖中英文、亮暗主题、小屏、150% 字号与横屏，生成首页、媒体详情、下载、媒体库、空状态、浏览器控件及播放器控制层图片。数据为测试夹具，仅存在于测试代码；图片中的空封面不代表真实媒体缩略图加载成功，播放器控制层不代表解码播放成功。

当前机器缺少 Android Emulator 硬件加速驱动。专用无窗口 Android 35 AVD 在软件加速、多个 GPU 后端和关闭外设功能后仍无法进入 Android 系统；没有修改系统虚拟化配置，也未安装到用户真实设备。因此本轮没有完成整机端到端的 WebView 浏览、真实媒体检测/下载、视频解码、系统权限及 PiP 验收。这部分是发布前仍需完成的验收，不以单元测试或布局渲染替代。

构建命令：

```powershell
.\gradlew.bat --console=plain -PSKIP_GO_BUILD=true -PSURFSAVE_APP_BUILD_DIR=build/surfsave-ui :app:testDiagnosticUnitTest :app:assembleDiagnostic :app:lintDiagnostic
```

产物位置：`build/surfsave-ui/outputs/apk/diagnostic/`。原生布局渲染图：`app/build/reports/surf-ui/`。测试与 Lint 报告：`build/surfsave-ui/reports/`。本机另需沿用已有 Gradle loopback JVM agent，未写入项目配置。

## 媒体浮钮修正（用户确认）
网页浏览区域的媒体入口从 180×56dp 文字胶囊改为 48×48dp 圆形按钮，内部图标/加载指示器均为 24dp；右上角显示小数量角标，完整动作与计数保留给读屏。默认物理右下角，按钮距可用区域边缘 8dp。

短按仍调用现有媒体列表入口。达到系统长按时间后以轻微震动和缩放反馈进入拖动，可上下移动并跨向另一侧；松手以 180ms 动画吸附最近的左右边缘，保留高度。超过系统触控容差的未长按手势不会再触发点击，即使手指回到起点。拖动释放、多指、系统取消及页面卸载均不会误开列表；禁用系统动画时遵守系统设置。

仍使用原有位置偏好键：横向值保存为 0/1，旧横向比例映射到最近侧边；纵向保存为可用范围内的高度比例。父布局/旋转/键盘改变可用空间时，只重算屏幕位置，不写偏好；键盘收起后恢复原高度比例。工具栏底部和窗口可见区域共同约束移动范围，浏览器父容器已位于底部导航上方，不重复减去导航高度。

本次仅调整入口布局与容器手势，不改 WebView、检测、下载和播放器业务。回退基线独立保存在 `app/build/media-button/baseline`，避免回退本次小改时覆盖此前 UI 与同事的业务修改。新增文件：`surf_media_count_background.xml` 和 `MovableContainerTest.kt`。验证仍使用上述 Android 原生布局运行时与本地构建，整机验证限制保持不变。

本次验证完成：504 项单元测试全部通过，其中新增 12 项手势/窗口边界回归；6 组原生布局场景通过，覆盖亮暗主题、中英文、小屏、150% 字号、横屏和左右停靠。Diagnostic 构建成功，arm64 APK v2 签名有效，Lint 0 errors / 113 warnings（包含辅助角标 10sp 的 SmallSp 提示和移除旧按钮属性后闲置资源提示，未将其隐藏）。完整日志和截图裁切预览见 `app/build/media-button`；没有真机整机验收。


## 2026-09-12 全屏播放器与标签概览修正

用户已确认并授权实现：播放器单行顶栏、统一底部控制层；标签预览置顶、标题/域名置底、完整画幅、一致间距与后台图片加载。继续沿用 XML / Fragment / Material 3，不改播放来源、检测、下载、WebView 生命周期或返回栈。

播放器使用本机 Media3 1.10.0 的 `controller_layout_id` 与 `exo_play_pause` / `exo_progress` / seek 控件 ID，保持原 Player 的事件与时间轴。Context7 无法连接，接口依据本机依赖资源和类签名确认。64dp 播放按钮、48dp 快退/快进与进度触控区，单行标题17sp。播放中3秒隐藏，暂停可见；PlayerChrome 统一缓冲指示，READY 暂停不显示加载圈；补系统栏和刘海安全区。额外设置只保留一个更多入口。

标签概览使用铺满浏览器宽度的 Drawer 组件，顶部仅返回、标签数量、新增、最近关闭；移除冗长说明。卡片12dp圆角，屏幕边距16dp，列间/纵向12dp，关闭触控48dp。选中细边框与浅色底，并向辅助功能暴露 selected 状态。窄屏两列，宽屏三列，同一实例随窗口尺寸调整；固定列瀑布布局接受混合画幅，避免同排短图下产生大空洞。不裁切、不按方向硬套比例，旧缓存首次解码后恢复真实比例，新缓存从文件名尺寸元数据直接确定比例。

缩略图链路：打开概览前只取一次 WebView 本身画面（Canvas 必须在主线程，分辨率限制720×1280）；不在抽屉盖住窗口后 PixelCopy。JPEG编码/写入在IO；临时文件完整写好后改名发布不可变路径，旧图不会覆盖新图，读取不会碰到半JPEG。提交校验 tabId / URL / 位图身份，关闭或导航后的结果删除。保存成功释放标签对象中的大位图，列表交由现有 Glide 处理解码、缓存与取消；保存与加载之间保留当前可见图，选择变更使用 payload，不重载图片。关闭撤销转移已有文件所有权，无需重新解码或压缩。文件数保留160余量，容纳100活跃标签、最近关闭和正在替换的图片。

验证使用 Android 35 原生图形运行时；实际加载生产 XML/Adapter/Media3 控制器，SimpleBasePlayer 是状态夹具，不是视频解码。覆盖亮暗主题、中英文、小屏、150%字号、横屏、播放/暂停/自动隐藏/缓冲、完整画幅、12dp间距及100标签滚动与回收。后台持久化单测覆盖旧/新截图逆序完成、关闭后不复活、导航后拒绝旧图。没有设备端FPS或视频解码验收，网页硬件视频帧仍可能不进入 WebView Canvas 截图，保留标题和域名辅助识别。

独立回退基线：`app/build/fullscreen-tabs/baseline`。恢复其中本轮对应文件即可保留之前同事及 UI 修改，不使用 Git reset。新增文件可单独撤回：
- `ui/component/widget/TabsOverviewDrawerLayout.kt`、`ui/component/adapter/TabGridSpacingDecoration.kt`
- `util/BrowserThumbnailPersistence.kt`、`ui/main/player/PlayerChrome.kt`
- `res/layout/surf_player_controller.xml`
- `res/drawable/surf_player_bottom_scrim.xml`、`surf_player_play_background.xml`、`surf_player_play.xml`、`surf_player_pause.xml`、`surf_player_rewind.xml`、`surf_player_forward.xml`、`surf_tab_close_background.xml`
- 测试 `ui/component/PreviewPlayer.kt`、`util/BrowserThumbnailPersistenceTest.kt`

最终门禁结果以 `app/build/fullscreen-tabs/verification-summary.json` 为准；本文件不把旧版本测试结果当作本轮验证。
