# AVBox 手机版改造 Spec(活规范)

> 项目:AVBox(TVBox OSC fork;仓库根目录 = 本文件所在目录的上一级)
> 配套:**先读 `SKILL.md`**(通用开发规范 + 全库文档索引),再读本文件。
> **本文件只保留「当前仍然生效」的规范与约束**;已完成的实施过程、历史补丁、排查记录全部在 `history/`(检索方式见 §8),
> **不要通读 `history/`**。`app/build.gradle.kts` 注释里引用的「§2」= 本文 §2 技术基线。

## 0. 工作规则(每个会话必读)

1. **读什么**:`SKILL.md`(通用规则 + 文档地图)→ 本文件 §1–§7。**不要通读 `history/`**,只在需要追溯"某功能当初怎么做的 / 为什么这么定 / 踩过什么坑"时按 §8 索引检索。
2. **谁说了算**:本文件是 UI 与架构的**唯一事实来源**;未记录的决策不要自行发明,遇到未覆盖的分叉点先向用户提问,把结论写回本文件再继续。
3. **改哪里**:新增/变更 UI 行为 → 先改 §4 对应小节再动代码;技术性约束与踩坑结论 → 写 §6。
4. **做完之后**:更新对应 §4 小节 + 在 `history/` 追加一条实施记录(改造步骤类进 `steps.md`,功能迭代类进 `features.md`);**不要再把过程叙述堆回本文件**。
5. 每完成一个页面/组件,同步删除对应的旧 View 实现,不留双体系死代码。

## 1. 项目背景与目标

- 源项目:AVBox(TVBox OSC fork)。纯 Java,AGP 9.3.2,compileSdk 37,minSdk 24,targetSdk 37,模块:app / player(dkplayer+media3)/ quickjs / pyramid。
- **定位**:只面向手机用户 → TV/遥控器适配代码全部删除(删除对账见 `history/steps.md`)。
- **UI 决策**:Jetpack Compose + Material 3 全量重写展示层;数据层与播放内核不动。

## 2. 技术基线(已定,不得擅改)

| 项 | 值 |
|---|---|
| kotlin | 2.4.10(用户指定) |
| ksp | 2.3.11(用户指定;现有 Room/Glide 是 Java 注解处理器,继续走 annotationProcessor,KSP 仅接入备用) |
| composeBom | 2026.08.00(用户指定) |
| material3 | 1.5.0-alpha23(用户指定,显式覆盖 BOM) |
| compose 编译器 | `org.jetbrains.kotlin.plugin.compose`,版本随 Kotlin,不单独定版 |
| Kotlin 接入方式 | **Step 0 已验证:回退传统 KGP 插件**。AGP 9.3.2 内置 Kotlin 固定绑定 KGP 2.2.10(见 AGP POM runtime 依赖),不可覆盖,无法满足 kotlin 2.4.10;gradle.properties 已设 `android.builtInKotlin=false` + `android.newDsl=false`(KGP 依赖旧 BaseExtension,二者均计划随 AGP 10 移除,届时跟进官方接法)。KSP 2.3.11 已在 :app 挂载验证通过 |
| 补充依赖 | activity-compose、lifecycle-runtime-compose、lifecycle-viewmodel-compose、coil-compose(图片,复用 OkHttp 配置)、material-icons-extended、core-splashscreen(可选) |
| 明确不用 | navigation-compose(tab 走 HorizontalPager、页面走 Activity 跳转)、AutoSize+mm dimens(退役)、TvRecyclerView(删除)、Glide-compose |
| 数据层(不动) | Room / Hawk / EventBus / OkGo / OkHttp / 爬虫源体系(Java,Kotlin UI 直接调用) |
| 播放器 | dkplayer 控制器保留 View 实现,Compose 里 `AndroidView` 包壳;手势层已具备:单击显隐/双击暂停/左半屏亮度/右半屏音量/横滑进度 |

**Step 0 接入验证结论(2026-09-07)**:
- 版本目录已写入 §2 全部版本并核对真实存在(composeBom 2026.08.00 / material3 1.5.0-alpha23 / kotlin 2.4.10 / ksp 2.3.11 / activity-compose 1.13.0 / lifecycle 2.11.0 / splashscreen 1.2.0 / coil 3.6.2);material-icons-extended 由 BOM 管理(1.7.8,官方最终版)。
- coil-network-okhttp 把 okhttp 由 3.12.11 抬升至 4.12.0(3→4 二进制兼容,行为不变),两处编译期适配:`OkGoHelper` UA 改用 `OkHttp.VERSION`;vendored 的 `app/src/main/java/okhttp3/dnsoverhttps/DnsOverHttps.java` 移植 3 处内部 API(Util.addSuppressedIfPossible → addSuppressed、Platform.log 参数序、PublicSuffixDatabase.Companion.get)。
- `Icons.AutoMirrored.Filled.ChevronRight` 在 icons 1.7.8 中不存在,设置行 chevron 使用非镜像 `Icons.Filled.ChevronRight`(App 中文,单方向)。
- material3 1.5.0-alpha23 的 `rememberModalBottomSheetState` 已弃用,BottomSheet 封装内改用 `rememberBottomSheetState`,以 `enabledValues={Hidden,Expanded}` 复现"跳过半展开"。
- 非 Android 12+ 的品牌色板仍待定(§7),主题暂用 Material 基线色板占位。
- **脱糖(2026-09-12 补)**:四个模块(app / player / quickjs / pyramid)统一开启 `isCoreLibraryDesugaringEnabled` + `desugar_jdk_libs_nio:2.1.5`(与 fongmi 的 catvod/app 做法一致)。作用范围 = **编译进 APK 的代码**(自身 + 库依赖;实测 APK 内出现 1200 处 `j$/*` 引用,说明 compose/media3/okhttp 等库确实在用 `java.time`/`java.nio`)。代价 +912KB。⚠️ **不覆盖动态加载的爬虫 jar** —— jar 是预编译 dex,不经过 D8,其 `java.time.*` 引用原样保留,API 24/25 上仍会 `NoClassDefFoundError`(详见 §6.3)。

## 3. 信息架构与主题(已定)

