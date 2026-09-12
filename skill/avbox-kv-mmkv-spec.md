# AVBox KV 存储迁移 Spec:Hawk → MMKV

> 项目:AVBox(TVBox OSC fork;仓库根目录 = 本文件所在目录的上一级)
> 配套:先读 `SKILL.md`(通用规范 + 文档地图)与 `avbox-mobile-ui-spec.md`(技术基线)。
> 状态:**已确认 — §7 决策已定(2026-09-13),可开始 P0 实施**。实施完成后,过程记录追加到 `history/features.md`。
> 触发背景:2026-09-13 排查"配置管理页源列表添加后重进即消失",根因 = gson 2.13+ 与 Hawk 2.0.1 不兼容(详见 `history/features.md` 同日记录)。

## 0. 摘要

用 MMKV 替换 Hawk,承载全项目键值存储(设置项 / 历史 / 源列表 / 主题等)。
分三阶段推进:①KV 门面(API 对齐 Hawk,业务侧近乎零改动) ②双写过渡 + 一次性数据迁移 ③全量切换后移除 Hawk 与 Conceal。
预估工作量 1~2 人天;最大的两个风险是「集合类型语义对齐」与「数据迁移窗口期(gson 必须仍 ≤2.12)」。

## 1. 现状盘点(2026-09-13 实测)

### 1.1 调用面

| 项 | 数据 |
|---|---|
| Hawk 调用点 | **34 个文件、约 250+ 处**(`Hawk.get/put/contains/delete`) |
| 高密度文件 | `ApiConfig` 48 / `LivePlayActivity` 35 / `SettingsPage` 28 / `HistoryHelper` 14 / `DanmuHelper` 12 |
| 键总量 | `HawkConfig` 定义 75 个(另有 `DEFAULT_LOAD_LIVE` 等少量直接字符串键) |
| 其他存储 | 2 处独立 SharedPreferences(`thunder` 雷电标识、`AudioTrackMemory`),与 Hawk 无关,不在本次范围 |

### 1.2 键类型分布(决定编码规则的关键)

- **基本类型(约 60 个)**:String / int / boolean / long —— KV 直接走 MMKV 原生 API。
- **集合与复杂类型(10 个,迁移重点)**:

| 键 | 类型 | 备注 |
|---|---|---|
| `search_history` / `api_history` / `live_api_history` / `api_line_list` | `ArrayList<String>` | 历史与线路列表 |
| `subscribe_list` / `live_subscribe_list` | `ArrayList<String>` | 每项 `名字\t链接`(配置管理页) |
| `live_group_list` | **`JsonArray`(Gson 节点树)** | 直播分组,注意它不是 List |
| `source_card_policy` | `HashMap<String, String>` | 源级卡片点击策略 |
| `sources_for_search` | **`HashMap<String, HashMap<String, String>>`** | 嵌套泛型,读侧需要显式 TypeToken |
| `doh_json` | String(JSON 文本) | 不是集合 |

### 1.3 为什么迁(Hawk 2.0.1 的硬伤)

1. **上游停更**(2018 年后无版本)且与 gson 2.13+ 不兼容:其 `HawkConverter` 用匿名 `TypeToken` 捕获类型变量,Gson 2.13 起直接抛 `IllegalArgumentException` → **所有集合键读取静默失败**(2026-09-13 实测;当前靠锁定 gson 2.10.1 规避)。
2. **静默失败**:`put` 失败仅返回 false(调用方普遍不检查)、`get` 失败返回默认值 —— "读-改-写"场景会静默丢数据。
3. 依赖 Conceal(native `.so`)做加密,链路重,同样停更。

## 2. 目标与非目标

**目标**

- G1 行为等价:Hawk 能存/读的所有类型,KV 一致支持(含集合与 JsonArray)。
- G2 数据零丢失:一次性迁移全部既有键;迁移幂等、可重试。
- G3 解除 gson 版本枷锁(迁移完成后可自由升级 gson)。
- G4 不再静默失败:写入失败可感知(返回 boolean + 错误日志)。

**非目标**

- N1 不改业务调用语义(不借机重构业务逻辑)。
- N2 不引入多进程/多实例等新能力(保持单进程单实例)。
- N3 不动 Room 与文件缓存等其他存储。

