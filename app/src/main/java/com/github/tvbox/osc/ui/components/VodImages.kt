package com.github.tvbox.osc.ui.components

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.crossfade
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * Coil 图片管线:TVBox 海报地址约定(url@Headers={...}、@Cookie=、@User-Agent=、@Referer=)
 * 由 OkHttp 拦截器剥离附加参数并注入请求头,行为与旧 ImgUtil.getImageModel 一致。
 * Coil 不感知该约定,必须在网络层处理(3.6 无逐请求 header API)。
 */
object VodImages {

    private const val PIC_HTTP_CACHE_MB = 250L
    private var picCacheDir: File? = null

    fun init(context: Context) {
        picCacheDir = File(context.cacheDir, "pic_http_cache")
        SingletonImageLoader.setSafe { appContext ->
            ImageLoader.Builder(appContext)
                .components {
                    // OkHttpNetworkFetcher 被 Kotlin 层 HIDDEN,经 Java 桥注册以注入海报请求头拦截器
                    add(com.github.tvbox.osc.util.CoilBridge.okhttpFetcher { picClient() })
                }
                .crossfade(true)
                .build()
        }
    }

    /**
     * 海报提速(2026-09-12 用户定稿,FongMi/Glide 默认行为对齐):HTTP 磁盘缓存 + 改写禁缓存头。
     * TVBox 图床普遍返回 no-cache/no-store/Pragma 禁缓存,coil3 又无 respectCacheHeaders API
     * (coil2 遗留项已移除)→ 在 OkHttp 网络层把禁缓存响应强制改写为「public, max-age=7 天」,
     * 同 URL 二次请求(再次进 App/切回源/滚动回看)直接磁盘命中,零网络。
     * 风险:源站原地换图且 URL 不变时显示旧图,最长 7 天(URL 带签名/变化时不受影响)。
     */
    private fun picClient(): OkHttpClient = OkHttpClient.Builder()
        .cache(picCacheDir?.let { Cache(it, PIC_HTTP_CACHE_MB * 1024L * 1024L) })
        .addInterceptor(picHeaderInterceptor)
        .addNetworkInterceptor(picForceCacheInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val picForceCacheInterceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val cc = response.header("Cache-Control")
        if (cc != null && (cc.contains("no-store") || cc.contains("no-cache") || cc.contains("max-age=0"))) {
            response.newBuilder()
                .removeHeader("Cache-Control")
                .removeHeader("Pragma")
                .header("Cache-Control", "public, max-age=604800")
                .build()
        } else {
            response
        }
    }

    private val picHeaderInterceptor = Interceptor { chain ->
        val request = chain.request()
        val parsed = parseVodPicUrl(request.url.toString())
        if (parsed == null) {
            chain.proceed(request)
        } else {
            val (url, headers) = parsed
            val newRequest = request.newBuilder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            chain.proceed(newRequest)
        }
    }

    /** 返回 null 表示无需改写(data: 直传,或不含 @ 附加参数) */
    private fun parseVodPicUrl(raw: String): Pair<String, Map<String, String>>? {
        if (raw.startsWith("data:") || !raw.contains('@')) return null

        fun grab(key: String): String? =
            if (raw.contains("$key=")) raw.split("$key=")[1].split("@")[0] else null

        val url = raw.split("@")[0]
        if (url.isEmpty()) return null

        val headers = LinkedHashMap<String, String>()
        fun put(k: String, v: String?) {
            if (!v.isNullOrEmpty()) headers[k] = v
        }
        // okhttp 已对 URL 中的附加参数做百分号编码,先还原再按 JSON 解析
        grab("@Headers")?.let { json ->
            try {
                val decoded = URLDecoder.decode(json, "UTF-8")
                val obj = Gson().fromJson(decoded, JsonObject::class.java)
                for (key in obj.keySet()) put(key, obj.get(key).asString)
            } catch (_: Throwable) {
            }
        }
        put("Cookie", grab("@Cookie"))
        put("User-Agent", grab("@User-Agent"))
        put("Referer", grab("@Referer"))
        return url to headers
    }
}
