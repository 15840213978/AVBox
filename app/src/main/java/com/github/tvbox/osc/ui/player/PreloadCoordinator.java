package com.github.tvbox.osc.ui.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.Observer;
import androidx.lifecycle.MutableLiveData;

import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.thunder.Jianpian;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.github.tvbox.osc.player.PreloadManagerHolder;
import com.github.tvbox.osc.util.KV;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 业务层预载协调器（预载方案第一期,见 skill/avbox-preload-next-episode-spec.md §5.2/§5.3）。
 *
 * <p>状态机:IDLE → RESOLVING(预解析中) → READY/PRELOADING → HIT;任意时刻可 invalidate() 回 IDLE。
 * PlayContainer 在正片稳定播放后调 {@link #scheduleEvaluate}（延迟 2s,避开正片冷启动抢带宽）,
 * 本类负责「何时预载谁」:校验开关 → 找下一集 → 发起预解析(getPlayForPreload 独立通道) →
 * 可预载判定(§5.3) → 调 PreloadManagerHolder.preload 触发引擎层内存预载。
 * 「怎么预载与命中」在引擎层(PreloadManagerHolder + app ExoPlayer.prepareAsync 注入)。
 *
 * <p>与真实播放的串扰防护:预解析走 {@link SourceViewModel#getPlayForPreload} 独立 LiveData 通道
 * 与独立 seq;本类再以「发起时的目标集 progressKey」做 token 校验,作废切集后迟到的结果。
 */
public final class PreloadCoordinator {
    /** 正片稳定播放后延迟评估,避开正片冷启动缓冲抢带宽(规格 §5.2) */
    private static final long EVALUATE_DELAY_MS = 2000L;
    /** 弱网下正片缓冲会清预载,冷却期内不重新预载,避免 clear/re-add 循环 */
    private static final long BUFFERING_COOLDOWN_MS = 10_000L;
    /** 预载起点对齐缓存 TTL:时效签名类 url 过期快,超时不复用(预载数据浪费但无害) */
    private static final long CACHE_TTL_MS = 60_000L;
    /** 预解析的 progressKey/subtKey 后缀,与真实播放键区分(规格 §5.2) */
    private static final String PRELOAD_KEY_SUFFIX = "-preload";

    /** 评估快照:由 PlayContainer 在调用点收集,协调器不回读 PlayContainer 内部状态 */
    public static final class Snapshot {
        public final Context context;
        public final String sourceKey;
        public final String playFlag;
        /** 当前集 progressKey(用于本集 gaveUp 判定) */
        public final String currentKey;
        /** 下一集 progressKey(预载 token,真实切到该集时命中查找的 key 同源) */
        public final String nextKey;
        /** 下一集原始取流地址(vs.url,未拼接 playUrl) */
        public final String nextUrl;
        /** 下一集字幕缓存键(预解析独立加后缀,不污染弹幕/字幕,规格 §7) */
        public final String nextSubtitleKey;
        /** 片头跳过秒数(mVodPlayerCfg.st,按剧持久化,下一集直接复用预测,规格 §5.4) */
        public final long startSkipMs;
        /**
         * 当前播放器实例是否 app ExoPlayer(规格 §1:IJK 内核不参与预载)。
         * 取实际实例判定而非配置值,自动涵盖 playerCfg 覆盖 / rtmp 强制 IJK / 自动重试切内核等场景。
         */
        public final boolean exoKernel;

        public Snapshot(Context context, String sourceKey, String playFlag, String currentKey,
                        String nextKey, String nextUrl, String nextSubtitleKey, long startSkipMs, boolean exoKernel) {
            this.context = context;
            this.sourceKey = sourceKey;
            this.playFlag = playFlag;
            this.currentKey = currentKey;
            this.nextKey = nextKey;
            this.nextUrl = nextUrl;
            this.nextSubtitleKey = nextSubtitleKey;
            this.startSkipMs = startSkipMs;
            this.exoKernel = exoKernel;
        }
    }

    private final SourceViewModel sourceViewModel;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean evaluatePending = new AtomicBoolean(false);

    /** 在途预解析的目标集 key(token);null = 无在途请求 */
    private String requestToken;
    /** 本集判定「不可预载/放弃」标记(key=当前集 progressKey),规格 §6:静默放弃本集内不再重试 */
    private String gaveUpKey;
    /** 已成功发起预载的目标集 key,同集不重复预解析 */
    private String preloadedKey;
    /** 最近一次评估快照(结果回调时复用) */
    private Snapshot activeSnapshot;
    /** 弱网冷却期截止时间 */
    private long bufferingCooldownUntil;
    /** 观察者是否已注册 */
    private boolean observing;
    /**
     * 已完成的预解析结果缓存(key=目标集真实 progressKey)。
     * 主链路切到该集时经 consumeResult 复用,跳过二次 getPlay——时效签名类源每次取流 url 都会变,
     * 不复用则预载 url 与播放 url 永不一致、预载永不命中(FongMi VodPreloader 同款设计,规格 §2)。
     */
    private JSONObject cachedInfo;
    private String cachedKey;
    private long cachedAt;

    private final Observer<JSONObject> preloadResultObserver = new Observer<JSONObject>() {
        @Override
        public void onChanged(JSONObject info) {
            handlePreloadResult(info);
        }
    };

    public PreloadCoordinator(SourceViewModel sourceViewModel) {
        this.sourceViewModel = sourceViewModel;
        MutableLiveData<JSONObject> channel = sourceViewModel.preloadResult;
        if (channel != null) {
            channel.observeForever(preloadResultObserver);
            observing = true;
        }
    }

    /** 正片进入稳定播放态(STATE_PLAYING)时由 PlayContainer 调用;幂等,不重复排队 */
    public void scheduleEvaluate(Snapshot snapshot) {
        if (snapshot == null || !PreloadManagerHolder.enabled()) {
            LOG.i("echo-preload-skip: " + (snapshot == null ? "no next episode" : "switch off"));
            return;
        }
        postEvaluate(snapshot, EVALUATE_DELAY_MS);
    }

    /** 延迟排队一次评估;已有排队则忽略(幂等)。冷却期内的重排也走这里 */
    private void postEvaluate(Snapshot snapshot, long delayMs) {
        if (!evaluatePending.compareAndSet(false, true)) return;
        handler.postDelayed(() -> {
            evaluatePending.set(false);
            evaluate(snapshot);
        }, delayMs);
    }

    /**
     * 失效事件(切集/换线/换源/重播,PlayContainer.play 开头调):只作废在途评估与预解析。
     * 预载数据**不在此处清理**——切集目标大概率就是预载目标,数据留给命中注入消费;
     * 真正清理时机:play() 确认未复用预解析(切的不是目标集)或 evaluate 换了新目标。
     */
    public void invalidate() {
        handler.removeCallbacksAndMessages(null);
        evaluatePending.set(false);
        requestToken = null;
        activeSnapshot = null;
    }

    /** 确认预载数据失效(切到的不是预载目标集,或预解析缓存未复用):清引擎层数据 */
    public void dropPreloadData() {
        preloadedKey = null;
        PreloadManagerHolder.clearAll();
    }

    /** 正片进入缓冲态(弱网仲裁,规格 §5.5):立即让路,清预载数据,冷却期内不重启 */
    public void onMainPlayerBuffering() {
        if (!PreloadManagerHolder.enabled()) return;
        bufferingCooldownUntil = System.currentTimeMillis() + BUFFERING_COOLDOWN_MS;
        PreloadManagerHolder.clearAll();
        // 已预载的目标允许在冷却期后重新评估(清除 preloadedKey 标记,保留 gaveUp 判定)
        preloadedKey = null;
    }

    /** 播放页销毁:移除观察 + 释放引擎层 manager */
    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        evaluatePending.set(false);
        requestToken = null;
        activeSnapshot = null;
        clearCache();
        if (observing && sourceViewModel != null) {
            sourceViewModel.preloadResult.removeObserver(preloadResultObserver);
            observing = false;
        }
        PreloadManagerHolder.release();
    }

    private void evaluate(Snapshot snapshot) {
        if (!PreloadManagerHolder.enabled()) return;
        // 内核判定:预载数据仅 Exo 内核可消费(命中注入在 app ExoPlayer.prepareAsync),
        // IJK 下预载必然 miss=白下载占带宽,直接跳过(规格 §1「IJK 内核不参与预载」)
        if (!snapshot.exoKernel) {
            LOG.i("echo-preload-skip: non-exo kernel");
            return;
        }
        long cooldownRemain = bufferingCooldownUntil - System.currentTimeMillis();
        if (cooldownRemain > 0) {
            // 冷却中不放弃,按剩余时间重新排程(2026-09-12 修复 Bug):原先直接 return,而 dkplayer 的
            // STATE_PLAYING 只在首帧渲染时发一次、缓冲结束只到 STATE_BUFFERED,于是播放中 seek/再缓冲一次后,
            // 本集剩余时间预载永久不再启动。现在由 PlayContainer 在 STATE_BUFFERED 补一次评估、
            // 这里再负责"还没到点就接着等"。
            LOG.i("echo-preload-skip: buffering cooldown, retry in " + cooldownRemain + "ms");
            postEvaluate(snapshot, cooldownRemain);
            return;
        }
        if (snapshot.nextKey.equals(snapshot.currentKey)) return; // 无下一集(边界由调用方保证,双保险)
        if (snapshot.currentKey.equals(gaveUpKey)) return;        // 本集已判定不可预载
        if (snapshot.nextKey.equals(preloadedKey)) {              // 该目标已预载/预载中
            LOG.i("echo-preload-skip: already preloaded");
            return;
        }
        // 评估出不同目标:旧目标的预载数据已无用,清掉(换线/换源后集号未变但内容源可能变,同样重建)
        if (preloadedKey != null) {
            dropPreloadData();
        }
        if (snapshot.nextKey.equals(requestToken)) {              // 已有在途预解析
            LOG.i("echo-preload-skip: resolving in-flight");
            return;
        }
        // 下一集为荐片/迅雷等特殊协议时不预载(规格 §5.3,正片播放路径 L1911-1942 特殊分支)
        if (Jianpian.isJpUrl(snapshot.nextUrl)) {
            gaveUp(snapshot);
            return;
        }
        activeSnapshot = snapshot;
        // token 与预解析请求的 progressKey 一致(带 -preload 后缀),结果回来的 proKey 才能对上
        requestToken = snapshot.nextKey + PRELOAD_KEY_SUFFIX;
        LOG.i("echo-preload-resolve: " + snapshot.nextUrl);
        sourceViewModel.getPlayForPreload(
                snapshot.sourceKey,
                snapshot.playFlag,
                snapshot.nextKey + PRELOAD_KEY_SUFFIX,
                snapshot.nextUrl,
                snapshot.nextSubtitleKey + PRELOAD_KEY_SUFFIX);
    }

    private void handlePreloadResult(JSONObject info) {
        final Snapshot snapshot = activeSnapshot;
        String token = requestToken;
        requestToken = null;
        if (snapshot == null || token == null) {
            LOG.i("echo-preload-result-drop: no active snapshot (late result)");
            return;
        }
        // token 校验:迟到的结果(目标已变)直接丢弃
        if (info == null || !token.equals(info.optString("proKey", ""))) {
            LOG.i("echo-preload-giveup: stale result, target=" + token);
            gaveUp(snapshot);
            return;
        }
        // 可预载判定(规格 §5.3):全部满足才进入预载,否则静默放弃
        String msg = info.optString("msg", "");
        boolean parse = info.optString("parse", "1").equals("1");
        boolean jx = info.optString("jx", "0").equals("1");
        String playUrl = info.optString("playUrl", "");
        Object rawUrl = info.opt("url");
        // ⚠️ 不能写 String.valueOf(rawUrl):rawUrl 为 null 时得到的是字符串 "null",会绕过下面的判空,
        // 于是对 "null" 发起预载并把 preloadedKey 置位,本集再也不重试(2026-09-12 修复 Bug)
        String url = rawUrl instanceof org.json.JSONArray ? rawUrl.toString()
                : (rawUrl == null ? "" : String.valueOf(rawUrl));
        if (parse || jx || !playUrl.isEmpty() || !msg.isEmpty()
                || url.isEmpty()
                || url.startsWith("[")          // 数组多线路 url,主链路会 firstUrlByArray 提取,预载无法对齐
                || url.startsWith("data:application")
                || url.startsWith("tvbox-xg:")) {
            String reason = parse ? "parse=1" : jx ? "jx=1" : !playUrl.isEmpty() ? "playUrl=" + playUrl
                    : !msg.isEmpty() ? "msg=" + msg : url.isEmpty() ? "empty url"
                    : url.startsWith("[") ? "array url" : url.startsWith("data:application") ? "data: url" : "tvbox-xg";
            LOG.i("echo-preload-giveup: " + reason);
            gaveUp(snapshot);
            return;
        }
        // 本地代理地址(净化/DASH/spider 自建代理,如 127.0.0.1:9978/proxy?do=hmys)不可预载(规格 §5.3):
        // ①m3u8 地址被 base64 藏进参数,.m3u8 净化判定失效;②播放侧 attachProxySiteKey 只对本地代理 url
        // 追加 siteKey,预载侧拿不到同源地址,key 必然不一致(实测仅差 &siteKey= 一段→永不命中)
        if (isLocalProxyUrl(url)) {
            LOG.i("echo-preload-giveup: local proxy url");
            gaveUp(snapshot);
            return;
        }
        // M3U8 净化代理地址是动态 127.0.0.1 回填,预载链路拿不到,此类源排除(规格 §5.3)
        if (url.contains(".m3u8")
                && KV.get(HawkConfig.M3U8_PURIFY, false)
                && !DefaultConfig.noAd(snapshot.playFlag)) {
            LOG.i("echo-preload-giveup: m3u8 purify on, url=" + url);
            gaveUp(snapshot);
            return;
        }
        HashMap<String, String> headers = extractHeaders(info);
        // 预载起点:片头跳过 与 下一集历史进度 取较大(规格 §5.4,对齐 FongMi getStartPositionMs)
        long startPos = snapshot.startSkipMs;
        try {
            Object history = CacheManager.getCache(MD5.string2MD5(snapshot.nextKey));
            long rec = 0;
            if (history instanceof Long) {
                rec = (Long) history;
            } else if (history instanceof String) {
                rec = Long.parseLong((String) history);
            }
            startPos = Math.max(startPos, rec);
        } catch (Throwable ignored) {
        }
        preloadedKey = snapshot.nextKey;
        LOG.i("echo-preload-resolve-ok: " + url);
        // 缓存预解析结果供主链路切集复用:proKey/subtKey 替换回真实键,保证主链路状态(进度/字幕/弹幕)不串
        try {
            info.put("proKey", snapshot.nextKey);
            info.put("subtKey", snapshot.nextSubtitleKey);
        } catch (Throwable ignored) {
        }
        cachedInfo = info;
        cachedKey = snapshot.nextKey;
        cachedAt = System.currentTimeMillis();
        PreloadManagerHolder.preload(snapshot.context, url, headers, startPos);
    }

    /**
     * 主链路切集时消费预解析缓存:目标集匹配且在 TTL 内返回结果(info 的 proKey/subtKey 已是真实键),
     * 否则返回 null(主链路走正常 getPlay)。消费即清除。
     */
    public JSONObject consumeResult(String realKey) {
        if (cachedInfo == null || cachedKey == null) return null;
        if (!cachedKey.equals(realKey)) return null;
        if (System.currentTimeMillis() - cachedAt > CACHE_TTL_MS) {
            LOG.i("echo-preload-cache-expired: " + realKey);
            clearCache();
            return null;
        }
        JSONObject result = cachedInfo;
        clearCache();
        LOG.i("echo-preload-cache-hit: " + realKey);
        return result;
    }

    private void clearCache() {
        cachedInfo = null;
        cachedKey = null;
    }

    private void gaveUp(Snapshot snapshot) {
        gaveUpKey = snapshot.currentKey;
    }

    /** 本地代理地址判定(与控制管理器同源,127.0.0.1/localhost) */
    private static boolean isLocalProxyUrl(String url) {
        return url.startsWith("http://127.0.0.1") || url.startsWith("https://127.0.0.1")
                || url.startsWith("http://localhost") || url.startsWith("https://localhost");
    }

    private static HashMap<String, String> extractHeaders(JSONObject info) {
        if (info == null) return null;
        // 结果 JSON 的 header/headers 字段(与 PlayContainer.getHeaders 同语义)
        HashMap<String, String> headers = new HashMap<>();
        try {
            for (String field : new String[]{"header", "headers"}) {
                Object raw = info.opt(field);
                if (!(raw instanceof JSONObject)) continue;
                JSONObject json = (JSONObject) raw;
                java.util.Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (key != null && !key.isEmpty()) {
                        headers.put(key, json.optString(key, ""));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return headers.isEmpty() ? null : headers;
    }
}
