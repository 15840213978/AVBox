package xyz.doikki.videoplayer.exo;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import okhttp3.OkHttpClient;

public final class ExoMediaSourceHelper {
    public static final String HEADER_FORMAT = "TVBox-Format";
    /** MediaItem.requestMetadata.extras 中承载 http headers 的 key（HashMap&lt;String,String&gt;） */
    public static final String EXTRA_HEADERS = "avbox.extras.httpHeaders";

    private static ExoMediaSourceHelper sInstance;
    /** 进程级共享缓存(预载写盘与播放读盘共用同一实例,见 skill/avbox-preload-next-episode-spec.md §10) */
    private static volatile Cache sSharedCache;
    /** 共享缓存容量(字节,默认 512MB):须在 getSharedCache 首次创建前注入(设置改动→重启 App 生效) */
    private static volatile long sSharedCacheSizeBytes = 512L * 1024 * 1024;

    private final Context mAppContext;
    /** 显式 setCache 覆盖(默认 null = 使用共享单例) */
    private Cache mCache;
    private OkHttpClient mClient;

    private ExoMediaSourceHelper(Context context) {
        mAppContext = context.getApplicationContext();
    }

    public static ExoMediaSourceHelper getInstance(Context context) {
        if (sInstance == null) {
            synchronized (ExoMediaSourceHelper.class) {
                if (sInstance == null) {
                    sInstance = new ExoMediaSourceHelper(context);
                }
            }
        }
        return sInstance;
    }

    public void setOkClient(OkHttpClient client) {
        mClient = client;
    }

    public MediaSource getMediaSource(String uri) {
        return getMediaSource(uri, null, false);
    }

    public MediaSource getMediaSource(String uri, Map<String, String> headers) {
        return getMediaSource(uri, headers, false);
    }

    public MediaSource getMediaSource(String uri, boolean isCache) {
        return getMediaSource(uri, null, isCache);
    }

    public MediaSource getMediaSource(String uri, Map<String, String> headers, boolean isCache) {
        return getMediaSource(uri, headers, isCache, inferContentType(uri, headers));
    }

    public MediaSource getHlsMediaSource(String uri, Map<String, String> headers) {
        return getMediaSource(uri, headers, false, C.TYPE_HLS);
    }

