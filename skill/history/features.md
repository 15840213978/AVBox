# AVBox 功能迭代实施记录（历史归档）

> 本文件是 `skill/history/` 归档的一部分：2026-09-09 起各项功能改造的**实施过程、历史补丁与排查记录**。
> 活规范见 `../avbox-mobile-ui-spec.md`，通用规则见 `../SKILL.md`。
> 用途：**仅在需要追溯「当初怎么做的、为什么这么做、踩过什么坑」时按需检索**，不要通读。
> 说明：文内 `§x` 引用沿用归档前的旧编号（§4.x 未变；旧 §5/§6/§7/§8 已重组，见 SKILL.md 文档地图）。

---

## 归档时的项目状态（2026-09-12）

- **状态**:Step 7 已完成(assembleDebug 通过,真机回归待装包验证);快搜功能已删除(2026-09-09,见下)
- **最近更新**:2026-09-11(★ **主题设置页**(照搬 `示例文件/android`:取色来源/深浅模式/预设色卡/HSV 取色器/配色风格,新增 materialkolor 依赖与 `AppThemeState` 全局状态,设置 tab 入口,见下方「主题设置页」);**顶部应用栏无边框化**(4 tab + 二级页;内容延伸至状态栏 + 随滚动滚走 + 顶部渐变遮罩,见 §6 与下方「顶部应用栏无边框化」;首页/栏目页**卡片点击分发**+ 网盘目录下钻 + 源级「搜索/详情」策略,见 §4.1「卡片点击分发」与本页「卡片点击分发 + 网盘目录下钻」;详情页 UI 补丁:⑦ 线路/清晰度卡片化、⑧ 状态栏图标外观断言,见 §8 Step 4 补丁;加载指示器全局改用 `ContainedLoadingIndicator` 并定稿 64dp(详情页/直播页例外 48dp),见 §6;搜索结果页 = 波浪线进度条 + 结果源筛选 chips,见 §4.6;**配置管理页**(2026-09-11 二轮:设置 tab 新入口 = 源添加/管理唯一入口,订阅源开关切换 + 长按删除,见下方「配置管理页」;隧道模式 + AAC 优先(见下方小节);2026-09-12:首页**下拉刷新**(48dp 圆形指示器 + 松手整页重载,见下方「首页下拉刷新」);**点播 / 直播配置拆分 + 配置管理页分段**(2026-09-12 晚,见下方同名小节))
- **下一步**:真机回归(Step 4/5/6/7 欠账合并验证 + 主题设置页新功能验证 + 隧道模式/AAC 优先 + 首页下拉刷新)

## 点播 / 直播配置拆分 + 配置管理页分段（2026-09-12 晚）

**背景（用户原话）**：「配置管理能否支持点播和直播分开来？如果没有加上直播源就默认使用目前开启的点播源作为直播，如果添加了直播源优先级则更高，但是点播还是用原来的点播源。」

**对照上游（`示例文件/TV-fongmi`，只读参考）**：FongMi 已是同语义实现，直接照搬其判定方式而非自创开关 —— `BaseConfig.needSync()`（`sync || config==null || url 为空 || url 等于直播配置 url`）、`LiveConfig.config()`（`sync = config.getUrl().equals(VodConfig.getUrl())`）、`LiveConfig.load()`（`if (sync) return`，跟随态连拉都不拉）、`VodConfig.initLive()`（仅 `needSync` 时把点播 JSON 的 lives 喂给 LiveConfig）；存储 = Room `Config` 实体带 `type` 列（0 点播 / 1 直播 / 2 壁纸），UI = 设置页 Vod / Live 两条独立行 + 同一个输入弹窗按 type 换标题（**清空 url = 删除该 type = 回到跟随**）。

**改造前的实际状态（读代码得出的关键结论）**：读路径**已经**具备该语义 —— `API_URL` / `LIVE_API_URL` 本就是两个键，`loadLiveConfig()` 空则回落 `API_URL`，`parseJson()` 只在 `live_api_url` 为空或等于当前解析的 `apiUrl` 时才从点播 JSON 取 `lives`，直播页切换也只写 `LIVE_API_URL`。**真正的缺口只有两处**：① `ConfigManagePage.applySubscribe()` 与 `SettingsPage` 接口线路**双写两个键**（点播/直播被强制同源，并会把独立直播源冲掉）；② 没有任何录入独立直播源的入口。

**落地（数据层 → 写入侧 → UI）**：

1. **数据层**：`ApiConfig.getEffectiveLiveUrl()`（独立源优先、空则回落点播源）/ `isLiveFollowVod()` / `invalidateLiveConfig()`（清内存 + `loadedLiveConfigUrl=""`，让直播页下次进入必然重载）；`clearConfig()` 拆成 `clearVodConfig()` / `clearLiveConfig()`。**跟随的表示法沿用「空 或 等于点播 url」** —— 旧双写留下的相等状态天然判定为跟随，老用户升级后行为不变，**不需要数据迁移**。
2. **写入侧解耦**：`applySubscribe()` 拆成 `applyVodSource()`（返回"切换后是否跟随"；跟随则把 `LIVE_API_URL` 归一化为空并继续跟随新点播源，独立则一个字都不改）/ `applyLiveSource()`（不碰 `API_URL`、不触发 `AppBootstrap.retry()`）/ `applyLiveFollowVod()`；`SettingsPage` 接口线路同样只写点播。删空点播列表改走 `clearVodConfig()`（独立直播源保留，旧 `clearConfig()` 会连坐清掉）。
3. **UI**：配置管理页顶栏改 `collapseEnabled = false`（pinned，`topPad` 恒定，分段行才能常驻 —— 与首页/搜索页同款模式），顶部加「点播 / 直播」分段；`CapsuleSegmentedButton` 加 `badge`（第二行摘要，null 时渲染与旧版一致，主题页不受影响）与 `minHeight`；直播段首项 = 合成的「跟随点播源」卡（开关不可关 —— 直播至少要有一个来源）；新增 `HawkConfig.LIVE_SUBSCRIBE_LIST` 存独立直播源（格式与 `SUBSCRIBE_LIST` 一致，`loadSubscribes/saveSubscribe` 参数化 key 复用，零迁移）。
4. **直播页「配置切换」组**：第 0 项补合成的「跟随点播源」（`ApiConfig.LIVE_FOLLOW_ITEM_NAME`），历史项下标整体 +1，选中判定 / 点击 / 长按删除三处同步换算。**不补这一项不行**：解耦后 `LIVE_API_HISTORY` 不再记录点播 url，该组会没有任何选中项。

**踩过的坑 / 决策（可复用）**：

- `ApiConfig.getLiveGroupIndexKey()` **故意保持原样**（仍按原始 `LIVE_API_URL` 取键）：改成按"生效地址"会让老用户的直播分组记忆一次性失效，收益不抵风险。
- 「跟随点播源」卡的当前源名走 `subtitle` 而非 `SettingsSwitchRow.valueText` —— `valueText` 在 Row 里不受 `weight` 约束，源名过长会把右侧 `Switch` 挤出卡片。
- 切分段必须重置 `manageMode` / `selected`，否则会把另一角色勾中的源当成当前角色的删除目标。
- `OutlinedTextField.supportingText` 用显式标注 `(@Composable () -> Unit)?` 的局部 val；写成 `x?.let { { Text(it) } }` 会被推断成普通 `()->Unit` 与可组合类型不匹配。
- `clearVodConfig()` 里 `isLiveFollowVod()` 必须在把 `API_URL` 置空**之前**求值，否则跟随判定失效、`LIVE_API_URL` 残留成陈旧快照。
- 直播源支持纯文本形态（`parseLiveConfigContent` 三分支:带 lives 的 JSON / 纯直播 JSON / m3u-txt 文本），因此直播段的添加框提示写「支持配置 JSON / m3u / txt 直播源」；本地文件经 `clan://` 同样走这条路。

**验证**：`:app:compileDebugKotlin` + `:app:compileDebugJavaWithJavac` 均 BUILD SUCCESSFUL（产物时间戳已核对晚于源文件改动）；真机待验：①点播段切源后直播仍跟随;②添加独立直播源后切点播源,直播源不被覆盖;③直播段关闭普通卡回到跟随;④删空点播列表时独立直播源仍在;⑤直播设置「配置切换」组跟随项选中态。

**同日追加（分段外观按页区分 + 配置管理页编辑控件 + 卡片源图标）**：

- **分段外观按页区分（用户反馈驱动）**：轨道样式一度做成组件全局默认,用户看过真机后反馈主题设置页"难看死了",遂改为 `SegmentStyle` 二选一 —— `Connected`(默认,原连接胶囊,主题设置页不传参即原样) / `Track`(配置管理页显式传)。`Connected` 分支除连接形状外**不传任何参数**、全走 M3 `ToggleButton` 默认,这样"主题页与引入时逐像素一致"是被代码结构保证的,而不是靠比对。教训:共享组件改默认外观 = 同时改所有调用页,先问清哪个页面要变。
- **图标转换**：`.tubiao/编辑.svg` → `drawable/ic_edit.xml`、`.tubiao/配置管理的订阅源卡片icon图标.svg` → `drawable/ic_subscribe_source.xml`,沿用既有约定(`viewportWidth/Height=960` + `<group android:translateY="960">` 平移负坐标 + `fillColor="#FFFFFFFF"`,颜色交给 Compose `Icon(tint=)`）。
- **编辑控件**：管理模式右上改为 `Row(8dp 间距)` = 编辑(左)+ 删除(右),两者复用历史页 `ManageActionIcon`;编辑仅当**勾选恰好 1 项**时 `enabled`(多选无意义)。新增 `updateSubscribe(mode, original, name, url)` 按**原链接**定位原地更新 —— 与 `saveSubscribe` 的"按新链接查重"不同,因为编辑允许改链接本身;链接撞车时去掉被撞项。保存后把勾选值从旧字符串替换为新字符串,避免"选中集里留着已不存在的条目 → 管理模式下无可见勾选却仍能点删除"的错位。
- **共用一个 dialog**：`AddSubscribeDialog` 加 `initialName/initialUrl`,新增态传空串、编辑态预填;标题在「添加/编辑」×「订阅/直播源」四种组合间切换。
- **当前在用源被编辑**：仅当地址变化时重新生效(点播 → `switchToVod` 整页重载;直播 → `switchToLive` 只换直播侧),纯改名不重载。
- **卡片源图标**：复用设置页的 `SettingsIconBadge`(40dp `primaryContainer` 圆 + 22dp 图标)。⚠️ 第一版把图标放进文字 Column 内与名称同行,导致链接左侧不缩进、与名称不对齐 —— 改为置于 Column **之外**,名称/链接同基线且整块与图标垂直居中,卡高也不变。
- **验证**：`:app:compileDebugKotlin` BUILD SUCCESSFUL;`:app:installDebug` BUILD SUCCESSFUL 27s,已装机(`lastUpdateTime=2026-09-13 00:13:23`)。

## 无痕模式（2026-09-12 晚；偏好设置页）

**需求（用户原话）**：「在偏好设置页面 m3u8 净化和弹幕开关卡片的中间新增加一项功能名为无痕模式，开启后搜索历史不记录，观看历史也不记录，手动收藏功能正常」。

**先查上游**：FongMi 已有同名功能（`Setting.isIncognito()` = `Prefers.getBoolean("incognito")`,设置页一行开关）,拦截点全在策略层 `playback/vod/VodHistoryPolicy.java`(`save` / `saveVisit` / `saveCurrent` 三个写入口都 `if (Setting.isIncognito()) return`,另外 `findOrCreate` 里 `history.delete()` 让本会话内仍能续播)。**差异**:FongMi 的无痕**只覆盖观看历史**,不拦搜索历史;本项目按用户要求把搜索历史也纳入。

**落地点（在数据层拦,不在调用点拦）**：

- `HawkConfig.INCOGNITO = "incognito"`(默认关);判定收敛到 `HistoryHelper.isIncognito()`,避免多处重复读 Hawk。
- 搜索历史:`HistoryHelper.setSearchHistory()` 开头 return。全工程写 `SEARCH_HISTORY` 的只有本类三处 —— `setSearchHistory`(拦)/ `clearSearchHistory` / `removeSearchHistory`(后两者是用户主动删除,**故意不拦**)。
- 观看历史:`RoomDataManger.insertVodRecord()` 开头 return。**这是关键判断** —— 该方法经核查是观看历史 + 播放进度的**唯一落库点**(`DetailActivity` 的片头 `preparePlayBundle`、切集、`syncPlayingVodInfo`、`playerCfg` 四处 `insertVod()` 全汇聚到这里),所以在数据层拦一处就全覆盖,且未来新增调用点自动受保护;比在 4 个调用点各写一遍可靠。
- **收藏不受影响**:收藏走 `RoomDataManger.insertVodCollect`(DetailActivity 收藏按钮 + 首页长按「加入收藏」),完全没碰。

**为什么只拦写入、不隐藏已有数据**:用户说的是"不记录",不是"看不到"。已有历史照常展示、清空/删除照常可用 —— 否则用户会以为历史丢了。

**验证**：`:app:compileDebugKotlin` + `:app:compileDebugJavaWithJavac` BUILD SUCCESSFUL;`:app:installDebug` BUILD SUCCESSFUL 22s,已装机。真机待验:①开启后搜索一次,退出重进搜索页无新历史;②播放一集后历史页不出现该条目,重进详情页从第 1 集开始(不续播);③同状态下点收藏,收藏页正常出现该条目;④关闭无痕后上述记录恢复。

## 详情页选集行去重 + 首页直播 FAB 换项目图标（2026-09-12 深夜，用户截图反馈）

- **选集行重复「全部」**：用户截图指出"选集区域这一栏不要在全部下面还显示全部"。核实:`EpisodeRow`(`DetailActivity.kt`)表头右侧已有 `PillAction(ic_episode_grid_all, "全部") → vm.showEpisodeSheet()`，而 `LazyRow` 末尾还挂了一颗 `item(key = "all")` 的 `FilterChip("全部")`，两者点击行为完全相同 → 删除末尾那颗（同一动作两个入口，既冗余又挤占横向 chips 空间）。⚠️ 保留表头那颗：它带图标、与「倒序/正序」成对，是选集卡片表头的固定组成。
- **首页直播 FAB 换图标**：用户新放入 `.tubiao/直播fab.svg`（Material Symbols 的 screen-share/直播图标）。转换 → `drawable/ic_live_fab.xml`（同前约定:viewport 960 + `translateY=960` + 白色 fill 交给 Compose tint），`HomePage.kt` 的 `Icon(imageVector = Icons.Filled.LiveTv)` 改为 `Icon(painter = painterResource(R.drawable.ic_live_fab))`，并清掉 `androidx.compose.material.icons.filled.LiveTv` 导入（全工程仅此一处引用）。HomePage 此前完全没用过 `R`/`painterResource`，本次补了两个 import。
- **验证**：`:app:compileDebugKotlin` BUILD SUCCESSFUL；`:app:installDebug` BUILD SUCCESSFUL 31s，已装机。

## 死代码 / 死文件审计（2026-09-13，用户要求）

**方法（可复现）**：先把 `app/src` + `quickjs/src` + `player/src` + `pyramid/src` 全部 `.kt/.java/.xml` 读成一个大字符串作为"全工程引用集"，再逐个比对：
①资源名 — 每个 `res/drawable*`/`mipmap*` 文件名、`res/values*` 里声明的每个 `name`，查 `R.<type>.<名>` 或 `@<type>/<名>`；
②文件级 — 每个 `.kt/.java` 提取顶层 `fun/class/object/interface` 名，看是否在本文件之外出现；
③成员级 — 每个 public/internal 方法/字段名在全工程是否只出现 1 次（即只有声明）。
⚠️ **两个必须做的修正**（否则误报一大片）：Java getter/setter 在 Kotlin 侧是**属性语法**（`getChannelGroupList()` 写成 `.channelGroupList`），要额外按首字母小写再数一次；`RefreshEvent` 那种全大写常量要注意大小写敏感的匹配。

**本次实际删除（已验证零引用、零风险）**：

| 项 | 位置 | 判定依据 |
|---|---|---|
| `ic_launcher_background.xml` | `res/drawable/` | AS 模板网格图；自适应图标用的是 **`@color/ic_launcher_background`**（colors.xml 里的同名颜色），这个 drawable 从建仓起就没人引用 |
| `color_CCFFFFFF` | `res/values/colors.xml` | 注释说服务于 `view_play_container.xml` 的加载提示，但那三个提示 View 已于 2026-09-11 移除（布局文件自己的注释可证）；此后无引用 |
| `IpScanningVo.java` | `bean/` | 整个类零引用（含 python/java 侧与 assets），IP 扫描功能早已不存在 |
| `hotVodDelete` | `HawkConfig` | `public static boolean`，零引用（注意：它是**字段**不是 Hawk 键，与其它常量不同，删掉不影响持久化数据） |
| 6 个 `TYPE_*` | `RefreshEvent` | `TYPE_PUSH_URL/EPG_URL_CHANGE/SETTING_SEARCH_TV/FILTER_CHANGE/LIVE_API_URL_CHANGE/HOME_SOURCE_CHANGE` 全工程既无 post 也无订阅者；**保留常量的数值不动**（EventBus 按 int 分发），并在类注释里记下这些空洞编号避免复用 |
| `minHeight` 形参 | `CapsuleSegmentedButton` | 2026-09-12 引入后，随着调用方改用 `SegmentStyle`（两段各自 40dp 天然高度），两个调用页都不再传 → 形参 + `heightIn` 调用 + `Dp` import 一并删除；段高下限仍由 M3 `ToggleButton` 自带 |

**刻意保留（扫描命中但绝不能删）** —— 这三类占了"疑似死代码"的绝大多数：

1. **JNI/native 桥**：`com.p2p.P2PClass` 全部 ~23 个 `P2P*`/`xGFilm*` 方法 —— 由 `libp2p.so` 反向调用，源码里必然"零引用"。
2. **JS 爬虫 API**：`com.github.catvod.crawler.js.*`（`Global.pdfh/pdfa/rsaX/js2Proxy`、`Json.safeListElement`、`Res.getCode`、`rsa/RSAEncrypt` 等）—— 由 JS 脚本通过 `JsSpider` 注册的全局对象调用；`proguard-rules.pro:208` 有 `-keep class com.github.catvod.**`。这正是 spec §6.3「依赖裁剪不得只看宿主源码静态引用」的例子。
3. **接口/框架回调**：DLNA 的 `remoteDeviceAdded/onServiceConnected/createStreamServer…`、`Service.onBind/onStartCommand`、`TimedTextFileFormat.toSRT/toASS/…`、OkHttp `Authenticator.authenticate`、`SSLSocketFactory.getDefaultCipherSuites`、Gson `ExclusionStrategy.shouldSkipField`、弹幕 `danmakuShown/drawingFinished` 等 —— 都由框架按接口调用。

**已知但本次未删（等用户决定）**：`app` 自己的工具类里还有约 40 个"零引用"的 public 方法，例如 `StringUtils.isNotNull/isBlank/trimBlanks/getBaseUrl/isJsonType/escapeJavaScriptString`、`ImgUtil.isBase64Image/decodeBase64ToBitmap/spanCountByStyle/initStyle/getStyleDefaultWidth`、`DefaultConfig.getAppVersionCode/getAppVersionName/getFileSuffix/getFilePrefixName`、`OkGoHelper.reloadDns/mapHosts/getItvClient`、`LocalIPAddress.isNetworkAvailable/isIPAddress`、`MD5.encrypt4login`、`BaseActivity.hasPermission/getAssetText`、`Proxy.getM3U8Content`、`SearchHelper.putCheckedSources`、`PlayerHelper.getPlayerExist`、`FileUtils.clearSpiderCacheFiles`、`AppManager.appExit/isActivity`、`SourceViewModel.clearSortCache`、`ApiConfig.clearJarLoader`、`DanmakuApi.hasCustomApi/getDisplayApiUrl/setCustomApi`、`PreloadManagerHolder.hasActivePreload/setDrmSessionManagerProvider`、`PlayerUiState.playLabelVisible`、`RemoteTVBox.setAvalible`、`CustomWebReceiver.REFRESH_SOURCE`、`RequestProcess.KEY_ACTION_*`、`parser/Utils.UaMobile`、`AppTaskExecutor.setDelegate`。

为什么不一并删：`proguard-rules.pro:207` 是 `-keep class com.github.tvbox.osc.** { *; }`（**全量 keep，死代码在 release 里也不会被 shrink 掉，所以清理确实有价值**），但同一份配置说明这些类是"对外保留"的宿主面；第三方 jar 里确实出现过引用宿主 `com.github.tvbox.osc.util.*` 的写法。删 `com.github.tvbox.osc.**` 的 public 方法风险不为零且需要逐个人工判断，按项目「最小化修改」约定留待确认。

**验证**：`:app:installDebug` BUILD SUCCESSFUL 36s，已装机。

### 第二批（未做）：`com.github.tvbox.osc.**` 的 ~40 个零引用 public 方法

见上方清单。风险点：`proguard-rules.pro:207` 是 `-keep class com.github.tvbox.osc.** { *; }`（全量 keep，说明这些类是"对外保留"的宿主面），且第三方 jar 出现过引用宿主 `com.github.tvbox.osc.util.*` 的写法 → 逐个删需人工判断，风险不为零。

### 第一批（2026-09-13 已做）：private/internal + 纯 UI 层

**方法补充**：第一轮只扫了 public/protected，本轮补扫 `private`（Kotlin `private fun/val`、Java `private` 方法），判据 = 名字在全工程只出现 1 次（私有成员本就无法被外部引用，所以"只出现一次"即死）。

**已删（7 项，全部零引用 + 行为等价）**：

| 项 | 位置 | 说明 |
|---|---|---|
| `findEpisodes` | `api/DanmakuApi` | private static 辅助；同文件 `findEpisodeList` 才是被用的那个 |
| `hasCustomApi` | `api/DanmakuApi` | 旧设置页的展示辅助，新设置页直接读 `HawkConfig.DANMU_API` |
| `getDisplayApiUrl` | `api/DanmakuApi` | 同上（新设置页用 `SettingsState.danmuApi`） |
| `playPreSource` | `ui/activity/LivePlayActivity` | 切"上一个源"；兄弟方法 `playNextSource` 仍被超时换源链路调用（第 545 行），本方法是旧"快滑换源"手势的残留（该手势已于 §4.5 定稿移除） |
| `playLabelVisible` | `player/state/PlayerUiState` | 计算属性，全工程无读者；`play_label` 这个 view 在 Compose 化后已不存在（全仓仅剩这一处注释提到它） |
| `clearSortCache(String)` | `viewmodel/SourceViewModel` | 只清单个 key；实际使用的是整清 `clearRuntimeCache()` |

**扫出但"不能删 / 先别删"的三类**（这才是这轮审计的主要价值）：

1. **`@Preview` 不是死代码**：`ui/theme/Theme.kt` 的 `AVBoxThemeLightPreview` / `AVBoxThemeDarkPreview` 被扫描判为"零引用"，但它们由 **Compose 工具链（Android Studio 预览）**消费，删了就没了预览能力 → **保留**。凡是 `@Preview` 标注的函数都会这样误报。
2. **catvod 包里的 private 死方法，本轮不动**：`crawler/JarLoader.requireRecentLoader`（同文件其它方法各自内联了同样的 `loaders.get(recent)` 逻辑，是重构残留）、`crawler/js/JsSpider.createArray`（同族 `createObject/get/set` 都在用，只有它没人用）。**故意不删** —— 这两个文件属 `com.github.catvod.**` 上游面，改动会增加后续同步上游的成本。
3. **两个"断线"而非"死代码"—— 需要用户决策，本轮只报告不删**：
   - **`RemoteTVBox.setAvalible` 是 `HawkConfig.REMOTE_TVBOX` 的唯一写入点，而全工程没人调用它**。链路后果：`getAvalible()` 恒为 null → `getAvalibleActionUrl()` 恒为 "" → `PlayerHelper:267` 的 `RemoteTVBox.run(...)` 恒返回 false，且 `PlayerHelper:220` 的 `playersExist.put(13, …)` 恒为 false ⇒ **13 号"RemoteTVBox 播放器"实际永远不可用**。但**投屏功能本身是好的**：Compose 投屏 sheet（`PlayerSheets.kt:974`）绕过这套机制，直接 `post("http://" + device.id + "/action")` 用扫描到的设备地址。判断：这是 Compose 迁移时丢掉的一次调用（旧 UI 应该会在发现设备后记住 host），不是单纯的残渣 —— 要么把 `setAvalible` 接回"发现设备/投屏成功"处，要么把 `run`/`getAvalible`/`getAvalibleActionUrl`/`REMOTE_TVBOX` 与 `PlayerHelper` 的 13 号条目一起退场。删 setter 一个方法反而会让这条线索消失，故保留。
   - **`LivePlayActivity.mUpdateTimeshiftRun` 从未被 post**：它是"每秒把 `tsPosition` 刷新成当前播放位置"的 Runnable，但全文件没有 `postDelayed(mUpdateTimeshiftRun, …)`。后果：进入回看（`startCatchupReplay` 只在开始时置一次 `tsPosition`）后，**时移条的滑块与"位置/时长"文本不会随播放自动前进**，只有拖动才更新。判断：这是**缺一次接线的小 bug**，不是死代码 —— 要么在 `startCatchupReplay` 里 post、在 `backToLiveFromEpg`/退出时 remove，要么确认不需要自动刷新后删掉。本轮保留待决策。

### 两个「断线」修复（2026-09-13，用户拍板"修复 a 和 b"）

**a) RemoteTVBox host 写入接回**（`PlayerSheets.kt`）：

- **扫描发现时**：`found(viewHost, end)` 回调里，若 `RemoteTVBox.getAvalible() == null` 则 `setAvalible(viewHost)` —— 只在"尚未记住"时写，避免多台 TVBox 时把用户的选择覆盖掉；写入放在 `mainHandler.post {}` 内（回调原本就在扫描线程）。
- **投屏成功时**：`ok == true` 分支里 `setAvalible(device.id)` 覆盖式写入 —— 这是用户显式选中的设备，优先级最高。失败不写（不给 #13 留一个连不上的地址）。
- 两处都紧跟 `PlayerHelper.invalidatePlayersExistInfo()`（新增方法）。
- ⚠️ **`PlayerHelper.getPlayersExistInfo()` 是进程级缓存**：`mPlayersExistInfo == null` 才重算，此前**没有任何重置点**。所以即使接回了写入，不重置缓存的话 13 号选项在本次进程内仍然不会出现 —— 这是修复里最容易漏的一环。
- 顺带确认：`CastDevice.tvbox(host)` 的 `id` 就是 host 本身，与 `getAvalibleActionUrl()` 拼的 `http://<host>/action` 及投屏 POST 的 URL 完全一致，所以存 host（不带 scheme）是对的。

**b) 时移进度 ticker 接线**（`LivePlayActivity.kt`）：

- 新增 `startTimeshiftTicker()`（**先 remove 再 postDelayed 1000ms**）/ `stopTimeshiftTicker()`。
- `startCatchupReplay()` 末尾启动；`backToLiveFromEpg()`、`playChannel()`（`isSHIYI = false` 那处，切台即退出回看）停止；`onDestroy()` 原有的 `mHandler.removeCallbacksAndMessages(null)` 已覆盖销毁路径。
- Runnable 内加 `if (!isSHIYI) return` 兜底：任何漏掉的退出路径最多多跑一拍就自停。
- "先 remove 再 post"是必需的 —— 反复进出回看会叠加多个 ticker（每秒刷新多次）。
- 📌 旁证：活规范 §4.5 早已写明时移条是"**1s 轮询**"，即设计意图如此，只是实现漏了 post —— 这不是新增行为，是补齐既定行为。

**验证**：`:app:installDebug` BUILD SUCCESSFUL 28s，已装机（`lastUpdateTime=2026-09-13 00:35:30`）。真机待验：①投屏 sheet 扫描到 TVBox 后，播放设置里的「RemoteTVBox 播放器」出现；②投屏成功后该选项仍可用且指向所选设备；③回看时滑块与时长文本每秒前进；④退出回看/切台后不再前进。

## 切到"坏源"后首页仍显示旧源（2026-09-13，用户提问驱动的修复）

**用户问题**：「配置管理页面点播分类如果添加并使用了一个不能使用的源，此时回到首页是不是还会显示之前能用的旧源，而不是显示失败或者空白？」—— **核实结论：是的，会**。完整链路：

1. `ConfigManagePage.applyVodSource()` 写 `API_URL = 新源` → `AppBootstrap.retry()`。
2. `loadConfig(false, …)`：新源没有历史缓存（缓存文件名 = `MD5(apiUrl)`）→ 拉取失败 → `callback.error(...)`。**关键：失败路径不会调用 `parseJson()`**，而清场动作 `resetConfigData()` 只在 `parseJson()` 开头执行 ⇒ 单例 `ApiConfig` 里的 `sourceBeanList` / `mHomeSource` / `parseBeanList` **原样保留上一个源的数据**。
3. `MainScreen` 的错误弹窗是**覆盖层**（`MainContent()` 始终在渲染），背后首页照常。
4. `HomeViewModel` 只在 `Boot.Ready` 时 `loadHome()`，对 `Error` 无反应；`sources`/`currentSource` 是 init 期快照 ⇒ 首页既不刷新也不报错，继续显示旧源内容。
5. 点弹窗「取消」= `continueOffline()` → `Boot.Ready` → `loadHome()` 读到 `mHomeSource`（旧源）⇒ **旧源照旧可用**。
6. 而 Hawk 里 `API_URL` 已经是新源 ⇒ **重启后才发现新源不可用**，与运行中表现矛盾。

**修复**：新增 `ApiConfig.invalidateVodConfig()`（`resetConfigData()` + `mHomeSource = null` + `invalidateLiveConfig()`，**不动 Hawk 地址**）与 `AppBootstrap.onApiUrlChanged()`（作废旧配置 → 广播 `TYPE_API_URL_CHANGE` → `retry()`），三处调用点：`ConfigManagePage.applyVodSource()`、`SettingsPage` 接口线路、`ApiConfig.switchApiCollectionIfNeeded()`。

- 为什么"切换前就作废"而不是"失败后回调里再清"：不必给 `AppBootstrap` 加失败回调链路；成功时 `parseJson()` 本来就会重新填充，作废是无害的；失败时首页自然落到 `emptyHome` → `loadHome` 的 `loadingSourceKey = null` 分支 → `onSortResult` 置空态（未配置引导态），与错误弹窗语义一致。
- 地址未变（同源再启用）不走这条链，只 `invalidateLiveConfig()`，避免无谓整页重载。
- 广播 `TYPE_API_URL_CHANGE` 的必要性：`HomeViewModel` 早就在 `init` 里把 `sources`/`currentSource` 快照住了，只清 `ApiConfig` 不会让在屏内容变化 —— 必须让首页立即 `reload()`，否则旧内容会一直摆到下一次 `Boot.Ready`。
- 📌 与 2026-09-11 的修复同源同思路：「原实现删光后保留 API_URL，会出现『订阅列表已空、App 仍用着被删的源』的状态不一致」—— 项目既有决策是**一致性优先于便利性**。
- 已知未处理（既有问题，非本次引入）：作废窗口期内若从历史记录进入详情页，`SourceViewModel.getDetail` 里 `ApiConfig.get().getSource(sourceKey)` 可能返回 null 而 NPE（`int type = sourceBean.getType()` 未判空）。触发需要"切换后加载完成前进详情页"且该源 key 已失效，概率低，留作后续加固。

**验证**：`:app:installDebug` BUILD SUCCESSFUL 30s，已装机。真机待验：①添加一个无效链接并启用 → 首页应显示「配置加载失败」弹窗，背后为未配置引导态/空态而非旧源内容；②点「取消」后首页仍是空态而不是旧源；③把地址改回可用源 → 首页恢复正常。

## 源失效判空加固 + 卡片涟漪对齐参考项目（2026-09-13）

**A. `getSource()` 返回 null 的判空（补上一节留的隐患）**。核查全工程 17 处 `ApiConfig.get().getSource(...)`，绝大多数已有 `?.`/`== null` 保护（`DefaultConfig`、`DetailActivity` 五处、`HistoryPage` 都是安全的），**真正缺判空的是 3 处**：

| 位置 | 原风险 |
|---|---|
| `SourceViewModel.getDetail` | `int type = sourceBean.getType()` 直接解引用 → NPE。改为先判空、`postValue(createEmptyDetail(sourceKey))`（与末尾"未知 type"分支同形状，详情页走空态）；顺手把末尾那段内联构造抽成 `createEmptyDetail()` 复用 |
| `SourceViewModel.getPlayInternal` | 同上 → 改为 `postPlayResult(..., null)`，播放器按"解析失败"处理 |
| `PlayContainer.initPlayerCfg` | `sourceBean.getPlayerType()` NPE，而**该 try 块的 `catch (Throwable)` 是空的** ⇒ 静默跳过后面 `pr/ijk/sc/sp/st/et` **全部**播放器设置，配置只剩半截（症状很隐蔽：播放器选项看似没生效）。改为 `sourceBean == null ? -1 : …`，回退全局播放器设置 |
| `PlayContainer.checkVideoFormat` | `sourceBean.getType()` NPE（有 `catch(Exception)` 兜底，但会走异常控制流）→ 加 `sourceBean != null &&` 前置判断 |

触发场景（同一根因）：切源后加载完成前从历史记录点进旧源条目；或订阅源被删而历史仍在。

**B. 卡片涟漪透明度对齐参考项目**。用户要求"参考 `示例文件/android` 修改点击卡片的激活反馈涟漪"。先摸清参考项目（子代理全仓审计）结论：**它没有可复用的 pressable 组件，也没有任何自定义 `Indication`/`ripple()`/`LocalIndication`** —— 涟漪定制只有一处，在 `Theme.kt` 里**全局**把 M3 默认透明度翻倍，经 `LocalRippleConfiguration` 下发：

```kotlin
val rippleAlpha = RippleAlpha(hoveredAlpha = 2f*0.08f, focusedAlpha = 2f*0.10f,
                              pressedAlpha = 2f*0.10f, draggedAlpha = 2f*0.16f)
CompositionLocalProvider(LocalRippleConfiguration provides RippleConfiguration(rippleAlpha = rippleAlpha))
```

照此在本项目 `AVBoxTheme` 里加了同样的 `rememberRippleConfiguration()` 包装。选全局而非逐卡传 `indication`：`clickable`/`combinedClickable`/`Surface(onClick)`/`ToggleButton` 一次覆盖、风格统一；`PressableCard` 现有的显式 `indication = ripple()` 同样会读到该配置。`RippleConfiguration` 已 deprecated 且官方无替代入口，只能 `@Suppress("DEPRECATION")`（参考项目代码里也留了同样的注释）。

**参考项目其它结论（供后续取舍，本次未改）**：①按压缩放目标只有 **0.94** 一个值（0.96/0.98 不存在），经 `Modifier.scale` + `spring(dampingRatio=0.6f, stiffness=800f)`，且**必须与真实 clickable 组合**涟漪才不丢（`ExpressiveButton` 的写法）；本项目沿用自定的 0.97 与 `graphicsLayer`。②参考项目圆角卡一律 **先 `.clip(shape)` 再 `clickable`**，源码注释写明原因（否则涟漪/长按激活区溢出圆角变矩形）—— 本项目 `PressableCard` 已是该顺序。③参考项目的卡片一律 `elevation = 0.dp` 且按下不变形/不抬升，只有 `ExpressiveButton` 有 `pressedElevation = 1.dp`。④它有 `LocalReducedMotion`，但只被入场动画消费，**按下反馈路径都不读它**。

**验证**：`:app:compileDebugKotlin` + `:app:compileDebugJavaWithJavac` BUILD SUCCESSFUL；`:app:installDebug` BUILD SUCCESSFUL 27s，已装机。真机待验：①首页卡片点击涟漪明显可见（原 10% → 20%）；②切源窗口期从历史点进旧源条目不崩、进空态。

## 宿主契约审计 + slf4j/JS/Guava 修复（2026-09-12 晚；承接当晚的 zxing 崩溃）

**背景**：zxing 崩溃（见 `logs/crash_diagnosis_20260912.md`）暴露了一类系统性问题 —— 宿主必须为动态加载的爬虫 jar 提供运行期类。当晚做了一次系统性审计并把缺口补齐。

**审计方法（可复现，值得复用）**：解析 jar 的 DEX —— 用 `class_defs`（class_idx → type_ids → string_ids）取出 jar **自己定义**的类；用 ASCII 正则 `L...;` 扫常量池取出 jar **引用**的类；再扫宿主 APK 全部 dex 的引用集。三者相减：
`jar 引用 ∧ jar 未定义 ∧ 宿主缺失 = 宿主必须补的类`。
注意两个坑：① DEX 里类型描述符**自带 `L` 前缀与 `;` 后缀**，比对时必须同形态，否则结果全是噪声；② `Add-Type` 定义的 C# 类型不跨 pwsh 进程，解析与比对必须在**同一次调用**里完成。

**审计结果（对真实事故 jar `logs/jars/crash_9ebc36e2.jar`，843 定义类 / 1190 引用类）**：
- jar 依赖宿主提供：okhttp3(17 类)、gson(7)、zxing(4)、quickjs wrapper(5)、宿主 catvod 的 `crawler.Spider`/`SpiderDebug`、平台自带的 `org.xmlpull.v1.XmlPullParser`；
- jar 自带 836 个 spider 类 + 6 个 `parser` 类 + `js/Method`，**不依赖宿主的 `utils.*`/`bean.*`** → fongmi catvod 里那套 `api` 大清单（brotli/guava/sardine/smbj/preference/logger）**本 jar 零引用**，不补；
- 真实缺口：`org.slf4j.ILoggerFactory`、`org.slf4j.impl.StaticLoggerBinder`、`com.whl.quickjs.android.QuickJSLoader$Console`、`com.github.catvod.debug.MainActivity`。
- 另一支 jar `old_77b88823.jar` **不是爬虫 jar**（含 `ftyguard_v7/v8.so` + `ftyshinidie.guard`，仅 36KB dex，`test/MainActivity`），无参考价值。

**修复三项**：
1. ~~**slf4j**：`org.slf4j:slf4j-api:1.7.36` + `slf4j-nop:1.7.36` + `-keep class org.slf4j.**` + `-dontwarn org.slf4j.**`~~ —— **当晚已全部撤回**，见下方「修正」。
2. **JS 全局别名 + `http.js` 模块名**：`assets/js/lib/net.js` 原本只到 fongmi `http.js` 的第 17 行，缺 19-32 行的 `defineGlobalAlias` 块；且 fongmi 的模块名是 `http.js`，本项目只有 `net.js` → JS 源站 `import ... from 'http.js'` 会走 `FileUtils.loadModule` 找不到文件 → `isInvalidModuleContent` → `compileEmptyModule`，**静默变成空模块而非报错**（`JsSpider.java:402-424 / 464-472`、`FileUtils.java:280-305`）。修复：`net.js` 补齐别名块，并新增 `http.js`（两者与 fongmi `http.js` **逐字节一致**，SHA256 `6317D5D4…`）。
3. **Guava keep**：Guava 由 `androidx.media3:media3-common` 传递带入（APK 内 2137 个 `com/google/common` 类），但宿主代码零静态引用 → release 下 R8 会改名/裁剪，jar 引用即崩。已加 `-keep class com.google.common.** { *; }`。

**release 验证（本轮最关键的一步）**：debug 不混淆，契约缺口只在 release 暴露。执行 `assembleDebug` + `assembleRelease` 后审计 **release 产物**（`AVBox_release.apk`，R8 后 5 个 dex / 34337 个类，64.6MB；debug 80.4MB）：
- 契约项全部命中：zxing / slf4j(含 `StaticLoggerBinder`) / Guava / okhttp3 / gson / quickjs / catvod 均在；
- 对真实 jar **无新增缺口**（仍只剩 `QuickJSLoader$Console`(上游共有问题，fongmi 同版本亦然) 与 `catvod/debug/MainActivity`(jar 调试入口)）；
- `mapping.txt` 佐证 keep 生效：`com.google.common.collect.ImmutableList -> com.google.common.collect.ImmutableList`、`org.slf4j.impl.StaticLoggerBinder -> org.slf4j.impl.StaticLoggerBinder` 等均为**同名映射**（未被改名/删除）。

**脱糖（同日补，见 spec §2）**：四个模块开启 `isCoreLibraryDesugaringEnabled` + `desugar_jdk_libs_nio:2.1.5`。⚠️ 只覆盖**编译进 APK 的代码**：jar 是预编译 dex 不过 D8，其 `java.time` 引用原样保留，API 24/25 仍会 `NoClassDefFoundError` —— 脱糖**修不了 jar**。

**未完成**：真机冒烟 —— 装包验证进行到一半时设备从 USB 断开（`adb devices` 为空），`install -r` 未执行；下次接上设备后补 `assembleDebug` 装机 + 启动 40s 无崩溃确认。

### 修正（2026-09-12 深夜）：slf4j 是误判，已撤回

**触发**：当晚播放中点开详情页后日志出现（被 catch 住、走 `System.err`，未崩应用）：

```
IncompatibleClassChangeError: Class 'org.slf4j.helpers.NOPLoggerFactory' does not implement
interface 'com.github.catvod.spider.merge.Pu' in call to
'com.github.catvod.spider.merge.a9 com.github.catvod.spider.merge.Pu.yq(java.lang.String)'
	at com.github.catvod.spider.merge.mu.tF(Unknown Source:1)
	at com.github.catvod.spider.merge.SC.<init>(Unknown Source:5)
	at com.github.catvod.spider.merge.q3.yq(Unknown Source:40)
```

**机理**：`NOPLoggerFactory` 只可能来自我加的 `slf4j-nop`，因果确定。爬虫 jar 把 slf4j-api **shade 进了自己的混淆包**（`merge.mu` = LoggerFactory、`merge.Pu` = ILoggerFactory），运行期按 slf4j 老规矩去宿主找 `org.slf4j.impl.StaticLoggerBinder`：

- 宿主**没有**绑定（改动前）→ 查找失败 → jar 内部回落 NOP → 静默降级，正常；
- 宿主**提供**绑定（改动后）→ 查找成功 → 拿到实现「未混淆 `org.slf4j.ILoggerFactory`」的 `NOPLoggerFactory`，而 jar 要的是自己那套 `merge.Pu` → **ICCE**。

**处置**：移除 `implementation(libs.slf4j.*)` 两行、`-keep/-dontwarn org.slf4j.**`、version catalog 的 `slf4j` 条目；重建后 APK 内 `org/slf4j` 引用数 = **0**（已核对）。version catalog 与《proguard-rules.pro》均留下了「不要加」的说明。

**教训（写进 spec §6.3）**：**常量池引用 ≠ 宿主应当提供**。zxing 是「jar 引用了、宿主没给 → 崩」，slf4j 是「jar 引用了、宿主给了 → 崩」——同一个审计方法得出的两类相反结论，区别只在 jar 是否把该库 shade 进了自己的包。这类判断静态审计看不出来，**必须跑起来才能证伪**；换句话说，第一版审计的「宿主契约清单」应视为**候选列表**，逐项都要过一遍真机。

## 首页下拉刷新(2026-09-12,用户要求:「下拉时出现圆形加载指示器,松手后刷新,指示器大小48dp」)

- **组件**:material3 1.5.0-alpha23 官方 `Modifier.pullToRefresh`(等价 `PullToRefreshBox`,因不想给 LazyColumn 整块体加一层缩进,挂在 LazyColumn 的 modifier 上,指示器作为同 BoxScope 兄弟节点)+ `rememberPullToRefreshState()`;阈值/行程均为 M3 默认 80dp(手指需下拉 160dp 触发,`DragMultiplier=0.5`)。
- **指示器**(`HomePage.HomePullRefreshIndicator`,文件中 private;2026-09-12 二轮按用户澄清重做):**进 App 引导页(BootLoading)同款 M3 expressive `ContainedLoadingIndicator`,48dp**——**全项目圆形加载指示器(播放器页面除外)统一用它**(见 §6)。滑入/隐藏/裁剪机制复用 `PullToRefreshDefaults.IndicatorBox`,但 `shape = RectangleShape` + `containerColor = Color.Transparent` + `elevation = 0.dp` → 不产生圆底徽章与阴影,形态与引导页一致;下拉态 `progress = { state.distanceFraction }`(随牵引距离形变,>1 时整体旋转——照抄 M3 官方 `PullToRefreshDefaults.LoadingIndicator` 的 drawWithContent 实现,注意 `rotate {}` 块内必须 `this@drawWithContent.drawContent()`,隐式调用编译不过)→ 刷新态转不定态圈,`Crossfade` 过渡。⚠️ 未用 M3 自带 `Indicator`(16dp 箭头,非 md3e)与 expressive `PullToRefreshDefaults.LoadingIndicator`(它把 `containerColor` 同时喂给外层圆底徽章和内层加载器,想去掉徽章就得连加载器自己的容器底一起去掉,拿不到引导页观感)。
- **顶栏遮挡(关键坑)**:顶栏是透明覆盖层且由 Scaffold 画在内容之上,指示器若停在内容顶部(y=0)会被左上角订阅源胶囊完全盖住 → 指示器整体 `offset(y = topPadding)`(topPadding = `AppTopBarScaffold` 回调的顶栏实测总高),从顶栏下沿滑出。指示器无 pointerInput/clickable,不拦截列表触摸。
- **松手刷新**:`onRefresh` → `pullRefreshing = true` + `HomeViewModel.reload()`(清 `sortCache`/`extendCache` 后整页重载;直接用 `loadHome()` 会命中 sortCache 导致「刷新了但推荐没变」)。
- **完成判定**:`rec.state != Loading && partitions.none { it.state == Loading }`(看门狗 45s 转 Error 同样解锁),`LaunchedEffect(pullRefreshing, homeReloaded)` 复位;**未配置接口的引导态不启用**下拉刷新。
- **验证**:`:app:compileDebugKotlin -q` 退出码 0;read_lints 无诊断;真机观感待用户确认(圆容器 + 24dp 环的组合、从顶栏下沿滑入的位置、"下拉填充→松手转圈"的过渡)。

## 隧道模式 + AAC 优先(2026-09-11;二轮按 fongmi 实现重做,一轮的音频 offload 方案废弃)

- **语义修正(关键)**:fongmi/OK影视 的「隧道模式」= **MediaCodec tunneled playback**(视频+音频经硬件 AV 同步直通渲染,`MediaFormat.KEY_TUNNELED_PLAYBACK`),入口 = `DefaultTrackSelector.Parameters.Builder.setTunnelingEnabled(boolean)` —— **不是音频 offload**。一轮实现的 `TrackSelectionParameters.AudioOffloadPreferences`(audio offload/DSP 直通)在实测机被系统 `AudioManager.getPlaybackOffloadSupport()=0` 挡死,永不生效,已废弃;`isOffloadedPlaybackSupported=true` 与 `getPlaybackOffloadSupport=0` 并存(vivo 的 direct profile 与 audio policy 判定不一致)。
- **fongmi 源码考古**(`示例文件/TV-fongmi`):`setting/DecodeSetting.java`(`isTunnel`/`putTunnel`,键 `decode_tunnel`)+ `player/exo/ExoUtil.java#buildTrackSelector`(`builder.setTunnelingEnabled(DecodeSetting.isTunnelingEnabled())` + `setPreferredAudioMimeType(AAC)`)。联动规则:`putTunnel(true)` → 强制 `putRender(RENDER_SURFACE)`;`putRender(TextureView)` → 自动 `putTunnel(false)`;播放判定 `isTunnelingEnabled() = isTunnel() && render==SURFACE`(**隧道要求视频直出 Surface,TextureView 走 GPU 合成不可隧道**);非 EXO 内核隐藏两开关(mpv 时);隧道开启时禁用画质/音质设置(strings: `error_video/audio_effect_tunnel`)。
- **落地(本项目)**:①键不变 `PLAY_TUNNEL`/`PLAY_PREFER_AAC`(默认 false);②`player/ExoPlayer#applyPlaybackParameters()`(`initPlayer` super 后)= `trackSelector.buildUponParameters()` → `setTunnelingEnabled(tunnel && render==SurfaceView)` + 可选 `setPreferredAudioMimeTypes(AAC)` → `trackSelector.setParameters(...)`(**不再走** `mInternalPlayer.setTrackSelectionParameters`,避免重置 tunneling 扩展字段);③设置页双向联动:开隧道且渲染=TextureView → 自动切 SurfaceView;渲染切 TextureView → 自动关隧道。
- **自动降级**:①内核自动切 IJK(rtmp 强制/自动重试)→ 本类不实例化;②渲染非 SurfaceView → 参数不启用;③设备 codec 不支持 FEATURE_TunneledPlayback → media3 静默回退(`isTunnelingEnabled()=false`),无副作用。
- **fongmi 未搬入**:非 EXO 内核隐藏开关(本项目保持独立开关常显)、隧道时禁画质/音质设置(本项目无此功能)、DecodeTrackSelector(依赖 fongmi 对 media3 的源码魔改,不需要)。
- **生效时机**:下次开始播放(播放器重建时读 Hawk)。
- **验证(已完成,2026-09-11 真机)**:参数下发成功(`prefs tunnel=true, surfaceRender=true, preferAac=true`);本机视频轨 video/avc(avc1.640028)的 codec 不支持 FEATURE_TunneledPlayback → `tunnelingEnabled=false` = 预期自动降级,链路(开关 → 选轨 → RendererConfiguration)工作正常。排查期临时落盘日志(`ExoPlayer` 内 `avbox_tunnel.log` 通道)已按用户要求移除。

## 配置管理页(2026-09-11,用户要求;同日二轮:开关切换 + 长按删除 + 成为源管理唯一入口)

- **定位**:全 App **唯一的源添加/管理入口**(二轮用户要求原文:「将设置页面的接口配置接口历史都删除了,此后添加和管理源的入口只有配置管理」)。
- **入口**:设置 tab「配置与数据」分组首位「配置管理」行 → `ConfigManageActivity`(壳同 `ThemeSettingsActivity`;页面 `ui/page/ConfigManagePage.kt`,Manifest portrait);首页两处旧入口同步改跳此页 = 引导态按钮(文案「添加订阅」)、订阅源 sheet 末组行(「配置管理」)。
- **页面**:无边框顶栏 = 返回钮 + 大标题「配置管理」+ 右上角控件(常态 = 「添加订阅」(40dp 圆形、surfaceBright 圆底、图标 = `.tubiao/添加订阅.svg` 转换的 `ic_subscribe_add.xml`);管理模式 = 「删除」(40dp 圆钮,复用历史页 `ManageActionIcon`),两者互斥);订阅源列表 = **28dp 圆角卡片**(色 `cardContainer`,距屏幕边缘 16dp、卡间距 12dp;卡内 = 名字 titleMedium + 链接 bodyMedium onSurfaceVariant 单行省略);空态 = `LoadStateBox`「暂无订阅」。
- **开关切换(二轮用户定稿)**:卡片右侧 `Switch` = 该源是否为当前接口(`activeUrl` 与 `Hawk API_URL` 比对);打开 = 切换(写 API_URL/LIVE_API_URL + 接口&直播历史 + 非线路历史清线路 + `AppBootstrap.retry()`,Toast「已切换到:名」);关闭不动作(必须有一个源在用);整卡点击 = 同开关。**列表首个订阅源添加后自动启用**(覆盖新装/清空后场景,免一步开关)。
- **排序(四轮用户定稿)**:**正在使用的源恒置顶**(渲染层排序 `orderedItems`,存储顺序不变),其余按添加顺序 → **新添加的源追加到列表末尾 = 显示在下方**(二轮时误插到最前)。
- **长按删除(二轮 + 四轮用户定稿)**:长按卡片 → 进入管理模式并选中该卡(卡右侧开关转 `Checkbox`,顶栏「添加订阅」转「删除」);管理模式整卡点击 = 切换选中;取消全部选中 / 删除完成自动退出。**正在使用的源不可删除** —— 勾选框 `enabled=false`,长按/点选 Toast「正在使用的源不能删除」,`deleteSelected()` 再过滤一层兜底;仅当列表被删空(边界:激活源不在列表中,如旧版本升级遗留)才 `ApiConfig.clearConfig()` + `AppBootstrap.retry()` 回引导态(**2026-09-11 修复**:原实现删光后保留 `API_URL`,会出现「订阅列表已空、App 仍用着被删的源」的状态不一致,重启也照样生效)。
- **添加订阅 dialog**:Material3 `AlertDialog`(标题「添加订阅」+ 两行 `OutlinedTextField`(名字 / 链接,label 常显)+ 右下角「保存」;链接为空时保存钮禁用)。同链接重复保存 = 更新名字,位置不变。
- **数据**:Hawk `HawkConfig.SUBSCRIBE_LIST` = `"subscribe_list"`(`ArrayList<String>`,每项 `名字\t链接`,分隔符约定同 HistoryHelper 的 `API_LINE_SPLIT`)。
- **连带删除(旧入口整链)**:设置页「接口配置」「接口历史」两行 + `ApiHistorySheet`/`ApiConfigSheet`/`ApiSheetHost`/`ApiConfigSheetHost`(`ui/dialog/ApiSheets.kt` 整文件)+ `Jump.showApiDialog()` + `MainScreen` 的 `ApiConfigSheetHost()` 挂载。保留 `HawkConfig.API_HISTORY`/`LIVE_API_HISTORY` 数据键(仍被 `ApiConfig` 仓源线路逻辑与 `LivePlayActivity` 直播设置「配置切换」消费)。
- **本地文件选择(2026-09-11 三轮引入 → 五轮改系统 SAF)**:添加订阅 dialog **标题右上角**「从本地选择」控件(40dp 圆形 surfaceBright 圆底 + `ic_file_choose.xml`(源 `.tubiao/文件选择.svg`,文件夹图标))。**五轮用户要求「把这个页面删除,改为 SAF」**:自绘 `LocalFileActivity` 整页删除 + Manifest 声明删除;`LocalConfigHelper` 重写为 SAF 链 —— 调用页注册 `ActivityResultContracts.OpenDocument()`(MIME `*/*`),`startLocalConfig(launcher, onResult)` 设 pending 后 `launcher.launch(...)`,回调经 `handleLocalConfigResult(activity, uri)` 把 Uri 转 `clan://` 接口地址**回填到链接输入框**(不直接保存)。转换规则 `localConfigToApi`:有存储权限且能取到真实路径 → 直接引用原文件;否则复制到 App 外置缓存 `config/`(SAF 自带读取授权,**无需存储权限**,故原内联 `PermissionHelper.requestStorage` 申请已删)。`MainActivity` 里专为旧 sheet 挂的 `localConfigLauncher`/`launchLocalConfig` 此前已删。
- **数据层新增(2026-09-11)**:`ApiConfig.clearConfig()`(public)= `resetConfigData()` + `mHomeSource = null` + 清 `API_URL`/`LIVE_API_URL`/`HOME_API` + `HistoryHelper.clearApiLineList()`;供「订阅列表被删空」回引导态使用(激活源不可删后,该路径仅剩「激活源不在列表中」的边界情形)。安全性依据:空 `API_URL` 时 `loadConfig` 直接 `callback.error("-1")`(AppBootstrap 按「取消等待、离线继续」处理 → Ready),`getHomeSourceBean()` 有 `emptyHome` 兜底,`HomePage` 以 `sources.isEmpty()` 进引导态。
- **验证**:`:app:compileDebugKotlin -q` 退出码 0;`installDebug` BUILD SUCCESSFUL 26s 已装机(V2425A);真机待验:开关切换与全局刷新、**使用中的源置顶**、**新加源排在下方**、**使用中的源不可删**(长按/点选提示)、长按删除、添加首个源自动启用、**SAF 选择器选中文件回填链接**。

## 主题设置页(2026-09-11,用户要求"照搬示例文件/android 主题设置页的 UI、功能与图标")

- **来源**:`示例文件/android` 的 `feature/settings/.../ui/theme/`(ThemeSettingsScreen / ThemeSections / ThemePresetSeeds / ColorPicker)+ `core/designsystem/.../theme/`(ThemeState / ThemeConfig / Theme.kt)+ `core/ui` widgets(CapsuleSegmentedButton / IconContainer / StatusBarScrim / segmentedItemShape)。
- **项目适配(本项目无 Hilt / 无 MVI / 无 DataStore)**:
  - 主题状态 = 单例 `object AppThemeState`(Hawk 持久化 4 个键 `THEME_SOURCE` / `THEME_MODE` / `THEME_SEED` / `THEME_PALETTE_STYLE`;`mutableStateOf` 向全 App 广播)。**不建 ViewModel**:主题是进程级状态,页面只做"读状态 + 下发 intent",加 VM 只是转发(与 MainScreen 直读 AppBootstrap 同风格)。
  - 新增依赖 `com.materialkolor:material-kolor:5.0.1`(版本目录键 `materialKolor`,与示例项目同版本、本地 Gradle 缓存已有),用于 `PaletteStyle` 与 `dynamicColorScheme`(种子色 → 整套 M3 配色)。缓存策略同示例:主配色 `(seed,isDark,style)` 与色卡预览 `(seed,style)` 各一个 `ConcurrentHashMap`。
  - 页面 = 新 `ThemeSettingsActivity` + `ui/page/ThemeSettingsPage.kt`(走 Activity 跳转,符合 §2「不用 navigation-compose」);入口 = 设置 tab **首个分组**「主题设置」整行,值摘要 = 取色来源 · 深浅模式。
  - 新增组件:`ui/components/CapsuleSegmentedButton.kt`(胶囊分段选择器:ToggleButton + connectedShapes + 按下弹簧回弹)、`ui/components/ThemeColorPickerSheet.kt`(HSV 色轮取色器,装在既有 `AVBoxBottomSheet` 内)、`ui/components/EdgeToEdgeTopBar.kt` 追加 `TopBarActionBox`(40dp 圆形返回钮,供二级页复用)。
  - 图标(`app/src/main/res/drawable/`,逐字取自示例项目):`ic_color_palette` / `ic_brightness_auto` / `ic_light_mode` / `ic_dark_mode`。
- **状态栏/导航栏图标归属(关键设计)**:`AVBoxTheme` 新增 `manageStatusBarIcons: Boolean = true` —— 默认按**解析后的应用主题**决定图标深浅(深浅模式覆盖系统时也不会出现"深色图标压在深色栏上"),同时断言状态栏与导航栏;纯黑状态栏页面(详情页 / 直播页 / 播放器覆盖层 `ComposeVideoController`)传 `false`,继续由各自 Activity 恒白断言。`MainActivity.applyStatusBarAppearance()` 同步改为按 `AppThemeState.isDark(系统深浅)` 取值。
- **与示例的差异(未做项)**:示例在 `Application.onCreate` 预加载全部 8 色 × 9 风格的预览配色;本实现改为**按需计算 + 缓存**(色卡在 `produceState` 里走 Default 调度器),首帧可能以当前配色占位一瞬。
- **补丁①(2026-09-11,装机反馈截图:首个卡片离顶栏过近)**:顶部占位原照设置 tab 页规则给 `topBarHeight - 12dp`(设置页首个分组如此),结果「主题颜色」卡片几乎贴着「主题设置」标题。改为**二级页规则** `topBarHeight - 8dp + 28dp`(顶栏内容下沿 + 首卡间距 28dp),与栏目页(`topPadding + 28dp`)/ 搜索页 / 历史收藏一致。已 installDebug 装机。
- **验证**:`:app:compileDebugKotlin`、`:app:assembleDebug` 均 BUILD SUCCESSFUL;真机待验(主题切换即时全局生效、色卡预览、取色器、深浅模式覆盖系统时的状态栏图标)。

## 顶部应用栏无边框化(2026-09-11,三项决策经结构化提问确认)

- **需求**:顶部应用栏改无边框、内容延伸到状态栏、带"渐变模糊",参考 `示例文件/android`。**用户决策**:①范围 = 4 个 tab(首页/历史/收藏/设置)+ 二级页(搜索/栏目/本地文件);②顶栏**随滚动滚走**(照搬示例项目 `exitUntilCollapsed` 语义);③"渐变模糊" = **渐变遮罩**(示例项目顶栏的实际做法,非真模糊 —— 真模糊只用在示例项目底部导航条,依赖 kyant backdrop 库,零新增依赖优先)。
- **参考实现要点**(`示例文件/android`):顶栏容器透明(`containerColor/scrolledContainerColor = Transparent`)+ `Scaffold(contentWindowInsets = WindowInsets(0,0,0,0))` + 顶栏 `Modifier.windowInsetsPadding(statusBars)` + 内容 `contentPadding.top = padding.calculateTopPadding()` + `StatusBarScrim`(状态栏高 ×1.2,0.95→0.6→透明三档)。
- **本项目落地**:
  - 新组件 `ui/components/EdgeToEdgeTopBar.kt`:`rememberScrollAwayTopBarBehavior()`(M3 `TopAppBarDefaults.exitUntilCollapsedScrollBehavior`,需 `ExperimentalMaterial3Api`)、`rememberTopBarHeight(contentHeight = TopBarContentHeight/56dp)`(状态栏 inset + 顶栏内容高)、`ScrollAwayTopBar`(状态栏 padding + `onSizeChanged` 设 `state.heightOffsetLimit = -实测高` + `offset{}` 布局期读偏移,不触发重组)、`TopScrim`(渐变遮罩;无 pointerInput 不拦截触摸)。
  - 页面统一结构:`Box(Modifier.fillMaxSize().nestedScroll(behavior.nestedScrollConnection)) { 滚动内容(contentPadding.top = topBarHeight + 原顶部留白); TopScrim(); ScrollAwayTopBar { 顶栏行(56dp / 水平 16dp) } }`。
  - 接入:`MainScreen`(Scaffold `contentWindowInsets` 清零;底栏与手势条由 NavigationBar 自身承担)、`HomePage`(源胶囊 + 搜索钮作顶栏;直播 FAB 保持右下)、`HistoryPage`/`CollectPage`(大标题 + 管理控件作顶栏;加载/空态补 `padding(top = topBarHeight)` 保持居中观感)、`SettingsPage`(标题从滚动 Column 移入顶栏,内容首位插 `Spacer(topBarHeight)`,分组间距 28dp 不变)、`SearchActivity`(返回 + 搜索框作顶栏;**波浪线进度条与结果源筛选 chips 由固定改为 LazyColumn 首项**,随列表滚走)、`PartitionListActivity`(`VideoGrid` 新增 `topPadding: Dp` 参数,首卡间距 = topBarHeight + 28dp;加载/空态同上)、`LocalFileActivity`(标题 + 当前路径两行作顶栏,内容高 64dp;路径从"紧贴标题下方"改为顶栏第二行)。
  - 配套 `MainActivity.applyStatusBarAppearance()`:状态栏图标按主题深浅取反(原恒深色)—— 内容延伸到状态栏后顶栏区域即页面色,深色主题须用白色图标;init / onResume 反复断言(系统会按主题重置)。
  - **不在本次范围**:详情页 / 直播页(纯黑状态栏 + 播放器,属既有补丁⑤⑧设计)、播放器视频覆盖层。
- **坑与注意**:①顶栏滚走依赖 nestedScroll 上报 —— 内容滚动容器必须位于挂了 `nestedScrollConnection` 的容器之内(`verticalScroll` / LazyColumn / LazyVerticalGrid 均可);②`heightOffsetLimit` 必须由顶栏实测高度设置,否则 M3 默认 0 = 顶栏不动;③**`onSizeChanged` 必须排在 `windowInsetsPadding(statusBars)` 之外(更外层)** —— inset 高度由 `windowInsetsPadding` 在本层叠加,写在其内层只能测到「内容高」,顶栏最多上移内容高、残留状态栏那一段压在状态栏上滚不干净;④`onSizeChanged` 写出前先判等,避免无限重组;⑤搜索页结果态原"固定进度条/chips"需移入列表,否则内容无法延伸到状态栏。
- **补丁①(2026-09-11,装机反馈截图)**:修复「顶栏滚不干净、标题压在状态栏上」(现象 = 设置页滚动后「设置」标题停在状态栏那一条上,对照示例项目应为完全滚出)—— 即上述坑③,`ScrollAwayTopBar` 的 `onSizeChanged` 由 `windowInsetsPadding` 内层移到外层,收起上限由「内容高 56dp」修正为「状态栏 + 内容高」;已重新装机。
- **全站审查 + 补丁⑤(2026-09-11,用户要求"审查是否还有需要修正的页面")**:
  - 留白核对(首项距屏幕顶 = 状态栏 + X):首页 64(改造前 64 ✓)、历史/收藏 76(76 ✓)、设置 72(68,+4)、搜索未搜索 76(68,+8)、搜索结果 60/波浪线(52,+8)、栏目 76(68,+8)、本地文件 68(68 ✓);差异均来自"顶栏行 56dp 内内容垂直居中"(内容下移);≤8dp,判定可接受,不再逐页微调。
  - **发现并修复|顶栏记账脱节**:`ScrollAwayTopBarState` 的 `scrolledPx` 是"内容累计滚动量"记账,**内容被程序整体替换**(切源 / 换目录 / 换筛选 / 新搜索)时列表位置重置到顶部,记账仍是旧值 → 顶栏停在屏幕外而内容已在顶部。修复 = ①新增 `reset()`(公开),在这 4 个场景显式调用(`HomePage` 切源、`LocalFileActivity` `LaunchedEffect(currentDir)`、`SearchActivity.submit()`、`PartitionListActivity` 筛选确认;⚠️ 局部函数 `submit` 只能捕获**先声明**的变量,`val scrollBehavior` 须移到 `submit` 之前);②`onPostScroll` 加**边界自愈**:`consumed.y == 0 && available.y > 0`(列表已无法向下滚 = 在顶部)且记账非零 → 归零。
  - 其余核对项(加载/空态 `padding(top = topBarHeight)` 居中、滚出上限 = 实测顶栏总高、底部 padding、FAB、sheet 覆盖层)均正常。
- **补丁④(2026-09-11,装机反馈:搜索结果页留白过大)**:现象 = 搜索框下方到进度条/chips/结果分区之间空一大块。根因 = ① 结果态 `contentPadding.top` 多给了 16dp(该 16dp 原是「列表首项与上方固定 chips 的间距」,chips 移入列表后由 `spacedBy(24dp)` 承担);② 进度条与筛选 chips 由固定布局改成两个列表 item 后,被 `spacedBy(24dp)` 额外拉开(原为紧邻,间距仅 12dp 内边距)。修复 = `contentPadding.top = topBarHeight - 8dp`(不加 16dp);**进度条 + chips 合并为一个前导 item**(`key = "search_leading"`,内部 Column 保持原 12dp/0dp 内边距关系),同时避免空态下多出一个空 item。
- **补丁③(2026-09-11,装机反馈:首项卡片离顶部过远)**:现象 = 设置页首个分组被推远(比改造前多约 16dp)。根因 = 顶栏行统一 56dp、行内内容**垂直居中**会产生下行余量(标题 32dp → 12dp;40dp 控件 → 8dp),而内容留白按「整行高度」给,余量被重复计入。修复 = 内容留白改为「顶栏内容下沿」(整行高度 - 行内居中余量)+ 原留白:设置页 `topBarHeight - 12dp`、历史/收藏 `topBarHeight - 8dp + 28dp`、搜索页 `topBarHeight - 8dp(+16dp)`、栏目页 `topPadding = topBarHeight - 8dp`;首页(行内 8+40+8 显式 padding)与本地文件页(64dp 行内 60dp 内容)视觉本就与改造前一致,不动。⚠️ 顶栏整行高度仅用于滚动滚出上限(`ScrollAwayTopBar` 实测),内容留白需按「内容下沿」计算。
- **补丁②(2026-09-11,装机反馈:轻滑一下顶栏就整体跑掉)**:现象 = 内容只滚了十几 dp,顶栏已完全消失(顶栏比内容先跑);对照示例项目应为内容滚多少顶栏移多少。根因 = M3 `TopAppBarScrollBehavior` 在 **fling 结束后按 velocity 吸附**(把 `heightOffset` 动画到完全收起/完全展开,javap 反编译 `ExitUntilCollapsedScrollBehavior$nestedScrollConnection$1` 确认其 `onPostFling` 读 velocity 后写 `setHeightOffset`),不是「跟随内容滚动量」。**修复 = 弃用 M3 behavior,改为自实现 `ScrollAwayTopBarState`**(`ui/components/EdgeToEdgeTopBar.kt`):`nestedScrollConnection.onPostScroll` 只读取 `consumed.y` 累计"内容净滚动量"(`scrolledPx`,向上滚为正),`offsetPx = (-scrolledPx).coerceIn(-高度, 0)`,**不消费滚动、无吸附**;`rememberScrollAwayTopBarBehavior()` 更名为 `rememberScrollAwayTopBarState()`。顶栏位置因此严格由内容滚动位置决定,滚过顶栏高度后再回滚也能正确还原。已重新装机。
- **验证**:`:app:compileDebugKotlin -q` 退出码 0;`installDebug` BUILD SUCCESSFUL 26s 已装机(V2425A)。真机待验:①4 tab 顶栏随滚动滚走/回滚复位;②内容穿过顶部时在状态栏区域渐隐;③搜索页进度条/chips 随结果列表滚动;④深色主题下状态栏图标为白色。

## 顶栏重做:M3 官方方案(2026-09-11 晚,自研记账两轮失联后用户拍板照示例)
- **背景**:自研 `ScrollAwayTopBarState`(补丁②引入的 1:1 增量记账)产生两类装机 bug:①列表在顶部而顶栏 offset 残留 ≈ 状态栏高(标题停进状态栏区,盖住时钟);②列表滚到中间而顶栏完全没跟随(标题叠在内容上)。方向相反、概率出现——增量记账与列表真实位置是两套状态,程序性列表复位(不经 nested scroll)必然失联;旧自愈依赖用户手势、顶部哨兵(`topDetector` + snapshotFlow)也只覆盖"列表在顶"单一形态。**弃用自研,逐字照 `示例文件/android` 的 SettingsScreen/ThemeSettingsScreen 重做。**
- **新组件** `AppTopBarScaffold`(`EdgeToEdgeTopBar.kt`,自研四符号全删):`exitUntilCollapsedScrollBehavior` + `Scaffold(nestedScroll, contentWindowInsets=0, containerColor 可传)` + `TopAppBar(windowInsets=0、statusBars padding、transparent)` + 内置 `TopScrim`;content 回调 `(topPadding, bottomPadding)` 且为 `BoxScope`(首页 FAB align 用)。`ScrollAwayTopBar`/`rememberTopBarHeight`/`rememberScrollAwayTopBarState` 不复存在;`TopScrim`/`TopBarActionBox` 保留。
- **8 页迁移**:设置(纯标题)、主题设置/配置管理/栏目(返回钮;配置管理右上「添加订阅/删除」、栏目筛选钮走 actions 槽;栏目 containerColor=surfaceContainer 遮旧窗口背景)、历史/收藏(标题+管理钮 actions)、首页(源胶囊行进 titleContent、搜索圆钮进 actions、FAB 留 content 内 align)、搜索(SearchField 进 titleContent、返回钮 navigationIcon)。各页原显式 `scrollBehavior.reset()`(切源/新搜索/换筛选)删除——M3 behavior 官方管理无需手工归位。VideoGrid 的 scrollBehavior 参数随之删除。
- **留白换算**:`topBarHeight` → content 回调 `topPadding`,相对差值不变(设置 -12、历史/收藏/配置/主题 -8+28、首页 +8、搜索/栏目 -8);M3 TopAppBar 高 64dp(自研 56),整体留白 +8dp,与示例一致。
- **行为变化**:fling 吸附回归(当初补丁②否掉的"轻滑顶栏先跑"是 M3 官方行为,示例同款;用户为根除错位接受)。加载/空态 `padding(top = topPad)`、sheet 覆盖层、深色状态栏图标断言均不变。
- **验证**:`compileDebugKotlin` 退出码 0;自研符号全工程 0 引用;read_lints 9 个文件无诊断;`installDebug` BUILD SUCCESSFUL 37s 已装机。真机待验:①滚动跟手与吸附观感;②两类错位是否根除;③各页留白/64dp 顶栏视觉;④首页胶囊/搜索钮、历史收藏管理钮、配置管理/栏目右上钮位置。

## 选集网格两位集数溢出修复(2026-09-11,装机反馈;两轮定位后定稿)

  - **现象**:详情页「选集 → 全部」弹窗里,`第10集 / 第20集 / 第22~26集` 这些格子的文字**横向来回滚动**,停在中间帧时显示成 `)集 第`、`0集 身` 等错位字符;第 1~9 集、第 11~19 集不滚。用户补充"只滚三次,之后就和别的集数一样完整显示"。
  - **实测数据(用户截图逐像素量取;1260×2800 @ density 560 → 1dp = 3.5px)**:格中心间距 293.5px → 4 列格宽 **266px = 76dp**;文字墨迹宽 `第1集` 116px=33.1dp、`第9集` 123px=35.1dp、`第10集`~`第26集` **144~147px = 41~42dp**。
  - **根因(第一轮判断有误,此处为更正结论)**:`DetailActivity` 选集网格采用 `GridCells.Fixed(4)`,label 上挂了 `Modifier.fillMaxWidth().basicMarquee()`。Material3 的 chip 内边距为 `FilterChipDefaults.ContentPadding = PaddingValues(horizontal = 8.dp)`(即左右各 8dp),故两位集数可用宽度约 **44dp** 而文字需 **41~42dp** —— **确实溢出约 2~3dp**。因此 `basicMarquee` 并非"误触发":它正确检测到了这 2~3dp 的溢出,只是以"横向滚动 + 滚满 `repeatCount`(默认 3)轮后停在中间帧"的形式表现,看起来像乱滚(用户观察到的"滚三次就停"即此)。⚠️ 第一轮结论曾误判为"文字远未超宽、属跑马灯误触发"(起因是把截图中一段非 chip 的像素跨度当成了 chip 宽度),改用省略号后立刻暴露成 `第2…`,才定位到真实原因。
  - **修复(用户选定"折中"方案)**:①label 字号 `14sp → 13sp`(两位集数需宽降至约 39dp);②`contentPadding = PaddingValues(horizontal = 6.dp)`(相对默认 8dp 多出 4dp);合计余量约 9dp,两位集数完整显示;③**去掉 `basicMarquee()`,改用 `overflow = TextOverflow.Ellipsis`**(并删除已无引用的 import `androidx.compose.foundation.basicMarquee`),避免"差一点点就整行乱滚"的观感;超长文件名(如 `xxx.2024.EP01.1080p.mkv`)仍单行省略号截断。
  - **教训**:①`basicMarquee` 不适合"仅差几 dp"的边界场景 —— 溢出量很小时滚动幅度极小,观感是"文字在抖/错位",不如省略号诚实稳定;跑马灯只应用于"确实超宽且必须看全"的场景。②排查像素问题**必须先确证所量跨度的归属**(chip?格?文字?),再据此下结论 —— 本轮一次误判即源于此。

## 快搜功能删除(2026-09-09,用户要求)

- **整链删除**:`FastSearchActivity.kt`/`FastSearchEngine.kt` 两文件、Manifest 声明、SearchActivity 页内「快搜」入口按钮、设置页「快搜」开关(SettingsState.fastSearchMode/FAST_SEARCH_MODE 读写)、Jump.kt 的 FAST_SEARCH_MODE 分支(jumpToDetail 源缺失回退与 jumpToSearch 均固定走 SearchActivity)、HawkConfig.FAST_SEARCH_MODE 常量。
- **死代码连带清理**:SourceViewModel 的 quickSearchResult/detailFallbackSearchResult 两个 LiveData 及 getQuickSearch/getDetailFallbackSearch(无调用方无观察者)、xml()/json()/postEmptySearchResult/postSearchResult 中对应分支、两参 postEmptySearchResult 重载;RefreshEvent 的 TYPE_QUICK_SEARCH/SELECT/WORD/WORD_CHANGE/RESULT 五个零引用常量;SearchHelper.splitWords(仅快搜分词使用)。
- **DetailActivity 兜底候选预填通道删除**:EXTRA_DETAIL_FALLBACK_CANDIDATES 常量与 initFromIntent 的 bundle 读取、cacheFallbackCandidates(仅快搜点击结果携带候选时使用);⚠️ 详情页换源/相关推荐的**自建聚合搜索保留不动**(EventBus TYPE_SEARCH_RESULT + fallbackCandidates,与快搜无关);SourceBean.quickSearch 源配置属性保留(isQuickSearch() 被换源过滤使用)。
- **保留未动**:SearchReceiver/ServerEvent.SERVER_SEARCH 远程推送搜索(SearchActivity 自用);styles.xml→item_bg_selector_right→button_detail_quick_search 旧样式链(仍被存活对话框样式引用,与快搜页面无关)。
- **行为变化**:jumpToSearch/jumpToDetail 源缺失时一律进普通搜索页;首页长按「搜索相似内容」同。搜索历史仍由 SearchActivity 写入。

## 卡片点击分发 + 网盘目录下钻(2026-09-11,三项决策已经用户确认)

- **背景**:用户 7 源配置混了三类语义 —— 影视源(`<9.10更新>修复文采 海绵`/`玩偶哥哥|4K弹幕`/`叨观荐影|预告片`/`聚剧|四盘`/`光影|不卡`)、网盘源(`📁我的云盘|我配置`,首页 = 「云盘配置」8 张动作卡)、音乐源(`🎙易听音乐|带歌词`,首页 = 歌手/榜单卡)。原实现「action 卡走 action,其余一律跳搜索」在音乐源与网盘目录卡上是错的(音乐卡搜出来的是别的影视源同名内容;`vod_tag=folder` 的目录卡被当影片)。
- **调研先行(读上游源码)**:判定字段 = `Vod.isAction()`(action 非空)/ `Vod.isFolder()`(`vod_tag=="folder"` 或 `cate!=null`)/ `Class.isFolder()`(`type_flag=="1"`)/ `Site.isIndex()`(`indexs==1`);分发集中在 `示例文件/TV-fongmi/app/src/mobile/.../ui/fragment/TypeFragment.java:191-208`(TV 版同构,leanback HomeActivity:399-411);优先级 action > folder(openFolder 下钻) > index 站(跳搜索) > 默认详情页。**上游没有「音乐」概念** —— 音乐源卡片走默认分支进详情页,音频靠播放器 `onAudio()→setAudioOnly(true)`。
- **决策(用户选定)**:①默认策略 = **保持搜索**(改动最小,兼容 2026-09-10 定稿);②切换入口 = **订阅源 sheet 行内标记**;③网盘目录下钻**本轮做,可递归**。
- **落地**:新建 `ui/page/VodCardAction.kt`(`SourceCardPolicy` 枚举 + `VodCardTarget` 密封接口 + `VodCardPolicy`(Hawk `source_card_policy`,只登记 DETAIL 的源)+ `resolveVodCardTarget` + `Context.dispatchVodCardClick` + `openVodFolder`);`AbsJson.AbsJsonVod` 增 `cate` 字段并在 `toXmlVideo()` 归一到 `tag="folder"`;`PartitionListActivity` 增 `MODE_FOLDER` + `startForFolder(folderId, name)`(目录 id 当分类 id,复用 `PartitionListVM`;递归 = 逐级开页,返回键回上级)+ action 卡 Toast;`PartitionListVM` 增 `runAction` / `actionMessages` / `refresh`;`SettingsOptionRow` 增 `trailing` 插槽;`HawkConfig` 增 `SOURCE_CARD_POLICY`。首页与栏目二级页的卡片点击都改走分发器(搜索模式与搜索结果页保持进详情)。
- **验证**:`:app:compileDebugKotlin -q` 退出码 0;read_lints 无诊断。
- **真机验证点**:①「订阅源」sheet 里把「易听音乐」「我的云盘」标记切成「详情」;②音乐源点歌手卡应进详情页并自动播放;③网盘源点目录卡进目录页、再点下级目录继续下钻、返回键逐级回退、末级文件卡进详情;④网盘配置卡(action)仍是 Toast + 列表刷新;⑤影视源点卡片仍跳搜索(行为未变)。

## 播放器覆盖层左右边距分档(2026-09-13,用户定稿)

- **诉求**:用户问「播放器控件距屏边缘多少 / 16dp 是否合适 / 大厂怎么设计 / 横屏是否该改 24dp」。核查原值 = 16dp(`PlayerTopBar` Row `start/end`、`PlayerBottomBar` Column `start/end`、锁屏钮 `end`),属 M3 compact 标准;但横屏画布宽已到 M3 的 expanded 档(测机 `screenWidthDp ≈ 930~1070`),16dp 仅占屏宽 **1.5%**,而竖屏预览态同样 16dp 占 **3.3%** —— 同一套控件两个方向观感差一倍。
- **定稿规则(用户确认)**:`playerEdgePadding()`(`player/ui/PlayerOverlay.kt`)= `screenWidthDp >= 600 ? 24.dp : 16.dp`,对齐 M3 窗口分档惯例(compact 16dp / medium 及以上 24dp);横屏 24dp 同时覆盖横屏挖孔落在左/右边缘的系统 safeInset(实测档位约 12~15dp)。
- **落点(用户指定范围,仅这 5 处)**:`PlayerTopBar` Row 左右、`PlayerBottomBar` Column 左右(含 KDoc 更新)、`PlayerLayers` 锁屏钮右。**未动**:顶栏顶 12dp、底栏下 16dp、进度条触摸高 `vs_30`、底栏菜单按钮高度、中央控制组与各提示浮层(居中,与边缘无关)。
- **本轮否决的两个改动**:①底栏菜单按钮行整体加高到 48dp —— 进度行在菜单行**之上**,行高增加会把进度条顶高 20~40dp(超过屏高 1/5),且按压高亮药丸会从 ≈28dp 变 48dp,破坏既定「轻量化文字条目」观感;②进度行与菜单行换序(进度条贴底、菜单行在上)= B 站/YouTube/media3 官方的排列,属结构性改造,超出本轮范围,待用户决定。
- **参考基线(实测 media3 1.9.0 官方 styled controller,解本机 AAR `res/values/values.xml` + `exo_player_control_view.xml`)**:底栏高 **60dp**、进度条触摸高 **48dp**、进度条距底 **52dp**(轨道中心 ≈76dp)、底栏 `marginTop` 10dp、时间文本左右 padding 10dp、控件组左右外边距 **0dp**(贴边)。即官方思路 =「触摸目标够大 + 内部控制间距」,而不是整组内容从屏幕边缩进。
- **⚠️ 排查结论(避免误判)**:边距与「边缘手势带」无关 —— dkplayer `PlayerUtils.isEdge()`(`player/src/main/java/xyz/doikki/videoplayer/util/PlayerUtils.java:162`)对**四边各 40dp** 内的触摸直接忽略,视频手势(亮度/音量/拖动/快滑)在该带内本就不响应;而覆盖层按钮是 Compose 控件自带 `pointerInput`,不受 `isEdge` 影响,放 16dp 同样可点。
- **验证**:`.\gradlew :app:installDebug --console=plain` → BUILD SUCCESSFUL(1m 3s),已安装到 `V2425A - 16`。
- **待办(用户尚未确认)**:覆盖层触摸目标统一到 48dp(锁屏钮 24dp、进度条 ≈24dp、菜单按钮 ≈28dp 均低于 M3 下限);若要同时保观感,需先决定是否把进度行移到菜单行**下方**贴底。

## 亮度/音量手势提示改为 M3 surface 药丸(2026-09-13,用户要求,附截图对比)

- **诉求(用户原文+截图)**:「手势控制音量和亮度出现的透明圆角胶囊改成和控制进度一样的风格,半透明的 material surface」——图1 = 现状(`音量0%`:深灰底 #6C3D3D3D + 2dp 白描边 + 固定 200x100mm 大框,居中白字);图2 = 目标(seek 提示的 M3 药丸:半透明白/浅 surface、无描边、贴合内容)。
- **落地(`PlayerLayers.kt` 单文件)**:`PlayerSlideHint` 改为复用 `HintPill` 构造 —— 居中放置,HintPill 提供「`shadow(4.dp, 圆角50)` + `surfaceContainer.copy(alpha = 0.9f)` + `padding(vs_20/vs_10)`」;文字由 `Color.White` 改 `MaterialTheme.colorScheme.onSurface`,字号仍 `ts_30`(与 seek 提示一致)。**尺寸由内容自适应**(「亮度50%」/「音量50%」),不再固定 200x100mm —— 与 seek 提示形态统一。
- **连带清理**:删除仅此处使用的 `PillBg` 常量,及随之失引的 import `foundation.border` / `layout.height`(`width`、`PillShape` 仍被 Spacer/HintPill 使用,保留);文件头视觉注释从「照搬 shape_user_focus(#6C3D3D3D + 白描边)」改为「提示类浮层统一 M3 surface 药丸」。
- **未动**:暂停浮层中央播放圆钮(Black 35% 圆底,功能按钮非提示)、长按倍速浮层(`0x66000000` + 12dp 圆角,用户未提)、直播页手势提示(`LivePlayActivity` 内 `gestureHintText`,现为 Black 60% + 10dp 圆角 —— 与点播页不同源,若需统一需另开一轮)。
- **验证**:`.\gradlew :app:installDebug --console=plain` → BUILD SUCCESSFUL(24s),已装 `V2425A - 16`;read_lints 无诊断。

## 竖屏详情页:进度行播放/暂停钮 + 双击暂停(2026-09-13,用户附截图要求)

- **诉求(用户原文)**:「影视详情竖屏界面下在播放器区域进度条的左边加上暂停按钮,同时把暂停按钮、进度条和全屏按钮的高度调整到同一水平」「竖屏详情页面应该支持双击播放器区域两次把视频暂停」。
- **落地 1 - 暂停钮(`PlayerBottomBar.kt`)**:预览态(`state.previewMode`)进度行行首插入 `PreviewPlayPauseButton` —— 触摸盒 **40dp**、`player_ic_pause/play` 图形 **22dp**、`ColorFilter.tint(White 90%)`,与详情页右下角全屏入口(`DetailActivity`:40dp 盒 + 9dp padding + 90% 白 tint = 22dp 图形)完全同款;点击走 `actions.onPlayPauseClicked()`(带 500ms 防抖);图标状态判定含 `BUFFERING/BUFFERED`(同 `PlayerCenterControls`,dkplayer 缓冲结束停在 STATE_BUFFERED)。
- **落地 2 - 三者同一水平线(关键计算)**:行高从 `vs_30`(≈24dp)变 40dp(按钮盒决定),若底距仍 16dp 则行中心上移 8dp、与全屏入口错位。故预览态底距改为 `16dp + playerDim(vs_30)/2 - 40dp/2`(= **DetailActivity 全屏入口 `bottom` 偏移的同一式子**) → 行中心 = 16 + vs_30/2,与全屏入口中心、以及**改动前进度条的中心线完全一致**(进度条只是被 40dp 盒垂直居中,自身位置未动)。⚠️ 两处式子必须同步改。
- **落地 3 - 双击暂停(`ComposeVideoController.kt`)**:`onDoubleTap` 去掉 `if (previewMode) return false` 提前返回(守卫 `isDoubleTapTogglePlayEnabled && !isLocked() && isInPlaybackState()` 保留),`onTouch` 的预览态分支注释同步更新。**副作用(固有)**:GestureDetector 语义下单击显隐要等双击窗口超时(~300ms)才 `onSingleTapConfirmed`;预览态滑动/长按仍不响应。
- **验证**:`.\gradlew :app:installDebug --console=plain` → BUILD SUCCESSFUL(35s),已装 `V2425A - 16`;read_lints 无诊断。
- **真机验证点**:①竖屏详情页播放中呼出控制条 → 左下角出现暂停图标,与进度条、右下角全屏图标同一水平线;②点它切换暂停/播放且图标随状态变化(缓冲中显示暂停图标);③双击播放区暂停/播放;④单击仍能显隐控制条(会晚 ~300ms);⑤左右边距:暂停钮左边距 = 全屏钮右边距 = `playerEdgePadding()`;⑥横屏全屏不受影响(无该按钮)。

## 全屏/退出全屏旋转过渡修复 A+B(2026-09-13,用户报"16:9 视频突然拉伸铺满全屏再变竖屏")

- **定位(纯代码审查结论)**:`DetailActivity.applyFullscreen` 立即翻转 `vm.fullScreen` → `DetailScreen` 当帧按 `if (full) fillMaxSize() else fillMaxWidth+statusBarsPadding+aspectRatio(16/9)` 换形态,而系统旋转要 200~500ms 才落地;`AndroidManifest` 对 DetailActivity 声明 `configChanges="orientation|screenSize"`(不重建),**全项目没有任何 `onConfigurationChanged`**(grep 确认 0 处)。故过渡期在**旧方向的窗口**里渲染**新方向的形态**:①横屏→竖屏:16:9 在横屏窗口里装不下(宽推高 = 1.25×屏高)→ Compose `aspectRatio` 退化成按高定尺寸(≈2108×1186,还减去刚显示的状态栏)、靠 Column 左对齐 → "视频缩小 + 跳";②竖屏→横屏:当帧 `fillMaxSize()` 在竖屏窗口 = 整块竖屏 → "黑屏 + 中间小视频";③`画面缩放=填充(MATCH_PARENT)` 时 `MeasureHelper` 直接取容器尺寸 → **真的拉伸变形**(这是"拉伸铺满"最直接的来源);④连带 `setPreviewMode`/字幕字号当帧跳。同批核查了上游 fongmi(`示例文件/TV-fongmi`):`changeHeight()` = 短边×比例 + `clamp(150dp, 屏高/2)` 且 land/fullscreen/PiP 直接 return;进出全屏只改 `mBinding.video` 的 LayoutParams + 横屏时 `ChangeBounds` 150ms;`onConfigurationChanged` 只做自动旋转与沉浸重断言、不门控布局——**它的几何在任何方向都合法,所以不需要门控**。
- **落地 A(形态跟随实际方向,门控切换时机)**:`DetailViewModel.rotating: MutableStateFlow<Boolean>`,在 `setFullScreen()` 里按 `playContainerRef.resources.configuration.orientation` 与目标方向比较置位;`DetailActivity.onConfigurationChanged` 清位并 `syncFullBoxSideEffects()`;`isFullBox()` = `if (rotating) !landNow else fullScreen`(与 `DetailScreen` 的 `fullBox` 同一判定);`DetailScreen` 把播放器 Box、加载覆盖层、右下角全屏入口、页面内容四处从 `full` 换成 `fullBox`。直播页同套:`LivePlayActivity.rotating` + `isFullBox()`,替换 `LiveScreen` 背景、`LiveReadyContent` 的 PlayerArea/频道区、`PlayerArea` 的角标分支(逻辑分支 `onSingleTap`/返回/`hideSysBar` 仍用 `fullScreen`)。
- **落地 B(几何钳制)**:预览态播放区高度由 `aspectRatio(16/9)` 改为显式高度 `短边 × 16:9` + `coerceAtLeast(150dp).coerceAtMost(max(150.dp, 长边/2))`(取 `LocalConfiguration.screenWidthDp/HeightDp`,短边=竖屏宽、长边/2=高度上限,均与方向无关),点播/直播两处同算法。**未做**:B 的"切换动画"部分(Compose 里会给 `AndroidView` 逐帧 resize 触发 SurfaceView 重设尺寸,且动画发生在旋转之后、反而把注意力吸到那一跳上;fongmi 需要动画是因为它在错误方向切形态,我们已用 A 消除了那次错误切换)——如仍想要,加 `Modifier.animateContentSize()` 一行即可试。
- **无额外延迟(用户追问已答复)**:`full` 目标态仍当帧翻转(沉浸切换/返回键/按钮反馈零延迟),**只有"形态采纳"落在旋转落地那一帧**,而那几百毫秒正是系统旋转动画本身,不会出现"点了没反应"。多连点安全:`rotating` 每次按「目标 ≠ 当前方向」重算,不是恒置位;回调缺失时退化为"当前方向的自然形态",不卡死。
- **验证**:`.\gradlew :app:installDebug --console=plain` → BUILD SUCCESSFUL(38s),已装 `V2425A - 16`;read_lints 无诊断。
- **真机验证点**:①竖屏详情页点右下角全屏:点击后画面**不动**,屏幕旋过去即铺满(不再有"竖屏整屏黑一下");②横屏按返回退出:画面保持全屏样转回竖屏,落地即变顶部 16:9 + 下方内容(不再"缩小靠左上跳");③预览态播放区高度与改动前一致(竖屏仍是满宽 16:9);④直播页同样两条路径;⑤`画面缩放=填充` 下也不再出现拉伸变形。

## 首页订阅源胶囊宽度 +20dp(2026-09-13,用户要求)

- **改动**:`HomePage.kt` 顶栏订阅源胶囊 `widthIn(max = 220.dp)` → **240dp**(2026-09-12 曾按用户要求由"占满顶栏剩余宽度"收到 220dp,本次再放 20dp)。胶囊仍是 `widthIn(max)` + 内容自适应,故**源名短于上限时宽度不变**,只有超长名(如「玩偶哥哥|4K弹幕」)会多显示 20dp 内容;注释与 spec §4.1 胶囊描述同步更新(不填固定宽度,避免短名胶囊被撑长)。
- **验证**:`.\gradlew :app:installDebug --console=plain` → BUILD SUCCESSFUL(29s),已装 `V2425A - 16`。
- **备注**:若用户本意是"无论源名长短,胶囊整体都宽 20dp"(即水平内边距 12dp → 22dp),改 padding 即可;两者语义不同,当前按"宽度上限 +20dp"落地。

## 退后台暂停浮层污染任务快照修复(2026-09-13,用户报"退出应用后后台管理里显示暂停状态")

- **现象与定位**:用户截图 = 后台管理(最近任务)卡片里,预览态播放器中央出现播放 ▶ 图标、左上出现 `pauseTitle` 标题(看起来"被暂停了"),但从后台返回会**自动续播**。根因链:`DetailActivity.onPause()` → `PlayContainer.hostPause()`(退后台自动暂停,`lifecyclePaused = isPlaying()` 记下并 `mVideoView.pause()`)→ `VideoView` 置 `STATE_PAUSED` → `ComposeVideoController.onPlayStateChanged` 收起底栏 → `PlayerUiState.pauseOverlayVisible` = `paused && !controlsVisible` 成立 → 画出暂停浮层 → **系统任务快照(在退后台瞬间抓取)把这一帧拍下**,于是卡片长期显示"暂停";而 `hostResume()` 会 `mVideoView.resume()` 自动续播,所以"实际没暂停"。即:暂停浮层没有区分**生命周期暂停**与**用户暂停**。
- **修复**:`PlayerControlApi` 新增 `setLifecyclePaused(boolean)` → `ComposeVideoController` 写入 `PlayerUiState.lifecyclePaused`(新增 Compose state);`pauseOverlayVisible` 追加 `&& !lifecyclePaused`;`PlayContainer.hostPause()` 在暂停前调用 `setLifecyclePaused(true)`,`hostResume()` 复位 false。两处写入同在退后台那一帧内完成,浮层不会先画后收(no flash)。手动暂停后进后台仍照实显示(回前台 `hostResume` 复位后浮层照常)。`hidePauseRoot()` 注释同步说明(它仍是空实现,浮层纯派生)。
- **验证**:`.\gradlew :app:installDebug --console=plain` → BUILD SUCCESSFUL(30s),已装 `V2425A - 16`。
- **真机验证点**:①播放中点 Home → 最近任务卡片里播放器区应只有视频帧(无 ▶ 图标、无左上标题);②返回应用自动续播;③手动暂停后点 Home → 卡片不显示暂停浮层,返回后视频仍处暂停且浮层正常出现;④耳机/后台音频场景(音频模式 `hasAudioOnlyPlayback`)不受影响。

## 竖屏详情页标题行增加投屏入口(2026-09-13,用户要求)

- **需求**:竖屏详情页收藏图标左边加一个投屏控件,图标用 `.tubiao/投屏.svg`,点击**复用播放器界面的投屏 dialog**。
- **素材**:`.tubiao/投屏.svg`(24px / viewBox `0 -960 960 960` 单 path)→ `res/drawable/ic_detail_cast.xml`,按项目约定加 `<group android:translateY="960">` 平移、`fillColor="#FFFFFFFF"`(由 `Icon` tint 着色)。
- **入口**(`DetailContent` 标题行,收藏钮左侧):`IconButton { Icon(painter = ic_detail_cast, contentDescription = "投屏", tint = onSurfaceVariant, size = 24dp) }` → `activity.playContainer?.showCast()`;`PlayContainer` 新增公开方法 `showCast()` 直接转调私有 `showCastDialog()`,**与播放器底栏「投屏」完全同一条链路**(构造 `CastVideo` → `uiState.setCastSheet(CastSheetState(...))` → `PlayerOverlay` 的 `CastSheet`)。面板本身是 `Dialog`(独立窗口),故在竖屏详情页触发也能全屏弹出,不受播放器区域裁剪影响;无可投地址时沿用内部 Toast「暂无可投屏播放地址」。
- **验证**:`.\gradlew :app:installDebug --console=plain` → BUILD SUCCESSFUL(28s),已装 `V2425A - 16`。
- **真机验证点**:①竖屏详情页标题行右侧出现「投屏 | 收藏」两枚图标钮,投屏在左;②点击弹出与播放器「投屏」一致的设备面板(标题「投屏到设备」、刷新/取消、DLNA/TVBox 扫描);③选中设备投屏成功后播放器自动暂停(`onCastSuccess` → `mVideoView.pause()`);④未取到播放地址时给 Toast 而非空白面板。

## 依赖升级:OkHttp 3.12.11 → 5.5.0 + Okio 2.8.0 → 3.18.2(2026-09-13,用户要求)

- **版本**:`gradle/libs.versions.toml` 的 `okhttp = "5.5.0"`、`okio = "3.18.2"`(实际解析:okhttp 5.5.0 + okhttp-android(Android 变体 AAR) + okhttp-dnsoverhttps 5.5.0 + okio-jvm 3.18.2;OkGo 3.0.4 / Picasso 2.71828 / coil-network-okhttp 请求的 3.x~4.12.0 全部提升到 5.5.0,均编译与运行通过)。**升级 5.x 的直接收益:官方 3.18.1 有 base64 padding 缺陷,3.18.2 修复**。
- **fork 移除(关键)**:删除仓库内 fork 的 `app/src/main/java/okhttp3/dnsoverhttps/`(`DnsOverHttps.java`/`DnsRecordCodec.java`/`BootstrapDns.java`)—— 该 fork 依赖 OkHttp 3.x 内部结构(自定义 `lookupHttpsForwardSync`),而 OkHttp 5 的 `Dns` 接口新增了嵌套类型 `Dns.Request`/`Dns.Callback`/`onRecords`,fork 的 `implements Dns` 会**继承这些嵌套类型并遮蔽同名导入**(`okhttp3.Request`/`okhttp3.Callback`),Java 侧报"不兼容的类型: okhttp3.Dns.Request 无法转换为 okhttp3.Request"等 8 处错误,且继续维护需要 Guava 化的 PublicSuffixDatabase 兜底(内部 API 不稳定)。
- **改用官方构件**:`libs.versions.toml` 新增 `okhttp-dnsoverhttps = { group/name 同版本 ref }`,`app/build.gradle.kts` 加 `implementation(libs.okhttp.dnsoverhttps)`;`OkGoHelper` 的 import 不变(包名相同 `okhttp3.dnsoverhttps.DnsOverHttps`),仅 Builder 放宽:`DnsOverHttps.Builder().client(dohClient).url(HttpUrl.get(dohUrl)).bootstrapDnsHosts(...)`;官方 Builder 要求 **url 非空** ⇒ `dohUrl` 为空(关闭 DoH)时直接 `dnsOverHttps = null`(调用方 `OkDns`/`CustomDns` 已判空回落 `Dns.SYSTEM`)。
- **本地 `/dns-query` 端点重写**(`RemoteServer.java`):原 `lookupHttpsForwardSync`(返回拼接的 A+AAAA 原始响应)随 fork 消失。新实现 `buildDnsResponse(hostname, addresses)` 用 Okio 手写合法 DNS 应答(ID=0,flags `0x8180|rCode`,单 question + 全部 A/AAAA 答案,TTL 60s,无地址回 SERVFAIL);取地址走官方 `dnsOverHttps.lookup(name)`。⚠️ 该端点全工程无内部调用方(仅有服务端实现),如外部有依赖此接口的客户端需回归。
- **未改动**:`OkDns.lookup`/`CustomDns.lookup`/`OkHttp.string` 等调用点全部兼容(`Dns.lookup` 在 5.x 仍保留为同步便捷入口)。
- **验证**:`:app:compileDebugKotlin` + `:app:compileDebugJavaWithJavac` BUILD SUCCESSFUL;`assembleDebug`/`assembleRelease`(R8)`installDebug` BUILD SUCCESSFUL;真机 `V2425A` 冷启动进首页正常、外部 443 连接建立(网络栈可用)、首页数据正常加载(截图确认)。proguard 现有 `-keep class okhttp3.**` 覆盖新构件。

## 依赖升级:Room 2.3.0 → Room 3.0.2(androidx.room3 新命名空间)(2026-09-13,用户要求)

- **关键前提**:Room 3 **换命名空间**,不是同坐标升版本 —— `androidx.room:room-runtime/room-compiler` → `androidx.room3:room3-runtime/room3-compiler:3.0.2`;AndroidX 构件在 **Google Maven**(不是 Maven Central);`androidx.room` 组最高仅 2.8.5,3.x 只在 `androidx.room3` 组下(3.0.2/3.0.3 正式版)。用户提示看 `示例文件/android` 定位到本项目的参照用法。
- **构建改动**:`libs.versions.toml` 坐标换 room3 + 新增 `androidxSqlite = "2.7.0"`(`androidx.sqlite:sqlite-bundled`);`app/build.gradle.kts` 的 `annotationProcessor(room.compiler)` → **`ksp(...)`**(app 早已应用 KSP 插件,注释里写明"备用接入"正好用上)、新增 `implementation(libs.androidx.sqlite.bundled)`、schema 导出从 `javaCompileOptions.annotationProcessorOptions` 改为**顶层** `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`(放 defaultConfig 里无效)。
- **代码改动(8 个文件)**:import `androidx.room.` → `androidx.room3.`(批量替换);`AppDataManager` 加 `.setDriver(new BundledSQLiteDriver())`(Room 3 必须显式指定 driver,不再走 SupportSQLite);删除 5 个从未启用的 `Migration` 死代码 + 死 `Callback`(Room 3 里 `Migration.onMigrate()`/`RoomDatabase.Callback.onCreate()` 都是 **suspend + SQLiteConnection** 参数,Java 无法实现);`isOpen()` 三处判空改 `== null`/`!= null`(Room 3 移除 `RoomDatabase.isOpen()`,仅剩 internal `isOpenInternal$room3_runtime()`)。
- **实测结论(重要)**:Room 3 **支持 Java 源** —— 3 个 Java Entity、3 个 Java DAO(同步方法,非 suspend)、`@Database` 全部经 KSP 编译通过;`Room.databaseBuilder(Context, Class<T>, String)` 静态重载保留;`setJournalMode/allowMainThreadQueries` 仍在 Builder 上。**上层 `RoomDataManger`/`CacheManager` 与所有业务调用方零改动**。
- **验证**:编译 + installDebug 成功;真机冷启动正常;`databases/tvbox.v3.db.lck` 出现(= Room 3 连接池打开旧库成功,identity 校验通过)、db 本体时间戳未变(只读打开)。**真机已验证**(用户确认):历史/收藏 DAO 读写正常。

## 依赖升级:media3 1.9.0 → 1.11.0(2026-09-13,用户要求)

- **版本与约束**:`libs.versions.toml` media3 = "1.11.0"(最新稳定版已到 1.11.1);**jellyfin `media3-ffmpeg-decoder` 上游最高仍 1.9.0+1**(其 POM 编译基线 media3 1.9.0,2025-12 后未跟进)→ 保持 1.9.0+1,toml 注释写明"先保持,需实测 AC3/EAC3 软解路径"。
- **ABI 静态核对(预编译扩展 jar 与新版本的核心风险,结论:兼容)**:javap 对比 —— `DecoderAudioRenderer` 的 3 个抽象方法(`supportsFormatInternal(Format)`/`createDecoder(Format, CryptoConfig)`/`getOutputFormat(T)`)签名一字不差;ffmpeg 用到的两个构造器(`(Handler, AudioRendererEventListener, AudioProcessor...)`、`(Handler, AudioRendererEventListener, AudioSink)`)在 1.11.0 均存在;`SimpleDecoder` 的 4 个抽象方法(`createInputBuffer`/`createOutputBuffer`/`createUnexpectedDecodeException`/`decode`)一致。
- **预载现状**:1.11.0 的 `source/preload` 包仍是 `DefaultPreloadManager`/`BasePreloadManager`/`PreCacheHelper`,**仍无 `DiskPreloadManager`** —— 与 1.9.0 时代的结论一致,项目自实现的"共享 SimpleCache 写盘+播放读盘"预载方案不受影响,无需改动。
- **验证**:compileDebugKotlin/Java + installDebug 成功;真机冷启动进首页正常。AC3/EAC3/DTS 软解路径未实测(用户找不到此类片源),以静态 ABI 核对为准放行;`libs.versions.toml` 注释已同步更新为"已核对兼容"并精简全文注释(zxing/desugar/slf4j 三处长注释只留结论)。

## 依赖清理:移除 Picasso(2026-09-13,用户要求)

- **依据**:项目零业务使用(仅 `OkGoHelper.initPicasso()` 兜底,注释自认是给 Spider jar 用的)+ 参照工程 FongMi 不用 + **设备上真实第三方 jar 常量池扫描不引用它**(该 jar 自带依赖并混淆,只裸引用框架类)→ 判定兜底无实际需求。
- **改动**:`OkGoHelper` 删 `initPicasso()` 与两个 import(`Bitmap`/`Picasso`);`setMaxRequestsPerHost(10)` 非 Picasso 专属(作用于共享 defaultClient)迁到 `init()` 内 build 之后保留;`libs.versions.toml` 删 `picasso` 版本+库定义;`app/build.gradle.kts` 删依赖。
- **验证**:编译 + installDebug + 冷启动首页正常;`debugRuntimeClasspath` 依赖树已无 picasso。
- **同轮评估(OkGo,未执行)**:3.0.4 上游停更(2017 后无版本)且针对 OkHttp 3.8.1 编译,跑在 5.5.0 上属"跨大版本字节码"隐患;逐类扫描仅 `HttpLoggingInterceptor` 引用 `okhttp3/internal.*`,其余公开 API 5.5.0 全有,实测正常。移除=重写 15+ 文件(SourceViewModel/SubtitleViewModel/JsLoader/JarLoader/PlayContainer/各 Activity 等)的网络调用、tag 取消、AbsCallback 转换,列为独立待办。

## 缺陷修复:gson 升级导致 Hawk 集合数据全部读不出(2026-09-13,用户报告)

- **现象**:配置管理页添加源后只显示最后添加的一个(旧的被替换),退出重进列表空白;用户疑为 Room 3 升级所致(实测无关——源列表存 Hawk,不经 Room)。
- **根因**:**Gson 2.13+ 禁止匿名 TypeToken 捕获类型变量**(`IllegalArgumentException: TypeToken type argument must not contain a type variable`),而 Hawk 2.0.1(停更)的 `HawkConverter.toList/toSet/toMap` 正是该写法 → 所有 List/Map/Set 键的 Hawk 读取抛异常并被**静默吞掉**(`DefaultHawkFacade.get` catch 后返回默认值)。`saveSubscribe` 的"读-改-写"在读空时把已有列表覆盖成只剩新项,造成二次丢失。触发点 = gson 2.10.1 → 2.14.0 升级。
- **证据链**:Hawk2.xml 快照 diff(键全变、subscribe_list 变短)→ 探针日志(`contains=true/rawSize=NULL/putOk=true/readBack=NULL` + `Hawk.get -> Converter failed`)→ 本地 Gson 复刻测试(2.14.0 FAIL / 2.10.1 OK)。
- **处置**:先实现并验证了 Hawk 兼容补丁(`HawkCompatConverter` 改用 `TypeToken.getParameterized(...)`,已证实修复且旧数据恢复),**用户最终选择回退 gson 到 2.10.1**,补丁与全部临时诊断日志(LOG 前缀/LogInterceptor/ConfigManagePage 探针)已删除;`libs.versions.toml` 的 gson 行保留"不可升 2.13+"的约束注释。
- **验证**:回退版装机 → 配置管理页截图 4 个源完整显示;依赖树 gson 2.10.1 ✅。
- **可复用经验**:Hawk 静默失败(`put` 返 false 不抛、`get` 失败返默认值)→ 集合读写异常无提示;升级与停更库(Hawk)共存的依赖(gson)前必须核对兼容性;`Hawk.init(ctx).setLogInterceptor()` 是定位 Hawk 内部链路问题的利器。

## KV 存储迁移:Hawk → MMKV(2026-09-13,按 skill/avbox-kv-mmkv-spec.md 实施 P0–P3)

- **背景**:同日「gson 升级导致 Hawk 集合数据全部读不出」只做了回退 gson 的止血;本次按 spec 根治 —— 用 MMKV 承载全项目键值存储,彻底摆脱"停更库 + 匿名 TypeToken 推元素类型"的组合。
- **P0 门面(`util/KV`,新文件)**:
  - `gradle/libs.versions.toml` 新增 `mmkv = "2.4.2"`(`com.tencent:mmkv`);实例 = `MMKV.mmkvWithID("avbox_kv", SINGLE_PROCESS_MODE)`,**不加密**(spec §7-Q1),`App.initParams` 里 `KV.init(this)`。
  - API 对齐 Hawk:`put/get(key)/get(key,def)/contains/delete`。差异有二:① 类型推断不再靠匿名 TypeToken;② 写入失败返回 false 并打 `echo-kv` 日志(不再静默,G4)。
  - **类型编码**(`util/kvcodec/KVDecoder`,**纯 JVM 无 Android 依赖**以便单测):String 原样存;集合 / Map / JsonArray / 任意对象 → `\u0001json:` 前缀 + Gson 文本。读侧类型优先级 = 调用侧默认值的具体类型 → 注册表 → 复杂值退化为 JSON 节点树(只保证不丢值)。
  - **`util/kv/KVKeySpec` 键→显式类型表**(spec §7-Q3):集合与嵌套泛型必须登记。⚠️ 这不是迁移脚手架而是长期设施 —— 泛型擦除后 `new ArrayList()` / `new HashMap<>()` / `null` 默认值都带不来元素类型,不登记就会解出元素为 `LinkedTreeMap` 的集合(取值 ClassCastException / 写回把元素类型写坏)。**这正是旧 Hawk 的病灶**:`HawkConverter.toList/toMap` 拿 `new TypeToken<List<T>>(){}.getType()` 推元素类型,在 gson 2.13+ 直接抛异常。
  - **单测 14 例**(`app/src/test/java/.../kvcodec/KVDecoderTest.java`,`testImplementation junit 4.13.2`,纯 JVM 不需要 Robolectric):ArrayList 元素恢复为 String、JsonArray 走节点树不按 List、嵌套 `HashMap<String,HashMap<String,String>>`、未登记键 + Object 默认值的兜底、坏数据返回 null 而非抛异常、静默副本等。
- **P1 一次性迁移(`util/kv/KVMigrate`)——已实现并真机跑通,随后整体删除**:触发 = 完成标记 `kv_migrated_from_hawk`;逐键 `Hawk.get` → `KV.put`,全部成功才写标记(幂等)。**删除原因**:用户 2026-09-13 明确"应用尚未发布、没有存量用户" —— 迁移唯一的价值(保护存量数据)不存在,留着只会让 Hawk/Conceal 永久留在依赖树里。连带删除:hawk 依赖(toml + `build.gradle.kts`)、`proguard` 的 `-keep class com.orhanobut.hawk.**`、旧库与 Conceal 密钥文件。**首装即原生 MMKV,不存在"旧库"这个前提。**(该段代码在删除前暴露过一个必崩缺陷,教训见下方崩溃复盘)
- **P2 全量切换**:**33 个文件**(与 spec §1.1 盘点的 34 个文件对账一致,HawkConfig 本身只含键常量)机械替换 `Hawk.` → `KV.` + import 替换;`HawkConfig` 类名与全部键字符串**保持不变**(§7-Q4 不做键名重构,零迁移风险)。顺带把两处字面量键收敛进 `HawkConfig`(`home_hot`/`home_hot_day`/`danmu_api_use_default`),便于类型登记。`LOG.FILE_LOG_PREFIXES` 增补 `echo-kv`。
- **P3 收尾(彻底解耦)**:移除 hawk/conceal 依赖链(`debugRuntimeClasspath` 依赖树实测只剩 `com.google.code.gson` + `com.tencent:mmkv`),`proguard` keep 规则清理,gson 版本锁注释解除(Hawk 已不在,2.13+ 约束消失)。**`util/kv/KVKeySpec` 保留** —— 它是 KV 正常运行的必需件(集合元素类型登记),不是迁移脚手架。
- **gson 升级 2.10.1 → 2.14.0(2026-09-13 用户要求,验证 G3 达成)**:gson 2.10.1 是当初为绕开"gson 2.13+ 与 Hawk 2.0.1 不兼容"而锁的版本(Hawk 的 `HawkConverter` 用匿名 `TypeToken<T>` 捕获类型变量,2.13+ 直接抛 `IllegalArgumentException`,导致 List/Map/Set 键整体读不出)。Hawk 移除后该枷锁消失,直接升到最新稳定版:**编译 + 19 例单测一次通过,零代码改动** —— 因为 KV 侧只使用稳定 API(`TypeToken.get(Class)` / `TypeToken.getParameterized(...)` 的替代路径:显式 `Type` 登记),不引用任何 gson 内部实现,也不再用匿名 TypeToken 推元素类型。spec 的目标 G3「解除 gson 版本枷锁」到此闭环。
- **踩坑记录(重要,写给以后的自己)**:批量替换 + 注释改写用 PowerShell `[System.IO.File]::ReadAllText/WriteAllText` 混用编码时,**中文注释被逐字损坏**(如"策略"→"略略"、"持久化"→"久久化"、`(`→`H`)且仍是合法 UTF-8,编译器不报错、单测也照过)。发现方式 = 逐文件把工作区与「HEAD 机械替换后」的文本做 `Compare-Object`,任何多出来的差异都要人工确认。补救 = 从 HEAD 取回、只用一种明确的编码(`Get-Content -Raw -Encoding UTF8` 读 + `UTF8Encoding($false)` 写)重做替换,再复核差异集合只剩预期改动。**教训:多字节文本的批量改写,验证步骤不可省,且不要在同一批文件上叠多轮不同的替换脚本。**
- **验证**:`:app:testDebugUnitTest` 19/19 通过;`:app:assembleDebug`、`:app:assembleRelease`(R8 + 资源压缩)BUILD SUCCESSFUL;`debugRuntimeClasspath` 依赖树 = `com.google.code.gson:2.10.1` + `com.tencent:mmkv:2.4.2`(**已无 hawk / conceal**);全库 grep 已无业务侧 `Hawk.` 调用。
- **未做(需真机)**:spec §6.2 全量回归(设置项/配置管理/搜索历史/线路历史与自动换线/直播分组与直播源切换/续播/弹幕/DoH/无痕/卸载重装走默认值)—— 本轮只做到编译 + 单测 + 构建。装机命令与预期见 spec §5 第 4 条。

## 崩溃修复:KV 迁移把"旧库里没有的键"写成代表值 → 搜索页 `Semaphore(0)` 必崩(2026-09-13,用户报"有崩溃")

> ⚠️ 相关迁移代码(`KVMigrate`)已按"应用未发布、无存量用户"整体删除;本节保留是因为其中两个缺陷/教训与**现行 KV 代码**直接相关。

- **现象**:装机后**一进搜索页必崩**:`java.lang.IllegalArgumentException: Semaphore should have at least 1 permit, but had 0`(`SearchViewModel.<init>` → `Semaphore(semaphorePermits)`)。
- **抓日志**:`adb logcat -d` 拿到 FATAL 栈(`androidx.lifecycle.ViewModelProvider` 反射创建 `SearchViewModel` → kotlinx `SemaphoreKt.Semaphore`)定位到崩溃点;`adb shell run-as <pkg> cat files/preload_debug.log` 拿到本轮迁移日志 —— **`migrate-start hawkKeys=26` 对 `migrate-done kvKeys=76`**,键数不守恒,一眼看出写多了;再 `exec-out cat shared_prefs/Hawk2.xml` 核对旧库真实键集合(26 个,`search_threads` 不在其中),`files/mmkv/avbox_kv` 里 `search_threads` 存的是 `json:0`,闭环。
- **根因**:`migrateRegisteredKeys()` 用 `Hawk.get(key, 代表值)` 直读,而 **`Hawk.get(key, default)` 在键不存在时返回的就是 default 本身**(不是 null);代表值(`0`/`false`/空集合)本意只是告诉 Hawk "元素是什么类型"。于是登记表里有、旧库却没有的键全被写进 KV 且写的是代表值 —— `search_threads` 业务默认 32,被写成 **0**,`Semaphore(0)` 直接抛异常。实测多写了 50 个假键。
- **同源第二个 bug(现行代码,已修)**:`KVCodec.decode` 用 `getValueSize(key) < 0` 判存在性 —— MMKV 该 API 底层是 `size_t`,**不存在的键返回 0 不是 -1**,判断恒 false,导致每个不存在的键都带 `raw=null` 进解码器刷 `type-mismatch`,一轮启动 **2.9 万行**,把有效日志淹掉。已改 `containsKey` + `raw == null` 兜底。
- **同批加固(现行代码,已修)**:`KVDecoder.coerceNumber()` —— Gson 解析裸数字 token 一律给 `Double`,按 `int` 读会 `isInstance` 失败而静默回落默认值(值看着对、类型被换掉);现按目标数值类型收敛,越界值降级为"回落默认值"(不静默截断)。
- **最终处置**:修好并真机验证后,用户指出"应用未发布、没有存量用户",于是**迁移整套(搬运 + 纠偏 + 完成标记 + hawk 依赖 + 旧库文件)全部删除** —— 首装即原生 MMKV。设备侧旧库已随之清掉。
- **验证**:单测 19 例(含本题回归 3 例:Double→int 收敛、越界回落、`json:0` 老实读出 0 而非猜测为缺省);`assembleDebug`/`assembleRelease` 通过;修复版真机冷启动后 `echo-kv` **只剩一行** `type-registry keys=75`,无 `FATAL EXCEPTION` / 无 `Semaphore should`。
- **可复用教训**:
  ① **"取默认值"与"判存在性"是两件事** —— 拿带默认值的 getter 去判断存在与否,必然把默认值当成真值;读取层必须把二者分开暴露(`KV.contains` vs `KV.get`);
  ② 一次性逻辑的完成标记必须可升版本,否则修复无法到达已执行过的机器;
  ③ **批量搬运/写入后核对数量守恒**,"完成键数"与"旧库键数"的对比是数据写坏的最早信号(本次首装机日志里就已显现,当时没看);
  ④ 不要用 size 类 API 的返回值猜存在性(注意 `size_t` 的"0 表示不存在"语义);
  ⑤ **没发布的代码不要背迁移包袱** —— 本次为一个不存在的需求写了完整的搬运 + 纠偏 + 标记体系,最后全部删除;先确认"有没有存量数据"再决定要不要迁移,能省掉整条链路与一整个缺陷面;
  ⑥ 单元测试要覆盖**语义契约**而不只是算法:当时的 19 例全是纯解码逻辑,迁移的"键不存在不得写入"没有任何测试覆盖,所以缺陷一路走到真机。

## 代码整洁:Kotlin 编译警告 25 → 5(2026-09-13,用户要求"行为等价的纯收紧")

- **背景**:CI 日志里有 25 条 Kotlin 编译警告(不影响构建)。按"是否值得动"分四类处理,**只做行为等价的三类**,弃用 API 迁移留待单独排期。
- **类别一 · 无效注解(1 条)**:`ComposeLiveController` 的 `@JvmOverloads constructor(context: Context)` —— 构造函数没有任何默认参数,注解完全无效(HEAD 里就有的历史遗留,不是本次改动引入)。删注解;`ComposeVideoController` 那个有默认参数、注解有效,**没动**。
- **类别三 · 冗余调用(11 条)**:`data?.subtitleList` → `data.subtitleList`(编译器的非空判定是权威,这类警告可放心直接删,推断错误会变成编译错误而不是运行期问题);`response.body?.string()` → `body.string()`(OkHttp 5 的 `body` 非空);`episode?.name ?: ""` → `episode.name`(`sheet.episodes` 是 `List<VodSeries>` 非空元素);`series?.name` → `series.name`;`(key ?: "") + "|" + (id ?: "")` → `"$key|$id"`(`key`/`id` 形参非空);`(getOrNull(0) as? LiveSettingGroup)?.x` → `getOrNull(0)?.x`(`liveSettingGroupList` 已是 `List<LiveSettingGroup>`,转换冗余);`currentPosition.toLong()` → `currentPosition`(`currentPosition` 本就是 `Long`)。
- **类别四 · 平台类型收紧(4 条)**:`LivePlayActivity` 的 catchup 解析里 `Matcher.group(1)` 是 Java 平台类型 `String!`,Kotlin 2.4 起会警告"nullable receiver"且传给 `String` 形参时类型不匹配。改为**显式非空断言** `matcher.group(1)!!` —— 与文件里既有的 `epg.startdateTime!!` 风格一致;断言成立的前提(`matches()`/`find()` 已返回 true、且两个正则都有捕获组)在注释里写明,等价于原语义,且把平台类型真正收紧成 `String`。
- **顺手**:`LivePlayActivity` 里 `response.body.string() ?: ""` 去掉多余 `?: ""`(我删掉 `?.` 后它就成了"elvis 恒返回左值",编译器会新报一条警告 —— 这类"修一条冒一条"要跟着收干净)。
- **未做(类别二,弃用 API,需交互回归)**:4 处 `Slider(...)` 弃用(应迁 `rememberSliderState` + `Slider(state, ...)`,但项目多处是"松手才落盘"的自持 state 模式,得逐个验证)、1 处 `onBackPressed()` 弃用(应迁 `onBackPressedDispatcher`;spec §4.7 记录过竖屏切集 `BackHandler` 的返回键语义,动它要回归返回行为)。
- **结果**:警告 **25 → 5 条**,剩下的正好是上述 5 条弃用 API。`compileDebugKotlin` + `compileDebugJava` + 单测 32 例(20+7+5)全过。
- **可复用经验**:Kotlin 的 "Unnecessary safe call / Elvis always returns left operand / redundant cast" 这类警告**可以放心批量清理** —— 编译器既然判定接收者非空,写错会直接变编译错误,不存在"删了才炸"的风险;而 "Only safe calls allowed on nullable receiver" 属于**真的类型缺口**(Java 平台类型),要用注释写明断言前提再收紧。

## 媒体通知扩展到影视内容(2026-09-13,用户报"播放影视也和音乐一样下拉通知栏能看到")

- **需求(用户原文)**:「播放影视也和音乐一样下拉通知栏能看到」。
- **定位**:前台服务通知 + MediaSession 原先只对**纯音频**建立,判据是 `PlayContainer.updateMusicSession()` 里
  `return !trackInfo.getAudio().isEmpty() && trackInfo.getVideo().isEmpty();` —— **影视有音轨但视频轨非空,直接被否**;
  另一处 `playStateObserver` 里的 `switchPlayback` 收尾分支同样用它。
- **处置(拆成两个概念,避免连带改掉既有行为)**:
  - `hasPlayableAudio()` = **有音轨** ⇒ 决定"要不要建会话/通知",影视同样满足(本次需求);
  - `hasAudioOnlyPlayback()` = **有音轨且无视频轨** ⇒ 只决定 `hostPause()` 里"退后台是否保持播放"(维持原状);
  - 抽出 `currentTrackInfo()` 复用取轨道信息的 try/catch;`getAudioOnlyPlayback()` 这个返回 `Boolean`(可为 null 表示"取不到")的旧方法随之删除。
- **⚠️ 明确不做的部分**:**退后台保持播放仍然只对纯音频生效** —— 视频退后台照旧由 `HostPause` 自动暂停(dkplayer 的 autoPause 未启用,是应用自己在管)。所以影视的通知语义是"暂停后仍能在通知栏看到、并能从通知恢复播放",**不是后台播放视频**。若将来要后者,改的是 `hostPause` 的判据,不是通知判据 —— 这两件事已在 spec §4.4 写明分界,不要混。
- **验证**:`compileDebugJava` 通过(无 `getAudioOnlyPlayback` 残留),已 `installDebug` 到 `V2425A`。
- **真机验证点**:①播放影视内容 → 下拉通知栏出现标题/集数/封面 + 播放暂停按钮;②切集后通知里的集数跟着变;③在通知里点暂停/播放,播放器状态同步;④**退后台视频仍会暂停**(与改动前一致);⑤播放纯音频(音乐源)行为不变,退后台继续播;⑥电视模式下(UI_MODE_TELEVISION)不建通知(`MusicPlaybackService.isSupported`)。

## ⚠️ 回归修复:上面那次"通知扩展到影视"引入的「有声无画」(2026-09-13 白天,用户报"只有海报、EXO/IJK 都一样")

- **现象**:任何视频起播后**只有海报画面、声音完全正常**;EXO 与 IJK 表现一致;与分辨率/码率无关(4K 网盘源最早被发现,实际所有源都中招);release/debug 一致 —— 一度被误判为 R8 裁剪或 media3 1.9→1.11 升级所致(两者均已排除:release dex 里 `MediaCodecVideoRenderer` / `FfmpegAudioRenderer` / `TextureRenderView` / `SurfaceRenderView` 全部健在)。
- **真机定位手段(可复用)**:`adb shell uiautomator dump` + `dumpsys SurfaceFlinger --list`。视图树显示播放器容器里 `artworkView`(封面 `ImageView`,MATCH_PARENT)**与 `surfaceView` 同处 `mPlayerContainer`,且封面在其之上**;而 SurfaceFlinger 里该应用的 `ActivityRecord` z=2 > `SurfaceView(...)(BLAST)` z=-2 —— 即 **app 窗口整体压在视频层之上**,所以窗口内的封面一旦 VISIBLE 就会把视频完全盖住(而 `showVideoFrame()` 只管 `frameCover`,管不到封面)。
- **根因**:上面那次改动把起播分支写成 `audioPlayback = true`(判据从"纯音频"变成"有音轨"),而 `audioPlayback` 同时是 `updateMusicSession()` 里给 `mVideoView.setArtwork()` 的开关 → **所有带音轨的视频都会去 setArtwork,把画面压成一张海报**。`playArtwork` 由取流结果的 `artwork` 字段回填,源里没给 artwork/lyric 时它一直是空串,于是该分支每次 `updateMusicSession` 都命中。
- **修复(两处,`PlayContainer` + `MyVideoView`)**:
  ① `updateMusicSession()` 的封面分支补上 `Boolean.TRUE.equals(isAudioOnlyPlayback())`(只有**确定**是纯音频才放行)与画面未就绪 `!isStartedPlayState(...)`(兜底任何"画面已出仍显示封面"的时序)两个条件;
  ② `MyVideoView.showVideoFrame()`(画面已出的那一枪)顺手 `clearArtwork()`,把**「有画面」与「显示封面」做成互斥**,任何未来的时序回归都会被这一枪兜住。
- **⚠️ 保持不动的部分**:`audioPlayback` 仍表示"有音频轨",继续驱动会话/通知(影视也能下拉看到),**通知功能不受影响**。**教训:`audioPlayback` 这个名字下面挂了两个用途(通知 / 封面),改它的赋值语义必须同时核对两个调用点。**

### 同批修掉的两个连带缺陷(用户要求"做 1 和 2")

1. **纯音频判定恢复三态**(`hasAudioOnlyPlayback()` → `Boolean isAudioOnlyPlayback()`,返回 null = 取不到轨道信息)。
   迁移把它压成 boolean 后,**同一个概念在两处对「未知」给出不同结论**:
   - `hostPause()` 退后台是否保持播放:迁移前是 `!Boolean.TRUE.equals(getAudioOnlyPlayback())` → **null 会正常暂停**;迁移后 `!hasAudioOnlyPlayback()` 让 **null 变成"不暂停"** → 起播瞬间 `getTrackInfo()` 返回 null 时,影视退后台会继续出声。本次恢复成 `!Boolean.TRUE.equals(...)`,与迁移前一致。
   - 封面兜底:用 `Boolean.TRUE.equals(...)` —— 只有确定是纯音频才显示封面。
   ⚠️ **两个调用点的用法不同且都不能改**:一个要"只有确定是纯音频才特殊对待",另一个要"只有确定是纯音频才放行封面"。规则写在 `isAudioOnlyPlayback()` 的 javadoc 里。
2. **封面在途图片请求改为可取消**:`clearArtwork()` 原来只做 `GONE + setImageDrawable(null)`,而 Coil 3 的 `GenericViewTarget.onSuccess()` **不检查视图可见性**(读过 3.6.2 源码确认),晚到的位图照样落到 ImageView 上 —— 换集/换线/换源时可能把过期海报盖到新画面上。现在:
   - `ImgUtil.loadPlayerArtwork(url, view)` 返回 `Disposable`(新增入口,与 `load()` 的唯一区别是把请求句柄交回调用方);
   - `MyVideoView.setArtwork()` 先 `cancelArtworkRequest()` 再发新请求;`clearArtwork()` 先取消再隐藏。
   - 依据(读 `coil-core-android-3.6.2-sources.jar`):`ViewTargetRequestManager.dispose()` 会**同步**把 `currentDisposable` 置 null(`ViewTargetDisposable.isDisposed` 随即为 true)并 `post` 一个主线程取消任务;`OneShotDisposable.isDisposed = !job.isActive`。**不依赖 Coil 的 `ViewTargetRequestManager`,自己持有句柄 = 取消语义明确可证。**


## 权限梳理:补通知权限申请 + 移除 5 项零引用敏感权限(2026-09-13,用户选"权限一起重新梳理")

- **触发**:用户问"当前项目是否需要通知权限"。核查结论:清单里声明了 `POST_NOTIFICATIONS`,但**代码里从未申请** —— targetSdk 37,Android 13+ 该权限是运行时权限、默认拒绝,于是 `MusicPlaybackService` 的 `startForeground` 通知不显示(服务能起,但用户看不到播放控制,部分 ROM 还会限制无可见通知的前台服务)。
- **补申请**:`PermissionHelper.requestNotificationIfNeeded(Activity)`(`XXPermissions` 28.3 的 `PermissionLists.getPostNotificationsPermission()`,仅 API 33+ 生效,已授权则秒回)。**申请点 = 启动时**(`MainActivity.init()`,2026-09-13 用户要求"启动就弹窗通知申请");`PlayContainer.updateMusicSession()` 保留一次兜底(覆盖"启动那次拒绝、后来想开"的路径,系统在拒绝两次后不再弹窗、只会静默返回)。拒绝**不阻断任何功能**。
- **移除的权限**(全项目零引用 + manifest-merger 报告核对来源):`READ_PHONE_STATE`(唯一使用者 `ScreenUtils.checkIsPhone` 靠 `getPhoneType()`,该调用 API 23+ 需此权限)、`GET_TASKS`(Android 5+ 废弃)、`ACCESS_FINE_LOCATION`(DLNA 组播锁不需要定位)、`MOUNT_UNMOUNT_FILESYSTEMS`(来自 `com.lzy.net:okgo:3.0.4`,系统签名权限,第三方应用纯噪声)、`player` 模块里与 app 重复的 `READ/WRITE_EXTERNAL_STORAGE` 声明。
- **`ScreenUtils` 改造**:`isTv()` 原来 = `UI_MODE_TYPE_TELEVISION || (屏幕很大 && !是手机)`。本项目是纯手机定位(`abiFilters` 仅 arm64-v8a、TV/遥控适配代码已全删),真正的风险反而是**大屏手机被 `SCREENLAYOUT_SIZE_LARGE` 误判成 TV**(会莫名隐藏锁屏钮)。故收窄为只认"系统声明为 TV"这一个权威判据,电话权限随之移除。
- **保留未删**:`REQUEST_INSTALL_PACKAGES`(零引用,但将来做应用内自更新必须用它;已在清单注释里写明"不需要就删本行"的用户决策点)。
- **踩坑**:`READ/WRITE_EXTERNAL_STORAGE` 在 app 清单里已**合法声明**(带 `maxSdkVersion=32`),我又加了两条 `tools:node="remove"` → 清单合并直接 `Validation failed` 构建失败。**同一声明里不能既要又要**。删掉那两条 remove 即可(okgo 声明的那份没有 maxSdkVersion,合并会自动保留我们带上限的版本)。
- **验证**:APK 实际权限从 **20 条降到 16 条**(含 1 条 androidx 内部权限),敏感项全部消失;`aapt2 dump xmltree` 核对通过;已 `installDebug` 到 `V2425A`。清单核对命令:
  ```powershell
  aapt2 dump xmltree --file AndroidManifest.xml app\build\outputs\apk\debug\AVBox_debug.apk
  # 或看来源:app\build\outputs\logs\manifest-merger-debug-report.txt
  ```
- **真机验证点**:①设置里应用权限列表应无"电话/位置/读取手机状态";②播放音乐类内容后,下拉通知栏应出现播放控制(首次会弹通知授权);③即使拒绝通知,音乐照常播放;④DLNA 投屏扫描仍能发现设备(证明删定位没影响组播);⑤大屏/普通手机进播放页,锁屏钮照常出现。
- **可复用教训**:① **清单里声明 ≠ 已获得权限** —— 运行时权限必须显式申请,只看 Manifest 会漏;② 依赖库会通过清单合并塞权限进来,**裁权限必须看合并后的 APK 清单或 merger 报告**,不能只搜自己源码;③ `tools:node="remove"` 不能与同名声明共存,否则构建期直接失败。

## 手势修复:竖屏上下滑改为调亮度/音量,删除"竖屏上下滑切集"(2026-09-13,用户报"上下滑动都会快进,看看 fongmitv")

- **现象**:用户反馈"进度手感调钝没效果",并补充"我上下滑动屏幕都会快进",要求对照 fongmi。
- **定位(对照 `player/.../GestureVideoController.java` 与 fongmi 参考工程)**:① `onScroll` 的方向判定 `abs(distanceX) >= abs(distanceY)` 与 dkplayer 第 189 行**逐字一致**,不是问题;② 真正的元凶是 `onScroll` **开头**的竖屏切集分支: `isPortraitEpisodeSwipe` 的判据只有 `abs(Δy) > abs(Δx)`(**与 80dp 阈值无关**),命中后**无条件 `return true`** —— 于是竖屏下**所有**上下滑都被它吞掉,后面的 `changeBrightness`/`changeVolume` 分支在竖屏永远到不了,用户既调不了亮度音量、又看到画面在换。③ 该分支是本项目 `VodController` 时期的扩展;**fongmi 没有它**(其手势完全交给 dkplayer 的 `GestureVideoController.onScroll`,只有横滑进度 / 半屏亮度 / 半屏音量三种),所以 fongmi 的上下滑稳定可用。这也解释了"改灵敏度没感觉"——`SLIDE_POSITION_FULL_WIDTH_MS` 只管**横滑**。
- **处置(用户选方案 2:只调亮度/音量,去掉竖屏切集)**:整套删除 —— `onScroll` 里的切集分支、`isPortraitEpisodeSwipe()`、`portraitEpisodeSwipeThreshold()`、`showPortraitEpisodeTitle()`、常量 `PORTRAIT_EPISODE_SWIPE_DP`(80dp)与 `PORTRAIT_EPISODE_TITLE_SHOW_MS`、字段 `portraitEpisodeSwipeTriggered`(`onDown` 里的复位一并删)、`episodeTitleRunnable`(含 `onDetachedFromWindow` 的 removeCallbacks)、`PlayerUiState.portraitEpisodeTitleTemp`(删除后无任何读写方)。**竖屏与横屏手势行为自此完全一致**。
- **保留**:`VodControlListener.playNext/playPre` 与 `listener?.playNext(...)` 仍被播放完成自动下一集、键盘/远端切集等使用,不是本次删除对象。
- **顺带保留的上一轮改动**:`SLIDE_POSITION_FULL_WIDTH_MS = 240000f`(横滑调钝,用户上一轮要求)—— 它现在只作用于**横滑进度**,方向判定与边缘屏蔽不变。
- **验证**:`compileDebugKotlin` + `compileDebugJava` 通过,全库 `portraitEpisode|PORTRAIT_EPISODE|episodeTitleRunnable` 残留 0 处;已 `installDebug` 到 `V2425A`。
- **真机验证点**:①竖屏预览态上下滑出现亮度/音量药丸、数值随滑动变化(以前完全无反应);②横屏全屏同样;③"禁用手势控制"开关开启后两者都不响应;④横滑仍是进度(滑满一屏 = 4 分钟);⑤单击显隐、双击暂停、长按倍速不受影响。
- **可复用教训**:**在 onScroll 开头做"无条件 return true"的形态分支,一定会吃掉后面所有手势** —— 这类"竖屏扩展手势"必须与既有手势划清边界(要么只在真的触发动作时才 return,要么放到独立手势通道),否则表现为"某些手势莫名失效/变成别的动作"。另外:**用户报"某个常量调了没效果"时,先确认这个常量所在代码路径是否真的可达**,本次就是路径被前置分支挡住。

## 新功能:偏好设置新增「禁用手势控制」(2026-09-13,用户要求)

- **需求(用户原文)**:「在偏好设置页面无痕模式和弹幕开关中间新增加一项功能,名为禁用手势控制,小标题为开启后将禁用手势控制亮度和音量,默认关闭。功能的作用是开启后无法再播放器页面通过手势控制音量和亮度」。
- **落地**:`PreferenceSettingsPage` 在无痕模式与弹幕开关之间插入 `SettingsSwitchRow(title="禁用手势控制", subtitle="开启后将禁用手势控制亮度和音量")`;键 = `HawkConfig.GESTURE_CONTROL_DISABLED`(`"gesture_control_disabled"`,默认 false,已登记进 `KVKeySpec`);`SettingsState` 增字段并在 `loadState()` 读取;判定收口在新的 `util/GestureHelper.isControlDisabled()`(照 `HistoryHelper.isIncognito()` 的既有约定:开关判定集中一处,消费侧统一调用)。
- **⚠️ 实现要点(踩过)**:该判定**必须是独立方法 `canChangeBrightnessVolume(event)`,不能并进 `canHandleGesture(event)`**。第一版我并进去了,复查时发现点播侧 `isPortraitEpisodeSwipe()`(竖屏上下滑切集)内部第一行就是 `if (!canHandleGesture(e1)) return false` —— 并进去会**连竖屏切集一起禁掉**,超出用户要求。改为:两个控制器各自新增 `canChangeBrightnessVolume = canHandleGesture && !GestureHelper.isControlDisabled()`,只在**竖屏滑动方向确认后**、真正要调亮度/音量之前判一次;关闭时该分支静默 return(不调值、不弹提示)。
- **生效范围**:点播(`ComposeVideoController`)与直播(`ComposeLiveController`)两侧都生效(直播侧手势本就只有亮度/音量 + 左右快滑切台,后者走 `onFling` 不受影响)。**不受影响**:单击显隐控制条、双击播放/暂停、横滑进度、竖屏上下滑切集、左右快滑切台。
- **验证**:`compileDebugKotlin` + `compileDebugJava` 通过。真机验证点:①开关默认关,与弹幕开关之间显示且带小标题;②开启后点播页上下滑不再出现亮度/音量药丸、数值不变;③开启后直播页同样;④开启后**竖屏上下滑切集仍然可用**;⑤横滑进度、单击、双击不受影响;⑥重启应用后开关状态保持。

## 缺陷修复:换源后搜索被窄化到只搜得到一个源(2026-09-13,用户报"换源后有概率只能搜到玩偶4K,重启恢复正常")

- **现象**:在配置管理页切换到别的点播源后,搜索**只剩一个源**(用户看到的是「玩偶4K」)有结果,其他源一条都不出;退出应用重进即恢复。
- **定位(纯代码审查 + 源码证据)**:`SearchActivity` 的 companion 里有一份**会话级**的"勾选搜索源"缓存,注释自称"与旧 SearchActivity 静态字段一致":
  ```kotlin
  @Volatile var checkedSources: HashMap<String, String>? = null
  ```
  它只在 `== null` 时装载一次(`LaunchedEffect` 里 `if (SearchViewModel.checkedSources == null) ...`),之后**永不刷新**;而这份缓存是**按源 key 记**的,源 key 属于**具体的源集合**。搜索筛选是
  `getSourceBeanList().filter { isSearchable() && checked.containsKey(it.key) }` ——
  换源后拿"旧源 key"去过滤"新源源列表",**只有两边 key 相同的源能活下来**,于是表现为"只剩某一个源"。重启清掉静态缓存,重新按新地址从 KV 取(新地址无记录 ⇒ 回落到"全部可搜源")⇒ 恢复。
- **为什么换源链路没兜住**:换源会走 `AppBootstrap.onApiUrlChanged()`(作废内存配置 → 广播刷新 → 重载),但那条链路**没有清这份缓存**;而唯一的写入点 `SearchHelper.putCheckedSources()` **全项目 0 个调用点**(死代码),意味着缓存一旦装载就再无修正途径。
- **修复(两层,缺一不可)**:
  ① **主修**:`AppBootstrap.onApiUrlChanged()` 增加 `SearchViewModel.clearCheckedSources()` —— 换源收尾是唯一正确位置(只有点播地址真的变了才需要失效);注释同步改成"四步"。
  ② **兜底**:`SearchActivity` 的装载条件从"只判 `== null`"改为 `isCheckedSourcesStale()`,判据三条 —— 未装载 / 属于别的源地址 / **选择里的源 key 已对不上当前源列表**(`SearchHelper.isSelectionStale`)。第三条正是本 bug 的不变量:选择永远是当前源 key 的子集。另外每次 `Boot.Ready` 也重对一次基准,避免"切源后配置尚未拉完时装载到错误基准"。
  ③ 顺带删除死方法 `SearchHelper.putCheckedSources()`。
- **遗留说明(已查,非本次问题)**:`detail` 页的"快速搜索"(`DetailActivity.startSourceSearch`)**不走这份会话缓存**,而是每次 `SearchHelper.getSourcesForSearch()` 按当前地址现取,所以不受本 bug 影响。但那里只按 `isSearchable()` 过滤、又在后续按 `isQuickSearch()` 过滤,两个口径不一致 —— 属既有行为,本次不动。
- **验证**:新增 `SearchHelperTest` 5 例(纯判定与 Android 解耦后单测),含"部分匹配也必须判过期"这一用户实测形态;`KVDecoderTest` 20 例 + `SearchHelperTest` 5 例全过,编译通过。**真机复现路径待用户确认**(切源 → 直接搜索,应可搜到新源的全部可搜源)。

## 缺陷修复:纯音频(音乐)SurfaceView 渲染洞穿 —— 快照变白/回前台透视桌面(2026-09-13,用户报三联症状)

- **现象**:竖屏详情页播音乐(易听音乐,纯音频)应用内画面黑色(正常);退后台 → 多任务卡片播放器区域**变白**;从桌面回前台 → 过渡动画中播放器区域**闪烁透视到桌面**。用户实测补充:**仅 SurfaceView 渲染有此问题,TextureView 三症状全无**。
- **根因**:SurfaceView 的画面在独立于应用窗口的合成层上(本渲染视图 `SurfaceRenderView` 用 `PixelFormat.RGBA_8888` 可透明格式),应用窗口在播放器矩形被"打洞":无视频帧的内容全靠空 Surface 垫底呈黑;退后台任务快照里 Surface 垫底消失,该区域只剩窗口底色;回前台 Surface 重建前洞完全透明透视壁纸。TextureView 画在应用窗口图层内,无帧呈黑、快照与过渡动画全部正常。
- **修复(双层)**:
  ① 起播预判:`PlayContainer.looksLikeAudioUrl(url)`(mp3/m4a/aac/flac/wav/ogg/oga/opus/wma 后缀,去 query/fragment)→ `updateCfg` 后 `mVideoView.setRenderViewFactory(TextureRenderViewFactory.create())`,补住「起播 → 轨道信息就绪」之间退后台的空窗;
  ② 轨道信息兜底:`STATE_PLAYING` 时 `ensureAudioOnlyRender()`(`Boolean.TRUE.equals(isAudioOnlyPlayback())` 且 `MyVideoView.isSurfaceRenderActive()`)→ `switchRenderToTexture()`(`setRenderViewFactory(Texture)` + fork protected `addDisplay()` 热切换,音频不中断)。
- **审查确认的关键机制**:
  - **`replay(false)` 不重建渲染视图**(fork:`keepRenderViewOnReset` 分支只 reset + `startPrepare(false)`;普通分支 `startPrepare(true,true)` 也只 rebind)—— `addDisplay()` 只在 `start()`→`startPlay` 执行 ⇒ reusePlayer(切线路/清晰度/换集续播)路径①不生效,**必须靠②在 STATE_PLAYING 后重建**;这也是双层缺一不可的原因。
  - 旧 SurfaceView 摘除后 `surfaceDestroyed` 异步回调 `setDisplay(null)`(ExoMediaPlayer → `mInternalPlayer.setVideoSurface(null)`)落在**无视频轨**的播放器上是无操作,不影响新 Texture 挂载(热切换仅音频内容触发)。
  - artworkView 永在渲染视图之上(`addDisplay` 恒插 index 0);MeasureHelper 无视频尺寸时按父容器铺满;换集/换源下次 `updateCfg` 按用户设置恢复渲染类型,影视不受影响;误判(音频后缀实为视频)无功能损失,TextureView 照常渲染。
  - IJK `getTrackInfo` 已过滤内嵌封面(`isAttachedPicture`);EXO 对 mp3/m4a/flac/ogg 的内嵌封面走 metadata 不产生视频轨 → `isAudioOnlyPlayback()` 三态判定可靠。
- **验证**:`:app:compileDebugJavaWithJavac` + `:app:assembleDebug` 通过(BUILD SUCCESSFUL)。
- **真机验证点**:①设置保持「画面渲染: SurfaceView」播音乐 → 退后台多任务卡片播放器区域黑色(不再变白);②回前台过渡不透视桌面;③音乐后台续播正常;④正常视频画面/声音/进度正常(渲染仍走 SurfaceView);⑤切线路/清晰度/换集后音乐仍正常。
- **已知限制(未覆盖)**:直播页(广播类纯音频频道)不在本次修复范围(LivePlayerManager 独立链路);无后缀的音频直链/代理地址在轨道信息就绪前有短暂 Surface 窗口(秒级)。

## 缺陷修复:真机两起崩溃(2026-09-13,用户报"应用刚刚是不是发生了崩溃",crash buffer 抓到)

- **崩溃①(11:52,旧构建)**:`NullPointerException: JSONObject.getInt on null` ← `PlayContainer.getSavedProgress` 读 `mVodPlayerCfg.getInt("st")` 为 null(原 try 只 catch `JSONException`,NPE 直接穿透);触发链 = 详情页**中央播放键**(`PlayerCenterControls → onPlayPauseClicked → ControlWrapper.togglePlay → start() → startPlay → ProgressManager.getSavedProgress`),即 `setInitBundle` 之前的空窗期点中央播放。**修复**:`st = (mVodPlayerCfg == null) ? 0 : mVodPlayerCfg.optInt("st", 0)`(顺手用 optInt 免掉 try/catch),空窗期点击不崩、片头跳过按 0 处理。
- **崩溃②(12:39,新构建)**:`IllegalArgumentException: Key "玩偶|131202" was already used` ← 详情页**相关推荐 LazyRow**(`RelatedSection` 的 item key = `sourceKey|id`)。根因:`DetailViewModel` 聚合搜索回调里 `relatedVideos.value = relatedVideos.value + related` **多源追加不去重**,同一 sourceKey|id 出现两次(玩偶源返回重复条目)即撞 key 闪退。**修复**:追加前按 `candidateKey(sourceKey|id)` 去重(`mapTo(HashSet)` 建 seen 集 + `seen.add` 过滤,同源同 id 留第一条;每次搜索 `relatedVideos` 重置,seen 随之重建)。
- **与渲染修复无关**:两处崩溃路径均不在纯音频渲染修复的改动范围(装机构建时间线佐证:崩溃①在 12:19:32 装机前、②在其后但属既有缺陷)。
- **验证**:`:app:assembleDebug` BUILD SUCCESSFUL。真机复测点:①详情页加载完成前立刻点中央播放键不崩;②滚动/等待相关推荐加载不崩(玩偶源可复现时);③相关推荐无重复卡片。