## 3. 方案总览(分阶段)

| 阶段 | 内容 | 出口条件 |
|---|---|---|
| P0 门面 | 新增 `util/KV`(内部 MMKV + Gson 编码)+ 依赖;`Hawk` 原样保留 | 编译通过;KV 各类型读写自测通过 |
| P1 迁移 | `KV.init` 内一次性搬 Hawk → MMKV(幂等,带完成标记) | 迁移日志逐键成功;抽样比对一致 |
| P2 切换 | 34 文件 `Hawk.` → `KV.` 批量替换;`Hawk` 仅保留供回滚 | §6.2 全量回归通过 |
| P3 清理 | 删除 Hawk/Conceal 依赖、迁移代码与旧数据 | **不设观察期**:P2 全量回归(§6.2)通过后立即执行(用户决策) |

## 4. 详细设计

### 4.1 依赖与初始化

- `gradle/libs.versions.toml` 新增 `mmkv = "2.4.2"`(Maven Central 最新正式版,2026-08-21 发布)。
- `App.onCreate` 中 `KV.init(this)`;`Hawk.init(this).build()` 保留至 P3(仅服务一次性迁移)。
- MMKV 实例:单实例 `MMKV.mmkvWithID("avbox_kv", MMKV.SINGLE_PROCESS_MODE)`(**不加密**,见 §4.4)。

### 4.2 KV 门面 API(对齐 Hawk,业务侧近乎零改动)

```java
public final class KV {
    public static void init(Context context);
    public static <T> boolean put(String key, T value);      // 失败返回 false 并 LOG.e
    public static <T> T get(String key);                     // 不存在/失败 → null
    public static <T> T get(String key, T defaultValue);     // 失败 → defaultValue + LOG.e
    public static boolean contains(String key);
    public static void delete(String key);
}
```

调用点替换规则:`Hawk.get(key, def)` → `KV.get(key, def)`;`put` / `contains` / `delete` 同理。
签名完全一致 ⇒ 可对 34 个文件做 `Hawk.` → `KV.` 全局替换 + import 替换,类型不匹配处由编译器兜底。

### 4.3 类型编码规则

| 值类型 | 编码方式 |
|---|---|
| String / int / long / boolean / float / double / byte[] | MMKV 原生 `encode` / `decodeXxx` |
| `List<T>` / `Set<T>` / `Map<K,V>` / `JsonArray` / 任意对象 | Gson 序列化为文本存 String;写侧同时记录**元素类型标记** |

- 读取用 **`TypeToken.getParameterized(...)`** 恢复类型 —— **这是对 Hawk 缺陷的直接修正:严禁再用匿名 `TypeToken<T>` 捕获类型变量**。
- 嵌套泛型(`sources_for_search` 等)由 KV 内建 **"键 → Type"注册表**统一处理(用户决策,§7-Q3):复杂键集中登记显式 Type,调用点保持 `KV.get(key, def)` 形态、可机械替换;注册表未登记的复杂类型 → `LOG.e` + 返回默认值(不静默)。
- **JsonArray 必须单独支持**(`live_group_list` 存的就是 Gson 节点树,不能按 List 处理)。

### 4.4 加密

- **不加密(用户决策,2026-09-13)**:采用 MMKV 默认行为,明文 mmap 存于应用私有目录。相对 Hawk/Conceal 属安全级别下调(root / adb 备份场景可读明文),用户已确认接受。
- 回补路径:MMKV 支持 `reKey(newKey)` 原地加密,未来若需加密可在任一发版补上,不丢数据。

### 4.5 数据迁移(一次性,幂等)

- 触发:`KV.init` 中若 `kv_migrated_from_hawk` 标记不为 true,且 Hawk 中存在任一已知键。
- 流程:按 §1.2 迁移表逐键 `Hawk.get` → `KV.put` → 全部成功后写入完成标记;任一步异常则不写标记(下次启动重试),`LOG.e` 汇总失败键。
- ⚠️ **窗口期约束:迁移必须在 gson ≤2.12 时执行** —— Hawk 的集合读取依赖 gson 旧行为;P2 切换完成后才允许升级 gson。
- 迁移完成后**不保留** Hawk:按 P3 在回归通过后立即移除依赖、迁移代码与旧数据(用户决策;放弃回滚退路,见 §6.3)。