- **MainActivity**:Scaffold + NavigationBar + HorizontalPager,4 个 tab:首页 / 历史 / 收藏 / 设置;支持手势横滑切换;每页滚动状态独立保留。
- **独立 Activity**:Detail(详情+播放)、LivePlay(直播)、Search(搜索)、ThemeSettings(主题设置)、ConfigManage(配置管理);原 LocalFile(本地文件)已于 2026-09-11 删除(改为系统 SAF,见 §4.7)。
- **删除页面**:PushActivity(推送整链,删除对账见 `history/steps.md`)。
- **主题**:默认跟随系统深浅色;Android 12+ 叠加 Material You 动态取色(`dynamicColorScheme`),低于 12 用自定义品牌色板。**2026-09-11 起可在「设置 → 主题设置」页改**:取色来源(系统取色 / 自定义种子色)、深浅模式(跟随系统 / 浅色 / 深色)、预设色卡与自定义种子色(HSV 取色器)、配色风格(MaterialKolor `PaletteStyle` 9 种);配置走 Hawk + 全局可观察单例 `AppThemeState`,改动即时全局生效(页面规范见 §4.8)。
- **色彩角色**:页面背景 `surfaceContainer`;卡片容器**不论深浅一律 `surfaceBright`**(2026-09-09 用户定稿,废弃原"深色 surfaceBright/浅色 surfaceContainerHigh"分支);底部导航栏 `surfaceContainerHigh`、高度 56dp(2026-09-09 用户定稿,原 surfaceContainer/M3 默认 80dp),图标 = `.tubiao/*.svg` 转换的 VectorDrawable(`ic_tab_home/history/collect/settings.xml`,单套图标,选中态 primary 由 NavigationBarItem 自动着色)。页面背景已审计(2026-09-09):全项目唯一 Scaffold(MainScreen) 显式 containerColor=surfaceContainer,无默认 background/Surface 覆盖;Scaffold 默认 background(#FEF7FF/#141218)未在任何页面生效;ModalBottomSheet 未显式指定色,走 M3 默认 surfaceContainerLow。
- **返回行为**:MainActivity 双击返回退出(带提示);LocalFileActivity 的"返回上级目录"改写为 OnBackPressedDispatcher 保留。
- **横竖屏**:仅播放器全屏时横屏沉浸(隐藏系统栏,configChanges 防播放器重建);其余页面竖屏。

## 4. 页面规范

### 4.1 首页 tab(定稿)

- **顶部区**(随内容滚动,不固定):左侧**订阅源胶囊**(圆角 20dp `cardContainer`,站点头像 / 接口 logo 兜底 + 源名 + ArrowDropDown;点击弹「订阅源」bottom sheet = 设置页卡位风格源列表 + 末组「配置接口」入口,2026-09-10 由源 chips 行收敛而来)+ 右侧搜索图标圆钮(40dp 正圆 `surfaceBright` → SearchActivity);**直播入口 = 右下角图标 FAB**(`ic_live_fab.xml`,源 `.tubiao/直播fab.svg`,2026-09-12 由 `Icons.Filled.LiveTv` 换成项目图标;原行首 chips 入口废止);切源后内容流整体刷新。
- **内容流(2026-09-09 定稿,2026-09-10 补 Hero)**:LazyColumn 分区列表;首项 = **Hero 大卡轮播**(推荐前 5 部,Loading 态即用骨架占位首项防滚动锚点漂移)+ 推荐分区(Hero 已展示的前 5 部去重);其余分区 = 当前源的**全部分类**,每区 = 大号粗体标题行 + LazyRow 卡片行;标题行右侧**「全部 >」入口**(bodyMedium onSurfaceVariant + KeyboardArrowRight 18dp 胶囊,原 Tune 筛选控件已删)→ `PartitionListActivity` 二级页(3 列海报网格,全量分页 + Tune 筛选 sheet,FilterSheet 已移至 ui/components 复用);搜索结果源分区右侧同样「全部 >」(携带结果 JSON 进同一二级页,无筛选)。首页加载看门狗 45s 超时转「加载失败 + 重试」(2026-09-11)。
- **分类隐藏**:设置 tab"首页分类显示"勾选管理,按 源+分类名 存 Hawk;隐藏的不加载不渲染。
- **卡片(最终版)**:2:3 海报、圆角 16dp、底部黑色渐变 scrim;白色粗体标题(16sp,titleLarge 就地覆盖;2026-09-09 由 18sp 调整,用户定稿)+ 名称下方年份行(≈14sp,白 70%,year>0 才显示;2026-09-09 由「年/地区/类型」拼接改为仅年份,vodMeta 删除);**无评分角标**(用户已否决);长按→收藏/操作菜单。该组件三处共用:首页内容流 / 详情页相关推荐 / 搜索结果卡。
- **卡片点击分发(2026-09-11 用户定稿,对齐上游 fongmi `TypeFragment.onItemClick`)**:统一入口 `ui/page/VodCardAction.kt` 的 `Context.dispatchVodCardClick(video, onAction)`(调用方 = 首页 Hero/推荐/分区、`PartitionListActivity` 的 partition/folder 模式),判定**优先级**:
  1. `video.action` 非空 → 执行 action(`SourceViewModel.action`;结果 msg 经 `actionMessages` Toast + 列表刷新。云盘配置卡「登入自己/清除优汐/清除夸父/自定网盘/网盘线路/手动推送」等走这条);
  2. `video.tag == "folder"` → **网盘目录下钻**:`PartitionListActivity.startForFolder(folderId, name)` 以目录 id 当分类 id 走同一条 `SourceViewModel.getList` 链(**可逐级递归**,返回键回上一级);
  3. **源级策略** `SourceCardPolicy`(`SEARCH` 默认 / `DETAIL`):`DETAIL` → 直接进详情页播放;`SEARCH` → 带标题跳搜索页(兼容 2026-09-10 定稿);
  4. 搜索模式(`MODE_SEARCH`)与搜索结果页(`SearchActivity`)卡片保持「进详情页」不变。
- **源级策略的由来与切换**:音乐 / 影视 / 网盘在卡片数据里**没有区分字段**(上游 fongmi 同样没有,只认 action / folder / 站点 `indexs`),因此「点卡片先搜索还是直接播放」只能按源定。存储 = Hawk `source_card_policy`(`HashMap<sourceKey,"detail">`,缺省即搜索,只登记 DETAIL 的源);UI = 「订阅源」sheet 每行右侧的**「搜索 / 详情」标记**(`CardPolicyPill`,点击切换且不改变当前选中源,`DETAIL` 态高亮 primary)。用户当前配置 = 5 个影视源保持搜索,「易听音乐 | 带歌词」「我的云盘 | 我配置」切详情。
- **性能**:切源后各分区第一页并发加载**限流 2~3**;LazyColumn 分区 key=分类 id,LazyRow 卡片 key=vodId;Coil 行内预加载;分区三态 = 横排灰卡骨架 shimmer / 空态 / 错误+分区级重试。
- **下拉刷新(2026-09-12 用户要求:「下拉出现圆形加载指示器,松手刷新,48dp」)**:内容流最外层 `LazyColumn` 挂 `Modifier.pullToRefresh`(material3 1.5.0-alpha23 官方下拉刷新,阈值 80dp M3 默认);**松手触发 `HomeViewModel.reload()`**(清运行期缓存 `SourceViewModel.clearRuntimeCache()` + 整页重载,避免 sortCache 命中导致「刷新后推荐没变」)。**指示器 = 引导页同款 M3 expressive `ContainedLoadingIndicator` 48dp**(2026-09-12 二轮用户定稿,与 §5「加载指示器」一致,实现过程见 `history/features.md`):下拉过程按 `distanceFraction` 形变,松手后转不定态圈。⚠️ 顶栏是透明覆盖层且画在内容之上,指示器整体下移「顶栏总高」(`offset(y = topPadding)`)从顶栏下沿滑出,否则会被左上角订阅源胶囊完全盖住;指示器无手势 modifiers,不拦截列表触摸。刷新完成判定 = 推荐与全部分区都不再 Loading(看门狗 45s 超时转 Error 同样解锁,防指示器永久转圈);未配置接口的引导态不启用下拉刷新。

### 4.2 历史 / 收藏 tab(Step 2 实施时已与用户确认)

- **视觉**:标题行 = 左上角「历史」/「收藏」大标题(`headlineSmall` 24sp/700;2026-09-09 由 titleLarge 18sp 改,用户定稿)+ 右侧「管理」;下方:历史 = 单列卡片列表(2026-09-09 用户定稿:每条观看记录用 **28dp 圆角卡片容器**包裹(`cardContainer` 底色,距屏幕边 16dp,卡片间距 12dp);卡内左 2:3 海报缩略图,右侧文字**三段垂直分布**(2026-09-09 用户定稿:名称上/集数「上次看到第X集」中/影视源下,列与海报等高 SpaceBetween;源名取 ApiConfig.getSource(sourceKey).name,无则回退 sourceKey;原观看时间不再显示));收藏 = 双列 2:3 海报网格(与首页卡片同风格)。点击进详情。
- **删除交互**:多选管理模式——顶部「管理」进入多选,长按条目亦可直接进入并选中该条;批量删除(收藏=取消收藏,历史=删除记录),历史另含「清空全部」;退出模式用「完成」。
- **刷新**:沿用 RoomDataManger(Room)+ EventBus TYPE_HISTORY_REFRESH。

### 4.3 设置 tab(定稿)

- **页面大标题**(2026-09-09 用户要求):左上角「设置」,`headlineSmall` 24sp/700,与历史/收藏页标题同角色;标题下方接分组卡片列表(间距 12dp)。
- **分组卡片组件 `SettingsGroup`**(形状按卡位自动):

| 卡位 | 圆角 topStart/topEnd/bottomEnd/bottomStart |
|---|---|
| 首卡 | 28/28/4/4 dp |
| 中间卡 | 4/4/4/4 dp |
| 末卡 | 4/4/28/28 dp |
| 单卡组 | 28/28/28/28 dp |

卡间距 2dp(2026-09-09 由 4dp 改,用户定稿);**设置页组间间距 28dp、组标题(13sp 小字)已移除**(2026-09-09 用户定稿,仅设设置页;播放器设置等 bottom sheet 的组标题保留);组标题 13sp `onSurfaceVariant` 仍为组件能力;ripple 按卡圆角裁剪;组内无 divider。该组件同时用于播放器设置/弹幕设置/筛选/投屏等 bottom sheet 的选项列表(全 App 统一"选项列表"视觉)。
- **行内规格**:行高 ≥56dp;左标题 16sp;右侧当前值 14sp `onSurfaceVariant` + chevron;开关用 Switch;单选点开 bottom sheet 单选。
- **分组内容**:播放器(内核/渲染/缩放/硬解/IJK 缓存/隧道模式/AAC 优先(2026-09-11,均默认关))、播放体验(自动换线/M3U8 净化/弹幕开关/弹幕 API)、首页与搜索(推荐来源/默认启动页/首页分类显示/历史条数/历史合并)、配置与数据(**配置管理**(2026-09-11,源添加/管理唯一入口)/接口线路/DOH)、关于(关于,bottom sheet 含版本号)。
- **已删条目**:换壁纸、重置壁纸、推送相关、**XWalkView 解析选项**(连带卸载 xwalk 依赖)、**进度预览/推荐样式/搜索视图**(死设置,重写后无消费,2026-09-09 删)、**检查更新**(假按钮无检查逻辑,删)、**开发者选项**(DEBUG_OPEN 及 OkGoHelper/FileUtils/PlayContainer 调试分支全清,删)、**配置备份**(BackupManager 孤儿,删)、**本地文件**(2026-09-11:入口先收敛到配置管理页,后整页删除改系统 SAF,见 §4.7)、**接口配置 / 接口历史**(2026-09-11 删除,源添加/管理统一到「配置管理」页,见 §4.7)。
- **弹幕开关默认值**:与 DanmuHelper.isOpen() 对齐为 true(2026-09-09 修,首装显示与实际一致)。
- **画面渲染默认值**:SurfaceView(PLAY_RENDER 默认 1;2026-09-09 由 TextureView 改,新装生效)。

### 4.4 详情 / 播放页(2026-09-07 实施,2026-09-11/12 补丁定稿)

- 竖屏布局:顶部 16:9 播放器(内嵌播放,点全屏进横屏沉浸)→ 标题/年份/评分 → 简介可展开 → 选集横向行(表头右侧「倒序/正序」+「全部」两个 `PillAction`,「全部」→ 分季+网格 bottom sheet,当前集高亮)→ 换源行 → 相关推荐。
- **选集行不要重复「全部」入口(2026-09-12)**:表头已有「全部」药丸钮,chips 行末尾**不再挂**第二颗「全部」chip —— 同一动作两个入口既冗余又挤占横向空间(用户截图反馈后删除 `EpisodeRow` 里 `item(key = "all")` 那段)。
- **状态栏区 = 纯黑**(2026-09-07 用户改选),播放器紧贴其下;状态栏图标强制白色且需**反复断言**(系统会按主题重设,见 §6.6)。
- **chips 分区行**(「清晰度」/「线路」)= `surfaceBright` 圆角卡片(圆角 16dp、距屏 6dp、卡内 vertical 12dp、标题 start 16 / bottom 8、chips 行 contentPadding 16),**宽度必须与「选集」卡对齐**(`ChipRow` 的 `Column` 与 `LazyRow` 各加 `fillMaxWidth()`,否则仅两条线路时卡片明显变窄)。
- **选集网格**(`全部` sheet):自适应多列网格 + 稳定定位当前集;格子 label 13sp + `contentPadding` 水平 6dp + `TextOverflow.Ellipsis`;**不要用 `basicMarquee`** —— 仅差几 dp 的溢出会表现成"文字乱滚/错位"。
- **换源行 / 播放容器 / 帧率的硬约束见 §6.1**(点击即停 + 失败回滚原源 + 进度继承 + Exo 帧率匹配必须保持关闭)。
- 播放手势:已有手势保留;**新增长按 2 倍速**(移动端惯例)、亮度/音量/进度手势指示器、全屏拖动进度预览。
- DLNA 投屏保留:播放页右上按钮 → 设备列表 bottom sheet。**同一 sheet 内也扫描局域网 TVBox 设备**(`RemoteTVBox.searchAvalible`),选中即 `post("http://<host>/action")` 推送;**扫描到 / 投屏成功时都要记住 host**(`RemoteTVBox.setAvalible` → Hawk `REMOTE_TVBOX`),因为 `PlayerHelper` 的 **13 号「RemoteTVBox 播放器」**(把 TVBox 当外部播放器用,`RemoteTVBox.run`)与它的可用性判定都依赖这个值 —— 2026-09-13 修复:Compose 迁移时漏掉了这次写入,导致该播放器恒不可用;同时 `PlayerHelper.invalidatePlayersExistInfo()` 必须跟着调用(该可用性表是**进程级缓存**,不重置则本次进程内不会重新计算)。
- 弹幕开关/字幕/倍速/音轨 → 播放器设置统一 bottom sheet。

### 4.5 直播页(2026-09-08 实施定稿)

- **页面结构**(竖屏):顶部 16:9 播放器(纯黑、statusBarsPadding 下方,MyVideoView + ComposeLiveController 经 AndroidView 包壳)→ 频道信息区(频道号徽标 + 名称 + 直播中/回看中徽标 + 线路 x/y + 当前/下个节目时段与标题,**常驻不再自动隐藏**,旧 showBottomEpg/setDefaultBottomEpg 逻辑等价移植)→ 频道分组折叠列表(全部分组可折叠展开;进入时定位上次频道并展开其分组、滚动到当前频道;当前频道高亮;锁定分组显锁图标,展开走旧 LivePasswordDialog(保留 View 对话框))。
- **播放器交互**(手势灵敏度照抄旧 LiveController/BaseController):单击 = 全屏切换(竖屏点画面进横屏沉浸;全屏中单击显隐浮层,6s 自动隐藏);长按 = 回看呼时移条 / 直播呼设置;**左右快滑 = 切上一/下一频道**(§4.5 定稿,取代旧的快滑换源——换源改走设置 sheet 的线路选择);上下滑 = 左半屏亮度 / 右半屏音量(带亮度/音量百分比指示,旧 msg 100 无展示位补齐);双击无动作(旧 setDoubleTapTogglePlayEnabled(false))。
- **设置**:播放器角上「节目单 + 设置」双圆钮(竖屏常驻、全屏随浮层)→ 直播设置 bottom sheet(SettingsGroup 单卡视觉);旧 7 组全保留:线路选择/画面比例/播放解码/超时换源/偏好设置(Switch 行)/多源切换/配置切换;点击行为与旧 clickSettingItem 等价(含无频道时隐藏 0-2 组、同项幂等)。**配置切换组(第 6 组)第 0 项 = 合成的「跟随点播源」**(2026-09-12,`ApiConfig.LIVE_FOLLOW_ITEM_NAME`),其后为直播配置历史;历史第 i 项的 `itemIndex = i + 1`(选中判定 / 点击 / 长按删除三处都要按该偏移换算,跟随项不参与删除)。
- **EPG 节目单 bottom sheet**:标题 = 节目单 · 频道名;旧行为仅今天一条日期(mEpgDateGridView 早已 GONE),等价简化为单日;节目行 = 时段 + 标题,当前节目「正在播出」、回看选中「回看中」高亮;过去节目可点回看(需 catchup 支持,clickable 判定同旧),点击正在播出 = 回直播。
- **时移条**:回看中点画面呼出(与全屏浮层共用显隐),播放/暂停 + Slider seek + 当前/总时长(**1s 轮询**,由 `mUpdateTimeshiftRun` 每秒刷新 `tsPosition`:进回看 `startTimeshiftTicker()` 先 remove 再 post、退出回看/切台/销毁 `stopTimeshiftTicker()`,Runnable 内 `if (!isSHIYI) return` 兜底),6s 自动隐藏;等价旧 backcontroller+countDownTimer3。⚠️ 2026-09-13 修复:该 Runnable 此前**从未被 post**,回看时滑块与「位置/时长」文本只有拖动才更新(与本节描述的"1s 轮询"不符)。时移秒长 shiyi_time_c、buildCatchupUrl 全套 catchup 逻辑 1:1 保留。
- **切台快照**:换台时旧帧截图(doScreenShot)+spinner 全屏遮罩,STATE_PREPARED/BUFFERED/PLAYING/ERROR 时移除;清晰度角标:换台后 300ms 轮询 videoSize 重试 10 次,成功显示 3s 自动隐藏(旧常驻,补了隐藏)。
- **全屏**:同详情页 applyFullscreen(SENSOR_LANDSCAPE + 基类沉浸,返回先收浮层再退竖屏),configChanges 防重建;数字选台与全部 DPAD/MENU/INFO 键逻辑随 TV 代码删除(Step 1 已拆,本次不再有承载 UI)。

### 4.6 搜索页(原则已定)

顶部 TextField + 系统输入法(删自绘键盘 SearchKeyboard);搜索历史 chips;各源结果分区;快搜功能已删除(2026-09-09,删除清单见 `history/features.md`),同名/换源需求由详情页换源行承担。**求解中进度 = 波浪线不定长 `LinearWavyProgressIndicator`**(2026-09-11 用户要求,替代 LinearProgressIndicator;左右 16dp 与搜索框对齐、上下各 12dp 留白,不贴搜索控件与下方卡片)。**结果源筛选**(2026-09-11 用户要求):进度条下方**横向滑动 FilterChip 行**(「全部」+ 所有有结果的源,单选;再点已选中的源 = 取消筛选回「全部」;仅 1 个源时整行不显示),仅过滤下方分区显示,不触发重搜;发起新搜索(含历史 chip/热搜/外部带标题进入)时筛选重置为「全部」。**空态文案居中**(2026-09-11 用户要求):「搜索历史」卡片的「暂无搜索历史」与「热搜榜」卡片的「暂无热搜数据」由靠左改居中(`Modifier.fillMaxWidth()` + `textAlign = TextAlign.Center`)。

### 4.7 配置管理页(2026-09-11 定稿,五轮迭代;2026-09-12 起点播/直播分段;过程见 `history/features.md`)

- **定位**:全 App **唯一的源添加/管理入口**(设置 tab 原「接口配置」「接口历史」已删)。
- **入口**:设置 tab「配置与数据」→「配置管理」→ `ConfigManageActivity`(portrait);首页引导态按钮、订阅源 sheet 末组行同样跳此页。
- **顶栏**:无边框 + 大标题「配置管理」,**常驻不折叠**(`AppTopBarScaffold(collapseEnabled = false)`,与首页/搜索页同款 pinned —— 分段行常驻要求 `topPad` 恒定);右上控件两种状态互斥 —— 常态 =「添加订阅 / 添加直播源」单个 40dp 圆钮,**管理模式 =「编辑」+「删除」两个 40dp 圆钮**(顺序:编辑在删除**左侧**,2026-09-12;`Row` + 8dp 间距;编辑仅当**勾选恰好 1 项**时可用,`ManageActionIcon(enabled=)` 降透明度)。管理模式由长按卡片进入,故编辑控件"随长按出现"。
- **点播 / 直播分段(2026-09-12)**:内容区顶部**常驻**分段行(`CapsuleSegmentedButton` + `style = SegmentStyle.Track`,两段等宽,距屏 16dp、与首卡 12dp),每段两行 = 标题 + `badge` 当前源名(不切分段也能看到两个角色各自的选择);点播段空态 = `LoadStateBox`「暂无订阅」,直播段**永不为空**(首项固定为合成的「跟随点播源」卡)。
- **列表**:订阅源卡片 = **28dp 圆角**(`cardContainer`,距屏 16dp、卡间距 12dp);卡内左侧 = **40dp 圆形源图标**(`SettingsIconBadge` + `ic_subscribe_source.xml`,源 `.tubiao/配置管理的订阅源卡片icon图标.svg`;置于文字 Column **之外**,使名称/链接同基线且整块与图标垂直居中、卡高不变),右侧 = 名字 titleMedium + 链接 bodyMedium `onSurfaceVariant` 单行省略;两个角色各自独立列表。
- **开关 = 当前角色的在用源**:点播段比对 Hawk `API_URL`,直播段比对 `LIVE_API_URL`(跟随态下无选中卡)。打开 = 切换(只写本角色的地址 + 本角色历史;点播切换额外:非线路历史清线路、`AppBootstrap.retry()`、`invalidateLiveConfig()`;直播切换不改点播、不 retry);**点播关闭不动作**(必须有一个点播源),**直播关闭 = 回到「跟随点播源」**(必须有一个直播来源);整卡点击 = 同开关;**列表首个订阅源添加后自动启用**。切分段会重置管理模式与勾选。
- **点播 / 直播解耦规则(核心不变量)**:未单独配置直播源时直播**跟随当前点播源**(`LIVE_API_URL` 空 = 跟随;旧版双写留下的 `LIVE_API_URL == API_URL` 等价视为跟随,无需迁移);一旦添加独立直播源则**优先级更高**,此后切点播源**不再覆盖**它,切直播源也**不触碰**点播配置。删空点播列表走 `ApiConfig.clearVodConfig()`(独立直播源保留),删空直播列表 = 自动回跟随(点播不受影响)。
- **切到"坏源"不留旧数据(2026-09-13 修复)**:点播地址变更必须走 `AppBootstrap.onApiUrlChanged()` = ①`ApiConfig.invalidateVodConfig()` 作废旧内存配置 → ②广播 `TYPE_API_URL_CHANGE` 让首页立刻按新状态刷新 → ③`retry()` 重载。⚠️ **缺了①就会有状态不一致**:`loadConfig` 失败走 `callback.error(...)`,**不会调用 `parseJson`**,而清场 `resetConfigData()` 只在 parseJson 开头跑,于是单例里的 `sourceBeanList`/`mHomeSource` 原样留着**上一个源**的数据 —— 表现为"设置里明明启用了新源、首页照旧显示旧源内容且能正常播放,重启后才发现新源不可用"。同源再启用(地址未变)不走这条链,只 `invalidateLiveConfig()`。`switchApiCollectionIfNeeded()` 内部换线路时同样先作废。同类先例见 2026-09-11「删空订阅列表仍用着被删的源」。
- **排序**:正在使用的源**恒置顶**(渲染层 `orderedItems` 排序,**存储顺序不变**),其余按添加顺序 → 新添加的源追加到**末尾**。
- **长按删除 / 编辑**:长按卡片进入管理模式并选中该卡(右侧 `Switch` 转 `Checkbox`);管理模式整卡点击 = 切换选中;取消全部选中 / 删除完成自动退出。**返回行为(2026-09-12)**:管理模式下左上角箭头与系统返回手势**都只退出管理模式**(取消勾选 + 关编辑弹窗),再按一次才离开页面 —— `BackHandler(enabled = manageMode)` 只拦管理态,非管理态不拦截、交 Activity 默认返回;⚠️ 此前两处都无条件 `finish()`,长按选中后误触返回会连勾选一起丢掉。**正在使用的源不可删除**(勾选框 `enabled=false` + Toast + `deleteSelected()` 兜底);点播列表被删空(边界:激活源不在列表中)才 `ApiConfig.clearVodConfig()` + `AppBootstrap.retry()` 回引导态(**2026-09-12**:原 `clearConfig()` 会连坐清掉独立直播源,已拆分为 `clearVodConfig()` / `clearLiveConfig()`);直播列表被删空 = 自动回「跟随点播源」,点播侧不受影响。合成的「跟随点播源」卡不参与选中与删除。**编辑(2026-09-12)**:管理模式右上「编辑」→ 同一个 dialog 预填名称/链接 → 保存时 `updateSubscribe()` 按**原链接**定位原地更新(位置不变;链接改成与另一项相同则去掉被撞项);改的若是当前在用的源且**地址变了**,按新地址重新生效(点播 = `switchToVod` 整页重载,直播 = `switchToLive` 只换直播侧),**仅名称变化不重新生效**;保存后该项保持勾选态。
- **添加 / 编辑对话框(共用一个)**:M3 `AlertDialog`(名字 / 链接两行 `OutlinedTextField`,label 常显;链接为空时保存钮禁用);新增态标题「添加订阅 / 添加直播源」、`initialName/initialUrl` 传空串,编辑态标题「编辑订阅 / 编辑直播源」并预填当前值;同链接重复保存 = 更新名字,位置不变;直播段链接框下方加 `supportingText` 提示「支持配置 JSON / m3u / txt 直播源」。标题右上角 =「从本地选择」40dp 圆钮。
- **本地文件选择 = 系统 SAF**(2026-09-11 五轮定稿):`ActivityResultContracts.OpenDocument()`(MIME `*/*`)→ Uri 转 `clan://` 接口地址**回填链接输入框**(不直接保存);能取到真实路径则直接引用,否则复制到 App 外置缓存 `config/`,**无需存储权限**。自绘 `LocalFileActivity` 已整页删除。
- **数据**:Hawk `HawkConfig.SUBSCRIBE_LIST` = `"subscribe_list"` + `HawkConfig.LIVE_SUBSCRIBE_LIST` = `"live_subscribe_list"`(`ArrayList<String>`,每项 `名字\t链接`,格式一致、各自独立);两个角色的地址键 = `HawkConfig.API_URL` / `HawkConfig.LIVE_API_URL`。数据层:`ApiConfig.getEffectiveLiveUrl()`(独立直播源优先、空则回落点播源)、`ApiConfig.isLiveFollowVod()`、`ApiConfig.invalidateLiveConfig()`、`clearVodConfig()` / `clearLiveConfig()`。

### 4.8 主题设置页(2026-09-11 定稿,照搬 `示例文件/android`;视觉细节见 §5)

- **状态**:单例 `object AppThemeState`(Hawk 4 键 `THEME_SOURCE`/`THEME_MODE`/`THEME_SEED`/`THEME_PALETTE_STYLE`,`mutableStateOf` 向全 App 广播);**不建 ViewModel**(进程级状态,页面只"读状态 + 下发 intent")。
- **能力**:取色来源(系统取色 / 自定义种子色)、深浅模式(跟随系统 / 浅色 / 深色)、预设色卡 + 自定义种子色(HSV 取色器)、配色风格(materialkolor `PaletteStyle` 9 种);改动**即时全局生效**。
- **依赖**:`com.materialkolor:material-kolor`(版本目录键 `materialKolor`,见 §2)。缓存 = 主配色 `(seed,isDark,style)` 与色卡预览 `(seed,style)` 各一个 `ConcurrentHashMap`;**按需计算**(不做示例的 8 色 × 9 风格预加载),首帧可能以当前配色占位一瞬。
- **页面**:`ThemeSettingsActivity` + `ui/page/ThemeSettingsPage.kt`(Activity 跳转,符合 §2「不用 navigation-compose」);入口 = 设置 tab **首个分组**「主题设置」整行,值摘要 = 取色来源 · 深浅模式。
- **组件**:`ui/components/CapsuleSegmentedButton.kt`(胶囊分段选择器)、`ui/components/ThemeColorPickerSheet.kt`(HSV 色轮取色器,装在 `AVBoxBottomSheet` 内)、`EdgeToEdgeTopBar.kt` 的 `TopBarActionBox`(40dp 圆形返回钮,二级页复用)。
- **状态栏/导航栏图标归属(关键设计)**:`AVBoxTheme(manageStatusBarIcons = true)` 默认按**解析后的应用主题**决定图标深浅(深浅模式覆盖系统时也不会出现"深色图标压在深色栏上"),同时断言状态栏与导航栏;**纯黑状态栏页面**(详情页 / 直播页 / 播放器覆盖层 `ComposeVideoController`)传 `false`,由各自 Activity 恒白断言。
- **顶栏留白**:二级页规则 = `topBarHeight - 8dp + 28dp`(顶栏内容下沿 + 首卡间距),与栏目页 / 搜索页 / 历史收藏一致;**不要照设置 tab 的 `-12dp`**(会让首个卡片贴住标题)。

### 4.9 偏好设置页(2026-09-12)

- **页面**:`PreferenceSettingsActivity` + `ui/page/PreferenceSettingsPage.kt`;入口 = 设置 tab「偏好设置」行。二级页壳(无边框顶栏 + 返回钮),内容 = `SettingsGroup` 单组卡片,顶栏留白按 §4.8 同规则。
- **卡片顺序(用户指定)**:自动换线 → M3U8 净化 → **无痕模式** → 弹幕开关 → 弹幕 API → 长按倍速 → 缓冲时间 → 搜索线程。
- **无痕模式(2026-09-12)**:开关行 = `SettingsSwitchRow(title="无痕模式")`(**无副标题** —— 2026-09-13 用户要求删掉「不记录搜索与观看历史」那行),值存 Hawk `HawkConfig.INCOGNITO`(`"incognito"`,默认关)。开启后**只拦写入、不隐藏已有数据**:①搜索历史 `HistoryHelper.setSearchHistory()` 直接 return;②观看历史 + 播放进度 `RoomDataManger.insertVodRecord()` 直接 return(该方法是观看历史的**唯一落库点**,片头/切集/进度同步都汇聚于此,拦一处即全覆盖)。**不受影响**:手动收藏(`insertVodCollect` 链路)、清空/删除历史、卸载式的用户主动操作。判定统一走 `HistoryHelper.isIncognito()`(照上游 FongMi 的 `Setting.isIncognito()` + `VodHistoryPolicy` 在策略层拦截的写法;区别是 FongMi 只覆盖观看历史,本项目按用户要求把搜索历史也纳入)。
- **留白**:内容末尾 `Spacer(64.dp)`,与设置页一致。

## 5. 视觉与组件约定

- 卡片触摸反馈:ripple + 按压缩放(0.96~0.98)。**涟漪透明度全局 = M3 默认 2 倍**(2026-09-13,照搬 `示例文件/android` 的 `Theme.kt`):`AVBoxTheme` 用 `LocalRippleConfiguration` 下发 `RippleConfiguration(rippleAlpha = …)`,pressed 0.20 / hovered 0.16 / focused 0.20 / dragged 0.32 —— M3 默认 pressed 仅 10%,首页海报卡是深色图片 + 黑色渐变 scrim,几乎看不出"点到了"。走全局配置而不是逐卡传 `indication`:`clickable`/`combinedClickable`/`Surface(onClick)`/`ToggleButton` 一次覆盖。⚠️ `RippleConfiguration` 已 deprecated 但官方无替代入口,必须 `@Suppress("DEPRECATION")`。圆角卡必须 **先 `.clip(shape)` 再挂 `clickable`**,否则涟漪与长按激活区会溢出圆角变成矩形(`PressableCard` 已是此顺序)。参考项目的按压缩放目标是 **0.94**(经 `Modifier.scale` + `spring(dampingRatio=0.6f, stiffness=800f)`),本项目沿用自定的 0.96~0.98 不改。
- 字号阶梯 18/14/12sp 替代 TV 的 mm 大字号;间距 8dp 栅格。页面左上角大标题(设置/历史/收藏)= `headlineSmall` 24sp/700(2026-09-09 用户定稿,Typography 集中定义)。
- 骨架屏 shimmer 统一组件(替代 LoadSir);空态/错误重试统一三态组件。
- **胶囊分段选择器(2026-09-11 引入,2026-09-12 加 `SegmentStyle`)**:`ui/components/CapsuleSegmentedButton.kt` —— M3 expressive `ToggleButton` 组,按压弹簧回弹(0.94→1),整行等宽,语义 `Role.RadioButton`;选项 `SegmentOption<T>(label, value, icon/iconPainter, badge)`,**`badge` = 可选的第二行摘要**(模式开关兼作状态显示;为 null 时渲染与单行版一致)。**两种外观由 `style` 选择,默认 `Connected`(不传即保留引入时原样)**:
  - `SegmentStyle.Connected`(原样式,**主题设置页**):段间 `ConnectedSpaceBetween` 细缝 + `ButtonGroupDefaults.connectedShapes`,除形状外全走 M3 `ToggleButton` 默认(选中 primary 实心 / 未选中 surfaceContainer)。
  - `SegmentStyle.Track`(**配置管理页**):外层全圆角胶囊轨道(`surfaceContainerHighest`)+ 4dp 内缩 + 4dp 段间距 + 段容器透明(选中胶囊"浮"在轨道上)+ `elevation = null`(透明容器上留默认阴影会有一圈灰边)+ 段内边距 12/2dp ⇒ 两行内容总高 **48dp**;三段形状统一为全圆角,顺带关掉 M3 默认的按压形变。⚠️ 段高下限 = M3 `ToggleButtonDefaults.MinHeight`(40dp,硬编码在 `ToggleButton` 内部的 `defaultMinSize`),要更小只能放弃 `ToggleButton` 自绘。
  - ⚠️ **改本组件前先确认两个调用页各自要的外观** —— 2026-09-12 教训:轨道样式一度全局生效,主题设置页观感变差,当天改为按页选择。
- **加载指示器(2026-09-11 用户定稿)**:页面/内容加载态统一改用 M3 expressive **`ContainedLoadingIndicator`**(替代 `CircularProgressIndicator`),**尺寸统一 64dp**(例外见下)。接入点 = `LoadStateBox` 默认 `loadingContent` 64dp(覆盖首页 / 搜索 / 栏目二级列表页等整页加载态)+ `MainScreen.BootLoading` 64dp + 历史 / 收藏页 loading 分支 64dp。**尺寸例外(保持默认 48dp)**:① 竖屏详情页两处(内容 Loading 分支、竖屏玩家区 Loading);② 直播页(播放器页)整页 LOADING —— `LivePlayActivity` 调 `LoadStateBox` 时显式传 48dp 的 `loadingContent` 覆盖默认值。详情页竖屏玩家区(纯黑底)另**显式传白色系配色**(容器 `White 20%`、指针 `White 75%`):默认 `secondaryContainer` 在纯黑上过亮,且与紧接其后的播放器白色 spinner 不接续。**播放器视频覆盖层 spinner 保持 `CircularProgressIndicator` 不动**(`PlayerLayers.PlayerLoadingLayer`、`PlayerSheets.SheetLoading`、`LivePlayActivity` 切台快照 36dp / 缓冲态 40dp)。组件尺寸由调用方 `Modifier.size` 控制(实现内部走 `SizeKt.size`,指针按 `ActiveIndicatorScale` 等比缩放);该 API 在 material3 1.5.0-alpha23 中标注 `@ExperimentalMaterial3ExpressiveApi`,接入文件统一 `@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)`(与 HomePage/SettingsPage 同风格)。**2026-09-12 扩展定稿:全项目所有圆形加载指示器(播放器页面除外)一律 `ContainedLoadingIndicator`** —— 全库审计后非播放器的 `CircularProgressIndicator` 仅剩首页下拉刷新一处,已换成引导页(BootLoading)同款 48dp;播放器保留清单不变(`PlayerLayers.PlayerLoadingLayer`、`PlayerSheets.SheetLoading`、`LivePlayActivity` 切台快照 36dp / 缓冲态 40dp)。
- **主题设置页(2026-09-11,照搬 `示例文件/android`)**:分组卡片沿用全局 `SettingsCard`(卡位圆角 + `cardContainer` 底色),行内规格 minHeight 64dp / 水平 16dp / 垂直 12dp 且内容垂直居中;不可用行(非自定义模式下的色卡/自定义色/风格)整卡 alpha 0.45。图标 4 枚直接取自示例项目 drawable:`ic_color_palette`(分组标题,primaryContainer 圆底)/ `ic_brightness_auto` / `ic_light_mode` / `ic_dark_mode`(模式分段选择器)。预设色卡 = 4 列 × 2 行、1:1 正方形、**圆角 16dp**(2026-09-11 用户定稿;12dp → 28dp → 16dp 两轮调整),选中态 primary 2dp 描边 + 右上角勾选圈,卡内为主色条 + 次色/第三色块 + 名称的动态配色预览。取色器 = 自绘 HSV 色轮(240dp,`Canvas` sweepGradient + 径向白渐变)+ 亮度滑块 + 初始/当前色对比,装在 `AVBoxBottomSheet` 内(标题「自定义颜色」,取消/确定)。顶栏与设置页一致(无边框 + 随滚动滚走 + 顶部渐变遮罩),左侧 40dp 圆形返回钮。
- edge-to-edge:enableEdgeToEdge + Scaffold insets;深浅色状态栏图标切换;双击返回退出提示。
- **顶部应用栏无边框化(2026-09-11 晚重做,逐字照 `示例文件/android` 官方方案)**:全站(4 tab + 搜索/栏目二级页)顶栏统一 —— 共享组件 = `ui/components/EdgeToEdgeTopBar.kt` 的 **`AppTopBarScaffold`**:`Scaffold(nestedScroll(exitUntilCollapsed), contentWindowInsets=0) + M3 TopAppBar(透明底、windowInsets=0、外层 statusBars padding) + TopScrim`(状态栏高×1.2 渐变遮罩,置于内容之上)。**滚动记账完全归 M3 官方 behavior**(含 fling 吸附,示例同款;⚠️ 不要再回到自研记账,理由见 §6.6)。顶栏高度 = M3 标准 64dp + statusBars(原自研 56dp,整体 +8dp);页面内容留白 = content 回调 `padding.calculateTopPadding()` + 各页相对差值(设置 -12、历史/收藏/配置/主题 -8+28、首页 +8、搜索/栏目 -8)。标题区/返回钮(40dp 圆钮)/右侧控件走 TopAppBar 的 title/navigationIcon/actions 槽。

## 6. 关键技术约束与已知坑(违反会复发 bug)

### 6.1 播放内核
- **Exo 帧率匹配必须保持关闭**:`ExoPlayer.disableFrameRateMatching()`(`Renderer.MSG_SET_CHANGE_FRAME_RATE_STRATEGY` 下发 `VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF`,由自定义 `SubtitleOffsetRenderersFactory.buildVideoRenderers()` 收集视频渲染器后在 `initPlayer()` 逐个下发)。**media3 默认把视频帧率写进播放 Surface,ROM(vivo 2425A / OriginOS)据此把整机刷新率由 120Hz 降到 60Hz**,主观表现 = "播放时滑动 / 开 bottom sheet 卡顿掉帧,暂停就正常"(IJK 不调该 API 故不触发)。⚠️ **窗口级高刷申请(`preferredDisplayModeId` / `preferredRefreshRate` / `View.setRequestedFrameRate`)实测无效,不要再试**。若某机型仍降频,备选 = 屏蔽 `MediaFormat.KEY_OPERATING_RATE`,或该机型默认内核改 IJK。
- **播放容器 = `ui/player/PlayContainer.java`**(非 Fragment 的 `FrameLayout`,原 `PlayFragment`):宿主显式驱动 `hostResume()/hostPause()/hostDestroy()`;⚠️ `mActivity` 置空必须在 `stopLoadWebView` **之后**,否则 WebView 泄漏。`SourceViewModel` 由容器直接持有(容器不是 ViewModelStoreOwner)。
- **换源交互(2026-09-11 方案 C 定稿)**:点击换源 = **立即停播**(`PlayContainer.stopForSourceSwitch()`,先 `getCurrentPosition()` 刷新 `mCurrentPosition` 再 release,置 `switchStopPending` 抑制在途取流回调)+ **进度继承到新源**(新键已有历史则不覆盖)+ **失败回滚原源并从停播处续播**(`DetailViewModel.SwitchSnapshot` / `rollbackManualSwitch`,四处失败点改回滚;无快照时保持原空态/关页行为)。快照不被覆盖 → 回滚目标始终是"最后一次可播状态"。过程见 `history/features.md`。

### 6.2 异步与列表
- **爬虫 `getSearch` / `getDetail` 等阻塞调用必须在 IO 线程**调用(主线程调用 → 卡死 / ANR);并发限流 6、单源 30s 超时(`future.get`)。
- **禁止在 item 内容里向 `LazyListScope` 追加 item**(非法嵌套 → 闪烁 / 覆盖);分区与卡片一律给稳定 key(分区 key=分类 id,卡片 key=vodId)。

### 6.3 依赖(易被误删,必读)
- **`com.google.zxing:core` 是运行期动态依赖**:宿主源码里"零引用"属正常现象,但爬虫 jar 由 `JarLoader`(`app/src/main/java/com/github/catvod/crawler/JarLoader.java`)以 `DexClassLoader(jar, cachePath, cachePath, App.getInstance().getClassLoader())` 加载(**父加载器 = 宿主**),jar 生成二维码时引用 `com.google.zxing.EncodeHintType`,**必须由宿主提供**。2026-09-08 曾按"零引用"删除 → 2026-09-12 设备更新爬虫 jar 后每次启动约 10s 必崩(`NoClassDefFoundError`,当日 6/6 次),已回滚为 `com.google.zxing:core:3.5.4`。诊断全文见 `logs/crash_diagnosis_20260912.md`。
- **宿主运行期契约的完整清单(2026-09-12 用 DEX class_defs 精确审计得出)**:爬虫 jar 由 `DexClassLoader` 加载,按**宿主类名**解析,需要宿主提供 ——
  - okhttp3(17 类)、gson(7 类)、`com.google.zxing`(4 类)、quickjs wrapper(5 类)、宿主 catvod 的 `crawler.Spider` / `SpiderDebug`、平台自带的 `org.xmlpull.v1.XmlPullParser`;
  - ⚠️ **`org.slf4j` —— 不要补**(2026-09-12 加了又撤回,有实测证据):jar 常量池确实引用 `org.slf4j.ILoggerFactory` / `impl.StaticLoggerBinder`,但它把 slf4j-api **shade 进了自己的混淆包**(`merge.mu` = LoggerFactory、`merge.Pu` = ILoggerFactory)。宿主**没有**绑定时它内部回落 NOP = 正常降级;宿主**提供**标准绑定(`slf4j-api` + `slf4j-nop`)时,拿回的 `org.slf4j.helpers.NOPLoggerFactory` 实现的是未混淆的 `org.slf4j.ILoggerFactory`,与 jar 的 `merge.Pu` 不兼容 → `IncompatibleClassChangeError`,打崩 jar 的日志/配置引导路径(实测:`logs/logcat_full_20260912_232710.txt`)。**教训:常量池引用 ≠ 宿主应当提供 —— 这一类判断只有跑起来才能证伪**;
  - 方法:`jar 引用 − jar 自身定义 − 宿主已提供 = 宿主必须补的类`。审计脚本与结论见 `history/features.md`「宿主契约审计」。
- **R8 会在 release 下改名/裁剪这些类,而 debug 不混淆 —— 契约缺口只在 release 暴露**。已加的 keep:`com.google.zxing.**`、`com.google.common.**`(Guava 由 `media3-common` 传递带入,宿主代码无静态引用,不 keep 必被改名)、`okhttp3.**`、`okio.**`、`com.google.gson.**`、`com.whl.quickjs.**`、`com.github.catvod.**`。
- **通则**:凡动态加载 jar 可能用到的宿主 API 一律保留;**依赖裁剪不得只看宿主源码静态引用**;改了依赖后要**在 release 产物上复验**「jar 需要的类是否都还在」(`assembleRelease` + DEX 扫描),debug 产物无法证明这一点。

### 6.4 图片
- **Coil 不识别 TVBox 海报地址约定**(`url@Headers={...}/@Cookie/@User-Agent/@Referer`),必须走 `ui/components/VodImages`(OkHttp 拦截器剥离附加参数并注入请求头)+ `util/CoilBridge`(`OkHttpNetworkFetcher.factory()` 被标注 `@Deprecated(HIDDEN)`,Kotlin 不可见,需 Java 桥调用),`App.onCreate` 注册单例。改图片加载链前先查 `history/steps.md` 的 Step 2 补丁。

### 6.5 底部 sheet 与覆盖层
- **`AVBoxBottomSheet` = 应用窗口内覆盖层,不是 dialog window**(M3 `ModalBottomSheet` 已移除):弹出/关闭不再切换状态栏外观归属窗口(修掉"状态栏图标闪两次")。页面位于被裁剪容器(MainScreen pager)时,由 `SheetHost` 槽位提升到窗口根部渲染(可覆盖底栏与系统栏),调用方零改动。
- **手写壳层必须自带 M3 的隐式能力**(否则只在"内容足够长"时暴露):面板 `heightIn(max = 屏幕高 × 0.9)`、把手/标题固定 + 内容区 `weight(1f, fill = false) + verticalScroll`、**下滑关闭手势只挂在把手/标题区**(不与内容滚动抢触摸)。内容自带滚动容器的 sheet 传 `isScrollable = false`(首页订阅源 / 直播 EPG / 直播设置 / 详情选集)。
- ⚠️ 入场动画**不要**依赖 `onGloballyPositioned` 回调(回调缺失即"点 sheet 不弹"),用比例式 `translationY = collapse * size.height`;遮罩在入场完成前必须 `clickable(enabled = entered)`,否则隐形遮罩会吃掉整页触摸。

### 6.6 顶栏与状态栏
- 全站统一 `AppTopBarScaffold`(`ui/components/EdgeToEdgeTopBar.kt`),滚动记账**完全交给 M3 官方 `exitUntilCollapsed` behavior**(含 fling 吸附,与 `示例文件/android` 一致)。⚠️ **不要再自研"增量记账"式滚动状态** —— 2026-09-11 两轮装机 bug(标题残留状态栏区 / 列表滚走顶栏不跟随)的根因是"记账"与"列表真实位置"是两套状态,程序性列表复位必然失联。
- 顶栏是透明覆盖层且画在内容之上:**顶部滑出的浮层(如下拉刷新指示器)必须 `offset(y = topPadding)`**,否则被左上角控件盖住。
- 状态栏图标外观会被系统按主题重设(沉浸进出 / 横竖屏 / 回前台):相关页面需在 `init` / `onResume` / 退出全屏后**反复断言**,否则深色图标画在纯黑状态栏上等于消失。
- 内容留白按「**顶栏内容下沿**」计算,不是整行高度(行内内容垂直居中会产生余量):各页相对差值 = 设置 `-12`、历史/收藏/配置/主题 `-8+28`、首页 `+8`、搜索/栏目 `-8`。

## 7. 未决 / 待细化清单

- ~~详情/播放页视觉细化(选集行样式、换源交互)~~(Step 4 已确认并实施,记录见 `history/steps.md`;遗留:预览态加载期无海报占位,旧 ivThumb 缩略图未迁移)
- ~~播放器内 View 对话框(字幕/弹幕设置/弹幕搜索/字幕搜索/投屏/选集/内核/倍速/尺寸)→ Compose bottom sheet 化~~(2026-09-08 纳入 Step 6,方案 A:全部 View 对话框含设置类 BaseDialog 体系一并 sheet 化,完成后卸 tv.recyclerview;长按倍速等手势指示器样式随 sheet 化一并处理)
- ~~检查更新/关于页的呈现形式~~(Step 2 已确认:检查更新做占位行「当前已是最新版本」,关于沿用旧 AboutDialog;完整更新逻辑待接入更新源时实现)
- ~~历史/收藏 tab 的具体视觉~~(Step 2 已确认,见 §4.2)
- 首页品牌色板(非动态取色时 Android <12 用的 fallback 色值)

## 8. 历史归档索引(`history/`,按需检索)

**规则:不要通读 `history/`,用检索定位。** 归档正文里的 `§x` 引用沿用 2026-09-12 归档前的旧编号(§4.x 未变)。

| 文件 | 内容 | 什么时候查 |
| --- | --- | --- |
| `history/steps.md` | Step 0–7 改造实施记录、Step 1 删除清单实际对账、各步决策与验证记录 | 想知道"某个类 / 布局 / 依赖当初为什么删"、"某步的架构决策与验证点" |
| `history/features.md` | 2026-09-09 起功能迭代记录:首页下拉刷新、隧道模式 + AAC 优先、配置管理页、主题设置页、顶部应用栏改造全过程、选集网格溢出修复、快搜删除、卡片点击分发 + 网盘下钻 | 想知道"某功能当初怎么实现 / 为什么这么定 / 踩过什么坑" |

**旧章节 → 新位置对应关系**:旧 §5 删除清单 → `history/steps.md`;旧 §7 实施路线 + 旧 §8 Step 记录 → `history/steps.md`;旧 §8 功能小节 → `history/features.md`;旧 §6 视觉细节 → 本文 §5;旧 §9 未决清单 → 本文 §7。

**检索示例**:

```powershell
# 全量历史里搜关键词(先看命中行,再定点展开上下文)
Select-String -Path skill\history\*.md -Pattern '帧率','顶栏' | Select-Object -First 20
Select-String -Path skill\history\features.md -Pattern '配置管理页' -Context 0,3
```

**其他文档**:通用开发规范 = `SKILL.md`;崩溃诊断 = `logs/crash_diagnosis_20260912.md`;本 spec 的历史版本见 git(`git log --oneline -- skill/`)。