    private MediaSource getMediaSource(String uri, Map<String, String> headers, boolean isCache, int contentType) {
        Uri contentUri = Uri.parse(uri);
        if ("rtsp".equals(contentUri.getScheme())) {
            return new RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(contentUri));
        }
        MediaItem mediaItem = buildMediaItem(uri, headers);
        DataSource.Factory factory = createDataSourceFactory(mediaItem);
        if (isCache) {
            // headers 取自 MediaItem(已归一化:过滤内部标记/trim),预载侧与播放侧 key 同源
            factory = getCacheDataSourceFactory(factory, getHeadersFrom(mediaItem));
        }
        switch (contentType) {
            case C.TYPE_DASH:
                return new DashMediaSource.Factory(factory).createMediaSource(mediaItem);
            case C.TYPE_HLS:
                return new HlsMediaSource.Factory(factory)
                        .setLoadErrorHandlingPolicy(new HlsErrorHandlingPolicy())  // 设置自定义错误处理策略，跳过坏的切片
                        .createMediaSource(mediaItem);
            default:
            case C.TYPE_OTHER:
                return new ProgressiveMediaSource.Factory(factory).createMediaSource(mediaItem);
        }
    }

    /**
     * 统一的 MediaItem 构建入口：headers 写入 requestMetadata.extras，播放与预载共用同一 header 语义。
     */
    public static MediaItem buildMediaItem(String uri, Map<String, String> headers) {
        Bundle extras = new Bundle();
        extras.putSerializable(EXTRA_HEADERS, toRequestHeaders(headers));
        MediaItem.RequestMetadata requestMetadata = new MediaItem.RequestMetadata.Builder()
                .setMediaUri(Uri.parse(uri))
                .setExtras(extras)
                .build();
        return new MediaItem.Builder()
                .setUri(uri)
                .setRequestMetadata(requestMetadata)
                .build();
    }

    /**
     * 从 buildMediaItem 构建的 MediaItem 中取回 headers（未携带时返回 null）。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> getHeadersFrom(MediaItem mediaItem) {
        if (mediaItem.requestMetadata == null || mediaItem.requestMetadata.extras == null) {
            return null;
        }
        Object stored = mediaItem.requestMetadata.extras.getSerializable(EXTRA_HEADERS);
        return stored instanceof Map ? (Map<String, String>) stored : null;
    }

    /**
     * 从 MediaItem 的 requestMetadata.extras 构建 per-item DataSource factory。
     * 不再复用全局共享 factory，避免多次构建 MediaSource 时 headers 相互覆盖。
     */
    @SuppressWarnings("unchecked")
    public DataSource.Factory createDataSourceFactory(MediaItem mediaItem) {
        Map<String, String> headers = getHeadersFrom(mediaItem);
        if (headers == null) {
            headers = Collections.emptyMap();
        }
        String userAgent = null;
        Map<String, String> requestHeaders = new HashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if ("User-Agent".equalsIgnoreCase(entry.getKey())) {
                userAgent = entry.getValue();
            } else {
                requestHeaders.put(entry.getKey(), entry.getValue());
            }
        }
        OkHttpClient client = mClient != null ? mClient : FallbackClient.INSTANCE;
        OkHttpDataSource.Factory httpFactory = new OkHttpDataSource.Factory(client);
        httpFactory.setUserAgent(userAgent);
        httpFactory.setDefaultRequestProperties(requestHeaders);
        return new DefaultDataSource.Factory(mAppContext, httpFactory);
    }

    /**
     * 兜底 OkHttpClient(2026-09-12):仅在 {@code OkGoHelper.init()} 未注入共享 client 时使用。
     * 原先此处每次 `new OkHttpClient.Builder().build()` —— 每个 MediaSource 各带一套 Dispatcher/ConnectionPool,
     * 丢失连接与 TLS 复用(HLS 多分片时明显更慢、更耗电)。改为类持有式懒加载单例:天然线程安全,无需 volatile。
     */
    private static final class FallbackClient {
        static final OkHttpClient INSTANCE = new OkHttpClient.Builder().build();
    }

    /**
     * 过滤内部标记与空键值，保留 UA 在 map 内（由 createDataSourceFactory 拆分处理）。
     */
    private static HashMap<String, String> toRequestHeaders(Map<String, String> headers) {
        HashMap<String, String> requestHeaders = new HashMap<>();
        if (headers == null) {
            return requestHeaders;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) {
                continue;
            }
            if (HEADER_FORMAT.equalsIgnoreCase(key)) {
                continue;
            }
            requestHeaders.put(key, value.trim());
        }
        return requestHeaders;
    }

    private int inferContentType(String fileName, Map<String, String> headers) {
        int formatType = inferFormatContentType(headers);
        if (formatType != C.TYPE_OTHER) {
            return formatType;
        }
        fileName = fileName.toLowerCase();
        if (fileName.contains(".mpd") || fileName.contains("type=mpd") || fileName.contains("type=dash") || fileName.contains("format=mpd") || fileName.contains("format=dash")) {
            return C.TYPE_DASH;
        } else if (isHlsUri(fileName)) {
            return C.TYPE_HLS;
        } else {
            return C.TYPE_OTHER;
        }
    }

    private int inferFormatContentType(Map<String, String> headers) {
        if (headers == null || !headers.containsKey(HEADER_FORMAT)) {
            return C.TYPE_OTHER;
        }
        String format = headers.get(HEADER_FORMAT);
        if (format == null) {
            return C.TYPE_OTHER;
        }
        format = format.trim().toLowerCase();
        if (format.equals("hls") || format.contains("mpegurl") || format.contains("m3u8")) {
            return C.TYPE_HLS;
        }
        if (format.equals("dash") || format.equals("mpd") || format.contains("dash+xml")) {
            return C.TYPE_DASH;
        }
        return C.TYPE_OTHER;
    }

    private boolean isHlsUri(String uri) {
        if (isAudioUri(uri)) {
            return false;
        }
        if (uri.contains("m3u8") || uri.contains("type=hls") || uri.contains("format=hls")) {
            return true;
        }
        Uri parsedUri = Uri.parse(uri);
        String path = parsedUri.getPath();
        if (path == null) {
            return false;
        }
        path = path.toLowerCase();
        return path.endsWith("/live.php") || path.contains("/live/");
    }

    private boolean isAudioUri(String uri) {
        Uri parsedUri = Uri.parse(uri);
        String path = parsedUri.getPath();
        if (path == null) {
            path = uri;
        }
        path = path.toLowerCase();
        return path.endsWith(".mp3")
                || path.endsWith(".m4a")
                || path.endsWith(".aac")
                || path.endsWith(".flac")
                || path.endsWith(".wav")
                || path.endsWith(".ogg")
                || path.endsWith(".opus")
                || path.endsWith(".amr");
    }

    /**
     * 边播缓存数据源(2026-09-13 修复「跨线路串缓存」):
     * media3 默认的 CacheKeyFactory 只认 dataSpec.key/uri —— 同一 URL 配不同 Referer/UA/token
     * 的源会互相读到对方写到盘上的数据;此处改为「分片 uri + 规范化 headers」作为 key。
     *
     * <p>两侧一致性(预载写盘 / 播放读盘):预载侧 {@code PreloadMediaSourceFactory} 与播放侧
     * 都经 {@link #getMediaSource(String, Map, boolean)} → 本方法,headers 均取自
     * {@link #getHeadersFrom(MediaItem)}(已过滤 HEADER_FORMAT 等内部标记),故 key 完全同源;
     * 规范化规则(排序/trim/大小写不敏感)与预载侧 {@code PreloadManagerHolder.keyOf} 保持一致。
     *
     * <p>无 headers 时保持 media3 默认行为(key=uri),不改变原有命中语义。
     */
    private DataSource.Factory getCacheDataSourceFactory(DataSource.Factory upstream, Map<String, String> headers) {
        Cache cache = mCache != null ? mCache : getSharedCache(mAppContext);
        CacheDataSource.Factory factory = new CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        final String keySuffix = headerKeySuffix(headers);
        if (!keySuffix.isEmpty()) {
            factory.setCacheKeyFactory(dataSpec -> dataSpec.uri + keySuffix);
        }
        return factory;
    }

    /** headers → 磁盘缓存 key 后缀(格式与 PreloadManagerHolder.keyOf 一致:排序 + trim + 大小写不敏感) */
    private static String headerKeySuffix(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return "";
        }
        Map<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                sorted.put(entry.getKey().trim(), entry.getValue().trim());
            }
        }
        if (sorted.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            sb.append('\n').append(entry.getKey()).append(':').append(entry.getValue()).append(';');
        }
        return sb.toString();
    }

    /**
     * 进程级共享 SimpleCache(2026-09-12 预载第二期):
     * ①预载侧(PreloadMediaSource 的数据源)与播放侧(CacheDataSource)共用同一实例——
     *   预载下载的数据落盘后被播放侧直接读盘命中,SimpleCache 同一目录也不允许多实例;
     * ②容量 LRU 512MB(沿用历史值),目录 externalCacheDir/exo-video-cache(沿用历史路径)。
     */
    public static Cache getSharedCache(Context context) {
        if (sSharedCache == null) {
            synchronized (ExoMediaSourceHelper.class) {
                if (sSharedCache == null) {
                    Context appContext = context.getApplicationContext();
                    sSharedCache = new SimpleCache(
                            new File(externalCacheDir(appContext), "exo-video-cache"),
                            new LeastRecentlyUsedCacheEvictor(sSharedCacheSizeBytes),
                            new StandaloneDatabaseProvider(appContext));
                }
            }
        }
        return sSharedCache;
    }

    /** 注入共享缓存容量(字节);仅影响尚未创建的缓存实例(设置改动需重启 App 生效) */
    public static void setSharedCacheSizeBytes(long bytes) {
        if (bytes > 0) {
            sSharedCacheSizeBytes = bytes;
        }
    }

    private static File externalCacheDir(Context context) {
        File externalCacheDir = context.getExternalCacheDir();
        if (externalCacheDir == null){
            externalCacheDir = context.getCacheDir();
        }
        return externalCacheDir;
    }

    /** 显式覆盖缓存实例(测试/多实例场景);默认走 {@link #getSharedCache} */
    public void setCache(Cache cache) {
        this.mCache = cache;
    }
}