### 4.6 错误处理与可观测性(吸取 Hawk 教训)

- `put` 返回 boolean ⇒ 新增调用点必须处理(至少 `LOG.e`);批量替换时同步补检查。
- 集合读取:区分「键不存在」(返回空集合,正常)与「解码失败」(返回默认值 + `LOG.e` + 键名)。
- 迁移与关键读写打点前缀 `echo-kv*`,经 `util/LOG` 落盘(`FILE_LOG_PREFIXES` 增补),真机取日志:`adb shell run-as <applicationId> cat files/<日志名>`(该 ROM 吞 logcat,此为既有约定)。

## 5. 实施步骤(P0→P3)

1. P0:依赖 + `KV` 门面(编码/键→Type 注册表/迁移) + 集合与嵌套泛型单测。
2. P0 验证:真机各类型 `put/get` 冒烟(可用配置管理页源列表跑通读-改-写)。
3. P1:迁移表落地,审查迁移日志(逐键成功)。
4. P2:全局替换 34 文件 → 编译纠错 → 按 §6.2 全量回归。
5. P3:P2 验收通过后**立即**删除 Hawk/Conceal 依赖、迁移代码与旧数据(用户决策:不设观察期)。

## 6. 风险、回滚与验收

### 6.1 风险

| 风险 | 等级 | 缓解 |
|---|---|---|
| 集合语义对齐出错(JsonArray / 嵌套 Map) | 高 | 编码规则单测 + 迁移后抽样比对 |
| 迁移窗口错过(gson 已升 2.13+) | 高 | P1 前禁止动 gson;迁移逻辑先本地验证 |
| MMKV native `.so` 兼容(本机为 16KB 页设备) | 中 | P0 装机即验证初始化与读写 |
| 回归面大(34 文件 250+ 处) | 中 | 批量替换 + 编译器兜底 + §6.2 清单逐项过 |
| P3 移除 Hawk 后无回滚退路(不保留决策) | 中 | P3 前完成 §6.2 全量回归;真机正常使用数日后再执行 P3 |

### 6.2 验收清单(逐项真机确认)

- 设置页全部选项(播放/偏好/预载/主题)改动后重启保持。
- 配置管理页:添加/编辑/删除源、切换源、重启保持。
- 搜索历史:新增/删除/清空、重启保持。
- 线路历史与自动换线、直播分组与直播源切换。
- 播放位置续播、弹幕配置、DoH 配置、无痕模式。
- 卸载重装(空数据)走默认值路径正常。

### 6.3 回滚

- P1/P2 期间 Hawk 数据未被清理,代码回退即可恢复;KV 侧新增数据如需保留,反向补一次 Hawk 写入。
- ⚠️ **P3(Hawk 移除)后无回滚退路**(用户决策:不保留):MMKV 侧数据问题只能清库重建。P3 必须待 §6.2 全量回归通过后才可执行。

## 7. 决策记录(2026-09-13 用户确认,取代原"未决问题")

| # | 问题 | 决策 | 对方案的影响 |
|---|---|---|---|
| Q1 | 是否启用 MMKV 加密(cryptKey)? | **不加密** | §4.1 初始化不带 cryptKey;§4.4 采用默认明文(接受相对 Hawk 的安全级别下调);未来可用 `reKey` 回补 |
| Q2 | 旧 Hawk 依赖保留几个版本周期? | **不保留**(不设观察期) | §3 阶段表 P3 改为"§6.2 全量回归通过后立即执行";§4.5 迁移后即移除;§6.3 起无回滚退路 |
| Q3 | 嵌套泛型处理机制? | **KV 内建"键 → Type"注册表** | §4.3 按注册表实现(复杂键集中登记);全部调用点保持 `KV.get(key, def)` 可机械替换 |
| Q4 | 是否借迁移统一键命名 / 清理废弃键? | **不做** | 迁移保持最小变更,不做键名重构与废弃键清理 |

> 以上四项已定,实施按 §5 从 P0 开始;若实施中发现决策需要调整,在此表追加修订记录(注明日期与原因)。
