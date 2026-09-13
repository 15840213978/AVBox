package com.github.tvbox.osc.util.kv;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.kvcodec.KVDecoder;
import com.google.gson.JsonArray;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 「键 → 显式类型」登记表(avbox-kv-mmkv-spec §4.3 / §7-Q3):集合与嵌套泛型的元素类型**必须**在这里声明。
 *
 * <p>为什么必须有这张表:Java 泛型擦除后,调用侧默认值 `new ArrayList()` / `new HashMap<>()` 与 `null`
 * 都带不来元素类型,按它们解码会得到元素为 LinkedTreeMap 的集合 —— 调用侧 `(String)` 取值时
 * ClassCastException,或写回时把元素类型写坏(gson 2.13+ 版本的 Hawk 正是栽在"用匿名 TypeToken
 * 捕获类型变量"推元素类型上,旧键会整体读不出来)。故改为一处集中、显式、可审查的登记。
 *
 * <p>调用侧仍保持 `KV.get(key, def)` 形态,可机械替换;未登记的键在给了具体 defaultValue 时
 * (String / 基本类型)照常工作,复杂类型未登记则打 `echo-kv` 日志并回落默认值,不静默。
 */
public final class KVKeySpec implements KVDecoder.TypeRegistry {

    /** 动态键族前缀(键名带变量后缀,无法逐键登记) */
    static final String JS_RUNTIME_PREFIX = "jsRuntime_";
    static final String CACHE_PREFIX = "cache_";

    private static final Map<String, Type> TYPES = new HashMap<>();

    /** 由 {@link KVCodec} 在类初始化时装配 */
    KVKeySpec() {
    }

    @Override
    @Nullable
    public Type typeOf(@NonNull String key) {
        Type type = TYPES.get(key);
        return type != null ? type : typeOfDynamic(key);
    }

    /**
     * 动态键族的类型:`live_group_index[_<直播源地址>]` → int;`jsRuntime_*` / `cache_*` → String。
     * 这些键名带变量后缀,无法逐键登记,只能按前缀归类。
     */
    @Nullable
    static Type typeOfDynamic(@NonNull String key) {
        if (key.startsWith(HawkConfig.LIVE_GROUP_INDEX)) return TYPES.get(HawkConfig.LIVE_GROUP_INDEX);
        if (key.startsWith(JS_RUNTIME_PREFIX) || key.startsWith(CACHE_PREFIX)) return TYPES.get(HawkConfig.API_URL);
        return null;
    }

    /** 静态登记的键数量(自检用) */
    static int registeredCount() {
        return TYPES.size();
    }

    private static void register(@NonNull String key, @NonNull Object sample) {
        TYPES.put(key, TypeToken.get(sample.getClass()).getType());
    }

    private static void register(@NonNull String key, @NonNull TypeToken<?> type) {
        TYPES.put(key, type.getType());
    }

    static {
        // ---- String ----
        register(HawkConfig.API_URL, "");
        register(HawkConfig.EPG_URL, "");
        register(HawkConfig.API_LINE_SOURCE, "");
        register(HawkConfig.HOME_API, "");
        register(HawkConfig.DEFAULT_PARSE, "");
        register(HawkConfig.IJK_CODEC, "");
        register(HawkConfig.LIVE_CHANNEL, "");
        register(HawkConfig.DOH_JSON, "");
        register(HawkConfig.LIVE_API_URL, "");
        register(HawkConfig.NOW_DATE, "");
        register(HawkConfig.REMOTE_TVBOX, "");
        register(HawkConfig.DANMU_API, "");
        register(HawkConfig.THEME_PALETTE_STYLE, "");
        register(HawkConfig.HOME_HOT, "");
        register(HawkConfig.HOME_HOT_DAY, "");

        // ---- int ----
        register(HawkConfig.PLAY_TYPE, 0);
        register(HawkConfig.LIVE_PLAY_TYPE, 0);
        register(HawkConfig.PLAY_RENDER, 0);
        register(HawkConfig.PLAY_SCALE, 0);
        register(HawkConfig.LIVE_PLAY_SCALE, 0);
        register(HawkConfig.PLAY_TIME_STEP, 0);
        register(HawkConfig.DOH_URL, 0);
        register(HawkConfig.HISTORY_NUM, 0);
        register(HawkConfig.LIVE_CONNECT_TIMEOUT, 0);
        register(HawkConfig.SUBTITLE_TEXT_SIZE, 0);
        register(HawkConfig.SUBTITLE_TIME_DELAY, 0);
        register(HawkConfig.SUBTITLE_EXO_SCALE, 0);
        register(HawkConfig.SUBTITLE_TEXT_STYLE, 0);
        register(HawkConfig.LIVE_GROUP_INDEX, 0);
        register(HawkConfig.SCREEN_DISPLAY, 0);
        register(HawkConfig.SEARCH_THREADS, 0);
        register(HawkConfig.LONG_PRESS_SPEED, 0);
        register(HawkConfig.BUFFER_TIMES, 0);
        register(HawkConfig.PRELOAD_DURATION, 0);
        register(HawkConfig.EXO_CACHE_SIZE_MB, 0);
        register(HawkConfig.DANMU_MAX_LINE, 0);
        register(HawkConfig.THEME_SOURCE, 0);
        register(HawkConfig.THEME_MODE, 0);
        register(HawkConfig.THEME_SEED, 0);
        register(HawkConfig.LIQUID_GLASS_BLUR, 0);
        register(HawkConfig.LIQUID_GLASS_DISTORTION, 0);

        // ---- boolean ----
        register(HawkConfig.PLAYER_IS_LIVE, false);
        register(HawkConfig.LIVE_CHANNEL_REVERSE, false);
        register(HawkConfig.LIVE_CROSS_GROUP, false);
        register(HawkConfig.LIVE_SHOW_NET_SPEED, false);
        register(HawkConfig.LIVE_SHOW_RESOLUTION, false);
        register(HawkConfig.LIVE_SHOW_TIME, false);
        register(HawkConfig.IJK_CACHE_PLAY, false);
        register(HawkConfig.M3U8_PURIFY, false);
        register(HawkConfig.AUTO_SWITCH_LINE, false);
        register(HawkConfig.DEFAULT_LOAD_LIVE, false);
        register(HawkConfig.INCOGNITO, false);
        register(HawkConfig.GESTURE_CONTROL_DISABLED, false);
        register(HawkConfig.PLAY_TUNNEL, false);
        register(HawkConfig.PLAY_PREFER_AAC, false);
        register(HawkConfig.PRELOAD_NEXT_EPISODE, false);
        register(HawkConfig.PLAY_CACHE, false);
        register(HawkConfig.DANMU_OPEN, false);
        register(HawkConfig.DANMU_RANDOM_COLOR, false);
        register(HawkConfig.DANMU_API_USE_DEFAULT, false);
        register(HawkConfig.LIQUID_GLASS_ENABLED, false);

        // ---- float ----
        register(HawkConfig.DANMU_SPEED, 0f);
        register(HawkConfig.DANMU_ALPHA, 0f);
        register(HawkConfig.DANMU_SIZE_SCALE, 0f);
        register(HawkConfig.SUBTITLE_EXO_POSITION, 0f);

        // ---- 集合(元素类型必须显式声明,否则退化为 LinkedTreeMap)----
        register(HawkConfig.SEARCH_HISTORY, new TypeToken<ArrayList<String>>() {
        });
        register(HawkConfig.API_HISTORY, new TypeToken<ArrayList<String>>() {
        });
        register(HawkConfig.EPG_HISTORY, new TypeToken<ArrayList<String>>() {
        });
        register(HawkConfig.LIVE_API_HISTORY, new TypeToken<ArrayList<String>>() {
        });
        register(HawkConfig.API_LINE_LIST, new TypeToken<ArrayList<String>>() {
        });
        // 配置管理订阅源:每项 "名字\t链接"
        register(HawkConfig.SUBSCRIBE_LIST, new TypeToken<ArrayList<String>>() {
        });
        register(HawkConfig.LIVE_SUBSCRIBE_LIST, new TypeToken<ArrayList<String>>() {
        });
        // 直播分组是 Gson 节点树,不能按 List 处理
        register(HawkConfig.LIVE_GROUP_LIST, TypeToken.get(JsonArray.class));

        // ---- 映射 ----
        // 直播源配置的 header/ua:ApiConfig 写入的就是 HashMap<String,String>(KV.put),
        // 这里必须登记同一类型 —— 此前登记成 String,读取侧 Gson 用 String 解析对象原文直接抛错,
        // 又被 KV.get(key)(quiet 副本)静默吞成 null,症状=直播源配置的 UA/Referer/header 全部失效
        register(HawkConfig.LIVE_WEB_HEADER, new TypeToken<HashMap<String, String>>() {
        });
        // 源级卡片点击策略:HashMap<sourceKey, "detail">
        register(HawkConfig.SOURCE_CARD_POLICY, new TypeToken<HashMap<String, String>>() {
        });
        // 嵌套泛型:HashMap<点播源地址, HashMap<sourceKey, "1">>,读侧必须显式 Type
        register(HawkConfig.SOURCES_FOR_SEARCH, new TypeToken<HashMap<String, HashMap<String, String>>>() {
        });
    }
}
